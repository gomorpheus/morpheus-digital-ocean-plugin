package com.morpheusdata.digitalocean.cloud.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * @author rahul.ray
 */

@Slf4j
class VirtualMachineSync {
    private Cloud cloud
    DigitalOceanPlugin plugin
    DigitalOceanApiService apiService
    private MorpheusContext morpheusContext
    private Map<String, ServicePlanIdentityProjection> servicePlans
    private Map<String, CloudPoolIdentity> zonePools
    private Map<String, OsType> osTypes
    private Map<String, ComputeServerType> computeServerTypes
    private Boolean createNew
    private CloudProvider cloudProvider

    VirtualMachineSync(DigitalOceanPlugin plugin, Cloud cloud, DigitalOceanApiService apiService, CloudProvider cloudProvider) {
        this.plugin = plugin
        this.cloud = cloud
        this.apiService = apiService
        this.morpheusContext = plugin.morpheusContext
        createNew = false
        this.@cloudProvider = cloudProvider
    }

    def execute() {
        try {
            def inventoryLevel = cloud.getConfigProperty('importExisting')
            if (inventoryLevel == 'on' || inventoryLevel == 'true' || inventoryLevel == true) {
                createNew = true
            }
            String datacenter = cloud.configMap.datacenter
            String vpc = cloud.configMap.vpc
            String apiKey = plugin.getAuthConfig(cloud).doApiKey

            ServiceResponse response = apiService.getAllDropletsForDataCenter(apiKey, datacenter, vpc)
            if (response.success) {
                removeDuplicateVirtualMachines(datacenter)
                def usageLists = [restartUsageIds: [], stopUsageIds: [], startUsageIds: [], updatedSnapshotIds: []]

                def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null)
                def blackListedNames = domainRecords.filter { it.status == 'provisioning' }.map { it.name }.toList().blockingGet()

                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, response.data as Collection<Map>)
                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                    domainObject.externalId == cloudItem.id.toString()
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
                    morpheusContext.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
                        SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
                        return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
                    }
                }.onAdd { itemsToAdd ->
                    if (createNew) {
                        addMissingVirtualMachines(itemsToAdd, blackListedNames, datacenter, usageLists)
                    }
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateMatchedVirtualMachines(updateItems)
                }.onDelete { removeItems ->
                    removeMissingVirtualMachines(removeItems)
                }.observe().blockingSubscribe()
            } else {
                log.error("Error Caching VMs for datacenter: {}", datacenter)
            }
        } catch (Exception ex) {
            log.error("VirtualMachineSync error: {}", ex, ex)
            log.info("VirtualMachineSync error: {}", ex, ex.getMessage())
        }
    }

    void    updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList) {
        List<ComputeServer> saves = []

        for (update in updateList) {
            ComputeServer currentServer = update.existingItem
            Map cloudItem = update.masterItem
            if (currentServer.status != 'provisioning') {
                try {
                    def save = false
                    def name = cloudItem.name
                    def zonePool = allZonePools[cloudItem.vpc_uuid]
                    def powerState = cloudItem?.status == 'active' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
                    if (!currentServer.computeServerType) {
                        currentServer.computeServerType = allComputeServerTypes['digitalOceanUnmanaged']
                        save = true
                    }
                    if (currentServer.region?.externalId != zonePool.externalId) {
                        currentServer.region = new CloudRegion(id: zonePool.id)
                        save = true
                    }
                    if (name != currentServer.name) {
                        currentServer.name = name
                        save = true
                    }
                    if (currentServer.resourcePool?.id != zonePool?.id) {
                        currentServer.resourcePool = zonePool?.id ? new CloudPool(id: zonePool.id) : null
                        save = true
                    }
                    def publicIpAddress = cloudItem.networks.v4?.find { it.type == "public" }?.ip_address
                    if (currentServer.externalIp != publicIpAddress) {
                        if (currentServer.externalIp == currentServer.sshHost) {
                            if (publicIpAddress) {
                                currentServer.sshHost = publicIpAddress
                            } else if (powerState == ComputeServer.PowerState.off) {
                                currentServer.sshHost = currentServer.internalIp
                            } else {
                                currentServer.sshHost = null
                            }
                        }
                        currentServer.externalIp = publicIpAddress
                        save = true
                    }
                    def privateIpAddress = cloudItem.networks.v4?.find { it.type == "private" }?.ip_address
                    if (currentServer.internalIp != privateIpAddress) {
                        if (currentServer.internalIp == currentServer.sshHost) {
                            currentServer.sshHost = privateIpAddress
                        }
                        currentServer.internalIp = privateIpAddress
                        save = true
                    }

                    if (powerState != currentServer.powerState) {
                        currentServer.powerState = powerState
                        save = true
                    }

                    def maxMemory = cloudItem.memory * ComputeUtility.ONE_MEGABYTE
                    if(currentServer.maxMemory != maxMemory) {
                        currentServer.maxMemory = maxMemory
                        save = true
                    }
                   /* def maxCores = (cloudItem.vcpus?.toLong() ?: 0) * (cloudItem.disk?.toLong() ?: 0)
                    if(currentServer.maxCores != maxCores) {
                        currentServer.maxCores = maxCores
                        save = true
                    }*/
                    def maxStorage = cloudItem.disk * ComputeUtility.ONE_GIGABYTE
                    if(currentServer.maxStorage != maxStorage) {
                        currentServer.maxStorage = maxStorage
                        save = true
                    }

                    // Set the plan on the server
                    if (currentServer.plan?.code != "digitalocean.size.${cloudItem.size_slug}") {
                        ServicePlan servicePlan = digitalOceanServicePlans["digitalocean.size.${cloudItem.size_slug}".toString()]
                        if (servicePlan) {
                            applyServicePlan(currentServer, servicePlan)
                            save = true
                        }
                    }
                    if (save) {
                        saves << currentServer
                    }
                } catch (e) {
                    log.warn("Error Updating Virtual Machine ${currentServer?.name} - ${currentServer.externalId} - ${e}", e)
                }
            }
        }
        if (saves) {
            morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
        }
    }

    def removeDuplicateVirtualMachines(String region) {
        Map<String, List<ComputeServerIdentityProjection>> vmsByExternalId = [:]
        List<ComputeServer> removeList = []
        for (def existingItem : morpheusContext.async.computeServer.listIdentityProjections(cloud.id, region).toList().blockingGet()) {
            if (!vmsByExternalId[existingItem.externalId]) {
                vmsByExternalId[existingItem.externalId] = []
            }
            vmsByExternalId[existingItem.externalId] << existingItem
        }
        List<ComputeServer> dupList = morpheusContext.services.computeServer.listById(vmsByExternalId.values().findAll { it.size() > 1 }.collect { it.id }.flatten())
        vmsByExternalId = [:]

        for (ComputeServer server : dupList) {
            if (!vmsByExternalId[server.externalId]) {
                vmsByExternalId[server.externalId] = []
            }
            vmsByExternalId[server.externalId] << server
        }
        for (String externalId : vmsByExternalId.keySet()) {
            List<ComputeServer> dups = vmsByExternalId[externalId]
            ComputeServer keep = dups.find { it.iacId } ?: dups.sort { it.id }.first()

            for (ComputeServer server : dups) {
                if (server != keep) {
                    removeList << server
                }
            }
        }
        if (removeList) {
            removeMissingVirtualMachines(removeList)
        }
    }

    def removeMissingVirtualMachines(List<ComputeServerIdentityProjection> removeList) {
        log.debug "removeMissingVirtualMachines: ${cloud} ${removeList.size()}"
        morpheusContext.async.computeServer.remove(removeList).blockingGet()
    }

    def addMissingVirtualMachines(List addList, List blackListedNames, String region, Map usageLists) {
        log.debug "addMissingVirtualMachines ${cloud} ${region} ${addList?.size()} ${blackListedNames} ${usageLists}"
        if (!createNew)
            return
        for (cloudItem in addList) {
            try {
                def servicePlan = cloudItem ? digitalOceanServicePlans["digitalocean.size.${cloudItem.size_slug}".toString()] : null
                def zonePool = allZonePools[cloudItem.vpc_uuid]
                def doCreate = zonePool?.inventory != false && !blackListedNames?.contains(cloudItem.name)
                if (doCreate) {
                    def vmConfig = buildVmConfig(cloudItem, zonePool)
                    ComputeServer add = new ComputeServer(vmConfig)
                    if (servicePlan) {
                        applyServicePlan(add, servicePlan)
                    }
                    ComputeServer savedServer = morpheusContext.async.computeServer.create(add).blockingGet()
                    if (!savedServer) {
                        log.error "Error in creating server ${add}"
                    } else {
                        performPostSaveSync(savedServer)
                    }
                    if (vmConfig.powerState == ComputeServer.PowerState.on) {
                        usageLists.startUsageIds << savedServer.id
                    } else {
                        usageLists.stopUsageIds << savedServer.id
                    }
                }
            } catch (e) {
                log.error "Error in adding VM ${e}", e
            }
        }
    }

    private buildVmConfig(Map cloudItem, CloudPoolIdentity zonePool) {
        def computeServerType = cloudProvider.computeServerTypes.find {
            it.code == 'digitalOceanUnmanaged'
        }
        def privateIpAddress = cloudItem.networks.v4?.find { it.type == "private" }?.ip_address
        def publicIpAddress = cloudItem.networks.v4?.find { it.type == "public" }?.ip_address
        def osType = cloudItem.image?.distribution?.toLowerCase()?.contains('windows') ? 'windows' : 'linux'
        def vmConfig = [
                account          : cloud.account,
                externalId       : cloudItem.id,
                name             : cloudItem.name,
                resourcePool     : zonePool ? new CloudPool(id: zonePool?.id) : null,
                externalIp       : publicIpAddress,
                internalIp       : privateIpAddress,
                sshHost          : publicIpAddress,
                sshUsername      : 'root',
                provision        : false,
                cloud            : cloud,
                lvmEnabled       : false,
                managed          : false,
                serverType       : 'vm',
                status           : 'provisioned',
                uniqueId         : cloudItem.id,
                powerState       : cloudItem.status == 'active' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
                maxMemory        : cloudItem.memory * ComputeUtility.ONE_MEGABYTE,
                maxCores         : (cloudItem.vcpus?.toLong() ?: 0) * (cloudItem.disk?.toLong() ?: 0),
                coresPerSocket   : 1l,
                osType           : osType,
                osDevice         : '/dev/vda',
                serverOs         : findOsMatch(cloudItem.image?.distribution, osType),
                apiKey           : java.util.UUID.randomUUID(),
                discovered       : true,
                region           : zonePool ? new CloudRegion(id: zonePool?.id) : null,
                computeServerType: computeServerType,
                category         : "digitalocean.vm.${cloud.id}"
        ]
        vmConfig
    }

    private applyServicePlan(ComputeServer server, ServicePlan servicePlan) {
        server.plan = servicePlan
        server.maxCores = servicePlan.maxCores
        server.maxCpu = servicePlan.maxCpu
        server.maxMemory = servicePlan.maxMemory
        if (server.computeCapacityInfo) {
            server.computeCapacityInfo.maxCores = server.maxCores
            server.computeCapacityInfo.maxCpu = server.maxCpu
            server.computeCapacityInfo.maxMemory = server.maxMemory
        }
    }

    private Boolean performPostSaveSync(ComputeServer server) {
        log.debug "performPostSaveSync: ${server?.id}"
        def changes = false
        // Disks and metrics
        if (server.status != 'resizing') {
            if (!server.computeCapacityInfo) {
                server.capacityInfo = new ComputeCapacityInfo(maxCores: server.maxCores, maxMemory: server.maxMemory, maxStorage: server.maxStorage)
                changes = true
            }
        }
        if (changes) {
            saveAndGet(server)
        }
        return changes
    }

    protected ComputeServer saveAndGet(ComputeServer server) {
        def saveSuccessful = morpheusContext.async.computeServer.save([server]).blockingGet()
        if (!saveSuccessful) {
            log.warn("Error saving server: ${server?.id}")
        }
        return morpheusContext.async.computeServer.get(server.id).blockingGet()
    }


    private Map<String, ServicePlan> getDigitalOceanServicePlans() {
        servicePlans ?: (servicePlans = morpheusContext.async.servicePlan.list(new DataQuery().withFilter('provisionTypeCode', 'digitalocean')).toMap { it.code }.blockingGet())
    }

    private Map<String, CloudPoolIdentity> getAllZonePools() {
        zonePools ?: (zonePools = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', cloud.configMap.datacenter).toMap { it.externalId }.blockingGet())
    }

    private Map<String, ComputeServerType> getAllComputeServerTypes() {
        computeServerTypes ?: (computeServerTypes = morpheusContext.async.cloud.getComputeServerTypes(cloud.id).blockingGet().collectEntries { [it.code, it] })
    }

    private Map<String, OsType> getAllOsTypes() {
        osTypes ?: (osTypes = morpheusContext.async.osType.listAll().toMap {it.name.toLowerCase()}.blockingGet())
    }

    def findOsMatch(distribution, osType) {
        allOsTypes[distribution.toLowerCase()] ?: allOsTypes[osType] ?: new OsType(code:'unknown')
    }
}
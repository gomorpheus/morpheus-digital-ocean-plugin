package com.morpheusdata.digitalocean.cloud.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

@Slf4j
class VPCSync {

    private Cloud cloud
    private MorpheusContext morpheusContext
    DigitalOceanApiService apiService
    DigitalOceanPlugin plugin

    public VPCSync(DigitalOceanPlugin plugin, Cloud cloud, DigitalOceanApiService apiService) {
        this.plugin = plugin
        this.cloud = cloud
        this.morpheusContext = this.plugin.morpheusContext
        this.apiService = apiService
    }

    def execute() {
        log.info("BEGIN: execute ClustersSync: ${cloud.id}")
        try {
            String apiKey = plugin.getAuthConfig(cloud).doApiKey
            String datacenter = cloud.configMap.datacenter
            def vpcs = apiService.listVpcs(apiKey, datacenter)
            def vpcMap =  vpcs.data as Collection<Map>
            log.info("Anant VPC data as Map : ${vpcMap}")
            if(vpcs.success) {
                Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, null, datacenter)
                SyncTask<CloudPoolIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, vpcs.data as Collection<Map>)
                log.info("RAZI VPC DATA: ${vpcs.data}")
                syncTask.addMatchFunction { CloudPoolIdentity domainObject, Map apiItem ->
                    domainObject.externalId == apiItem.id
                }.onDelete { removeItems ->
                    removeMissingResourcePools(removeItems)
                }.onUpdate { List<SyncTask.UpdateItem<CloudPool, Map>> updateItems ->
                    updateMatchedVpcs(updateItems, datacenter)
                }.onAdd { itemsToAdd ->
                    log.info("Anant Items to add: ${itemsToAdd}")
                    addMissingVpcs(itemsToAdd, datacenter)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>).map {CloudPool cloudPool ->
                        SyncTask.UpdateItemDto<CloudPool, Map> matchItem = updateItemMap[cloudPool.id]
                        return new SyncTask.UpdateItem<CloudPool,Map>(existingItem:cloudPool, masterItem:matchItem.masterItem)
                    }
                }.start()
            }
        } catch(e) {
            log.error "Error in execute : ${e}", e
        }
        log.info("END: execute ClustersSync: ${cloud.id}")
    }

    def addMissingVpcs(Collection<Map> addList, String region) {
        def adds = []

        log.info("RAZI ADD LIST: ${addList}")

        for(Map cloudItem in addList) {
            //def clusterData = cloudItem.status
            def poolConfig = [
                    owner     : [id:cloud.owner.id],
                    type      : 'vpc',
                    name      : cloudItem.name,
                    displayName:"${cloudItem.name} (${region})",
                    description:"${cloudItem.name} - ${cloudItem.id}",
                    externalId: cloudItem.id,
                    uniqueId  : cloudItem.id,
                    internalId: cloudItem.name,
                    refType   : 'ComputeZone',
                    refId     : cloud.id,
                    regionCode: region,
                    cloud     : [id:cloud.id],
                    category  : "digitalocean.${cloud.id}.vpc",
                    code      : "digitalocean.${cloud.id}.vpc.${cloudItem.id}",
                    readOnly  : true
            ]
            log.info("RAZI POOL CONFIG: ${poolConfig}")

            def add = new CloudPool(poolConfig)
            adds << add
        }

        if(adds) {
            morpheusContext.async.cloud.pool.create(adds).blockingGet()
        }
    }

    private updateMatchedVpcs(List<SyncTask.UpdateItem<CloudPool, Map>> updateList, String region) {
        log.info "RAZI updateMatchedVpcs: ${cloud} ${updateList.size()}"
        def updates = []

        for(update in updateList) {
            def masterItem = update.masterItem
            def existing = update.existingItem
            Boolean save = false
            log.info("RAZI EXISTING: ${existing.name}")
            log.info("RAZI MASTER: ${masterItem.name}")
            log.info("RAZI CLOUD REGION: ${existing.regionCode}")
            log.info("RAZI REGION: ${region}")
            if(existing.name != masterItem.name) {
                existing.name = masterItem.name
                save = true
            }
            if(region && existing.regionCode != region) {
                existing.regionCode = region
                save = true
            }
            if(save) {
                updates << existing
            }
        }
        if(updates) {
            morpheusContext.async.cloud.pool.save(updates).blockingGet()
        }
    }
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//    def execute() {
//        try {
//            morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).concatMap {
//                final String regionCode = it.externalId
//                def amazonClient = plugin.getAmazonClient(cloud,false,it.externalId)
//                def vpcResults = AmazonComputeUtility.listVpcs([amazonClient: amazonClient])
//                if(vpcResults.success) {
//                    Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, null, regionCode)
//                    SyncTask<CloudPoolIdentity, Vpc, CloudPool> syncTask = new SyncTask<>(domainRecords, vpcResults.vpcList as Collection<Vpc>)
//                    return syncTask.addMatchFunction { CloudPoolIdentity domainObject, Vpc data ->
//                        domainObject.externalId == data.getVpcId()
//                    }.onDelete { removeItems ->
//                        removeMissingResourcePools(removeItems)
//                    }.onUpdate { List<SyncTask.UpdateItem<CloudPool, Vpc>> updateItems ->
//                        updateMatchedVpcs(updateItems,regionCode)
//                    }.onAdd { itemsToAdd ->
//                        addMissingVpcs(itemsToAdd, regionCode)
//                    }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<CloudPoolIdentity, Vpc>> updateItems ->
//                        return morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>)
//                    }.observe()
//                } else {
//                    log.error("Error Caching VPCs for Region: {} - {}",regionCode,vpcResults.msg)
//                    return Single.just(false).toObservable() //ignore invalid region
//                }
//            }.blockingSubscribe()
//        } catch(Exception ex) {
//            log.error("VPCSync error: {}", ex, ex)
//        }
//    }
//    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//    def execute() {
//        try {
//            morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).concatMap {
//                final String regionCode = it.externalId
////                def amazonClient = plugin.getAmazonClient(cloud,false,it.externalId)
////                def vpcResults = AmazonComputeUtility.listVpcs([amazonClient: amazonClient])
//                String apiKey = plugin.getAuthConfig(cloud).doApiKey
//                String datacenter = cloud.configMap.datacenter
//                def vpcResults = apiService.listVpcs(apiKey, datacenter)
//                vpcResults
//                if(vpcResults.success) {
//                    Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, null, regionCode)
//                    SyncTask<CloudPoolIdentity, DigitalOceanApiService, CloudPool> syncTask = new SyncTask<>(domainRecords, vpcResults.data as Collection<DigitalOceanApiService>)
//                    return syncTask.addMatchFunction { CloudPoolIdentity domainObject, DigitalOceanApiService data ->
//                        domainObject.externalId == data.getVpcId()
//                    }.onDelete { removeItems ->
//                        removeMissingResourcePools(removeItems)
//                    }.onUpdate { List<SyncTask.UpdateItem<CloudPool, Vpc>> updateItems ->
//                        updateMatchedVpcs(updateItems,regionCode)
//                    }.onAdd { itemsToAdd ->
//                        addMissingVpcs(itemsToAdd, regionCode)
//                    }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<CloudPoolIdentity, Vpc>> updateItems ->
//                        return morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>)
//                    }.observe()
//                } else {
//                    log.error("Error Caching VPCs for Region: {} - {}",regionCode,vpcResults.msg)
//                    return Single.just(false).toObservable() //ignore invalid region
//                }
//            }.blockingSubscribe()
//        } catch(Exception ex) {
//            log.error("VPCSync error: {}", ex, ex)
//        }
//    }

//    final String datacenter = cloud.configMap.datacenter
//
//    def execute() {
//        log.debug("VPCSync execute: ${cloud}")
//        try {
////            final String datacenter = cloud.configMap.datacenter
//            log.info("RAZI DATACENTER: $datacenter")
//            def vpcs = listVPCs()
//            if (vpcs?.size() > 0) {
//                Observable<ReferenceDataSyncProjection> domainReferenceData = morpheusContext.referenceData.listByCategory(generateCategoryForCloud(cloud))
//                SyncTask<ReferenceDataSyncProjection, ReferenceData, ReferenceData> syncTask = new SyncTask(domainReferenceData, vpcs)
//                syncTask.addMatchFunction { ReferenceDataSyncProjection projection, ReferenceData apiVpc ->
//                    projection.externalId == apiVpc.keyValue
//                }.onDelete { List<ReferenceDataSyncProjection> deleteList ->
//                    morpheusContext.referenceData.remove(deleteList)
//                }.onAdd { createList ->
//                    morpheusContext.referenceData.create(createList).blockingGet()
//                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, ReferenceData>> updateItems ->
//                    Map<Long, SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
//                    morpheusContext.async.referenceData.listById(updateItems.collect { it.existingItem.id } as List<Long>).map { ReferenceData vpc ->
//                        SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map> matchItem = updateItemMap[vpc.id]
//                        return new SyncTask.UpdateItem<ServicePlan,Map>(existingItem:vpc, masterItem:matchItem.masterItem)
//                    }
//                }.onUpdate { updateList ->
//                    updateVpcs(updateList, datacenter)
//                }.start()
//            }
//        } catch(e) {
//            log.error("Error in execute : ${e}", e)
//        }
//    }

//    void updateVpcs(List<SyncTask.UpdateItem<CloudPool, Object>> updateList, String region) {
//        def updates = []
//
//        updateList.each { updateItem ->
//            CloudPool existingItem = updateItem.existingItem
//            Object newVpc = updateItem.masterItem
//            log.info("RAZI OLD VPC: ${existingItem}")
//            log.info("RAZI NEW VPC: ${newVpc}")
//            boolean save = false
//            if (existingItem.id != newVpc.id) {
//                existingItem.id = newVpc.id
//                save = true
//            }
//            if (existingItem.name != newVpc.name) {
//                existingItem.name = newVpc.name
//                save = true
//            }
//            if (existingItem.description != newVpc.description) {
//                existingItem.description = newVpc.description
//                save = true
//            }
//            if (region && existingItem.regionCode != region) {
//                existingItem.regionCode = region
//                save = true
//            }
//            if(existingItem.type != 'vpc') {
//                existingItem.type = 'vpc'
//                save = true
//
//            }
//            if (save) {
//                updates << existingItem
//            }
//        }
//
//        if (updates) {
//            morpheusContext.referenceData.update(updates).blockingGet()
//        }
//    }

//    List<ReferenceData> listVPCs() {
//        log.debug("listVPCs")
//        List<ReferenceData> vpcs = []
//        String apiKey = plugin.getAuthConfig(cloud).doApiKey
//        String datacenter = cloud.configMap.datacenter
//        log.info("RAZI DATACENTER: $datacenter")
//        if (datacenter) {
//            def response = apiService.listVpcs(apiKey, datacenter)
//            if (response.success) {
//                List vpcList = response.data
//                def category = generateCategoryForCloud(cloud)
//
//                log.debug("VPCs: $vpcList")
//                vpcList.each { vpc ->
//                    if(vpc.available == true ) {
//                        Map props = [
//                                code      : "${category}.${vpc.id}",
//                                category  : category,
//                                name      : vpc.name,
//                                keyValue  : vpc.id,
//                                externalId: vpc.id,
//                                value     : vpc.id,
//                                flagValue : vpc.available,
//                                config    : [features: vpc.features, sizes: vpc.sizes].encodeAsJSON().toString()
//                        ]
//                        vpcs << new ReferenceData(props)
//                    }
//                }
//            } else {
//                log.error("Failed to list VPCs: ${response.errorMessage}")
//            }
//        } else {
//            log.warn("Datacenter is not specified in the configuration.")
//        }
//
//        log.debug("listVPCs: $vpcs")
//        return vpcs
//    }


//    String generateCategoryForCloud(Cloud cloud) {
//        return "digitalocean.${cloud.id}.vpc"
//    }

    private removeMissingResourcePools(List<CloudPoolIdentity> removeList) {
        log.debug "removeMissingResourcePools: ${removeList?.size()}"
        log.info "RAZI removeMissingResourcePools: ${removeList?.size()}"
        morpheusContext.async.cloud.pool.remove(removeList).blockingGet()
    }

//    ServiceResponse clean(Map opts=[:]) {
//        log.debug("Cleaning up VPCSync data on DigitalOcean cloud with id {}", cloud.id)
//        Observable<ReferenceDataSyncProjection> domainReferenceData = morpheusContext.referenceData.listByCategory(generateCategoryForCloud(cloud))
//                .buffer(50)
//                .blockingSubscribe { List<ReferenceDataSyncProjection> deleteList ->
//                    morpheusContext.referenceData.remove(deleteList).blockingGet()
//                }
//
//        return ServiceResponse.success();
//    }

}

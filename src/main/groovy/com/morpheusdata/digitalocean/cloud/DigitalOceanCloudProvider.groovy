package com.morpheusdata.digitalocean.cloud

import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.digitalocean.cloud.sync.DatacentersSync
import com.morpheusdata.digitalocean.cloud.sync.ImagesSync
import com.morpheusdata.digitalocean.cloud.sync.SizesSync
import com.morpheusdata.core.backup.BackupProvider
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.*
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class DigitalOceanCloudProvider implements CloudProvider {
	DigitalOceanPlugin plugin
	MorpheusContext morpheusContext

	DigitalOceanCloudProvider(DigitalOceanPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	Icon getIcon() {
		return new Icon(path:"digitalocean.svg", darkPath: "digitalocean.svg")
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"digitalocean-circle.svg", darkPath: "digitalocean-circle.svg")
	}

	@Override
	String getCode() {
		return 'digitalocean'
	}

	@Override
	String getName() {
		return 'DigitalOcean'
	}

	@Override
	String getDescription() {
		return 'DigitalOcean'
	}

	@Override
	String getDefaultProvisionTypeCode() {
		return 'digitalocean-provision-provider'
	}

	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean hasFolders() {
		return false
	}

	@Override
	Boolean hasCloudInit() {
		return true
	}

	@Override
	Boolean hasDatastores() {
		return false
	}

	@Override
	Boolean hasBareMetal() {
		return false
	}

	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		def options = []
		options << new OptionType(
				code: 'zoneType.digitalocean.credential',
				inputType: OptionType.InputType.CREDENTIAL,
				name: 'Credentials',
				fieldName: 'type',
				fieldLabel: 'Credentials',
				fieldContext: 'credential',
				required: true,
				defaultValue: 'local',
				displayOrder: 0,
				optionSource: 'credentials',
				config: '{"credentialTypes":["username-api-key"]}'
		)
		options << new OptionType(
				name: 'Username',
				code: 'zoneType.digitalocean.username',
				fieldName: 'username',
				displayOrder: 10,
				fieldCode: 'gomorpheus.optiontype.Username',
				fieldLabel: 'Username',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				localCredential: true
		)
		options << new OptionType(
				name: 'API Key',
				code: 'zoneType.digitalocean.apiKey',
				fieldName: 'apiKey',
				displayOrder: 20,
				fieldCode: 'gomorpheus.optiontype.ApiKey',
				fieldLabel: 'API Key',
				required: true,
				inputType: OptionType.InputType.PASSWORD,
				fieldContext: 'config',
				localCredential: true
		)
		options << new OptionType(
				name: 'Datacenter',
				code: 'zoneType.digitalocean.datacenter',
				fieldName: 'datacenter',
				optionSourceType: 'digitalOcean', // only required until embedded is removed
				optionSource: 'digitalOceanDataCenters',
				displayOrder: 30,
				fieldCode: 'gomorpheus.optiontype.Datacenter',
				fieldLabel: 'Datacenter',
				required: true,
				inputType: OptionType.InputType.SELECT,
				dependsOn: 'config.username, config.apiKey, credential.type, credential.username, credential.password',
				fieldContext: 'config'
		)
		return options
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		//digital ocean
		def serverTypes = [
				new ComputeServerType(code: 'digitalOceanWindows', name: 'DigitalOcean Windows Node', description: '', platform: PlatformType.windows, agentType: ComputeServerType.AgentType.host, nodeType:'morpheus-windows-node',
						enabled: true, selectable: false, externalDelete: true, managed: true, controlPower: true, controlSuspend: false, creatable: false, computeService: null,
						displayOrder: 17, hasAutomation: true, reconfigureSupported: true, provisionTypeCode: 'digitalocean',
						containerHypervisor: true, bareMetalHost: false, vmHypervisor: false, guestVm: true,
						optionTypes: []
				),

				new ComputeServerType(code: 'digitalOceanVm', name: 'DigitalOcean VM Instance', description: '', platform: PlatformType.linux, nodeType:'morpheus-vm-node',
						enabled: true, selectable: false, externalDelete: true, managed: true, controlPower: true, controlSuspend: false, creatable: false, computeService: null,
						displayOrder: 0, hasAutomation: true, reconfigureSupported: true, provisionTypeCode: 'digitalocean',
						containerHypervisor: false, bareMetalHost: false, vmHypervisor: false, agentType: ComputeServerType.AgentType.guest, guestVm: true,
						optionTypes: []
				),

				//docker
				new ComputeServerType(code:'digitalOceanLinux', name: 'DigitalOcean Docker Host', description: '', platform: PlatformType.linux, nodeType:'morpheus-node',
						enabled: true, selectable: false, externalDelete: true, managed: true, controlPower: true, controlSuspend: false, creatable: true, computeService: null, containerEngine: "docker",
						displayOrder: 16, hasAutomation: true, reconfigureSupported: true, provisionTypeCode: 'digitalocean',
						containerHypervisor: true, bareMetalHost: false, vmHypervisor: false, agentType: ComputeServerType.AgentType.host, clusterType: ComputeServerType.ClusterType.docker,
						computeTypeCode: 'docker-host',
						optionTypes: []
				),

				//kubernetes
				new ComputeServerType(code: 'digitalOceanKubeMaster', name: 'Digital Ocean Kubernetes Master', description: '', platform: PlatformType.linux, nodeType:'kube-master',
					reconfigureSupported: true, enabled: true, selectable: false, externalDelete: true, managed: true, controlPower: true, controlSuspend: true, creatable: true,
						supportsConsoleKeymap: true, computeService: null, displayOrder: 10, provisionTypeCode: 'digitalocean',
						hasAutomation: true, containerHypervisor: true, bareMetalHost: false, vmHypervisor: false, agentType: ComputeServerType.AgentType.guest, clusterType: ComputeServerType.ClusterType.kubernetes,
						computeTypeCode: 'kube-master',
						optionTypes: []
				),
				new ComputeServerType(code: 'digitalOceanKubeWorker', name: 'Digital Ocean Kubernetes Worker', description: '', platform: PlatformType.linux, nodeType:'kube-worker',
						reconfigureSupported: true, enabled: true, selectable: false, externalDelete: true, managed: true, controlPower: true, controlSuspend: true, creatable: true,
						supportsConsoleKeymap: true, computeService: null, displayOrder: 10, provisionTypeCode: 'digitalocean',
						hasAutomation: true, containerHypervisor: true, bareMetalHost: false, vmHypervisor: false, agentType: ComputeServerType.AgentType.guest, clusterType: ComputeServerType.ClusterType.kubernetes,
						computeTypeCode: 'kube-worker',
						optionTypes: []
				),
				//unmanaged discovered type
				new ComputeServerType(code: 'digitalOceanUnmanaged', name: 'Digital Ocean VM', description: 'Digital Ocean VM', platform: PlatformType.none, agentType: ComputeServerType.AgentType.guest,
						enabled: true, selectable: false, externalDelete: true, managed: false, controlPower: true, controlSuspend: false, creatable: false, computeService: null,
						displayOrder: 99, hasAutomation: false, provisionTypeCode: 'digitalocean',
						containerHypervisor: false, bareMetalHost: false, vmHypervisor: false, managedServerType: 'digitalOceanVm2', guestVm: true, supportsConsoleKeymap: true,
						optionTypes: []
				)
		]

		return serverTypes
	}

	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
		return plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		return plugin.getProvidersByType(BackupProvider) as Collection<com.morpheusdata.core.backup.BackupProvider>
	}

	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	@Override
	Collection<NetworkType> getNetworkTypes() {
		return null
	}

	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		return null
	}

	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		return null
	}

	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		return null
	}

	@Override
	ServiceResponse validate(Cloud cloud, ValidateCloudRequest validateCloudRequest) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		log.debug("validating Cloud: ${cloud.code}, ${validateCloudRequest.credentialType} ${validateCloudRequest.credentialUsername} ${validateCloudRequest.credentialPassword}")
		if (!cloud.configMap.datacenter) {
			return new ServiceResponse(success: false, msg: 'Choose a datacenter')
		}
		def apiKey
		def username

		if(validateCloudRequest.credentialType == 'local') {
			apiKey = cloud.configMap.apiKey
			username = cloud.configMap.username
		} else if (validateCloudRequest.credentialType && validateCloudRequest.credentialType.isNumber()) {
			def credentialId = validateCloudRequest.credentialType.toLong()
			AccountCredential accountCredential = morpheusContext.accountCredential.get(credentialId).blockingGet()
			apiKey = accountCredential.data?.password
			username = accountCredential.data?.username
		} else {
			apiKey = validateCloudRequest.credentialPassword
			username = validateCloudRequest.credentialUsername
		}

		if (!username) {
			return new ServiceResponse(success: false, msg: 'Enter a username')
		}
		if (!apiKey) {
			return new ServiceResponse(success: false, msg: 'Enter your api key')
		}

		ServiceResponse response = apiService.listRegions(apiKey)
		if(response.success == false) {
			return new ServiceResponse(success: false, msg: 'Invalid credentials')
		}

		return new ServiceResponse(success: true)
	}

	@Override
	ServiceResponse initializeCloud(Cloud cloud) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		ServiceResponse serviceResponse
		log.debug("Initializing Cloud: ${cloud.code}")
		log.debug("config: ${cloud.configMap}")
		String apiKey = plugin.getAuthConfig(cloud).doApiKey

		// check account
		ServiceResponse initResponse = apiService.getAccount(apiKey)
		if (initResponse.success && initResponse.data.status == 'active') {
			serviceResponse = new ServiceResponse(success: true, content: initResponse.content)

			refreshDaily(cloud, apiService)
			refresh(cloud, apiService)

			KeyPair keyPair = morpheusContext.cloud.findOrGenerateKeyPair(cloud.account).blockingGet()
			if (keyPair) {
				KeyPair updatedKeyPair = findOrUploadKeypair(apiKey, keyPair.publicKey, keyPair.name)
				if(updatedKeyPair) {
					morpheusContext.cloud.updateKeyPair(updatedKeyPair, cloud)
				} else {
					log.warn("Unable to create SSH Key pair")
				}
			} else {
				log.debug("no morpheus keys found")
			}
		} else {
			serviceResponse = new ServiceResponse(success: false, msg: initResponse.errorCode, content: initResponse.content)
		}

		serviceResponse
	}

	@Override
	ServiceResponse refresh(Cloud cloud, DigitalOceanApiService apiService=null) {
		def rtn = ServiceResponse.prepare()
		log.debug("Short refresh cloud ${cloud.code}")
		apiService = apiService ?: new DigitalOceanApiService()
		def syncDate = new Date()
		String apiKey = plugin.getAuthConfig(cloud).doApiKey
		ServiceResponse testResult = apiService.testConnection(apiKey)
		if(testResult.success) {
			(new ImagesSync(plugin, cloud, apiService, true)).execute()
			rtn.success = true
		} else {
			if(testResult.data.invalidLogin) {
				rtn.success = false
				rtn.msg = 'Error refreshing cloud: invalid credentials'
				rtn.data = [status: Cloud.Status.offline]
			} else {
				rtn.success = false
				rtn.msg = 'Error refreshing cloud: host not reachable'
				rtn.data = [status: Cloud.Status.offline]
			}
		}

		log.debug("Completed short refresh for cloud $cloud.code")
		return rtn
	}

	@Override
	void refreshDaily(Cloud cloud, DigitalOceanApiService apiService=null) {
		log.debug("daily refresh cloud ${cloud.code}")
		apiService = apiService ?: new DigitalOceanApiService()
		def syncDate = new Date()
		String apiKey = plugin.getAuthConfig(cloud).doApiKey
		ServiceResponse testResult = apiService.testConnection(apiKey)
		if(testResult.success) {
			(new DatacentersSync(plugin, cloud, apiService)).execute()
			(new SizesSync(plugin, cloud, apiService)).execute()
			(new ImagesSync(plugin, cloud, apiService, false)).execute()
		} else {
			if(testResult.data.invalidLogin) {
				morpheusContext.cloud.updateZoneStatus(cloud, Cloud.Status.offline, 'Error refreshing cloud: invalid credentials', syncDate)
			} else {
				morpheusContext.cloud.updateZoneStatus(cloud, Cloud.Status.offline, 'Error refreshing cloud: host not reachable', syncDate)
			}
		}

		log.debug("Completed daily refresh for cloud ${cloud.code}")
	}

	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		log.debug("deleteCloud with id {}", cloudInfo.id)
		(new DatacentersSync(plugin, cloudInfo, null)).clean()
		(new SizesSync(plugin, cloudInfo, null)).clean()
		(new ImagesSync(plugin, cloudInfo, null)).clean()
		return new ServiceResponse(success: true)
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer, DigitalOceanApiService apiService=null) {
		apiService = apiService ?: new DigitalOceanApiService()
		String dropletId = computeServer.externalId
		String apiKey = plugin.getAuthConfig(computeServer.cloud).doApiKey
		log.debug("startServer: ${dropletId}")
		if (!dropletId) {
			log.debug("no Droplet ID provided")
			return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
		}
		return apiService.performDropletAction(apiKey, dropletId, 'power_on')
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer, DigitalOceanApiService apiService=null) {
		apiService = apiService ?: new DigitalOceanApiService()
		String dropletId = computeServer.externalId
		String apiKey = plugin.getAuthConfig(computeServer.cloud).doApiKey
		log.debug("stopServer: ${dropletId}")
		if (!dropletId) {
			log.debug("no Droplet ID provided")
			return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
		}
		return apiService.performDropletAction(apiKey, dropletId, 'shutdown')
	}

	@Override
	ServiceResponse deleteServer(ComputeServer computeServer, DigitalOceanApiService apiService=null) {
		apiService = apiService ?: new DigitalOceanApiService()
		String dropletId = computeServer.externalId
		String apiKey = plugin.getAuthConfig(computeServer.cloud).doApiKey
		log.debug("deleteServer: ${dropletId}")
		if (!dropletId) {
			log.debug("no Droplet ID provided")
			return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
		}
		ServiceResponse response = apiService.deleteDroplet(apiKey, dropletId)
		if (response.success) {
			return new ServiceResponse(success: true)
		} else {
			return new ServiceResponse(success: false, results: response.results, data: response.data, msg: response.errorCode, error: response.results)
		}
	}

	KeyPair findOrUploadKeypair(String apiKey, String publicKey, String keyName, DigitalOceanApiService apiService=null) {
		apiService = apiService ?: new DigitalOceanApiService()
		KeyPair rtn = null
		Map match = null
		keyName = keyName ?: 'morpheus_do_plugin_key'
		log.debug("find or update keypair for key $keyName")
		ServiceResponse keysResponse = apiService.listAccountKeys(apiKey)
		if(keysResponse.success) {
			match = keysResponse.data.find { publicKey.startsWith(it['public_key']) }
			log.debug("match: ${match} - list: ${keysResponse.data}")
			if (!match) {
				log.debug('key not found in DO')
				ServiceResponse response = apiService.createAccountKey(apiKey, keyName, publicKey)
				if (response.success) {
					match = response.data
				} else {
					log.debug('failed to add DO ssh key')
				}
			}
		}

		if(match) {
			rtn = new KeyPair(name: match.name, externalId: match.id, publicKey: match.public_key, publicFingerprint: match.fingerprint)
		}

		return rtn
	}
}

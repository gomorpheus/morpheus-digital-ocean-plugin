package com.morpheusdata.digitalocean.provisioning

import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.providers.ComputeProvisionProvider
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.VmProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.KeyPair
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.model.provisioning.UsersConfiguration
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class DigitalOceanProvisionProvider extends AbstractProvisionProvider implements ComputeProvisionProvider, VmProvisionProvider, WorkloadProvisionProvider.ResizeFacet, HostProvisionProvider.ResizeFacet {
	DigitalOceanPlugin plugin
	MorpheusContext morpheusContext
	private static final String DIGITAL_OCEAN_ENDPOINT = 'https://api.digitalocean.com'
	private static final String UBUNTU_VIRTUAL_IMAGE_CODE = 'digitalocean.image.morpheus.ubuntu.18.04'

	DigitalOceanProvisionProvider(DigitalOceanPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	public Icon getCircularIcon() {
		return new Icon(path:"digitalocean-circle.svg", darkPath: "digitalocean-circle.svg")
	}


	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	@Override
	HostType getHostType() {
		HostType.vm
	}

	@Override
	Collection<VirtualImage> getVirtualImages() {
		VirtualImage virtualImage = new VirtualImage(
				code: UBUNTU_VIRTUAL_IMAGE_CODE,
				category:'digitalocean.image.morpheus',
				name:'Ubuntu 18.04 LTS (Digital Ocean Marketplace)',
				imageType: ImageType.qcow2,
				systemImage:true,
				isCloudInit:true,
				externalId:'ubuntu-18-04-x64',
				osType: new OsType(code: 'ubuntu.18.04.64')
		)
		[virtualImage]
	}

	@Override
	Collection<ComputeTypeLayout> getComputeTypeLayouts() {
		List<ComputeTypeLayout> layouts = []
		return layouts
	}

	@Override
	public Collection<OptionType> getNodeOptionTypes() {
		def options = [
			new OptionType(
				name: 'image',
				category: "provisionType.digitalOcean.custom",
				code: 'provisionType.digitalOcean.custom.containerType.imageId',
				fieldContext: 'domain',
				fieldName: 'virtualImage.id',
				fieldCode: 'gomorpheus.label.vmImage',
				fieldLabel: 'VM Image',
				fieldGroup: null,
				inputType: OptionType.InputType.SELECT,
				displayOrder: 10,
				required: true,
				optionSourceType: 'digitalOcean',
				optionSource: 'digitalOceanImage'
			),
			new OptionType(
				name: 'mount logs',
				category: "provisionType.digitalOcean.custom",
				code: 'provisionType.digitalOcean.custom.containerType.mountLogs',
				fieldContext: 'domain',
				fieldName: 'mountLogs',
				fieldCode: 'gomorpheus.optiontype.LogFolder',
				fieldLabel: 'Log Folder',
				fieldGroup: null,
				inputType: OptionType.InputType.TEXT,
				displayOrder: 20,
				required: false,
				editable: false
			),
			new OptionType(
				name: 'mount config',
				category: "provisionType.digitalOcean.custom",
				code: 'provisionType.digitalOcean.custom.containerType.mountConfig',
				fieldContext: 'domain',
				fieldName: 'mountConfig',
				fieldCode: 'gomorpheus.optiontype.ConfigFolder',
				fieldLabel: 'Config Folder',
				fieldGroup: null,
				inputType: OptionType.InputType.TEXT,
				displayOrder: 30,
				required: false,
				editable: false
			),
			new OptionType(
				name: 'mount data',
				category: "provisionType.digitalOcean.custom",
				code: 'provisionType.digitalOcean.custom.containerType.mountData',
				fieldContext: 'domain',
				fieldName: 'mountData',
				fieldCode: 'gomorpheus.optiontype.DeployFolder',
				fieldLabel: 'Deploy Folder',
				fieldGroup: null,
				inputType: OptionType.InputType.TEXT,
				displayOrder: 40,
				required: false,
				editable: false,
				helpTextI18nCode: "gomorpheus.help.deployFolder"
			),
			new OptionType(
				code: "instanceType.digitalOcean.statTypeCode",
				inputType: OptionType.InputType.HIDDEN,
				name: "statTypeCode",
				category: "provisionType.digitalOcean",
				fieldName: "statTypeCode",
				fieldLabel: "Stat Type",
				fieldContext: "containerType",
				fieldGroup: "default",
				required: false,
				enabled: true,
				editable: false,
				global: false,
				placeHolder: null,
				helpBlock: "",
				defaultValue: "vm",
				custom: false,
				displayOrder: 100,
				fieldClass: null
			),
			new OptionType(
				code: "instanceType.digitalOcean.logTypeCode",
				inputType: OptionType.InputType.HIDDEN,
				name: "logTypeCode",
				category: "provisionType.digitalOcean",
				fieldName: "logTypeCode",
				fieldLabel: "Log Type",
				fieldContext: "containerType",
				fieldGroup: "default",
				required: false,
				enabled: true,
				editable: false,
				global: false,
				placeHolder: null,
				helpBlock: "",
				defaultValue: "vm",
				custom: false,
				displayOrder: 101,
				fieldClass: null
			),
			new OptionType(
				code: "instanceType.digitalOcean.showServerLogs",
				inputType: OptionType.InputType.HIDDEN,
				name: "showServerLogs",
				category: "provisionType.digitalOcean",
				fieldName: "showServerLogs",
				fieldLabel: "Show Server Logs",
				fieldContext: "containerType",
				fieldGroup: "default",
				required: false,
				enabled: true,
				editable: false,
				global: false,
				placeHolder: null,
				helpBlock: "",
				defaultValue: "true",
				custom: false,
				displayOrder: 102,
				fieldClass: null
			),
			new OptionType(
				code: "instanceType.digitalOcean.serverType",
				inputType: OptionType.InputType.HIDDEN,
				name: "serverType",
				category: "provisionType.digitalOcean",
				fieldName: "serverType",
				fieldLabel: "Server Type",
				fieldContext: "containerType",
				fieldGroup: "default",
				required: false,
				enabled: true,
				editable: false,
				global: false,
				placeHolder: null,
				helpBlock: "",
				defaultValue: "vm",
				custom: false,
				displayOrder: 103,
				fieldClass: null
			)
		]

		return options
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		def options = []
		options << new OptionType(
			name: 'skip agent install',
			code: 'provisionType.digitalOcean.noAgent',
			category: 'provisionType.digitalOcean',
			inputType: OptionType.InputType.CHECKBOX,
			fieldName: 'noAgent',
			fieldContext: 'config',
			fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
			fieldLabel: 'Skip Agent Install',
			fieldGroup:'Advanced Options',
			displayOrder: 4,
			required: false,
			enabled: true,
			editable:false,
			global:false,
			placeHolder:null,
			helpBlock:'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.',
			defaultValue:null,
			custom:false,
			fieldClass:null
		)

		return options
	}

	@Override
	Collection<ServicePlan> getServicePlans() {
		return []
	}

	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		return []
	}

	@Override
	String getCode() {
		return 'digitalocean-provision-provider'
	}

	@Override
	String getProvisionTypeCode() {
		return "digitalocean"
	}

	@Override
	String getName() {
		return 'DigitalOcean'
	}

	@Override
	Boolean hasDatastores() {
		return false
	}

	@Override
	Boolean hasNetworks() {
		return false
	}

	@Override
	Boolean hasPlanTagMatch() {
		false
	}

	@Override
	Integer getMaxNetworks() {
		return 1
	}

	@Override
	Boolean supportsCustomServicePlans() {
		return false
	}

	@Override
	Boolean hasNodeTypes() {
		return true
	}

	/**
	 * Specifies which deployment service should be used with this provider. In this case we are using the vm service
	 * @return the name of the service
	 */
	@Override
	String getDeployTargetService() {
		return "vmDeployTargetService"
	}

	@Override
	String getNodeFormat() {
		return "vm"
	}

	@Override
	Boolean customSupported() {
		return true
	}

	@Override
	Boolean lvmSupported() {
		return false
	}

	@Override
	Boolean createDefaultInstanceType() {
		return false
	}

	/**
	 * Determines if this provision type has resources pools that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has resource pools
	 */
	@Override
	Boolean hasComputeZonePools() {
		return true
	}

	/**
	 * Indicates if a ComputeZonePool is required during provisioning
	 * @return Boolean
	 */
	@Override
	Boolean computeZonePoolRequired() {
		return true
	}

	@Override
	ServiceResponse validateWorkload(Map opts) {
		log.debug("validateWorkload: ${opts}")
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse validateHost(ComputeServer server, Map opts) {
		log.debug("validateDockerHost, server: ${server}, opts: ${opts}")
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug("prepareWorkload, workload: ${workload}, workloadRequest: ${workloadRequest}, opts: ${opts}")
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<>()
		resp.data = new PrepareWorkloadResponse(workload: workload, options: [sendIp: false])
		resp.success = true
		return resp
	}

	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		log.debug("runWorkload: ${workload.configs} ${opts}")
		def containerConfig = new groovy.json.JsonSlurper().parseText(workload.configs ?: '{}')
		ComputeServer server = workload.server
		Cloud cloud = server?.cloud

		String apiKey = plugin.getAuthConfig(cloud).doApiKey
		if (!apiKey) {
			return new ServiceResponse(success: false, msg: 'No API Key provided')
		}
		Map callbackOpts = [:]

		def imageId = opts.imageRef
		log.debug("runWorkload: cloneContainerId: $opts.cloneContainerId, opts.backupSetId: $opts.backupSetId")
		if(opts.cloneContainerId && opts.backupSetId) {
			BackupResult backupResult = plugin.morpheus.backup.backupResult.listByBackupSetIdAndContainerId(opts.backupSetId, opts.cloneContainerId).blockingFirst()
			log.debug("runWorkload backupResults: $backupResult")
			if(backupResult) {
				def snapshotImageId = backupResult.externalId
				if(snapshotImageId){
					log.debug("creating server from snapshot image: ${snapshotImageId}")
					imageId = snapshotImageId
				}
			}
		}

		VirtualImage virtualImage = workload.server.sourceImage
		// Grab the user configuration data (then update the server)
		UsersConfiguration usersConfiguration = morpheus.provision.getUserConfig(workload, virtualImage, opts).blockingGet()
		log.debug("usersConfiguration ${usersConfiguration}")
		server.sshUsername = usersConfiguration.sshUsername
		server.sshPassword = usersConfiguration.sshPassword
		morpheus.computeServer.save([server]).blockingGet()

		def userData
		if(virtualImage?.isCloudInit) {
			// Utilize the morpheus build cloud-init methods
			Map cloudConfigOptions = workloadRequest.cloudConfigOpts
			log.debug("cloudConfigOptions ${cloudConfigOptions}")

			// Inform Morpheus to install the agent (or not) after the server is created
			callbackOpts.noAgent = (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true)
			callbackOpts.installAgent = (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true))

			def cloudConfigUser = workloadRequest.cloudConfigUser
			log.debug("cloudConfigUser: ${cloudConfigUser}")
			userData = cloudConfigUser

			// Not really used in DO provisioning (example only)
			String metadata = workloadRequest.cloudConfigMeta
			log.debug("metadata: ${metadata}")

			//// Not really used in DO provisioning (example only)
			//String networkData = morpheus.provision.buildCloudNetworkData(com.morpheusdata.model.PlatformType.valueOf(server.osType), cloudConfigOptions).blockingGet()
			//log.debug("networkData: ${networkData}")
		} else {
			// These users will be created by Morpheus after provisioning
			callbackOpts.createUsers = usersConfiguration.createUsers
		}

		// Now.. ready to create it in DO
		def dropletConfig = [
			'name'              : cleanInstanceName(workload.server.name),
			'region'            : cloud.configMap.datacenter,
			'size'              : workload.plan.externalId,
			'image'             : imageId,
			'backups'           : "${opts.doBackups == true}",
			'ipv6'              : "${opts.ipv6 == true}",
			'user_data'         : userData
		]

		// Add ssh keys provided by morpheus core services, e.g. Account or User ssh keys
		if(opts.sshKeys && opts.sshKeys.size() > 0) {
			dropletConfig.ssh_keys = opts.sshKeys.collect { it.toString() }
		}

		// add account ssh key if its not in the droplet config
		KeyPair accountKeyPair = morpheus.cloud.findOrGenerateKeyPair(workload.account).blockingGet()
		def keyPairId = accountKeyPair?.configMap["${cloud.code}.keyId"]
		if(keyPairId && (!dropletConfig.ssh_keys || dropletConfig.ssh_keys.contains(keyPairId.toString()) == false)) {
			dropletConfig.ssh_keys = dropletConfig.ssh_keys ?: []
			dropletConfig.ssh_keys << keyPairId.toString()
		}

		// log.debug("runWorkload dropletConfig: $dropletConfig")
		def response = apiService.createDroplet(apiKey, dropletConfig)
		if (response.success) {
			log.debug("Droplet Created ${response.results}")

			// Need to store the link between the Morpheus ComputeServer reference and the Digital Ocean object
			def droplet = response.data
			ProvisionResponse provisionResponse = dropletToProvisionResponse(droplet, callbackOpts)

			def externalId = droplet.id
			server.externalId = externalId
			server.osDevice = '/dev/vda'
			server.dataDevice = '/dev/vda'
			server.lvmEnabled = false
			server = saveAndGet(server)

			return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
		} else {
			def errorMessage = getErrorMessage(response.errorCode, response.results)
			log.debug("Failed to create droplet: $errorMessage")
			return new ServiceResponse(success: false, msg: errorMessage, content: response.content, error: response.data)
		}
	}

	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		ServiceResponse rtn = ServiceResponse.prepare()
		log.debug("finalizeWorkload: ${workload?.id}")
		try {
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			def serverDetails = getServerDetails(server, apiService)

			// update IP address if necessary
			if(serverDetails.success == true && serverDetails.data.publicIp) {
				def doSave = false
				def privateIp = serverDetails.data.privateIp
				def publicIp = serverDetails.data.publicIp
				if(server.internalIp != privateIp) {
					server.internalIp = privateIp
					doSave = true
				}
				if(serverDetails.data.publicIp != publicIp) {
					server.externalIp = publicIp
					doSave = true
				}

				if(doSave) {
					morpheusContext.computeServer.save([server]).blockingGet()
				}
				rtn.success = true
			}
		} catch(e) {
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error("Error in finalizeWorkload: {}", e, e)
		}

		return rtn
	}

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("prepareHost: ${server} ${hostRequest} ${opts}")

		def prepareResponse = new PrepareHostResponse(computeServer: server, disableCloudInit: false, options: [sendIp: false])
		ServiceResponse<PrepareHostResponse> rtn = ServiceResponse.prepare(prepareResponse)

		try {
			VirtualImage virtualImage
			Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.services.computeTypeSet.get(computeTypeSetId)
				if(computeTypeSet.workloadType) {
					WorkloadType workloadType = morpheus.services.workloadType.get(computeTypeSet.workloadType.id)
					virtualImage = workloadType?.virtualImage
				}
			}
			if(!virtualImage) {
				rtn.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(server)
				rtn.success = true
			}
		} catch(e) {
			rtn.msg = "Error in prepareHost: ${e}"
			log.error("${rtn.msg}, ${e}", e)

		}
		return rtn
	}

	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()

		log.debug("runHost: ${server} ${hostRequest} ${opts}")

		String apiKey = plugin.getAuthConfig(server.cloud).doApiKey
		if (!apiKey) {
			return new ServiceResponse(success: false, msg: 'No API Key provided')
		}
		
		// sshKeys needed?
		// opts.sshKeys = getKeyList(server.cloud, config.publicKeyId)

		def rtn = [success:false]
		def found = false
		log.debug("findOrCreateServer: ${server.externalId}")

		def dropletConfig = [
				'name'              : cleanInstanceName(server.name),
				'region'            : server.cloud.configMap.datacenter,
				'size'              : server.plan.externalId,
				'image'             : server.sourceImage.externalId,
				'backups'           : "${opts.doBackups}",
				'ipv6'              : false,
				'user_data'         : hostRequest.cloudConfigUser,
				'private_networking': false
		]

		def response = apiService.createDroplet(apiKey, dropletConfig)

		Map callbackOpts = [:]
		if(server.sourceImage?.isCloudInit) {
			// Utilize the morpheus built cloud-init methods
			Map cloudConfigOptions = hostRequest.cloudConfigOpts
			log.debug("cloudConfigOptions ${cloudConfigOptions}")

			// Inform Morpheus to install the agent (or not) after the server is created
			callbackOpts.installAgent = opts.installAgent && (cloudConfigOptions.installAgent != true)
		} else {
			// These users will be created by Morpheus after provisioning
			callbackOpts.createUsers = hostRequest.usersConfiguration
		}

		if (response.success) {
			log.debug("Droplet Created ${response.content}")

			def droplet = response.data
			ProvisionResponse provisionResponse = dropletToProvisionResponse(droplet, callbackOpts)

			// Need to store the link between the Morpheus ComputeServer reference and the Digital Ocean object
			server.externalId = provisionResponse.externalId
			server.osDevice = '/dev/vda'
			server.dataDevice = '/dev/vda'
			server.lvmEnabled = false
			server = saveAndGet(server)

			return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
		} else {
			def errorMessage = getErrorMessage(response.errorCode, response.results)
			log.debug("Failed to create droplet: $errorMessage")
			return new ServiceResponse(success: false, msg: errorMessage, content: response.content, error: response.data)
		}
	}

	@Override
	ServiceResponse<ProvisionResponse> waitForHost(ComputeServer server) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()

		log.debug("waitForHost: ${server}")
		try {
			return getServerDetails(server, apiService)
		} catch(e) {
			log.error("Error waitForHost: ${e}", e)
			return new ServiceResponse(success: false, msg: "Error in waiting for Host: ${e}")
		}
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		ServiceResponse rtn = ServiceResponse.prepare()
		log.debug("finalizeHost: ${server?.id}")
		try {
			Cloud cloud = server.cloud
			def serverDetails = getServerDetails(server, apiService)

			// update IP address if necessary
			if(serverDetails.success == true && serverDetails.data.publicIp) {
				def doSave = false
				def privateIp = serverDetails.data.privateIp
				def publicIp = serverDetails.data.publicIp
				if(server.internalIp != privateIp) {
					server.internalIp = privateIp
					doSave = true
				}
				if(serverDetails.externalIp != publicIp) {
					server.externalIp = publicIp
					doSave = true
				}

				if(doSave) {
					morpheusContext.computeServer.save([server]).blockingGet()
				}
				rtn.success = true
			}
		} catch(e) {
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error("Error in finalizeWorkload: {}", e, e)
		}

		return rtn
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		log.debug("resizeServer: ${server} ${resizeRequest} ${opts}")
		internalResizeServer(server, resizeRequest, apiService)
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		log.debug("resizeWorkload: ${instance} ${workload} ${resizeRequest} ${opts}")
		internalResizeServer(workload.server, resizeRequest, apiService)
	}

	private ServiceResponse internalResizeServer(ComputeServer server, ResizeRequest resizeRequest, DigitalOceanApiService apiService=null) {
		apiService = apiService ?: new DigitalOceanApiService()
		log.debug("internalResizeServer: ${server} ${resizeRequest}")
		ServiceResponse rtn = ServiceResponse.success()
		try {
			String apiKey = plugin.getAuthConfig(server.cloud).doApiKey
			String dropletId = server.externalId
			Map actionConfig = [
					'disk': true,
					'size': resizeRequest.plan.externalId
			]
			rtn = apiService.performDropletAction(apiKey, dropletId, 'resize', actionConfig)
			if(rtn.success) {
				StorageVolume existingVolume = server.volumes?.getAt(0)
				if (existingVolume) {
					existingVolume.maxStorage = resizeRequest.maxStorage
					morpheus.storageVolume.save([existingVolume]).blockingGet()
				}
			}
		} catch(e) {
			rtn.success = false
			rtn.msg = e.message
			log.error("Error in resizing server: ${e}", e)
		}
		rtn
	}

	@Override
	ServiceResponse<ProvisionResponse> stopWorkload(Workload workload, DigitalOceanApiService apiService=null) {
		apiService = apiService ?: new DigitalOceanApiService()
		String dropletId = workload.server.externalId
		String apiKey = plugin.getAuthConfig(workload.server.cloud).doApiKey
		log.debug("stopWorkload: ${dropletId}")
		if (!dropletId) {
			log.debug("no Droplet ID provided")
			return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
		}

		ServiceResponse response = apiService.performDropletAction(apiKey, dropletId, 'shutdown')
		if (response.success) {
			return apiService.checkActionComplete(apiKey, response.data.id.toString())
		} else {
			powerOffServer(apiKey, dropletId, apiService)
		}
	}

	@Override
	ServiceResponse<ProvisionResponse> startWorkload(Workload workload, DigitalOceanApiService apiService=null) {
		apiService = apiService ?: new DigitalOceanApiService()
		String dropletId = workload.server.externalId
		String apiKey = plugin.getAuthConfig(workload.server.cloud).doApiKey
		log.debug("startWorkload: ${dropletId}")
		if (!dropletId) {
			log.debug("no Droplet ID provided")
			return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
		}
		return apiService.performDropletAction(apiKey, dropletId, 'power_on')
	}

	@Override
	ServiceResponse restartWorkload(Workload workload) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()

		ServiceResponse rtn = ServiceResponse.prepare()
		log.debug("restartWorkload: ${workload.id}")
		ServiceResponse stopResult = stopWorkload(workload, apiService)
		if (stopResult.success) {
			rtn = startWorkload(workload, apiService)
		} else {
			rtn = stopResult
		}

		return rtn
	}

	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()

		String dropletId = workload.server.externalId
		log.debug("removeWorkload: ${dropletId}")
		if (!dropletId) {
			log.debug("no Droplet ID provided")
			return new ServiceResponse(success: false, msg: 'No Droplet ID provided')
		}

		String apiKey = plugin.getAuthConfig(workload.server.cloud).doApiKey
		return apiService.deleteDroplet(apiKey, dropletId)
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
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server, DigitalOceanApiService apiService=null) {
		apiService = apiService ?: new DigitalOceanApiService()
		log.debug("getServerDetails: $server.id")
		ServiceResponse rtn = ServiceResponse.prepare(new ProvisionResponse())
		String apiKey = plugin.getAuthConfig(server.cloud).doApiKey

		Boolean pending = true
		Integer attempts = 0
		while (pending) {
			log.debug("getServerDetails attempt $attempts")
			sleep(1000l * 20l)
			def resp = apiService.getDroplet(apiKey, server.externalId)
			if (resp.success) {
				if(resp.data?.status == "active") {
					rtn.success = true
					rtn.data = dropletToProvisionResponse(resp.data)
					pending = false
				} else if(resp.msg == 'failed') {
					rtn.msg = resp.msg
					pending = false
				}
			}
			attempts++
			if (attempts > 15) {
				pending = false
			}
		}

		return rtn
	}

	ServiceResponse<ProvisionResponse> powerOffServer(String apiKey, String dropletId, DigitalOceanApiService apiService=null) {
		apiService = apiService ?: new DigitalOceanApiService()
		log.debug("powerOffServer: $dropletId")
		return apiService.performDropletAction(apiKey, dropletId, 'power_off')
	}

	protected ProvisionResponse dropletToProvisionResponse(Map droplet, Map opts=[:]) {
		ProvisionResponse provisionResponse = new ProvisionResponse()
		provisionResponse.externalId = droplet?.id

		// networks
		def publicNetwork = droplet?.networks?.v4?.find {
			it.type == 'public'
		}
		def privateNetwork = droplet?.networks?.v4?.find {
			it.type == 'private'
		}
		def publicIp = publicNetwork?.ip_address
		def privateIp = privateNetwork?.ip_address ?: publicIp
		provisionResponse.publicIp = publicIp
		provisionResponse.privateIp = privateIp

		if(opts?.containsKey('installAgent')) {
			provisionResponse.installAgent = opts.installAgent
		}
		if(opts?.containsKey('noAgent')) {
			provisionResponse.noAgent = opts.noAgent
		}
		if(opts?.containsKey('createUsers')) {
			provisionResponse.createUsers = opts.createUsers
		}

		return provisionResponse
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		morpheus.computeServer.save([server]).blockingGet()
		return morpheus.computeServer.get(server.id).blockingGet()
	}

	protected cleanInstanceName(name) {
		def rtn = name.replaceAll(/[^a-zA-Z0-9\.\-]/,'')
		return rtn
	}

	protected getErrorMessage(String errorCode, Map responseBody) {
		def rtn = ""
		if(responseBody.message) {
			rtn = "$responseBody.message"
			if(errorCode) {
				rtn += " (error: $errorCode)"
			}
		} else if(errorCode) {
			rtn = "API error: $errorCode"
		} else {
			rtn = "Unknown API error occurred."
		}

		return rtn
	}
}

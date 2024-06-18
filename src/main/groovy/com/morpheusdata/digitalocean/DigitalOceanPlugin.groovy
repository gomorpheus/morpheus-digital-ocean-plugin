package com.morpheusdata.digitalocean

import com.morpheusdata.digitalocean.cloud.DigitalOceanCloudProvider
import com.morpheusdata.digitalocean.backup.DigitalOceanBackupProvider
import com.morpheusdata.digitalocean.datasets.DatacenterDatasetProvider
import com.morpheusdata.digitalocean.provisioning.DigitalOceanProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

@Slf4j
class DigitalOceanPlugin extends Plugin {

	@Override
	String getCode() {
		return 'morpheus-digital-ocean-plugin'
	}

	@Override
	void initialize() {
		this.name = 'DigitalOcean Plugin'

		// shared instance of the api service for the all providers to ensure the
		// api throttle rate is shared

		DigitalOceanCloudProvider cloudProvider = new DigitalOceanCloudProvider(this, morpheus)
		DigitalOceanProvisionProvider provisionProvider = new DigitalOceanProvisionProvider(this, morpheus)
		DigitalOceanOptionSourceProvider optionSourceProvider = new DigitalOceanOptionSourceProvider(this, morpheus)
		DatacenterDatasetProvider datacenterDatasetProvider = new DatacenterDatasetProvider(this, morpheus)
		pluginProviders.put(provisionProvider.code, provisionProvider)
		pluginProviders.put(cloudProvider.code, cloudProvider)
		pluginProviders.put(optionSourceProvider.code, optionSourceProvider)
		this.registerProvider(datacenterDatasetProvider)

		DigitalOceanBackupProvider backupProvider = new DigitalOceanBackupProvider(this, morpheus)
		pluginProviders.put(backupProvider.code, backupProvider)
	}

	@Override
	void onDestroy() {

	}

	MorpheusContext getMorpheusContext() {
		return morpheus
	}

	Map getAuthConfig(Cloud cloud) {
		def rtn = [:]

		if(!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				if(!cloud.account?.id || !cloud.owner?.id) {
					log.debug("cloud account or owner id is missing, loading cloud object")
					// in some cases marshalling the cloud doesn't include the account and owner, in those cases
					// we need to load the cloud to include those elements.
					cloud = morpheus.services.cloud.get(cloud.id)
				}
				accountCredential = morpheus.services.accountCredential.loadCredentials(cloud)
			} catch(e) {
				// If there is no credential on the cloud, then this will error
			}
			cloud.accountCredentialLoaded = true
			cloud.accountCredentialData = accountCredential?.data
		}

		log.debug("AccountCredential loaded: $cloud.accountCredentialLoaded, Data: $cloud.accountCredentialData")

		def username
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
			username = cloud.accountCredentialData['username']
		} else {
			username = cloud.configMap.username
		}
		def apiKey
		if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
			apiKey = cloud.accountCredentialData['password']
		} else {
			apiKey = cloud.configMap.apiKey
		}

		rtn.doUsername = username
		rtn.doApiKey = apiKey
		return rtn
	}

	// get auth config from credential args, usually used for API request before a cloud is created
	Map getAuthConfig(Map args) {
		def rtn = [:]
		def accountCredentialData
		def username
		def apiKey

		if(args.credential && args.credential.type != 'local') {
			Map accountCredential
			try {
				accountCredential = morpheus.services.accountCredential.loadCredentialConfig(args.credential, [:])
			} catch(e) {
				// If there is no credential in the args, then this will error
			}
			log.debug("accountCredential: $accountCredential")
			accountCredentialData = accountCredential?.data
			if(accountCredentialData) {
				if(accountCredentialData.containsKey('username')) {
					username = accountCredentialData['username']
				}
				if(accountCredentialData.containsKey('password')) {
					apiKey = accountCredentialData['password']
				}
			}
		} else {
			log.debug("config: $args.config")
			username = args?.config?.username
			apiKey = args?.config?.apiKey
		}

		rtn.doUsername = username
		rtn.doApiKey = apiKey
		return rtn
	}
}

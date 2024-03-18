package com.morpheusdata.digitalocean

import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import groovy.util.logging.Slf4j
import com.morpheusdata.core.OptionSourceProvider

@Slf4j
class DigitalOceanOptionSourceProvider implements OptionSourceProvider {

	Plugin plugin
	MorpheusContext morpheusContext

	DigitalOceanOptionSourceProvider(Plugin plugin, MorpheusContext context) {
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
	String getCode() {
		return 'digital-ocean-option-source'
	}

	@Override
	String getName() {
		return 'DigitalOcean Option Source'
	}

	@Override
	List<String> getMethodNames() {
		return new ArrayList<String>(['digitalOceanDataCenters', 'digitalOceanImage'])
	}

	def digitalOceanDataCenters(args) {
		log.debug("datacenters: ${args}")
		List datacenters = []
		Long cloudId = args.getAt(0)?.zoneId?.toLong()
		String paramsApiKey = plugin.getAuthConfig(args.getAt(0) as Map).doApiKey
		Cloud cloud = null

		// if we know the cloud then load from cached data
		if(cloudId) {
			cloud = morpheus.services.cloud.get(cloudId)
			morpheus.services.referenceData.list(new DataQuery().withFilter("category", "digitalocean.${cloudId}.datacenter")).each { ReferenceDataSyncProjection refData ->
				datacenters << [name: refData.name, value: refData.externalId]
			}
		}

		// check if auth config has changed and force a refresh of the datacenters
		if(cloud) {
			def cloudApiKey = plugin.getAuthConfig(cloud).doApiKey
			log.debug("api key: ${cloudApiKey} vs ${paramsApiKey}")
			if(cloudApiKey != paramsApiKey && paramsApiKey?.startsWith("******") == false) {
				log.debug("API key has changed, clearing cached datacenters")
				datacenters = []
			}
		}

		// if cloud isn't created or hasn't cached the datacenters yet, load directly from the API
		if(datacenters.size() == 0) {
			log.debug("Datacenters not cached, loading from API")
			DigitalOceanApiService apiService = new DigitalOceanApiService()


			if(paramsApiKey) {
				def response = apiService.listRegions(paramsApiKey)
				if(response.success) {
					datacenters = []
					response.data?.each {
						if(it.available == true) {
							datacenters << [name: it.name, value: it.slug]
						}
					}
				}
			} else {
				log.debug("API key not supplied, failed to load datacenters")
			}

		}

		log.debug("listDatacenters regions: $datacenters")
		def rtn = datacenters?.sort { it.name } ?: []

		return rtn
	}

	def digitalOceanImage(args) {
		log.debug "digitalOceanImage: ${args}"
		def zoneId = args?.size() > 0 ? args.getAt(0).zoneId?.toLong() : null
		def accountId = args?.size() > 0 ? args.getAt(0).accountId?.toLong() : null
		List options = []
		morpheus.virtualImage.listSyncProjectionsByCategory(accountId, "digitalocean.image.os").blockingSubscribe{options << [name: it.name, value: it.id]}
		if(zoneId) {
			morpheus.virtualImage.listSyncProjections(zoneId).blockingSubscribe{options << [name: it.name, value: it.id]}
		}
		return options.unique().sort { it.name }
	}
}

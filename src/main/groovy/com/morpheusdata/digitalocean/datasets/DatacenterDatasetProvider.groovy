package com.morpheusdata.digitalocean.datasets

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import groovy.util.logging.Slf4j
import io.reactivex.Maybe
import io.reactivex.Observable

@Slf4j
class DatacenterDatasetProvider extends AbstractDatasetProvider<ReferenceData, String> {

	public static final providerName = 'DigitalOcean Datacenters'
	public static final providerNamespace = 'digitalocean'
	public static final providerKey = 'digitalOceanDataCenters'
	public static final providerDescription = 'Get available datacenters from DigitalOcean'

	DatacenterDatasetProvider(DigitalOceanPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheusContext = morpheus
	}

	@Override
	DatasetInfo getInfo() {
		return new DatasetInfo(
			name: providerName,
			namespace: providerNamespace,
			key: providerKey,
			description: providerDescription
		)
	}

	@Override
	Class<ReferenceData> getItemType() {
		return ReferenceData.class
	}

	/**
	 * List the available datacenters for a given cloud stored in the local cache.
	 * @param datasetQuery
	 * @return a list of datacenters represented as ReferenceData
	 */
	@Override
	Observable list(DatasetQuery datasetQuery) {
		log.debug("datacenters: ${datasetQuery.parameters}")
		Long cloudId = datasetQuery.get("zoneId")?.toLong()
		if(cloudId) {
			return morpheus.async.referenceData.list(new DataQuery().withFilter("category", "digitalocean.${cloudId}.datacenter"))
		}
		return Observable.empty()
	}

	/**
	 * List the available datacenters for a given cloud stored in the local cache or fetched from the API of no cached data is available.
	 * @param datasetQuery
	 * @return a list of datacenters represented as a collection of key/value pairs.
	 */
	@Override
	Observable<Map> listOptions(DatasetQuery datasetQuery) {
		log.debug("datacenters: ${datasetQuery.parameters}")
		List datacenters = []
		String paramsApiKey = plugin.getAuthConfig(datasetQuery.parameters).doApiKey
		Long cloudId = datasetQuery.get("zoneId")?.toLong()
		Cloud cloud = null
		if(cloudId) {
			cloud = morpheus.services.cloud.get(cloudId)
		}

		// if we know the cloud then load from cached data
		datacenters = list(datasetQuery).toList().blockingGet().collect {refData -> [name: refData.name, value: refData.externalId] }

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

		return Observable.fromIterable(rtn)
	}

	@Override
	ReferenceData fetchItem(Object value) {
		// we can't really fetch the item here without more information. For our purposes, an option source for a new cloud form, we use the external ID as the value.
		// When creating a cloud we don't have an internal ID yet, so we need to use the external ID as the value.
		return null
	}

	ReferenceData item(String value) {
		// we can't really fetch the item here without more information. For our purposes, an option source for a new cloud form, we use the external ID as the value.
		// When creating a cloud we don't have an internal ID yet, so we need to use the external ID as the value.
		return null
	}

	@Override
	String itemName(ReferenceData item) {
		return item.name
	}

	@Override
	String itemValue(ReferenceData item) {
		return item.externalId
	}

	@Override
	boolean isPlugin() {
		return true
	}
}

/*
 * Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.morpheusdata.digitalocean

import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.response.ServiceResponse
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
		return new ArrayList<String>(['digitalOceanImage', 'digitalOceanVpc'])
	}

	def digitalOceanImage(args) {
		log.debug "digitalOceanImage: ${args}"
		def zoneId = args?.size() > 0 ? args.getAt(0).zoneId?.toLong() : null
		def accountId = args?.size() > 0 ? args.getAt(0).accountId?.toLong() : null
		List options = []
		morpheus.async.virtualImage.listIdentityProjectionsByCategory(accountId, (String[])["digitalocean.image.os"]).blockingSubscribe{ options << [name: it.name, value: it.id] }
		if(zoneId) {
			morpheus.async.virtualImage.listSyncProjections(zoneId).blockingSubscribe{ options << [name: it.name, value: it.id] }
		}
		return options.unique().sort { it.name }
	}

	/**
	 * Retrieves a list of VPCs available in the DigitalOcean cloud based on the provided arguments.
	 *
	 * @param args a Map: The arguments passed to the method.
	 * @return A list of VPCs. Each VPC is represented as a map with keys 'name' and 'value'.
	 */
	def digitalOceanVpc(args) {
		log.debug("digitalOceanVpc: ${args}")
		List vpcs = []
		Long cloudId = args.getAt(0)?.zoneId?.toLong()
		String paramsApiKey = plugin.getAuthConfig(args.getAt(0) as Map).doApiKey
		String datacenter = args?.config.getAt(0)?.datacenter
		Cloud cloud = null

		// if we know the cloud then load from cached data
		if(cloudId) {
			cloud = morpheus.services.cloud.get(cloudId)
			morpheus.services.cloud.pool.list(new DataQuery().withFilter("category", "digitalocean.${cloudId}.vpc"))
					.each { CloudPool refData ->
						vpcs << [name: refData.name, value: refData.externalId]
					}
		}

		// check if auth config has changed and force a refresh of the VPCs
		if(cloud) {
			def cloudApiKey = plugin.getAuthConfig(cloud).doApiKey
			log.debug("api key: ${cloudApiKey} vs ${paramsApiKey}")
			if(cloudApiKey != paramsApiKey && paramsApiKey?.startsWith("******") == false) {
				log.debug("API key has changed, clearing cached VPCs")
				vpcs = []
			}
		}

		// if cloud isn't created or hasn't cached the VPCs yet, load directly from the API
		if(vpcs.size() == 0) {
			log.debug("VPCs not cached, loading from API")
			DigitalOceanApiService apiService = new DigitalOceanApiService()

			if(paramsApiKey) {
				ServiceResponse response = apiService.listVpcs(paramsApiKey, datacenter)
				if(response.success) {
					vpcs = []
					response.data.each {
						vpcs << [name: "${it.name}", value: "${it.id}"]
					}
				}
			} else {
				log.debug("API key not supplied, failed to load VPCs")
			}

		}

		log.debug("available listVpcs: $vpcs")
		def rtn = vpcs?.sort { it.name } ?: []

		// Add default option if no VPCs found
		if (rtn.empty) {
			rtn << [name: 'No VPCs found', value: '-1', isDefault: true]
		}

		// Add "All" option at the beginning
		rtn.add(0, [name: morpheusContext.services.localization.get('gomorpheus.label.all'), value: ''])

		return rtn
	}

}

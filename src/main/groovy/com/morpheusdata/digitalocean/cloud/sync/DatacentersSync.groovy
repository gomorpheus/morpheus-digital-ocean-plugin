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

package com.morpheusdata.digitalocean.cloud.sync

import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class DatacentersSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	DigitalOceanApiService apiService
	DigitalOceanPlugin plugin

	public DatacentersSync(DigitalOceanPlugin plugin, Cloud cloud, DigitalOceanApiService apiService) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = this.plugin.morpheusContext
		this.apiService = apiService
	}

	def execute() {
		log.debug("DatacentersSync execute: ${cloud}")
		try {
			def datacenters = listDatacenters()
			if(datacenters?.size() > 0) {
				Observable<ReferenceDataSyncProjection> domainReferenceData = morpheusContext.referenceData.listByCategory(generateCategoryForCloud(cloud))
				SyncTask<ReferenceDataSyncProjection, ReferenceData, ReferenceData> syncTask = new SyncTask(domainReferenceData, datacenters)
				syncTask.addMatchFunction { ReferenceDataSyncProjection projection, ReferenceData apiDatacenter ->
					projection.externalId == apiDatacenter.keyValue
				}.onDelete { List<ReferenceDataSyncProjection> deleteList ->
					morpheusContext.referenceData.remove(deleteList)
				}.onAdd { createList ->
					morpheusContext.referenceData.create(createList).blockingGet()
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, ReferenceData>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.async.referenceData.listById(updateItems.collect { it.existingItem.id } as List<Long>).map { ReferenceData datacenter ->
						SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map> matchItem = updateItemMap[datacenter.id]
						return new SyncTask.UpdateItem<ServicePlan,Map>(existingItem:datacenter, masterItem:matchItem.masterItem)
					}
				}.onUpdate { updateList ->
					// No updates.. just add/remove
				}.start()
			}
		} catch(e) {
			log.error("Error in execute : ${e}", e)
		}
	}

	List<ReferenceData> listDatacenters() {
		log.debug("listDatacenters")
		List<ReferenceData> datacenters = []
		String apiKey = plugin.getAuthConfig(cloud).doApiKey
		def response = apiService.listRegions(apiKey)
		if(response.success) {
			List regions = response.data
			def category = generateCategoryForCloud(cloud)

			log.debug("regions: $regions")
			regions.each { it ->
				if(it.available == true ) {
					Map props = [
						code      : "${category}.${it.slug}",
						category  : category,
						name      : it.name,
						keyValue  : it.slug,
						externalId: it.slug,
						value     : it.slug,
						flagValue : it.available,
						config    : [features: it.features, sizes: it.sizes].encodeAsJSON().toString()
					]
					datacenters << new ReferenceData(props)
				}
			}
		}

		log.debug("listDatacenters regions: $datacenters")
		return datacenters
	}

	String generateCategoryForCloud(Cloud cloud) {
		return "digitalocean.${cloud.id}.datacenter"
	}

	ServiceResponse clean(Map opts=[:]) {
		log.debug("Cleaning up DatacenterSync data on digital ocean cloud with id {}", cloud.id)
		Observable<ReferenceDataSyncProjection> domainReferenceData = morpheusContext.referenceData.listByCategory(generateCategoryForCloud(cloud))
			.buffer(50)
			.blockingSubscribe { List<ReferenceDataSyncProjection> deleteList ->
				morpheusContext.referenceData.remove(deleteList).blockingGet()
			}

		return ServiceResponse.success();
	}

}

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

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.projection.CloudPoolIdentity
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * Sync class for syncing VPCs within an DigitalOcean Cloud account
 * This sync system first iterates over a list of VPCs for a particular datacenter using apiKey
 */
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

	/**
	 * Executes the synchronization process for virtual private clouds (VPCs).
	 *
	 * This method retrieves the necessary API key and datacenter information from the plugin configuration,
	 * retrieves the list of VPCs from the API service, and performs synchronization tasks based on the retrieved data.
	 *
	 * @return void
	 */
	def execute() {
		try {
			String apiKey = plugin.getAuthConfig(cloud).doApiKey
			String datacenter = cloud.configMap.datacenter
			String vpcId = cloud.configMap.vpc

			def vpcs = apiService.listVpcs(apiKey, datacenter)
			if(vpcId && !vpcId.trim().isEmpty()) {
				def vpcData = vpcs.data.find { vpc -> vpc.id == vpcId }
				vpcs.data = vpcData ? [vpcData] : []
			}

			if(vpcs.success) {
				Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, null, datacenter)
				SyncTask<CloudPoolIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, vpcs.data as Collection<Map>)
				syncTask.addMatchFunction { CloudPoolIdentity domainObject, Map apiItem ->
					domainObject.externalId == apiItem.id
				}.onDelete { removeItems ->
					removeMissingResourcePools(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<CloudPool, Map>> updateItems ->
					updateMatchedVpcs(updateItems, datacenter)
				}.onAdd { itemsToAdd ->
					addMissingVpcs(itemsToAdd, datacenter)
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<CloudPoolIdentity, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>).map { CloudPool cloudPool ->
						SyncTask.UpdateItemDto<CloudPool, Map> matchItem = updateItemMap[cloudPool.id]
						return new SyncTask.UpdateItem<CloudPool, Map>(existingItem: cloudPool, masterItem: matchItem.masterItem)
					}
				}.start()
			}
		} catch(ex) {
			log.error("VPCSync error: {}", ex)
		}
	}


	/**
	 * Adds missing virtual private clouds (VPCs) based on the provided list and region.
	 *
	 * @param addList A collection of Map objects representing the VPCs to be added.
	 * @param region A String representing the region code to be applied to the VPCs.
	 * @return void
	 */
	private addMissingVpcs(Collection<Map> addList, String region) {
		def adds = []

		for(Map cloudItem in addList) {
			def poolConfig = [
				owner      : [id: cloud.owner.id],
				type       : 'vpc',
				name       : cloudItem.name,
				displayName: "${cloudItem.name} (${region})",
				description: "${cloudItem.name} - ${cloudItem.id}",
				externalId : cloudItem.id,
				uniqueId   : cloudItem.id,
				internalId : cloudItem.name,
				refType    : 'ComputeZone',
				refId      : cloud.id,
				regionCode : region,
				active     : cloud.defaultPoolSyncActive,
				cloud      : [id: cloud.id],
				category   : "digitalocean.${cloud.id}.vpc",
				code       : "digitalocean.${cloud.id}.vpc.${cloudItem.id}"
			]
			def add = new CloudPool(poolConfig)
			adds << add
		}

		if(adds) {
			morpheusContext.async.cloud.pool.create(adds).blockingGet()
		}
	}

	/**
	 * Updates matched virtual private clouds (VPCs) based on the provided update list and region.
	 *
	 * @param updateList A list of SyncTask.UpdateItem objects representing the updates to be applied to the VPCs.
	 * @param region A String representing the region code to be applied to the VPCs. Can be null.
	 * @return void
	 */
	private updateMatchedVpcs(List<SyncTask.UpdateItem<CloudPool, Map>> updateList, String region) {
		def updates = []

		for(update in updateList) {
			def masterItem = update.masterItem
			def existing = update.existingItem
			Boolean save = false

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

	/**
	 * Removes missing resource pools from the cloud.
	 *
	 * @param removeList A list of CloudPoolIdentity objects representing the resource pools to be removed.
	 * @return void
	 */
	private removeMissingResourcePools(List<CloudPoolIdentity> removeList) {
		log.debug "removeMissingResourcePools: ${removeList?.size()}"
		morpheusContext.async.cloud.pool.remove(removeList).blockingGet()
	}

}

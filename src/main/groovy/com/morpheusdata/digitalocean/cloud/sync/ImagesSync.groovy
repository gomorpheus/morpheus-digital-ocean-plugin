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

import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.MetadataTag
import com.morpheusdata.model.OsType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.VirtualImageType
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ImagesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	DigitalOceanApiService apiService
	DigitalOceanPlugin plugin
	private Boolean userImages

	ImagesSync(DigitalOceanPlugin plugin, Cloud cloud, DigitalOceanApiService apiService, Boolean userImages=false) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = this.plugin.morpheusContext
		this.apiService = apiService
		this.userImages = userImages
	}

	def execute() {
		log.debug("ImagesSync execute: ${cloud}, userImages: ${this.userImages}")
		try {
			String imageCategory = userImages ? "digitalocean.image.user.${cloud.code}" : "digitalocean.image.os"

			List<VirtualImage> cloudItems = listImages(this.userImages)

			Observable<VirtualImageLocationIdentityProjection> existingRecords = morpheusContext.async.virtualImage.location.listIdentityProjections(
				new DataQuery().withFilters(
					new DataOrFilter(
						new DataFilter<String>("virtualImage.imageType", "qcow2"),
						new DataFilter<String>("virtualImage.virtualImageType.code", "qcow2")
					),
					new DataFilter("virtualImage.category", imageCategory),
					new DataFilter<String>("imageRegion", cloud.regionCode),
					new DataFilter<String>("refType", "ComputeZone"),
					new DataFilter<String>("refId", cloud.id)
				)
			)
			// to prevent duplicates, we will only keep the first match
			Map<String, VirtualImageLocationIdentityProjection> firstMatch = [:]
			existingRecords.toList().blockingGet().sort { it.id }.each {
				if(!firstMatch[it.externalId]) {
					firstMatch[it.externalId] = it
				}
			}
			SyncTask<VirtualImageLocationIdentityProjection, VirtualImage, VirtualImageLocation> syncTask = new SyncTask<>(existingRecords, cloudItems)
			syncTask.addMatchFunction { VirtualImageLocationIdentityProjection existingItem, VirtualImage cloudItem ->
				existingItem.externalId == cloudItem.externalId && firstMatch[existingItem.externalId].id == existingItem.id
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, VirtualImageLocation>> updateItems ->
				morpheusContext.async.virtualImage.location.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.onAdd { itemsToAdd ->
				addMissingVirtualImageLocations(itemsToAdd, imageCategory, cloud.regionCode)
			}.onUpdate { List<SyncTask.UpdateItem<VirtualImageLocation, VirtualImage>> updateItems ->
				updateMatchedVirtualImageLocations(updateItems, imageCategory, cloud.regionCode)
			}.onDelete { removeItems ->
				removeMissingVirtualImages(removeItems)
			}.start()
		} catch(e) {
			log.error("Error in execute : ${e}", e)
		}
	}

	private addMissingVirtualImageLocations(Collection<VirtualImage> addList, String imageCategory, String regionCode) {
		// check for images w/o location, i.e. system and user defined
		log.debug "addMissingVirtualImageLocations: ${cloud} ${imageCategory} ${regionCode} ${addList.size()}"

		List<String> names = addList.collect { it.name }
		Observable<VirtualImageIdentityProjection> existingRecords = morpheusContext.async.virtualImage.listIdentityProjections(new DataQuery().withFilters(
			new DataFilter<String>("imageType", "in", getImageTypes()),
			new DataFilter<Collection<String>>("name", "in", names),
			new DataOrFilter(
				new DataFilter<Boolean>("systemImage", true),
				new DataOrFilter(
					new DataFilter("owner", null),
					new DataFilter<Long>("owner.id", cloud.owner.id)
				)
			)
		))

		SyncTask<VirtualImageIdentityProjection, VirtualImage, VirtualImage> syncTask = new SyncTask<>(existingRecords, addList)
		syncTask.addMatchFunction { VirtualImageIdentityProjection existingItem, VirtualImage cloudItem ->
			existingItem.name == cloudItem.name
		}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, VirtualImage>> updateItems ->
			morpheusContext.async.virtualImage.listById(updateItems.collect { it.existingItem.id } as List<Long>)
		}.onAdd { itemsToAdd ->
			addMissingVirtualImages(itemsToAdd)
		}.onUpdate { List<SyncTask.UpdateItem<VirtualImage, VirtualImage>> updateItems ->
			updateMatchedVirtualImages(updateItems, regionCode)
		}.onDelete { removeItems ->
			//nothing to see here
		}.start()
	}

	private addMissingVirtualImages(Collection<VirtualImage> addList) {
		log.debug("Add missing virtual image: " + addList.collect { it.name })
		morpheusContext.async.virtualImage.create(addList, cloud).blockingGet()
	}

	private updateMatchedVirtualImages(List<SyncTask.UpdateItem<VirtualImage, VirtualImage>> updateList, String regionCode) {
		log.debug("updateMatchedVirtualImages: ${cloud} ${regionCode} ${updateList.size()}")
		def adds = []
		def removes = []
		for(def updateItem in updateList) {
			log.debug("Existing ${updateItem.existingItem.name} location found: ${updateItem.existingItem.imageLocations.find { it.refType == "ComputeZone" && it.refId == cloud.id && it.externalId == updateItem.masterItem.externalId }}")
			if(!updateItem.existingItem.imageLocations.find { it.refType == "ComputeZone" && it.refId == cloud.id && it.externalId == updateItem.masterItem.externalId }) {
				log.debug("Add missing location item to existing virtualImage: ${updateItem.existingItem} ${updateItem.masterItem.name}")
				adds << new VirtualImageLocation(
					virtualImage: updateItem.existingItem,
					code: updateItem.masterItem.code,
					externalId:updateItem.masterItem.externalId,
					refType:'ComputeZone',
					refId:cloud.id,
					imageName:updateItem.masterItem.name,
					imageRegion: regionCode,
					public: updateItem.masterItem.public
				)
			}

			//prune duplicates
			def locations = [:]
			for(def location : updateItem.existingItem.imageLocations.sort { it.id }) {
				def locationKey = location.refId + ':' + location.refType + ':' + location.externalId
				if(locations[locationKey]) {
					if(!location.systemImage) {
						removes << location
					}
				} else {
					locations[locationKey] = true
				}
			}
		}
		if(adds) {
			morpheusContext.async.virtualImage.location.bulkCreate(adds).blockingGet()
		}
		if(removes) {
			morpheusContext.async.virtualImage.location.bulkRemove(removes).blockingGet()
		}
	}

	private updateMatchedVirtualImageLocations(List<SyncTask.UpdateItem<VirtualImageLocation, VirtualImage>> updateList, String imageCategory, String regionCode) {
		log.debug "updateMatchedVirtualImages: ${cloud} ${regionCode} ${updateList.size()}"
		def saveLocationList = []
		def saveImageList = []
		def virtualImagesById = morpheusContext.async.virtualImage.listById(updateList.collect { it.existingItem.virtualImage.id }).toMap {it.id}.blockingGet()

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def virtualImage = virtualImagesById[existingItem.virtualImage.id]
			def cloudItem = updateItem.masterItem
			def save = false
			def saveImage = false

			if(existingItem.imageRegion != regionCode) {
				existingItem.imageRegion = regionCode
				save = true
			}

			def imageName = cloudItem.name ?: cloudItem.imageId
			if(existingItem.imageName != imageName) {
				existingItem.imageName = imageName

				if(virtualImage.imageLocations?.size() < 2) {
					virtualImage.name = imageName
					saveImage = true
				}
				save = true
			}

			if(existingItem.externalId != cloudItem.externalId) {
				existingItem.externalId = cloudItem.externalId
				save = true
			}

			def shouldUpdatePublic = existingItem.public != cloudItem.public
			log.debug("image ${existingItem.virtualImage.name} should update public: ${shouldUpdatePublic}")

			if(existingItem.public != cloudItem.public) {
				existingItem.public = cloudItem.public
				save = true
			}

			if(save) {
				saveLocationList << existingItem
			}

			if(saveImage) {
				saveImageList << virtualImage
			}
		}

		if(saveLocationList) {
			morpheusContext.async.virtualImage.location.bulkSave(saveLocationList).blockingGet()
		}
		if(saveImageList) {
			morpheusContext.async.virtualImage.save(saveImageList, cloud).blockingGet()
		}
	}

	private removeMissingVirtualImages(Collection<VirtualImageLocationIdentityProjection> removeList) {
		log.debug "removeMissingVirtualImages: ${cloud} ${removeList.size()}"
		morpheusContext.async.virtualImage.location.remove(removeList).blockingGet()
	}

	List<VirtualImage> listImages(Boolean userImages) {
		log.debug("list ${userImages ? 'User' : 'OS'} Images")
		List<VirtualImage> virtualImages = []

		String privateImage = null
		String imageType = null
		if (userImages) {
			privateImage = 'true'
		} else {
			imageType = 'distribution'
		}
		String imageCodeBase = "digitalocean.image.${userImages ? 'user' : 'os'}"

		String apiKey = plugin.getAuthConfig(cloud).doApiKey
		ServiceResponse response = apiService.listImages(apiKey, privateImage, imageType)
		if(response) {
			List images = response.data
			log.debug("images: $images")
			images.each {
				Map props = [
					name       : "${it.distribution} ${it.name}",
					externalId : it.id,
					internalId : it.slug,
					code       : "${imageCodeBase}${userImages ? ".${cloud.code}.${it.id}" : ".${it.id}"}",
					category   : "${imageCodeBase}${userImages ? ".${cloud.code}" : ""}",
					imageType  : ImageType.qcow2,
					virtualImageType: new VirtualImageType(code: "qcow2"),
					platform   : it.distribution == "Unknown" ? PlatformType.unknown : PlatformType.linux,
					minDisk    : it.min_disk_size,
					locations  : it.regions,
					account    : cloud.account,
					isCloudInit: true,
					isPublic   : (userImages == false)
				]

				if(userImages) {
					props += [
						refId      : cloud.id,
						refType    : 'ComputeZone'
					]
				}
				VirtualImage virtualImage = new VirtualImage(props)
				Map locationProps = [
					virtualImage: virtualImage,
					code        : "${imageCodeBase}${userImages ? ".${cloud.code}.${virtualImage.externalId}" : ".${virtualImage.externalId}"}",
					internalId  : virtualImage.externalId,
					externalId  : virtualImage.externalId,
					refType		: 'ComputeZone',
					refId		: cloud.id,
					imageName   : virtualImage.name,
					imageRegion : cloud.regionCode
				]
				VirtualImageLocation virtualImageLocation = new VirtualImageLocation(locationProps)
				virtualImage.imageLocations = [virtualImageLocation]
				virtualImages << virtualImage
			}
		}
		log.debug("api images: ${virtualImages.collect { it.name + " (ComputeZOne: ${it.imageLocations.getAt(0)?.refId})" }}")
		return virtualImages
	}


	List<String> getImageTypes() {
		return plugin.getProvidersByType(ProvisionProvider).collect { ProvisionProvider pp -> pp.virtualImageTypes.collect { it.code } }.flatten().unique()
	}

	ServiceResponse clean(Map opts=[:]) {
		// delete stuff
		return ServiceResponse.success();
	}

}

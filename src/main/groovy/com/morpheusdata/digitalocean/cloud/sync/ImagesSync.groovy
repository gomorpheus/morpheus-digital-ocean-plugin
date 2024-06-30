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

import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.VirtualImageType
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
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
			List<VirtualImage> apiImages = listImages(this.userImages)

			Observable<VirtualImageIdentityProjection> domainImages
			if(this.userImages) {
				domainImages = morpheusContext.virtualImage.listSyncProjectionsByCloudAndCategory(cloud.account.id, cloud.id, ["digitalocean.image.user.${cloud.code}"] as String[])
			} else {
				domainImages = morpheusContext.virtualImage.listSyncProjectionsByCategory(cloud.account.id, ["digitalocean.image.os"] as String[])
			}

			SyncTask<VirtualImageIdentityProjection, VirtualImage, VirtualImage> syncTask = new SyncTask(domainImages, apiImages)
			syncTask.addMatchFunction { VirtualImageIdentityProjection projection, VirtualImage apiImage ->
				projection.externalId == apiImage.externalId
			}.onDelete { List<VirtualImageIdentityProjection> deleteList ->
				log.debug("deleteList: ${deleteList?.size()}")
				morpheusContext.virtualImage.remove(deleteList).blockingGet()
			}.onAdd { createList ->
				log.debug("Creating ${createList?.size()} new images")
				while (createList.size() > 0) {
					List chunkedList = createList.take(50)
					createList = createList.drop(50)
					morpheusContext.virtualImage.create(chunkedList, cloud).blockingGet()
				}
			}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, VirtualImage>> updateItems ->

				Map<Long, SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
				morpheusContext.virtualImage.listById(updateItems.collect { it.existingItem.id } as Collection<Long>).map {VirtualImage virtualImage ->
					SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map> matchItem = updateItemMap[virtualImage.id]
					return new SyncTask.UpdateItem<VirtualImage,Map>(existingItem:virtualImage, masterItem:matchItem.masterItem)
				}

			}.onUpdate { updateList ->
				updateMatchedImages(updateList)
			}.start()
		} catch(e) {
			log.error("Error in execute : ${e}", e)
		}
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
					isPublic   : true
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
					imageName   : virtualImage.name,
					imageRegion : cloud.regionCode
				]
				VirtualImageLocation virtualImageLocation = new VirtualImageLocation(locationProps)
				virtualImage.imageLocations = [virtualImageLocation]
				virtualImages << virtualImage
			}
		}
		log.debug("api images: $virtualImages")
		return virtualImages
	}

	void updateMatchedImages(List<SyncTask.UpdateItem<VirtualImage,Map>> updateItems) {
		log.debug("updateMatchedImages: ${updateItems?.size()}")
		List<VirtualImage> imagesToUpdate = []

		updateItems.each {it ->
			def masterItem = it.masterItem
			VirtualImage existingItem = it.existingItem
			def doSave = false

			if(existingItem.isCloudInit != masterItem.isCloudInit) {
				existingItem.isCloudInit = masterItem.isCloudInit
				doSave = true
			}

			if(existingItem.public != masterItem.public) {
				existingItem.public = masterItem.public
				doSave = true
			}

			if(existingItem.platform != masterItem.platform) {
				existingItem.platform = masterItem.platform
				doSave = true
			}

			if(existingItem.code != masterItem.code) {
				existingItem.code = masterItem.code
				doSave = true
			}

			if(existingItem.category != masterItem.category) {
				existingItem.category = masterItem.category
				doSave = true
			}

			if(!existingItem.virtualImageType) {
				existingItem.virtualImageType = new VirtualImageType(code: "qcow2")
				doSave = true
			}

			if(doSave) {
				imagesToUpdate << existingItem
			}
		}

		log.debug("Have ${imagesToUpdate?.size()} to update")
		morpheusContext.async.virtualImage.save(imagesToUpdate, cloud).blockingGet()
	}

	ServiceResponse clean(Map opts=[:]) {
		// delete stuff
		return ServiceResponse.success();
	}

}

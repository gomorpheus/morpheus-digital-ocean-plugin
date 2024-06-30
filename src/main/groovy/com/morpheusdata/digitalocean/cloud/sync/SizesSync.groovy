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

import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.SyncList
import com.morpheusdata.model.Account
import com.morpheusdata.model.AccountPrice
import com.morpheusdata.model.AccountPriceSet
import com.morpheusdata.model.ProvisionType
import com.morpheusdata.model.ServicePlanPriceSet
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.AccountPriceIdentityProjection
import com.morpheusdata.model.projection.AccountPriceSetIdentityProjection
import com.morpheusdata.model.projection.ServicePlanPriceSetIdentityProjection
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class SizesSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	DigitalOceanApiService apiService
	DigitalOceanPlugin plugin

	static final SIZE_PREFIX = "digitalocean.size."

	SizesSync(DigitalOceanPlugin plugin, Cloud cloud, DigitalOceanApiService apiService) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = this.plugin.morpheusContext
		this.apiService = apiService
	}

	def execute() {
		log.debug("SizesSync execute: ${cloud}")
		try {
			ProvisionProvider provisionProvider = (ProvisionProvider)this.plugin.getProviderByCode('digitalocean-provision-provider')
			ProvisionType provisionType = new ProvisionType(code: provisionProvider.provisionTypeCode)
			String apiKey = plugin.getAuthConfig(cloud).doApiKey
			ServiceResponse response = apiService.listDropletSizes(apiKey)
			List<ServicePlan> servicePlans = []
			response.data?.each {
				def name = getNameForSize(it)
				def servicePlan = new ServicePlan(
						code: "${SIZE_PREFIX}${it.slug}".toString(),
						active: cloud.defaultPlanSyncActive,
						provisionTypeCode: 'digitalocean',
						description: name,
						name: name,
						editable: false,
						externalId: it.slug,
						maxCores: it.vcpus,
						maxMemory: it.memory.toLong() * 1024l * 1024l, // MB
						maxStorage: it.disk.toLong() * 1024l * 1024l * 1024l, //GB
						sortOrder: it.disk.toLong(),
						price_monthly: new BigDecimal((it.price_monthly ?: '0.0').toString()),
						price_hourly: new BigDecimal((it.price_hourly ?: '0.0').toString()),
						refType: 'ComputeZone',
						refId: cloud.id,
						provisionType: provisionType
				)
				servicePlans << servicePlan
			}

			log.debug("Service Plans to sync, total: ${servicePlans.size()}, codes: ${servicePlans.collect { it.code}}")

			if (servicePlans) {
				Observable<ServicePlanIdentityProjection> domainPlans = morpheusContext.async.servicePlan.listSyncProjections(provisionType)
				SyncTask<ServicePlanIdentityProjection, ServicePlan, ServicePlan> syncTask = new SyncTask(domainPlans, servicePlans)
				syncTask.addMatchFunction { ServicePlanIdentityProjection projection, ServicePlan apiPlan ->
					return projection.code == apiPlan.code
				}.onDelete { List<ServicePlanIdentityProjection> deleteList ->
					def deleteIds = deleteList.collect { it.id }
					log.debug("Removing ${deleteList.size()} service plans")
					List<ServicePlanPriceSet> servicePlanPriceSetDeleteList = morpheusContext.async.servicePlanPriceSet.listByServicePlanIds(deleteIds).toList().blockingGet()
					Boolean servicePlanPriceSetDeleteResult = morpheusContext.services.servicePlanPriceSet.bulkRemove(servicePlanPriceSetDeleteList)
					if(servicePlanPriceSetDeleteResult) {
						morpheusContext.services.servicePlan.bulkRemove(deleteList)
					} else {
						log.error("Failed to delete ServicePlanPriceSets associated to ServicePlans")
					}
				}.onAdd { createList ->
					while (createList.size() > 0) {
						List chunkedList = createList.take(50)
						createList = createList.drop(50)
						log.debug("Adding ${chunkedList.size()} service plans")
						Boolean servicePlanCreateSuccess = morpheusContext.services.servicePlan.bulkCreate(chunkedList)
						if(servicePlanCreateSuccess) {
							syncPlanPrices(chunkedList)
						}
					}
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ServicePlanIdentityProjection, ServicePlan>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<ServicePlanIdentityProjection, ServicePlan>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
					morpheusContext.async.servicePlan.listById(updateItems.collect { it.existingItem.id } as Collection<Long>).map { ServicePlan servicePlan ->
						SyncTask.UpdateItemDto<ServicePlanIdentityProjection, ServicePlan> matchItem = updateItemMap[servicePlan.id]
						return new SyncTask.UpdateItem<ServicePlan,ServicePlan>(existingItem:servicePlan, masterItem:matchItem.masterItem)
					}
				}.onUpdate { updateList ->
					while (updateList.size() > 0) {
						List chunkedList = updateList.take(50)
						updateList = updateList.drop(50)
						updateMatchedPlans(chunkedList)
					}
				}.start()

			}

			// clean up old duplicate data caused by a bug in v1.0.0 of this plugin sync
			cleanDuplicates()
		} catch(e) {
			log.error("Error in execute : ${e}", e)
		}
	}

	def updateMatchedPlans(List<SyncTask.UpdateItem<ServicePlan,ServicePlan>> updateItems) {
		log.debug("updateMatchedPlans: updating ${updateItems.size()} plans")
		List<ServicePlan> itemsToUpdate = []
		updateItems.each {it ->
			ServicePlan remoteItem = it.masterItem
			ServicePlan localItem = it.existingItem
			def save = false

			if(localItem.deleted == true) {
				localItem.deleted = false
				save = true
			}

			if(localItem.name != remoteItem.name) {
				localItem.name = remoteItem.name
				save = true
			}

			if(localItem.price_monthly != remoteItem.price_monthly) {
				localItem.price_monthly = remoteItem.price_monthly
				save = true
			}

			if(localItem.price_hourly != remoteItem.price_hourly) {
				localItem.price_hourly = remoteItem.price_hourly
				save = true
			}

			if(save) {
				itemsToUpdate << localItem
			}
		}

		if(itemsToUpdate.size() > 0) {
			morpheusContext.async.servicePlan.save(itemsToUpdate).blockingGet()
		}

		syncPlanPrices(updateItems.collect { it.existingItem })
	}

	def syncPlanPrices(List<ServicePlan> servicePlans) {

		def priceUnits = ['month', 'hour']
		List<String> priceSetCodes = []
		List<AccountPriceSet> apiPriceSets = []
		List<AccountPrice> apiPrices = []
		Map<String, ServicePlan> priceSetPlans = [:]
		Map<String, ServicePlan> priceSetPrices = [:]

		// fetch service plans with their updated IDs
		List<String> servicePlanCodes = servicePlans.collect { it.code }
		Map<String, ServicePlan> tmpServicePlanMap = morpheusContext.async.servicePlan.listByCode(servicePlanCodes).distinct { it.code }.toList().blockingGet().collectEntries { [(it.code):it]}

		priceUnits.each { String priceUnit ->
			// each new or existing service plan
			servicePlans.each { ServicePlan servicePlan ->
				def priceSetName = "${servicePlan.name} - ${priceUnit.capitalize()}"
				def priceSetCode = "digitalocean.size.${servicePlan.externalId}.${priceUnit}".toString()
				if(!priceSetCodes.contains(priceSetCode)) { // prevent duplicate codes
					priceSetCodes << priceSetCode

					def tmpServicePlan = tmpServicePlanMap[servicePlan.code]
					// need the id from the local plan and the price from the temp api plan
					servicePlan.id = tmpServicePlan.id
					priceSetPlans[priceSetCode] = servicePlan

					AccountPriceSet priceSet = new AccountPriceSet(
						name: priceSetName,
						code: priceSetCode,
						priceUnit: priceUnit,
						type: AccountPriceSet.PRICE_SET_TYPE.fixed.toString(),
						systemCreated: true
					)
					apiPriceSets << priceSet

					AccountPrice price = new AccountPrice(
						name: priceSetName,
						code: priceSetCode,
						active:true,
						priceType: AccountPrice.PRICE_TYPE.fixed,
						incurCharges: 'always',
						systemCreated: true,
						cost: new BigDecimal((servicePlan.getAt("price_${priceUnit}ly") ?: '0.0').toString()),
						priceUnit: priceUnit,
						markup: 0,
						markupPercent: 0,
						currency: 'usd'
					)
					apiPrices << price
					priceSetPrices[priceSetCode] = price
				}
			}
		}

		// Account Price Set
		Observable<AccountPriceSetIdentityProjection> existingPriceSets = morpheusContext.async.accountPriceSet.listSyncProjectionsByCode(priceSetCodes)
		SyncTask<AccountPriceSetIdentityProjection, AccountPriceSet, AccountPriceSet> syncTask = new SyncTask(existingPriceSets, apiPriceSets)
		syncTask.addMatchFunction { AccountPriceSetIdentityProjection projection, AccountPriceSet apiItem ->
			return projection.code == apiItem.code
		}.onDelete { List<AccountPriceSetIdentityProjection> deleteList ->
			def deleteIds = deleteList.collect { it.id }
			List<ServicePlanPriceSet> servicePlanPriceSetDeleteList = morpheusContext.async.servicePlanPriceSet.listByAccountPriceSetIds(deleteIds).toList().blockingGet()
			Boolean servicePlanPriceSetDeleteResult = morpheusContext.async.servicePlanPriceSet.remove(servicePlanPriceSetDeleteList).blockingGet()
			if(servicePlanPriceSetDeleteResult) {
				morpheusContext.async.accountPriceSet.remove(deleteList).blockingGet()
			} else {
				log.error("Failed to delete ServicePlanPriceSets associated to AccountPriceSet")
			}
		}.onAdd { List<AccountPriceSet> createList ->
			while (createList.size() > 0) {
				List chunkedList = createList.take(50)
				createList = createList.drop(50)
				createPriceSets(chunkedList, priceSetPlans)
			}
		}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<AccountPriceSetIdentityProjection, AccountPriceSet>> updateItems ->
			Map<Long, SyncTask.UpdateItemDto<AccountPriceSetIdentityProjection, AccountPriceSet>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
			morpheusContext.async.accountPriceSet.listById(updateItems.collect { it.existingItem.id } as Collection<Long>).map {AccountPriceSet priceSet ->
				SyncTask.UpdateItemDto<AccountPriceSetIdentityProjection, AccountPriceSet> matchItem = updateItemMap[priceSet.id]
				return new SyncTask.UpdateItem<AccountPriceSet,AccountPriceSet>(existingItem:priceSet, masterItem:matchItem.masterItem)
			}
		}.onUpdate { updateList ->
			while (updateList.size() > 0) {
				List chunkedList = updateList.take(50)
				updateList = updateList.drop(50)
				updateMatchedPriceSet(chunkedList, priceSetPlans)
			}
		}.observe().blockingSubscribe() { complete ->
			if(complete) {
				Observable<AccountPriceIdentityProjection> existingPrices = morpheusContext.async.accountPrice.listSyncProjectionsByCode(priceSetCodes)
				SyncTask<AccountPriceIdentityProjection, AccountPrice, AccountPrice> priceSyncTask = new SyncTask(existingPrices, apiPrices)
				priceSyncTask.addMatchFunction { AccountPriceIdentityProjection projection, AccountPrice apiItem ->
					projection.code == apiItem.code
				}.onDelete { List<AccountPriceIdentityProjection> deleteList ->
					morpheusContext.async.accountPrice.remove(deleteList).blockingGet()
				}.onAdd { createList ->
					while(createList.size() > 0) {
						List chunkedList = createList.take(50)
						createList = createList.drop(50)
						createPrice(chunkedList)
					}
				}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<AccountPriceIdentityProjection, AccountPrice>> updateItems ->
					Map<Long, SyncTask.UpdateItemDto<AccountPriceIdentityProjection, AccountPrice>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
					morpheusContext.async.accountPrice.listById(updateItems.collect { it.existingItem.id } as Collection<Long>).map { AccountPrice price ->
						SyncTask.UpdateItemDto<AccountPriceIdentityProjection, AccountPrice> matchItem = updateItemMap[price.id]
						return new SyncTask.UpdateItem<AccountPrice, AccountPrice>(existingItem: price, masterItem: matchItem.masterItem)
					}
				}.onUpdate { updateList ->
					while (updateList.size() > 0) {
						List chunkedList = updateList.take(50)
						updateList = updateList.drop(50)
						updateMatchedPrice(chunkedList)
					}
				}.start()
			}
		}

	}

	def createPriceSets(List<AccountPriceSet> createList, Map<String, ServicePlan> priceSetPlans) {
		Boolean priceSetsCreated = morpheusContext.async.accountPriceSet.create(createList).blockingGet()
		if(priceSetsCreated) {
			List<AccountPriceSet> tmpPriceSets = morpheusContext.async.accountPriceSet.listByCode(createList.collect { it.code }).distinct{it.code }.toList().blockingGet()
			syncServicePlanPriceSets(tmpPriceSets, priceSetPlans)
		}
	}

	def updateMatchedPriceSet(List<SyncTask.UpdateItem<AccountPriceSet,AccountPriceSet>> updateItems, Map<String, ServicePlan> priceSetPlans) {
		List<AccountPriceSet> itemsToUpdate = []
		updateItems.each {it ->
			AccountPriceSet remoteItem = it.masterItem
			AccountPriceSet localItem = it.existingItem
			def save = false

			if(localItem.name != remoteItem.name) {
				localItem.name = remoteItem.name
				save = true
			}

			if(save) {
				itemsToUpdate << localItem
			}
		}

		if(itemsToUpdate.size() > 0) {
			morpheusContext.accountPriceSet.save(itemsToUpdate).blockingGet()
		}

		syncServicePlanPriceSets(updateItems.collect { it.existingItem }, priceSetPlans)
	}

	def syncServicePlanPriceSets(List<AccountPriceSet> priceSets, Map<String, ServicePlan> priceSetPlans) {
		Map<String, ServicePlanPriceSet> apiItems = [:]

		// make sure we have a distinct list of price sets to prevent duplicate service plan price sets.
		// this is primarily an issue when the data already had duplicates, we will continue to create duplicates
		// and compound the problem.
		priceSets?.collect { AccountPriceSet priceSet ->
			if(apiItems[priceSet.code] == null) {
				apiItems[priceSet.code] = new ServicePlanPriceSet(priceSet: priceSet, servicePlan: priceSetPlans[priceSet.code])
			}
		}

		Observable<ServicePlanPriceSetIdentityProjection> existingItems = morpheusContext.servicePlanPriceSet.listSyncProjections(priceSets)
		SyncTask<ServicePlanPriceSetIdentityProjection, ServicePlanPriceSet, ServicePlanPriceSet> syncTask = new SyncTask(existingItems, apiItems.values())
		syncTask.addMatchFunction { ServicePlanPriceSetIdentityProjection projection, ServicePlanPriceSet apiItem ->
			return (projection.priceSet.code == apiItem.priceSet.code && projection.servicePlan.code == apiItem.servicePlan.code)
		}.onDelete { List<ServicePlanPriceSetIdentityProjection> deleteList ->
			morpheusContext.async.servicePlanPriceSet.remove(deleteList).blockingGet()
		}.onAdd { createList ->
			while(createList.size() > 0) {
				List chunkedList = createList.take(50)
				createList = createList.drop(50)
				morpheusContext.async.servicePlanPriceSet.create(chunkedList).blockingGet()
			}
		}.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ServicePlanPriceSetIdentityProjection, ServicePlanPriceSet>> updateItems ->
			Map<Long, SyncTask.UpdateItemDto<ServicePlanPriceSetIdentityProjection, ServicePlanPriceSet>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
			morpheusContext.async.servicePlanPriceSet.listById(updateItems.collect { it.existingItem.id } as Collection<Long>).map { ServicePlanPriceSet servicePlanPriceSet ->
				SyncTask.UpdateItemDto<ServicePlanPriceSetIdentityProjection, ServicePlanPriceSet> matchItem = updateItemMap[servicePlanPriceSet.id]
				return new SyncTask.UpdateItem<ServicePlanPriceSet, ServicePlanPriceSet>(existingItem: servicePlanPriceSet, masterItem: matchItem.masterItem)
			}
		}.onUpdate { updateList ->
			// do nothing
		}.start()
	}

	def createPrice(List<AccountPrice> createList) {
		Boolean itemsCreated = morpheusContext.async.accountPrice.create(createList).blockingGet()
		if(itemsCreated) {
			List<String> priceSetCodes = createList.collect { it.code }
			Map<String, AccountPriceSet> tmpPriceSets = morpheusContext.accountPriceSet.listByCode(priceSetCodes).toList().blockingGet().collectEntries { [(it.code): it] }
			morpheusContext.async.accountPrice.listByCode(priceSetCodes).blockingSubscribe { AccountPrice price ->
				AccountPriceSet priceSet = tmpPriceSets[price.code]
				if(priceSet) {
					morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, price).blockingGet()
				} else {
					log.error("createPrice addToPriceSet: Could not find matching price set for code {}", price.code)
				}
			}
		}
	}

	def updateMatchedPrice(List<SyncTask.UpdateItem<AccountPrice,AccountPrice>> updateItems) {
		// update price for pricing changes
		List<AccountPrice> itemsToUpdate = []
		Map<Long, BigDecimal> updateCostMap = [:]
		updateItems.each {it ->
			AccountPrice remoteItem = it.masterItem
			AccountPrice localItem = it.existingItem
			def doSave = false

			if(localItem.name != remoteItem.name) {
				localItem.name = remoteItem.name
				doSave = true
			}

			if(localItem.cost != remoteItem.cost) {
				log.debug("cost doesn't match, updating: local: $localItem.cost, remote: $remoteItem.cost")
				localItem.cost = remoteItem.cost
				doSave = true
			}

			if(localItem.priceType != remoteItem.priceType) {
				localItem.priceType = remoteItem.priceType
				doSave = true
			}

			if(localItem.incurCharges != remoteItem.incurCharges) {
				localItem.incurCharges = remoteItem.incurCharges
				doSave = true
			}

			if(localItem.priceUnit != remoteItem.priceUnit) {
				localItem.priceUnit = remoteItem.priceUnit
				doSave = true
			}
			//
			// if(localItem.markup != remoteItem.markup) {
			// 	localItem.markup = remoteItem.markup
			// 	doSave = true
			// }
			//
			// if(localItem.markupPercent != remoteItem.markupPercent) {
			// 	localItem.markupPercent = remoteItem.markupPercent
			// 	doSave = true
			// }

			if(localItem.currency != remoteItem.currency) {
				localItem.currency = remoteItem.currency
				doSave = true
			}

			if(doSave) {
				updateCostMap[localItem.id] = remoteItem.cost
				itemsToUpdate << localItem
			}
		}

		if(itemsToUpdate.size() > 0) {
			Boolean itemsUpdated = morpheusContext.async.accountPrice.save(itemsToUpdate).blockingGet()
			if(itemsUpdated) {
				List<String> priceSetCodes = itemsToUpdate.collect { it.code }
				Map<String, AccountPriceSet> tmpPriceSets = morpheusContext.async.accountPriceSet.listByCode(priceSetCodes).toList().blockingGet().collectEntries { [(it.code): it] }
				morpheusContext.async.accountPrice.listByCode(priceSetCodes).blockingSubscribe { AccountPrice price ->
					AccountPriceSet priceSet = tmpPriceSets[price.code]
					BigDecimal matchedCost = updateCostMap[price.id]
					if(matchedCost != null) {
						price.cost = matchedCost
					}
					if(priceSet) {
						morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, price).blockingGet()
					} else {
						log.error("createPrice addToPriceSet: Could not find matching price set for code {}", price.code)
					}
				}
			}
		}
	}

	def cleanDuplicates() {
		// service plan price sets
		// account price
		// account price set
		// service plan
	}


	def getNameForSize(sizeData) {
		def memoryName = sizeData.memory < 1000 ? "${sizeData.memory} MB" : "${sizeData.memory.div(1024l)} GB"
		return "${sizeData.description} ${sizeData.vcpus} CPU, ${memoryName} Memory, ${sizeData.disk} GB Storage"
	}

	ServiceResponse clean(Map opts=[:]) {
		// delete stuff
		return ServiceResponse.success();
	}
}

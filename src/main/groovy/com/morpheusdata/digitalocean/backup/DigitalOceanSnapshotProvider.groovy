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

package com.morpheusdata.digitalocean.backup

import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.AbstractMorpheusBackupTypeProvider
import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.core.backup.response.BackupRestoreResponse
import com.morpheusdata.core.backup.util.BackupStatusUtility
import com.morpheusdata.core.util.DateUtility
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.BackupRestore
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class DigitalOceanSnapshotProvider extends AbstractMorpheusBackupTypeProvider {

	DigitalOceanSnapshotProvider(Plugin plugin, MorpheusContext context) {
		super(plugin, context)
	}

	@Override
	String getCode() {
		return "digitaloceanSnapshot"
	}

	@Override
	String getName() {
		return "DigitalOcean VM Snapshot"
	}

	@Override
	boolean isPlugin() {
		return true
	}

	@Override
	String getContainerType() {
		return 'single'
	}

	@Override
	Boolean getCopyToStore() {
		return false
	}

	@Override
	Boolean getDownloadEnabled() {
		return false
	}

	@Override
	Boolean getRestoreExistingEnabled() {
		return true
	}

	@Override
	Boolean getRestoreNewEnabled() {
		return true
	}

	@Override
	String getRestoreType() {
		return 'offline'
	}

	@Override
	String getRestoreNewMode() {
		return null
	}

	@Override
	Boolean getHasCopyToStore() {
		return false
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		return null
	}

	@Override
	ServiceResponse refresh(Map authConfig, BackupProvider backupProvider) {
		return ServiceResponse.success();
	}

	@Override
	ServiceResponse clean(BackupProvider backupProvider, Map opts) {
		return ServiceResponse.success();
	}

	@Override
	ServiceResponse prepareExecuteBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse prepareBackupResult(BackupResult backupResult, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse executeBackup(Backup backup, BackupResult backupResult, Map executionConfig, Cloud cloud, ComputeServer computeServer, Map opts) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()

		log.debug("Executing backup {} with result {}", backup.id, backupResult.id)
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))

		String apiKey = plugin.getAuthConfig(cloud).doApiKey
		String dropletId = computeServer.externalId

		def statusMap = [backupResultId: backupResult.id, success: false, backupSizeInMb: 0, providerType:'digitalocean', config: []]
		def snapshotName = "${computeServer.name}.${computeServer.id}.${System.currentTimeMillis()}".toString()

		log.debug("Is source image cloud init: ${computeServer.sourceImage.isCloudInit}, not windows: ${computeServer.serverOs?.platform != 'windows'}")
		if(computeServer.sourceImage && computeServer.sourceImage.isCloudInit && computeServer.serverOs?.platform != 'windows') {
			getPlugin().morpheus.executeCommandOnServer(computeServer, 'sudo rm -f /etc/cloud/cloud.cfg.d/99-manual-cache.cfg; sudo cp /etc/machine-id /tmp/machine-id-old ; sync', false, computeServer.sshUsername, computeServer.sshPassword, null, null, null, null, true, true).blockingGet()
		}

		def snapshotResults = apiService.createSnapshot(apiKey, dropletId, snapshotName)
		log.debug("snapshot results: {}", snapshotResults)
		if(snapshotResults.success) {
			rtn.success = true
			// set config properties for legacy embedded compatibility.
			log.debug("Snapshot action ID: ${snapshotResults.data?.id?.toString()}")
			rtn.data.backupResult.setConfigProperty("backupRequestId", snapshotResults.data?.id?.toString())
			rtn.data.backupResult.setConfigProperty("dropletId", snapshotResults.data?.resource_id?.toString())
			rtn.data.backupResult.setConfigProperty("snapshotName", snapshotName)
			rtn.data.backupResult.internalId = snapshotResults.data?.id?.toString()
			rtn.data.backupResult.backupName = snapshotName
			rtn.data.updates = true
		} else {
			//error
			rtn.data.backupResult.sizeInMb = 0
			rtn.data.backupResult.errorOutput = snapshotResults?.msg
			rtn.data.updates = true
		}


		return rtn
	}

	@Override
	ServiceResponse refreshBackupResult(BackupResult backupResult) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		log.debug("refreshing backup result {}", backupResult.id)
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))
		try {
			Boolean doUpdate = false
			ComputeServer computeServer
			def cloudId = backupResult.zoneId ?: backupResult.backup?.zoneId
			if(cloudId) {
				Cloud cloud = plugin.morpheus.cloud.getCloudById(cloudId).blockingGet()
				def computeServerId = backupResult.serverId ?: backupResult.backup?.computeServerId
				if(computeServerId) {
					computeServer = getPlugin().morpheus.computeServer.get(computeServerId).blockingGet()
					String apiKey = plugin.getAuthConfig(cloud).doApiKey
					String actionId = backupResult.internalId ?: backupResult.getConfigProperty("backupRequestId")?.toString()
					String snapshotName = backupResult.backupName

					log.debug("refreshBackupResult snapshot action ID: ${actionId}")
					if(actionId) {
						def actionResults = apiService.getAction(apiKey, actionId)
						if(actionResults?.data?.status == "completed"){
							log.debug("snapshot complete ${actionId}")
							if(actionResults.success && actionResults.data){
								def action = actionResults.data
								def snapshotResp = getSnapshot(cloud, computeServer, snapshotName, apiService)
								def snapshot = snapshotResp.data
								log.debug("Snapshot details: ${snapshot}")

								if(snapshotResp.success && snapshot && !rtn.data.backupResult.externalId) {
									rtn.data.backupResult.externalId = snapshot?.id
									doUpdate = true
								}

								if(snapshotResp?.success && snapshot){
									rtn.data.backupResult.status = BackupStatusUtility.SUCCEEDED
									rtn.data.backupResult.startDate = DateUtility.parseDate(snapshot['created_at'])
									rtn.data.backupResult.endDate = DateUtility.parseDate(action['completed_at'])
									rtn.data.backupResult.setConfigProperty("snapshotId", snapshot.id)
									Long backupSizeMb = (long) ((snapshot['size_gigabytes'] ?: 0l) * ComputeUtility.ONE_KILOBYTE)
									Long backupSizeBytes = (long) ((snapshot['size_gigabytes'] ?: 0l) * ComputeUtility.ONE_GIGABYTE)

									log.debug("refreshBackupResult backupSizeMb: $backupSizeMb, snapshotId: $snapshot.id")
									rtn.data.backupResult.sizeInMb = backupSizeMb
									rtn.data.backupResult.sizeInBytes = backupSizeBytes

									def startDate = rtn.data.backupResult.startDate
									def endDate =rtn.data.backupResult.endDate
									if(startDate && endDate){
										def start = DateUtility.parseDate(startDate)
										def end = DateUtility.parseDate(endDate)
										rtn.data.backupResult.durationMillis = end.time - start.time
									}

									doUpdate = true
									log.debug("refreshBackupResult doUpdate: $doUpdate")
								}
							}
						} else if (actionResults?.data?.status == "errored") {
							def updatedStatus = BackupStatusUtility.FAILED
							if(rtn.data.backupResult.status != updatedStatus) {
								rtn.data.backupResult.status = updatedStatus
								doUpdate = true
							}
						}
					}
				}  else {
					rtn.data.backupResult.status = BackupStatusUtility.FAILED
					rtn.data.msg = "Associated compute server not found"
					rtn.success = false
					doUpdate = true
				}
			} else {
				rtn.data.backupResult.status = BackupStatusUtility.FAILED
				rtn.data.msg = "Associated cloud not found"
				rtn.success = false
				doUpdate = true
			}

			rtn.data.updates = doUpdate
			rtn.success = true

			if([BackupStatusUtility.FAILED, BackupStatusUtility.CANCELLED, BackupStatusUtility.SUCCEEDED].contains(rtn.data.backupResult.status)) {
				if(computeServer && computeServer.sourceImage && computeServer.sourceImage.isCloudInit && computeServer.serverOs?.platform != 'windows') {
					getPlugin().morpheus.executeCommandOnServer(computeServer, "sudo bash -c \"echo 'manual_cache_clean: True' >> /etc/cloud/cloud.cfg.d/99-manual-cache.cfg\"; sudo cat /tmp/machine-id-old > /etc/machine-id ; sudo rm /tmp/machine-id-old ; sync", true, computeServer.sshUsername, computeServer.sshPassword, null, null, null, null, true, true).blockingGet()
				}
			}
		} catch(Exception e) {
			rtn.success = false
			rtn.msg = e.getMessage()
			log.error("refreshBackupResult error: ${e}", e)
		}

		return rtn
	}

	ServiceResponse getSnapshot(Cloud cloud, ComputeServer computeServer, String snapshotName, DigitalOceanApiService apiService=null){
		apiService = apiService ?: new DigitalOceanApiService()
		def rtn = ServiceResponse.prepare()
		String apiKey = plugin.getAuthConfig(cloud).doApiKey
		def dropletId = computeServer.externalId
		def snapshotResults = apiService.listDropletSnapshots(apiKey, dropletId)
		if(snapshotResults.success){
			snapshotResults?.data.each{ snapshot ->
				log.debug("snapshot name ${snapshot.name} == snapshotName: ${snapshotName}: ${snapshot.name == snapshotName}")
				if(snapshot.name == snapshotName) {
					rtn.success = true
					rtn.data = snapshot
				}
			}
		}

		return rtn
	}

	@Override
	ServiceResponse deleteBackupResult(BackupResult backupResult, Map opts) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		log.debug("Delete backup result {}", backupResult.id)
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			def snapshotId = (backupResult.externalId ?: backupResult.getConfigProperty("snapshotId"))?.toString()
			def cloudId = backupResult.zoneId ?: backupResult.backup?.zoneId
			log.debug("deleteBackupResult zoneId: ${cloudId}, snapshotId: ${snapshotId}")
			if(snapshotId && cloudId) {
				Cloud cloud = plugin.morpheus.cloud.getCloudById(cloudId).blockingGet()
				if(cloud) {
					String apiKey = plugin.getAuthConfig(cloud).doApiKey
					def resp = apiService.deleteSnapshot(apiKey, snapshotId)
					log.debug("Delete snapshot resp: ${resp}")
					if(resp.success || resp.errorCode == '404') { //ignore snapshots already removed
						log.debug("Delete successful")
						rtn.success = true
					}
					log.debug("deleteBackupResult result: ${rtn}")
				} else {
					rtn.success = false
					rtn.msg = "Associated cloud could not be found."
				}
			} else {
				// cloud or snapshot ref missing, allow delete to continue
				rtn.success = true
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
			rtn.success = false
		}

		return rtn
	}

	@Override
	ServiceResponse configureRestoreBackup(BackupResult backupResult, Map config, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse getBackupRestoreInstanceConfig(BackupResult backupResult, Instance instanceModel, Map restoreConfig, Map opts) {
		def rtn = [success:false, data:[:]]
		try {
			def backup = backupResult.backup
			log.debug("getBackupRestoreInstanceConfig: {}", backupResult)
			log.debug("restoreConfig: {}", restoreConfig)
			log.debug("opts: {}", opts)
			restoreConfig.config = backupResult.getConfigMap() ?: [:]
			restoreConfig.instanceOpts = restoreConfig.instanceOpts ?: [:]
			// backupSetId used for configuring restore to new in morpheus services
			restoreConfig.instanceOpts.provisionOpts = [backupSetId:backupResult.backupSetId]

			rtn.data = restoreConfig
			rtn.success = true
		} catch(e) {
			log.error("getBackupRestoreInstanceConfig error: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse validateRestoreBackup(BackupResult backupResult, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse getRestoreOptions(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse restoreBackup(BackupRestore backupRestore, BackupResult backupResult, Backup backup, Map opts) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		log.debug("restoreBackup {}", backupResult)
		ServiceResponse rtn = ServiceResponse.prepare(new BackupRestoreResponse(backupRestore))
		Boolean doUpdates
		try{
			def config = backupResult.getConfigMap()
			def snapshotId = backupResult.externalId ?: config.snapshotId
			def dropletId = config.dropletId
			if(snapshotId && dropletId) {
				def tragetWorkload = plugin.morpheus.workload.get(opts?.containerId ?: backupResult.containerId).blockingGet()
				ComputeServer computeServer = tragetWorkload.server
				if (computeServer?.externalId) {
					dropletId = computeServer.externalId
				}
				Cloud cloud = computeServer.cloud
				def apiKey = plugin.getAuthConfig(cloud).doApiKey
				def restoreResults = apiService.restoreSnapshot(apiKey, dropletId, snapshotId)
				log.debug("restore results: ${restoreResults}")
				if(restoreResults.success){
					rtn.data.backupRestore.status = BackupStatusUtility.IN_PROGRESS
					rtn.data.backupRestore.externalId = dropletId
					rtn.data.backupRestore.externalStatusRef = restoreResults.data?.id
					rtn.data.backupRestore.startDate = DateUtility.parseDate(restoreResults.data["started_at"])
					rtn.success = true
					doUpdates = true
				} else {
					rtn.data.backupRestore.status = BackupStatusUtility.FAILED
					rtn.success = false
					doUpdates = true
				}
			}

		} catch(e) {
			log.error("restoreBackup: ${e}", e)
			rtn.success = false
			rtn.msg = e.getMessage()
			rtn.data.backupRestore.status = BackupStatusUtility.FAILED
			doUpdates = true
		}

		rtn.data.updates = doUpdates
		return rtn
	}

	@Override
	ServiceResponse refreshBackupRestoreResult(BackupRestore backupRestore, BackupResult backupResult) {
		DigitalOceanApiService apiService = new DigitalOceanApiService()
		log.debug("syncBackupRestoreResult restore: {}", backupRestore)
		ServiceResponse<BackupRestoreResponse> rtn = ServiceResponse.prepare(new BackupRestoreResponse(backupRestore))
		def actionId = backupRestore.externalStatusRef
		if(actionId) {
			def sourceWorkload = plugin.morpheus.workload.get(backupResult.containerId).blockingGet()
			def computeServer = sourceWorkload.server
			def cloud = computeServer.cloud
			def apiKey = plugin.getAuthConfig(cloud).doApiKey
			def actionResults = apiService.getAction(apiKey, actionId)
			if(actionResults.success && actionResults.data){
				def action = actionResults.data
				if(action.status == "completed"){
					rtn.data.backupRestore.endDate = DateUtility.parseDate(action["completed_at"])
					rtn.data.backupRestore.status = "SUCCEEDED"
					def startDate = rtn.data.backupRestore.startDate
					def endDate = rtn.data.backupRestore.endDate
					if(startDate && endDate)
						rtn.data.backupRestore.duration = endDate.time - startDate.time

					rtn.data.updates = true
				}
			}
		}

		return rtn
	}
}

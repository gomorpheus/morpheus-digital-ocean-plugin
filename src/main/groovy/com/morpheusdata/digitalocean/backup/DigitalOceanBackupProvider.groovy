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

import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.digitalocean.backup.DigitalOceanSnapshotProvider
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.BackupJobProvider
import com.morpheusdata.core.backup.BackupProvider;
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.core.backup.BackupTypeProvider
import com.morpheusdata.core.backup.MorpheusBackupProvider
import com.morpheusdata.model.BackupIntegration
import com.morpheusdata.model.BackupJob
import com.morpheusdata.model.BackupProviderType
import com.morpheusdata.model.BackupType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReplicationType
import com.morpheusdata.response.ServiceResponse

class DigitalOceanBackupProvider extends MorpheusBackupProvider {

	DigitalOceanBackupProvider(Plugin plugin, MorpheusContext morpheusContext) {
		super(plugin, morpheusContext)

		DigitalOceanSnapshotProvider digitalOceanSnapshotProvider = new DigitalOceanSnapshotProvider(plugin, morpheusContext)
		plugin.pluginProviders.put(digitalOceanSnapshotProvider.code, digitalOceanSnapshotProvider)
		addScopedProvider(digitalOceanSnapshotProvider, "digitalocean", null)
	}

}

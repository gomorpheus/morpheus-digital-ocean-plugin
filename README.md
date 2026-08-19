# Morpheus DigitalOcean Plugin

The Morpheus DigitalOcean Plugin integrates Morpheus with DigitalOcean to provide Droplet provisioning, backup via snapshots, cloud synchronisation, and option source data for regions, images, and plans. The plugin communicates with the DigitalOcean v2 REST API.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Repository structure](#repository-structure)
- [Building the plugin](#building-the-plugin)
- [License](#license)
- [Installing](#installing)
- [Detailed Usage Steps](#detailed-usage-steps)
- [API Endpoints](#api-endpoints)

---

## Features

### Droplet Provisioning

Provision and decommission DigitalOcean Droplets from Morpheus. Supports region, plan (size), image, VPC, and SSH key selection.

### Backup via Snapshots

Back up and restore Droplets using DigitalOcean snapshots managed through the Morpheus backup framework.

### Cloud Sync

Morpheus synchronises the following DigitalOcean resources for inventory:

- Droplets (virtual machines)
- Regions and datacenters
- Images (distributions and user snapshots)
- Sizes (plans)
- VPCs

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Morpheus | 9.0.0 or later |
| Java | 25 or later |
| Gradle | Use the included Gradle wrapper (`./gradlew`) |

Additional prerequisites:

- A DigitalOcean account with a Personal Access Token (read/write scope)
- Network access from the Morpheus appliance to `https://api.digitalocean.com` over HTTPS (port 443)

---

## Repository structure

```
src/main/groovy/com/morpheusdata/digitalocean/
├── DigitalOceanPlugin.groovy             - Plugin entry point; registers all providers
├── DigitalOceanApiService.groovy         - DigitalOcean v2 API client; all HTTP calls
├── DigitalOceanOptionSourceProvider.groovy - UI option source data (regions, sizes, images, VPCs)
├── backup/
│   ├── DigitalOceanBackupProvider.groovy  - BackupProvider implementation
│   └── DigitalOceanSnapshotProvider.groovy - Snapshot-based backup type
├── cloud/
│   ├── DigitalOceanCloudProvider.groovy   - CloudProvider implementation
│   └── sync/
│       ├── DatacentersSync.groovy          - Syncs regions/datacenters
│       ├── ImagesSync.groovy               - Syncs images
│       ├── SizesSync.groovy                - Syncs plans/sizes
│       ├── VirtualMachineSync.groovy       - Syncs Droplets
│       └── VPCSync.groovy                  - Syncs VPCs
├── datasets/
│   └── DatacenterDatasetProvider.groovy   - Dataset provider for datacenter selection
└── provisioning/
    └── DigitalOceanProvisionProvider.groovy - ProvisionProvider implementation
src/main/resources/i18n/               - Internationalisation message bundles
src/main/resources/scribe/             - Seed/migration scripts
build.gradle, gradle.properties        - Build configuration and plugin metadata
```

---

## Building the plugin

Run the following command to compile and package the plugin jar:

```bash
./gradlew clean build
```

The packaged jar will be written to `build/libs/`.

To execute tests, use the following command:

```bash
./gradlew test
```

---

## License

This project is licensed under the Apache License 2.0.

See the [LICENSE](LICENSE) file for details.

---

## Installing

1. Build the plugin (see [Building the plugin](#building-the-plugin)) or download a released jar.
2. In Morpheus, navigate to **Administration > Integrations > Plugins**.
3. Click **Add** and upload the `morpheus-digital-ocean-plugin-<version>.jar` from `build/libs/`.
4. Navigate to **Infrastructure > Clouds > Add** and select **DigitalOcean** to configure the integration.

---

## Detailed Usage Steps

### Adding a DigitalOcean Cloud

1. Go to **Infrastructure > Clouds > Add**.
2. Select **DigitalOcean** as the cloud type.
3. Enter a **Name** and provide your DigitalOcean **Personal Access Token**.
4. Save. Morpheus connects to the DigitalOcean API and begins syncing regions, images, sizes, VPCs, and Droplets.

### Provisioning a Droplet

1. Go to **Provisioning > Instances > Add**.
2. Select a DigitalOcean-backed instance type.
3. Choose the target **Group**, **Cloud**, and **Region**.
4. Select the **Plan** (Droplet size), **Image**, **VPC**, and **SSH Key**.
5. Complete the form and provision. Morpheus creates the Droplet via the DigitalOcean API.

### Taking a Backup

1. From an instance detail page, navigate to the **Backups** tab.
2. Click **Backup Now** to trigger an on-demand DigitalOcean snapshot.
3. Snapshots appear in the backup list once the DigitalOcean snapshot job completes.

### Restoring from a Snapshot

1. From the instance **Backups** tab, select a completed snapshot.
2. Click **Restore** and confirm. Morpheus initiates a Droplet restore from the selected snapshot.

---

## API Endpoints

This plugin communicates with the **DigitalOcean v2 API** at `https://api.digitalocean.com`. All calls use HTTPS (port 443) and are authenticated with a Bearer token.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/v2/account` | GET | Validate credentials |
| `/v2/account/keys` | GET | List SSH keys |
| `/v2/account/keys` | POST | Upload an SSH key |
| `/v2/regions` | GET | List regions |
| `/v2/sizes` | GET | List Droplet sizes/plans |
| `/v2/images` | GET | List images |
| `/v2/droplets` | GET | List Droplets |
| `/v2/droplets` | POST | Create a Droplet |
| `/v2/droplets/{id}` | GET | Get Droplet details |
| `/v2/droplets/{id}` | DELETE | Delete a Droplet |
| `/v2/droplets/{id}/actions` | POST | Perform Droplet actions (power on/off, snapshot, restore) |
| `/v2/snapshots` | GET | List snapshots |
| `/v2/vpcs` | GET | List VPCs |

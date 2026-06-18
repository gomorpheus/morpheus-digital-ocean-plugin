# Morpheus DigitalOcean Plugin

This plugin provides a full integration between [DigitalOcean](https://www.digitalocean.com) and [Morpheus](https://morpheusdata.com). It enables cloud inventory sync, Droplet provisioning, VM power and resize actions, and snapshot-based backups from within the Morpheus platform.

## Requirements

| Component | Minimum Version |
|-----------|----------------|
| Morpheus | 9.0.0 |

## Installation

1. Download the latest `.jar` from the [Releases](https://github.com/HewlettPackard/morpheus-digital-ocean-plugin/releases) page, or [build it yourself](#building).
2. In Morpheus, navigate to **Administration → Integrations → Plugins**.
3. Click **Browse** and upload the `.jar` file.
4. The **DigitalOcean** cloud type will appear after the plugin loads.

## Configuration

When adding a DigitalOcean cloud in Morpheus (**Infrastructure → Clouds → Add Cloud**), provide the following:

| Field | Description |
|-------|-------------|
| **Credentials** | Select local credentials or a stored username/API key credential |
| **Username** | DigitalOcean account username |
| **API Key** | DigitalOcean personal access token |
| **Datacenter** | DigitalOcean region to add to Morpheus |
| **VPC** | DigitalOcean VPC to associate with the cloud |
| **Inventory Existing Instances** | Inventory existing Droplets in the selected datacenter and VPC |

Credentials can also be stored as a Morpheus [Credential](https://docs.morpheusdata.com/en/latest/administration/credentials/credentials.html) and selected at cloud setup time.

## Features

### Cloud Sync

The following resources are discovered and kept in sync from DigitalOcean:

- **Datacenters** — available DigitalOcean regions
- **Service Plans** — Droplet sizes and pricing data
- **Images** — DigitalOcean OS images and user images available for provisioning
- **VPCs** — virtual private clouds in the selected datacenter
- **Virtual Machines** — DigitalOcean Droplets, including power state and network details

Any additions, updates, and removals in DigitalOcean are automatically reflected in Morpheus on the next sync cycle.

### Provisioning

Virtual machines can be provisioned into DigitalOcean directly from Morpheus using standard instance types and layouts. Supported operations include:

- Create, start, stop, and delete Droplets
- Resize Droplets to a different DigitalOcean size
- Use DigitalOcean images and uploaded SSH keys during provisioning
- Provision Linux, Windows, Docker host, and Kubernetes node server types
- Apply cloud-init and Morpheus agent customization options

### Backups

DigitalOcean Droplet snapshots are supported via the Morpheus backup framework. Supported operations include:

- Create VM snapshot backups
- Track snapshot action status and backup result metadata
- Restore snapshots to existing or new workloads

## Building

```bash
./gradlew shadowJar
```

The plugin JAR will be written to `build/libs/`.

## License

Copyright 2024 Morpheus Data, LLC. Licensed under the [Apache License, Version 2.0](LICENSE).

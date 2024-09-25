## Morpheus DigitalOcean Plugin

This is the official Morpheus plugin for interacting with the Digital Ocean API. This plugin provides droplet provisioning, snapshot create, and snapshot restore.

### Building

This is a Morpheus plugin that leverages the `morpheus-plugin-core` which can be referenced by visiting [https://developer.morpheusdata.com](https://developer.morpheusdata.com). It is a groovy plugin designed to be uploaded into a Morpheus environment via the `Administration -> Integrations -> Plugins` section. To build this product from scratch run the shadowJar gradle task on java 11:

```bash
./gradlew shadowJar
```

A jar will be produced in the `build/lib` folder that can be uploaded into a Morpheus environment.


### Configuring

Once the plugin is loaded in the environment. DigitalOcean becomes available as an option when creating a new cloud in `Infrastructure -> Clouds`.

The following information is required when adding a DigitalOcean cloud:

1. Username: Your DigitalOcean username.
1. API Key: A personal access token generated on the DigitalOcean portal.
3. Datacenter: A list of available datacenters is provided.


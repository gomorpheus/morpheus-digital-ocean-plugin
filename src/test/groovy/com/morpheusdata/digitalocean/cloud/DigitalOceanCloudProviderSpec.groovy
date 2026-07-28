package com.morpheusdata.digitalocean.cloud

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.MorpheusServicePlanService
import com.morpheusdata.core.MorpheusVirtualImageService
import com.morpheusdata.core.Plugin
import com.morpheusdata.digitalocean.DigitalOceanApiService
import com.morpheusdata.digitalocean.DigitalOceanPlugin
import com.morpheusdata.model.Cloud
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

class DigitalOceanCloudProviderSpec extends Specification {

	@Subject
	DigitalOceanCloudProvider provider
	@Shared
	DigitalOceanApiService apiService
	@Shared
	MorpheusVirtualImageService virtualImageContext
	@Shared
	MorpheusServicePlanService servicePlanContext


	def setup() {
		Plugin plugin = new DigitalOceanPlugin()
		MorpheusContext context = Mock(MorpheusContext)
		MorpheusServices services = Mock(MorpheusServices)
		MorpheusSynchronousCloudService cloudService = Mock(MorpheusSynchronousCloudService)
		context.getServices() >> services
		services.getCloud() >> cloudService
		provider = new DigitalOceanCloudProvider(plugin, context)
		apiService = Mock(DigitalOceanApiService)
		provider.apiService = apiService
	}

	void "validate - fail"() {
		given:
		Cloud cloud = new Cloud(configMap: [doApiKey: 'abc123', doUsername: 'user'])
		ValidateCloudRequest validateCloudRequest = new ValidateCloudRequest("username", "password", "local", [:])

		when:
		def res = provider.validate(cloud, validateCloudRequest)

		then:
		!res.success
		res.msg == 'Choose a datacenter'
	}

	void "validate"() {
		given:
		Cloud cloud = new Cloud(configMap: [apiKey: 'abc123', username: 'user', datacenter: 'nyc1'])
		ValidateCloudRequest validateCloudRequest = new ValidateCloudRequest("user", "abc123", "local", [:])

		when:
		def res = provider.validate(cloud, validateCloudRequest)

		then:
		1 * apiService.listRegions('abc123') >> new ServiceResponse(success: true, data: [])
		res.success
	}

	void "validate - invalid credentials"() {
		given:
		Cloud cloud = new Cloud(configMap: [apiKey: 'abc123', username: 'user', datacenter: 'nyc1'])
		ValidateCloudRequest validateCloudRequest = new ValidateCloudRequest("user", "abc123", "local", [:])

		when:
		def res = provider.validate(cloud, validateCloudRequest)

		then:
		1 * apiService.listRegions('abc123') >> new ServiceResponse(success: false)
		!res.success
		res.msg == 'Invalid credentials'
	}

	void "initializeCloud - fail"() {
		given:
		Cloud cloud = new Cloud(code: 'doCloud', configMap: [apiKey: 'abc123'])

		when:
		def resp = provider.initializeCloud(cloud)

		then:
		1 * apiService.getAccount('abc123') >> new ServiceResponse(success: false, errorCode: '400')
		!resp.success
		resp.msg == '400'
	}
}

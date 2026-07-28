package com.morpheusdata.digitalocean


import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject

class DigitalOceanApiServiceSpec extends Specification {

	@Subject
	DigitalOceanApiService service

	def setup() {
		service = new DigitalOceanApiService()
	}

	void "checkActionComplete reports a completed action as successful"() {
		given:
		def service = Spy(DigitalOceanApiService)
		service.actionPollIntervalMs = 0l

		when:
		def resp = service.checkActionComplete('abc123', '999')

		then:
		1 * service.getAction('abc123', '999') >> new ServiceResponse(success: true, data: [status: 'completed'])
		resp.success == true
	}

	void "checkActionComplete reports a #status action as failed (MORPH-3706)"() {
		given:
		def service = Spy(DigitalOceanApiService)
		service.actionPollIntervalMs = 0l

		when:
		def resp = service.checkActionComplete('abc123', '999')

		then:
		1 * service.getAction('abc123', '999') >> new ServiceResponse(success: true, data: [status: status])
		resp.success == false
		resp.data.status == status

		where:
		status << ['errored', 'failed']
	}

	void "checkActionComplete keeps polling while the action is in progress"() {
		given:
		def service = Spy(DigitalOceanApiService)
		service.actionPollIntervalMs = 0l

		when:
		def resp = service.checkActionComplete('abc123', '999')

		then:
		2 * service.getAction('abc123', '999') >>> [
			new ServiceResponse(success: true, data: [status: 'in-progress']),
			new ServiceResponse(success: true, data: [status: 'completed'])
		]
		resp.success == true
	}
}

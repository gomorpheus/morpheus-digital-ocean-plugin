package com.morpheusdata.digitalocean

import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.client.utils.URIBuilder
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.HttpApiClient.RequestOptions

@Slf4j
class DigitalOceanApiService {
	protected static final String DIGITAL_OCEAN_ENDPOINT = 'https://api.digitalocean.com'

	HttpApiClient apiClient

	DigitalOceanApiService() {
		// API Client, throttled to prevent hitting digital ocean rate limit.
		this.apiClient = new HttpApiClient(throttleRate:500l)
	}

	ServiceResponse testConnection(String apiKey) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/regions"
		ServiceResponse response = internalPaginatedGetApiRequest(apiKey, apiPath, 'regions')
		rtn.success = response.success
		rtn.data = [invalidLogin: (response?.errorCode == "401")]

		return rtn
	}

	ServiceResponse getAccount(String apiKey) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/account"
		ServiceResponse response = internalGetApiRequest(apiKey, apiPath)
		if(response.success) {
			rtn.success = true
			rtn.data = response.data.account
			rtn.results = response.data
		} else {
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse listAccountKeys(String apiKey) {
		ServiceResponse rtn = ServiceResponse.prepare()

		def apiPath = '/v2/account/keys'
		ServiceResponse response = internalPaginatedGetApiRequest(apiKey, apiPath, 'ssh_keys')
		if(response.success) {
			rtn.success = true
			rtn.data = response.data.ssh_keys
			rtn.results = response.data
		} else {
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse createAccountKey(String apiKey, String keyName, String publicKey) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/account/keys"

		def keyConfig = [public_key: publicKey, name: keyName]

		ServiceResponse response = internalPostApiRequest(apiKey, apiPath, keyConfig)
		if(response.success) {
			rtn.success = true
			rtn.results = response.data
			rtn.data = response.data.ssh_key
		} else {
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse listRegions(String apiKey) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/regions"
		ServiceResponse response = internalPaginatedGetApiRequest(apiKey, apiPath, "regions")
		if(response.success) {
			rtn.success = true
			rtn.data = response.data.regions
			rtn.results = response.data
		} else {
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse listImages(String apiKey, String privateImage=null, String imageType=null) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/images"
		Map queryParams = [:]
		if(privateImage) {
			queryParams.private = privateImage
		}
		if(imageType) {
			queryParams.type = imageType
		}
		ServiceResponse response = internalPaginatedGetApiRequest(apiKey, apiPath, "images", queryParams)
		if(response.success) {
			rtn.success = true
			rtn.data = response.data.images
			rtn.results = response.data
		} else {
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse listDropletSizes(String apiKey) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/sizes"
		ServiceResponse response = internalGetApiRequest(apiKey, apiPath)
		if(response.success) {
			rtn.success = true
			rtn.data = response.data.sizes
			rtn.results = response.data
		} else {

		}

		return rtn
	}

	ServiceResponse getDroplet(String apiKey, String dropletId) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/droplets/${dropletId}"
		ServiceResponse response = internalGetApiRequest(apiKey, apiPath)
		log.debug("getDroplet response: ${response}")
		if (response.success) {
			rtn.success = true
			rtn.data = response.data?.droplet
			rtn.results = response.data
		} else {
			rtn.success = false
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse createDroplet(String apiKey, Map dropletConfig) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/droplets"
		log.debug("createDroplet config: {}", dropletConfig)
		ServiceResponse response = internalPostApiRequest(apiKey, apiPath, dropletConfig)
		log.debug("createDroplet response: {}", response)
		if(response.success) {
			rtn.success = true
			rtn.results = response.data
			rtn.data = response.data.droplet
		} else {
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse deleteDroplet(String apiKey, String dropletId) {
		def rtn = ServiceResponse.prepare()
		String apiPath = "/v2/droplets/${dropletId}"
		ServiceResponse response = internalDeleteApiRequest(apiKey, apiPath)
		log.debug("deleteDroplet response: ${response}")
		if(response.success) {
			rtn.success = true
		} else {
			rtn.success = false
			rtn.errorCode = response.errorCode
			rtn.results = response.data
			log.debug("deleteDroplet error: ${rtn.errorCode}, results: ${rtn.results}")
		}

		return rtn
	}

	ServiceResponse performDropletAction(String apiKey, String dropletId, String action, Map config=null) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/droplets/${dropletId}/actions"
		Map body = [type: action]
		if(config) {
			body = body + config
		}
		ServiceResponse results = internalPostApiRequest(apiKey, apiPath, body)
		if (results.success) {
			rtn = checkActionComplete(apiKey, results.data.action.id.toString())
		} else {
			rtn.content = results.content
			rtn.errorCode = results.erroCode
			rtn.msg = results.errorCode
			rtn.error = response.data
		}

		return rtn
	}

	ServiceResponse checkActionComplete(String apiKey, String actionId) {
		try {
			def pending = true
			def attempts = 0
			while (pending) {
				log.debug("waiting for action complete...")
				sleep(1000l * 10l)
				ServiceResponse actionDetail = getAction(apiKey, actionId)
				if (actionDetail.success == true && actionDetail?.data?.status) {
					def tmpState = actionDetail.data.status
					if (tmpState == 'completed' || tmpState == 'failed') {
						return actionDetail
					}
				}
				attempts++
				if (attempts > 60) {
					pending = false
				}
			}
		} catch (e) {
			log.error("An Exception Has Occurred: ${e.message}")
		}
		return new ServiceResponse(success: false, msg: 'Too many failed attempts to check Droplet action status')
	}

	ServiceResponse getAction(String apiKey, String actionId) {
		def rtn = ServiceResponse.prepare()
		String apiPath = "/v2/actions/${actionId}"
		ServiceResponse response = internalGetApiRequest(apiKey, apiPath)
		log.debug("getAction response: ${response}")
		if(response.success) {
			rtn.success = true
			rtn.results = response.data
			rtn.data = response.data.action
		} else {
			rtn.success = false
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse listDropletSnapshots(String apiKey, String dropletId) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/droplets/${dropletId}/snapshots"

		ServiceResponse response = internalGetApiRequest(apiKey, apiPath)
		log.debug("listDropletSnapshots response: ${response}")
		if(response.success) {
			rtn.success = true
			rtn.results = response.data
			rtn.data = response.data.snapshots
		} else {
			rtn.success = false
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse createSnapshot(String apiKey, String dropletId, String snapshotName) {
		def rtn = ServiceResponse.prepare()
		String apiPath = "/v2/droplets/${dropletId}/actions"
		Map body = [
			'type':'snapshot',
			'name': snapshotName
		]
		ServiceResponse response = internalPostApiRequest(apiKey, apiPath, body)
		log.debug("createSnapshot response: ${response}")
		if (response.success) {
			rtn.success = true
			rtn.data = response.data.action
			rtn.results = response.data
		} else {
			rtn.success = false
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse restoreSnapshot(String apiKey, String dropletId, String snapshotId) {
		def rtn = ServiceResponse.prepare()
		String apiPath = "/v2/droplets/${dropletId}/actions"
		Map body = [
			'type':'restore',
			'image': snapshotId
		]
		log.debug("restoreSnapshot path: $apiPath, config: $body")
		ServiceResponse response = internalPostApiRequest(apiKey, apiPath, body)
		log.debug("restoreSnapshot response: ${response}")
		if (response.success) {
			rtn.success = true
			rtn.data = response.data.action
			rtn.results = response.data
		} else {
			rtn.success = false
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}

		return rtn
	}

	ServiceResponse deleteSnapshot(String apiKey, String snapshotId){
		def rtn = ServiceResponse.prepare()
		String apiPath = "/v2/snapshots/${snapshotId}"
		ServiceResponse response = internalDeleteApiRequest(apiKey, apiPath)
		log.debug("deleteSnapshot response: ${response}")
		if(response.success) {
			rtn.success = true
		} else {
			rtn.success = false
			rtn.errorCode = response.errorCode
			rtn.results = response.data
			log.debug("deleteSnapshot error: ${rtn.errorCode}, results: ${rtn.results}")
		}

		return rtn
	}

	/**
	 * Retrieves a list of VPCs from the DigitalOcean API.
	 *
	 * @param apiKey The API key used to authenticate the request.
	 * @param dataCenter The data center (region) for which to fetch VPCs.
	 * @return A ServiceResponse object containing the list of VPCs or an error message.
	 */
	ServiceResponse listVpcs(String apiKey, String dataCenter) {
		ServiceResponse rtn = ServiceResponse.prepare()
		String apiPath = "/v2/vpcs"
		def regionMap = [region: dataCenter]
		ServiceResponse response = internalPaginatedGetApiRequest(apiKey, apiPath, "vpcs", regionMap)
		if(response.success) {
			rtn.success = true
			rtn.data = response.data.vpcs
			rtn.results = response.data
		} else {
			rtn.errorCode = response.errorCode
			rtn.results = response.data
		}
		return rtn
	}

	private ServiceResponse internalGetApiRequest(String apiKey, String path, Map queryParams=null, Map headers=null) {
		internalApiRequest(apiKey, path, 'GET', null, queryParams, headers)
	}

	private ServiceResponse internalPaginatedGetApiRequest(String apiKey, String path, String resultKey, Map queryParams=null, Map addHeaders=null) {
		ServiceResponse rtn = ServiceResponse.prepare()
		List resultList = []
		def pageNum = 1
		def perPage = 10
		Map tmpQueryParams = [per_page: "${perPage}", page: "${pageNum}"]
		if(queryParams) {
			tmpQueryParams += queryParams
		}
		def theresMore = true
		while (theresMore) {
			tmpQueryParams.page = "${pageNum}"
			ServiceResponse response = internalGetApiRequest(apiKey, path, tmpQueryParams, addHeaders)
			log.debug("internalPaginatedGetApiRequest response: $response")
			if(response.success) {
				rtn.success = true
				resultList += response.data?."$resultKey" ?: []
				theresMore = response.data?.links?.pages?.next ? true : false
				pageNum++
			} else {
				theresMore = false
				rtn.success = false
				rtn.results = response.results
				rtn.errorCode = response.errorCode
			}
		}

		if(rtn.success) {
			rtn.data = [
				(resultKey): resultList
			]
		}

		return rtn
	}

	private ServiceResponse internalPostApiRequest(String apiKey, String path, Map body=null, Map queryParams=null, Map headers=null) {
		internalApiRequest(apiKey, path, 'POST', body, queryParams, headers)
	}

	private ServiceResponse internalPatchApiRequest(String apiKey, String path, Map body=null, Map queryParams=null, Map headers=null) {
		internalApiRequest(apiKey, path, 'PATCH', body, queryParams, headers)
	}

	private ServiceResponse internalDeleteApiRequest(String apiKey, String path, Map queryParams=null, Map headers=null) {
		internalApiRequest(apiKey, path, 'DELETE', null, queryParams, headers)
	}

	private ServiceResponse internalApiRequest(String apiKey, String path, String requestMethod='GET', Map body=null, Map queryParams=null, Map addHeaders=null) {
		def rtn = ServiceResponse.prepare()
		try {
			RequestOptions requestOptions = new RequestOptions(apiToken: apiKey, contentType: 'application/json')
			if(body) {
				requestOptions.body = body
			}
			if(queryParams) {
				requestOptions.queryParams = queryParams
			}
			requestOptions.headers = ['Accept':'application/json', 'ContentType':'application/json']
			if(addHeaders) {
				addHeaders.each {
					requestOptions.headers[it.key] = it.value
				}
			}
			rtn = apiClient.callJsonApi(DIGITAL_OCEAN_ENDPOINT, path, requestOptions, requestMethod)

			// handle occasional "API failed to respond" issue, might be a local network issue or slow API, just try a again a few times
			Integer attempts = 1;
			if(rtn.success == false) {
				Boolean noResponse = (rtn.success == false && rtn.getError()?.toLowerCase()?.contains("failed to respond"))
				Boolean serviceUnavailable = (rtn.success == false && rtn.getErrorCode() == '503')
				while((noResponse == true || serviceUnavailable == true) && attempts <= 5) {
					log.debug("API failed to respond, attempting to try again (attempt $attempts)")
					rtn = apiClient.callJsonApi(DIGITAL_OCEAN_ENDPOINT, path, requestOptions, requestMethod)
					noResponse = (rtn.success == false && rtn.getError()?.toLowerCase()?.contains("failed to respond"))
					if(noResponse == true) {
						sleep(500l)
						if(attempts == 5) {
							log.debug("API failed to respond after ${attempts} attempts, returning failed API response.")
						}
					}
					attempts++
				}
			}

		} catch(e) {
			log.error("error during api request {}: {}", path, e, e)
		}
		return rtn
	}
}

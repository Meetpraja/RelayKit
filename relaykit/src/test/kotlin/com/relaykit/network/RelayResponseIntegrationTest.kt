package com.relaykit.network

import android.content.Context
import com.relaykit.network.json.RelayJson
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.result.RelayResult
import com.relaykit.network.result.getOrThrow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class RelayResponseIntegrationTest {
    @Serializable data class User(val id: Int, val name: String)
    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        client = RelayClient.create(context) { baseUrl = server.url("/").toString() }
    }

    @After fun shutdown() { server.shutdown() }

    @Test fun parsesTypedAndDynamicResponses() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":7,"name":"Ada"}"""))
        val typed = client.call<User>("user", HttpMethod.GET)
        assertTrue(typed is RelayResult.Success)
        assertEquals("Ada", (typed as RelayResult.Success).data.name)
        assertTrue(typed.rawBody!!.contains("Ada"))

        server.enqueue(MockResponse().setBody("""{"total":4}"""))
        val dynamic = client.call<RelayJson>("stats", HttpMethod.GET)
        assertEquals(4, dynamic.getOrThrow()["total"].asInt())
    }

    @Test fun classifiesMalformedTypedBodyAsParsingFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json"))
        val result = client.call<User>("user", HttpMethod.GET)
        assertEquals(com.relaykit.network.error.ErrorType.PARSING, result.errorType)
        assertEquals("not-json", result.rawBody)
    }

    @Test fun supportsNoContentAsUnit() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.call<Unit>("delete", HttpMethod.DELETE)
        assertTrue(result is RelayResult.Success)
    }
}

package com.relaykit.network

import android.content.Context
import com.relaykit.network.error.ErrorType
import com.relaykit.network.json.RelayJson
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.result.RelayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.TimeUnit

class RelayResponseManagementTest {
    @Serializable private data class Value(val value: Int)

    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        client = RelayClient.create(context) { baseUrl = server.url("api/").toString() }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun successAndHttpErrorBodiesAreConsumedAndLaterRequestsStillWork(): Unit = runBlocking {
        server.enqueue(MockResponse().setHeader("X-Response", "first").setBody("{\"value\":1}"))
        val first = client.request("first", HttpMethod.GET)
        assertTrue(first.isSuccessful)
        assertEquals(200, first.statusCode)
        assertEquals("first", first.headers.entries.first { it.key.equals("X-Response", ignoreCase = true) }.value)
        assertEquals("{\"value\":1}", first.body)
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse().setResponseCode(500).setHeader("X-Response", "error").setBody("{\"message\":\"failed\"}"))
        val error = client.call<RelayJson>("error", HttpMethod.GET)
        assertEquals(ErrorType.HTTP, error.errorType)
        assertEquals(500, error.statusCode)
        assertEquals("failed", error.json["message"].asString())
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse().setBody("{\"value\":3}"))
        val next = client.call<Value>("next", HttpMethod.GET)
        assertEquals(3, (next as RelayResult.Success).data.value)
        server.takeRequest(5, TimeUnit.SECONDS)
    }

    @Test fun rawAndTypedApisHandleEmptyBodiesAndNoContentExplicitly(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val raw = client.request("empty-raw", HttpMethod.GET)
        assertTrue(raw.isSuccessful)
        assertEquals(200, raw.statusCode)
        assertEquals("", raw.body)
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse().setResponseCode(204))
        val noContent = client.call<Unit>("no-content", HttpMethod.GET)
        assertTrue(noContent is RelayResult.Success)
        assertEquals(204, noContent.statusCode)
        assertEquals("", noContent.rawBody)
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse().setBody(""))
        val emptyTyped = client.call<Value>("empty-typed", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, emptyTyped.errorType)
        assertNotNull(emptyTyped.errorOrNull?.cause)
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse().setResponseCode(204))
        val emptyDynamic = client.call<RelayJson>("empty-dynamic", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, emptyDynamic.errorType)
        server.takeRequest(5, TimeUnit.SECONDS)
    }

    @Test fun invalidTypedJsonIsClosedBeforeFailureReturns(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("{invalid"))
        val invalid = client.call<Value>("invalid", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, invalid.errorType)
        assertNotNull(invalid.errorOrNull?.cause)
        assertEquals("{invalid", invalid.rawBody)
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse().setBody("{\"value\":7}"))
        val recovered = client.call<Value>("after-invalid", HttpMethod.GET)
        assertEquals(7, (recovered as RelayResult.Success).data.value)
        server.takeRequest(5, TimeUnit.SECONDS)
    }

    @Test fun delayedResponseCanBeCancelledAndTheClientRemainsUsable(): Unit = runBlocking {
        server.enqueue(MockResponse().setBodyDelay(500, TimeUnit.MILLISECONDS).setBody("\"too-late\""))
        val pending = async(Dispatchers.IO) { client.call<String>("delayed", HttpMethod.GET) }
        assertNotNull("request must arrive before cancellation", server.takeRequest(5, TimeUnit.SECONDS))
        pending.cancel()
        try {
            pending.await()
            throw AssertionError("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: Retrofit cancellation closes the active response exchange.
        }

        server.enqueue(MockResponse().setBody("\"ok\""))
        val next = client.call<String>("after-cancel", HttpMethod.GET)
        assertEquals("ok", (next as RelayResult.Success).data)
        server.takeRequest(5, TimeUnit.SECONDS)
    }

    @Test fun repeatedRequestsAndLargeRawBodyKeepTheCompletePublicSnapshot(): Unit = runBlocking {
        repeat(30) { index ->
            server.enqueue(MockResponse().setBody("\"item-$index\""))
            val result = client.call<String>("repeat-$index", HttpMethod.GET)
            assertEquals("item-$index", (result as RelayResult.Success).data)
            server.takeRequest(5, TimeUnit.SECONDS)
        }

        val largeBody = "z".repeat(256 * 1024)
        server.enqueue(MockResponse().setBody(largeBody))
        val raw = client.request("large", HttpMethod.GET)
        assertEquals(200, raw.statusCode)
        assertEquals(largeBody.length, raw.body.length)
        assertEquals(largeBody, raw.body)
        server.takeRequest(5, TimeUnit.SECONDS)
    }

    @Test fun concurrentTypedResponsesRemainAssociatedAndIndependent(): Unit = runBlocking {
        val count = 24
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val index = request.requestUrl!!.encodedPath.substringAfterLast('-').toInt()
                return MockResponse().setBody("{\"value\":$index}")
            }
        }
        val results = (0 until count).map { index -> async(Dispatchers.IO) {
            client.call<Value>("parallel-$index", HttpMethod.GET)
        } }.map { it.await() }
        results.forEachIndexed { index, result ->
            assertTrue(result is RelayResult.Success)
            assertEquals(index, (result as RelayResult.Success).data.value)
            assertEquals(200, result.statusCode)
        }
        repeat(count) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
    }
}

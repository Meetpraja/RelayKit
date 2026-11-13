package com.relaykit.network

import android.content.Context
import com.relaykit.network.config.Auth
import com.relaykit.network.error.ErrorType
import com.relaykit.network.event.RelayEvent
import com.relaykit.network.json.RelayJson
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.result.RelayResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException
import java.util.concurrent.TimeUnit

class RelayErrorHandlingTest {
    @Serializable private data class RequiredValue(val value: Int)

    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
    }

    @After fun tearDown() { server.shutdown() }

    private fun client(block: com.relaykit.network.config.RelayConfigBuilder.() -> Unit = {}): RelayClient =
        RelayClient.create(context) { baseUrl = server.url("api/").toString(); block() }

    @Test fun commonHttpStatusesRemainStructuredAndPreserveErrorBodies() = runBlocking {
        val relay = client()
        val cases = listOf(400, 401, 404, 429, 500)
        for (status in cases) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"message":"server says $status","code":$status}"""))
            val result = relay.call<RelayJson>("status-$status", HttpMethod.GET)
            assertTrue("status=$status result=$result", result is RelayResult.Failure)
            assertEquals(ErrorType.HTTP, result.errorType)
            assertEquals(status, result.statusCode)
            assertEquals("server says $status", result.message)
            assertEquals("server says $status", result.json["message"].asString())
            assertTrue(result.rawBody!!.contains("\"code\":$status"))
        }
    }

    @Test fun rawAndTypedApisKeepTheirDocumentedHttpAndNetworkContracts() = runBlocking {
        val relay = client()
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val raw = relay.request("raw-404", HttpMethod.GET)
        assertFalse(raw.isSuccessful)
        assertEquals(404, raw.statusCode)
        assertEquals("not found", raw.body)

        val offlineServer = MockWebServer().also { it.start() }
        val offlineUrl = offlineServer.url("/").toString()
        offlineServer.shutdown()
        val offlineClient = RelayClient.create(context) { baseUrl = offlineUrl }
        val typed = offlineClient.call<RelayJson>("typed-disconnect", HttpMethod.GET)
        assertEquals(ErrorType.NETWORK, typed.errorType)
        assertNotNull(typed.errorOrNull?.cause)

        try {
            offlineClient.request("raw-disconnect", HttpMethod.GET)
            throw AssertionError("Raw request should propagate connectivity exceptions")
        } catch (error: IOException) {
            assertTrue(error !is java.net.SocketTimeoutException)
        }
    }

    @Test fun failureBodiesAreRetainedWithinTheConfiguredSafetyLimit() = runBlocking {
        val relay = client()
        server.enqueue(MockResponse().setResponseCode(500).setBody("x".repeat(20 * 1024)))
        val failure = relay.call<RelayJson>("large-error", HttpMethod.GET) as RelayResult.Failure
        assertEquals(500, failure.statusCode)
        assertTrue(failure.rawBody!!.length <= 16 * 1024)
    }

    @Test fun readAndCallTimeoutsHaveTheTimeoutCategoryAndOriginalCause() = runBlocking {
        val relay = client {
            timeouts {
                read = 100.milliseconds
                call = 1.seconds
            }
        }
        server.enqueue(MockResponse().setHeadersDelay(500, TimeUnit.MILLISECONDS).setBody("\"late\""))
        val readTimeout = relay.call<String>("read-timeout", HttpMethod.GET)
        assertEquals(ErrorType.TIMEOUT, readTimeout.errorType)
        assertNotNull(readTimeout.errorOrNull?.cause)
        val timeoutEvent = relay.events.replayCache.filterIsInstance<RelayEvent.Failed>().last()
        assertEquals(ErrorType.TIMEOUT, timeoutEvent.errorType)

        val callTimeoutClient = client {
            timeouts {
                read = 2.seconds
                call = 100.milliseconds
            }
        }
        server.enqueue(MockResponse().setHeadersDelay(500, TimeUnit.MILLISECONDS).setBody("\"late\""))
        val callTimeout = callTimeoutClient.call<String>("call-timeout", HttpMethod.GET)
        assertEquals(ErrorType.TIMEOUT, callTimeout.errorType)
        assertNotNull(callTimeout.errorOrNull?.cause)
    }

    @Test fun malformedDynamicAndTypedJsonAreParsingFailuresWithCauses() = runBlocking {
        val relay = client()
        server.enqueue(MockResponse().setBody("not-json"))
        val dynamic = relay.call<RelayJson>("invalid-dynamic", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, dynamic.errorType)
        assertTrue(dynamic.errorOrNull?.cause is SerializationException)
        assertEquals("not-json", dynamic.rawBody)

        server.enqueue(MockResponse().setBody("not-json"))
        val typedInvalid = relay.call<RequiredValue>("invalid-typed", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, typedInvalid.errorType)
        assertTrue(typedInvalid.errorOrNull?.cause is SerializationException)

        server.enqueue(MockResponse().setBody("{}"))
        val missing = relay.call<RequiredValue>("missing-field", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, missing.errorType)
        assertTrue(missing.errorOrNull?.cause is SerializationException)
        assertEquals("{}", missing.rawBody)

        server.enqueue(MockResponse().setBody("{\"value\":\"not-an-integer\"}"))
        val incompatible = relay.call<RequiredValue>("incompatible-field", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, incompatible.errorType)
        assertTrue(incompatible.errorOrNull?.cause is SerializationException)
    }

    @Test fun unexpectedExceptionsBecomeUnknownFailuresAndRetainTheOriginalCause() = runBlocking {
        val relay = client()
        val cause = IllegalStateException("auth provider failed")
        val result = relay.call<RelayJson>("unknown", HttpMethod.GET, auth = Auth.Bearer { throw cause })
        assertEquals(ErrorType.UNKNOWN, result.errorType)
        assertEquals(cause::class, result.errorOrNull?.cause?.let { it::class })
        assertEquals(cause.message, result.errorOrNull?.cause?.message)
        assertEquals("The request failed", result.message)
    }

    @Test fun bodyLoggingOmitsUnparseableTextInsteadOfLoggingItVerbatim() = runBlocking {
        val logs = mutableListOf<String>()
        val relay = client {
            logging(LogLevel.BODY, LogSink(logs::add))
        }
        server.enqueue(MockResponse().setBody("access_token=must-not-appear"))
        relay.request("plain-text", HttpMethod.GET)
        val output = logs.joinToString("\n")
        assertFalse(output.contains("must-not-appear"))
        assertTrue(output.contains("body omitted"))
    }
}

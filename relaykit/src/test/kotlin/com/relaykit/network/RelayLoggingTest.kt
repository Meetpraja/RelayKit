package com.relaykit.network

import android.content.Context
import com.relaykit.network.event.RelayEvent
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import com.relaykit.network.request.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

class RelayLoggingTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        logs.clear()
    }
    @After fun shutdown() { server.shutdown() }

    private fun client(block: com.relaykit.network.config.RelayConfigBuilder.() -> Unit = {}): RelayClient =
        RelayClient.create(context) { baseUrl = server.url("api/").toString(); block() }

    @Test fun noneLevelProducesNoNetworkLogs() = runBlocking {
        val client = client { logging(LogLevel.NONE, LogSink(logs::add)) }
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals("ok", client.request("none", HttpMethod.GET).body)
        assertTrue(logs.isEmpty())
    }

    @Test fun basicLogsMethodUrlStatusAndDuration() = runBlocking {
        val client = client { logging(LogLevel.BASIC, LogSink(logs::add)) }
        server.enqueue(MockResponse().setResponseCode(202).setBody("accepted"))
        client.request("resource?id=4", HttpMethod.GET)
        val output = logs.joinToString("\n")
        assertTrue(output.contains("GET"))
        assertTrue(output.contains("resource?id=4"))
        assertTrue(output.contains("status=202"))
        assertTrue(output.contains("duration_ms="))
        assertFalse(output.contains("Headers:"))
        assertFalse(output.contains("Body:"))
    }

    @Test fun headersAreCaseInsensitivelyRedactedOnRequestAndResponse() = runBlocking {
        val client = client {
            header("aUtHoRiZaTiOn", "auth-secret")
            header("Proxy-Authorization", "proxy-secret")
            header("Cookie", "request-cookie-secret")
            header("X-Internal-Key", "custom-header-secret")
            redactHeaders("x-internal-key", "x-response-secret")
            logging(LogLevel.HEADERS, LogSink(logs::add))
        }
        server.enqueue(MockResponse().setBody("ok")
            .addHeader("sEt-CoOkIe", "response-cookie-secret")
            .addHeader("X-Response-Secret", "response-custom-secret"))
        client.request("headers", HttpMethod.GET)
        val output = logs.joinToString("\n")
        listOf("auth-secret", "proxy-secret", "request-cookie-secret", "custom-header-secret", "response-cookie-secret", "response-custom-secret").forEach {
            assertFalse("secret leaked: $it", output.contains(it))
        }
        assertTrue(output.lowercase().contains("authorization=******"))
        assertTrue(output.contains("Cookie=******"))
        assertTrue(output.lowercase().contains("set-cookie=******"))
    }

    @Test fun customSensitiveHeadersAreRedactedAndOrdinaryHeadersRemainVisible() = runBlocking {
        val client = client {
            header("X-Tenant", "tenant-public")
            header("X-Private-Session", "session-secret")
            redactHeaders("x-private-session")
            logging(LogLevel.HEADERS, LogSink(logs::add))
        }
        server.enqueue(MockResponse().setBody("ok").addHeader("X-Private-Session", "response-session-secret"))
        client.request("custom-header", HttpMethod.GET)
        val output = logs.joinToString("\n")
        assertTrue(output.contains("tenant-public"))
        assertFalse(output.contains("session-secret"))
        assertFalse(output.contains("response-session-secret"))
    }

    @Test fun queryRedactionHandlesEncodedNamesUserInfoAndCustomNamesWithoutChangingRequest() = runBlocking {
        val client = client {
            redactQueryParameters("session-id")
            logging(LogLevel.BASIC, LogSink(logs::add))
        }
        val url = server.url("api/query")
            .newBuilder()
            .username("url-user-secret")
            .password("url-password-secret")
            .addEncodedQueryParameter("ACCESS%5FTOKEN", "encoded-query-secret")
            .addQueryParameter("session-id", "custom-query-secret")
            .addQueryParameter("visible", "public-value")
            .build()
        server.enqueue(MockResponse().setBody("ok"))
        client.request(url.toString(), HttpMethod.GET)
        val recorded = server.takeRequest()
        assertEquals("encoded-query-secret", recorded.requestUrl!!.queryParameter("ACCESS_TOKEN"))
        assertEquals("custom-query-secret", recorded.requestUrl!!.queryParameter("session-id"))
        val output = logs.joinToString("\n")
        listOf("url-user-secret", "url-password-secret", "encoded-query-secret", "custom-query-secret").forEach {
            assertFalse("secret leaked: $it", output.contains(it))
        }
        assertTrue(output.contains("ACCESS_TOKEN=******"))
        assertTrue(output.contains("session-id=******"))
        assertTrue(output.contains("visible=public-value"))
    }

    @Test fun bodyRedactionIsCaseInsensitiveAndRecursesThroughObjectsAndArrays() = runBlocking {
        val client = client { logging(LogLevel.BODY, LogSink(logs::add)) }
        val requestBody = mapOf(
            "AccessToken" to "request-access-secret",
            "items" to listOf(
                mapOf("CLIENTsecret" to "request-client-secret", "visible" to "request-public"),
                mapOf("cardVerificationValue" to "request-card-secret", "cvv" to "request-cvv-secret"),
            ),
        )
        server.enqueue(MockResponse().setBody("""{"RefreshToken":"response-refresh-secret","items":[{"Password":"response-password-secret","visible":"response-public"},{"card_security_code":"response-card-secret"}]}"""))
        client.request("nested", HttpMethod.POST, body = requestBody)
        val output = logs.joinToString("\n")
        listOf("request-access-secret", "request-client-secret", "request-card-secret", "request-cvv-secret", "response-refresh-secret", "response-password-secret", "response-card-secret").forEach {
            assertFalse("secret leaked: $it", output.contains(it))
        }
        assertTrue(output.contains("request-public"))
        assertTrue(output.contains("response-public"))
        assertTrue(output.contains("******"))
    }

    @Test fun malformedAndUnsupportedBodiesAreOmittedInsteadOfLeaking() = runBlocking {
        val client = client { logging(LogLevel.BODY, LogSink(logs::add)) }
        server.enqueue(MockResponse().setBody("invalid-body-with-secret-value"))
        client.request("malformed", HttpMethod.GET)
        val output = logs.joinToString("\n")
        assertFalse(output.contains("invalid-body-with-secret-value"))
        assertTrue(output.contains("<body omitted:"))
    }

    @Test fun bodySizeLimitOmitsLargeLogsWithoutChangingReturnedOrSentBodies() = runBlocking {
        val payload = "x".repeat(300)
        val client = client { logging(LogLevel.BODY, LogSink(logs::add), maxBodySize = 32) }
        server.enqueue(MockResponse().setBody("{\"visible\":\"$payload\"}"))
        val response = client.request("large", HttpMethod.POST, body = mapOf("visible" to payload, "password" to "large-secret"))
        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains(payload))
        assertTrue(response.body.contains(payload))
        val output = logs.joinToString("\n")
        assertFalse(output.contains(payload))
        assertFalse(output.contains("large-secret"))
        assertTrue(output.contains("exceeds 32 character limit"))
    }

    @Test fun prettyPrintingIsOptionalAndOnlyAppliesAfterRedaction() = runBlocking {
        val client = client {
            logging(LogLevel.BODY, LogSink(logs::add))
            prettyPrintJsonBodies()
        }
        server.enqueue(MockResponse().setBody("""{"password":"pretty-secret","value":1}"""))
        client.request("pretty", HttpMethod.GET)
        val output = logs.joinToString("\n")
        assertTrue(output.contains("\"value\": 1"))
        assertTrue(output.contains("\n    \"password\": \"******\""))
        assertFalse(output.contains("pretty-secret"))
    }

    @Test fun compactJsonRemainsAvailableByDefault() = runBlocking {
        val client = client { logging(LogLevel.BODY, LogSink(logs::add)) }
        server.enqueue(MockResponse().setBody("{\"value\":1}"))
        client.request("compact", HttpMethod.GET)
        assertTrue(logs.joinToString().contains("{\"value\":1}"))
        assertFalse(logs.joinToString().contains("\"value\": 1"))
    }

    @Test fun authRefreshAttemptsAreIdentifiedAndSecretsStayRedacted() = runBlocking {
        val client = client {
            bearer()
            logging(LogLevel.HEADERS, LogSink(logs::add))
            refreshBearerToken { "new-refresh-access-secret" }
        }
        client.setToken("old-access-secret")
        server.enqueue(MockResponse().setResponseCode(401).addHeader("Set-Cookie", "refresh-cookie-secret"))
        server.enqueue(MockResponse().setBody("ok"))
        client.request("auth-refresh", HttpMethod.GET)
        val output = logs.joinToString("\n")
        listOf("old-access-secret", "new-refresh-access-secret", "refresh-cookie-secret").forEach { assertFalse(output.contains(it)) }
        assertTrue(output.contains("status=401"))
        assertTrue(output.contains("auth_refresh=false"))
        assertTrue(output.contains("auth_refresh=true"))
        assertTrue(output.contains("Authorization=******"))
    }

    @Test fun refreshClientLogsAreSuppressedInsideRefreshCallback() = runBlocking {
        val refreshLogs = Collections.synchronizedList(mutableListOf<String>())
        val refreshClient = client { logging(LogLevel.BODY, LogSink(refreshLogs::add)) }
        val client = client {
            bearer()
            logging(LogLevel.BODY, LogSink(logs::add))
            refreshBearerToken {
                refreshClient.request("refresh", HttpMethod.POST, body = mapOf("refresh_token" to "external-refresh-secret"))
                "new-access-secret"
            }
        }
        client.setToken("expired-access-secret")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("refresh-response-secret"))
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals("ok", client.request("protected", HttpMethod.GET).body)
        val output = logs.joinToString("\n") + refreshLogs.joinToString("\n")
        listOf("external-refresh-secret", "refresh-response-secret", "new-access-secret", "expired-access-secret").forEach {
            assertFalse("secret leaked: $it", output.contains(it))
        }
        assertTrue(refreshLogs.isEmpty())
    }

    @Test fun retryAttemptsAreLoggedIndividuallyButLifecycleEventsRemainLogical() = runBlocking {
        val interceptorCalls = AtomicInteger()
        val client = client {
            logging(LogLevel.BASIC, LogSink(logs::add))
            interceptor(Interceptor { chain -> interceptorCalls.incrementAndGet(); chain.proceed(chain.request()) })
            retry { enabled = true; maxRetries = 1; initialDelay = Duration.ZERO; jitterFactor = 0.0 }
        }
        server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setBody("ok"))
        client.request("retry-log", HttpMethod.GET)
        val output = logs.joinToString("\n")
        assertTrue(output.contains("attempt=1"))
        assertTrue(output.contains("attempt=2"))
        assertEquals(2, interceptorCalls.get())
        assertEquals(1, client.events.replayCache.count { it is RelayEvent.Started })
        assertEquals(1, client.events.replayCache.count { it is RelayEvent.Succeeded })
    }

    @Test fun sinkExceptionsNeverBreakRequests() = runBlocking {
        val client = client { logging(LogLevel.BODY, LogSink { throw IllegalStateException("sink failed") }) }
        server.enqueue(MockResponse().setBody("request still succeeds"))
        assertEquals("request still succeeds", client.request("sink-failure", HttpMethod.GET).body)
    }

    @Test fun concurrentRequestsCanShareAThreadSafeSink() = runBlocking {
        val count = 16
        val ready = CountDownLatch(count)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("ok")
        }
        val client = client { logging(LogLevel.BASIC, LogSink(logs::add)) }
        coroutineScope {
            val tasks = (0 until count).map { index -> async(Dispatchers.IO) {
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                client.request("concurrent-$index", HttpMethod.GET)
            } }
            check(withContext(Dispatchers.IO) { ready.await(5, TimeUnit.SECONDS) })
            release.countDown()
            tasks.awaitAll()
        }
        assertEquals(count, logs.count { it.startsWith("REQUEST") })
        assertEquals(count, logs.count { it.startsWith("RESPONSE") })
    }

    @Test fun legacyRedactNamesContinueToProtectJsonAndHeaders() = runBlocking {
        val client = client {
            header("private_value", "shared-header-secret")
            logging(LogLevel.BODY, LogSink(logs::add), maxBodySize = 512)
            redact("private_value")
        }
        server.enqueue(MockResponse().setBody("""{"private_value":"response-private-secret"}"""))
        client.request("legacy-redact", HttpMethod.POST, body = mapOf("private_value" to "request-private-secret"))
        val output = logs.joinToString("\n")
        listOf("shared-header-secret", "response-private-secret", "request-private-secret").forEach { assertFalse(output.contains(it)) }
    }
}

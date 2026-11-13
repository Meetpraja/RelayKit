package com.relaykit.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.relaykit.network.config.RelayConfigBuilder
import com.relaykit.network.error.ErrorType
import com.relaykit.network.event.RelayEvent
import com.relaykit.network.internal.engine.RetryTiming
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayFile
import com.relaykit.network.result.RelayResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RelayRetryTest {
    @Serializable private data class Payload(val value: Int)

    private class FakeTiming(randomValues: List<Double> = emptyList()) : RetryTiming {
        val delays = mutableListOf<Duration>()
        private val random = randomValues.toMutableList()
        var waitHook: (suspend (Duration) -> Unit)? = null
        override suspend fun wait(duration: Duration) {
            delays += duration
            waitHook?.invoke(duration)
        }
        override fun randomUnit(): Double = if (random.isEmpty()) 0.5 else random.removeAt(0)
    }

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
    }
    @After fun tearDown() = server.shutdown()

    private fun retryClient(
        timing: RetryTiming = FakeTiming(),
        block: RelayConfigBuilder.() -> Unit = {},
    ): RelayClient {
        val builder = RelayConfigBuilder().apply {
            baseUrl = server.url("api/").newBuilder().host("127.0.0.1").build().toString()
            retry {
                enabled = true
                initialDelay = Duration.ZERO
                jitterFactor = 0.0
            }
            block()
        }
        return RelayClient(context, builder.build(), timing)
    }

    private fun defaultClient(block: RelayConfigBuilder.() -> Unit = {}): RelayClient = RelayClient.create(context) {
        baseUrl = server.url("api/").newBuilder().host("127.0.0.1").build().toString()
        block()
    }

    @Test fun retriesAreDisabledByDefaultAndOneRequestIsSent() = runBlocking {
        val client = defaultClient()
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary"))
        server.enqueue(MockResponse().setBody("later"))
        assertEquals(500, client.request("disabled", HttpMethod.GET).statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun retries500And503ThenReturnsSuccessForRawAndTypedRequests() = runBlocking {
        val client = retryClient()
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary"))
        server.enqueue(MockResponse().setBody("raw-ok"))
        assertEquals("raw-ok", client.request("raw", HttpMethod.GET).body)
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        server.enqueue(MockResponse().setBody("{\"value\":9}"))
        val typed = client.call<Payload>("typed", HttpMethod.GET)
        assertEquals(9, (typed as RelayResult.Success).data.value)
        assertEquals(4, server.requestCount)
    }

    @Test fun retries429AndHonorsRetryAfterCappedAtMaximumDelay() = runBlocking {
        val timing = FakeTiming()
        val client = retryClient(timing) {
            retry {
                enabled = true
                maxRetries = 1
                maxDelay = 2.seconds
                retryOnStatusCodes(429)
            }
        }
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "5").setBody("slow down"))
        server.enqueue(MockResponse().setBody("done"))
        assertEquals("done", client.request("rate-limit", HttpMethod.GET).body)
        assertEquals(listOf(2.seconds), timing.delays)
        assertEquals(2, server.requestCount)
    }

    @Test fun retryAfterHttpDateIsParsed() = runBlocking {
        val timing = FakeTiming()
        val client = retryClient(timing) {
            retry { enabled = true; maxRetries = 1; maxDelay = 3.seconds; retryOnStatusCodes(503) }
        }
        val retryAt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(java.util.Date(System.currentTimeMillis() + 15_000))
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", retryAt))
        server.enqueue(MockResponse().setBody("ok"))
        client.request("date-retry-after", HttpMethod.GET)
        assertEquals(3.seconds, timing.delays.single())
    }

    @Test fun networkDisconnectAndSocketTimeoutCanBeRetried() = runBlocking {
        val disconnectClient = retryClient()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("recovered"))
        assertEquals("recovered", disconnectClient.request("disconnect", HttpMethod.GET).body)
        assertEquals(2, server.requestCount)

        val timeoutClient = RelayClient.create(context) {
            baseUrl = server.url("api/").newBuilder().host("127.0.0.1").build().toString()
            timeouts { read = 50.milliseconds }
            retry { enabled = true; maxRetries = 1; initialDelay = Duration.ZERO; jitterFactor = 0.0 }
        }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("after-timeout"))
        assertEquals("after-timeout", timeoutClient.request("timeout", HttpMethod.GET).body)
        assertEquals(4, server.requestCount)
    }

    @Test fun retryCountMeansRetriesAfterInitialAndExhaustionPreservesHttpFailure() = runBlocking {
        val client = retryClient {
            retry { enabled = true; maxRetries = 2; initialDelay = Duration.ZERO; jitterFactor = 0.0 }
        }
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("still unavailable")) }
        val result = client.call<Payload>("exhaust", HttpMethod.GET)
        assertEquals(503, result.statusCode)
        assertEquals(ErrorType.HTTP, result.errorType)
        assertEquals(3, server.requestCount)
    }

    @Test fun exponentialBackoffAndMaximumDelayAreBounded() = runBlocking {
        val exponentialTiming = FakeTiming()
        val exponential = retryClient(exponentialTiming) {
            retry { enabled = true; maxRetries = 2; initialDelay = 10.milliseconds; maxDelay = 30.milliseconds; backoffMultiplier = 2.0; jitterFactor = 0.0 }
        }
        server.enqueue(MockResponse().setResponseCode(500)); server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setBody("ok"))
        exponential.request("backoff", HttpMethod.GET)
        assertEquals(listOf(10.milliseconds, 20.milliseconds), exponentialTiming.delays)

        val cappedTiming = FakeTiming()
        val capped = retryClient(cappedTiming) {
            retry { enabled = true; maxRetries = 3; initialDelay = 20.milliseconds; maxDelay = 25.milliseconds; backoffMultiplier = 3.0; jitterFactor = 0.0 }
        }
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }; server.enqueue(MockResponse().setBody("ok"))
        capped.request("capped", HttpMethod.GET)
        assertEquals(listOf(20.milliseconds, 25.milliseconds, 25.milliseconds), cappedTiming.delays)
    }

    @Test fun jitterStaysWithinConfiguredBounds() = runBlocking {
        val timing = FakeTiming(listOf(0.0, 1.0))
        val client = retryClient(timing) {
            retry { enabled = true; maxRetries = 2; initialDelay = 100.milliseconds; maxDelay = 200.milliseconds; backoffMultiplier = 1.0; jitterFactor = 0.25 }
        }
        server.enqueue(MockResponse().setResponseCode(500)); server.enqueue(MockResponse().setResponseCode(500)); server.enqueue(MockResponse().setBody("ok"))
        client.request("jitter", HttpMethod.GET)
        assertEquals(listOf(75.milliseconds, 125.milliseconds), timing.delays)
    }

    @Test fun onlyGetRetriesByDefaultAndUnsafeMethodsRequireOptIn() = runBlocking {
        val safe = retryClient()
        server.enqueue(MockResponse().setResponseCode(500)); server.enqueue(MockResponse().setBody("get-ok"))
        assertEquals("get-ok", safe.request("get", HttpMethod.GET).body)
        assertEquals(2, server.requestCount)
        repeat(2) { server.takeRequest() }

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(500, safe.request("post", HttpMethod.POST, body = mapOf("x" to 1)).statusCode)
        assertEquals(3, server.requestCount)
        server.takeRequest()

        val optedIn = retryClient {
            retry { enabled = true; maxRetries = 1; retryNonIdempotentRequests = true }
        }
        server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setBody("post-ok"))
        assertEquals("post-ok", optedIn.request("post-opt-in", HttpMethod.POST, body = mapOf("x" to 1), headers = mapOf("Idempotency-Key" to "operation-1")).body)
        val original = server.takeRequest(1, TimeUnit.SECONDS)!!
        val retry = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("operation-1", original.getHeader("Idempotency-Key"))
        assertEquals("operation-1", retry.getHeader("Idempotency-Key"))
    }

    @Test fun providerBackedFileRequestsAreNotRetriedEvenWithUnsafeOptIn() = runBlocking {
        val resolver = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(context.contentResolver).thenReturn(resolver)
        val uri = Mockito.mock(Uri::class.java)
        Mockito.`when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream("file-content".toByteArray()))
        val client = retryClient {
            retry { enabled = true; maxRetries = 2; retryNonIdempotentRequests = true }
        }
        server.enqueue(MockResponse().setResponseCode(500).setBody("no retry"))
        client.request("upload", HttpMethod.POST, multipart = mapOf("kind" to "file"), files = listOf(RelayFile("file", uri = uri)))
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDuringBackoffStopsImmediately() = runBlocking {
        val timing = FakeTiming()
        val enteredWait = CompletableDeferred<Unit>()
        val client = retryClient(timing) {
            retry { enabled = true; maxRetries = 3; initialDelay = 1.seconds }
        }
        timing.waitHook = { enteredWait.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        server.enqueue(MockResponse().setResponseCode(503))
        val task = async(Dispatchers.IO) { client.request("cancel-backoff", HttpMethod.GET) }
        withContext(Dispatchers.IO) { enteredWait.await() }
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDuringInflightRetryableRequestIsNotRetried() = runBlocking {
        val client = retryClient {
            retry { enabled = true; maxRetries = 3; initialDelay = Duration.ZERO }
        }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = async(Dispatchers.IO) { client.request("cancel-inflight", HttpMethod.GET) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun networkRetryCanBeDisabledAndExhaustionKeepsTimeoutClassification() = runBlocking {
        val disabledConfig = RelayConfigBuilder().apply {
            baseUrl = server.url("api/").newBuilder().host("127.0.0.1").build().toString()
            timeouts { read = 50.milliseconds }
            retry { enabled = true; maxRetries = 2; retryNetworkFailures = false; initialDelay = Duration.ZERO }
        }.build()
        val disabled = RelayClient(context, disabledConfig, FakeTiming())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val once = disabled.call<Payload>("network-disabled", HttpMethod.GET)
        assertEquals(ErrorType.TIMEOUT, once.errorType)
        assertEquals(1, server.requestCount)

        val exhaustedConfig = RelayConfigBuilder().apply {
            baseUrl = server.url("api/").newBuilder().host("127.0.0.1").build().toString()
            timeouts { read = 50.milliseconds }
            retry { enabled = true; maxRetries = 1; initialDelay = Duration.ZERO }
        }.build()
        val exhausted = RelayClient(context, exhaustedConfig, FakeTiming())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val failure = exhausted.call<Payload>("network-exhausted", HttpMethod.GET)
        assertEquals(ErrorType.TIMEOUT, failure.errorType)
        assertEquals(3, server.requestCount)
    }

    @Test fun parsingFailuresAndPermanentClientErrorsAreNotRetried() = runBlocking {
        val client = retryClient()
        server.enqueue(MockResponse().setBody("not-json"))
        val malformed = client.call<Payload>("parse", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, malformed.errorType)
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(404, client.request("not-found", HttpMethod.GET).statusCode)
        assertEquals(2, server.requestCount)
    }

    @Test fun bearerRefreshAndTransientRetryUseSeparateFiniteLimits() = runBlocking {
        val refreshes = AtomicInteger()
        val retryConfig = RelayConfigBuilder().apply {
            baseUrl = server.url("api/").newBuilder().host("127.0.0.1").build().toString()
            bearer()
            refreshBearerToken { refreshes.incrementAndGet(); "fresh-token" }
            timeouts { read = 50.milliseconds }
            retry { enabled = true; maxRetries = 1; initialDelay = Duration.ZERO; jitterFactor = 0.0 }
        }.build()
        val client = RelayClient(context, retryConfig, FakeTiming())
        client.setToken("expired-token")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("{\"value\":12}"))
        val response = client.call<Payload>("both", HttpMethod.GET)
        assertEquals(12, (response as RelayResult.Success).data.value)
        assertEquals(1, refreshes.get())
        assertEquals("Bearer expired-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
        assertEquals(3, server.requestCount)
    }

    @Test fun applicationInterceptorsRunPerHttpAttemptButLifecycleEventsRunOnce() = runBlocking {
        val interceptorCalls = AtomicInteger()
        val client = retryClient {
            interceptor(Interceptor { chain -> interceptorCalls.incrementAndGet(); chain.proceed(chain.request()) })
        }
        server.enqueue(MockResponse().setResponseCode(500)); server.enqueue(MockResponse().setBody("ok"))
        val result = client.request("interceptor", HttpMethod.GET)
        assertEquals("ok", result.body)
        assertEquals(2, interceptorCalls.get())
        assertEquals(1, client.events.replayCache.count { it is RelayEvent.Started })
        assertEquals(1, client.events.replayCache.count { it is RelayEvent.Succeeded })
    }

    @Test fun retryableStatusCodesCanBeCustomizedBut401RemainsReservedForAuth() = runBlocking {
        val client = retryClient {
            retry { enabled = true; maxRetries = 1; retryOnStatusCodes(418) }
        }
        server.enqueue(MockResponse().setResponseCode(418)); server.enqueue(MockResponse().setBody("teapot recovered"))
        assertEquals("teapot recovered", client.request("custom-status", HttpMethod.GET).body)
        assertEquals(2, server.requestCount)
        try {
            retryClient { retry { retryOnStatusCodes(401) } }
            fail("401 must remain reserved for authentication refresh")
        } catch (_: IllegalArgumentException) {
            assertEquals(2, server.requestCount)
        }
    }
}

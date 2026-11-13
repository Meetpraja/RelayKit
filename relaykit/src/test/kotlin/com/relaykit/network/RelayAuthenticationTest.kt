package com.relaykit.network

import android.content.Context
import com.relaykit.network.error.ErrorType
import com.relaykit.network.json.RelayJson
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.result.RelayResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RelayAuthenticationTest {
    @Serializable private data class Payload(val ok: Boolean)
    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
    }
    @After fun tearDown() = server.shutdown()
    private fun client(block: com.relaykit.network.config.RelayConfigBuilder.() -> Unit = {}) =
        RelayClient.create(context) { baseUrl = server.url("api/").toString(); block() }

    @Test fun dynamicBearerTokenAndRawTypedRequestsUseLatestValue() = runBlocking {
        val relay = client { bearer() }
        relay.setToken("token-one")
        server.enqueue(MockResponse().setBody("raw"))
        relay.request("raw", HttpMethod.GET)
        assertEquals("Bearer token-one", server.takeRequest().getHeader("Authorization"))
        relay.setToken("token-two")
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        assertTrue(relay.call<Payload>("typed", HttpMethod.GET) is RelayResult.Success)
        assertEquals("Bearer token-two", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun missingTokenAndExplicitAuthorizationAreRespected() = runBlocking {
        val refreshes = AtomicInteger()
        val relay = client { bearer(); refreshBearerToken { refreshes.incrementAndGet(); "should-not-refresh" } }
        server.enqueue(MockResponse().setBody("none"))
        relay.request("none", HttpMethod.GET)
        assertNull(server.takeRequest().getHeader("Authorization"))
        relay.setToken("managed-token")
        server.enqueue(MockResponse().setResponseCode(401).setBody("explicit"))
        relay.request("explicit", HttpMethod.GET, headers = mapOf("authorization" to "Basic caller-value"))
        assertEquals("Basic caller-value", server.takeRequest().getHeader("authorization"))
        assertEquals(0, refreshes.get())
    }

    @Test fun providerIsReadForEveryRequest() = runBlocking {
        val token = java.util.concurrent.atomic.AtomicReference("first")
        val relay = client { bearer { token.get() } }
        server.enqueue(MockResponse().setBody("1")); server.enqueue(MockResponse().setBody("2"))
        relay.request("one", HttpMethod.GET); server.takeRequest()
        token.set("second")
        relay.request("two", HttpMethod.GET)
        assertEquals("Bearer second", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun successfulUnauthorizedRefreshRetriesOnceAndUpdatesFollowingCalls() = runBlocking {
        val requests = AtomicInteger()
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/refresh") -> MockResponse().setBody("refresh")
                requests.incrementAndGet() == 1 -> MockResponse().setResponseCode(401).setBody("expired")
                else -> MockResponse().setBody("{\"ok\":true}")
            }
        }
        val relay = client {
            bearer()
            refreshBearerToken { expired -> assertEquals("old", expired); refreshes.incrementAndGet(); "fresh" }
        }
        relay.setToken("old")
        val raw = relay.request("raw", HttpMethod.GET)
        assertTrue(raw.isSuccessful)
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, refreshes.get())
        val typed = relay.call<Payload>("typed", HttpMethod.GET)
        assertTrue(typed is RelayResult.Success)
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun simultaneousUnauthorizedResponsesShareOneRefresh() = runBlocking {
        val count = 8
        val allUnauthorized = CountDownLatch(count)
        val requestCount = AtomicInteger()
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = requestCount.incrementAndGet()
                if (n <= count) { allUnauthorized.countDown(); return MockResponse().setResponseCode(401).setBody("expired") }
                return MockResponse().setBody("ok")
            }
        }
        val relay = client {
            bearer()
            refreshBearerToken {
                refreshCount.incrementAndGet()
                withContext(Dispatchers.IO) { check(allUnauthorized.await(5, TimeUnit.SECONDS)) }
                "new-token"
            }
        }
        relay.setToken("old-token")
        val results = coroutineScope { (0 until count).map { async(Dispatchers.IO) { relay.request("item-$it", HttpMethod.GET) } }.awaitAll() }
        assertEquals(count, results.size)
        assertTrue(results.all { it.isSuccessful })
        assertEquals(1, refreshCount.get())
        assertEquals(count * 2, requestCount.get())
    }

    @Test fun refreshFailureAndRepeatedUnauthorizedDoNotLoop() = runBlocking {
        val calls = AtomicInteger(); val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { calls.incrementAndGet(); return MockResponse().setResponseCode(401).setBody("unauthorized") }
        }
        val relay = client { bearer(); refreshBearerToken { refreshes.incrementAndGet(); null } }
        relay.setToken("old")
        val first = relay.request("failure", HttpMethod.GET)
        assertEquals(401, first.statusCode); assertEquals(1, calls.get()); assertEquals(1, refreshes.get())

        val again = client { bearer(); refreshBearerToken { "new" } }.also { it.setToken("old") }
        val repeated = again.request("again", HttpMethod.GET)
        assertEquals(401, repeated.statusCode)
        server.takeRequest()
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        assertEquals(3, server.requestCount)
    }

    @Test fun forbiddenDoesNotRefreshAndRefreshCannotReenterSameClient() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val refreshes = AtomicInteger()
        val relay = client { bearer(); refreshBearerToken { refreshes.incrementAndGet(); "new" } }
        relay.setToken("old")
        assertEquals(403, relay.request("forbidden", HttpMethod.GET).statusCode)
        assertEquals(0, refreshes.get())

        lateinit var sameClient: RelayClient
        sameClient = client { bearer(); refreshBearerToken { sameClient.request("refresh", HttpMethod.GET); "never" } }
        sameClient.setToken("old")
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        assertEquals(401, sameClient.request("protected", HttpMethod.GET).statusCode)
        assertEquals(2, server.requestCount) // only forbidden and protected calls reached the server
    }

    @Test fun refreshCancellationPropagates() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        val entered = CountDownLatch(1)
        val relay = client { bearer(); refreshBearerToken { entered.countDown(); suspendCancellableCoroutine<String?> { } } }
        relay.setToken("old")
        val task = async(Dispatchers.IO) { relay.request("cancel", HttpMethod.GET) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun tokenProviderCancellationPropagatesBeforeSendingRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val relay = client { bearer { entered.countDown(); suspendCancellableCoroutine<String?> { } } }
        val task = async(Dispatchers.IO) { relay.request("cancel-provider", HttpMethod.GET) }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(0, server.requestCount)
    }

    @Test fun logsAndTypedErrorTextDoNotContainCredentials() = runBlocking {
        val logs = mutableListOf<String>()
        val token = "secret-access-token"
        val relay = client {
            bearer(); logging(LogLevel.HEADERS, LogSink(logs::add))
            refreshBearerToken { throw IllegalStateException(token) }
        }
        relay.setToken(token)
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val failure = relay.call<RelayJson>("secure", HttpMethod.GET)
        assertEquals(ErrorType.HTTP, failure.errorType)
        assertFalse(failure.errorOrNull.toString().contains(token))
        assertFalse(logs.joinToString().contains(token))
        assertFalse(relay.events.replayCache.toString().contains(token))
    }
}

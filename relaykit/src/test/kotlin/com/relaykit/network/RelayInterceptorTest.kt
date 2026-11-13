package com.relaykit.network

import android.content.Context
import com.relaykit.network.error.ErrorType
import com.relaykit.network.json.RelayJson
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.result.RelayResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RelayInterceptorTest {
    @Serializable private data class Value(val value: Int)

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

    private fun Map<String, String>.header(name: String): String? = entries.firstOrNull { it.key.equals(name, true) }?.value

    @Test fun interceptorModifiesRequestsAndInspectsResponsesForRawAndTypedCalls() = runBlocking {
        val invoked = AtomicInteger()
        val interceptor = Interceptor { chain ->
            invoked.incrementAndGet()
            val request = chain.request().newBuilder()
                .header("X-Intercepted", "yes")
                .url(chain.request().url.newBuilder().addQueryParameter("from", "interceptor").build())
                .build()
            val response = chain.proceed(request)
            response.newBuilder().header("X-Observed-Status", response.code.toString()).build()
        }
        val relay = client {
            header("X-Default", "stable")
            interceptor(interceptor)
        }

        server.enqueue(MockResponse().setResponseCode(202).setBody("raw-body"))
        val raw = relay.request("raw", HttpMethod.GET)
        assertEquals(202, raw.statusCode)
        assertEquals("raw-body", raw.body)
        assertEquals("202", raw.headers.header("X-Observed-Status"))
        val rawRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("yes", rawRequest.getHeader("X-Intercepted"))
        assertEquals("stable", rawRequest.getHeader("X-Default"))
        assertEquals("interceptor", rawRequest.requestUrl!!.queryParameter("from"))

        server.enqueue(MockResponse().setBody("{\"value\":9}"))
        val typed = relay.call<Value>("typed", HttpMethod.GET)
        assertTrue(typed is RelayResult.Success)
        assertEquals(9, (typed as RelayResult.Success).data.value)
        val typedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("yes", typedRequest.getHeader("X-Intercepted"))
        assertEquals("stable", typedRequest.getHeader("X-Default"))
        assertEquals(2, invoked.get())
    }

    @Test fun multipleInterceptorsRunInRegistrationOrderAndUnwindInReverse() = runBlocking {
        val order = mutableListOf<String>()
        fun recording(name: String) = Interceptor { chain ->
            order += "$name-request"
            val response = chain.proceed(chain.request())
            order += "$name-response"
            response
        }
        val relay = client {
            interceptor(recording("first"))
            interceptor(recording("second"))
        }
        server.enqueue(MockResponse().setBody("ok"))
        relay.request("order", HttpMethod.GET)
        assertEquals(listOf("first-request", "second-request", "second-response", "first-response"), order)
    }

    @Test fun interceptorIoExceptionsUseRawAndTypedErrorContracts() = runBlocking {
        val interceptor = Interceptor { throw IOException("interceptor network failure") }
        val relay = client { interceptor(interceptor) }

        val typed = relay.call<RelayJson>("io-failure", HttpMethod.GET)
        assertEquals(ErrorType.NETWORK, typed.errorType)
        assertEquals("interceptor network failure", typed.errorOrNull?.cause?.message)

        try {
            relay.request("raw-io-failure", HttpMethod.GET)
            throw AssertionError("Raw request should propagate interceptor IOException")
        } catch (error: IOException) {
            assertEquals("interceptor network failure", error.message)
        }
    }

    @Test fun runtimeInterceptorExceptionsBecomeUnknownTypedFailures() = runBlocking {
        val relay = client { interceptor(Interceptor { throw IllegalStateException("bad interceptor") }) }
        val result = relay.call<RelayJson>("runtime-failure", HttpMethod.GET)
        assertEquals(ErrorType.UNKNOWN, result.errorType)
        assertEquals("bad interceptor", result.errorOrNull?.cause?.message)
    }

    @Test fun cancellationExceptionThrownByInterceptorIsNotConvertedToFailure() = runBlocking {
        val relay = client { interceptor(Interceptor { throw CancellationException("interceptor cancelled") }) }
        try {
            relay.call<RelayJson>("cancelled-interceptor", HttpMethod.GET)
            throw AssertionError("Expected interceptor cancellation to propagate")
        } catch (error: CancellationException) {
            assertEquals("interceptor cancelled", error.message)
        }
    }

    @Test fun retrofitCancellationPropagatesThroughAnInterceptorProceedingOnTheCall() = runBlocking {
        val entered = CountDownLatch(1)
        val interceptor = Interceptor { chain ->
            entered.countDown()
            chain.proceed(chain.request())
        }
        val relay = client { interceptor(interceptor) }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = async(Dispatchers.IO) { relay.call<RelayJson>("cancel", HttpMethod.GET) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        task.cancel()
        try {
            task.await()
            throw AssertionError("Expected coroutine cancellation")
        } catch (_: CancellationException) {
            // The interceptor's chain proceeds through the Retrofit call, which cancellation aborts.
        }
    }

    @Test fun sharedInterceptorHandlesConcurrentRequestsWithoutCrossRequestState() = runBlocking {
        val count = 24
        val invocations = AtomicInteger()
        val interceptor = Interceptor { chain ->
            invocations.incrementAndGet()
            val original = chain.request()
            val request = original.newBuilder().header("X-Path", original.url.encodedPath.substringAfterLast('/')).build()
            chain.proceed(request)
        }
        val relay = client { interceptor(interceptor) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(request.getHeader("X-Path").orEmpty())
        }

        val ready = CountDownLatch(count)
        val release = CountDownLatch(1)
        val tasks = (0 until count).map { index -> async(Dispatchers.IO) {
            ready.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            relay.request("parallel-$index", HttpMethod.GET)
        } }
        check(ready.await(5, TimeUnit.SECONDS))
        release.countDown()
        val responses = tasks.awaitAll()
        responses.forEachIndexed { index, response -> assertEquals("parallel-$index", response.body) }
        assertEquals(count, invocations.get())
    }

    @Test fun interceptorRegistrationIsSnapshottedAndAppliedOncePerRequest() = runBlocking {
        val calls = AtomicInteger()
        val interceptor = Interceptor { chain ->
            calls.incrementAndGet()
            chain.proceed(chain.request())
        }
        val relay = client { interceptor(interceptor) }
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        assertEquals("one", relay.request("one", HttpMethod.GET).body)
        assertEquals("two", relay.request("two", HttpMethod.GET).body)
        assertEquals(2, calls.get())
        assertTrue(relay.config.interceptors.size == 1)
        try {
            (relay.config.interceptors as MutableList).clear()
            throw AssertionError("Published interceptor configuration must be immutable")
        } catch (_: UnsupportedOperationException) {
            // The config list is a read-only snapshot.
        }
    }
}

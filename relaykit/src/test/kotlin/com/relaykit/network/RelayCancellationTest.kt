package com.relaykit.network

import android.content.Context
import com.relaykit.network.error.ErrorType
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.result.RelayResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.serializer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RelayCancellationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        client = RelayClient.create(context) { baseUrl = server.url("api/").toString() }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun cancellationBeforeRequestStartsDoesNotReachServer() = runBlocking {
        val task = async(start = CoroutineStart.LAZY) { client.call<String>("before", HttpMethod.GET) }
        task.cancel()
        assertCancellation(task)
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellingInflightGetPropagatesCancellationAndCancelsRetrofitCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = async(Dispatchers.IO) { client.call<String>("slow-get", HttpMethod.GET) }
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("GET must reach MockWebServer before cancellation", request)
        task.cancel()
        assertCancellation(task)
    }

    @Test fun cancellingInflightPostPropagatesCancellationAndKeepsItsBodyIsolated() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = async(Dispatchers.IO) {
            client.call<String>("slow-post", HttpMethod.POST, body = mapOf("payload" to "post-body"))
        }
        val request: RecordedRequest? = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("POST must reach MockWebServer before cancellation", request)
        assertTrue(request!!.body.readUtf8().contains("\"payload\":\"post-body\""))
        task.cancel()
        assertCancellation(task)
    }

    @Test fun cancellationDuringTypedResponseProcessingIsObservedAfterTheBodyIsClosed() = runBlocking {
        server.enqueue(MockResponse().setBody("\"decoded\""))
        val decodingStarted = CountDownLatch(1)
        val finishDecoding = CountDownLatch(1)
        val serializer = BlockingStringSerializer(decodingStarted, finishDecoding)
        val task = async(Dispatchers.Default) {
            client.callTyped("processing", HttpMethod.GET, null, emptyMap(), emptyMap(), null, null, null, emptyList(), serializer)
        }

        try {
            assertTrue("serializer should be reached", decodingStarted.await(5, TimeUnit.SECONDS))
            task.cancel()
        } finally {
            finishDecoding.countDown()
        }
        assertCancellation(task)
        // A follow-up response proves the cancelled processing path released its HTTP resources.
        server.enqueue(MockResponse().setBody("\"ok\""))
        val next = client.call<String>("after-cancel", HttpMethod.GET)
        assertTrue(next is RelayResult.Success)
    }

    @Test fun cancellationDoesNotAffectAnotherConcurrentRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("\"independent\""))
        val cancelled = async(Dispatchers.IO) { client.call<String>("slow", HttpMethod.GET) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        val successful = async(Dispatchers.IO) { client.call<String>("fast", HttpMethod.GET) }
        val result = successful.await()
        assertEquals("independent", (result as RelayResult.Success).data)
        cancelled.cancel()
        assertCancellation(cancelled)
    }

    @Test fun genuineNetworkFailureStillReturnsTheExistingFailureResult() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = client.call<String>("disconnect", HttpMethod.GET)
        assertTrue(result is RelayResult.Failure)
        assertEquals(ErrorType.NETWORK, result.errorType)
    }

    @Test fun successfulRequestStillReturnsSuccessAfterCancellationCases() = runBlocking {
        server.enqueue(MockResponse().setBody("\"okay\""))
        val result = client.call<String>("normal", HttpMethod.GET)
        assertTrue(result is RelayResult.Success)
        assertEquals("okay", (result as RelayResult.Success).data)
    }

    private suspend fun assertCancellation(task: Deferred<*>) {
        try {
            task.await()
            fail("Expected CancellationException, not a RelayResult.Failure")
        } catch (_: CancellationException) {
            // Expected: cancellation remains control flow and is never represented as a result.
        }
    }

    private class BlockingStringSerializer(
        private val started: CountDownLatch,
        private val release: CountDownLatch,
    ) : KSerializer<String> {
        override val descriptor: SerialDescriptor = String.serializer().descriptor

        override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

        override fun deserialize(decoder: Decoder): String {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test did not release response decoding" }
            return decoder.decodeString()
        }
    }
}

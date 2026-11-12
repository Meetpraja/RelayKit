package com.relaykit.network

import android.content.Context
import com.relaykit.network.event.RelayEvent
import com.relaykit.network.event.RelayEventListener
import com.relaykit.network.request.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

class RelayEventIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        client = RelayClient.create(context) { baseUrl = server.url("/").toString(); events(8) }
    }
    @After fun shutdown() { server.shutdown() }

    @Test fun emitsOrderedEventsWithRequestMetadata() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        client.request("tagged", HttpMethod.GET) { tag("profile"); extra("screen", "account") }
        val emitted = withTimeout(2_000) { client.events.take(2).toList() }
        val started = emitted[0] as RelayEvent.Started
        val succeeded = emitted[1] as RelayEvent.Succeeded
        assertEquals("profile", started.tag)
        assertEquals("account", started.extras["screen"])
        assertEquals(started.requestId, succeeded.requestId)
    }

    @Test fun listenerExceptionsDoNotBreakRequests() = runBlocking {
        val throwing = RelayClient.create(Mockito.mock(Context::class.java).also { Mockito.`when`(it.applicationContext).thenReturn(it) }) {
            baseUrl = server.url("/").toString()
            events(listener = RelayEventListener { error("listener failed") })
        }
        server.enqueue(MockResponse().setBody("still works"))
        assertEquals("still works", throwing.request("test", HttpMethod.GET).body)
    }

}

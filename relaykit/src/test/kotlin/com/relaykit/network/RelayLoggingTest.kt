package com.relaykit.network

import android.content.Context
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import com.relaykit.network.request.HttpMethod
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class RelayLoggingTest {
    private lateinit var server: MockWebServer
    private val logs = mutableListOf<String>()

    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun shutdown() { server.shutdown() }

    @Test fun redactsHeadersJsonAndCustomFieldsAndTruncates() = runBlocking {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        val client = RelayClient.create(context) {
            baseUrl = server.url("/").toString()
            header("Authorization", "Bearer do-not-log")
            logging(LogLevel.BODY, LogSink(logs::add), maxBodySize = 24)
            redact("private_value")
        }
        server.enqueue(MockResponse().setBody("""{"password":"secret","private_value":"hidden","visible":"abcdefghijklmnopqrstuvwxyz"}"""))
        client.request("?token=secret", HttpMethod.POST, body = mapOf("password" to "secret", "private_value" to "hidden"))
        val output = logs.joinToString("\n")
        assertFalse(output.contains("do-not-log"))
        assertFalse(output.contains("secret"))
        assertFalse(output.contains("hidden"))
        assertTrue(output.contains("******"))
        assertTrue(output.contains("truncated"))
    }
}

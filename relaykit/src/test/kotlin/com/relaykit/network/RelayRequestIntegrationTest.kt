package com.relaykit.network

import android.content.Context
import com.relaykit.network.config.Auth
import com.relaykit.network.request.HttpMethod
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import kotlinx.coroutines.runBlocking
import java.io.File

class RelayRequestIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            header("X-Default", "default")
            query("platform", "android")
        }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun getMergesDefaultAndRequestHeadersAndQuery() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val result = client.request("users", HttpMethod.GET, query = mapOf("page" to 2), headers = mapOf("X-Request" to "yes"))
        val request = server.takeRequest()
        assertTrue(result.isSuccessful)
        assertEquals("/api/users?platform=android&page=2", request.path)
        assertEquals("default", request.getHeader("X-Default"))
        assertEquals("yes", request.getHeader("X-Request"))
        assertEquals("ok", result.body)
    }

    @Test fun postEncodesJsonAndSupportsHeaderOverride() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("created"))
        client.request("items", HttpMethod.POST, body = mapOf("name" to "demo"), headers = mapOf("X-Default" to "override"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("override", request.getHeader("X-Default"))
        assertTrue(request.body.readUtf8().contains("\"name\":\"demo\""))
    }

    @Test fun rejectsGetBodyBeforeNetworkExecution() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.request("users", HttpMethod.GET, body = mapOf("bad" to true)) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun namedApiKeyAndRequestDslWork() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        client.request("items", HttpMethod.POST) {
            body(mapOf("count" to 1))
            header("X-Request", "dsl")
            auth(Auth.ApiKey("key", "X-Key"))
        }
        val request = server.takeRequest()
        assertEquals("key", request.getHeader("X-Key"))
        assertEquals("dsl", request.getHeader("X-Request"))
    }

    @Test fun postsUrlEncodedForm() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        client.request("form", HttpMethod.POST, form = mapOf("user" to "Ada Lovelace"))
        val request = server.takeRequest()
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("user=Ada%20Lovelace", request.body.readUtf8())
    }

    @Test fun postsMultipartTextAndFile() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        val file = File.createTempFile("relaykit", ".txt").apply { writeText("sample-file") }
        try {
            client.request("upload", HttpMethod.POST, multipart = mapOf("caption" to "hello"), files = listOf(com.relaykit.network.request.RelayFile("attachment", file = file, mimeType = "text/plain")))
            val request = server.takeRequest()
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("caption"))
            assertTrue(body.contains("hello"))
            assertTrue(body.contains("sample-file"))
        } finally { file.delete() }
    }

    @Test fun rejectsMultipartForGetAndRejectsConflictingBodies() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.request("upload", HttpMethod.GET, multipart = mapOf("x" to "y")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.request("upload", HttpMethod.POST, body = mapOf("x" to 1), form = mapOf("x" to "y")) }
        }
        assertEquals(0, server.requestCount)
    }
}

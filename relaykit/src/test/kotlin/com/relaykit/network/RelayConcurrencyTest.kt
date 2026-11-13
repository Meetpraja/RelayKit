package com.relaykit.network

import android.content.Context
import com.relaykit.network.request.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RelayConcurrencyTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath.substringAfterLast('/')
                val body = request.body.readUtf8()
                return if (path == "fail") MockResponse().setResponseCode(500).setBody("failure")
                else MockResponse().setBody("${request.method}:$path:${request.requestUrl!!.query.orEmpty()}:${request.getHeader("X-Request").orEmpty()}:${body.ifEmpty { "-" }}")
            }
        }
        server.start()
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            header("X-Global", "stable")
            query("global", "yes")
        }
    }

    @After fun tearDown() { server.shutdown() }

    /** Wait for every request coroutine to be ready, then release all of them together. */
    private suspend fun <T> launchTogether(count: Int, block: suspend (Int) -> T): List<T> = coroutineScope {
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val tasks = (0 until count).map { index -> async(Dispatchers.IO) {
            ready.countDown()
            check(start.await(10, TimeUnit.SECONDS)) { "Concurrent request launch was not released" }
            block(index)
        } }
        try {
            check(ready.await(10, TimeUnit.SECONDS)) { "Concurrent request coroutines did not become ready" }
        } finally {
            start.countDown()
        }
        tasks.awaitAll()
    }

    @Test fun fiftyConcurrentGetsKeepTheirResponsesAndRequestParametersIsolated() = runBlocking {
        val responses = launchTogether(50) { index ->
            client.request("get-$index", HttpMethod.GET, query = mapOf("id" to index), headers = mapOf("X-Request" to "h$index"))
        }
        assertEquals(50, responses.size)
        responses.forEachIndexed { index, response ->
            assertTrue(response.isSuccessful)
            assertTrue(response.body.contains("GET:get-$index:global=yes&id=$index:h$index:-"))
        }
    }

    @Test fun concurrentPostsUseIndependentBodiesAndResponses() = runBlocking {
        val responses = launchTogether(24) { index ->
            client.request("post-$index", HttpMethod.POST, body = mapOf("id" to index))
        }
        responses.forEachIndexed { index, response ->
            assertTrue(response.body.contains("POST:post-$index:global=yes::"))
            assertTrue(response.body.endsWith("\"id\":$index}"))
        }
    }

    @Test fun snapshotsMutableRequestCollectionsBeforeExecution() = runBlocking {
        val nested = mutableListOf<Any?>("before")
        val body = mutableMapOf<String, Any?>("items" to nested)
        val query = mutableMapOf<String, Any?>("q" to mutableListOf("before"))
        val headers = mutableMapOf("X-Request" to "before")
        val builder = com.relaykit.network.request.RequestBuilder("snapshot", HttpMethod.POST).apply {
            body(body)
            query("q", query["q"])
            header("X-Request", headers.getValue("X-Request"))
        }
        val snapshot = builder.build()
        nested[0] = "after"
        body["extra"] = true
        (query["q"] as MutableList<Any?>)[0] = "after"
        headers["X-Request"] = "after"
        val response = client.executionEngine.execute(snapshot)
        assertTrue(response.body.contains("q=before"))
        assertTrue(response.body.contains(":before:{\"items\":[\"before\"]}"))
    }

    @Test fun headerAndQueryValuesStayAssociatedWithTheirRequests() = runBlocking {
        val responses = launchTogether(20) { index ->
            client.request("isolated-$index", HttpMethod.GET,
                query = mapOf("only" to "q$index"), headers = mapOf("X-Request" to "h$index"))
        }
        responses.forEachIndexed { index, response ->
            assertTrue(response.body.contains("global=yes&only=q$index:h$index:-"))
        }
    }

    @Test fun mutableMultipartFieldsAndFileListAreSnapshottedByDsl() = runBlocking {
        val fields = mutableMapOf("field" to "before")
        val file = java.io.File.createTempFile("relaykit-isolation", ".txt").apply { writeText("file-before") }
        try {
            val requestBuilder = com.relaykit.network.request.RequestBuilder("multipart", HttpMethod.POST).apply {
                multipart(fields)
                file(com.relaykit.network.request.RelayFile("upload", file = file, mimeType = "text/plain"))
            }
            val snapshot = requestBuilder.build()
            fields["field"] = "after"
            requestBuilder.file(com.relaykit.network.request.RelayFile("later", file = file, mimeType = "text/plain"))
            val response = client.executionEngine.execute(snapshot)
            assertTrue(response.body.contains("name=\"field\""))
            assertTrue(response.body.contains("before"))
            assertFalse(response.body.contains("after"))
            assertTrue(response.body.contains("file-before"))
            assertFalse(response.body.contains("name=\"later\""))
        } finally {
            file.delete()
        }
    }

    @Test fun failuresAndHttpErrorsDoNotAffectConcurrentSuccessesAndBodiesAreConsumed() = runBlocking {
        val results = listOf("fail", "ok-a", "ok-b").map { path -> async(Dispatchers.IO) {
            client.request(path, HttpMethod.GET)
        } }.awaitAll()
        assertFalse(results[0].isSuccessful)
        assertEquals("failure", results[0].body)
        assertTrue(results[1].isSuccessful)
        assertTrue(results[2].isSuccessful)
        assertTrue(client.request("after", HttpMethod.GET).isSuccessful)
    }

    @Test fun concurrentDefaultClientAccessReturnsFullyInitializedPublishedClient() = runBlocking {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        val initialized = RelayKit.init(context) { baseUrl = server.url("default/").toString() }
        val clients = (0 until 100).map { async(Dispatchers.IO) { RelayKit.client() } }.awaitAll()
        assertTrue(clients.all { it === initialized && it.config.baseUrl == initialized.config.baseUrl })
    }

    @Test fun concurrentInitializationPublishesOnlyCompleteClientsAndLastSequentialInitWins() = runBlocking {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        val ready = CountDownLatch(12)
        val start = CountDownLatch(1)
        val initialized = (0 until 12).map { index -> async(Dispatchers.IO) {
            ready.countDown()
            check(start.await(5, TimeUnit.SECONDS))
            RelayKit.init(context) { baseUrl = server.url("init-$index/").toString() }
        } }
        check(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        val results = initialized.awaitAll()
        assertTrue(results.all { it.config.baseUrl.endsWith('/') })
        assertTrue(results.any { it === RelayKit.client() })
        val replacement = RelayKit.init(context) { baseUrl = server.url("final/").toString() }
        assertTrue(RelayKit.client() === replacement)
    }

    @Test fun repeatedConcurrentStressRequestsRemainIsolated() = runBlocking {
        repeat(5) { round ->
            val responses = launchTogether(40) { index ->
                client.request("stress-$round-$index", HttpMethod.POST,
                    body = mapOf("round" to round, "id" to index),
                    query = mapOf("round" to round, "id" to index),
                    headers = mapOf("X-Request" to "$round-$index"))
            }
            responses.forEachIndexed { index, response ->
                assertTrue(response.body.contains("stress-$round-$index"))
                assertTrue(response.body.contains("id=$index"))
                assertTrue(response.body.endsWith("\"round\":$round,\"id\":$index}"))
            }
        }
    }
}

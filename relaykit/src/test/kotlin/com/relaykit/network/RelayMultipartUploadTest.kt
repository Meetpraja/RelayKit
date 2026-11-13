package com.relaykit.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RelayMultipartUploadTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var client: RelayClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        client = RelayClient.create(context) { baseUrl = server.url("api/").toString() }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun multipartStreamsFilesAndIncludesMultiplePartsAndCustomMetadata() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        val first = File.createTempFile("relay-upload-a", ".txt").apply { writeText("first-content") }
        val second = File.createTempFile("relay-upload-b", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            client.request("upload", HttpMethod.POST) {
                multipart(mapOf("caption" to "private text", "category" to "sample"))
                file(RelayFile("primary", first, fileName = "renamed.txt", mimeType = "text/plain"))
                file(RelayFile("backup", second, fileName = "backup.bin", mimeType = "application/x-test"))
            }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            val body = request.body.readUtf8()
            val contentType = request.getHeader("Content-Type")!!
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            val boundary = contentType.substringAfter("boundary=")
            assertTrue(body.contains("--$boundary"))
            assertTrue(body.contains("name=\"caption\""))
            assertTrue(body.contains("private text"))
            assertTrue(body.contains("name=\"primary\"; filename=\"renamed.txt\""))
            assertTrue(body.contains("Content-Type: text/plain"))
            assertTrue(body.contains("first-content"))
            assertTrue(body.contains("name=\"backup\"; filename=\"backup.bin\""))
            assertTrue(body.contains("Content-Type: application/x-test"))
            assertTrue(body.contains("\u0001\u0002\u0003\u0004"))
        } finally { first.delete(); second.delete() }
    }

    @Test fun progressReportsAggregateByteCountsAndDoesNotBreakWhenCallbackThrows() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        val done = CountDownLatch(1)
        val finalProgress = AtomicReference<Pair<Long, Long?>>()
        val second = File.createTempFile("relay-progress", ".dat").apply { writeBytes(ByteArray(160 * 1024) { 7 }) }
        try {
            server.enqueue(MockResponse().setBody("ok"))
            val response = client.request(
                "upload", HttpMethod.POST, multipart = mapOf("note" to "hello"), files = listOf(RelayFile("file", second)),
                onUploadProgress = { sent, total ->
                    finalProgress.set(sent to total)
                    if (total != null && sent >= total) done.countDown()
                    throw IllegalStateException("observer failure")
                },
            )
            assertTrue(response.isSuccessful)
            assertTrue("final progress callback was not delivered", done.await(5, TimeUnit.SECONDS))
            val uploaded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(uploaded.body.size, finalProgress.get().first)
            assertEquals(uploaded.body.size, finalProgress.get().second)
        } finally { second.delete() }
    }

    @Test fun progressIsAvailableForTypedCallsAndMultipartLoggingOmitsValuesAndBytes() = runBlocking {
        val messages = mutableListOf<String>()
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            logging(LogLevel.BODY, LogSink { synchronized(messages) { messages += it } })
        }
        server.enqueue(MockResponse().setBody("\"done\""))
        val file = File.createTempFile("relay-log", ".txt").apply { writeText("file-secret-content") }
        try {
            val result = client.call<String>("upload", HttpMethod.POST, multipart = mapOf("password" to "text-secret"), files = listOf(RelayFile("document", file, fileName = "report.txt", mimeType = "text/plain")), onUploadProgress = { _, _ -> })
            assertTrue(result is com.relaykit.network.result.RelayResult.Success)
            val bodyLog = synchronized(messages) { messages.joinToString("\n") }
            assertTrue(bodyLog.contains("multipart"))
            assertTrue(bodyLog.contains("report.txt"))
            assertFalse(bodyLog.contains("text-secret"))
            assertFalse(bodyLog.contains("file-secret-content"))
        } finally { file.delete() }
    }

    @Test fun contentUriStreamsAndIsNotRetried() = runBlocking {
        val resolver = Mockito.mock(ContentResolver::class.java)
        val uri = Mockito.mock(Uri::class.java)
        var streamClosed = false
        val source = object : ByteArrayInputStream("uri-content".toByteArray()) {
            override fun close() { streamClosed = true; super.close() }
        }
        Mockito.`when`(context.contentResolver).thenReturn(resolver)
        Mockito.`when`(resolver.openInputStream(uri)).thenReturn(source)
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            retry {
                enabled = true
                maxRetries = 1
                retryNonIdempotentRequests = true
                retryOnStatusCodes(503)
            }
        }
        server.enqueue(MockResponse().setResponseCode(503).setBody("try again"))
        server.enqueue(MockResponse().setBody("ok"))
        val unknownTotalProgress = CountDownLatch(1)
        client.request("upload", HttpMethod.POST, files = listOf(RelayFile("file", uri = uri)), onUploadProgress = { sent, total ->
            if (sent > 0 && total == null) unknownTotalProgress.countDown()
        })
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8().contains("uri-content"))
        assertTrue("content URI length should be unknown", unknownTotalProgress.await(5, TimeUnit.SECONDS))
        assertTrue("provider stream should close after request", streamClosed)
    }

    @Test fun retryableFileUploadCreatesANewRequestAttempt() = runBlocking {
        val file = File.createTempFile("relay-retry", ".txt").apply { writeText("replayable-file") }
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            retry {
                enabled = true
                maxRetries = 1
                initialDelay = kotlin.time.Duration.ZERO
                jitterFactor = 0.0
                retryNonIdempotentRequests = true
                retryOnStatusCodes(503)
            }
        }
        server.enqueue(MockResponse().setResponseCode(503).setBody("retry"))
        server.enqueue(MockResponse().setBody("ok"))
        try {
            assertTrue(client.request("upload", HttpMethod.POST, files = listOf(RelayFile("file", file))).isSuccessful)
            assertEquals(2, server.requestCount)
            repeat(2) { assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8().contains("replayable-file")) }
        } finally { file.delete() }
    }

    @Test fun concurrentUploadsKeepBodiesAndProgressIndependent() = runBlocking {
        server.enqueue(MockResponse().setBody("ok-a"))
        server.enqueue(MockResponse().setBody("ok-b"))
        val progress = ConcurrentLinkedQueue<Pair<String, Long>>()
        val first = File.createTempFile("relay-concurrent-a", ".txt").apply { writeText("A".repeat(90_000)) }
        val second = File.createTempFile("relay-concurrent-b", ".txt").apply { writeText("B".repeat(110_000)) }
        try {
            val requests = listOf(
                async(Dispatchers.IO) { client.request("upload/a", HttpMethod.POST, files = listOf(RelayFile("file", first)), onUploadProgress = { sent, _ -> progress += "a" to sent }) },
                async(Dispatchers.IO) { client.request("upload/b", HttpMethod.POST, files = listOf(RelayFile("file", second)), onUploadProgress = { sent, _ -> progress += "b" to sent }) },
            )
            assertTrue(requests.all { it.await().isSuccessful })
            val bodies = listOf(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8(), server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            assertTrue(bodies.any { it.contains("A".repeat(90_000)) })
            assertTrue(bodies.any { it.contains("B".repeat(110_000)) })
            assertTrue(progress.any { it.first == "a" && it.second > 0 })
            assertTrue(progress.any { it.first == "b" && it.second > 0 })
        } finally { first.delete(); second.delete() }
    }

    @Test fun cancellationClosesActiveContentUriStream() = runBlocking {
        val resolver = Mockito.mock(ContentResolver::class.java)
        val uri = Mockito.mock(Uri::class.java)
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val blockingStream = object : InputStream() {
            private var sent = false
            override fun read(): Int {
                if (sent) return -1
                sent = true
                reading.countDown()
                releaseRead.await(5, TimeUnit.SECONDS)
                return 'x'.code
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val value = read()
                if (value < 0) return -1
                buffer[offset] = value.toByte()
                return 1
            }
            override fun close() { closed.countDown() }
        }
        Mockito.`when`(context.contentResolver).thenReturn(resolver)
        Mockito.`when`(resolver.openInputStream(uri)).thenReturn(blockingStream)
        server.enqueue(MockResponse().setBody("ok"))
        val pending = async(Dispatchers.IO) {
            client.request("upload", HttpMethod.POST, files = listOf(RelayFile("file", uri = uri)))
        }
        try {
            assertTrue("upload source should start reading", reading.await(5, TimeUnit.SECONDS))
            pending.cancel()
        } finally {
            releaseRead.countDown()
        }
        try {
            pending.await()
            throw AssertionError("cancelled multipart request should not complete")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue("active content URI stream should close", closed.await(5, TimeUnit.SECONDS))
    }
}

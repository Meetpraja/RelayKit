package com.relaykit.network

import android.content.Context
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import com.relaykit.network.request.DownloadProgress
import com.relaykit.network.request.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RelayDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var client: RelayClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        directory = java.nio.file.Files.createTempDirectory("relay-download-").toFile()
        client = RelayClient.create(context) { baseUrl = server.url("api/").toString() }
    }

    @After fun tearDown() {
        directory.deleteRecursively()
        server.shutdown()
    }

    @Test fun downloadsFileAndReportsAggregateProgress() = runBlocking {
        val content = "downloaded-content-123"
        server.enqueue(MockResponse().setBody(content))
        val destination = File(directory, "small.bin")
        val finished = CountDownLatch(1)
        val last = AtomicReference<DownloadProgress>()
        val result = client.download("files/one?ignored=true", destination, query = mapOf("token" to "private")) { progress ->
            last.set(progress)
            if (progress.downloadedBytes == content.length.toLong()) finished.countDown()
        }
        assertTrue(result.isSuccessful)
        assertEquals(200, result.statusCode)
        assertEquals(content, destination.readText())
        assertEquals(destination, result.destination)
        assertEquals(content.length.toLong(), result.downloadedBytes)
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(100, last.get().percent)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(request.path!!.contains("token=private"))
    }

    @Test fun largeAndChunkedResponsesStreamWithKnownAndUnknownTotals() = runBlocking {
        val large = ByteArray(8 * 1024 * 1024) { (it % 239).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(large)))
        val largeFile = File(directory, "large.bin")
        val known = client.download("large", largeFile)
        assertTrue(known.isSuccessful)
        assertEquals(large.size.toLong(), largeFile.length())
        assertTrue(largeFile.readBytes().contentEquals(large))

        server.enqueue(MockResponse().setChunkedBody("unknown-length-payload", 5))
        val unknownFile = File(directory, "unknown.bin")
        val unknownProgress = AtomicReference<DownloadProgress>()
        val seen = CountDownLatch(1)
        val unknown = client.download("chunked", unknownFile, onProgress = {
            unknownProgress.set(it)
            if (it.downloadedBytes > 0L && it.totalBytes == null) seen.countDown()
            throw IllegalStateException("progress observer failure")
        })
        assertTrue(unknown.isSuccessful)
        assertEquals("unknown-length-payload", unknownFile.readText())
        assertTrue(seen.await(5, TimeUnit.SECONDS))
        assertNull(unknownProgress.get().totalBytes)
        assertNull(unknownProgress.get().percent)
    }

    @Test fun httpErrorsReturnStructuredFailureAndDoNotCreateDestination() = runBlocking {
        listOf(404, 500).forEachIndexed { index, code ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("sensitive-error-body"))
            val destination = File(directory, "error-$index.bin")
            val result = client.download("missing-$index", destination)
            assertFalse(result.isSuccessful)
            assertEquals(code, result.statusCode)
            assertFalse(destination.exists())
            assertFalse(result.partialFile.exists())
            assertFalse(result.message!!.contains("sensitive-error-body"))
        }
    }

    @Test fun errorResponseBodyIsClosedWithoutReadingItIntoMemory() = runBlocking {
        val closed = AtomicBoolean(false)
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            interceptor(Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = response.body!!
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = object : ForwardingSource(original.source()) {
                        override fun close() { closed.set(true); super.close() }
                    }.buffer()
                }).build()
            })
        }
        server.enqueue(MockResponse().setResponseCode(404).setBody("error payload must not be consumed"))
        val response = client.download("not-found", File(directory, "not-found.bin"))
        assertFalse(response.isSuccessful)
        assertTrue(closed.get())
    }

    @Test fun existingDestinationIsRejectedWithoutChangingItsContents() = runBlocking {
        val destination = File(directory, "existing.bin").apply { writeText("keep me") }
        assertThrows(IOException::class.java) { runBlocking { client.download("file", destination) } }
        assertEquals("keep me", destination.readText())
        assertEquals(0, server.requestCount)
    }

    @Test fun resumeSendsRangeAndAppendsOnlyValidatedPartialContent() = runBlocking {
        val destination = File(directory, "resumed.bin")
        File(directory, "resumed.bin.part").writeText("abcd")
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 4-9/10").setBody("efghij"))
        val result = client.download("resume", destination, resume = true)
        assertTrue(result.isSuccessful)
        assertTrue(result.resumed)
        assertEquals("abcdefghij", destination.readText())
        assertEquals("bytes=4-", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
    }

    @Test fun ignoredRangeRestartsFromZeroAnd416OrInvalidRangeKeepsPartialFile() = runBlocking {
        val ignored = File(directory, "ignored.bin")
        File(directory, "ignored.bin.part").writeText("old-prefix")
        server.enqueue(MockResponse().setResponseCode(200).setBody("complete-new-file"))
        val restarted = client.download("ignore-range", ignored, resume = true)
        assertTrue(restarted.isSuccessful)
        assertEquals("complete-new-file", ignored.readText())
        assertEquals("bytes=10-", server.takeRequest()!!.getHeader("Range"))

        val notSatisfiable = File(directory, "range-416.bin")
        File(directory, "range-416.bin.part").writeText("partial")
        server.enqueue(MockResponse().setResponseCode(416).addHeader("Content-Range", "bytes */20"))
        val rangeFailure = client.download("range-416", notSatisfiable, resume = true)
        assertFalse(rangeFailure.isSuccessful)
        assertEquals(416, rangeFailure.statusCode)
        assertEquals("partial", rangeFailure.partialFile.readText())
        assertFalse(notSatisfiable.exists())

        val invalid = File(directory, "invalid-range.bin")
        File(directory, "invalid-range.bin.part").writeText("prefix")
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 2-6/7").setBody("bad-range"))
        val invalidResult = client.download("invalid-range", invalid, resume = true)
        assertFalse(invalidResult.isSuccessful)
        assertEquals("Invalid Content-Range", invalidResult.message)
        assertEquals("prefix", invalidResult.partialFile.readText())
    }

    @Test fun transientNetworkRetryDoesNotDuplicateResponseBytes() = runBlocking {
        val attempts = AtomicLong(0)
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            retry { enabled = true; maxRetries = 1; initialDelay = kotlin.time.Duration.ZERO; jitterFactor = 0.0 }
            interceptor(Interceptor { chain ->
                if (attempts.incrementAndGet() == 1L) throw IOException("deterministic simulated disconnect")
                chain.proceed(chain.request())
            })
        }
        server.enqueue(MockResponse().setBody("fresh-content"))
        val destination = File(directory, "retry.bin")
        val result = client.download("retry", destination)
        assertTrue(result.isSuccessful)
        assertEquals("fresh-content", destination.readText())
        assertEquals(2L, attempts.get())
        assertEquals(1, server.requestCount)
    }

    @Test fun resumedRetryAfterMidBodyFailureRequestsOnlyMissingBytes() = runBlocking {
        val attempts = AtomicLong(0)
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            retry { enabled = true; maxRetries = 1; initialDelay = kotlin.time.Duration.ZERO; jitterFactor = 0.0 }
            interceptor(Interceptor { chain ->
                val response = chain.proceed(chain.request())
                if (attempts.incrementAndGet() != 1L) return@Interceptor response
                val original = response.body!!
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = object : ForwardingSource(original.source()) {
                        private var first = true
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (first) {
                                first = false
                                return super.read(sink, minOf(byteCount, 4L))
                            }
                            throw IOException("deterministic mid-body interruption")
                        }
                    }.buffer()
                }).build()
            })
        }
        server.enqueue(MockResponse().setBody("abcdefghij"))
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 4-9/10").setBody("efghij"))
        val destination = File(directory, "resume-retry.bin")
        val result = client.download("resume-retry", destination, resume = true)
        assertTrue(result.isSuccessful)
        assertEquals("abcdefghij", destination.readText())
        assertEquals(2L, attempts.get())
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("bytes=4-", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
    }

    @Test fun cancellationDuringStreamingCancelsCallClosesBodyAndDeletesNonResumablePartial() = runBlocking {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bodyClosed = AtomicBoolean(false)
        val destination = File(directory, "cancel.bin")
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            interceptor(Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = response.body!!
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = object : ForwardingSource(original.source()) {
                        private var first = true
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (first) {
                                first = false
                                return super.read(sink, minOf(byteCount, 32 * 1024L))
                            }
                            blocked.countDown()
                            release.await(5, TimeUnit.SECONDS)
                            return super.read(sink, byteCount)
                        }
                        override fun close() { bodyClosed.set(true); super.close() }
                    }.buffer()
                }).build()
            })
        }
        server.enqueue(MockResponse().setBody(ByteArray(256 * 1024) { 65 }.decodeToString()))
        val pending = async(Dispatchers.IO) { client.download("cancel", destination) }
        try {
            assertTrue("download should reach blocked stream read", blocked.await(5, TimeUnit.SECONDS))
            pending.cancel()
        } finally { release.countDown() }
        try {
            pending.await()
            throw AssertionError("cancelled download completed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(bodyClosed.get())
        assertFalse(destination.exists())
        assertFalse(File(directory, "cancel.bin.part").exists())
    }

    @Test fun responseBodyClosesAfterSuccessfulDownload() = runBlocking {
        val bodyClosed = AtomicBoolean(false)
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            interceptor(Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = response.body!!
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = object : ForwardingSource(original.source()) {
                        override fun close() { bodyClosed.set(true); super.close() }
                    }.buffer()
                }).build()
            })
        }
        server.enqueue(MockResponse().setBody("normal-download"))
        val result = client.download("close", File(directory, "closed.bin"))
        assertTrue(result.isSuccessful)
        assertTrue(bodyClosed.get())
    }

    @Test fun cancellingResumedDownloadKeepsItsPartialFile() = runBlocking {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destination = File(directory, "resumed-cancel.bin")
        val partial = File(directory, "resumed-cancel.bin.part").apply { writeText("seed") }
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            interceptor(Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = response.body!!
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = object : ForwardingSource(original.source()) {
                        private var first = true
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (first) {
                                first = false
                                return super.read(sink, minOf(byteCount, 32 * 1024L))
                            }
                            blocked.countDown()
                            release.await(5, TimeUnit.SECONDS)
                            return super.read(sink, byteCount)
                        }
                    }.buffer()
                }).build()
            })
        }
        val content = ByteArray(128 * 1024) { 65 }
        server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 4-${content.size + 3}/${content.size + 4}").setBody(Buffer().write(content)))
        val pending = async(Dispatchers.IO) { client.download("resume-cancel", destination, resume = true) }
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            pending.cancel()
        } finally { release.countDown() }
        try {
            pending.await()
            throw AssertionError("cancelled resumed download completed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(partial.exists())
        assertTrue(partial.length() > 4L)
        assertTrue(partial.readBytes().copyOfRange(0, 4).contentEquals("seed".toByteArray()))
        assertFalse(destination.exists())
    }

    @Test fun authRefreshRetriesWithoutWritingUnauthorizedBodyAndLogsNoFileContents() = runBlocking {
        val logs = mutableListOf<String>()
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            bearer { "expired-token" }
            refreshBearerToken { "fresh-token" }
            logging(LogLevel.HEADERS, LogSink { synchronized(logs) { logs += it } })
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("do-not-save-secret"))
        server.enqueue(MockResponse().setBody("real-file"))
        val destination = File(directory, "auth.bin")
        val result = client.download("protected", destination)
        assertTrue(result.isSuccessful)
        assertEquals("real-file", destination.readText())
        assertEquals("Bearer expired-token", server.takeRequest()!!.getHeader("Authorization"))
        assertEquals("Bearer fresh-token", server.takeRequest()!!.getHeader("Authorization"))
        val logText = synchronized(logs) { logs.joinToString("\n") }
        assertFalse(logText.contains("expired-token"))
        assertFalse(logText.contains("fresh-token"))
        assertFalse(logText.contains("do-not-save-secret"))
    }

    @Test fun concurrentDownloadsWriteToIndependentDestinations() = runBlocking {
        server.enqueue(MockResponse().setBody("file-a"))
        server.enqueue(MockResponse().setBody("file-b"))
        val responsesReady = CountDownLatch(2)
        val releaseResponses = CountDownLatch(1)
        client = RelayClient.create(context) {
            baseUrl = server.url("api/").toString()
            interceptor(Interceptor { chain ->
                val response = chain.proceed(chain.request())
                responsesReady.countDown()
                check(releaseResponses.await(5, TimeUnit.SECONDS)) { "concurrent response gate timed out" }
                response
            })
        }
        val a = File(directory, "a.bin")
        val b = File(directory, "b.bin")
        val jobs = listOf(
            async(Dispatchers.IO) { client.download("a", a) },
            async(Dispatchers.IO) { client.download("b", b) },
        )
        try {
            assertTrue("both calls should reach response handling concurrently", responsesReady.await(5, TimeUnit.SECONDS))
        } finally { releaseResponses.countDown() }
        assertTrue(jobs.all { it.await().isSuccessful })
        assertEquals(setOf("file-a", "file-b"), setOf(a.readText(), b.readText()))
    }
}

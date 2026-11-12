package com.relaykit.demo.core.data

import com.relaykit.demo.feature.history.HistoryStatusFilter
import com.relaykit.demo.feature.history.HistoryFilterState
import com.relaykit.demo.feature.history.filterHistory
import com.relaykit.demo.feature.logs.LogsFilterState
import com.relaykit.demo.feature.logs.filterLogs
import com.relaykit.demo.feature.request.RequestBuilderViewModel
import com.relaykit.network.request.HttpMethod
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ActivityRecordsTest {
    @Test fun historyIdsAreUniqueAndRecordsPersistAcrossRepositoryInstances() = runBlocking {
        val dir = Files.createTempDirectory("relaykit-history-test").toFile()
        try {
            val file = File(dir, "relaykit-history.json")
            val first = HistoryRecord(method = HttpMethod.GET, endpoint = "/users", resolvedUrl = "https://example.test/users")
            val second = HistoryRecord(method = HttpMethod.POST, endpoint = "/posts", resolvedUrl = "https://example.test/posts")
            assertNotEquals(first.id, second.id)
            HistoryRepository(file).upsert(first)
            HistoryRepository(file).upsert(second)

            val reopened = HistoryRepository(file)
            reopened.refresh()
            assertEquals(setOf(first.id, second.id), reopened.state.value.records.map { it.id }.toSet())
            assertNull(reopened.state.value.storageError)
        } finally { dir.deleteRecursively() }
    }

    @Test fun historyFiltersByMethodAndOutcomeAndSupportsDeletionAndClear() = runBlocking {
        val dir = Files.createTempDirectory("relaykit-history-filter").toFile()
        try {
            val repository = HistoryRepository(File(dir, "relaykit-history.json"))
            repository.upsert(HistoryRecord(method = HttpMethod.GET, endpoint = "/users", resolvedUrl = "https://example.test/users", success = true, outcome = "SUCCESS"))
            repository.upsert(HistoryRecord(method = HttpMethod.POST, endpoint = "/posts", resolvedUrl = "https://example.test/posts", success = false, outcome = "HTTP_ERROR"))
            val filtered = filterHistory(repository.state.value.records, HistoryFilterState(method = HttpMethod.POST, status = HistoryStatusFilter.FAILED))
            assertEquals(listOf("/posts"), filtered.map { it.endpoint })

            val id = filtered.single().id
            repository.delete(id)
            assertFalse(repository.state.value.records.any { it.id == id })
            repository.clear()
            assertTrue(repository.state.value.records.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun relayKitLogsPersistFilterAndClearIndependently() = runBlocking {
        val dir = Files.createTempDirectory("relaykit-logs-test").toFile()
        try {
            val historyFile = File(dir, "relaykit-history.json")
            val logsFile = File(dir, "relaykit-logs.json")
            val logs = LogsRepository(logsFile)
            logs.relayKitMessage("REQUEST POST https://example.test/posts?api_key=hidden")
            val captured = withTimeout(2_000) { logs.state.first { it.records.isNotEmpty() }.records.first() }
            assertEquals("RELAYKIT_LOG_SINK", captured.source)
            assertFalse(captured.message.contains("hidden"))

            val requestId = "demo-request-42"
            logs.append(DiagnosticLog(executionId = requestId, level = LogSeverity.ERROR, category = "Request failed", message = "network error", method = HttpMethod.POST, endpoint = "/posts"))
            val reopened = LogsRepository(logsFile)
            reopened.refresh()
            val filtered = filterLogs(reopened.state.value.records, LogsFilterState(requestId = requestId, method = HttpMethod.POST, endpoint = "/posts", level = LogSeverity.ERROR))
            assertEquals(requestId, filtered.single().executionId)
            reopened.clear()
            assertTrue(reopened.state.value.records.isEmpty())

            HistoryRepository(historyFile).upsert(HistoryRecord(method = HttpMethod.GET, endpoint = "/kept", resolvedUrl = "https://example.test/kept"))
            assertEquals(1, HistoryRepository(historyFile).also { it.refresh() }.state.value.records.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun redactionCoversHeadersQueriesBodiesAndBodyLimit() {
        val headers = RecordRedactor.headers(mapOf("Authorization" to "Bearer secret", "X-API-Key" to "key-secret", "Accept" to "application/json"))
        assertEquals("[REDACTED]", headers["Authorization"])
        assertEquals("[REDACTED]", headers["X-API-Key"])
        assertEquals("application/json", headers["Accept"])
        assertEquals("[REDACTED]", RecordRedactor.query(mapOf("access_token" to "secret"))["access_token"])
        val body = RecordRedactor.jsonBody("""{"password":"pw-1","profile":{"refresh_token":"tok-2","apiToken":"tok-3"},"credential":"c-4","message":"ok"}""")!!
        assertFalse(body.contains("pw-1"))
        assertFalse(body.contains("tok-2"))
        assertFalse(body.contains("tok-3"))
        assertFalse(body.contains("c-4"))
        assertTrue(body.contains("[REDACTED]"))
        assertTrue(RecordRedactor.bounded("x".repeat(RecordRedactor.MAX_BODY_CHARS + 10))!!.length < RecordRedactor.MAX_BODY_CHARS + 32)
    }

    @Test fun historyReopensSanitizedRequestWithoutExecuting() {
        val viewModel = RequestBuilderViewModel()
        val record = HistoryRecord(
            method = HttpMethod.POST, endpoint = "/users", resolvedUrl = "https://example.test/users", authType = "Bearer",
            bodyChoice = "JSON", requestHeaders = mapOf("Content-Type" to "application/json", "Authorization" to "[REDACTED]"),
            query = mapOf("page" to "2"), requestBody = """{"name":"Ada","password":"[REDACTED]"}""",
        )
        viewModel.loadFromHistory(record)
        assertEquals(HttpMethod.POST, viewModel.state.value.config.method)
        assertEquals("/users", viewModel.state.value.config.endpoint)
        assertTrue(viewModel.state.value.config.jsonBody.contains("[REDACTED]"))
        assertEquals("NONE", viewModel.state.value.config.authChoice.name)
        assertNull(viewModel.state.value.execution)
        assertFalse(viewModel.state.value.executing)
    }

    @Test fun workspaceActivityUsesPersistedHistoryRatherThanSamples() = runBlocking {
        val dir = Files.createTempDirectory("relaykit-workspace").toFile()
        try {
            val container = DemoContainer(dir)
            container.history.upsert(HistoryRecord(method = HttpMethod.PATCH, endpoint = "/profile", resolvedUrl = "https://example.test/profile", responseStatus = 200, success = true, outcome = "SUCCESS"))
            assertEquals("/profile", container.history.state.value.records.first().endpoint)
            val reopened = HistoryRepository(File(dir, "relaykit-history.json"))
            reopened.refresh()
            assertEquals("/profile", reopened.state.value.records.first().endpoint)
        } finally { dir.deleteRecursively() }
    }
}

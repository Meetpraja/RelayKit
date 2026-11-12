package com.relaykit.demo.core.data

import com.relaykit.network.request.HttpMethod
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class LogSeverity { DEBUG, INFO, WARNING, ERROR }

data class HistoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val method: HttpMethod,
    val endpoint: String,
    val resolvedUrl: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val requestHeaders: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val authType: String = "None",
    val bodyChoice: String = "NONE",
    val requestBody: String? = null,
    val requestBodyFields: Map<String, String> = emptyMap(),
    val responseStatus: Int? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String? = null,
    val durationMs: Long = 0,
    val success: Boolean? = null,
    val outcome: String = "RUNNING",
    val errorDetails: String? = null,
)

data class DiagnosticLog(
    val id: String = UUID.randomUUID().toString(),
    val executionId: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val level: LogSeverity,
    val category: String,
    val message: String,
    val method: HttpMethod? = null,
    val endpoint: String? = null,
    val source: String = "DEMO",
)

data class HistorySnapshot(val records: List<HistoryRecord> = emptyList(), val storageError: String? = null)
data class LogsSnapshot(val records: List<DiagnosticLog> = emptyList(), val storageError: String? = null)

/** One redaction policy is used before persistence and again before UI display. */
object RecordRedactor {
    const val MAX_BODY_CHARS = 64 * 1024
    private val sensitiveNames = setOf(
        "authorization", "proxy_authorization", "www_authenticate", "x_api_key", "api_key", "apikey",
        "password", "passwd", "token", "access_token", "refresh_token", "client_secret", "private_key", "credential", "credentials", "cookie", "set_cookie", "secret",
    )
    private val freeTextSecret = Regex("""(?i)((?:authorization|proxy-authorization|x-api-key|api[_-]?key|apikey|password|passwd|[a-z0-9_-]*token|[a-z0-9_-]*secret|[a-z0-9_-]*password|[a-z0-9_-]*credential|cookie)\"?\s*[=:]\s*\"?)(?:bearer\s+)?([^\s,;\"'}]+)""")
    private val bearer = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*")

    fun isSensitive(name: String): Boolean {
        val normalized = name.lowercase().replace('-', '_')
        val compact = normalized.replace("_", "")
        return normalized in sensitiveNames || normalized.endsWith("_token") || normalized.endsWith("_secret") || normalized.endsWith("_password") || normalized.endsWith("_credential") || normalized.endsWith("_credentials") || normalized.endsWith("_cookie") || compact.contains("token") || compact.contains("secret") || compact.contains("password") || compact.contains("credential") || compact.contains("apikey")
    }

    fun headers(values: Map<String, String>): Map<String, String> = values.mapValues { (key, value) -> if (isSensitive(key)) "[REDACTED]" else text(value) }
    fun query(values: Map<String, String>): Map<String, String> = values.mapValues { (key, value) -> if (isSensitive(key)) "[REDACTED]" else text(value) }
    fun text(value: String): String = bearer.replace(freeTextSecret.replace(value, "$1[REDACTED]"), "$1[REDACTED]")

    fun url(raw: String): String {
        val queryStart = raw.indexOf('?')
        if (queryStart < 0) return text(raw)
        val fragmentStart = raw.indexOf('#', queryStart).let { if (it < 0) raw.length else it }
        val head = raw.substring(0, queryStart + 1)
        val query = raw.substring(queryStart + 1, fragmentStart).split('&').joinToString("&") { part ->
            val equals = part.indexOf('=')
            val name = if (equals < 0) part else part.substring(0, equals)
            if (isSensitive(runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name))) "$name=[REDACTED]" else text(part)
        }
        return head + query + raw.substring(fragmentStart)
    }

    fun jsonBody(raw: String?): String? = raw?.let { bounded(redactJsonText(it)) }

    fun jsonBody(value: Any?): String? = value?.let { item ->
        val json = Json.encodeToString(JsonElement.serializer(), toElement(item))
        jsonBody(json)
    }

    fun bounded(value: String?): String? = value?.let { if (it.length <= MAX_BODY_CHARS) it else it.take(MAX_BODY_CHARS) + "… [truncated]" }

    private fun redactJsonText(raw: String): String = runCatching {
        val parsed = Json.parseToJsonElement(raw)
        Json.encodeToString(JsonElement.serializer(), redactElement(parsed))
    }.getOrElse {
        freeTextSecret.replace(raw, "$1[REDACTED]")
    }

    private fun redactElement(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) -> if (isSensitive(key)) JsonPrimitive("[REDACTED]") else redactElement(child) })
        is JsonArray -> JsonArray(value.map(::redactElement))
        else -> value
    }

    private fun toElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
            require(key is String) { "Request body keys must be strings" }
            key to toElement(item)
        })
        is Iterable<*> -> JsonArray(value.map(::toElement))
        is Array<*> -> JsonArray(value.map(::toElement))
        else -> JsonPrimitive(value.toString())
    }
}

/** Small atomic JSON-file persistence for demo-only activity records. All calls run on IO. */
private class JsonListFile(private val file: File) {
    fun read(): List<JsonObject> {
        if (!file.exists()) return emptyList()
        return Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }
    }

    fun write(records: List<JsonObject>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(JsonArray(records).toString())
        if (file.exists() && !file.delete()) throw IllegalStateException("Could not replace ${file.name}")
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }
}

class HistoryRepository(private val file: File, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val store = JsonListFile(file)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(HistorySnapshot())
    val state = mutable.asStateFlow()

    init { scope.launch { refresh() } }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        runCatching { mutex.withLock { store.read().map(::decodeHistory).sortedByDescending { it.timestampMillis } } }
            .onSuccess { mutable.value = HistorySnapshot(it) }
            .onFailure { mutable.value = mutable.value.copy(storageError = it.message ?: "History storage could not be read") }
    }

    suspend fun upsert(record: HistoryRecord) = withContext(Dispatchers.IO) {
        runCatching { mutex.withLock {
            val records = store.read().filterNot { it["id"]?.jsonPrimitive?.contentOrNull == record.id }
                .plus(encodeHistory(record)).sortedByDescending { it["timestampMillis"]?.jsonPrimitive?.longOrNull ?: 0L }.take(MAX_RECORDS)
            store.write(records)
            records.map(::decodeHistory)
        } }.onSuccess { mutable.value = HistorySnapshot(it) }
            .onFailure { mutable.value = mutable.value.copy(storageError = it.message ?: "History could not be saved") }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runCatching { mutex.withLock { store.read().filterNot { it["id"]?.jsonPrimitive?.contentOrNull == id }.also(store::write).map(::decodeHistory) } }
            .onSuccess { mutable.value = HistorySnapshot(it) }
            .onFailure { mutable.value = mutable.value.copy(storageError = it.message ?: "History record could not be deleted") }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { mutex.withLock { store.write(emptyList()) } }.onSuccess { mutable.value = HistorySnapshot() }
            .onFailure { mutable.value = mutable.value.copy(storageError = it.message ?: "History could not be cleared") }
    }

    private companion object { const val MAX_RECORDS = 500 }
}

class LogsRepository(private val file: File, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val store = JsonListFile(file)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(LogsSnapshot())
    val state = mutable.asStateFlow()

    init { scope.launch { refresh() } }

    fun recordAsync(record: DiagnosticLog) { scope.launch { append(record) } }
    fun relayKitMessage(message: String) {
        val safe = RecordRedactor.text(message)
        val request = Regex("^REQUEST\\s+(GET|POST|PUT|PATCH|DELETE)\\s+(\\S+)").find(safe)
        val response = Regex("^RESPONSE\\s+(\\d{3})\\s+(GET|POST|PUT|PATCH|DELETE)").find(safe)
        val code = response?.groupValues?.get(1)?.toIntOrNull()
        recordAsync(DiagnosticLog(
            level = if (code != null && code >= 400) LogSeverity.WARNING else LogSeverity.INFO,
            category = if (request != null) "HTTP request" else if (response != null) "HTTP response" else "RelayKit",
            message = safe,
            method = request?.groupValues?.get(1)?.let { runCatching { HttpMethod.valueOf(it) }.getOrNull() }
                ?: response?.groupValues?.get(2)?.let { runCatching { HttpMethod.valueOf(it) }.getOrNull() },
            endpoint = request?.groupValues?.get(2)?.let(RecordRedactor::url),
            source = "RELAYKIT_LOG_SINK",
        ))
    }

    suspend fun append(record: DiagnosticLog) = withContext(Dispatchers.IO) {
        runCatching { mutex.withLock {
            val safe = record.copy(message = RecordRedactor.text(record.message), endpoint = record.endpoint?.let(RecordRedactor::url))
            val records = store.read().filterNot { it["id"]?.jsonPrimitive?.contentOrNull == safe.id }
                .plus(encodeLog(safe)).sortedByDescending { it["timestampMillis"]?.jsonPrimitive?.longOrNull ?: 0L }.take(MAX_RECORDS)
            store.write(records)
            records.map(::decodeLog)
        } }.onSuccess { mutable.value = LogsSnapshot(it) }
            .onFailure { mutable.value = mutable.value.copy(storageError = it.message ?: "Diagnostic log could not be saved") }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        runCatching { mutex.withLock { store.read().map(::decodeLog).sortedByDescending { it.timestampMillis } } }
            .onSuccess { mutable.value = LogsSnapshot(it) }
            .onFailure { mutable.value = mutable.value.copy(storageError = it.message ?: "Diagnostic logs could not be read") }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { mutex.withLock { store.write(emptyList()) } }.onSuccess { mutable.value = LogsSnapshot() }
            .onFailure { mutable.value = mutable.value.copy(storageError = it.message ?: "Diagnostic logs could not be cleared") }
    }

    private companion object { const val MAX_RECORDS = 1000 }
}

class DemoContainer(filesDir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val history = HistoryRepository(File(filesDir, "relaykit-history.json"), scope)
    val logs = LogsRepository(File(filesDir, "relaykit-logs.json"), scope)
    fun demoLog(record: DiagnosticLog) = logs.recordAsync(record)
}

private fun encodeHistory(value: HistoryRecord) = buildJsonObject {
    put("id", value.id); put("method", value.method.name); put("endpoint", RecordRedactor.url(value.endpoint)); put("resolvedUrl", RecordRedactor.url(value.resolvedUrl))
    put("timestampMillis", value.timestampMillis); put("requestHeaders", stringMap(RecordRedactor.headers(value.requestHeaders)))
    put("query", stringMap(RecordRedactor.query(value.query))); put("authType", value.authType); put("bodyChoice", value.bodyChoice)
    value.requestBody?.let { put("requestBody", RecordRedactor.jsonBody(it)) }; put("requestBodyFields", stringMap(RecordRedactor.query(value.requestBodyFields)))
    value.responseStatus?.let { put("responseStatus", it) }; put("responseHeaders", stringMap(RecordRedactor.headers(value.responseHeaders)))
    value.responseBody?.let { put("responseBody", RecordRedactor.jsonBody(it)) }; put("durationMs", value.durationMs)
    value.success?.let { put("success", it) }; put("outcome", value.outcome); value.errorDetails?.let { put("errorDetails", RecordRedactor.text(it)) }
}

private fun decodeHistory(value: JsonObject) = HistoryRecord(
    id = value.str("id"), method = runCatching { HttpMethod.valueOf(value.str("method")) }.getOrDefault(HttpMethod.GET),
    endpoint = RecordRedactor.url(value.str("endpoint")), resolvedUrl = RecordRedactor.url(value.str("resolvedUrl")), timestampMillis = value.long("timestampMillis"),
    requestHeaders = value.map("requestHeaders"), query = value.map("query"), authType = value.str("authType"), bodyChoice = value.str("bodyChoice"),
    requestBody = value.optStr("requestBody")?.let(RecordRedactor::jsonBody), requestBodyFields = value.map("requestBodyFields"),
    responseStatus = value["responseStatus"]?.jsonPrimitive?.intOrNull, responseHeaders = value.map("responseHeaders"),
    responseBody = value.optStr("responseBody")?.let(RecordRedactor::jsonBody), durationMs = value.long("durationMs"),
    success = value["success"]?.jsonPrimitive?.booleanOrNull, outcome = value.str("outcome"), errorDetails = value.optStr("errorDetails")?.let(RecordRedactor::text),
)

private fun encodeLog(value: DiagnosticLog) = buildJsonObject {
    put("id", value.id); value.executionId?.let { put("executionId", it) }; put("timestampMillis", value.timestampMillis); put("level", value.level.name)
    put("category", value.category); put("message", RecordRedactor.text(value.message)); value.method?.let { put("method", it.name) }
    value.endpoint?.let { put("endpoint", RecordRedactor.url(it)) }; put("source", value.source)
}

private fun decodeLog(value: JsonObject) = DiagnosticLog(
    id = value.str("id"), executionId = value.optStr("executionId"), timestampMillis = value.long("timestampMillis"),
    level = runCatching { LogSeverity.valueOf(value.str("level")) }.getOrDefault(LogSeverity.INFO), category = value.str("category"),
    message = RecordRedactor.text(value.str("message")), method = value.optStr("method")?.let { runCatching { HttpMethod.valueOf(it) }.getOrNull() },
    endpoint = value.optStr("endpoint")?.let(RecordRedactor::url), source = value.str("source"),
)

private fun stringMap(value: Map<String, String>) = buildJsonObject { value.forEach { (key, item) -> put(key, item) } }
private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.optStr(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L
private fun JsonObject.map(key: String) = this[key]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()

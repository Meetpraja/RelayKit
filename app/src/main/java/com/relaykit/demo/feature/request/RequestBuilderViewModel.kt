package com.relaykit.demo.feature.request

import androidx.lifecycle.ViewModel
import com.relaykit.network.config.Auth
import com.relaykit.network.request.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import com.relaykit.network.RelayKit
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.error.ErrorType
import com.relaykit.demo.core.data.DemoContainer
import com.relaykit.demo.core.data.DiagnosticLog
import com.relaykit.demo.core.data.HistoryRecord
import com.relaykit.demo.core.data.LogSeverity
import com.relaykit.demo.core.data.RecordRedactor
import com.relaykit.network.config.ApiKeyPlacement
import com.relaykit.network.config.Auth as RelayAuth
import java.net.URLEncoder
import java.util.UUID
import java.io.IOException
import java.net.SocketTimeoutException
import org.json.JSONArray
import org.json.JSONObject

data class RequestExecution(val response: TransportResponse?, val durationMs: Long, val requestId: String, val errorType: ErrorType? = null, val error: String? = null)
data class RequestBuilderState(val config: RequestConfiguration = RequestConfiguration(), val tab: RequestTab = RequestTab.HEADERS, val showSecret: Boolean = false, val validation: RequestValidation = RequestValidation(), val executing: Boolean = false, val execution: RequestExecution? = null, val executionMessage: String? = null)
enum class RequestTab { HEADERS, QUERY, AUTH, BODY }

/** Holds and validates an editable request without performing network operations. */
class RequestBuilderViewModel(private val data: DemoContainer? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(RequestBuilderState())
    val state = mutableState.asStateFlow()
    private var executionJob: kotlinx.coroutines.Job? = null
    fun edit(transform: (RequestConfiguration) -> RequestConfiguration) { mutableState.value = mutableState.value.copy(config = transform(mutableState.value.config), validation = RequestValidation()) }
    fun selectTab(tab: RequestTab) { mutableState.value = mutableState.value.copy(tab = tab) }
    fun showSecret(show: Boolean) { mutableState.value = mutableState.value.copy(showSecret = show) }

    fun prepare(): PreparedRequest {
        val c = mutableState.value.config
        val endpointError = validateEndpoint(c.endpoint)
        val headerErrors = c.headers.filter { it.enabled }.associateNotNull { row ->
            when { !validHeaderName(row.name) -> row.id to "Enter a valid header name"; '\n' in row.value || '\r' in row.value -> row.id to "Header value cannot contain a newline"; else -> null }
        }
        val queryErrors = c.query.filter { it.enabled }.associateNotNull { row -> if (row.name.isBlank()) row.id to "Parameter name is required" else null }
        val jsonError = if (c.bodyChoice == BodyChoice.JSON) validateJson(c.jsonBody) else null
        val bodyError = when {
            c.method in setOf(HttpMethod.GET, HttpMethod.DELETE) && c.bodyChoice != BodyChoice.NONE -> "${c.method.name} requests cannot include a body through the current public API"
            c.bodyChoice == BodyChoice.FORM && c.bodyFields.any { it.enabled && it.name.isBlank() } -> "Enabled form field names are required"
            c.bodyChoice == BodyChoice.MULTIPART && c.bodyFields.any { it.enabled && it.name.isBlank() } -> "Enabled multipart field names are required"
            else -> null
        }
        val validation = RequestValidation(endpointError, headerErrors, queryErrors, jsonError, bodyError)
        val auth = when (c.authChoice) {
            AuthChoice.INHERIT -> null
            AuthChoice.NONE -> Auth.None
            AuthChoice.API_KEY -> Auth.ApiKey(c.authValue, c.authName.ifBlank { "X-API-Key" })
            AuthChoice.BEARER -> Auth.Bearer { c.authValue }
            AuthChoice.CUSTOM -> Auth.Custom(c.authName, c.authValue)
        }
        val body = if (c.bodyChoice == BodyChoice.JSON && jsonError == null) parseJsonObject(c.jsonBody) else null
        val form = c.bodyFields.filter { it.enabled }.associate { it.name to it.value }.takeIf { c.bodyChoice == BodyChoice.FORM }
        val multipart = c.bodyFields.filter { it.enabled }.associate { it.name to it.value }.takeIf { c.bodyChoice == BodyChoice.MULTIPART }
        return PreparedRequest(c.method, c.endpoint.trim(), c.headers.filter { it.enabled }.associate { it.name.trim() to it.value }, c.query.filter { it.enabled }.associate { it.name to it.value }, auth, body, form, multipart, validation, c.files)
    }

    fun execute() {
        if (mutableState.value.executing) return
        val prepared = prepare()
        if (!prepared.validation.valid) return
        mutableState.value = mutableState.value.copy(executing = true, executionMessage = null)
        val requestId = UUID.randomUUID().toString()
        executionJob = viewModelScope.launch {
            val started = System.nanoTime()
            val client = runCatching { RelayKit.client() }.getOrNull()
            val history = client?.let { historyRecord(requestId, prepared, it.config.baseUrl, it.config.headers, it.config.queryParams, it.config.auth) }
            history?.let { data?.history?.upsert(it) }
            data?.demoLog(DiagnosticLog(executionId = requestId, level = LogSeverity.INFO, category = "Request sent", message = "Sending ${prepared.method} ${history?.resolvedUrl.orEmpty()}", method = prepared.method, endpoint = prepared.endpoint))
            try {
                val activeClient = client ?: RelayKit.client()
                val response = activeClient.request(prepared.endpoint, prepared.method) {
                    body(prepared.body)
                    prepared.query.forEach { (name, value) -> query(name, value) }
                    prepared.headers.forEach { (name, value) -> header(name, value) }
                    auth(prepared.auth)
                    prepared.form?.let { form(it) }
                    prepared.multipart?.let { multipart(it) }
                    prepared.files.forEach { file(it) }
                    tag("relaykit-demo")
                    extra("demoExecutionId", requestId)
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val safeResponse = response.copy(headers = RecordRedactor.headers(response.headers), body = RecordRedactor.jsonBody(response.body).orEmpty())
                history?.let { record -> data?.history?.upsert(record.copy(responseStatus = response.statusCode, responseHeaders = safeResponse.headers, responseBody = safeResponse.body, durationMs = elapsed, success = response.isSuccessful, outcome = if (response.isSuccessful) "SUCCESS" else "HTTP_ERROR", errorDetails = if (response.isSuccessful) null else "HTTP ${response.statusCode}: ${RecordRedactor.text(response.body).take(500)}")) }
                mutableState.value = mutableState.value.copy(
                    execution = RequestExecution(safeResponse, elapsed, requestId, if (response.isSuccessful) null else ErrorType.HTTP, if (response.isSuccessful) null else "HTTP ${response.statusCode}"),
                    executionMessage = null,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                history?.let { record -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { data?.history?.upsert(record.copy(durationMs = (System.nanoTime() - started) / 1_000_000, success = false, outcome = "CANCELLED", errorDetails = "Request cancelled")) } }
                data?.demoLog(DiagnosticLog(executionId = requestId, level = LogSeverity.WARNING, category = "Request cancelled", message = "${prepared.method} ${prepared.endpoint} was cancelled", method = prepared.method, endpoint = prepared.endpoint))
                mutableState.value = mutableState.value.copy(executionMessage = "Request cancelled")
                throw cancelled
            } catch (error: Exception) {
                val type = when (error) { is SocketTimeoutException -> ErrorType.TIMEOUT; is IOException -> ErrorType.NETWORK; else -> ErrorType.UNKNOWN }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val safeError = RecordRedactor.text(error.message ?: "Request failed")
                history?.let { record -> data?.history?.upsert(record.copy(durationMs = elapsed, success = false, outcome = "FAILED", errorDetails = safeError)) }
                data?.demoLog(DiagnosticLog(executionId = requestId, level = LogSeverity.ERROR, category = "Request failed", message = "${type.name}: $safeError", method = prepared.method, endpoint = prepared.endpoint))
                mutableState.value = mutableState.value.copy(execution = RequestExecution(null, elapsed, requestId, type, safeError))
            } finally {
                mutableState.value = mutableState.value.copy(executing = false)
            }
        }
    }

    fun cancelExecution() { executionJob?.cancel() }

    /** Restores only sanitized values and never executes the request. */
    fun loadFromHistory(record: HistoryRecord) {
        mutableState.value = RequestBuilderState(config = record.toSanitizedRequestConfiguration())
    }

    private fun historyRecord(id: String, prepared: PreparedRequest, baseUrl: String, defaultHeaders: Map<String, String>, defaultQuery: Map<String, String>, defaultAuth: RelayAuth?): HistoryRecord {
        val auth = prepared.auth ?: defaultAuth
        val headers = (defaultHeaders + prepared.headers).toMutableMap()
        when (auth) {
            is RelayAuth.Bearer -> headers["Authorization"] = "[REDACTED]"
            is RelayAuth.Custom -> headers[auth.header] = "[REDACTED]"
            is RelayAuth.ApiKey -> if (auth.placement == ApiKeyPlacement.HEADER) headers[auth.name] = "[REDACTED]"
            else -> Unit
        }
        val query = (defaultQuery + prepared.query).mapValues { (_, value) -> value.toString() }.toMutableMap()
        if (auth is RelayAuth.ApiKey && auth.placement == ApiKeyPlacement.QUERY) query[auth.name] = "[REDACTED]"
        val bodyValue: Any? = when {
            prepared.body != null -> prepared.body
            prepared.form != null -> prepared.form
            prepared.multipart != null -> prepared.multipart
            prepared.files.isNotEmpty() -> mapOf("files" to prepared.files.map { it.fileName })
            else -> null
        }
        val fields = (prepared.form ?: prepared.multipart).orEmpty().mapValues { (key, value) -> if (RecordRedactor.isSensitive(key)) "[REDACTED]" else RecordRedactor.text(value) } + prepared.files.associate { it.fileName to "[file omitted]" }
        val resolved = resolveUrl(baseUrl, prepared.endpoint)
        val queryString = query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }.takeIf { it.isNotEmpty() }
        val fullUrl = if (queryString == null) resolved else "$resolved${if ('?' in resolved) "&" else "?"}$queryString"
        return HistoryRecord(
            id = id, method = prepared.method, endpoint = prepared.endpoint, resolvedUrl = RecordRedactor.url(fullUrl),
            requestHeaders = RecordRedactor.headers(headers), query = RecordRedactor.query(query),
            authType = when (auth) { null, RelayAuth.None -> "None"; is RelayAuth.ApiKey -> "API key"; is RelayAuth.Bearer -> "Bearer"; is RelayAuth.Custom -> "Custom" },
            bodyChoice = mutableState.value.config.bodyChoice.name, requestBody = RecordRedactor.jsonBody(bodyValue), requestBodyFields = fields,
        )
    }

    private fun resolveUrl(base: String, endpoint: String) = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) endpoint else base + endpoint.removePrefix("/")
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    fun formatJson() { edit { it.copy(jsonBody = prettyJson(it.jsonBody)) } }
    fun resetJson() { edit { it.copy(jsonBody = "{\n  \n}") } }

    private fun validateEndpoint(endpoint: String): String? {
        if (endpoint.isBlank()) return "Endpoint is required"
        if (endpoint.startsWith("/")) return null
        val uri = runCatching { java.net.URI(endpoint) }.getOrNull() ?: return "Enter a relative endpoint or valid absolute URL"
        return if (!uri.isAbsolute || (uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank())) null else "Absolute endpoints must use HTTP or HTTPS"
    }
    private fun validHeaderName(name: String) = name.isNotBlank() && name.none { it <= ' ' || it in "()<>@,;:\\\"/[]?={}" }
    private fun validateJson(value: String): String? = runCatching { parseJsonObject(value); null }.getOrElse { it.message ?: "Invalid JSON" }
    private fun parseJsonObject(value: String): Any = runCatching { JSONObject(value).toMap() }.getOrElse { JSONArray(value).toList() }
    private fun prettyJson(value: String): String = runCatching { JSONObject(value).toString(2) }.getOrElse { JSONArray(value).toString(2) }
    private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key -> when (val v = get(key)) { JSONObject.NULL -> null; is JSONObject -> v.toMap(); is JSONArray -> v.toList(); else -> v } }
    private fun JSONArray.toList(): List<Any?> = (0 until length()).map { i -> when (val v = get(i)) { JSONObject.NULL -> null; is JSONObject -> v.toMap(); is JSONArray -> v.toList(); else -> v } }
    private inline fun <K, V : Any> Iterable<V?>.associateNotNull(keySelector: (V) -> Pair<K, String>?): Map<K, String> = mapNotNull { it?.let(keySelector) }.toMap()
}

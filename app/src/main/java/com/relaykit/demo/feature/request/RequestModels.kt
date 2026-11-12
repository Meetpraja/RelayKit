package com.relaykit.demo.feature.request

import com.relaykit.network.config.Auth
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayFile
import com.relaykit.demo.core.data.HistoryRecord
import java.io.File
import java.util.UUID

data class HeaderEntry(val id: String = UUID.randomUUID().toString(), val name: String = "", val value: String = "", val enabled: Boolean = true)
data class QueryEntry(val id: String = UUID.randomUUID().toString(), val name: String = "", val value: String = "", val enabled: Boolean = true)
enum class AuthChoice { INHERIT, NONE, API_KEY, BEARER, CUSTOM }
enum class BodyChoice { NONE, JSON, FORM, MULTIPART }
data class RequestConfiguration(
    val method: HttpMethod = HttpMethod.GET,
    val endpoint: String = "",
    val headers: List<HeaderEntry> = emptyList(),
    val query: List<QueryEntry> = emptyList(),
    val authChoice: AuthChoice = AuthChoice.INHERIT,
    val authName: String = "X-API-Key",
    val authValue: String = "",
    val bodyChoice: BodyChoice = BodyChoice.NONE,
    val jsonBody: String = "{\n  \n}",
    val bodyFields: List<QueryEntry> = emptyList(),
    val files: List<RelayFile> = emptyList(),
)
data class RequestValidation(val endpointError: String? = null, val headerErrors: Map<String, String> = emptyMap(), val queryErrors: Map<String, String> = emptyMap(), val jsonError: String? = null, val bodyError: String? = null) {
    val valid: Boolean get() = endpointError == null && headerErrors.isEmpty() && queryErrors.isEmpty() && jsonError == null && bodyError == null
}
data class PreparedRequest(
    val method: HttpMethod, val endpoint: String, val headers: Map<String, String>, val query: Map<String, Any?>,
    val auth: Auth?, val body: Any?, val form: Map<String, String>?, val multipart: Map<String, String>?, val validation: RequestValidation,
    val files: List<RelayFile> = emptyList(),
)

/** Converts a persisted record into an editable draft; secret values and real files stay omitted. */
fun HistoryRecord.toSanitizedRequestConfiguration(): RequestConfiguration {
    val fileCount = requestBodyFields.count { it.value == "[file omitted]" }
    return RequestConfiguration(
        method = method,
        endpoint = endpoint,
        headers = requestHeaders.filterValues { it != "[REDACTED]" }.map { (name, value) -> HeaderEntry(name = name, value = value) },
        query = query.filterValues { it != "[REDACTED]" }.map { (name, value) -> QueryEntry(name = name, value = value) },
        authChoice = AuthChoice.NONE,
        bodyChoice = runCatching { BodyChoice.valueOf(bodyChoice) }.getOrDefault(BodyChoice.NONE),
        jsonBody = requestBody ?: "{\n  \n}",
        bodyFields = requestBodyFields.filterValues { it != "[file omitted]" }.map { (name, value) -> QueryEntry(name = name, value = value) },
        files = (1..fileCount).map { index -> RelayFile(name = "file", file = File("path/to/upload-$index.bin"), fileName = "upload-$index.bin") },
    )
}

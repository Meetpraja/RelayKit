package com.relaykit.demo.feature.setup

import android.content.Context
import androidx.lifecycle.ViewModel
import com.relaykit.network.RelayClient
import com.relaykit.network.RelayKit
import com.relaykit.network.config.Auth
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import com.relaykit.network.event.RelayEvent
import com.relaykit.demo.core.data.DemoContainer
import com.relaykit.demo.core.data.DiagnosticLog
import com.relaykit.demo.core.data.LogSeverity
import com.relaykit.demo.core.data.RecordRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.seconds

enum class AuthMode { NONE, API_KEY, BEARER }
data class HeaderDraft(val name: String = "", val value: String = "", val enabled: Boolean = true)
data class SetupState(
    val baseUrl: String = "https://api.example.com/",
    val connectSeconds: String = "15",
    val readSeconds: String = "30",
    val writeSeconds: String = "30",
    val logLevel: LogLevel = LogLevel.NONE,
    val authMode: AuthMode = AuthMode.NONE,
    val apiKey: String = "",
    val bearerToken: String = "",
    val headers: List<HeaderDraft> = emptyList(),
    val initialized: Boolean = false,
    val activeBaseUrl: String? = null,
    val activeConnectSeconds: Long? = null,
    val activeReadSeconds: Long? = null,
    val activeWriteSeconds: Long? = null,
    val activeLogLevel: LogLevel? = null,
    val activeAuth: String = "None",
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val latestLog: String? = null,
)

/** UI state and RelayKit configuration lifecycle for Setup and Workspace. */
class SetupViewModel(private val data: DemoContainer? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(SetupState())
    val state = mutableState.asStateFlow()
    private var client: RelayClient? = null

    fun update(transform: (SetupState) -> SetupState) { mutableState.value = transform(mutableState.value) }

    fun initialize(context: Context) {
        val form = mutableState.value
        val base = form.baseUrl.trim()
        val parsed = runCatching { java.net.URI(base) }.getOrNull()
        if (parsed == null || !parsed.isAbsolute || parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank() || !base.endsWith('/')) {
            update { it.copy(error = "Enter an absolute HTTP or HTTPS base URL ending in /", message = null) }
            return
        }
        val connect = form.connectSeconds.toLongOrNull()?.takeIf { it in 1..300 }
        val read = form.readSeconds.toLongOrNull()?.takeIf { it in 1..300 }
        val write = form.writeSeconds.toLongOrNull()?.takeIf { it in 1..300 }
        if (connect == null || read == null || write == null) {
            update { it.copy(error = "Timeouts must be whole seconds from 1 to 300", message = null) }
            return
        }
        val validHeaders = form.headers.filter { it.enabled }
        if (validHeaders.any { it.name.isBlank() || it.name.any { c -> c <= ' ' || c in "()<>@,;:\\\"/[]?={}" } || '\n' in it.value || '\r' in it.value }) {
            update { it.copy(error = "Check enabled header names and values", message = null) }
            return
        }
        if ((form.authMode == AuthMode.API_KEY && form.apiKey.isBlank()) || (form.authMode == AuthMode.BEARER && form.bearerToken.isBlank())) {
            update { it.copy(error = "Enter the credential for the selected authentication type", message = null) }
            return
        }
        update { it.copy(busy = true, error = null, message = null) }
        try {
            val logCallback = LogSink { message ->
                update { current -> current.copy(latestLog = RecordRedactor.text(message).take(500)) }
                data?.logs?.relayKitMessage(message)
            }
            val created = RelayKit.init(context.applicationContext) {
                baseUrl = base
                timeouts { this.connect = connect.seconds; this.read = read.seconds; this.write = write.seconds; call = maxOf(connect, read, write).seconds }
                logging(form.logLevel, logCallback)
                events(listener = com.relaykit.network.event.RelayEventListener { event -> data?.logs?.recordAsync(event.toDiagnosticLog()) })
                when (form.authMode) {
                    AuthMode.NONE -> auth(Auth.None)
                    AuthMode.API_KEY -> apiKey(form.apiKey)
                    AuthMode.BEARER -> bearer { form.bearerToken }
                }
                validHeaders.forEach { header(it.name.trim(), it.value) }
            }
            client = created
            update { it.copy(initialized = true, activeBaseUrl = created.config.baseUrl,
                activeConnectSeconds = created.config.timeouts.connect.inWholeSeconds,
                activeReadSeconds = created.config.timeouts.read.inWholeSeconds,
                activeWriteSeconds = created.config.timeouts.write.inWholeSeconds,
                activeLogLevel = created.config.loggingLevel,
                activeAuth = when (created.config.auth) { null, Auth.None -> "None"; is Auth.ApiKey -> "API key"; is Auth.Bearer -> "Bearer token"; is Auth.Custom -> "Custom authorization" },
                busy = false, error = null, message = "RelayKit initialized successfully") }
        } catch (failure: Exception) {
            update { it.copy(busy = false, initialized = false, error = failure.message ?: "RelayKit initialization failed") }
        }
    }

    fun reset() {
        RelayKit.resetForTesting()
        client = null
        update { SetupState() .copy(message = "RelayKit reset. Configuration and credentials remain only in this screen's memory until reset.") }
    }
}

private fun RelayEvent.toDiagnosticLog(): DiagnosticLog {
    val executionId = extras["demoExecutionId"] as? String
    return when (this) {
        is RelayEvent.Started -> DiagnosticLog(executionId = executionId, level = LogSeverity.DEBUG, category = "Request started", message = "RelayKit started $method $endpoint", method = method, endpoint = endpoint, source = "RELAYKIT_EVENT")
        is RelayEvent.Succeeded -> DiagnosticLog(executionId = executionId, level = LogSeverity.INFO, category = "Request completed", message = "RelayKit received HTTP $statusCode in ${durationMs}ms", method = method, endpoint = endpoint, source = "RELAYKIT_EVENT")
        is RelayEvent.Failed -> {
            val code = statusCode
            DiagnosticLog(executionId = executionId, level = if (code != null && code >= 400) LogSeverity.WARNING else LogSeverity.ERROR, category = "Request failed", message = "${errorType.name}: ${RecordRedactor.text(message)}", method = method, endpoint = endpoint, source = "RELAYKIT_EVENT")
        }
    }
}

package com.relaykit.demo.feature.codegenerator

import com.relaykit.demo.core.data.RecordRedactor
import com.relaykit.demo.feature.request.AuthChoice
import com.relaykit.demo.feature.request.BodyChoice
import com.relaykit.demo.feature.request.RequestConfiguration
import com.relaykit.demo.feature.setup.AuthMode
import com.relaykit.demo.feature.setup.SetupState
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.config.ApiKeyPlacement
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Generates source against the RelayKit API surface that exists in this project. */
object KotlinCodeGenerator {
    fun generate(type: CodeSampleType, setup: SetupState, request: RequestConfiguration, apiKeyPlacement: ApiKeyPlacement = ApiKeyPlacement.HEADER): GeneratedCode = when (type) {
        CodeSampleType.INITIALIZATION -> GeneratedCode(initialization(setup, apiKeyPlacement))
        CodeSampleType.REQUEST -> requestOnly(request, apiKeyPlacement)
        CodeSampleType.COMPLETE -> {
            val generatedRequest = requestFunction(request, apiKeyPlacement)
            GeneratedCode(
                source = imports(initializationImports + requestImports + generatedRequest.imports + if (setup.authMode == AuthMode.API_KEY && apiKeyPlacement == ApiKeyPlacement.QUERY) setOf("com.relaykit.network.config.ApiKeyPlacement") else emptySet()) + "\n\n" +
                    initializationFunction(setup, apiKeyPlacement) + "\n\n" + generatedRequest.source + "\n\n" +
                    "suspend fun runRelayKitExample(context: Context): TransportResponse {\n" +
                    "    val client = initializeRelayKit(context)\n" +
                    "    return executeRelayKitRequest(client)\n" +
                    "}\n" + tokenProvider(setup.authMode == AuthMode.BEARER || request.authChoice == AuthChoice.BEARER),
                warning = generatedRequest.warning,
            )
        }
    }

    private fun initialization(setup: SetupState, apiKeyPlacement: ApiKeyPlacement): String {
        val imports = initializationImports + authImports(setup.authMode) + if (setup.authMode == AuthMode.API_KEY && apiKeyPlacement == ApiKeyPlacement.QUERY) setOf("com.relaykit.network.config.ApiKeyPlacement") else emptySet()
        return imports(imports) + "\n\n" + initializationFunction(setup, apiKeyPlacement) + tokenProvider(setup.authMode == AuthMode.BEARER)
    }

    private fun requestOnly(request: RequestConfiguration, apiKeyPlacement: ApiKeyPlacement): GeneratedCode {
        val generatedRequest = requestFunction(request, apiKeyPlacement)
        return GeneratedCode(imports(requestImports + generatedRequest.imports) + "\n\n" + generatedRequest.source + tokenProvider(request.authChoice == AuthChoice.BEARER), generatedRequest.warning)
    }

    private data class RequestSource(val source: String, val imports: Set<String>, val warning: String?)

    private fun requestFunction(request: RequestConfiguration, apiKeyPlacement: ApiKeyPlacement): RequestSource {
        val method = request.method
        val endpoint = safeUrl(request.endpoint.trim().ifBlank { "YOUR_ENDPOINT" })
        val warnings = mutableListOf<String>()
        val bodyAllowed = method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
        val json = if (request.bodyChoice == BodyChoice.JSON && bodyAllowed) parseJson(request.jsonBody).also { if (it == null) warnings += "The JSON body is empty or invalid, so no body call was generated." } else null
        if (request.bodyChoice != BodyChoice.NONE && !bodyAllowed) warnings += "${method.name} requests cannot carry a body through RelayKit's current request API; the selected body was omitted."
        val emitForm = bodyAllowed && request.bodyChoice == BodyChoice.FORM
        val emitMultipart = bodyAllowed && request.bodyChoice == BodyChoice.MULTIPART
        val imports = linkedSetOf("com.relaykit.network.RelayClient", "com.relaykit.network.request.HttpMethod", "com.relaykit.network.request.TransportResponse")
        if (request.authChoice != AuthChoice.INHERIT && request.authChoice != AuthChoice.NONE) imports += "com.relaykit.network.config.Auth"
        if (request.authChoice == AuthChoice.API_KEY && apiKeyPlacement == ApiKeyPlacement.QUERY) imports += "com.relaykit.network.config.ApiKeyPlacement"
        if (emitMultipart && request.files.isNotEmpty()) {
            imports += "com.relaykit.network.request.RelayFile"
            imports += "java.io.File"
        }
        val output = StringBuilder()
        output.append("suspend fun executeRelayKitRequest(client: RelayClient): TransportResponse {\n")
        output.append("    return try {\n")
        output.append("        val response = client.request(${kotlinString(endpoint)}, HttpMethod.${method.name}) {\n")
        request.query.filter { it.enabled && it.name.isNotBlank() }.forEach { item ->
            val value = safeValue(item.name, item.value)
            output.append("            query(${kotlinString(item.name)}, ${kotlinString(value)})\n")
        }
        request.headers.filter { it.enabled && it.name.isNotBlank() }.forEach { item ->
            val value = safeValue(item.name, item.value)
            output.append("            header(${kotlinString(item.name)}, ${kotlinString(value)})\n")
        }
        appendRequestAuth(output, request, apiKeyPlacement)
        when {
            json != null -> output.append("            body(${jsonExpression(json)})\n")
            emitForm -> output.append("            form(${stringMap(request.bodyFields.filter { it.enabled && it.name.isNotBlank() }.associate { it.name to safeValue(it.name, it.value) })})\n")
            emitMultipart -> {
                output.append("            multipart(${stringMap(request.bodyFields.filter { it.enabled && it.name.isNotBlank() }.associate { it.name to safeValue(it.name, it.value) })})\n")
                request.files.forEachIndexed { index, file ->
                    output.append("            file(RelayFile(name = ${kotlinString(file.name.ifBlank { "file" })}, file = File(${kotlinString("path/to/upload-${index + 1}.bin")}), fileName = ${kotlinString("upload-${index + 1}.bin")}, mimeType = \"application/octet-stream\"))\n")
                }
                if (request.files.isNotEmpty()) warnings += "Multipart file paths are safe placeholders; replace them with real files or content URIs."
            }
        }
        output.append("        }\n")
        output.append("        if (response.isSuccessful) {\n")
        output.append("            println(\"RelayKit request succeeded: HTTP \${response.statusCode}\")\n")
        output.append("        } else {\n")
        output.append("            System.err.println(\"RelayKit request failed: HTTP \${response.statusCode} \${response.body}\")\n")
        output.append("        }\n")
        output.append("        response\n")
        output.append("    } catch (error: Exception) {\n")
        output.append("        System.err.println(\"RelayKit transport error: \${error.message}\")\n")
        output.append("        throw error\n")
        output.append("    }\n")
        output.append("}\n")
        return RequestSource(output.toString(), imports, warnings.distinct().takeIf { it.isNotEmpty() }?.joinToString(" "))
    }

    private fun appendRequestAuth(output: StringBuilder, request: RequestConfiguration, apiKeyPlacement: ApiKeyPlacement) {
        when (request.authChoice) {
            AuthChoice.INHERIT -> Unit
            AuthChoice.NONE -> output.append("            auth(Auth.None)\n")
            AuthChoice.API_KEY -> output.append("            auth(Auth.ApiKey(value = \"YOUR_API_KEY\", name = ${kotlinString(request.authName.ifBlank { "X-API-Key" })}${if (apiKeyPlacement == ApiKeyPlacement.QUERY) ", placement = ApiKeyPlacement.QUERY" else ""}))\n")
            AuthChoice.BEARER -> output.append("            auth(Auth.Bearer { loadBearerToken() })\n")
            AuthChoice.CUSTOM -> output.append("            auth(Auth.Custom(header = ${kotlinString(request.authName.ifBlank { "Authorization" })}, value = \"YOUR_AUTH_VALUE\"))\n")
        }
    }

    private fun initializationFunction(setup: SetupState, apiKeyPlacement: ApiKeyPlacement): String {
        val base = safeBaseUrl(setup.baseUrl)
        val connect = setup.connectSeconds.toLongOrNull()?.coerceIn(1, 300) ?: 15L
        val read = setup.readSeconds.toLongOrNull()?.coerceIn(1, 300) ?: 30L
        val write = setup.writeSeconds.toLongOrNull()?.coerceIn(1, 300) ?: 30L
        val out = StringBuilder("fun initializeRelayKit(context: Context): RelayClient =\n    RelayKit.init(context.applicationContext) {\n")
        out.append("        baseUrl = ${kotlinString(base)}\n")
        out.append("        timeouts {\n            connect = $connect.seconds\n            read = $read.seconds\n            write = $write.seconds\n            call = maxOf(connect, read, write)\n        }\n")
        setup.headers.filter { it.enabled && it.name.isNotBlank() }.forEach { header ->
            out.append("        header(${kotlinString(header.name)}, ${kotlinString(safeValue(header.name, header.value))})\n")
        }
        out.append("        logging(LogLevel.${setup.logLevel.name}, LogSink { message -> println(message) })\n")
        when (setup.authMode) {
            AuthMode.NONE -> out.append("        auth(Auth.None)\n")
            AuthMode.API_KEY -> if (apiKeyPlacement == ApiKeyPlacement.QUERY) out.append("        auth(Auth.ApiKey(value = \"YOUR_API_KEY\", name = \"X-API-Key\", placement = ApiKeyPlacement.QUERY))\n") else out.append("        apiKey(\"YOUR_API_KEY\")\n")
            AuthMode.BEARER -> out.append("        bearer { loadBearerToken() }\n")
        }
        out.append("    }\n")
        return out.toString()
    }

    private fun tokenProvider(required: Boolean): String = if (!required) "" else "\n\nprivate suspend fun loadBearerToken(): String? {\n    // Read the token from your app's secure storage. Do not hardcode it here.\n    return null // TODO: connect your secure token provider\n}\n"

    private fun imports(values: Set<String>): String = values.distinct().sorted().joinToString("\n") { "import $it" }

    private fun stringMap(values: Map<String, String>): String = if (values.isEmpty()) "mapOf()" else values.entries.joinToString(prefix = "mapOf(", postfix = ")") { (key, value) -> "${kotlinString(key)} to ${kotlinString(value)}" }

    private fun parseJson(raw: String): JsonElement? = runCatching {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        if (element == JsonNull) null else redactElement(element)
    }.getOrNull()

    private fun redactElement(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) -> if (RecordRedactor.isSensitive(key)) JsonPrimitive("YOUR_SECRET") else redactElement(child) })
        is JsonArray -> JsonArray(value.map(::redactElement))
        else -> value
    }

    private fun jsonExpression(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonObject -> if (value.isEmpty()) "mapOf()" else value.entries.joinToString(prefix = "mapOf(", postfix = ")") { (key, item) -> "${kotlinString(key)} to ${jsonExpression(item)}" }
        is JsonArray -> value.joinToString(prefix = "listOf(", postfix = ")") { jsonExpression(it) }
        is JsonPrimitive -> if (value.isString) kotlinString(value.content) else value.content
        else -> "null"
    }

    private fun safeValue(name: String, value: String): String = if (RecordRedactor.isSensitive(name)) "YOUR_SECRET" else RecordRedactor.text(value)

    private fun safeBaseUrl(raw: String): String {
        val candidate = raw.trim().ifBlank { "https://api.example.com/" }
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return "https://api.example.com/"
        val withoutUserInfo = runCatching { URI(parsed.scheme, null, parsed.host, parsed.port, parsed.path, parsed.query, parsed.fragment).toString() }.getOrDefault("https://api.example.com/")
        return RecordRedactor.url(withoutUserInfo)
    }

    private fun safeUrl(raw: String): String {
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return raw
        val withoutUserInfo = if (parsed.isAbsolute && parsed.host != null && parsed.userInfo != null) runCatching { URI(parsed.scheme, null, parsed.host, parsed.port, parsed.path, parsed.query, parsed.fragment).toString() }.getOrDefault(raw) else raw
        return RecordRedactor.url(withoutUserInfo)
    }

    /** Escapes Kotlin source string literals, including interpolation and control characters. */
    fun kotlinString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private val initializationImports = setOf(
        "android.content.Context", "com.relaykit.network.RelayClient", "com.relaykit.network.RelayKit",
        "com.relaykit.network.config.Auth", "com.relaykit.network.logging.LogLevel", "com.relaykit.network.logging.LogSink",
        "kotlin.time.Duration.Companion.seconds",
    )
    private val requestImports = setOf("com.relaykit.network.RelayClient", "com.relaykit.network.request.HttpMethod", "com.relaykit.network.request.TransportResponse")
    private fun authImports(mode: AuthMode) = if (mode == AuthMode.NONE) emptySet() else setOf("com.relaykit.network.config.Auth")
}

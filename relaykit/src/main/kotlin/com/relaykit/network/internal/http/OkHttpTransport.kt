package com.relaykit.network.internal.http

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.relaykit.network.RelayClient
import com.relaykit.network.config.Auth
import com.relaykit.network.config.ApiKeyPlacement
import com.relaykit.network.config.RelayConfig
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayRequest
import com.relaykit.network.request.TransportResponse
import com.relaykit.network.request.RelayFile
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.internal.logging.LogRedactor

internal class OkHttpTransport(
    private val owner: RelayClient,
    private val json: Json,
) : HttpTransport {
    private val service: RetrofitService by lazy { createService(owner.config, json) }

    override suspend fun execute(request: RelayRequest): TransportResponse {
        val config = owner.config
        val url = buildUrl(resolveUrl(config.baseUrl, request.endpoint), config.queryParams + request.query)
        val headers = config.headers + request.headers
        val auth = request.auth ?: config.auth
        val finalHeaders = headers.toMutableMap()
        var finalUrl = url
        when (auth) {
            null, Auth.None -> Unit
            is Auth.ApiKey -> if (auth.placement == ApiKeyPlacement.HEADER) finalHeaders[auth.name] = auth.value else finalUrl = buildUrl(finalUrl, mapOf(auth.name to auth.value))
            is Auth.Bearer -> (auth.tokenProvider() ?: owner.token())?.let { finalHeaders["Authorization"] = "Bearer $it" }
            is Auth.Custom -> finalHeaders[auth.header] = auth.value
        }
        val requestBody = when {
            request.files.isNotEmpty() || request.multipart != null -> multipartBody(request)
            request.form != null -> okhttp3.FormBody.Builder().apply { request.form.forEach { (k, v) -> add(k, v) } }.build()
            request.body != null -> encodeBody(request.body).toRequestBody(config.contentType.toMediaType())
            else -> null
        }
        val startedAt = System.nanoTime()
        if (config.loggingLevel != LogLevel.NONE) {
            val safeUrl = redactUrl(finalUrl, config.redactedKeys)
            val headerText = if (config.loggingLevel >= LogLevel.HEADERS) "\nHeaders: ${redactHeaders(finalHeaders, config.redactedKeys)}" else ""
            val bodyText = if (config.loggingLevel == LogLevel.BODY && request.files.isEmpty() && request.multipart == null) "\nBody: ${redactBody(request.body?.let(::encodeBody), config.redactedKeys, config.maxLogBodySize)}" else ""
            config.logSink?.log("REQUEST ${request.method} $safeUrl$headerText$bodyText")
        }
        val result = if (request.files.isNotEmpty() || request.multipart != null) {
            val parts = (requestBody as MultipartBody).parts.map { MultipartBody.Part.create(it.headers, it.body) }
            when (request.method) {
                HttpMethod.POST -> service.multipartPost(finalUrl, finalHeaders, parts)
                HttpMethod.PUT -> service.multipartPut(finalUrl, finalHeaders, parts)
                HttpMethod.PATCH -> service.multipartPatch(finalUrl, finalHeaders, parts)
                else -> throw IllegalArgumentException("Multipart requests support POST, PUT, and PATCH")
            }
        } else when (request.method) {
            HttpMethod.GET -> service.get(finalUrl, finalHeaders)
            HttpMethod.POST -> service.post(finalUrl, finalHeaders, requestBody)
            HttpMethod.PUT -> service.put(finalUrl, finalHeaders, requestBody)
            HttpMethod.PATCH -> service.patch(finalUrl, finalHeaders, requestBody)
            HttpMethod.DELETE -> service.delete(finalUrl, finalHeaders)
        }
        val raw = result.raw()
        val text = (result.body() ?: result.errorBody())?.string().orEmpty()
        if (config.loggingLevel != LogLevel.NONE) {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            val headerText = if (config.loggingLevel >= LogLevel.HEADERS) "\nHeaders: ${redactHeaders(raw.headers.toMultimap().mapValues { it.value.joinToString(",") }, config.redactedKeys)}" else ""
            val bodyText = if (config.loggingLevel == LogLevel.BODY) "\nBody: ${redactBody(text, config.redactedKeys, config.maxLogBodySize)}" else ""
            config.logSink?.log("RESPONSE ${raw.code} ${raw.request.method} ${elapsed}ms$headerText$bodyText")
        }
        return TransportResponse(
            statusCode = raw.code,
            headers = raw.headers.toMultimap().mapValues { it.value.joinToString(",") },
            body = text,
            isSuccessful = raw.isSuccessful,
        )
    }

    private fun createService(config: RelayConfig, json: Json): RetrofitService {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.timeouts.connect.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(config.timeouts.read.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(config.timeouts.write.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(config.timeouts.call.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(config.followRedirects)
            .followSslRedirects(config.followRedirects)
            .build()
        return Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(config.contentType.toMediaType()))
            .build()
            .create(RetrofitService::class.java)
    }

    private fun resolveUrl(base: String, endpoint: String): String =
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) endpoint else base + endpoint.removePrefix("/")

    private fun multipartBody(request: RelayRequest): MultipartBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
        request.multipart.orEmpty().forEach { (name, value) -> addFormDataPart(name, value) }
        request.files.forEach { addFormDataPart(it.name, it.fileName, fileBody(it)) }
    }.build()

    private fun fileBody(file: RelayFile): RequestBody {
        val mediaType = file.mimeType.toMediaType()
        file.file?.let { return it.asRequestBody(mediaType) }
        val uri = file.uri ?: throw FileNotFoundException("File URI is missing")
        val resolver = owner.applicationContext.contentResolver
        return object : RequestBody() {
            override fun contentType() = mediaType
            override fun writeTo(sink: BufferedSink) {
                val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException("Unable to open content URI")
                stream.source().use { source -> sink.writeAll(source) }
            }
        }
    }

    private fun buildUrl(rawUrl: String, params: Map<String, Any?>): String {
        val parsed = rawUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid request URL")
        val builder = parsed.newBuilder()
        params.forEach { (key, value) -> when (value) {
            null -> Unit
            is Iterable<*> -> value.forEach { item -> if (item != null) builder.addQueryParameter(key, item.toString()) }
            is Array<*> -> value.forEach { item -> if (item != null) builder.addQueryParameter(key, item.toString()) }
            else -> builder.setQueryParameter(key, value.toString())
        } }
        return builder.build().toString()
    }

    private fun redactUrl(value: String, keys: Set<String>): String {
        val parsed = value.toHttpUrlOrNull() ?: return value
        val builder = parsed.newBuilder()
        for (index in 0 until parsed.querySize) if (LogRedactor.sensitive(parsed.queryParameterName(index), keys)) builder.setQueryParameter(parsed.queryParameterName(index), "******")
        return builder.build().toString()
    }

    private fun redactHeaders(headers: Map<String, String>, keys: Set<String>): Map<String, String> = headers.mapValues { (key, value) -> if (LogRedactor.sensitive(key, keys)) "******" else value }

    private fun redactBody(body: String?, keys: Set<String>, limit: Int): String {
        if (body == null) return "<empty>"
        val sanitized = runCatching { json.encodeToString(JsonElement.serializer(), LogRedactor.redactJson(json.parseToJsonElement(body), keys)) }.getOrDefault(body)
        return if (sanitized.length > limit) sanitized.take(limit) + "… [truncated]" else sanitized
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeBody(value: Any): String = try {
        json.encodeToString(json.serializersModule.serializer(value::class.java), value)
    } catch (_: IllegalArgumentException) {
        json.encodeToString(JsonElement.serializer(), toJsonElement(value))
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
            require(key is String) { "JSON object keys must be strings" }
            key to toJsonElement(item)
        })
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        is Array<*> -> JsonArray(value.map(::toJsonElement))
        else -> throw IllegalArgumentException("Request body must be a JSON primitive, JsonElement, map, list, or array")
    }
}

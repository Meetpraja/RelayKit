# RelayKit Android Networking Library

RelayKit is a headless Android/Kotlin library for configuring an HTTP client, making requests, and handling raw or parsed responses. This guide documents the **`:relaykit` library APIs only**. The Compose application in this repository is a separate consumer and is not required by your app.

The examples use [JSONPlaceholder](https://jsonplaceholder.typicode.com/) as a public fake API:

- Base URL: `https://jsonplaceholder.typicode.com/`
- Read a post: `GET /posts/1`
- Create a fake post: `POST /posts`

Replace that base URL with your own API when integrating RelayKit into a product.

## Add RelayKit to an Android project

After the RelayKit repository is published to GitHub and its release tag is available through JitPack, add JitPack to your consuming project's repositories. With centralized repository configuration, add it to `settings.gradle.kts`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then depend on the `relaykit` library module using the GitHub owner, repository name, and published Git tag. Replace `YOUR_GITHUB_USERNAME` and `VERSION` with the actual repository owner and release tag:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.YOUR_GITHUB_USERNAME:RelayKit:relaykit:VERSION")
}
```

For example, if the GitHub repository is `exampledev/RelayKit` and the release tag is `1.0.0`, the dependency is:

```kotlin
implementation("com.github.exampledev:RelayKit:relaykit:1.0.0")
```

JitPack builds the library from the matching GitHub tag. The example coordinate is illustrative; use the actual GitHub username and a release tag that has been pushed and successfully built by JitPack. This project also contains a demo app, so the `relaykit` module name is part of the JitPack coordinate.

The consuming Android app must declare internet access in its manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

The library has a minimum SDK of 21. Consumers should use a compatible Android Gradle Plugin and Kotlin toolchain; this repository builds with JDK 17.

## Initialize RelayKit

Initialize the default client once with an application context, typically from your `Application` class or dependency-injection setup. A base URL is required and must end in `/`.

```kotlin
import android.app.Application
import com.relaykit.network.RelayKit
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import android.util.Log
import kotlin.time.Duration.Companion.seconds

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        RelayKit.init(applicationContext) {
            baseUrl = "https://jsonplaceholder.typicode.com/"

            timeouts {
                connect = 15.seconds
                read = 30.seconds
                write = 30.seconds
                call = 60.seconds
            }

            header("Accept", "application/json")

            // BASIC prints request/response summaries to this sink.
            logging(
                level = LogLevel.BASIC,
                sink = LogSink { message -> Log.d("RelayKit", message) },
            )
        }
    }
}
```

`RelayKit.init(...)` creates and installs the process-wide default `RelayClient`. Retrieve it later with:

```kotlin
val client = RelayKit.client()
```

Calling `init` again replaces the default client. For an independently configured client, use `RelayClient.create(context) { ... }` and keep the returned client instead of using the process-wide default.

## Make a GET request

`RelayClient.request` returns a `TransportResponse` containing the HTTP status, headers, raw body text, and `isSuccessful` flag. HTTP errors such as 404 are still responses; network and request errors can throw exceptions from this raw API.

```kotlin
import com.relaykit.network.RelayKit
import com.relaykit.network.request.HttpMethod

suspend fun loadPost(): String {
    val response = RelayKit.client().request(
        endpoint = "posts/1",
        method = HttpMethod.GET,
        headers = mapOf("X-Client-Name" to "MyAndroidApp"),
    )

    return if (response.isSuccessful) {
        response.body
    } else {
        error("HTTP ${response.statusCode}: ${response.body}")
    }
}
```

The request can also be written with RelayKit's request DSL. Query parameters and per-request headers are added there:

```kotlin
val response = RelayKit.client().request("posts/1", HttpMethod.GET) {
    query("include", "comments")
    header("X-Client-Name", "MyAndroidApp")
}

if (response.isSuccessful) {
    println(response.body)
}
```

Both `"posts/1"` and `"/posts/1"` resolve against the configured base URL. An absolute HTTP or HTTPS URL can also be supplied as the endpoint. The map-based request overload accepts query parameters using `query = mapOf("page" to 2)`.

## Make a POST request

For JSON requests, provide a JSON-serializable value such as a map. RelayKit's default content type is `application/json`.

```kotlin
import com.relaykit.network.RelayKit
import com.relaykit.network.request.HttpMethod

suspend fun createPost(): String {
    val response = RelayKit.client().request(
        endpoint = "posts",
        method = HttpMethod.POST,
        body = mapOf(
            "title" to "RelayKit example",
            "body" to "A sample request from an Android app",
            "userId" to 1,
        ),
        headers = mapOf(
            "X-Client-Name" to "MyAndroidApp",
            "X-Request-Source" to "mobile",
        ),
    )

    if (!response.isSuccessful) {
        error("HTTP ${response.statusCode}: ${response.body}")
    }
    return response.body
}
```

The request DSL has equivalent `body(...)`, `header(...)`, and `query(...)` calls. Per-request headers override default headers with the same name. Authentication is applied after headers and can override the corresponding auth header.

RelayKit supports `GET`, `POST`, `PUT`, `PATCH`, and `DELETE`. Its current request API does not accept a body for `GET` or `DELETE`. JSON, form-urlencoded, and multipart bodies are supported with `POST`, `PUT`, and `PATCH`.

## Serialization configuration

RelayKit creates one `Json` instance per client and reuses it for typed response decoding, dynamic JSON parsing, and serializable request bodies. With no custom `Json`, the defaults are `ignoreUnknownKeys = true`, `isLenient = false`, `coerceInputValues = false`, `explicitNulls = false`, and `encodeDefaults = false`. Configure decoding defaults with `serialization(...)`, set request encoding defaults with `encodeDefaults(true)`, or supply a complete kotlinx Serialization `Json` instance with `json(...)`. When a custom instance is supplied, RelayKit uses its settings and `serializersModule` as-is.

For example, register a custom date serializer through the Json serializers module:

```kotlin
import com.relaykit.network.RelayKit
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ApiDateSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ApiDate", PrimitiveKind.STRING)
    private fun formatter() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    override fun serialize(encoder: Encoder, value: Date) = encoder.encodeString(formatter().format(value))
    override fun deserialize(decoder: Decoder): Date {
        val input = decoder.decodeString()
        val position = ParsePosition(0)
        return formatter().parse(input, position)?.takeIf { position.index == input.length }
            ?: throw SerializationException("Invalid API date")
    }
}

@Serializable
data class Invoice(val id: String, @Contextual val dueDate: Date)

RelayKit.init(applicationContext) {
    baseUrl = "https://api.example.com/"
    json(Json {
        serializersModule = SerializersModule {
            contextual(Date::class, ApiDateSerializer)
        }
    })
}
```

For APIs that add fields over time, keep unknown fields ignored (the default), or set the policy explicitly:

```kotlin
RelayKit.init(applicationContext) {
    baseUrl = "https://api.example.com/"
    serialization(ignoreUnknownKeys = true)
}
```

Set `ignoreUnknownKeys = false` to make an unknown response field a parsing failure. Missing fields with model defaults and nullable fields follow Kotlin Serialization behavior. Invalid JSON, incompatible values, and custom serializer exceptions return `RelayResult.Failure` with `ErrorType.PARSING`; cancellation during decoding propagates. A supplied `Json` instance is retained as client configuration and must not be mutated after initialization. One configured instance is shared across concurrent requests, so custom serializers should be stateless or thread safe.

## Do I need a model class?

No. A model is optional. Choose the response style that fits your code:

1. **Raw text:** `client.request(...)` returns `TransportResponse.body` as a string. Parse it yourself if needed.
2. **Dynamic JSON:** `client.call<RelayJson>(...)` returns a safe JSON wrapper without defining an app model.
3. **Typed data:** `client.call<MyModel>(...)` deserializes into a serializable Kotlin model.

### Dynamic JSON without a model

```kotlin
import com.relaykit.network.RelayKit
import com.relaykit.network.json.RelayJson
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.result.RelayResult

suspend fun loadPostTitle(): String? {
    return when (val result = RelayKit.client().call<RelayJson>("posts/1", HttpMethod.GET)) {
        is RelayResult.Success -> result.data["title"].asStringOrNull()
        is RelayResult.Failure -> {
            println("${result.error.type}: ${result.error.message}")
            null
        }
    }
}
```

`RelayJson` supports safe object/array access with `get`, `has`, `toMap`, `toList`, and typed accessors such as `asStringOrNull()` and `asIntOrNull()`.

### Typed model

To use `call<T>` with your own response model, add the Kotlin serialization plugin and runtime to the consuming app. Use a serialization plugin version matching your Kotlin plugin version; these versions match this repository's current Kotlin setup:

```kotlin
// app/build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
```

Define a serializable data class and call `call<Post>(...)`:

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class Post(
    val userId: Int,
    val id: Int? = null,
    val title: String,
    val body: String,
)
```

```kotlin
import com.relaykit.network.RelayKit
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.result.RelayResult

suspend fun loadPost(): Post? {
    return when (val result = RelayKit.client().call<Post>("posts/1", HttpMethod.GET)) {
        is RelayResult.Success -> result.data
        is RelayResult.Failure -> {
            println("${result.error.type}: ${result.error.message}")
            println("HTTP status: ${result.statusCode}")
            null
        }
    }
}
```

The `call<T>` API returns a `RelayResult<T>` rather than throwing for HTTP, network, timeout, or parsing failures. Inspect `Success` and `Failure`, or use `isSuccess`, `statusCode`, `errorType`, `errorOrNull`, and `dataOrNull`. `call<Unit>(...)` is available for successful calls whose response data is not needed.

### Handle structured failures

`RelayResult.Failure.error.type` is an `ErrorType`: `HTTP` includes the server status, `NETWORK` represents connectivity failures, `TIMEOUT` covers connect/read/write/call timeouts, `PARSING` covers invalid or incompatible response data, and `UNKNOWN` is reserved for unexpected failures. Cancellation is not returned as a failure; a cancelled coroutine receives `CancellationException`.

```kotlin
import com.relaykit.network.error.ErrorType

when (val result = client.call<Post>("posts/1", HttpMethod.GET)) {
    is RelayResult.Success -> show(result.data)
    is RelayResult.Failure -> when (result.error.type) {
        ErrorType.HTTP -> showHttpError(result.statusCode, result.error.message, result.rawBody)
        ErrorType.NETWORK -> showOfflineMessage()
        ErrorType.TIMEOUT -> showRetryOption()
        ErrorType.PARSING -> reportUnexpectedResponse(result.error.cause)
        ErrorType.UNKNOWN -> reportUnexpectedFailure(result.error.cause)
        ErrorType.CANCELLED -> Unit // Cancellation is propagated and does not produce Failure.
    }
}
```

`Failure.statusCode` and `Failure.json` expose HTTP status and safely parsed JSON details when available. `Failure.rawBody` retains up to 16 KiB of an error or parsing response body. `Failure.error.cause` provides the original exception for network, timeout, parsing, and unknown failures; its message is not copied into RelayKit's user-facing fallback message.

The raw `client.request(...)` API has a different contract: non-2xx HTTP responses return normally as `TransportResponse` with `isSuccessful == false`, status, headers, and body. Connectivity and other execution exceptions are thrown. The typed `client.call<T>(...)` API converts HTTP, network, timeout, and parsing outcomes into `RelayResult.Failure`. Both APIs are suspending, so coroutine cancellation propagates and cancels an in-flight Retrofit/OkHttp call.

## Response body ownership

RelayKit consumes each OkHttp response body once and closes its source before returning. This applies to successful responses, HTTP error responses, parsing failures, and cancellation. `call<T>(...)` owns that lifecycle and returns parsed data or a `RelayResult`; callers do not receive a live response stream. A `Unit` call accepts an empty successful response, including HTTP 204. Other typed and dynamic JSON calls report an empty successful body as a parsing failure. The raw API returns an empty string for an empty body and preserves status and headers.

`TransportResponse.body` is a fully buffered `String`, so the raw API does not hold an open network resource after it returns. Successful and raw response bodies are not size-limited to preserve existing behavior; large responses therefore require memory proportional to their body size. Failure bodies retained by `RelayResult` are capped at 16 KiB. Typed JSON responses are parsed once into the JSON tree used by both the typed decoder and `RelayResult.json`.

## OkHttp interceptors

Register standard OkHttp application interceptors in the RelayKit configuration block. They are attached once to the shared OkHttp client used by both `request(...)` and `call<T>(...)`:

```kotlin
import com.relaykit.network.RelayKit
import okhttp3.Interceptor
import okhttp3.Response

class TenantHeaderInterceptor(private val tenantId: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Tenant-ID", tenantId)
            .build()
        return chain.proceed(request)
    }
}

RelayKit.init(applicationContext) {
    baseUrl = "https://api.example.com/"
    interceptor(TenantHeaderInterceptor("tenant-42"))
    interceptor(BearerHeaderInterceptor { secureTokenStore.currentToken })
}
```

```kotlin
class BearerHeaderInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = chain.request().newBuilder().apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        val response = chain.proceed(request)
        // Inspect response.code or response.header("X-Request-ID") here as needed.
        return response
    }
}
```

Interceptors run in registration order on the request path and unwind in reverse order on the response path. They can inspect or modify the OkHttp request before `chain.proceed(request)` and inspect the returned response without consuming its body. RelayKit then consumes and closes the body as described above.

RelayKit reuses each registered interceptor instance for concurrent requests. Treat interceptor fields as shared state: use immutable values or thread-safe storage, and keep request-specific values on the request/response objects. Interceptors are synchronous and cannot be forcibly interrupted while executing arbitrary blocking code; cancellation propagates through `chain.proceed(...)` and cancels its underlying call. `IOException` from an interceptor follows the normal network-error contract (thrown by raw `request`, represented as `NETWORK` by typed `call<T>`); other runtime exceptions become `UNKNOWN` failures for typed calls. `CancellationException` propagates. Transient retries are disabled by default and can be configured as described below; bearer refresh is a separate optional feature.

For bearer authentication that changes over time, use a thread-safe, non-suspending token provider inside an interceptor. The existing `bearer { ... }` configuration remains the supported option when token lookup itself needs to suspend.

## Configure headers and timeouts

Configure default headers once, then add or override headers on a request:

```kotlin
RelayKit.init(applicationContext) {
    baseUrl = "https://jsonplaceholder.typicode.com/"
    header("Accept", "application/json")
    header("X-App-Version", "1.0")
    timeouts {
        connect = 10.seconds
        read = 20.seconds
        write = 20.seconds
        call = 30.seconds
    }
}
```

The timeout DSL uses Kotlin `Duration` values. `connect`, `read`, and `write` default to 15, 30, and 30 seconds; the overall call timeout defaults to 60 seconds.

## Authentication

RelayKit supports no auth, API keys, bearer tokens, and custom auth headers. Never commit real credentials in source code. Load them from your app's secure storage or another protected provider.

```kotlin
import com.relaykit.network.config.Auth
import com.relaykit.network.config.ApiKeyPlacement
import com.relaykit.network.request.HttpMethod

// Default API-key header: X-API-Key
RelayKit.init(applicationContext) {
    baseUrl = "https://api.example.com/"
    apiKey("YOUR_API_KEY")
}

// Bearer token supplied when a request is made.
RelayKit.init(applicationContext) {
    baseUrl = "https://api.example.com/"
    bearer { secureTokenStore.readAccessToken() }
}

// Or update the client's in-memory token without recreating it.
val client = RelayKit.client()
client.setToken(secureTokenStore.readAccessToken())
// Call client.setToken(newAccessToken) after login or token rotation.

// Custom auth header or a query-parameter API key.
val client = RelayKit.client()
val response = client.request("profile", HttpMethod.GET) {
    auth(Auth.Custom(header = "Authorization", value = "YOUR_AUTH_VALUE"))
    // Alternatively:
    // auth(Auth.ApiKey(value = "YOUR_API_KEY", name = "api_key", placement = ApiKeyPlacement.QUERY))
}
```

The bearer provider is read for each request, so it can return the latest token; providers may be called concurrently and must be thread safe. The no-argument `bearer()` reads the in-memory token updated by `RelayKit.setToken(...)` or `client.setToken(...)`. With `bearer { ... }`, an explicitly set in-memory token takes precedence over the provider until it is changed again. When automatic bearer auth is active, an explicitly supplied `Authorization` request/default header takes precedence and is sent unchanged. Per-request `auth(...)` overrides the configured auth. Use `Auth.None` to explicitly disable inherited authentication for one request.

Optional refresh runs only after a 401 from a request carrying an automatically supplied bearer token. Register a suspend callback that returns the replacement access token, or `null` if refresh failed:

```kotlin
RelayKit.init(applicationContext) {
    baseUrl = "https://api.example.com/"
    bearer()
    refreshBearerToken { expiredAccessToken ->
        // Call your separate refresh service here; return its new access token.
        authSession.refreshAccessToken(expiredAccessToken)
    }
}
```

`authSession` is application-owned. Its refresh call must use a separate unauthenticated service/client, not this `RelayClient`; RelayKit detects and rejects same-client re-entry to prevent recursive refresh. Concurrent 401 responses for the same token share one suspendable refresh operation. A successful refresh updates the client's in-memory token and retries each waiting original request at most once. A null, blank, or failed refresh returns the original 401 without retrying; 403 never refreshes. Cancellation during token lookup or refresh propagates. Refresh callbacks and token providers can be invoked concurrently with ordinary requests, so their shared state must be thread safe. RelayKit does not log token values or place them in events/errors; Authorization headers are redacted from network logs. RelayKit suppresses its own network logs for RelayClient calls made from inside a refresh callback. Logging performed directly by a third-party client or interceptor remains application controlled.

## Retry policy

Transient retries are disabled by default to preserve the existing one-request behavior. Enable them in the config DSL:

```kotlin
import com.relaykit.network.RelayKit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

RelayKit.init(applicationContext) {
    baseUrl = "https://api.example.com/"
    retry {
        enabled = true
        maxRetries = 2 // two retries after the initial attempt; up to three attempts total
        initialDelay = 250.milliseconds
        backoffMultiplier = 2.0
        maxDelay = 2.seconds
        jitterFactor = 0.2 // symmetric ±20% jitter
        retryOnStatusCodes(408, 429, 500, 502, 503, 504)
        retryNetworkFailures = true
    }
}
```

The default policy values, when enabled, use two retries, 250 ms initial delay, a 2x multiplier, 2 s maximum delay, 20% jitter, network/timeout retries, and status codes 408, 429, 500, 502, 503, and 504. `Retry-After` seconds or HTTP dates are honored for retryable responses and capped by `maxDelay`. Delays use cancellable coroutine suspension. Invalid request configuration and serialization/parsing failures are outside the retry loop. HTTP 401 is reserved for bearer refresh and cannot be added to transient retry codes.

GET is the only method retried by default. PUT, POST, PATCH, and DELETE require `retryNonIdempotentRequests = true`, which explicitly opts into replay risk. For a POST, pair that opt-in with an idempotency key supported by the server:

```kotlin
import com.relaykit.network.RelayKit
import com.relaykit.network.request.HttpMethod

val response = RelayKit.client().request("payments", HttpMethod.POST) {
    header("Idempotency-Key", paymentOperationId)
    body(paymentPayload)
}
```

JSON, form, and multipart field bodies are rebuilt for each attempt. Java `File` attachments are reopened and replayable while the file remains stable; provider-backed content URI attachments are never retried because they may be one-shot. Keep model bodies and file contents stable while a request is in flight. RelayKit disables OkHttp's implicit connection retry so `maxRetries` is the single transient retry limit. Each attempt invokes configured application interceptors again, so interceptor side effects must account for retries. Lifecycle events are emitted once per logical request, not once per attempt. A 401 refresh may make one internal authentication retry; that retry is separate from `maxRetries`, and any subsequent transient failure uses the configured policy.

## Streaming downloads

Use `RelayClient.download` to stream a GET response directly to a destination file. The destination must not already exist; RelayKit preserves it by writing to a sibling `.part` file and moving that file into place only after a successful response. A non-resumable failed or cancelled download removes its partial file. HTTP errors return a `DownloadResponse` with `isSuccessful == false`, status and headers; their bodies are closed without being copied to disk or buffered as text.

```kotlin
import com.relaykit.network.RelayKit
import com.relaykit.network.request.DownloadProgress
import java.io.File

val result = RelayKit.client().download(
    endpoint = "exports/archive.zip",
    destination = File(context.cacheDir, "archive.zip"),
    headers = mapOf("X-Request-Source" to "android"),
)
if (result.isSuccessful) {
    useDownloadedFile(result.destination)
}

val withProgress = RelayKit.client().download(
    endpoint = "exports/report.pdf",
    destination = File(context.cacheDir, "report.pdf"),
    onProgress = { progress: DownloadProgress ->
        renderProgress(progress.downloadedBytes, progress.totalBytes, progress.percent)
    },
)
```

Response bytes are copied to disk in a fixed 32 KiB buffer. Progress reports aggregate file bytes, total length when known, and a percentage when the total is positive. Notifications are throttled to 64 KiB increments plus the final update and run asynchronously on a RelayKit background thread. A slow callback can coalesce intermediate updates; callback exceptions are ignored. Keep callbacks short and protect shared state when multiple downloads are active. RelayKit does not log response contents or emit a log line for each chunk.

Cancellation cancels the active OkHttp call, closes the response and file streams, and propagates `CancellationException`. A provider is not involved in downloads. With `resume = false`, partial data is removed after cancellation or failure. Enable resume to retain a sibling `.part` file and send a `Range: bytes=<offset>-` request on the next attempt:

```kotlin
val resumed = RelayKit.client().download(
    endpoint = "exports/archive.zip",
    destination = File(context.cacheDir, "archive.zip"),
    resume = true,
    onProgress = { progress -> renderProgress(progress.downloadedBytes, progress.totalBytes, progress.percent) },
)
```

Resume requires a server that honors byte ranges with a valid `206 Partial Content` and `Content-Range` starting at the existing partial length. Do not modify the `.part` file while a resumed request is active. A `200 OK` means the server ignored Range, so RelayKit discards the old partial and starts over. `416 Range Not Satisfiable` and malformed or mismatched `Content-Range` return unsuccessful results and preserve the partial file for inspection or another attempt. When enabled, transient network retries use the partial file's current length and validate each returned range before appending; cancellation is never retried. HTTP 401 refresh is handled before any response bytes are written, and the authenticated retry uses the same resume offset. Download requests use the configured headers, authentication, interceptors, timeout settings, logging redaction, and retry policy. The API currently supports GET only and rejects a pre-existing destination rather than overwriting it.

## Form and multipart requests

```kotlin
import com.relaykit.network.RelayKit
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayFile
import java.io.File

// Assume `context` is an Android Context and RelayKit was initialized above.
// Form-urlencoded body
val formResponse = RelayKit.client().request("posts", HttpMethod.POST) {
    form(mapOf("title" to "A form post", "userId" to "1"))
}

// Multipart text fields and a file from app-owned storage
val uploadResponse = RelayKit.client().request("upload", HttpMethod.POST) {
    multipart(mapOf("caption" to "profile image"))
    file(
        RelayFile(
            name = "file",
            file = File(context.cacheDir, "upload.png"),
            fileName = "upload.png",
            mimeType = "image/png",
        ),
    )
}

// Multiple files, custom part metadata, and aggregate upload progress
val multiUpload = RelayKit.client().request("documents", HttpMethod.POST) {
    multipart(mapOf("category" to "reports", "note" to "quarterly export"))
    file(RelayFile(name = "primary", file = File(context.cacheDir, "report.pdf"), fileName = "report.pdf", mimeType = "application/pdf"))
    file(RelayFile(name = "preview", file = File(context.cacheDir, "preview.png"), fileName = "preview.png", mimeType = "image/png"))
    onUploadProgress { bytesWritten, totalBytes ->
        // Called asynchronously on a RelayKit background callback thread.
        updateUploadProgress(bytesWritten, totalBytes)
    }
}

// Typed response calls accept the same optional callback parameter.
val typedUpload = RelayKit.client().call<UploadReceipt>(
    "documents", HttpMethod.POST,
    files = listOf(RelayFile("file", File(context.cacheDir, "report.pdf"), mimeType = "application/pdf")),
    onUploadProgress = { bytesWritten, totalBytes -> updateUploadProgress(bytesWritten, totalBytes) },
)
```

`File` contents are streamed from disk in bounded chunks; RelayKit does not read the whole upload into a byte array. Content `Uri` sources are opened through the app `ContentResolver` and their streams are closed after each write. The supported upload sources are `File` and content `Uri`; RelayKit does not accept caller-owned `InputStream`s. A content URI can be provider-backed or one-shot, so it is not retried after transient failures or authentication refresh. File-backed bodies are reopened for each allowed retry, but the underlying file must remain stable while the request is in flight.

The optional progress callback reports aggregate multipart body bytes (including boundaries and text fields). `totalBytes` is null when the source length is unknown. Notifications are throttled to roughly 64 KiB increments plus a final update and are queued asynchronously on a dedicated daemon background thread, so callback work does not run on the network writer. A slow callback may cause intermediate updates to be coalesced; the latest queued update is retained. Callback exceptions are ignored. Notifications from a retry start again from zero; queued notifications may arrive just after the response completes. Keep callback work short and thread safe if it shares state with other uploads. Cancellation cancels the OkHttp call and closes active file/URI streams when the transport unwinds; a provider blocked inside its own read may delay cancellation.

A multipart request must use `POST`, `PUT`, or `PATCH`. Do not combine JSON, form, and multipart bodies in a single request. Multipart BODY logs contain part metadata only (field names, filenames, MIME types, and known file lengths); text values and file contents are never logged. Logging does not buffer the upload.

## Logging during development

Logging is disabled by default. Configure a level and a `LogSink` to send output somewhere useful, such as Android Logcat:

```kotlin
import android.util.Log
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink

logging(
    level = LogLevel.HEADERS,
    sink = LogSink { message -> Log.d("RelayKit", message) },
)
redactHeaders("X-Partner-Secret")
redactQueryParameters("session-id")
```

Available levels:

| Level | Output |
| --- | --- |
| `NONE` | No transport log messages |
| `BASIC` | Method, sanitized URL, response status, duration, request ID, and attempt number |
| `HEADERS` | Summaries plus request and response headers |
| `BODY` | Headers and safely redacted JSON request/response bodies |

Sensitive headers are matched case-insensitively. Defaults include Authorization, Proxy-Authorization, Cookie, Set-Cookie, X-API-Key, and API-Key. Sensitive query names are also matched case-insensitively, including token, access_token, refresh_token, api_key, apikey, password, secret, and client_secret. URL user-info is masked. Add application-specific names with `redactHeaders(...)` and `redactQueryParameters(...)`.

For BODY logs, RelayKit redacts common JSON fields such as password, token, accessToken, refreshToken, clientSecret, card number, CVV/CVC, and security-code fields, including inside nested objects and arrays. Add custom JSON field names with `redact("session_id", "private_value")`. Only JSON objects and arrays are logged; malformed JSON, scalar JSON, form bodies, multipart bodies, and unsupported formats are omitted. If a body exceeds `maxLogBodySize` (8 KiB by default), it is omitted with a reason marker. The same limit applies to request and response log output and is separate from response retention. The raw request and response data are not changed. Pretty printing is available with `prettyPrintJsonBodies()`.

```kotlin
logging(
    level = LogLevel.BODY,
    sink = LogSink { message -> Log.d("RelayKit", message) },
    maxBodySize = 4096,
)
prettyPrintJsonBodies()
redact("private_value", "session_id")
redactHeaders("X-Partner-Secret")
redactQueryParameters("session-id")
```

Logs are emitted for each HTTP attempt. Retry attempts include their attempt number; authentication refresh attempts include `auth_refresh=true`. A logical request still produces one lifecycle event sequence. Requests made through RelayClient from inside a token-refresh callback are not logged by RelayKit. A configured sink may be invoked concurrently and must be thread safe. Sink and formatting exceptions are isolated and do not fail requests. BODY logging is explicit opt-in; prefer NONE, BASIC, or HEADERS in production and do not log sensitive application data. RelayKit formats only the request/response body already used by its transport and never consumes a second response copy for logging.

## Events and request DSL metadata

The public `RelayKit.events` / `RelayClient.events` streams expose `Started`, `Succeeded`, and `Failed` lifecycle events. You can also configure an `events(listener = ...)` callback in the initialization block. To associate events with a caller-defined ID, use the request DSL's `tag("...")` and `extra("key", value)` methods; extras are metadata and are not sent to the server.

```kotlin
val response = RelayKit.client().request("posts/1", HttpMethod.GET) {
    tag("profile-screen")
    extra("traceId", "local-debug-42")
}
```

## Quick reference

- Use `request(...)` when you want the raw `TransportResponse` and are comfortable handling thrown transport/configuration exceptions.
- Use `call<T>(...)` when you want a `RelayResult<T>` with typed parsing and structured error information.
- Use `call<RelayJson>(...)` when you want parsed JSON but do not want to define model classes.
- Use `GET` and `DELETE` without request bodies; use `POST`, `PUT`, or `PATCH` for JSON, form, and multipart bodies.
- Configure `INTERNET` permission in the consuming app manifest.

## License

No license has been specified for this project yet. Add the chosen license text in a root-level `LICENSE` file before distributing RelayKit so users know the terms for using and modifying it.

---

Made with ❤️ by **Mit Prajapati**.

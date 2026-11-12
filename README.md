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

// Custom auth header or a query-parameter API key.
val client = RelayKit.client()
val response = client.request("profile", HttpMethod.GET) {
    auth(Auth.Custom(header = "Authorization", value = "YOUR_AUTH_VALUE"))
    // Alternatively:
    // auth(Auth.ApiKey(value = "YOUR_API_KEY", name = "api_key", placement = ApiKeyPlacement.QUERY))
}
```

Per-request `auth(...)` overrides the configured auth. Use `Auth.None` to explicitly disable inherited authentication for one request. `RelayKit.setToken(...)` and `client.setToken(...)` replace the in-memory token used by the no-argument `bearer()` configuration.

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
```

For provider-backed files, pass a content `Uri` to `RelayFile(uri = ...)` instead of a `File`. A multipart request must use `POST`, `PUT`, or `PATCH`. Do not combine JSON, form, and multipart bodies in a single request.

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
```

Available levels:

| Level | Output |
| --- | --- |
| `NONE` | No transport log messages |
| `BASIC` | Request and response summaries |
| `HEADERS` | Summaries plus request and response headers |
| `BODY` | Headers and eligible request/response bodies |

For body logging during local development, set `level = LogLevel.BODY`. The library redacts common credential keys before sending messages to the sink and limits logged body size (8 KiB by default). Add application-specific sensitive field names with `redact("session_id", "private_value")`. Multipart request bodies are not printed. Keep detailed body logging out of production unless you have reviewed the data being logged.

### Pretty-print JSON bodies in Logcat

RelayKit's sink receives readable request/response messages; JSON bodies may be compact. You can format the body portion in your Android log sink with `org.json`:

```kotlin
import android.util.Log
import com.relaykit.network.logging.LogSink
import org.json.JSONArray
import org.json.JSONObject

private fun prettyRelayMessage(message: String): String {
    val marker = "\nBody: "
    val bodyStart = message.indexOf(marker)
    if (bodyStart < 0) return message

    val prefix = message.substring(0, bodyStart)
    val body = message.substring(bodyStart + marker.length)
    val prettyBody = runCatching { JSONObject(body).toString(2) }
        .recoverCatching { JSONArray(body).toString(2) }
        .getOrDefault(body) // Non-JSON or truncated body: keep the original text.
    return "$prefix\nBody:\n$prettyBody"
}

val logSink = LogSink { message ->
    Log.d("RelayKit", prettyRelayMessage(message))
}
```

Pass `logSink` to `logging(level = LogLevel.BODY, sink = logSink, maxBodySize = 4096)` during local development. The formatter only changes display; it does not bypass RelayKit's redaction.

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

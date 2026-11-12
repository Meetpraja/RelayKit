package com.relaykit.demo.feature.codegenerator

import android.content.Context
import com.relaykit.network.RelayClient
import com.relaykit.network.RelayKit
import com.relaykit.network.config.Auth
import com.relaykit.network.config.ApiKeyPlacement
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.logging.LogSink
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.request.RelayFile
import java.io.File
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Compile-time contract: every DSL entry emitted by the generator is checked against public RelayKit API. */
class RelayKitCodegenApiContractTest {
    @Suppress("UNUSED_VARIABLE")
    private fun configurationApiCompiles(context: Context) {
        val client = RelayKit.init(context.applicationContext) {
            baseUrl = "https://api.example.com/"
            timeouts { connect = 10.seconds; read = 20.seconds; write = 30.seconds; call = maxOf(connect, read, write) }
            header("Accept", "application/json")
            apiKey("YOUR_API_KEY")
            bearer { null }
            customAuth("Authorization", "YOUR_AUTH_VALUE")
            auth(Auth.None)
            logging(LogLevel.BASIC, LogSink { message -> println(message) })
        }
        check(client.config.baseUrl.isNotBlank())
    }

    @Suppress("UNUSED_VARIABLE")
    private suspend fun requestApiCompiles(client: RelayClient) {
        val get = client.request("items", HttpMethod.GET) { query("page", "1"); header("Accept", "application/json") }
        val post = client.request("items", HttpMethod.POST) { body(mapOf("name" to "item")); auth(Auth.ApiKey("YOUR_API_KEY", name = "api_key", placement = ApiKeyPlacement.QUERY)) }
        val put = client.request("items/1", HttpMethod.PUT) { form(mapOf("name" to "item")) }
        val patch = client.request("items/1", HttpMethod.PATCH) { multipart(mapOf("caption" to "item")); file(RelayFile(name = "file", file = File("path/to/upload.bin"))) }
        val delete = client.request("items/1", HttpMethod.DELETE) { auth(Auth.Custom("Authorization", "YOUR_AUTH_VALUE")) }
        if (post.isSuccessful) println("HTTP ${post.statusCode}") else System.err.println(post.body)
        listOf(get, post, put, patch, delete)
    }

    @Test fun publicApiContractFixtureIsPresent() {
        assertNotNull(::configurationApiCompiles)
        assertNotNull(::requestApiCompiles)
    }
}

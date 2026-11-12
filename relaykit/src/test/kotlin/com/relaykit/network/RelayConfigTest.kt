package com.relaykit.network

import com.relaykit.network.config.Auth
import com.relaykit.network.config.RelayConfigBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayConfigTest {
    @Test
    fun defaultsAndOverridesAreCapturedInImmutableConfig() {
        val config = RelayConfigBuilder().apply {
            baseUrl = "https://api.example.com/v1/"
            header("Accept-Language", "en")
            query("platform", "android")
            timeouts { connect = kotlin.time.Duration.parse("5s") }
            serialization(ignoreUnknownKeys = false, explicitNulls = true)
        }.build()

        assertEquals("https://api.example.com/v1/", config.baseUrl)
        assertEquals(mapOf("Accept-Language" to "en"), config.headers)
        assertEquals(mapOf("platform" to "android"), config.queryParams)
        assertEquals(kotlin.time.Duration.parse("5s"), config.timeouts.connect)
        assertFalse(config.ignoreUnknownKeys)
        assertTrue(config.explicitNulls)
    }

    @Test
    fun rejectsInvalidBaseUrls() {
        listOf("", "api.example.com", "ftp://api.example.com/", "https:///path", "https://api.example.com/v1").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                RelayConfigBuilder().apply { baseUrl = url }.build()
            }
        }
    }

    @Test
    fun supportsAuthenticationOptions() {
        val keyConfig = RelayConfigBuilder().apply {
            baseUrl = "https://api.example.com/"
            apiKey("secret", "X-Key")
        }.build()
        assertEquals(Auth.ApiKey("secret", "X-Key"), keyConfig.auth)

        val noAuthConfig = RelayConfigBuilder().apply {
            baseUrl = "https://api.example.com/"
            auth(Auth.None)
        }.build()
        assertEquals(Auth.None, noAuthConfig.auth)
        assertNull(RelayConfigBuilder().apply { baseUrl = "https://api.example.com/" }.build().auth)
    }
}

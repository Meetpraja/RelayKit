package com.relaykit.network

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito

class RelayKitInitializationTest {
    @After
    fun reset() = RelayKit.resetForTesting()

    @Test
    fun accessingDefaultClientBeforeInitializationThrowsHelpfulError() {
        val error = assertThrows(IllegalStateException::class.java) { RelayKit.client() }
        assertEquals(true, error.message?.contains("RelayKit.init(context)") == true)
    }

    @Test
    fun initializationAndReplacementUseApplicationContext() {
        val context = Mockito.mock(Context::class.java)
        val appContext = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(appContext)
        Mockito.`when`(appContext.applicationContext).thenReturn(appContext)

        val first = RelayKit.init(context) { baseUrl = "https://one.example.com/" }
        val second = RelayKit.init(context) { baseUrl = "https://two.example.com/" }

        assertSame(second, RelayKit.client())
        assertNotSame(first, second)
        assertSame(appContext, second.applicationContext)
        assertEquals("https://two.example.com/", second.config.baseUrl)
    }

    @Test
    fun defaultTokenCanBeSetAndCleared() {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        val client = RelayKit.init(context) {
            baseUrl = "https://api.example.com/"
            bearer()
        }

        RelayKit.setToken("token-value")
        assertEquals("token-value", client.token())
        RelayKit.setToken(null)
        assertNull(client.token())
    }

    @Test
    fun namedClientsKeepIndependentConfigurationsAndTokens() {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        val first = RelayClient.create(context) { baseUrl = "https://one.example.com/" }
        val second = RelayClient.create(context) { baseUrl = "https://two.example.com/" }

        first.setToken("first-token")
        second.setToken("second-token")

        assertEquals("https://one.example.com/", first.config.baseUrl)
        assertEquals("https://two.example.com/", second.config.baseUrl)
        assertEquals("first-token", first.token())
        assertEquals("second-token", second.token())
    }
}

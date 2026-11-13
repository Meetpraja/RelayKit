package com.relaykit.network

import android.content.Context
import com.relaykit.network.error.ErrorType
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.result.RelayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RelaySerializationTest {
    @Serializable private data class Basic(val value: Int)
    @Serializable private data class Defaults(val label: String = "default", val optional: String? = null)
    @Serializable private data class Outgoing(val name: String, val version: Int = 7, val optional: String? = null)
    private enum class ApiStatus { ACTIVE, INACTIVE }
    @Serializable(with = AccountIdSerializer::class) @JvmInline private value class AccountId(val value: String)
    @Serializable private data class CustomTypes(
        @Contextual val date: LocalDate,
        @Contextual val status: ApiStatus,
        val account: AccountId,
    )
    @Serializable private data class BrokenPayload(@Contextual val value: Broken)
    private data class Broken(val value: String)

    private object DateSerializer : KSerializer<LocalDate> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ApiDate", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
        override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
    }
    private object StatusSerializer : KSerializer<ApiStatus> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ApiStatus", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: ApiStatus) = encoder.encodeString(if (value == ApiStatus.ACTIVE) "A" else "I")
        override fun deserialize(decoder: Decoder): ApiStatus = when (decoder.decodeString()) {
            "A" -> ApiStatus.ACTIVE
            "I" -> ApiStatus.INACTIVE
            else -> throw SerializationException("Unknown status code")
        }
    }
    private object AccountIdSerializer : KSerializer<AccountId> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AccountId", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: AccountId) = encoder.encodeString(value.value)
        override fun deserialize(decoder: Decoder): AccountId = AccountId(decoder.decodeString())
    }
    private object BrokenSerializer : KSerializer<Broken> {
        override val descriptor: SerialDescriptor = String.serializer().descriptor
        override fun serialize(encoder: Encoder, value: Broken) = encoder.encodeString(value.value)
        override fun deserialize(decoder: Decoder): Broken = throw IllegalArgumentException("custom decode rejected value")
    }

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
    }
    @After fun tearDown() = server.shutdown()
    private fun client(block: com.relaykit.network.config.RelayConfigBuilder.() -> Unit = {}) =
        RelayClient.create(context) { baseUrl = server.url("api/").toString(); block() }

    @Test fun defaultJsonConfigurationIsBackwardCompatible() {
        val relay = client()
        assertTrue(relay.json.configuration.ignoreUnknownKeys)
        assertFalse(relay.json.configuration.isLenient)
        assertFalse(relay.json.configuration.coerceInputValues)
        assertFalse(relay.json.configuration.explicitNulls)
        assertFalse(relay.json.configuration.encodeDefaults)
    }

    @Test fun suppliedJsonInstanceIsUsedUnchanged() = runBlocking {
        val supplied = Json { ignoreUnknownKeys = false; isLenient = true; coerceInputValues = true; explicitNulls = true; encodeDefaults = true }
        val relay = client { json(supplied) }
        assertSame(supplied, relay.json)
        server.enqueue(MockResponse().setBody("{\"value\":4}"))
        val result = relay.call<Basic>("custom-json", HttpMethod.GET)
        assertEquals(4, (result as RelayResult.Success).data.value)
    }

    @Test fun unknownKeyPolicyCanBeEnabledOrDisabled() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"value\":3,\"extra\":true}"))
        val accepted = client { serialization(ignoreUnknownKeys = true) }.call<Basic>("accept", HttpMethod.GET)
        assertEquals(3, (accepted as RelayResult.Success).data.value)
        server.enqueue(MockResponse().setBody("{\"value\":3,\"extra\":true}"))
        val rejected = client { serialization(ignoreUnknownKeys = false) }.call<Basic>("reject", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, rejected.errorType)
    }

    @Test fun missingDefaultsAndNullableFieldsFollowKotlinSerialization() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val missing = client().call<Defaults>("missing", HttpMethod.GET) as RelayResult.Success
        assertEquals(Defaults(), missing.data)
        server.enqueue(MockResponse().setBody("{\"label\":\"set\",\"optional\":null}"))
        val explicitNull = client().call<Defaults>("null", HttpMethod.GET) as RelayResult.Success
        assertEquals("set", explicitNull.data.label)
        assertNull(explicitNull.data.optional)
    }

    @Test fun customDateEnumAndValueClassSerializersAreUsed() = runBlocking {
        val module = SerializersModule {
            contextual(LocalDate::class, DateSerializer)
            contextual(ApiStatus::class, StatusSerializer)
        }
        val relay = client { json(Json { serializersModule = module }) }
        server.enqueue(MockResponse().setBody("{\"date\":\"2026-11-13\",\"status\":\"A\",\"account\":\"acct-9\"}"))
        val result = relay.call<CustomTypes>("custom-types", HttpMethod.GET) as RelayResult.Success
        assertEquals(LocalDate.of(2026, 11, 13), result.data.date)
        assertEquals(ApiStatus.ACTIVE, result.data.status)
        assertEquals("acct-9", result.data.account.value)
    }

    @Test fun encodeDefaultsFlagControlsSerializableRequestBodies() = runBlocking {
        val relay = client { json(Json { encodeDefaults = true }) }
        server.enqueue(MockResponse().setBody("ok"))
        relay.request("encode", HttpMethod.POST, body = Outgoing("sample"))
        assertEquals("{\"name\":\"sample\",\"version\":7,\"optional\":null}", server.takeRequest().body.readUtf8())
    }

    @Test fun explicitNullsSettingControlsRequestEncoding() = runBlocking {
        val relay = client { json(Json { encodeDefaults = true; explicitNulls = false }) }
        server.enqueue(MockResponse().setBody("ok"))
        relay.request("encode-null", HttpMethod.POST, body = Outgoing("sample"))
        assertEquals("{\"name\":\"sample\",\"version\":7}", server.takeRequest().body.readUtf8())
    }

    @Test fun encodeDefaultsCanBeSetThroughTheConfigurationDsl() = runBlocking {
        val relay = client { encodeDefaults(true) }
        server.enqueue(MockResponse().setBody("ok"))
        relay.request("encode-default", HttpMethod.POST, body = Outgoing("sample"))
        assertEquals("{\"name\":\"sample\",\"version\":7}", server.takeRequest().body.readUtf8())
    }

    @Test fun malformedAndIncompatibleResponsesAreParsingFailures() = runBlocking {
        server.enqueue(MockResponse().setBody("{not-json"))
        assertEquals(ErrorType.PARSING, client().call<Basic>("malformed", HttpMethod.GET).errorType)
        server.enqueue(MockResponse().setBody("{\"value\":\"wrong-type\"}"))
        assertEquals(ErrorType.PARSING, client().call<Basic>("incompatible", HttpMethod.GET).errorType)
    }

    @Test fun customSerializerExceptionsBecomeParsingFailures() = runBlocking {
        val relay = client { json(Json { serializersModule = SerializersModule { contextual(Broken::class, BrokenSerializer) } }) }
        server.enqueue(MockResponse().setBody("{\"value\":\"bad\"}"))
        val result = relay.call<BrokenPayload>("broken-serializer", HttpMethod.GET)
        assertEquals(ErrorType.PARSING, result.errorType)
        assertTrue(result.errorOrNull?.cause is SerializationException)
        assertTrue(result.errorOrNull?.cause?.cause is IllegalArgumentException)
    }

    @Test fun concurrentTypedCallsShareConfiguredJsonSafely() = runBlocking {
        val count = 20
        val ready = CountDownLatch(count)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"value\":${request.path!!.substringAfterLast('-')}}")
        }
        val relay = client { json(Json { ignoreUnknownKeys = true }) }
        val results = coroutineScope {
            (0 until count).map { index -> async(Dispatchers.IO) {
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                relay.call<Basic>("item-$index", HttpMethod.GET)
            } }.also {
                check(withContext(Dispatchers.IO) { ready.await(5, TimeUnit.SECONDS) })
                release.countDown()
            }.awaitAll()
        }
        assertEquals((0 until count).toList(), results.map { (it as RelayResult.Success).data.value })
    }

    @Test fun rawResponseBehaviorRemainsUnchanged() = runBlocking {
        server.enqueue(MockResponse().setBody("plain text"))
        val response = client().request("raw", HttpMethod.GET)
        assertEquals("plain text", response.body)
    }
}

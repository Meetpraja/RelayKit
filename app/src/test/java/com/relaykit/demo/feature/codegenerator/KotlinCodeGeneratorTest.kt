package com.relaykit.demo.feature.codegenerator

import com.relaykit.demo.feature.request.AuthChoice
import com.relaykit.demo.feature.request.BodyChoice
import com.relaykit.demo.feature.request.HeaderEntry
import com.relaykit.demo.feature.request.QueryEntry
import com.relaykit.demo.feature.request.RequestBuilderViewModel
import com.relaykit.demo.feature.request.RequestConfiguration
import com.relaykit.demo.feature.setup.AuthMode
import com.relaykit.demo.feature.setup.HeaderDraft
import com.relaykit.demo.feature.setup.SetupState
import com.relaykit.network.logging.LogLevel
import com.relaykit.network.config.ApiKeyPlacement
import com.relaykit.network.request.HttpMethod
import com.relaykit.demo.core.data.HistoryRecord
import com.relaykit.demo.feature.request.toSanitizedRequestConfiguration
import org.junit.Assert.*
import org.junit.Test

class KotlinCodeGeneratorTest {
    @Test fun initializationUsesAvailableRelayKitConfigurationApiAndSafePlaceholders() {
        val result = KotlinCodeGenerator.generate(
            CodeSampleType.INITIALIZATION,
            SetupState(baseUrl = "https://user:pass@example.test/v1/?api_key=url-secret", connectSeconds = "8", readSeconds = "21", writeSeconds = "13", logLevel = LogLevel.BASIC, authMode = AuthMode.API_KEY, apiKey = "real-key", headers = listOf(HeaderDraft("Accept", "application/json"), HeaderDraft("X-API-Key", "header-secret"))),
            RequestConfiguration(),
        )
        assertTrue(result.source.contains("RelayKit.init(context.applicationContext)"))
        assertTrue(result.source.contains("baseUrl = \"https://example.test/v1/?api_key=[REDACTED]\""))
        assertTrue(result.source.contains("connect = 8.seconds"))
        assertTrue(result.source.contains("read = 21.seconds"))
        assertTrue(result.source.contains("write = 13.seconds"))
        assertTrue(result.source.contains("logging(LogLevel.BASIC, LogSink"))
        assertTrue(result.source.contains("apiKey(\"YOUR_API_KEY\")"))
        assertTrue(result.source.contains("header(\"X-API-Key\", \"YOUR_SECRET\")"))
        listOf("real-key", "header-secret", "url-secret", "user:pass").forEach { assertFalse(result.source.contains(it)) }

        val queryKey = KotlinCodeGenerator.generate(CodeSampleType.INITIALIZATION, SetupState(authMode = AuthMode.API_KEY), RequestConfiguration(), ApiKeyPlacement.QUERY).source
        assertTrue(queryKey.contains("auth(Auth.ApiKey(value = \"YOUR_API_KEY\", name = \"X-API-Key\", placement = ApiKeyPlacement.QUERY))"))
    }

    @Test fun generatesAllFiveSupportedMethodsWithTheRequestDsl() {
        HttpMethod.entries.forEach { method ->
            val code = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(method = method, endpoint = "/items")).source
            assertTrue("missing $method", code.contains("HttpMethod.${method.name}"))
            assertTrue(code.contains("client.request(\"/items\", HttpMethod.${method.name})"))
            assertTrue(code.contains("response.isSuccessful"))
            assertTrue(code.contains("response.statusCode"))
            assertTrue(code.contains("response.body"))
        }
    }

    @Test fun enabledHeadersAndQueriesAreEmittedAndSecretValuesBecomePlaceholders() {
        val code = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(
            endpoint = "/search",
            headers = listOf(HeaderEntry(name = "Accept", value = "application/json"), HeaderEntry(name = "X-API-Key", value = "real-key"), HeaderEntry(name = "X-Off", value = "ignored", enabled = false)),
            query = listOf(QueryEntry(name = "page", value = "3"), QueryEntry(name = "access_token", value = "real-token"), QueryEntry(name = "off", value = "ignored", enabled = false)),
        )).source
        assertTrue(code.contains("header(\"Accept\", \"application/json\")"))
        assertTrue(code.contains("header(\"X-API-Key\", \"YOUR_SECRET\")"))
        assertTrue(code.contains("query(\"page\", \"3\")"))
        assertTrue(code.contains("query(\"access_token\", \"YOUR_SECRET\")"))
        listOf("real-key", "real-token", "X-Off", "ignored").forEach { assertFalse(code.contains(it)) }
    }

    @Test fun jsonBodyBecomesTypedKotlinMapsAndRedactsNestedCredentials() {
        val code = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(
            method = HttpMethod.POST, endpoint = "/users", bodyChoice = BodyChoice.JSON,
            jsonBody = """{"name":"Ada","active":true,"count":2,"nested":{"password":"top-secret","apiToken":"camel-secret"},"list":["x",null]}""",
        )).source
        assertTrue(code.contains("body(mapOf(\"name\" to \"Ada\""))
        assertTrue(code.contains("\"password\" to \"YOUR_SECRET\""))
        assertTrue(code.contains("listOf(\"x\", null)"))
        assertFalse(code.contains("top-secret"))
        assertFalse(code.contains("camel-secret"))
    }

    @Test fun formMultipartAndFileExamplesUseSupportedRequestBuilderMethods() {
        val formCode = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(method = HttpMethod.PUT, endpoint = "/form", bodyChoice = BodyChoice.FORM, bodyFields = listOf(QueryEntry(name = "title", value = "hello"), QueryEntry(name = "password", value = "pw")))).source
        assertTrue(formCode.contains("form(mapOf(\"title\" to \"hello\", \"password\" to \"YOUR_SECRET\"))"))
        assertFalse(formCode.contains("\"pw\""))

        val multipart = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(method = HttpMethod.POST, endpoint = "/upload", bodyChoice = BodyChoice.MULTIPART, bodyFields = listOf(QueryEntry(name = "caption", value = "avatar")), files = listOf(com.relaykit.network.request.RelayFile(name = "file", file = java.io.File("private/path.png"), fileName = "private/path.png"))))
        assertTrue(multipart.source.contains("multipart(mapOf(\"caption\" to \"avatar\"))"))
        assertTrue(multipart.source.contains("file(RelayFile(name = \"file\", file = File(\"path/to/upload-1.bin\")"))
        assertFalse(multipart.source.contains("private/path.png"))
        assertTrue(multipart.warning.orEmpty().contains("placeholders"))
    }

    @Test fun supportedAuthenticationModesUsePlaceholdersAndValidAuthTypes() {
        val apiKey = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(endpoint = "/x", authChoice = AuthChoice.API_KEY, authName = "X-Key", authValue = "real-api-key")).source
        assertTrue(apiKey.contains("Auth.ApiKey(value = \"YOUR_API_KEY\", name = \"X-Key\")"))
        val queryApiKey = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(endpoint = "/x", authChoice = AuthChoice.API_KEY, authName = "api_key", authValue = "real-api-key"), ApiKeyPlacement.QUERY).source
        assertTrue(queryApiKey.contains("Auth.ApiKey(value = \"YOUR_API_KEY\", name = \"api_key\", placement = ApiKeyPlacement.QUERY)"))
        val bearer = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(endpoint = "/x", authChoice = AuthChoice.BEARER, authValue = "real-bearer")).source
        assertTrue(bearer.contains("Auth.Bearer { loadBearerToken() }"))
        assertTrue(bearer.contains("private suspend fun loadBearerToken(): String?"))
        val custom = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(endpoint = "/x", authChoice = AuthChoice.CUSTOM, authName = "Authorization", authValue = "real-custom")).source
        assertTrue(custom.contains("Auth.Custom(header = \"Authorization\", value = \"YOUR_AUTH_VALUE\")"))
        val none = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(endpoint = "/x", authChoice = AuthChoice.NONE)).source
        assertTrue(none.contains("auth(Auth.None)"))
        listOf(apiKey, bearer, custom).forEach { code -> listOf("real-api-key", "real-bearer", "real-custom").forEach { assertFalse(code.contains(it)) } }
    }

    @Test fun missingFieldsAndIncompatibleBodiesProduceCompilablePlaceholdersAndWarnings() {
        val empty = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration())
        assertTrue(empty.source.contains("client.request(\"YOUR_ENDPOINT\", HttpMethod.GET)"))
        assertFalse(empty.source.contains("body("))
        val getBody = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(method = HttpMethod.GET, endpoint = "/x", bodyChoice = BodyChoice.JSON, jsonBody = "{}"))
        assertFalse(getBody.source.contains("body("))
        assertTrue(getBody.warning.orEmpty().contains("GET requests cannot carry a body"))
        val invalidJson = KotlinCodeGenerator.generate(CodeSampleType.REQUEST, SetupState(), RequestConfiguration(method = HttpMethod.POST, endpoint = "/x", bodyChoice = BodyChoice.JSON, jsonBody = "{invalid"))
        assertFalse(invalidJson.source.contains("body("))
        assertTrue(invalidJson.warning.orEmpty().contains("invalid"))
    }

    @Test fun kotlinStringEscapingAndGenerationAreDeterministic() {
        val value = "line1\n\"quoted\" \$path \\tab\t"
        assertEquals("\"line1\\n\\\"quoted\\\" \\\$path \\\\tab\\t\"", KotlinCodeGenerator.kotlinString(value))
        val input = RequestConfiguration(method = HttpMethod.PATCH, endpoint = "/things/\"\$id", query = listOf(QueryEntry(name = "q", value = "a\$b")))
        val first = KotlinCodeGenerator.generate(CodeSampleType.COMPLETE, SetupState(), input).source
        val second = KotlinCodeGenerator.generate(CodeSampleType.COMPLETE, SetupState(), input).source
        assertEquals(first, second)
        assertTrue(first.contains("/things/\\\"\\\$id"))
        assertTrue(first.contains("a\\\$b"))
        assertTrue(first.contains("runRelayKitExample"))
    }

    @Test fun generatorCanLoadBuilderAndSanitizedHistoryConfigurationsWithoutExecution() {
        val builder = RequestBuilderViewModel()
        builder.edit { it.copy(method = HttpMethod.POST, endpoint = "/posts", headers = listOf(HeaderEntry(name = "Accept", value = "application/json", enabled = true), HeaderEntry(name = "X-Off", value = "no", enabled = false))) }
        val generator = CodeGeneratorViewModel()
        generator.fromRequest(builder.state.value.config)
        assertTrue(generator.state.value.generatedCode.contains("HttpMethod.POST"))
        assertTrue(generator.state.value.generatedCode.contains("header(\"Accept\""))
        assertFalse(builder.state.value.executing)

        val history = HistoryRecord(method = HttpMethod.POST, endpoint = "/safe", resolvedUrl = "https://api.example.test/safe", authType = "Bearer", requestHeaders = mapOf("Authorization" to "[REDACTED]"), bodyChoice = "JSON", requestBody = """{"token":"[REDACTED]"}""")
        generator.fromHistory(history.toSanitizedRequestConfiguration(), "History")
        assertTrue(generator.state.value.generatedCode.contains("YOUR_SECRET"))
        assertFalse(generator.state.value.generatedCode.contains("[REDACTED]"))
        assertFalse(generator.state.value.generatedCode.contains("Bearer"))
    }
}

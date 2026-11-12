package com.relaykit.demo.feature.request

import com.relaykit.network.request.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBuilderViewModelTest {
    @Test fun preparesEnabledRowsAndPreservesDisabledRowsInEditor() {
        val vm = RequestBuilderViewModel()
        vm.edit { it.copy(endpoint = "/users", method = HttpMethod.POST, headers = listOf(HeaderEntry(name = "X-Active", value = "yes"), HeaderEntry(name = "X-Off", value = "no", enabled = false)), query = listOf(QueryEntry(name = "q", value = "a b"), QueryEntry(name = "skip", value = "x", enabled = false))) }
        val prepared = vm.prepare()
        assertTrue(prepared.validation.valid)
        assertEquals(mapOf("X-Active" to "yes"), prepared.headers)
        assertEquals(mapOf("q" to "a b"), prepared.query)
        assertEquals(2, vm.state.value.config.headers.size)
    }

    @Test fun validatesEndpointHeadersJsonAndGetBody() {
        val vm = RequestBuilderViewModel()
        vm.edit { it.copy(endpoint = "", headers = listOf(HeaderEntry(name = "bad name", value = "x")), bodyChoice = BodyChoice.JSON, jsonBody = "{oops") }
        val result = vm.prepare()
        assertFalse(result.validation.valid)
        assertTrue(result.validation.endpointError != null)
        assertTrue(result.validation.headerErrors.isNotEmpty())
        assertTrue(result.validation.jsonError != null)

        vm.edit { it.copy(endpoint = "/users", method = HttpMethod.GET, bodyChoice = BodyChoice.JSON, jsonBody = "{}") }
        assertTrue(vm.prepare().validation.bodyError != null)
    }
}

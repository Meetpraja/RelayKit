package com.relaykit.demo.feature.codegenerator

import androidx.lifecycle.ViewModel
import com.relaykit.demo.feature.request.RequestConfiguration
import com.relaykit.demo.feature.setup.SetupState
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.config.ApiKeyPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CodeGeneratorViewModel : ViewModel() {
    private val mutable = MutableStateFlow(CodeGeneratorState())
    val state = mutable.asStateFlow()

    fun selectType(type: CodeSampleType) { mutable.value = mutable.value.copy(sampleType = type, generatedCode = "", warning = null) }
    fun updateSetup(setup: SetupState) {
        val current = mutable.value
        mutable.value = current.copy(setup = setup, generatedCode = if (setup == current.setup) current.generatedCode else "", warning = if (setup == current.setup) current.warning else null)
    }
    fun updateRequest(transform: (RequestConfiguration) -> RequestConfiguration) { mutable.value = mutable.value.copy(request = transform(mutable.value.request), generatedCode = "", warning = null) }

    fun fromRequest(request: RequestConfiguration) {
        mutable.value = mutable.value.copy(sampleType = CodeSampleType.REQUEST, request = request, sourceLabel = "Request Builder")
        generate()
    }

    fun fromHistory(request: RequestConfiguration, label: String) {
        mutable.value = mutable.value.copy(sampleType = CodeSampleType.REQUEST, request = request, sourceLabel = label)
        generate()
    }

    fun generate() {
        val current = mutable.value
        val result = KotlinCodeGenerator.generate(current.sampleType, current.setup, current.request, current.apiKeyPlacement)
        mutable.value = current.copy(generatedCode = result.source, warning = result.warning)
    }

    fun reset() { mutable.value = CodeGeneratorState(setup = mutable.value.setup, sourceLabel = "No request loaded") }
    fun selectMethod(method: HttpMethod) { updateRequest { it.copy(method = method) } }
    fun editEndpoint(endpoint: String) { updateRequest { it.copy(endpoint = endpoint) } }
    fun selectApiKeyPlacement(placement: ApiKeyPlacement) { mutable.value = mutable.value.copy(apiKeyPlacement = placement, generatedCode = "", warning = null) }
}

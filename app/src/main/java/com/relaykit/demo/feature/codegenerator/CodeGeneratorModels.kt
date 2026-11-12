package com.relaykit.demo.feature.codegenerator

import com.relaykit.demo.feature.request.RequestConfiguration
import com.relaykit.demo.feature.setup.SetupState
import com.relaykit.network.config.ApiKeyPlacement

enum class CodeSampleType(val label: String) { INITIALIZATION("Initialization"), REQUEST("Request"), COMPLETE("Complete example") }

data class CodeGeneratorState(
    val sampleType: CodeSampleType = CodeSampleType.REQUEST,
    val setup: SetupState = SetupState(),
    val request: RequestConfiguration = RequestConfiguration(),
    val apiKeyPlacement: ApiKeyPlacement = ApiKeyPlacement.HEADER,
    val sourceLabel: String = "Current request",
    val generatedCode: String = "",
    val warning: String? = null,
)

data class GeneratedCode(val source: String, val warning: String? = null)

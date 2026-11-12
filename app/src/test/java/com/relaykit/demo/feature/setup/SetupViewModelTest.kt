package com.relaykit.demo.feature.setup

import org.junit.Assert.assertTrue
import org.junit.Test
import android.content.ContextWrapper

class SetupViewModelTest {
    @Test fun rejectsBaseUrlWithoutTrailingSlash() {
        val viewModel = SetupViewModel()
        viewModel.update { it.copy(baseUrl = "https://api.example.com") }
        viewModel.initialize(ContextWrapper(null))
        assertTrue(viewModel.state.value.error.orEmpty().contains("ending in /"))
        assertTrue(!viewModel.state.value.initialized)
    }

    @Test fun rejectsOutOfRangeTimeout() {
        val viewModel = SetupViewModel()
        viewModel.update { it.copy(connectSeconds = "0") }
        viewModel.initialize(ContextWrapper(null))
        assertTrue(viewModel.state.value.error.orEmpty().contains("1 to 300"))
        assertTrue(!viewModel.state.value.initialized)
    }
}

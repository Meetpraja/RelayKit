package com.relaykit.demo.core.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.relaykit.demo.feature.history.HistoryViewModel
import com.relaykit.demo.feature.logs.LogsViewModel
import com.relaykit.demo.feature.codegenerator.CodeGeneratorViewModel
import com.relaykit.demo.feature.request.RequestBuilderViewModel
import com.relaykit.demo.feature.setup.SetupViewModel

class DemoViewModelFactory(private val data: DemoContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(RequestBuilderViewModel::class.java) -> RequestBuilderViewModel(data)
        modelClass.isAssignableFrom(SetupViewModel::class.java) -> SetupViewModel(data)
        modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(data)
        modelClass.isAssignableFrom(LogsViewModel::class.java) -> LogsViewModel(data)
        modelClass.isAssignableFrom(CodeGeneratorViewModel::class.java) -> CodeGeneratorViewModel()
        else -> error("Unsupported ViewModel: ${modelClass.name}")
    } as T
}

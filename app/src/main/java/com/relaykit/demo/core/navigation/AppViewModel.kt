package com.relaykit.demo.core.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.relaykit.demo.core.data.DemoContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns only application-shell navigation state for Phase 1. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val data = DemoContainer(application.filesDir)
    private val _destination = MutableStateFlow(Destination.WORKSPACE)
    val destination: StateFlow<Destination> = _destination.asStateFlow()
    fun navigate(destination: Destination) { _destination.value = destination }
}

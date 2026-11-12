package com.relaykit.demo.feature.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relaykit.demo.core.data.DemoContainer
import com.relaykit.demo.core.data.DiagnosticLog
import com.relaykit.demo.core.data.LogSeverity
import com.relaykit.network.request.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class LogsFilterState(
    val search: String = "",
    val level: LogSeverity? = null,
    val requestId: String = "",
    val method: HttpMethod? = null,
    val endpoint: String = "",
    val selectedId: String? = null
)

class LogsViewModel(private val data: DemoContainer) : ViewModel() {
    private val filter = MutableStateFlow(LogsFilterState())
    val filterState = filter
    val snapshot = data.logs.state
    val visibleLogs = combine(snapshot, filter) { stored, current ->
        filterLogs(stored.records, current)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (LogsFilterState) -> LogsFilterState) { filter.value = transform(filter.value) }
    fun select(log: DiagnosticLog?) { update { it.copy(selectedId = log?.id) } }


    fun clear() = viewModelScope.launch { data.logs.clear() }
}

fun filterLogs(records: List<DiagnosticLog>, current: LogsFilterState): List<DiagnosticLog> =

    records.filter { log ->
        (current.search.isBlank() || log.message.contains(
            current.search,
            true
        ) || log.category.contains(current.search, true) || log.endpoint.orEmpty()
            .contains(current.search, true)) &&
                (current.level == null || current.level == log.level) &&
                (current.requestId.isBlank() || log.executionId.orEmpty()
                    .contains(current.requestId, true)) &&
                (current.method == null || current.method == log.method) &&
                (current.endpoint.isBlank() || log.endpoint.orEmpty()
                    .contains(current.endpoint, true))
    }

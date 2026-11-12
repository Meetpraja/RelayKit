package com.relaykit.demo.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relaykit.demo.core.data.DemoContainer
import com.relaykit.demo.core.data.HistoryRecord
import com.relaykit.network.request.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

enum class HistoryStatusFilter { ALL, SUCCESS, FAILED }
data class HistoryFilterState(val search: String = "", val method: HttpMethod? = null, val status: HistoryStatusFilter = HistoryStatusFilter.ALL, val selectedId: String? = null)

class HistoryViewModel(private val data: DemoContainer) : ViewModel() {
    private val filter = MutableStateFlow(HistoryFilterState())
    val filterState = filter
    val records = data.history.state
    val visibleRecords = combine(records, filter) { snapshot, current ->
        filterHistory(snapshot.records, current)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun search(value: String) { filter.value = filter.value.copy(search = value) }
    fun method(value: HttpMethod?) { filter.value = filter.value.copy(method = value) }
    fun status(value: HistoryStatusFilter) { filter.value = filter.value.copy(status = value) }
    fun select(record: HistoryRecord?) { filter.value = filter.value.copy(selectedId = record?.id) }
    fun clear() = viewModelScope.launch { data.history.clear() }
    fun delete(id: String) = viewModelScope.launch { data.history.delete(id) }
}

fun filterHistory(records: List<HistoryRecord>, current: HistoryFilterState): List<HistoryRecord> = records.filter { record ->
    val matchesSearch = current.search.isBlank() || record.endpoint.contains(current.search, true) || record.resolvedUrl.contains(current.search, true) || record.method.name.contains(current.search, true)
    matchesSearch && (current.method == null || current.method == record.method) && when (current.status) {
        HistoryStatusFilter.ALL -> true
        HistoryStatusFilter.SUCCESS -> record.success == true
        HistoryStatusFilter.FAILED -> record.success == false
    }
}

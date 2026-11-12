package com.relaykit.demo.feature.logs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaykit.demo.core.data.DiagnosticLog
import com.relaykit.demo.core.data.LogSeverity
import com.relaykit.demo.feature.history.DetailSection
import com.relaykit.demo.feature.history.StorageIssue
import com.relaykit.network.request.HttpMethod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(viewModel: LogsViewModel, modifier: Modifier = Modifier) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val filter by viewModel.filterState.collectAsStateWithLifecycle()
    val visible by viewModel.visibleLogs.collectAsStateWithLifecycle()
    val selected = snapshot.records.firstOrNull { it.id == filter.selectedId }
    if (selected != null) {
        LogDetail(selected, { viewModel.select(null) }, modifier)
        return
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Diagnostic Logs",
                    style = MaterialTheme.typography.headlineSmall
                ); Text(
                "RelayKit output and demo lifecycle events",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
            TextButton(onClick = viewModel::clear, enabled = snapshot.records.isNotEmpty()) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    null
                ); Spacer(Modifier.width(5.dp)); Text("Clear")
            }
        }
        snapshot.storageError?.let { StorageIssue(it) }
        OutlinedTextField(
            filter.search,
            { viewModel.update { current -> current.copy(search = it) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search logs") },
            singleLine = true
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            FilterChip(
                filter.level == null,
                { viewModel.update { it.copy(level = null) } },
                label = { Text("All levels") })
            LogSeverity.entries.forEach { level ->
                FilterChip(
                    filter.level == level,
                    { viewModel.update { it.copy(level = level) } },
                    label = { Text(level.name) })
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                filter.requestId,
                { value -> viewModel.update { it.copy(requestId = value) } },
                modifier = Modifier.weight(1f),
                label = { Text("Request ID") },
                singleLine = true
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var methodMenuOpen by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(
                    false
                )
            }
            Box {
                OutlinedButton(onClick = { methodMenuOpen = true }) {
                    Text(
                        filter.method?.name ?: "Any method"
                    )
                }
                DropdownMenu(methodMenuOpen, { methodMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Any method") },
                        onClick = {
                            methodMenuOpen = false; viewModel.update { it.copy(method = null) }
                        })
                    HttpMethod.entries.forEach { method ->
                        DropdownMenuItem(
                            text = { Text(method.name) },
                            onClick = {
                                methodMenuOpen =
                                    false; viewModel.update { it.copy(method = method) }
                            })
                    }
                }
            }
            OutlinedTextField(
                filter.endpoint,
                { value -> viewModel.update { it.copy(endpoint = value) } },
                modifier = Modifier.weight(1f),
                label = { Text("Endpoint contains") },
                singleLine = true
            )
        }
        if (visible.isEmpty()) {
            EmptyLogs(
                if (snapshot.records.isEmpty()) "No diagnostic logs" else "No matching logs",
                if (snapshot.records.isEmpty()) "Send a request to capture RelayKit output and request lifecycle diagnostics." else "Adjust the filters to see matching events."
            )
        } else Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            visible.forEach {
                LogRow(
                    it,
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.select(it) })
            }
        }
    }
}

@Composable private fun LogRow(log: DiagnosticLog, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(log.level.name, color = levelColor(log.level), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(62.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(log.category, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(log.endpoint ?: log.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text("${log.method?.name ?: log.source} · ${clock(log.timestampMillis)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable private fun LogDetail(log: DiagnosticLog, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back to logs") }
            Column { Text("Log details", style = MaterialTheme.typography.headlineSmall); Text("${log.level} · ${log.category}", color = levelColor(log.level)) }
        }
        DetailSection("Event", "Source: ${log.source}\nTime: ${clock(log.timestampMillis)}\nMethod: ${log.method?.name ?: "Unknown"}\nEndpoint: ${log.endpoint ?: "Unknown"}")
        DetailSection("Associated execution", log.executionId ?: "Not available from the RelayKit LogSink event")
        DetailSection("Message", log.message)
    }
}

@Composable private fun EmptyLogs(title: String, description: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier
            .fillMaxWidth()
            .padding(22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun levelColor(level: LogSeverity) = when (level) {
    LogSeverity.ERROR -> MaterialTheme.colorScheme.error
    LogSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    LogSeverity.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogSeverity.INFO -> MaterialTheme.colorScheme.primary
}
private fun clock(value: Long) = SimpleDateFormat("MMM d, HH:mm:ss.SSS", Locale.getDefault()).format(Date(value))

package com.relaykit.demo.feature.history

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaykit.demo.core.data.HistoryRecord
import com.relaykit.network.request.HttpMethod
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onLoadRequest: (HistoryRecord) -> Unit, onGenerateCode: (HistoryRecord) -> Unit, modifier: Modifier = Modifier) {
    val snapshot by viewModel.records.collectAsStateWithLifecycle()
    val filter by viewModel.filterState.collectAsStateWithLifecycle()
    val visible by viewModel.visibleRecords.collectAsStateWithLifecycle()
    val selected = snapshot.records.firstOrNull { it.id == filter.selectedId }
    if (selected != null) {
        HistoryDetail(selected, onBack = { viewModel.select(null) }, onLoad = { onLoadRequest(selected) }, onGenerate = { onGenerateCode(selected) }, modifier)
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Request History", style = MaterialTheme.typography.headlineSmall); Text("Completed and failed RelayKit calls", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = viewModel::clear, enabled = snapshot.records.isNotEmpty()) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(5.dp)); Text("Clear") }
        }
        snapshot.storageError?.let { StorageIssue(it) }
        OutlinedTextField(filter.search, viewModel::search, modifier = Modifier.fillMaxWidth(), label = { Text("Search endpoint or method") }, singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(filter.method == null, { viewModel.method(null) }, label = { Text("All methods") })
            HttpMethod.entries.forEach { method -> FilterChip(filter.method == method, { viewModel.method(method) }, label = { Text(method.name) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            HistoryStatusFilter.entries.forEach { status -> FilterChip(filter.status == status, { viewModel.status(status) }, label = { Text(status.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
        }
        if (visible.isEmpty()) {
            EmptyHistory(if (snapshot.records.isEmpty()) "No requests recorded" else "No matching requests", if (snapshot.records.isEmpty()) "Send a request from Request Builder. Its sanitized details and response will appear here." else "Adjust search or filters to see matching request executions.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.forEach { record -> HistoryRow(record, Modifier.fillMaxWidth().clickable { viewModel.select(record) }) }
            }
        }
    }
}

@Composable private fun HistoryRow(record: HistoryRecord, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = methodColor(record.method.name), shape = MaterialTheme.shapes.small) { Text(record.method.name, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(record.endpoint, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text("${record.responseStatus?.let { "HTTP $it" } ?: record.outcome} · ${time(record.timestampMillis)} · ${record.durationMs} ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(if (record.success == true) "OK" else "FAIL", color = if (record.success == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable private fun HistoryDetail(record: HistoryRecord, onBack: () -> Unit, onLoad: () -> Unit, onGenerate: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back to history") }
            Column(Modifier.weight(1f)) { Text("Request details", style = MaterialTheme.typography.headlineSmall); Text("${record.method} · ${record.responseStatus?.let { "HTTP $it" } ?: record.outcome}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onLoad) { Text("Load in Builder") }
                TextButton(onClick = onGenerate) { Text("Generate code") }
            }
        }
        Text("Loading restores a sanitized draft and does not send it. Credentials and file contents are omitted; multipart examples use placeholders.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DetailSection("Request", "${record.method} ${record.resolvedUrl}\nEndpoint: ${record.endpoint}\nTime: ${time(record.timestampMillis)}\nDuration: ${record.durationMs} ms\nAuthentication: ${record.authType}")
        DetailSection("Request headers", mapText(record.requestHeaders))
        DetailSection("Query parameters", mapText(record.query))
        DetailSection("Request body · ${record.bodyChoice}", record.requestBody ?: mapText(record.requestBodyFields))
        DetailSection("Response headers", mapText(record.responseHeaders))
        DetailSection("Response body", record.responseBody ?: "No response body")
        record.errorDetails?.let { DetailSection("Error", it, error = true) }
    }
}

@Composable internal fun DetailSection(title: String, value: String, error: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Text(value, Modifier.fillMaxWidth().padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun EmptyHistory(title: String, message: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun StorageIssue(message: String) { Text("Storage issue: $message", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
private fun mapText(values: Map<String, String>) = if (values.isEmpty()) "None" else values.entries.joinToString("\n") { "${it.key}: ${it.value}" }
private fun time(timestamp: Long) = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
@Composable private fun methodColor(method: String) = when (method) {
    "POST" -> MaterialTheme.colorScheme.tertiaryContainer
    "PUT", "PATCH" -> MaterialTheme.colorScheme.secondaryContainer
    "DELETE" -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.primaryContainer
}

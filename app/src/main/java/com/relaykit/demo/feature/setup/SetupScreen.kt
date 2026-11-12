package com.relaykit.demo.feature.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaykit.network.logging.LogLevel
import com.relaykit.demo.core.data.RecordRedactor

@Composable fun SetupScreen(viewModel: SetupViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("RelayKit configuration", style = MaterialTheme.typography.headlineSmall)
            Text("Configure the process-wide default client. Credentials are kept in memory only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(state.baseUrl, { viewModel.update { s -> s.copy(baseUrl = it, error = null) } }, label = { Text("Base URL") }, supportingText = { Text("Absolute HTTP(S) URL ending in /") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Timeouts · seconds", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeoutField("Connect", state.connectSeconds, Modifier.weight(1f)) { v -> viewModel.update { it.copy(connectSeconds = v, error = null) } }
                TimeoutField("Read", state.readSeconds, Modifier.weight(1f)) { v -> viewModel.update { it.copy(readSeconds = v, error = null) } }
                TimeoutField("Write", state.writeSeconds, Modifier.weight(1f)) { v -> viewModel.update { it.copy(writeSeconds = v, error = null) } }
            }
            Text("Connect: opens a connection · Read: waits for response data · Write: sends request data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Logging", style = MaterialTheme.typography.titleMedium)
            Text("Controls the detail sent to the in-memory diagnostic sink. RelayKit redacts sensitive values.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LogLevel.entries.forEach { level -> FilterChip(selected = state.logLevel == level, onClick = { viewModel.update { it.copy(logLevel = level) } }, label = { Text(level.name) }) }
            }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Authentication", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AuthMode.entries.forEach { mode -> FilterChip(selected = state.authMode == mode, onClick = { viewModel.update { it.copy(authMode = mode) } }, label = { Text(mode.name.replace('_', ' ')) }) }
            }
            when (state.authMode) {
                AuthMode.NONE -> Text("Requests use no configured authentication.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AuthMode.API_KEY -> SecretField("API key", state.apiKey, { v -> viewModel.update { it.copy(apiKey = v, error = null) } })
                AuthMode.BEARER -> SecretField("Bearer token", state.bearerToken, { v -> viewModel.update { it.copy(bearerToken = v, error = null) } })
            }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Default headers", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { viewModel.update { it.copy(headers = it.headers + HeaderDraft()) } }) { Text("Add header") }
            }
            if (state.headers.isEmpty()) Text("No default headers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.headers.forEachIndexed { index, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(row.enabled, { enabled -> viewModel.update { s -> s.copy(headers = s.headers.toMutableList().also { it[index] = it[index].copy(enabled = enabled) }) } })
                    OutlinedTextField(row.name, { v -> viewModel.update { s -> s.copy(headers = s.headers.toMutableList().also { it[index] = it[index].copy(name = v) }) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(row.value, { v -> viewModel.update { s -> s.copy(headers = s.headers.toMutableList().also { it[index] = it[index].copy(value = v) }) } }, label = { Text("Value") }, singleLine = true, visualTransformation = if (RecordRedactor.isSensitive(row.name)) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.update { s -> s.copy(headers = s.headers.toMutableList().also { it.removeAt(index) }) } }) { Text("Remove") }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !state.busy, onClick = { viewModel.initialize(context) }) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (state.initialized) "Update client" else "Initialize RelayKit")
            }
            OutlinedButton(enabled = !state.busy, onClick = viewModel::reset) { Text("Reset") }
        }
        Text("Configuration and credentials are not persisted. Reset clears the active RelayKit client and the current in-memory form.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun TimeoutField(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}

@Composable private fun SecretField(label: String, value: String, onValue: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
        supportingText = { Text("Stored in memory only; it is never shown in Workspace summaries.") }, modifier = Modifier.fillMaxWidth())
}

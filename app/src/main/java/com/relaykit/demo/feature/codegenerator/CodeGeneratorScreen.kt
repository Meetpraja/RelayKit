package com.relaykit.demo.feature.codegenerator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaykit.demo.feature.setup.SetupState
import com.relaykit.network.request.HttpMethod
import com.relaykit.network.config.ApiKeyPlacement

@Composable
fun CodeGeneratorScreen(
    viewModel: CodeGeneratorViewModel,
    setup: SetupState,
    onRefreshRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(setup.baseUrl, setup.connectSeconds, setup.readSeconds, setup.writeSeconds, setup.logLevel, setup.authMode, setup.headers) {
        viewModel.updateSetup(setup)
    }
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Kotlin Code Generator", style = MaterialTheme.typography.headlineSmall)
            Text("Generate copyable examples from RelayKit’s current configuration.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CodeSampleType.entries.forEach { type ->
                FilterChip(selected = state.sampleType == type, onClick = { viewModel.selectType(type) }, label = { Text(type.label) })
            }
        }
        if (state.sampleType != CodeSampleType.INITIALIZATION) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                var methodExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { methodExpanded = true }) { Text(state.request.method.name) }
                    DropdownMenu(methodExpanded, { methodExpanded = false }) {
                        HttpMethod.entries.forEach { method -> DropdownMenuItem(text = { Text(method.name) }, onClick = { methodExpanded = false; viewModel.selectMethod(method) }) }
                    }
                }
                OutlinedTextField(state.request.endpoint, viewModel::editEndpoint, label = { Text("Endpoint") }, placeholder = { Text("/users/1") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Source · ${state.sourceLabel}", style = MaterialTheme.typography.labelLarge)
                    Text("${state.request.bodyChoice.name} body · enabled headers and parameters only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRefreshRequest) { Text("Refresh from Builder") }
            }
            if (state.request.authChoice == com.relaykit.demo.feature.request.AuthChoice.API_KEY || state.sampleType != CodeSampleType.REQUEST && state.setup.authMode == com.relaykit.demo.feature.setup.AuthMode.API_KEY) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("API key placement", style = MaterialTheme.typography.labelLarge)
                    ApiKeyPlacement.entries.forEach { placement ->
                        FilterChip(state.apiKeyPlacement == placement, { viewModel.selectApiKeyPlacement(placement) }, label = { Text(placement.name.lowercase().replaceFirstChar(Char::uppercase)) })
                    }
                }
            }
        } else Text("Setup values and enabled default headers are used. Credentials are replaced with safe placeholders.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::generate) { Text("Generate code") }
            OutlinedButton(onClick = viewModel::reset) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = state.generatedCode.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(state.generatedCode)) }) { Text("Copy") }
        }
        state.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.generatedCode.isBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No code generated", style = MaterialTheme.typography.titleMedium)
                    Text("Choose an example type and generate a preview. This screen never executes the request.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Text(when (state.sampleType) {
                CodeSampleType.INITIALIZATION -> "Initializes the default RelayKit client with the selected timeouts, logging, headers, and safe auth placeholders."
                CodeSampleType.REQUEST -> "Executes the configured request in a suspend function and handles HTTP and transport failures."
                CodeSampleType.COMPLETE -> "Combines initialization and request execution in one copyable example."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Text(state.generatedCode, Modifier.padding(14.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
                }
            }
        }
    }
}

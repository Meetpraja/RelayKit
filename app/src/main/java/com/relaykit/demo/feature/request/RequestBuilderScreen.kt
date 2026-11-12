package com.relaykit.demo.feature.request

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.OpenableColumns
import com.relaykit.network.request.RelayFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaykit.network.request.HttpMethod
import com.relaykit.demo.core.data.RecordRedactor

@Composable fun RequestBuilderScreen(viewModel: RequestBuilderViewModel, baseUrl: String?, initialized: Boolean, onGenerateCode: () -> Unit = {}, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = state.config
    val prepared = viewModel.prepare()
    val shownState = state.copy(validation = prepared.validation)
    val clipboard = LocalClipboardManager.current
    val appContext = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = runCatching {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "upload"
            }.getOrDefault("upload")
            viewModel.edit { it.copy(files = it.files + RelayFile(name = "file", uri = uri, fileName = name)) }
        }
    }
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            var methodMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { methodMenu = true }) { Text(c.method.name) }
                DropdownMenu(methodMenu, { methodMenu = false }) { HttpMethod.entries.forEach { method -> DropdownMenuItem(text = { Text(method.name) }, onClick = { methodMenu = false; viewModel.edit { it.copy(method = method) } }) } }
            }
            OutlinedTextField(c.endpoint, { value -> viewModel.edit { it.copy(endpoint = value) } }, modifier = Modifier.weight(1f), label = { Text("Endpoint") }, placeholder = { Text("/users/1") }, singleLine = true, isError = state.validation.endpointError != null)
            Button(onClick = viewModel::execute, enabled = initialized && !state.executing && prepared.validation.valid) {
                if (state.executing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send")
            }
        }
        shownState.validation.endpointError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Base URL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(baseUrl ?: "Not configured", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(if (initialized) "Client ready" else "Initialize RelayKit in Setup", style = MaterialTheme.typography.labelMedium, color = if (initialized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TabRow(selectedTabIndex = state.tab.ordinal) {
            RequestTab.entries.forEach { tab -> Tab(selected = state.tab == tab, onClick = { viewModel.selectTab(tab) }, text = { Text(tab.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
        }
        when (state.tab) {
            RequestTab.HEADERS -> EntriesEditor("Headers", c.headers.map { HeaderAdapter(it.id, it.name, it.value, it.enabled) }, shownState.validation.headerErrors, "Header", { viewModel.edit { it.copy(headers = it.headers + HeaderEntry()) } }, { id, name, value, enabled -> viewModel.edit { s -> s.copy(headers = s.headers.map { if (it.id == id) it.copy(name = name, value = value, enabled = enabled) else it }) } }, { id -> viewModel.edit { it.copy(headers = it.headers.filterNot { row -> row.id == id }) } })
            RequestTab.QUERY -> EntriesEditor("Query parameters", c.query.map { QueryAdapter(it.id, it.name, it.value, it.enabled) }, shownState.validation.queryErrors, "Parameter", { viewModel.edit { it.copy(query = it.query + QueryEntry()) } }, { id, name, value, enabled -> viewModel.edit { s -> s.copy(query = s.query.map { if (it.id == id) it.copy(name = name, value = value, enabled = enabled) else it }) } }, { id -> viewModel.edit { it.copy(query = it.query.filterNot { row -> row.id == id }) } })
            RequestTab.AUTH -> AuthEditor(viewModel, shownState)
            RequestTab.BODY -> BodyEditor(viewModel, shownState, onPickFile = { filePicker.launch(arrayOf("*/*")) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Request preview", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onGenerateCode) { Text("Generate Kotlin") }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val target = if (c.endpoint.startsWith("http")) c.endpoint else (baseUrl.orEmpty() + c.endpoint.removePrefix("/"))
                Text("${c.method}  ${RecordRedactor.url(target)}", fontFamily = FontFamily.Monospace)
                Text("${prepared.headers.size} headers · ${prepared.query.size} parameters · ${c.authChoice.name} · ${c.bodyChoice.name}", style = MaterialTheme.typography.bodySmall)
                if (prepared.auth != null && c.authChoice !in setOf(AuthChoice.NONE, AuthChoice.INHERIT)) Text("Authorization value · ••••••••", style = MaterialTheme.typography.bodySmall)
                if (!prepared.validation.valid) Text("Fix validation issues before this request can be prepared.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.executing) OutlinedButton(onClick = viewModel::cancelExecution) { Text("Cancel request") }
        state.executionMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.execution?.let { execution ->
            ResponseViewer(execution, clipboard)
        }
    }
}

@Composable private fun EntriesEditor(title: String, rows: List<out EntryUi>, errors: Map<String, String>, singular: String, add: () -> Unit, update: (String, String, String, Boolean) -> Unit, remove: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(title, style = MaterialTheme.typography.titleMedium); Text("Disabled rows are excluded from the prepared request.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = add) { Text("Add $singular") }
        }
        if (rows.isEmpty()) Text("No $title added", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(row.enabled, { update(row.id, row.name, row.value, it) })
                OutlinedTextField(row.name, { update(row.id, it, row.value, row.enabled) }, label = { Text("Name") }, singleLine = true, isError = row.id in errors, modifier = Modifier.weight(1f))
                OutlinedTextField(row.value, { update(row.id, row.name, it, row.enabled) }, label = { Text("Value") }, singleLine = true, visualTransformation = if (RecordRedactor.isSensitive(row.name)) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.weight(1f))
                TextButton(onClick = { remove(row.id) }) { Text("Remove") }
            }
            errors[row.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private interface EntryUi { val id: String; val name: String; val value: String; val enabled: Boolean }
// Adapter overloads keep stable row IDs while sharing a single compact editor.
private data class HeaderAdapter(override val id: String, override val name: String, override val value: String, override val enabled: Boolean) : EntryUi
private data class QueryAdapter(override val id: String, override val name: String, override val value: String, override val enabled: Boolean) : EntryUi

@Composable private fun AuthEditor(vm: RequestBuilderViewModel, state: RequestBuilderState) {
    val c = state.config
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Authorization", style = MaterialTheme.typography.titleMedium)
        Text("Inherited credentials are not shown in the request preview.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { AuthChoice.entries.forEach { mode -> FilterChip(c.authChoice == mode, { vm.edit { it.copy(authChoice = mode) } }, label = { Text(mode.name.replace('_', ' ')) }) } }
        when (c.authChoice) {
            AuthChoice.API_KEY, AuthChoice.CUSTOM -> {
                OutlinedTextField(c.authName, { v -> vm.edit { it.copy(authName = v) } }, label = { Text(if (c.authChoice == AuthChoice.API_KEY) "API key header" else "Authorization header") }, singleLine = true)
                SecretInput("Value", c.authValue, state.showSecret, { v -> vm.edit { it.copy(authValue = v) } }, { vm.showSecret(it) })
            }
            AuthChoice.BEARER -> SecretInput("Bearer token", c.authValue, state.showSecret, { v -> vm.edit { it.copy(authValue = v) } }, { vm.showSecret(it) })
            else -> Unit
        }
        if (c.authChoice == AuthChoice.API_KEY) Text("Per-request API keys are sent as headers by the current request API.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SecretInput(label: String, value: String, visible: Boolean, onValue: (String) -> Unit, onVisibility: (Boolean) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { onVisibility(!visible) }) { Text(if (visible) "Hide" else "Show") } }, modifier = Modifier.fillMaxWidth())
}

@Composable private fun BodyEditor(vm: RequestBuilderViewModel, state: RequestBuilderState, onPickFile: () -> Unit) {
    val c = state.config
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Request body", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { BodyChoice.entries.forEach { mode -> FilterChip(c.bodyChoice == mode, { vm.edit { it.copy(bodyChoice = mode) } }, label = { Text(mode.name.replace('_', ' ')) }) } }
        when (c.bodyChoice) {
            BodyChoice.NONE -> Text("This request has no body.", style = MaterialTheme.typography.bodySmall)
            BodyChoice.JSON -> {
                OutlinedTextField(c.jsonBody, { value -> vm.edit { it.copy(jsonBody = value) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), label = { Text("JSON") }, minLines = 8, isError = state.validation.jsonError != null, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                Row { OutlinedButton(onClick = vm::formatJson) { Text("Format") }; TextButton(onClick = { vm.edit { it.copy(jsonBody = "{\n  \n}") } }) { Text("Reset") } }
                state.validation.jsonError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            BodyChoice.FORM, BodyChoice.MULTIPART -> {
                if (c.bodyChoice == BodyChoice.MULTIPART) {
                    Text("Multipart text fields and file attachments are supported.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onPickFile) { Text("Choose file") }
                    c.files.forEach { file -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${file.fileName} · ${file.mimeType}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.edit { it.copy(files = it.files - file) } }) { Text("Remove") }
                    } }
                }
                if (c.bodyFields.isEmpty()) Text("No fields added", color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.bodyFields.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(row.enabled, { enabled -> vm.edit { s -> s.copy(bodyFields = s.bodyFields.map { if (it.id == row.id) it.copy(enabled = enabled) else it }) } })
                        OutlinedTextField(row.name, { v -> vm.edit { s -> s.copy(bodyFields = s.bodyFields.map { if (it.id == row.id) it.copy(name = v) else it }) } }, label = { Text("Name") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(row.value, { v -> vm.edit { s -> s.copy(bodyFields = s.bodyFields.map { if (it.id == row.id) it.copy(value = v) else it }) } }, label = { Text("Value") }, visualTransformation = if (RecordRedactor.isSensitive(row.name)) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.weight(1f), singleLine = true)
                        TextButton(onClick = { vm.edit { it.copy(bodyFields = it.bodyFields.filterNot { field -> field.id == row.id }) } }) { Text("Remove") }
                    }
                }
                TextButton(onClick = { vm.edit { it.copy(bodyFields = it.bodyFields + QueryEntry()) } }) { Text("Add field") }
            }
        }
        state.validation.bodyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun ResponseViewer(execution: RequestExecution, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val response = execution.response
    var rawMode by remember(execution.requestId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("Response", style = MaterialTheme.typography.titleLarge)
        if (response != null) {
            val success = response.isSuccessful
            Text("${if (success) "SUCCESS" else "HTTP ERROR"} · ${response.statusCode} · ${execution.durationMs} ms · ${response.body.toByteArray().size} bytes", color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!rawMode, { rawMode = false }, label = { Text("Formatted") })
                FilterChip(rawMode, { rawMode = true }, label = { Text("Raw") })
                TextButton(onClick = { clipboard.setText(AnnotatedString(response.body)) }) { Text("Copy body") }
            }
            if (response.body.isBlank()) Text("Empty response body", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(if (rawMode) response.body else formatResponse(response.body), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("Response headers", style = MaterialTheme.typography.titleMedium)
            response.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) -> Text("$key: $value", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text("${execution.errorType ?: "ERROR"} · ${execution.durationMs} ms", color = MaterialTheme.colorScheme.error)
            Text(execution.error.orEmpty())
        }
    }
}

private fun formatResponse(text: String): String = runCatching { org.json.JSONObject(text).toString(2) }.getOrElse { runCatching { org.json.JSONArray(text).toString(2) }.getOrDefault(text) }

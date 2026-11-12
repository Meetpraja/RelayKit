package com.relaykit.demo.core.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relaykit.demo.core.design.components.SectionHeading
import com.relaykit.demo.core.data.DemoViewModelFactory
import com.relaykit.demo.core.data.HistoryRecord
import com.relaykit.demo.feature.history.HistoryScreen
import com.relaykit.demo.feature.history.HistoryViewModel
import com.relaykit.demo.feature.logs.LogsScreen
import com.relaykit.demo.feature.logs.LogsViewModel
import com.relaykit.demo.feature.codegenerator.CodeGeneratorScreen
import com.relaykit.demo.feature.codegenerator.CodeGeneratorViewModel
import com.relaykit.demo.feature.request.toSanitizedRequestConfiguration
import com.relaykit.demo.feature.setup.SetupScreen
import com.relaykit.demo.feature.setup.SetupViewModel
import com.relaykit.demo.feature.request.RequestBuilderScreen
import com.relaykit.demo.feature.request.RequestBuilderViewModel
import androidx.compose.runtime.remember

@Composable
fun RelayKitApp(viewModel: AppViewModel = viewModel()) {
    val destination by viewModel.destination.collectAsState()
    val factory = remember(viewModel.data) { DemoViewModelFactory(viewModel.data) }
    val setupViewModel: SetupViewModel = viewModel(factory = factory)
    val requestViewModel: RequestBuilderViewModel = viewModel(factory = factory)
    val historyViewModel: HistoryViewModel = viewModel(factory = factory)
    val logsViewModel: LogsViewModel = viewModel(factory = factory)
    val generatorViewModel: CodeGeneratorViewModel = viewModel(factory = factory)
    val history by viewModel.data.history.state.collectAsStateWithLifecycle()
    val setupState by setupViewModel.state.collectAsStateWithLifecycle()
    val requestState by requestViewModel.state.collectAsStateWithLifecycle()
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    var moreOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)) {
                Spacer(Modifier.height(12.dp))
                Text("RELAYKIT", Modifier.padding(horizontal = 16.dp, vertical = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Destination.entries.forEach { item ->
                    NavigationRailItem(selected = item == destination, onClick = { if (item == Destination.GENERATOR) generatorViewModel.fromRequest(requestState.config); viewModel.navigate(item) }, icon = { Icon(item.icon, contentDescription = null) }, label = { Text(item.title, maxLines = 1) })
                }
            }
            if (destination == Destination.SETTINGS) SetupScreen(setupViewModel, Modifier.weight(1f).fillMaxHeight())
            else if (destination == Destination.REQUEST) RequestBuilderScreen(requestViewModel, setupState.activeBaseUrl, setupState.initialized, onGenerateCode = { generatorViewModel.fromRequest(requestState.config); viewModel.navigate(Destination.GENERATOR) }, modifier = Modifier.weight(1f).fillMaxHeight())
            else when (destination) {
                Destination.HISTORY -> HistoryScreen(historyViewModel, { requestViewModel.loadFromHistory(it); viewModel.navigate(Destination.REQUEST) }, { generatorViewModel.fromHistory(it.toSanitizedRequestConfiguration(), "History · ${it.method} ${it.endpoint}"); viewModel.navigate(Destination.GENERATOR) }, Modifier.weight(1f).fillMaxHeight())
                Destination.LOGS -> LogsScreen(logsViewModel, Modifier.weight(1f).fillMaxHeight())
                Destination.GENERATOR -> CodeGeneratorScreen(generatorViewModel, setupState, { generatorViewModel.fromRequest(requestState.config) }, Modifier.weight(1f).fillMaxHeight())
                else -> ScreenContent(destination, setupViewModel, { viewModel.navigate(Destination.SETTINGS) }, history.records, { viewModel.navigate(Destination.HISTORY) }, Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        Scaffold(bottomBar = {
            Box {
                NavigationBar(windowInsets = WindowInsets.navigationBars) {
                    listOf(Destination.WORKSPACE, Destination.REQUEST, Destination.HISTORY).forEach { item ->
                        NavigationBarItem(selected = item == destination, onClick = { viewModel.navigate(item) }, icon = { Icon(item.icon, contentDescription = null) }, label = { Text(item.shortTitle(), maxLines = 1, softWrap = false) })
                    }
                    NavigationBarItem(selected = destination in listOf(Destination.LOGS, Destination.GENERATOR, Destination.SETTINGS), onClick = { moreOpen = true }, icon = { Icon(Icons.Outlined.MoreHoriz, contentDescription = null) }, label = { Text("More", maxLines = 1) })
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    listOf(Destination.LOGS, Destination.GENERATOR, Destination.SETTINGS).forEach { item ->
                    DropdownMenuItem(text = { Text(item.title) }, leadingIcon = { Icon(item.icon, null) }, onClick = { moreOpen = false; if (item == Destination.GENERATOR) generatorViewModel.fromRequest(requestState.config); viewModel.navigate(item) })
                    }
                }
            }
        }) { padding ->
            if (destination == Destination.SETTINGS) SetupScreen(setupViewModel, Modifier.fillMaxSize().padding(padding))
            else if (destination == Destination.REQUEST) RequestBuilderScreen(requestViewModel, setupState.activeBaseUrl, setupState.initialized, onGenerateCode = { generatorViewModel.fromRequest(requestState.config); viewModel.navigate(Destination.GENERATOR) }, modifier = Modifier.fillMaxSize().padding(padding))
            else when (destination) {
                Destination.HISTORY -> HistoryScreen(historyViewModel, { requestViewModel.loadFromHistory(it); viewModel.navigate(Destination.REQUEST) }, { generatorViewModel.fromHistory(it.toSanitizedRequestConfiguration(), "History · ${it.method} ${it.endpoint}"); viewModel.navigate(Destination.GENERATOR) }, Modifier.fillMaxSize().padding(padding))
                Destination.LOGS -> LogsScreen(logsViewModel, Modifier.fillMaxSize().padding(padding))
                Destination.GENERATOR -> CodeGeneratorScreen(generatorViewModel, setupState, { generatorViewModel.fromRequest(requestState.config) }, Modifier.fillMaxSize().padding(padding))
                else -> ScreenContent(destination, setupViewModel, { viewModel.navigate(Destination.SETTINGS) }, history.records, { viewModel.navigate(Destination.HISTORY) }, Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}

@Composable private fun ScreenContent(destination: Destination, setupViewModel: SetupViewModel, onConfigure: () -> Unit, recentHistory: List<HistoryRecord>, onOpenHistory: () -> Unit, modifier: Modifier = Modifier) {
    val setup by setupViewModel.state.collectAsState()
    Column(modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeading(destination.title, destination.caption)
        HorizontalDivider()
        when (destination) {
            Destination.WORKSPACE -> {
                Text("Your API workspace", style = MaterialTheme.typography.titleLarge)
                Text("Build requests with RelayKit, inspect responses, and turn working calls into Kotlin source.", style = MaterialTheme.typography.bodyLarge)
                if (setup.initialized) {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("CLIENT ACTIVE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(setup.activeBaseUrl.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text("Timeouts · connect ${setup.activeConnectSeconds}s · read ${setup.activeReadSeconds}s · write ${setup.activeWriteSeconds}s")
                            Text("Logging · ${setup.activeLogLevel}     Authentication · ${setup.activeAuth}")
                            setup.latestLog?.let { Text("Latest diagnostic: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                } else EmptyPanel(Icons.Outlined.Send, "RelayKit is not initialized", "Configure a base URL and client settings before building API requests.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onConfigure) { Text("Configure RelayKit") }
                    if (setup.initialized) OutlinedButton(onClick = setupViewModel::reset) { Text("Reset client") }
                }
                HorizontalDivider()
                Text("Recent requests", style = MaterialTheme.typography.titleMedium)
                if (recentHistory.isEmpty()) Text("No requests have been made in this workspace yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recentHistory.take(4).forEach { record ->
                        Surface(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(record.method.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(record.endpoint, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                Text(record.responseStatus?.toString() ?: record.outcome, color = if (record.success == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    TextButton(onClick = onOpenHistory) { Text("View all history") }
                }
            }
            Destination.REQUEST -> EmptyPanel(Icons.Outlined.Tune, "No request open", "Create a request to set its method, URL, headers, parameters, and body.")
            Destination.HISTORY, Destination.LOGS -> Unit
            Destination.GENERATOR -> EmptyPanel(Icons.Outlined.Code, "No source to generate", "Configure a request and response example to prepare reusable Kotlin files.")
            Destination.SETTINGS -> EmptyPanel(Icons.Outlined.Settings, "Workspace settings", "Application preferences are not part of this configuration phase.")
        }
    }
}

@Composable private fun EmptyPanel(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun Destination.shortTitle(): String = when (this) {
    Destination.WORKSPACE -> "Workspace"
    Destination.HISTORY -> "History"
    else -> title
}

private val Destination.icon get() = when (this) {
    Destination.WORKSPACE -> Icons.Outlined.Home
    Destination.REQUEST -> Icons.Outlined.Send
    Destination.HISTORY -> Icons.Outlined.History
    Destination.LOGS -> Icons.Outlined.ReceiptLong
    Destination.GENERATOR -> Icons.Outlined.Code
    Destination.SETTINGS -> Icons.Outlined.Settings
}


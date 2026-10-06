package dev.sshdeck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.sshdeck.core.QuickCommand
import dev.sshdeck.core.Server
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeckScreen(
    server: Server,
    runs: RunManager,
    onBack: () -> Unit,
    onOpenRun: (Run) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var command by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<QuickCommand?>(null) }

    fun runQuick(cmd: QuickCommand) {
        try {
            onOpenRun(runs.startQuickCommand(server, cmd))
        } catch (e: FileNotFoundException) {
            scope.launch { snackbar.showSnackbar("Script not found: ${e.message}") }
        }
    }

    fun sendCommand() {
        val text = command.trim()
        if (text.isEmpty()) return
        command = ""
        onOpenRun(runs.startCommand(server, text))
    }

    pending?.let { cmd ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Run command?") },
            text = { Text("Run \"${cmd.label}\" on ${server.name}?") },
            confirmButton = {
                TextButton(onClick = { pending = null; runQuick(cmd) }) { Text("Run") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.name)
                        Text(
                            "${server.username}@${server.host}:${server.port}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Command") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendCommand() }),
                    )
                    Button(onClick = ::sendCommand, enabled = command.isNotBlank()) { Text("Send") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (server.commands.isEmpty()) {
                item { Text("No quick commands configured for this server.") }
            }
            for ((project, cmds) in server.commands.groupBy { it.project }) {
                item(key = "project-$project") {
                    Text(project, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                item(key = "buttons-$project") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (cmd in cmds) CommandButton(cmd) { pending = cmd }
                    }
                }
            }

            val recent = runs.runsFor(server.name)
            if (recent.isNotEmpty()) {
                item(key = "recent") {
                    Text("Recent runs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                }
                items(recent, key = { "run-${it.id}" }) { run ->
                    RunRow(run) { onOpenRun(run) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CommandButton(cmd: QuickCommand, onClick: () -> Unit) {
    val bg = cmd.color?.let(::parseColor)
    val colors = if (bg != null) {
        ButtonDefaults.buttonColors(containerColor = bg, contentColor = if (bg.luminance() > 0.5f) Color.Black else Color.White)
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(onClick = onClick, colors = colors, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Text(cmd.label)
    }
}

@Composable
private fun RunRow(run: Run, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBadge(run.status)
        Text(run.title.lineSequence().first(), modifier = Modifier.weight(1f), maxLines = 1)
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(run.startedAt)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun StatusBadge(status: RunStatus) {
    val (label, color) = when (status) {
        RunStatus.RUNNING -> "Running" to MaterialTheme.colorScheme.primary
        RunStatus.SUCCEEDED -> "OK" to Color(0xFF2E7D32)
        RunStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        RunStatus.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.outline
    }
    Text(label, color = color, style = MaterialTheme.typography.labelMedium)
}

/** Tk colours from the desktop: `#rrggbb` and the common names Android knows. */
private fun parseColor(value: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()

package dev.sshdeck

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sshdeck.core.ConfigException
import dev.sshdeck.core.ConfigStore
import dev.sshdeck.core.ParsedConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Paste or load a config exported by the desktop's Copy Config (or this app), then
 * confirm before overwriting. [onImport] receives the validated config.
 */
@Composable
fun ImportDialog(
    store: ConfigStore,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onImport: (ParsedConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ParsedConfig?>(null) }

    // Pre-fill from the clipboard when it already holds an export.
    LaunchedEffect(Unit) {
        val clip = clipboardText(context) ?: return@LaunchedEffect
        if (runCatching { store.parseConfig(clip) }.isSuccess) text = clip
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readUri(context, uri) } }
                .onSuccess { text = it; error = null }
                .onFailure { error = "Could not open file: ${it.message}" }
        }
    }

    val config = pending
    if (config != null) {
        ConfirmImportDialog(config, existingNames, onDismiss = { pending = null }, onConfirm = { onImport(config) })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import config") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a config exported with Copy Config, or load it from a file.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Load file") }
                    OutlinedButton(onClick = {
                        clipboardText(context)?.let { text = it; error = null }
                            ?: run { error = "The clipboard is empty." }
                    }) { Text("Paste") }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    placeholder = { Text("{\"format\": \"ssh-deck-config\", ...}") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    try {
                        pending = store.parseConfig(text)
                    } catch (e: ConfigException) {
                        error = e.message
                    }
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmImportDialog(
    config: ParsedConfig,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val replaced = config.servers.map { it.name }.filter { it in existingNames }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm import") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Import ${config.servers.size} server(s) and ${config.scripts.size} script file(s)?")
                if (replaced.isNotEmpty()) {
                    Text("These servers will be overwritten:\n" + replaced.joinToString("\n") { "  $it" })
                }
                if (config.scripts.isNotEmpty()) Text("Script files with the same path will be overwritten.")
                Text("SSH keys are not part of the export; each server's key is added on this device.")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val item = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return item.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}

private fun readUri(context: Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        ?: error("no data")

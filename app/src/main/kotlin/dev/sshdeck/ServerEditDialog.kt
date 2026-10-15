package dev.sshdeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.sshdeck.core.Server

/**
 * Edit a server's connection (host, port, user) on the phone, e.g. to switch to a
 * Tailscale address. Commands stay desktop-owned; a later import overwrites this.
 */
@Composable
fun ServerEditDialog(server: Server, onDismiss: () -> Unit, onSave: (Server) -> Unit) {
    var host by remember { mutableStateOf(server.host) }
    var port by remember { mutableStateOf(server.port.toString()) }
    var username by remember { mutableStateOf(server.username) }

    val portValue = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = host.isNotBlank() && username.isNotBlank() && portValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${server.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host or IP") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = host.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = portValue == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    isError = username.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Importing this server from the desktop again replaces these values.")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(server.copy(host = host.trim(), port = portValue!!, username = username.trim()))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

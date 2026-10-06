package dev.sshdeck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shows this device's public key for the servers' authorized_keys. */
@Composable
fun DeviceKeyDialog(publicKeyLine: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "SSH Deck logs in with this key, generated on this phone. Add the line below " +
                        "to ~/.ssh/authorized_keys on each server (on Windows admin accounts: " +
                        "C:\\ProgramData\\ssh\\administrators_authorized_keys)."
                )
                SelectionContainer {
                    Text(publicKeyLine, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("SSH Deck public key", publicKeyLine))
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicKeyLine)
                context.startActivity(Intent.createChooser(send, "Share public key"))
            }) { Text("Share") }
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

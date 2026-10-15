package dev.sshdeck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Shows the "commands running" notification of RunService; runs work without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val app = application as SshDeckApp
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SshDeckUi(app)
            }
        }
    }
}

private sealed interface Screen {
    data object Servers : Screen
    data class Deck(val serverName: String) : Screen
    data class RunOutput(val runId: Long) : Screen
}

@Composable
private fun SshDeckUi(app: SshDeckApp) {
    var loaded by remember { mutableStateOf(app.store.loadServers()) }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Servers)) }
    var showDeviceKey by remember { mutableStateOf(false) }

    fun push(screen: Screen) { stack = stack + screen }
    fun pop() { stack = stack.dropLast(1) }
    /** Opening a run from a run (Again / retry) replaces it rather than stacking. */
    fun openRun(run: Run) {
        val base = if (stack.last() is Screen.RunOutput) stack.dropLast(1) else stack
        stack = base + Screen.RunOutput(run.id)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    if (showDeviceKey) {
        DeviceKeyDialog(app.deviceKey.authorizedKeysLine(), onDismiss = { showDeviceKey = false })
    }

    when (val screen = stack.last()) {
        Screen.Servers -> ServerListScreen(
            store = app.store,
            loaded = loaded,
            onReload = { loaded = it },
            onOpenServer = { push(Screen.Deck(it.name)) },
            onShowDeviceKey = { showDeviceKey = true },
        )
        is Screen.Deck -> {
            val server = loaded.servers.firstOrNull { it.name == screen.serverName }
            if (server == null) {
                pop()
            } else {
                DeckScreen(
                    server, app.runs, onBack = ::pop, onOpenRun = ::openRun,
                    onSaveServer = { updated ->
                        // Name is unchanged, so this rewrites server.json in place.
                        app.store.saveServer(updated)
                        loaded = app.store.loadServers()
                    },
                )
            }
        }
        is Screen.RunOutput -> {
            val run = app.runs.find(screen.runId)
            if (run == null) pop() else RunScreen(run, app.runs, onBack = ::pop, onOpenRun = ::openRun)
        }
    }
}

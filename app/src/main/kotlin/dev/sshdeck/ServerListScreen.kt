package dev.sshdeck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sshdeck.core.ConfigStore
import dev.sshdeck.core.LoadResult
import dev.sshdeck.core.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    store: ConfigStore,
    loaded: LoadResult,
    onReload: (LoadResult) -> Unit,
    onOpenServer: (Server) -> Unit,
    onShowDeviceKey: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }

    if (showImport) {
        ImportDialog(
            store = store,
            existingNames = loaded.servers.map { it.name }.toSet(),
            onDismiss = { showImport = false },
            onImport = { config ->
                showImport = false
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            store.applyImport(loaded.servers, config)
                            store.loadServers()
                        }
                    }
                    result.onSuccess(onReload)
                    snackbar.showSnackbar(
                        result.fold(
                            { "Imported ${config.servers.size} server(s)." },
                            { "Import failed: ${it.message}" },
                        )
                    )
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH Deck") },
                actions = {
                    TextButton(onClick = onShowDeviceKey) { Text("Device key") }
                    TextButton(onClick = { showImport = true }) { Text("Import") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loaded.servers.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No servers yet. Import a config from the desktop app to get started.")
                        Button(onClick = { showImport = true }) { Text("Import config") }
                    }
                }
            }
            items(loaded.servers, key = { it.name }) { server ->
                Card(onClick = { onOpenServer(server) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp),
                    )
                    Text(
                        "${server.username}@${server.host}:${server.port} · ${server.commands.size} commands",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 12.dp),
                    )
                }
            }
            items(loaded.errors) { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

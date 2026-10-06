package dev.sshdeck

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sshdeck.core.OutputTag

// The desktop's output colours.
private val OutputBackground = Color(0xFF1E1E1E)
private val TagColors = mapOf(
    OutputTag.OUTPUT to Color(0xFF4EC9B0),
    OutputTag.ERROR to Color(0xFFF44747),
    OutputTag.CMD to Color(0xFF569CD6),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(run: Run, runs: RunManager, onBack: () -> Unit, onOpenRun: (Run) -> Unit) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val lineCount = run.lines.size

    // Follow new output until the user scrolls up; scrolling back to the bottom resumes it.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(lineCount) {
        if (follow && lineCount > 0) listState.scrollToItem(lineCount - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(run.title.lineSequence().first(), maxLines = 1)
                        Row { StatusBadge(run.status); Text("  ${run.server.name}", style = MaterialTheme.typography.labelMedium) }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (run.status == RunStatus.RUNNING) {
                        TextButton(onClick = run::cancel) { Text("Cancel") }
                    } else {
                        TextButton(onClick = { onOpenRun(run.restart()) }) { Text("Again") }
                    }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "${run.server.name} — ${run.title.lineSequence().first()}")
                            .putExtra(Intent.EXTRA_TEXT, run.logText())
                        context.startActivity(Intent.createChooser(send, "Share log"))
                    }) { Text("Share") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (run.hostKeyChanged) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        "The server's host key changed. Only continue if you know why " +
                            "(e.g. the server was reinstalled); otherwise this may be an attack.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { onOpenRun(runs.trustNewHostKeyAndRetry(run)) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Trust new key and retry")
                    }
                }
            }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(OutputBackground),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    itemsIndexed(run.lines) { _, line ->
                        Text(
                            line.text.removeSuffix("\n"),
                            color = TagColors[line.tag] ?: Color(0xFFD4D4D4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

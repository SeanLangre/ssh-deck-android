package dev.sshdeck

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sshdeck.core.Cancellation
import dev.sshdeck.core.DownloadDirective
import dev.sshdeck.core.OutputSink
import dev.sshdeck.core.OutputTag
import dev.sshdeck.core.QuickCommand
import dev.sshdeck.core.Server
import kotlin.concurrent.thread

enum class RunStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class OutputLine(val tag: OutputTag, val text: String)

/** One command or download, with its output. State is only changed on the main thread. */
class Run(
    val id: Long,
    val server: Server,
    val title: String,
    val startedAt: Long,
    /** Starts the same command again as a new run. */
    val restart: () -> Run,
) {
    val lines = mutableStateListOf<OutputLine>()
    var status by mutableStateOf(RunStatus.RUNNING)
        internal set
    internal val cancellation = Cancellation()

    fun cancel() = cancellation.cancel()

    val hostKeyChanged get() = status == RunStatus.FAILED && lines.lastOrNull()?.text?.contains("Host key for ") == true

    fun logText(): String = lines.joinToString("") { it.text }
}

/**
 * Starts runs on background threads and keeps them in memory while the app process lives,
 * so leaving a run's screen doesn't stop it.
 */
class RunManager(private val app: SshDeckApp) {
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L
    val runs = mutableStateListOf<Run>()

    fun runsFor(serverName: String) = runs.filter { it.server.name == serverName }.asReversed()

    fun find(id: Long) = runs.firstOrNull { it.id == id }

    /** Runs a quick command's script, or its download. Throws if the script file is missing. */
    fun startQuickCommand(server: Server, cmd: QuickCommand): Run {
        val content = app.store.loadScriptContent(server.name, cmd.project, cmd.script)
        val download = DownloadDirective.parse(content)
        return start(server, cmd.label) { run, sink ->
            if (download != null) {
                app.runner.download(server, download.remote, DownloadsSink(app, download.local), sink, run.cancellation)
            } else {
                app.runner.runCommand(server, content, sink, run.cancellation)
            }
        }
    }

    fun startCommand(server: Server, command: String): Run =
        start(server, command) { run, sink -> app.runner.runCommand(server, command, sink, run.cancellation) }

    /** Forgets the server's old host key (it was changed on purpose) and runs again. */
    fun trustNewHostKeyAndRetry(run: Run): Run {
        app.runner.forgetHostKey(run.server)
        return run.restart()
    }

    private fun start(server: Server, title: String, body: (Run, OutputSink) -> Unit): Run {
        val run = Run(nextId++, server, title, System.currentTimeMillis()) { start(server, title, body) }
        runs += run
        var lastTag: OutputTag? = null
        val sink = OutputSink { tag, text ->
            lastTag = tag
            main.post { append(run, OutputLine(tag, text)) }
        }
        thread(name = "run-${run.id}", isDaemon = true) {
            body(run, sink)
            val status = when {
                run.cancellation.isCancelled -> RunStatus.CANCELLED
                lastTag == OutputTag.ERROR -> RunStatus.FAILED
                else -> RunStatus.SUCCEEDED
            }
            main.post { run.status = status }
        }
        return run
    }

    private fun append(run: Run, line: OutputLine) {
        // Suppress consecutive blank lines, like the desktop's output panel.
        if (line.text.isBlank() && run.lines.lastOrNull()?.text?.isBlank() == true) return
        run.lines += line
    }
}

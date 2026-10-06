package dev.sshdeck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class ConfigStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = ConfigStore(tmp.root.resolve("scripts").apply { mkdirs() })

    private val server = Server(
        name = "box", host = "h", username = "u", keyPath = "/k",
        // Listed in load order: commands come back grouped by project folder, sorted.
        commands = listOf(
            QuickCommand("B", "b.sh", "#222222"),
            QuickCommand("A", "a.sh", "#111111", project = "P1"),
        ),
    )

    @Test fun saveRemovesStaleProjectCommandFiles() {
        val store = store()
        store.saveServer(server)
        assertTrue(File(store.scriptsDir, "box/P1/commands.json").isFile)

        store.saveServer(server.copy(commands = server.commands.take(1)))
        assertFalse(File(store.scriptsDir, "box/P1/commands.json").exists())
        assertEquals(listOf("B"), store.loadServers().servers.single().commands.map { it.label })
    }

    @Test fun brokenFilesAreReportedNotFatal() {
        val store = store()
        store.saveServer(server)
        store.saveServer(server.copy(name = "other"))
        File(store.scriptsDir, "box/server.json").writeText("{not json")
        File(store.scriptsDir, "other/P1/commands.json").writeText("""[{"label": "no script"}, {"label": "ok", "script": "ok.sh"}]""")

        val (servers, errors) = store.loadServers()
        assertEquals(listOf("other"), servers.map { it.name })
        assertEquals(listOf("B", "ok"), servers.single().commands.map { it.label })
        assertEquals(2, errors.size)
        assertTrue(errors[0], errors[0].startsWith("box/server.json: "))
        assertTrue(errors[1], errors[1].startsWith("other/P1/commands.json: skipped malformed command"))
    }

    @Test fun exportImportRoundTrip() {
        val src = store()
        src.saveServer(server)
        File(src.scriptsDir, "box/P1/a.sh").writeText("echo a\n")
        File(src.scriptsDir, "_shared/s.sh").apply { parentFile.mkdirs() }.writeText("echo ü\n")
        File(src.scriptsDir, "box/ALL/blob.bin").writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
        val text = src.exportConfig(src.loadServers().servers)

        val dst = ConfigStore(tmp.newFolder("dst"))
        val parsed = dst.parseConfig(text)
        assertEquals(setOf("box/P1/a.sh", "_shared/s.sh"), parsed.scripts.keys)
        dst.applyImport(emptyList(), parsed)
        assertEquals(src.loadServers(), dst.loadServers())
        assertEquals("echo ü\n", File(dst.scriptsDir, "_shared/s.sh").readText())
    }

    @Test fun importReplacesServersWithSameName() {
        val store = store()
        val existing = listOf(server, server.copy(name = "keep"))
        store.saveServers(existing)
        val text = store.exportConfig(listOf(server.copy(host = "new-host")))

        val merged = store.applyImport(existing, store.parseConfig(text))
        assertEquals(listOf("new-host", "h"), merged.map { it.host })
        assertEquals(merged, store.loadServers().servers)
    }

    @Test fun parseRejectsBadConfigs() {
        val store = store()
        fun rejects(text: String, message: String) =
            assertEquals(message, assertThrows(ConfigException::class.java) { store.parseConfig(text) }.message)

        rejects("""{"format": "other"}""", "This is not an SSH Deck config export.")
        rejects("""[1]""", "This is not an SSH Deck config export.")
        rejects("""{"format": "ssh-deck-config", "servers": {}}""", "Config is malformed.")
        rejects("""{"format": "ssh-deck-config", "servers": [{"host": "x"}]}""", "Every server needs a name.")
        for (bad in listOf("../evil", "/etc", "a/../..", "")) {
            val json = """{"format": "ssh-gui-config", "scripts": {"$bad": "x"}}"""
            rejects(json, "Unsafe path in config: $bad")
        }
        assertTrue(store.parseConfig("""{"format": "ssh-gui-config"}""").servers.isEmpty())
    }

    @Test fun scriptLookupFallsBackToSharedFolders() {
        val store = store()
        File(store.scriptsDir, "_shared/x.sh").apply { parentFile.mkdirs() }.writeText("global \$PROJECT")
        assertEquals("PROJECT=my-app\nglobal \$PROJECT", store.loadScriptContent("box", "My-App", "x.sh"))

        File(store.scriptsDir, "box/_shared/x.sh").apply { parentFile.mkdirs() }.writeText("server")
        assertEquals("PROJECT=all\nserver", store.loadScriptContent("box", "ALL", "x.sh"))

        File(store.scriptsDir, "box/P/x.sh").apply { parentFile.mkdirs() }.writeText(" own \n")
        assertEquals("own", store.loadScriptContent("box", "P", "x.sh"))

        val missing = assertThrows(FileNotFoundException::class.java) { store.loadScriptContent("box", null, "nope.sh") }
        assertEquals("scripts/box/ALL/nope.sh", missing.message)
    }

    @Test fun renameAndDelete() {
        val store = store()
        store.saveServer(server)
        store.renameServer("box", "box2")
        store.saveServer(server.copy(name = "box2"))
        assertEquals(listOf("box2"), store.loadServers().servers.map { it.name })

        store.deleteServer("box2")
        assertTrue(store.loadServers().servers.isEmpty())
    }

    @Test fun execCommandWrapsInLoginShell() {
        assertEquals("bash -l -c 'echo '\\''hi'\\'''", buildExecCommand("echo 'hi'", loginShell = true))
        assertEquals("dir C:\\", buildExecCommand("dir C:\\", loginShell = false))
        assertEquals("1m05s", formatElapsed(65))
        assertEquals("9s", formatElapsed(9))
    }
}

package dev.sshdeck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Checks against fixtures produced by the desktop app itself (tools/gen_fixtures.py). */
class DesktopCompatTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixtures = File(javaClass.getResource("/fixtures")!!.toURI())
    private val desktopScripts = File(fixtures, "scripts")
    private val golden = Json.parseToJsonElement(File(fixtures, "golden.json").readText()).jsonObject

    private fun copyOfDesktopScripts() = tmp.newFolder("scripts").also { desktopScripts.copyRecursively(it) }

    private fun relFiles(dir: File) =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    @Test fun loadsDesktopConfig() {
        val (servers, errors) = ConfigStore(desktopScripts).loadServers()
        assertEquals(emptyList<String>(), errors)
        assertEquals(listOf("linux-box", "win-pc"), servers.map { it.name })

        val win = servers[1]
        assertEquals(2222, win.port)
        assertEquals(false, win.loginShell)
        assertEquals("~/.ssh/win \"key\"", win.keyPath)

        val deploy = servers[0].commands.single { it.script == "deploiement.sh" }
        assertEquals("Déploiement ✓", deploy.label)
        assertEquals("AM", deploy.project)
        assertEquals(Json.parseToJsonElement("true"), deploy.extra["confirm"])
    }

    @Test fun saveWritesByteIdenticalFiles() {
        val servers = ConfigStore(desktopScripts).loadServers().servers
        val out = tmp.newFolder("out")
        ConfigStore(out).saveServers(servers)

        val written = relFiles(out)
        assertEquals(relFiles(desktopScripts).filter { it.endsWith(".json") }, written)
        for (rel in written) {
            assertEquals(rel, File(desktopScripts, rel).readText(), File(out, rel).readText())
        }
    }

    @Test fun exportMatchesDesktopExport() {
        val store = ConfigStore(desktopScripts)
        val exported = store.exportConfig(store.loadServers().servers)
        // Script key order follows the desktop's os.walk order, so compare as JSON values.
        assertEquals(
            Json.parseToJsonElement(File(fixtures, "export.json").readText()),
            Json.parseToJsonElement(exported),
        )
    }

    @Test fun importingDesktopExportRebuildsSameTree() {
        val out = tmp.newFolder("imported")
        val store = ConfigStore(out)
        store.applyImport(emptyList(), store.parseConfig(File(fixtures, "export.json").readText()))

        assertEquals(relFiles(desktopScripts), relFiles(out))
        for (rel in relFiles(out)) {
            assertEquals(rel, File(desktopScripts, rel).readText(), File(out, rel).readText())
        }
    }

    @Test fun scriptContentMatchesDesktop() {
        val store = ConfigStore(copyOfDesktopScripts())
        for ((key, expected) in golden["load_script_content"]!!.jsonObject) {
            val (server, project, script) = key.split("|")
            assertEquals(key, expected.jsonPrimitive.content, store.loadScriptContent(server, project, script))
        }
    }

    @Test fun downloadDirectivesMatchDesktop() {
        for (case in golden["parse_download_directive"]!!.jsonArray) {
            val (input, expected) = case.jsonArray
            val actual = DownloadDirective.parse(input.jsonPrimitive.content)
            if (expected is JsonNull) {
                assertEquals(input.toString(), null, actual)
            } else {
                val obj = expected as JsonObject
                assertEquals(
                    input.toString(),
                    DownloadDirective(obj["remote"]!!.jsonPrimitive.content, obj["local"]!!.jsonPrimitive.content),
                    actual,
                )
            }
        }
    }

    @Test fun textHelpersMatchDesktop() {
        for ((input, expected) in golden["label_to_script_name"]!!.jsonObject) {
            assertEquals(input, expected.jsonPrimitive.content, labelToScriptName(input))
        }
        for ((input, expected) in golden["to_sftp_path"]!!.jsonObject) {
            assertEquals(input, expected.jsonPrimitive.content, toSftpPath(input))
        }
        for (case in golden["strip_ansi"]!!.jsonArray) {
            val (input, expected) = (case as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(input, expected, stripAnsi(input))
        }
        for (case in golden["shlex_quote"]!!.jsonArray) {
            val (input, expected) = (case as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(input, expected, Shlex.quote(input))
            assertTrue(input, Shlex.split(expected).joinToString("") == input)
        }
    }
}

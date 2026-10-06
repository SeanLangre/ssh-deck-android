package dev.sshdeck.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class ConfigException(message: String) : Exception(message)

data class LoadResult(val servers: List<Server>, val errors: List<String>)

data class ParsedConfig(val servers: List<Server>, val scripts: Map<String, String>)

/**
 * The scripts folder, laid out exactly like the desktop app's:
 *
 *     scripts/_shared/                    scripts shared by every server
 *     scripts/<server>/server.json        connection fields
 *     scripts/<server>/_shared/           scripts shared by this server's projects
 *     scripts/<server>/<project>/commands.json + its scripts
 */
class ConfigStore(val scriptsDir: File) {

    // ---------- Server persistence ----------

    /** A broken file is skipped and reported, not fatal. */
    fun loadServers(): LoadResult {
        val servers = mutableListOf<Server>()
        val errors = mutableListOf<String>()
        for (name in scriptsDir.list().orEmpty().sorted()) {
            val serverDir = File(scriptsDir, name)
            val metaFile = File(serverDir, SERVER_META_FILENAME)
            if (!metaFile.isFile) continue
            val meta = try {
                readJson(metaFile) as? JsonObject ?: throw ConfigException("expected a JSON dict")
            } catch (e: Exception) {
                errors += "${rel(metaFile)}: ${e.message}"
                continue
            }

            val commands = mutableListOf<QuickCommand>()
            for (project in serverDir.list().orEmpty().sorted()) {
                val cmdsFile = File(serverDir, "$project/$COMMANDS_FILENAME")
                if (!cmdsFile.isFile) continue
                val cmds = try {
                    readJson(cmdsFile) as? JsonArray ?: throw ConfigException("expected a JSON list")
                } catch (e: Exception) {
                    errors += "${rel(cmdsFile)}: ${e.message}"
                    continue
                }
                for (entry in cmds) {
                    val cmd = QuickCommand.fromJson(entry, project)
                    if (cmd == null) errors += "${rel(cmdsFile)}: skipped malformed command $entry"
                    else commands += cmd
                }
            }
            servers += Server.fromJson(meta, name, commands)
        }
        return LoadResult(servers, errors)
    }

    /** Write one server's split config, replacing its stale command files. */
    fun saveServer(server: Server) {
        val serverDir = safePath(server.name)
        serverDir.mkdirs()
        File(serverDir, SERVER_META_FILENAME).writeText(PyJson.dumps(server.toMetaJson()))

        val groups = server.commands.groupBy { it.project.ifEmpty { DEFAULT_PROJECT } }
        for (project in serverDir.list().orEmpty()) {
            val cmdsFile = File(serverDir, "$project/$COMMANDS_FILENAME")
            if (cmdsFile.isFile && project !in groups) cmdsFile.delete()
        }
        for ((project, cmds) in groups) {
            val projDir = File(serverDir, project).apply { mkdirs() }
            File(projDir, COMMANDS_FILENAME).writeText(PyJson.dumps(JsonArray(cmds.map { it.toStoredJson() })))
        }
    }

    fun saveServers(servers: List<Server>) = servers.forEach(::saveServer)

    /** Moves the scripts folder; call before saving the server under its new name. */
    fun renameServer(oldName: String, newName: String) {
        val from = safePath(oldName)
        val to = safePath(newName)
        if (!from.isDirectory) return
        if (to.exists()) throw IOException("A folder named '$newName' already exists")
        if (!from.renameTo(to)) throw IOException("Could not rename scripts folder to '$newName'")
    }

    /** Config lives in the server's folder, so this also deletes its scripts. */
    fun deleteServer(name: String) {
        safePath(name).deleteRecursively()
    }

    // ---------- Config export / import ----------

    /** Every server plus the text of every script, so importing rebuilds the same setup. */
    fun exportConfig(servers: List<Server>): String {
        val scripts = linkedMapOf<String, JsonElement>()
        scriptsDir.walkTopDown()
            .filter { it.isFile && it.name != SERVER_META_FILENAME && it.name != COMMANDS_FILENAME }
            .sortedBy { rel(it) }
            .forEach { file ->
                // Skip binary/unreadable files, as the desktop does.
                val text = runCatching { readUtf8Strict(file) }.getOrNull() ?: return@forEach
                scripts[rel(file)] = JsonPrimitive(text)
            }
        return PyJson.dumps(JsonObject(linkedMapOf(
            "format" to JsonPrimitive(EXPORT_FORMAT),
            "version" to JsonPrimitive(EXPORT_VERSION),
            "servers" to JsonArray(servers.map { it.toExportJson() }),
            "scripts" to JsonObject(scripts),
        )))
    }

    /** Validate an exported config without touching disk. */
    fun parseConfig(text: String): ParsedConfig {
        val data = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw ConfigException("Not valid JSON: ${e.message}")
        }
        val format = ((data as? JsonObject)?.get("format") as? JsonPrimitive)?.content
        if (data !is JsonObject || format !in ACCEPTED_FORMATS) {
            throw ConfigException("This is not an SSH Deck config export.")
        }
        val servers = data["servers"] ?: JsonArray(emptyList())
        val scripts = data["scripts"] ?: JsonObject(emptyMap())
        if (servers !is JsonArray || scripts !is JsonObject) throw ConfigException("Config is malformed.")

        val parsedServers = servers.map { entry ->
            val obj = entry as? JsonObject
            val name = obj?.string("name")
            if (obj == null || name.isNullOrEmpty()) throw ConfigException("Every server needs a name.")
            safePath(name)
            val cmds = (obj["commands"] as? JsonArray).orEmpty().map {
                QuickCommand.fromJson(it, project = null)
                    ?: throw ConfigException("Server '$name' has a malformed command: $it")
            }
            Server.fromJson(obj, name, cmds)
        }
        val parsedScripts = scripts.mapValues { (rel, content) ->
            safePath(rel)
            (content as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw ConfigException("Script '$rel' is not text.")
        }
        return ParsedConfig(parsedServers, parsedScripts)
    }

    /**
     * Write the imported scripts and servers; servers with matching names are replaced.
     * Returns the merged server list.
     */
    fun applyImport(current: List<Server>, config: ParsedConfig): List<Server> {
        for ((rel, content) in config.scripts) {
            val file = safePath(rel)
            file.parentFile.mkdirs()
            file.writeText(content)
        }
        val merged = current.toMutableList()
        for (server in config.servers) {
            val idx = merged.indexOfFirst { it.name == server.name }
            if (idx < 0) merged += server else merged[idx] = server
        }
        saveServers(merged)
        return merged
    }

    /** Resolve [rel] under the scripts folder, refusing anything that escapes it. */
    fun safePath(rel: String): File {
        val root = scriptsDir.canonicalFile
        val path = File(root, rel).canonicalFile
        if (File(rel).isAbsolute || path == root || !path.toPath().startsWith(root.toPath())) {
            throw ConfigException("Unsafe path in config: $rel")
        }
        return path
    }

    // ---------- Scripts ----------

    fun scriptDir(serverName: String, project: String?): File =
        File(scriptsDir, "$serverName/${project?.ifEmpty { null } ?: DEFAULT_PROJECT}")

    /**
     * Resolve a script: project folder, then the server's _shared, then the global _shared.
     * A missing script resolves to its project-folder path.
     */
    fun scriptFile(serverName: String, project: String?, script: String): File {
        val candidates = listOf(
            File(scriptDir(serverName, project), script),
            File(scriptsDir, "$serverName/$SHARED_DIR_NAME/$script"),
            File(scriptsDir, "$SHARED_DIR_NAME/$script"),
        )
        return candidates.firstOrNull { it.exists() } ?: candidates[0]
    }

    /** Script text to run; a _shared script gets `PROJECT=<project>` bound first. */
    fun loadScriptContent(serverName: String, project: String?, script: String): String {
        val file = scriptFile(serverName, project, script)
        if (!file.isFile) throw FileNotFoundException("scripts/${rel(file)}")
        val content = file.readText().trim()
        if (file.parentFile.name != SHARED_DIR_NAME) return content
        return "PROJECT=${Shlex.quote(projectRemoteDir(project))}\n$content"
    }

    private fun rel(file: File) = file.relativeTo(scriptsDir).invariantSeparatorsPath

    private fun readJson(file: File): JsonElement = Json.parseToJsonElement(file.readText())

    private fun readUtf8Strict(file: File): String =
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString()

    companion object {
        const val SERVER_META_FILENAME = "server.json"
        const val COMMANDS_FILENAME = "commands.json"
        const val SHARED_DIR_NAME = "_shared"
        const val EXPORT_FORMAT = "ssh-deck-config"
        const val EXPORT_VERSION = 1
        /** Exports from before the rename to SSH Deck are still accepted. */
        val ACCEPTED_FORMATS = setOf(EXPORT_FORMAT, "ssh-gui-config")

        /** Value bound to $PROJECT in _shared scripts. */
        fun projectRemoteDir(project: String?) = project.orEmpty().lowercase(java.util.Locale.ROOT)
    }
}

package dev.sshdeck.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

const val DEFAULT_PROJECT = "ALL"

/**
 * One quick-command button. [extra] keeps keys this app doesn't know about, so a
 * config edited here and synced back to the desktop loses nothing.
 */
data class QuickCommand(
    val label: String,
    val script: String,
    val color: String? = null,
    /** Script folder and button group; commands without a project live in "ALL". */
    val project: String = DEFAULT_PROJECT,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    /** The commands.json entry (no project: the folder holds it). */
    fun toStoredJson(): JsonObject = JsonObject(buildMap {
        put("label", JsonPrimitive(label))
        put("script", JsonPrimitive(script))
        color?.let { put("color", JsonPrimitive(it)) }
        putAll(extra)
    })

    /** The export entry, which carries the project like the desktop's in-memory dict. */
    fun toExportJson(): JsonObject = JsonObject(toStoredJson() + ("project" to JsonPrimitive(project)))

    companion object {
        private val KNOWN = setOf("label", "script", "color", "project")

        /** Returns null for an entry the desktop would also skip as malformed. */
        fun fromJson(e: JsonElement, project: String?): QuickCommand? {
            val obj = e as? JsonObject ?: return null
            val label = obj.string("label") ?: return null
            val script = obj.string("script") ?: return null
            return QuickCommand(
                label = label,
                script = script,
                color = obj.string("color"),
                project = project?.ifEmpty { null } ?: obj.string("project")?.ifEmpty { null } ?: DEFAULT_PROJECT,
                extra = obj.filterKeys { it !in KNOWN },
            )
        }
    }
}

data class Server(
    /** Display name and scripts folder name. */
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** Desktop path of the private key; on Android the key itself is imported separately. */
    val keyPath: String = "",
    /** Wrap commands in `bash -l`; off for Windows servers. */
    val loginShell: Boolean = true,
    val commands: List<QuickCommand> = emptyList(),
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    /** server.json contents. */
    fun toMetaJson(): JsonObject = JsonObject(linkedMapOf(
        "host" to JsonPrimitive(host),
        "port" to JsonPrimitive(port),
        "username" to JsonPrimitive(username),
        "key_path" to JsonPrimitive(keyPath),
        "login_shell" to JsonPrimitive(loginShell),
    ))

    fun toExportJson(): JsonObject = JsonObject(buildMap {
        putAll(toMetaJson())
        putAll(extra)
        put("name", JsonPrimitive(name))
        put("commands", kotlinx.serialization.json.JsonArray(commands.map { it.toExportJson() }))
    })

    companion object {
        private val KNOWN = setOf("name", "host", "port", "username", "key_path", "login_shell", "commands")

        /** Lenient like the desktop: missing fields fall back to defaults. */
        fun fromJson(obj: JsonObject, name: String, commands: List<QuickCommand>) = Server(
            name = name,
            host = obj.string("host").orEmpty(),
            port = (obj["port"] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() } ?: 22,
            username = obj.string("username").orEmpty(),
            keyPath = obj.string("key_path").orEmpty(),
            loginShell = (obj["login_shell"] as? JsonPrimitive)?.booleanOrNull ?: true,
            commands = commands,
            extra = obj.filterKeys { it !in KNOWN },
        )
    }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

package dev.sshdeck.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serialises JSON exactly like Python's `json.dumps(obj, indent=2)` (ensure_ascii on),
 * so files written here are byte-identical to the desktop app's.
 */
object PyJson {
    fun dumps(element: JsonElement): String = StringBuilder().also { write(it, element, 0) }.toString()

    private fun write(out: StringBuilder, e: JsonElement, depth: Int) {
        when (e) {
            is JsonObject -> writeContainer(out, '{', '}', e.entries, depth) { (k, v) ->
                writeString(out, k)
                out.append(": ")
                write(out, v, depth + 1)
            }
            is JsonArray -> writeContainer(out, '[', ']', e, depth) { write(out, it, depth + 1) }
            JsonNull -> out.append("null")
            is JsonPrimitive -> if (e.isString) writeString(out, e.content) else out.append(e.content)
        }
    }

    private fun <T> writeContainer(
        out: StringBuilder, open: Char, close: Char, items: Collection<T>, depth: Int,
        writeItem: (T) -> Unit,
    ) {
        if (items.isEmpty()) {
            out.append(open).append(close)
            return
        }
        val inner = "  ".repeat(depth + 1)
        out.append(open)
        items.forEachIndexed { i, item ->
            out.append(if (i == 0) "\n" else ",\n").append(inner)
            writeItem(item)
        }
        out.append('\n').append("  ".repeat(depth)).append(close)
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                in ' '..'~' -> out.append(c)
                // Non-ASCII is escaped per UTF-16 unit, which matches Python's surrogate pairs.
                else -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
        }
        out.append('"')
    }
}

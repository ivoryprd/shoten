package io.github.ivoryprd.shoten.util

import java.io.Closeable
import java.io.IOException
import java.io.Reader
import java.io.StringReader

class JsonException(message: String) : IOException(message)

enum class JsonToken { BEGIN_ARRAY, END_ARRAY, BEGIN_OBJECT, END_OBJECT, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

/**
 * A small streaming (pull) JSON parser. Dictionary files can be hundreds of megabytes, so they are
 * read token by token instead of being loaded into memory.
 *
 * Values read with [readTree] are represented as: `null`, [Boolean], [Long] (integral numbers that
 * fit), [Double] (other numbers), [String], [List] and [Map] (insertion ordered).
 */
class JsonReader(private val input: Reader) : Closeable {
    private val buffer = CharArray(BUFFER_SIZE)
    private var pos = 0
    private var limit = 0

    private var stack = IntArray(32)
    private var depth = 1

    private var peeked: JsonToken? = null
    private var peekedText: String? = null
    private var peekedBool = false
    private val sb = StringBuilder()

    /** Number of characters consumed so far; useful for progress reporting. */
    var charsConsumed = 0L
        private set

    init {
        stack[0] = EMPTY_DOCUMENT
    }

    fun peek(): JsonToken {
        peeked?.let { return it }
        val token = doPeek()
        peeked = token
        return token
    }

    fun hasNext(): Boolean {
        val t = peek()
        return t != JsonToken.END_ARRAY && t != JsonToken.END_OBJECT && t != JsonToken.END_DOCUMENT
    }

    fun beginArray() {
        expect(JsonToken.BEGIN_ARRAY)
        push(EMPTY_ARRAY)
    }

    fun endArray() {
        expect(JsonToken.END_ARRAY)
        depth--
    }

    fun beginObject() {
        expect(JsonToken.BEGIN_OBJECT)
        push(EMPTY_OBJECT)
    }

    fun endObject() {
        expect(JsonToken.END_OBJECT)
        depth--
    }

    fun nextName(): String {
        expect(JsonToken.NAME)
        return peekedText!!
    }

    /** Returns the next string value. Numbers are returned in their textual form. */
    fun nextString(): String {
        val t = peek()
        if (t != JsonToken.STRING && t != JsonToken.NUMBER) throw unexpected(t, "STRING")
        peeked = null
        return peekedText!!
    }

    fun nextBoolean(): Boolean {
        expect(JsonToken.BOOLEAN)
        return peekedBool
    }

    fun nextNull() {
        expect(JsonToken.NULL)
    }

    fun nextLong(): Long {
        val t = peek()
        val text = when (t) {
            JsonToken.NUMBER, JsonToken.STRING -> peekedText!!
            else -> throw unexpected(t, "NUMBER")
        }
        val value = text.toLongOrNull() ?: text.toDoubleOrNull()?.let { d ->
            if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 9.0E18) d.toLong() else null
        } ?: throw JsonException("Expected an integer but was '$text'")
        peeked = null
        return value
    }

    fun nextDouble(): Double {
        val t = peek()
        val text = when (t) {
            JsonToken.NUMBER, JsonToken.STRING -> peekedText!!
            else -> throw unexpected(t, "NUMBER")
        }
        val value = text.toDoubleOrNull() ?: throw JsonException("Expected a number but was '$text'")
        peeked = null
        return value
    }

    /** Reads the next string, or returns null if the next value is JSON null. */
    fun nextStringOrNull(): String? {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return null
        }
        return nextString()
    }

    fun skipValue() {
        var count = 0
        do {
            when (peek()) {
                JsonToken.BEGIN_ARRAY -> { beginArray(); count++ }
                JsonToken.BEGIN_OBJECT -> { beginObject(); count++ }
                JsonToken.END_ARRAY -> { endArray(); count-- }
                JsonToken.END_OBJECT -> { endObject(); count-- }
                JsonToken.END_DOCUMENT -> throw JsonException("Unexpected end of document")
                else -> { peeked = null }
            }
        } while (count > 0)
    }

    /** Reads the next value (including nested arrays and objects) into a tree. */
    fun readTree(): Any? = readTree(0)

    private fun readTree(level: Int): Any? {
        if (level > MAX_TREE_DEPTH) throw JsonException("JSON nested too deeply")
        return when (val t = peek()) {
            JsonToken.BEGIN_ARRAY -> {
                beginArray()
                val list = ArrayList<Any?>()
                while (hasNext()) list.add(readTree(level + 1))
                endArray()
                list
            }
            JsonToken.BEGIN_OBJECT -> {
                beginObject()
                val map = LinkedHashMap<String, Any?>()
                while (hasNext()) {
                    val name = nextName()
                    map[name] = readTree(level + 1)
                }
                endObject()
                map
            }
            JsonToken.STRING -> nextString()
            JsonToken.NUMBER -> parseNumber(nextString())
            JsonToken.BOOLEAN -> nextBoolean()
            JsonToken.NULL -> { nextNull(); null }
            else -> throw unexpected(t, "a value")
        }
    }

    override fun close() {
        input.close()
    }

    private fun expect(token: JsonToken) {
        val t = peek()
        if (t != token) throw unexpected(t, token.name)
        peeked = null
    }

    private fun unexpected(actual: JsonToken, expected: String) =
        JsonException("Expected $expected but was $actual near character $charsConsumed")

    private fun push(scope: Int) {
        if (depth == stack.size) {
            if (depth >= MAX_STACK_DEPTH) throw JsonException("JSON nested too deeply")
            stack = stack.copyOf(depth * 2)
        }
        stack[depth++] = scope
    }

    private fun doPeek(): JsonToken {
        when (stack[depth - 1]) {
            EMPTY_ARRAY -> {
                val c = nextNonWhitespace()
                if (c == ']'.code) return JsonToken.END_ARRAY
                stack[depth - 1] = NONEMPTY_ARRAY
                return readValueToken(c)
            }
            NONEMPTY_ARRAY -> {
                return when (nextNonWhitespace()) {
                    ']'.code -> JsonToken.END_ARRAY
                    ','.code -> readValueToken(nextNonWhitespace())
                    else -> throw syntax("Expected ',' or ']'")
                }
            }
            EMPTY_OBJECT, NONEMPTY_OBJECT -> {
                var c = nextNonWhitespace()
                if (c == '}'.code) return JsonToken.END_OBJECT
                if (stack[depth - 1] == NONEMPTY_OBJECT) {
                    if (c != ','.code) throw syntax("Expected ',' or '}'")
                    c = nextNonWhitespace()
                }
                if (c != '"'.code) throw syntax("Expected a name")
                peekedText = readString()
                stack[depth - 1] = DANGLING_NAME
                return JsonToken.NAME
            }
            DANGLING_NAME -> {
                if (nextNonWhitespace() != ':'.code) throw syntax("Expected ':'")
                stack[depth - 1] = NONEMPTY_OBJECT
                return readValueToken(nextNonWhitespace())
            }
            EMPTY_DOCUMENT -> {
                var c = nextNonWhitespace()
                if (c == 0xFEFF) c = nextNonWhitespace()
                stack[depth - 1] = NONEMPTY_DOCUMENT
                return readValueToken(c)
            }
            else -> {
                if (nextNonWhitespace() != -1) throw syntax("Unexpected data after the document")
                return JsonToken.END_DOCUMENT
            }
        }
    }

    private fun readValueToken(c: Int): JsonToken {
        return when (c) {
            '['.code -> JsonToken.BEGIN_ARRAY
            '{'.code -> JsonToken.BEGIN_OBJECT
            '"'.code -> { peekedText = readString(); JsonToken.STRING }
            't'.code -> { readLiteral("rue"); peekedBool = true; JsonToken.BOOLEAN }
            'f'.code -> { readLiteral("alse"); peekedBool = false; JsonToken.BOOLEAN }
            'n'.code -> { readLiteral("ull"); JsonToken.NULL }
            -1 -> throw syntax("Unexpected end of input")
            else -> {
                if (c == '-'.code || c in '0'.code..'9'.code) {
                    peekedText = readNumber(c.toChar())
                    JsonToken.NUMBER
                } else {
                    throw syntax("Unexpected character '${c.toChar()}'")
                }
            }
        }
    }

    private fun readLiteral(rest: String) {
        for (ch in rest) {
            if (read() != ch.code) throw syntax("Invalid literal")
        }
    }

    private fun readNumber(first: Char): String {
        sb.setLength(0)
        sb.append(first)
        while (true) {
            if (pos == limit && !fill()) break
            val ch = buffer[pos]
            if ((ch in '0'..'9') || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                sb.append(ch)
                pos++
                charsConsumed++
            } else {
                break
            }
        }
        val text = sb.toString()
        if (text.toDoubleOrNull() == null) throw syntax("Invalid number '$text'")
        return text
    }

    private fun readString(): String {
        sb.setLength(0)
        while (true) {
            if (pos == limit && !fill()) throw syntax("Unterminated string")
            // Fast path: copy runs of ordinary characters.
            var p = pos
            val l = limit
            while (p < l) {
                val ch = buffer[p]
                if (ch == '"' || ch == '\\') break
                p++
            }
            if (p > pos) {
                sb.appendRange(buffer, pos, p)
                charsConsumed += (p - pos)
                pos = p
                continue
            }
            val ch = buffer[pos++]
            charsConsumed++
            if (ch == '"') return sb.toString()
            // Escape sequence.
            when (val e = read()) {
                '"'.code -> sb.append('"')
                '\\'.code -> sb.append('\\')
                '/'.code -> sb.append('/')
                'b'.code -> sb.append('\b')
                'f'.code -> sb.append('\u000C')
                'n'.code -> sb.append('\n')
                'r'.code -> sb.append('\r')
                't'.code -> sb.append('\t')
                'u'.code -> {
                    var value = 0
                    repeat(4) {
                        val h = read()
                        val digit = when (h) {
                            in '0'.code..'9'.code -> h - '0'.code
                            in 'a'.code..'f'.code -> h - 'a'.code + 10
                            in 'A'.code..'F'.code -> h - 'A'.code + 10
                            else -> throw syntax("Invalid \\u escape")
                        }
                        value = (value shl 4) or digit
                    }
                    sb.append(value.toChar())
                }
                -1 -> throw syntax("Unterminated string")
                else -> throw syntax("Invalid escape '\\${e.toChar()}'")
            }
        }
    }

    private fun read(): Int {
        if (pos == limit && !fill()) return -1
        charsConsumed++
        return buffer[pos++].code
    }

    private fun nextNonWhitespace(): Int {
        while (true) {
            if (pos == limit && !fill()) return -1
            val ch = buffer[pos++]
            charsConsumed++
            if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') return ch.code
        }
    }

    private fun fill(): Boolean {
        val n = input.read(buffer, 0, buffer.size)
        if (n <= 0) {
            pos = 0
            limit = 0
            return false
        }
        pos = 0
        limit = n
        return true
    }

    private fun syntax(message: String) = JsonException("$message near character $charsConsumed")

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_STACK_DEPTH = 4096
        private const val MAX_TREE_DEPTH = 1000

        private const val EMPTY_ARRAY = 1
        private const val NONEMPTY_ARRAY = 2
        private const val EMPTY_OBJECT = 3
        private const val DANGLING_NAME = 4
        private const val NONEMPTY_OBJECT = 5
        private const val EMPTY_DOCUMENT = 6
        private const val NONEMPTY_DOCUMENT = 7

        fun parseNumber(text: String): Any {
            val integral = text.indexOfAny(charArrayOf('.', 'e', 'E')) < 0
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDouble()
        }
    }
}

object Json {
    /** Parses a complete JSON document into a tree (see [JsonReader.readTree]). */
    fun parse(text: String): Any? {
        JsonReader(StringReader(text)).use { reader ->
            val value = reader.readTree()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw JsonException("Unexpected data after the document")
            return value
        }
    }

    /** Serializes a tree made of the types produced by [JsonReader.readTree] into compact JSON. */
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(value, out)
            is Boolean -> out.append(if (value) "true" else "false")
            is Long, is Int, is Short, is Byte -> out.append(value.toString())
            is Double -> writeDouble(value, out)
            is Float -> writeDouble(value.toDouble(), out)
            is Number -> writeDouble(value.toDouble(), out)
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeDouble(d: Double, out: StringBuilder) {
        if (!d.isFinite()) {
            out.append("null")
        } else if (d == Math.floor(d) && Math.abs(d) < 1.0E15) {
            out.append(d.toLong().toString())
        } else {
            out.append(d.toString())
        }
    }

    fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ' || ch.code == 0x2028 || ch.code == 0x2029) {
                    out.append("\\u").append(Integer.toHexString(ch.code).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }
}

// Typed accessors for JSON trees.

@Suppress("UNCHECKED_CAST")
fun Any?.asJsonObject(): Map<String, Any?>? = this as? Map<String, Any?>

fun Any?.asJsonArray(): List<Any?>? = this as? List<Any?>

fun Map<String, Any?>.string(key: String): String? = when (val v = this[key]) {
    is String -> v
    is Long -> v.toString()
    is Double -> Json.write(v)
    is Boolean -> v.toString()
    else -> null
}

fun Map<String, Any?>.number(key: String): Double? = when (val v = this[key]) {
    is Long -> v.toDouble()
    is Double -> v
    is String -> v.toDoubleOrNull()
    else -> null
}

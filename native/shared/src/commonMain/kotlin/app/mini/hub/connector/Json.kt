package app.mini.hub.connector

/**
 * Small, dependency-free JSON reader for provider responses. The shared module has no
 * serialization dependency, and regex extraction (used by the first Gmail adapter) cannot handle
 * nesting, arrays of objects with repeated keys, or escapes reliably. Values are read-only.
 */
sealed class JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val raw: String) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()

    operator fun get(name: String): JsonValue? = (this as? Obj)?.fields?.get(name)

    /** String form of scalars. Numbers keep their source text so 64-bit IDs are never rounded. */
    val string: String? get() = when (this) {
        is Str -> value
        is Num -> raw
        is Bool -> value.toString()
        else -> null
    }
    val long: Long? get() = string?.toLongOrNull()
    val bool: Boolean? get() = (this as? Bool)?.value
    val array: List<JsonValue> get() = (this as? Arr)?.items.orEmpty()
    val objects: List<JsonValue> get() = array.filterIsInstance<Obj>()

    fun str(name: String): String? = get(name)?.string
    fun path(vararg names: String): JsonValue? = names.fold(this as JsonValue?) { acc, n -> acc?.get(n) }

    companion object {
        fun parse(text: String): JsonValue = JsonReader(text).readDocument()
        fun parseOrNull(text: String): JsonValue? = try { parse(text) } catch (_: IllegalArgumentException) { null }
    }
}

private const val MAX_JSON_BYTES = 2 * 1024 * 1024
private const val MAX_JSON_DEPTH = 64
private const val MAX_CONTAINER_ITEMS = 100_000

/** Minimal JSON writer for request bodies. Only the shapes MINI sends are supported. */
object JsonWriter {
    fun write(value: Any?): String = buildString { writeJson(this, value) }

    private fun writeJson(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    quote(out, k.toString()); out.append(':'); writeJson(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v -> if (i > 0) out.append(','); writeJson(out, v) }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value ${value::class.simpleName}")
        }
    }

    fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}

private class JsonReader(private val s: String) {
    private var i = 0

    fun readDocument(): JsonValue {
        require(s.encodeToByteArray().size <= MAX_JSON_BYTES) { "JSON input exceeds size limit" }
        val v = read(0)
        ws()
        require(i == s.length) { "Trailing JSON content at $i" }
        return v
    }

    private fun read(depth: Int): JsonValue {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting exceeds depth limit" }
        ws()
        require(i < s.length) { "Unexpected end of JSON" }
        return when (val c = s[i]) {
            '{' -> obj(depth + 1)
            '[' -> arr(depth + 1)
            '"' -> JsonValue.Str(str())
            't' -> lit("true", JsonValue.Bool(true))
            'f' -> lit("false", JsonValue.Bool(false))
            'n' -> lit("null", JsonValue.Null)
            else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Unexpected '$c' at $i")
        }
    }

    private fun obj(depth: Int): JsonValue {
        i++
        val fields = linkedMapOf<String, JsonValue>()
        ws()
        if (peek() == '}') { i++; return JsonValue.Obj(fields) }
        while (true) {
            require(fields.size < MAX_CONTAINER_ITEMS) { "JSON object has too many fields" }
            ws(); require(peek() == '"') { "Expected key at $i" }
            val k = str(); ws(); require(peek() == ':') { "Expected ':' at $i" }; i++
            fields[k] = read(depth); ws()
            when (peek()) { ',' -> i++; '}' -> { i++; return JsonValue.Obj(fields) }; else -> throw IllegalArgumentException("Expected ',' or '}' at $i") }
        }
    }

    private fun arr(depth: Int): JsonValue {
        i++
        val items = mutableListOf<JsonValue>()
        ws()
        if (peek() == ']') { i++; return JsonValue.Arr(items) }
        while (true) {
            require(items.size < MAX_CONTAINER_ITEMS) { "JSON array has too many items" }
            items += read(depth); ws()
            when (peek()) { ',' -> i++; ']' -> { i++; return JsonValue.Arr(items) }; else -> throw IllegalArgumentException("Expected ',' or ']' at $i") }
        }
    }

    private fun str(): String {
        i++
        val out = StringBuilder()
        while (true) {
            require(i < s.length) { "Unterminated string" }
            val c = s[i++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    require(i < s.length) { "Bad escape" }
                    when (val e = s[i++]) {
                        '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                        'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'n' -> out.append('\n')
                        'r' -> out.append('\r'); 't' -> out.append('\t')
                        'u' -> {
                            require(i + 4 <= s.length) { "Bad unicode escape" }
                            out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                        }
                        else -> throw IllegalArgumentException("Bad escape '\\$e'")
                    }
                }
                else -> {
                    require(c.code >= 0x20) { "Unescaped control character in JSON string" }
                    out.append(c)
                }
            }
        }
    }

    private fun num(): JsonValue {
        val start = i
        if (peek() == '-') i++
        require(i < s.length) { "Incomplete JSON number" }
        if (s[i] == '0') {
            i++
        } else {
            require(s[i] in '1'..'9') { "Invalid JSON number" }
            while (i < s.length && s[i].isDigit()) i++
        }
        if (peek() == '.') {
            i++
            require(i < s.length && s[i].isDigit()) { "Invalid JSON fraction" }
            while (i < s.length && s[i].isDigit()) i++
        }
        if (peek() == 'e' || peek() == 'E') {
            i++
            if (peek() == '+' || peek() == '-') i++
            require(i < s.length && s[i].isDigit()) { "Invalid JSON exponent" }
            while (i < s.length && s[i].isDigit()) i++
        }
        return JsonValue.Num(s.substring(start, i))
    }

    private fun lit(word: String, v: JsonValue): JsonValue {
        require(s.startsWith(word, i)) { "Bad literal at $i" }
        i += word.length
        return v
    }

    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
}

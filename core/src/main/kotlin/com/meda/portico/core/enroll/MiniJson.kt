package com.meda.portico.core.enroll

/**
 * A small strict JSON (RFC 8259) reader for the two pasted inputs this package handles.
 *
 * Why not a library: `:core` has no JSON dependency, the inputs are a few KB, and a hand parser
 * keeps two properties that matter here: it never throws (a syntax error is `null`), and no
 * error carries the input text (a Python `JSONDecodeError` holds the whole document in `.doc`;
 * so do several JVM parsers' messages). Nesting is capped, so hostile input cannot overflow the
 * stack. Numbers are kept as their source lexeme, so `project_number` round-trips exactly.
 */
internal sealed interface Json {
    class Obj(val members: Map<String, Json>) : Json {
        operator fun get(key: String): Json? = members[key]
        fun has(key: String): Boolean = members.containsKey(key)
        override fun toString() = "Json.Obj(keys=${members.keys})"
    }

    class Arr(val items: List<Json>) : Json {
        override fun toString() = "Json.Arr(size=${items.size})"
    }

    /** toString() never prints the value: it may be a token. */
    class Str(val value: String) : Json {
        override fun toString() = "Json.Str(length=${value.length})"
    }

    class Num(val raw: String) : Json {
        val isInteger: Boolean get() = raw.none { it == '.' || it == 'e' || it == 'E' }
        override fun toString() = "Json.Num"
    }

    class Bool(val value: Boolean) : Json {
        override fun toString() = "Json.Bool($value)"
    }

    data object Null : Json
}

internal object MiniJson {
    const val MAX_DEPTH = 64

    /** The parsed value, or null for anything that is not exactly one JSON value. Never throws. */
    fun parse(text: String): Json? = try {
        val p = Reader(text)
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) null else v
    } catch (_: Bad) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** Stackless and messageless: nothing about the input escapes in it. */
    private object Bad : RuntimeException() {
        @Suppress("unused")
        private fun readResolve(): Any = Bad
        override fun fillInStackTrace(): Throwable = this
    }

    private class Reader(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else throw Bad

        private fun expect(c: Char) {
            if (peek() != c) throw Bad
            pos++
        }

        fun value(depth: Int): Json {
            if (depth > MAX_DEPTH) throw Bad
            skipWs()
            return when (peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> Json.Str(string())
                't' -> literal("true", Json.Bool(true))
                'f' -> literal("false", Json.Bool(false))
                'n' -> literal("null", Json.Null)
                else -> number()
            }
        }

        private fun obj(depth: Int): Json {
            expect('{')
            val members = LinkedHashMap<String, Json>()
            skipWs()
            if (peek() == '}') {
                pos++
                return Json.Obj(members)
            }
            while (true) {
                skipWs()
                if (peek() != '"') throw Bad
                val key = string()
                skipWs()
                expect(':')
                members[key] = value(depth + 1) // duplicate keys: last wins, as Python's json does
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return Json.Obj(members) }
                    else -> throw Bad
                }
            }
        }

        private fun arr(depth: Int): Json {
            expect('[')
            val items = ArrayList<Json>()
            skipWs()
            if (peek() == ']') {
                pos++
                return Json.Arr(items)
            }
            while (true) {
                items.add(value(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return Json.Arr(items) }
                    else -> throw Bad
                }
            }
        }

        private fun literal(word: String, v: Json): Json {
            if (!s.startsWith(word, pos)) throw Bad
            pos += word.length
            return v
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = peek()
                pos++
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val e = peek()
                        pos++
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw Bad
                                var code = 0
                                for (k in 0 until 4) {
                                    val d = Character.digit(s[pos + k], 16)
                                    if (d < 0) throw Bad
                                    code = code * 16 + d
                                }
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> throw Bad
                        }
                    }
                    c < ' ' -> throw Bad
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Json {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            if (pos >= s.length) throw Bad
            if (s[pos] == '0') {
                pos++
            } else if (s[pos] in '1'..'9') {
                while (pos < s.length && s[pos].isAsciiDigit()) pos++
            } else {
                throw Bad
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                digits()
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                digits()
            }
            return Json.Num(s.substring(start, pos))
        }

        private fun digits() {
            val start = pos
            while (pos < s.length && s[pos].isAsciiDigit()) pos++
            if (pos == start) throw Bad
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}

/** Python truthiness of a JSON value, for mirroring `if not x` in the server's parser. */
internal fun Json?.pyTruthy(): Boolean = when (this) {
    null, Json.Null -> false
    is Json.Str -> value.isNotEmpty()
    is Json.Num -> raw.toBigDecimalOrNull()?.signum() != 0
    is Json.Bool -> value
    is Json.Obj -> members.isNotEmpty()
    is Json.Arr -> items.isNotEmpty()
}

/**
 * Python `str(x)` for the shapes `google-services.json` actually uses: a string as itself, an
 * integer as its canonical digits. Anything else (null, bool, float, object, list) gives null,
 * where Python would produce `"None"`, `"True"`, `"[...]"`: the app reports those as
 * `missing_fields` rather than store a stringified non-value.
 */
internal fun Json?.pyStrOrNull(): String? = when (this) {
    is Json.Str -> value
    is Json.Num -> if (isInteger) raw.toBigIntegerOrNull()?.toString() else null
    else -> null
}

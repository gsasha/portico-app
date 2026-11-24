package com.meda.portico.core.push

/**
 * A minimal strict JSON reader for the vendored contract fixture, so `:core` needs no JSON
 * dependency. Objects become `Map<String, Any?>` (insertion-ordered), arrays `List<Any?>`, numbers
 * `Long` (the fixture has integers only; anything else fails loudly), strings `String`.
 */
internal class MiniJson private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? {
            val p = MiniJson(text)
            val v = p.value()
            p.ws()
            require(p.i == p.s.length) { "trailing data at ${p.i}" }
            return v
        }
    }

    private fun ws() {
        while (i < s.length && s[i] in " \t\r\n") i++
    }

    private fun value(): Any? {
        ws()
        require(i < s.length) { "unexpected end" }
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (c == '-' || c in '0'..'9') num() else error("unexpected '$c' at $i")
        }
    }

    private fun lit(word: String, v: Any?): Any? {
        require(s.startsWith(word, i)) { "bad literal at $i" }
        i += word.length
        return v
    }

    private fun num(): Long {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && s[i] in '0'..'9') i++
        require(i < s.length && s[i] !in ".eE" || i == s.length) { "non-integer number at $start" }
        return s.substring(start, i).toLong()
    }

    private fun str(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            require(i < s.length) { "unterminated string" }
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                        }
                        else -> error("bad escape at $i")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun arr(): List<Any?> {
        i++
        val out = ArrayList<Any?>()
        ws()
        if (s[i] == ']') { i++; return out }
        while (true) {
            out.add(value())
            ws()
            when (s[i++]) {
                ',' -> continue
                ']' -> return out
                else -> error("expected , or ] at ${i - 1}")
            }
        }
    }

    private fun obj(): Map<String, Any?> {
        i++
        val out = LinkedHashMap<String, Any?>()
        ws()
        if (s[i] == '}') { i++; return out }
        while (true) {
            ws()
            require(s[i] == '"') { "expected key at $i" }
            val k = str()
            ws()
            require(s[i++] == ':') { "expected : at ${i - 1}" }
            require(k !in out) { "duplicate key $k" }
            out[k] = value()
            ws()
            when (s[i++]) {
                ',' -> continue
                '}' -> return out
                else -> error("expected , or } at ${i - 1}")
            }
        }
    }
}

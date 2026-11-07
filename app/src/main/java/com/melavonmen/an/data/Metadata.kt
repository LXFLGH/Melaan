package com.melavonmen.an.data

data class ComicMeta(val id:String,val title:String,val authors:List<String>,val tags:List<String>,val publishDate:String,val pageCount:Int,val description:String)

/**
 * Parser for the metadata.json entry packed inside a 7z archive.
 * Format: {id,title,authors[],tags[],works[],actors[],description,publish_date,update_date,page_count,views,likes,chapters[]}
 * Unknown fields are ignored; missing fields fall back to empty/zero defaults.
 */
internal object MetadataParser {

    fun parse(bytes: ByteArray): ComicMeta? = parse(String(bytes, Charsets.UTF_8))

    fun parse(text: String): ComicMeta? {
        // 真实库 metadata.json 常带 UTF-8 BOM（抓取脚本常见），必须先剔除，否则整段解析失败
        val cleaned = text.removePrefix("\uFEFF").removePrefix("\u200B").trimStart()
        val root = try {
            Json.parse(cleaned)
        } catch (_: Exception) {
            return null
        } as? Map<*, *> ?: return null
        return ComicMeta(
            id = Json.asString(root["id"]),
            title = Json.asString(root["title"]),
            authors = Json.asStringList(root["authors"]),
            tags = Json.asStringList(root["tags"]),
            publishDate = Json.asString(root["publish_date"] ?: root["publishDate"]),
            pageCount = Json.asInt(root["page_count"] ?: root["pageCount"]),
            description = Json.asString(root["description"])
        )
    }
}

/**
 * Minimal self-contained JSON reader/writer.
 * Deliberately avoids org.json so the data layer runs on plain JVM unit tests
 * without the stubbed android.jar implementation.
 */
internal object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("trailing characters at ${p.pos}")
        return value
    }

    fun asString(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        else -> v.toString()
    }

    fun asInt(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: 0
        else -> 0
    }

    fun asStringList(v: Any?): List<String> =
        (v as? List<*>)?.map { asString(it) } ?: emptyList()

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    sb.append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    private class Parser(private val s: String) {
        var pos = 0
            private set

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("unexpected end of input")
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("invalid literal at $pos")
            pos += word.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (!atEnd() && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw IllegalArgumentException("expected ':' at $pos")
                pos++
                map[key] = parseValue()
                skipWs()
                if (atEnd()) throw IllegalArgumentException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (!atEnd() && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (atEnd()) throw IllegalArgumentException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            if (atEnd() || s[pos] != '"') throw IllegalArgumentException("expected string at $pos")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("unterminated string")
                when (val c = s[pos]) {
                    '"' -> {
                        pos++
                        return sb.toString()
                    }
                    '\\' -> {
                        pos++
                        if (atEnd()) throw IllegalArgumentException("unterminated escape")
                        when (val e = s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= s.length) throw IllegalArgumentException("bad unicode escape")
                                sb.append(s.substring(pos + 1, pos + 5).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape at $pos")
                        }
                        pos++
                    }
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (!atEnd() && (s[pos] == '-' || s[pos] == '+')) pos++
            while (!atEnd() && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '-' || s[pos] == '+')) pos++
            if (pos == start) throw IllegalArgumentException("invalid number at $pos")
            val text = s.substring(start, pos)
            return if (text.any { it == '.' || it == 'e' || it == 'E' }) {
                text.toDouble()
            } else {
                text.toLongOrNull() ?: text.toDouble()
            }
        }
    }
}

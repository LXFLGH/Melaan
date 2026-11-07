package com.melavonmen.an.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 标签别名覆盖层：疑似重复键经用户在 UI 勾选+确认后合并。
 * merge() 是唯一写入口（无任何自动合并路径）；7z 内 metadata.json 永不改写。
 * 持久化：SharedPreferences JSON {"canonical":["variant",...]}。
 */
object TagAliasStore {

    private const val PREFS_NAME = "tag_aliases"
    private const val KEY_ALIASES = "aliases_json"

    private var prefs: SharedPreferences? = null
    /** canonical -> variants（不含 canonical 自身，扁平化存储） */
    private val groups = LinkedHashMap<String, MutableList<String>>()
    /** tag -> canonical（含 canonical 自身映射） */
    private val index = HashMap<String, String>()

    fun init(context: Context) {
        initWithPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    internal fun initWithPrefs(sp: SharedPreferences) {
        prefs = sp
        groups.clear()
        index.clear()
        loadFromJson(sp.getString(KEY_ALIASES, null))
        rebuildIndex()
    }

    /** 变体 -> 规范名；无别名的标签返回自身。 */
    fun canonicalOf(tag: String): String = index[tag] ?: tag

    /**
     * 合并变体到 canonical。要求 canonical ∈ variants，否则抛 IllegalArgumentException。
     * 唯一写入口——调用方（UI）必须先经用户确认。
     */
    fun merge(canonical: String, variants: Set<String>) {
        require(canonical in variants) { "canonical must be one of variants" }
        // 任何变体若是其它组的 canonical，该组成员跟随并入（扁平化），旧组删除
        val absorbed = LinkedHashSet<String>()
        for (v in variants) {
            groups[v]?.let { absorbed.addAll(it) }
        }
        for (v in variants + absorbed) {
            groups.remove(v)
            // 从其它组的变体列表移除
            for ((_, list) in groups) list.remove(v)
        }
        groups.values.removeAll { it.isEmpty() }
        val members = LinkedHashSet<String>()
        members.addAll(variants)
        members.addAll(absorbed)
        members.remove(canonical)
        groups[canonical] = members.toMutableList()
        rebuildIndex()
        persist()
    }

    /** 撤销以 canonical 为规范名的合并；其变体恢复为自身。 */
    fun undo(canonical: String) {
        if (groups.remove(canonical) != null) {
            rebuildIndex()
            persist()
        }
    }

    /** canonical -> 变体列表（不含 canonical 自身）。 */
    fun all(): Map<String, List<String>> = groups.mapValues { it.value.toList() }

    private fun rebuildIndex() {
        index.clear()
        for ((canonical, members) in groups) {
            index[canonical] = canonical
            for (m in members) index[m] = canonical
        }
    }

    private fun persist() {
        val sp = prefs ?: return
        sp.edit().putString(KEY_ALIASES, toJson()).apply()
    }

    private fun loadFromJson(raw: String?) {
        if (raw.isNullOrBlank()) return
        for ((k, v) in parseJsonMap(raw)) groups[k] = v.toMutableList()
    }

    // ---------- 极简 JSON 编解码（Map<String, List<String>>，避开单测环境 org.json stub） ----------

    private fun toJson(): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((canonical, members) in groups) {
            if (!first) sb.append(',')
            first = false
            appendEscaped(sb, canonical)
            sb.append(':').append('[')
            members.forEachIndexed { i, m ->
                if (i > 0) sb.append(',')
                appendEscaped(sb, m)
            }
            sb.append(']')
        }
        return sb.append('}').toString()
    }

    private fun appendEscaped(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private fun parseJsonMap(raw: String): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        var i = 0
        fun skipWs() { while (i < raw.length && raw[i].isWhitespace()) i++ }
        fun parseString(): String {
            check(i < raw.length && raw[i] == '"') { "expected string at $i" }
            i++
            val sb = StringBuilder()
            while (i < raw.length && raw[i] != '"') {
                var c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    i++
                    c = when (val e = raw[i]) {
                        '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> { i += 4; raw.substring(i - 3, i + 1).toInt(16).toChar() }
                        else -> e
                    }
                }
                sb.append(c)
                i++
            }
            check(i < raw.length) { "unterminated string" }
            i++
            return sb.toString()
        }
        skipWs()
        check(i < raw.length && raw[i] == '{') { "expected object" }
        i++
        skipWs()
        if (i < raw.length && raw[i] == '}') return result
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            check(i < raw.length && raw[i] == ':') { "expected :" }
            i++
            skipWs()
            check(i < raw.length && raw[i] == '[') { "expected array" }
            i++
            val list = ArrayList<String>()
            skipWs()
            if (i < raw.length && raw[i] == ']') {
                i++
            } else {
                while (true) {
                    skipWs()
                    list.add(parseString())
                    skipWs()
                    check(i < raw.length) { "unterminated array" }
                    if (raw[i] == ',') { i++; continue }
                    check(raw[i] == ']') { "expected ]" }
                    i++
                    break
                }
            }
            result[key] = list
            skipWs()
            check(i < raw.length) { "unterminated object" }
            if (raw[i] == ',') { i++; continue }
            check(raw[i] == '}') { "expected }" }
            break
        }
        return result
    }
}

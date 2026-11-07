package com.melavonmen.an.data

/** 单个原文标签的统计（原文口径，不做归并；归并视图由 UI 经 TagAliasStore.canonicalOf 展示）。 */
data class TagStat(val tag: String, val count: Int, val entryCount: Int, val normKey: String)

/**
 * 标签目录：频次统计（原文口径，供清洗页发现变体）与交集筛选（别名感知）。
 * 阅读状态过滤基于 ProgressStore 进度：null/<=0=未读，>=100=已读完，其余=在读。
 */
object TagCatalog {

    /** 原文标签统计，first-seen 序。count=总出现次数，entryCount=去重条目数。 */
    fun build(entries: List<ComicEntry>): List<TagStat> {
        val order = LinkedHashMap<String, IntArray>() // tag -> [count, entryCount]
        for (e in entries) {
            val seen = HashSet<String>()
            for (t in e.tags) {
                val rec = order.getOrPut(t) { IntArray(2) }
                rec[0]++
                if (seen.add(t)) rec[1]++
            }
        }
        return order.map { (tag, rec) -> TagStat(tag, rec[0], rec[1], TagNormalizer.normKey(tag)) }
    }

    /** 多标签 AND 交集（别名感知），无状态过滤。 */
    fun filterIntersection(entries: List<ComicEntry>, tags: Set<String>): List<ComicEntry> =
        filterIntersection(entries, TagAliasStore, tags, null)

    /**
     * 多标签 AND 交集（别名感知：canonicalOf 相等即命中）+ 阅读状态过滤。
     * status: null=全部；"未读"/"在读"/"已读完"。
     */
    fun filterIntersection(
        entries: List<ComicEntry>,
        alias: TagAliasStore,
        tags: Set<String>,
        status: String?,
    ): List<ComicEntry> = entries.filter { e ->
        if (tags.isNotEmpty()) {
            val hit = tags.map { sel ->
                val c = alias.canonicalOf(sel)
                e.tags.any { alias.canonicalOf(it) == c }
            }
            if (!hit.all { it }) return@filter false
        }
        if (status != null && statusOf(e.id) != status) return@filter false
        true
    }

    private fun statusOf(comicId: String): String {
        val pct = ProgressStore.load(comicId)?.percent ?: 0
        return when {
            pct <= 0 -> "未读"
            pct >= 100 -> "已读完"
            else -> "在读"
        }
    }
}

package com.watchdl.core

/**
 * 补章：某章在本书源下拿不到（重试耗尽 / 超时 / 正文过短）时，到别的源取同名章补上。
 *
 * 为什么不是「换源重下整本」：失败往往集中在少数几十章，而那几十章在别的站通常有。
 * 整本换源要重下 3000 章，补章只补缺口。
 *
 * 三条节流，缺一条这个功能就不可用：
 * - **每本只搜一次**：候选来自一次 [MultiSource.searchAll]，之后所有失败章共用。
 *   每章全源搜索的预算是 25 秒，一本 200 章的书要跑 83 分钟。
 * - **每个候选源只取一次目录**：目录比正文贵得多，缓存到 [catalogs]。
 * - **候选上限 [maxSources]**：排在后面的源命中的概率极低，多试一个就多一次 25 秒风险。
 *
 * 失败一律返回 null 且不抛异常：补章是尽力而为，不能因为它把整本书的下载打穿。
 */
class ChapterFallback(
    private val multi: MultiSource,
    /** 书名与作者。用于筛选「同一本书」的候选（[sameBook]）。 */
    private val bookName: String,
    private val author: String,
    /**
     * 本书当前源的名字。补章要跳过它 —— 从同一个源再取一次，
     * 多半还是同一份坏规则，白等一轮。
     */
    private val skipSourceName: String,
    private val maxSources: Int = 3,
    private val log: (String) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
) {
    /** 候选（源 + 搜索结果）。null = 还没搜过；空表 = 搜过了但没有可用候选。 */
    private var candidates: List<Pair<MultiSource.NamedSource, BookHit>>? = null

    /** 每个候选源的目录。键是「源名 + bookId」，值为 null 表示取过且失败（不再重试）。 */
    private val catalogs = HashMap<String, BookInfo?>()

    /**
     * 补一章。取到合格正文返回原文片段，否则 null。
     *
     * 加锁：下载是 [Downloader] 起的多线程并发，补章要共用候选表与目录缓存。
     * 串行化不会拖慢正常下载 —— 只有失败章才会走到这里。
     */
    @Synchronized
    fun fetch(chapterTitle: String): String? {
        val want = chapterTitle.trim()
        if (want.isEmpty() || isCancelled()) return null
        val list = pickCandidates()
        if (list.isEmpty()) return null
        for ((ns, hit) in list) {
            if (isCancelled()) return null
            val info = catalogOf(ns, hit) ?: continue
            val at = ReaderStyle.chapterIndex(info.chapters.map { it.title }, want)
            if (at < 0) continue
            val ch = info.chapters[at]
            if (isCancelled()) return null
            val text = runCatching { ns.source.content(ch.itemId, hit.bookId) }.getOrNull()
            if (text.isNullOrBlank()) continue
            // 与主流程同一口径：低于 300 字的正文不算补到。
            // 不同站同一章的正文长度不同，但 300 是「这章真的在」的下限。
            if (Downloader.bodyChars(text) < Downloader.MIN_BODY_CHARS) continue
            log("补章：${want.take(12)} 取自 ${MultiSource.tidyName(ns.name)}")
            return text
        }
        return null
    }

    /**
     * 候选源：一次全源搜索，按「同一本书 + 不是本书源」筛，按源去重，截前 [maxSources]。
     *
     * 只在第一次调用时搜。搜不到就记成空表，后面的失败章不再重复烧 25 秒。
     */
    private fun pickCandidates(): List<Pair<MultiSource.NamedSource, BookHit>> {
        candidates?.let { return it }
        if (bookName.isBlank()) {
            candidates = emptyList()
            return emptyList()
        }
        val result = runCatching { multi.searchAll(bookName, 1) }.getOrNull()
        if (result == null) {
            candidates = emptyList()
            return emptyList()
        }
        val skip = MultiSource.tidyName(skipSourceName)
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Pair<MultiSource.NamedSource, BookHit>>()
        for (hit in result.hits) {
            if (out.size >= maxSources) break
            if (!sameBook(bookName, author, hit.name, hit.author)) continue
            val ns = multi.namedFor(hit) ?: continue
            val name = MultiSource.tidyName(ns.name)
            if (name == skip) continue
            if (!seen.add(name)) continue
            out += ns to hit
        }
        candidates = out
        if (out.isEmpty()) {
            log("补章：没能为《$bookName》找到可换的源（搜到 ${result.hits.size} 条）")
        } else {
            log("补章：为《$bookName》备选 ${out.size} 个源（${out.joinToString("、") { MultiSource.tidyName(it.first.name) }}）")
        }
        return out
    }

    /** 候选源的目录，每个源只取一次。取不到记 null，下次直接跳过。 */
    private fun catalogOf(ns: MultiSource.NamedSource, hit: BookHit): BookInfo? {
        val key = MultiSource.tidyName(ns.name) + "\u0001" + hit.bookId
        if (catalogs.containsKey(key)) return catalogs[key]
        if (isCancelled()) return null
        val info = runCatching { ns.source.toc(hit.bookId, hit.name, hit.author) }
            .getOrNull()
            ?.takeIf { it.chapters.isNotEmpty() }
        catalogs[key] = info
        return info
    }
}

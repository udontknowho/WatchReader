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
            // 这里不能用 ReaderStyle.chapterIndex：它的末级判据是「归一化后双向包含」，
            // 那是给「换源回到原章」用的，找不到也得给个近处落点。补章不能这么松 ——
            // 它会让「第3章 出发」命中别站的「第3章 出发前」，把别人的正文写进本书还标 DONE，
            // 读者根本看不出错。matchIndex 判据全等失败一律 -1：宁可补不到，不可补错。
            val at = matchIndex(info.chapters.map { it.title }, want)
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

    companion object {
        /** 中文式章号：「第12章」「第十二回」「第 三 节」。 */
        private val NUM_CN = Regex(
            """第\s*([0-9]{1,6}|[零〇一二三四五六七八九十百千两壹贰叁肆伍陆柒捌玖拾佰仟]{1,8})\s*[章节節回篇集话話]"""
        )

        /** 英文式章号：「Chapter 12」。 */
        private val NUM_EN = Regex("""(?i)\bchapter\s*([0-9]{1,6})\b""")

        /** 数字加点：「12. 风起」「3、出发」。 */
        private val NUM_DOT = Regex("""^\s*([0-9]{1,6})\s*[.、,．:：)）]""")

        /**
         * 一个标题拆成的三样东西：章号（无则 -1）、带 `#号` 的统一串、标题体。
         *
         * 统一串用于第二级判据（全等即可），标题体用于第三级（同号后相容性判定）。
         */
        private class Key(val no: Int, val unified: String, val body: String) {
            companion object {
                fun parse(title: String): Key {
                    // 归一化：只留字母数字，CJK 算字母。空格、标点、全角括号一律抹掉。
                    val norm = title.filter { it.isLetterOrDigit() }
                    val m = NUM_CN.find(title) ?: NUM_EN.find(title) ?: NUM_DOT.find(title)
                        ?: return Key(-1, norm, norm)
                    val no = parseInt(m.groupValues.getOrNull(1).orEmpty())
                        ?: return Key(-1, norm, norm)
                    val token = m.value.filter { it.isLetterOrDigit() }
                    if (token.isEmpty()) return Key(-1, norm, norm)
                    val at = norm.indexOf(token)
                    if (at < 0) return Key(-1, norm, norm)
                    val body = norm.removeRange(at, at + token.length)
                    return Key(no, "#$no$body", body)
                }

                private fun parseInt(s: String): Int? {
                    if (s.isEmpty()) return null
                    if (s.all { it in '0'..'9' }) return s.toIntOrNull()
                    val v = chineseNumber(s) ?: return null
                    return if (v > 0) v else null
                }

                /** 中文数字转整数：「十二」=12、「一百零一」=101、「十」=10、「三千」=3000。 */
                private fun chineseNumber(s: String): Int? {
                    fun digitOf(c: Char): Int? = when (c) {
                        '零', '〇' -> 0
                        '一', '壹' -> 1
                        '二', '两', '贰' -> 2
                        '三', '叁' -> 3
                        '四', '肆' -> 4
                        '五', '伍' -> 5
                        '六', '陆' -> 6
                        '七', '柒' -> 7
                        '八', '捌' -> 8
                        '九', '玖' -> 9
                        else -> null
                    }
                    fun unitOf(c: Char): Int? = when (c) {
                        '十', '拾' -> 10
                        '百', '佰' -> 100
                        '千', '仟' -> 1000
                        else -> null
                    }
                    var total = 0
                    var cur = 0
                    var seen = false
                    for (c in s) {
                        val d = digitOf(c)
                        if (d != null) {
                            cur = d
                            seen = true
                            continue
                        }
                        val u = unitOf(c) ?: return null
                        seen = true
                        // 「十二」的十前面没数字，按 1 算；「百」单独出现同理。
                        if (cur == 0) cur = 1
                        total += cur * u
                        cur = 0
                    }
                    if (!seen) return null
                    val v = total + cur
                    return if (v > 0) v else null
                }
            }
        }

        /**
         * 在 [titles] 里找与 [want] 对应的章节下标；找不到、或吃不准，一律 -1。
         *
         * 判据依次收紧，任何一级「不唯一」就退到下一级，最后一级也过不了就是 -1：
         * 1. 原文全等（trim 后）。
         * 2. 归一化章号后全等：章号 token 统一成 `#<整数>`，「第十二章 风起」与「第 12 章 风起」相等。
         * 3. 章号相等 + 标题体相容（互相包含）；同号但标题体多于一种（上/下卷）视为有歧义 → -1；
         *    其中若有标题体与 [want] 完全一致的，优先取第一条。
         *
         * 性能：目录可能上千条，keys 先一次性算成数组，循环里不再重复解析。
         */
        fun matchIndex(titles: List<String>, want: String): Int {
            if (titles.isEmpty()) return -1
            val w = want.trim()
            if (w.isEmpty()) return -1
            for (i in titles.indices) if (titles[i].trim() == w) return i

            val wk = Key.parse(w)
            val keys = Array(titles.size) { Key.parse(titles[it]) }
            for (i in keys.indices) if (keys[i].unified == wk.unified) return i

            if (wk.no < 0) return -1
            var firstCompatible = -1
            val bodies = LinkedHashSet<String>()
            for (i in keys.indices) {
                val k = keys[i]
                if (k.no != wk.no) continue
                if (!k.body.contains(wk.body) && !wk.body.contains(k.body)) continue
                if (k.body == wk.body) return i
                bodies += k.body
                if (firstCompatible < 0) firstCompatible = i
            }
            // 同号下出现多种标题体：分不清是哪一版正文，宁可补不到。
            if (bodies.size > 1) return -1
            return firstCompatible
        }
    }
}

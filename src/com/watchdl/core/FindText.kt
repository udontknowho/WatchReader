package com.watchdl.core

/**
 * 全文搜索：在**已下载正文**里找关键词（#23）。
 *
 * 与 [ReaderFind] 的分工：那边搜「当前这一章」，这边搜整库。搜的范围是
 * `chapters.jsonl` 里的正文，所以离线可用，也不受书源死活影响。
 *
 * 三个上限都在这里定死：手表屏一次只看得见三四条，命中几百条没有意义，
 * 而且整库逐章跑正则是这块唯一的耗时点，上限也是性能闸门。
 */
object FindText {

    /** 一条命中。[at] 是章内字符偏移，留给以后定位滚动位置。 */
    class Hit(
        val bookId: String,
        val bookName: String,
        /** 章号（job.json 里的顺序），点进去直接用它。 */
        val index: Int,
        val title: String,
        val excerpt: String,
        val at: Int = 0,
    )

    /** 单本书最多出几条。一本书命中几百条会把别的书全挤出去。 */
    const val PER_BOOK = 8

    /** 一章最多出几条。同一章命中五段，列表里五行摘录是噪音。 */
    const val PER_CHAPTER = 2

    /** 整屏最多几条。 */
    const val LIMIT = 60

    /** 块级标签转换行：不换行的话整章会挤成一行，摘录看不出上下文。 */
    private val BLOCK = Regex("(?i)<(br|/p|/div|/section|/h[1-6])[^>]*>")
    private val TAG = Regex("<[^>]+>")

    /**
     * 正文 HTML → 纯文本。
     *
     * 不用 `Downloader.htmlToText`：那个会加全角缩进、去推荐位书单、逐行 trim，
     * 整库几千章逐章跑会把界面卡住。这里只做「块级标签换行 + 去标签 + 还原实体」。
     */
    fun plain(html: String): String {
        if (html.isEmpty()) return ""
        val withBreaks = html.replace(BLOCK, "\n")
        return Downloader.decodeEntities(withBreaks.replace(TAG, " "))
    }

    /**
     * 扫整库。按 [JobStore.listBooks] 的顺序（最近改过的在前）逐本过。
     *
     * [onBook] 每扫完一本回报一次，UI 用它更新「已扫 N 本」；
     * [cancelled] 让调用方在切屏后中断，否则用户走了还在读几兆文件。
     *
     * 只读 job.json 拿章名（[Downloader.restoreLight]），正文按行流式读 ——
     * 一次 `readChapters` 把整本 jsonl 读成 Map，三千章的书在手表上要占十几兆。
     */
    fun scan(
        store: JobStore,
        query: String,
        limit: Int = LIMIT,
        perBook: Int = PER_BOOK,
        onBook: (scanned: Int, name: String) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        var scanned = 0
        for (bookId in store.listBooks()) {
            if (cancelled() || out.size >= limit) break
            val job = Downloader.restoreLight(store, bookId) ?: continue
            val idxOf = HashMap<String, Int>(job.chapters.size * 2)
            for (i in job.chapters.indices) idxOf[job.chapters[i].itemId] = i
            val file = store.chaptersFile(bookId)
            if (!file.isFile) continue
            val found = ArrayList<Hit>()
            // 同一章被重下过会追写多行，只认第一次出现的那行。
            val seen = HashSet<String>()
            runCatching {
                file.bufferedReader().use { r ->
                    for (line in r.lineSequence()) {
                        if (found.size >= perBook || cancelled()) break
                        val id = Regex("\"itemId\"\\s*:\\s*\"([^\"]*)\"").find(line)
                            ?.groupValues?.get(1) ?: continue
                        val index = idxOf[id] ?: continue
                        if (!seen.add(id)) continue
                        val content = TinyJson.path(line, "content") ?: continue
                        for (h in ReaderFind.inText(plain(content), q, PER_CHAPTER)) {
                            found.add(Hit(bookId, job.name, index, job.chapters[index].title, h.excerpt, h.start))
                        }
                    }
                }
            }
            // 章号排序：jsonl 按下载顺序写，续传或重下会让顺序乱掉。
            out.addAll(found.sortedBy { it.index }.take(perBook))
            scanned++
            onBook(scanned, job.name)
        }
        return out
    }
}

package com.watchdl.core

/**
 * 阅读页里的两种查找：目录按标题找、正文按字串找。
 *
 * 纯函数，没有 Android 依赖 —— 手表上就一块屏，查找结果的条数和摘录长度
 * 必须提前截断，否则对话框一开就卡。这些上限都在这里定，UI 只管画。
 */
object ReaderFind {

    /** 一条正文命中。[start] 是章内字符偏移，用来定位滚动位置。 */
    class Hit(val start: Int, val line: Int, val excerpt: String)

    /**
     * 目录里标题命中关键词的下标。
     *
     * [limit] 是硬上限：五千章的书搜「第」能命中一半，全给出去等于卡死。
     */
    fun titles(titles: List<String>, query: String, limit: Int = 200): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        for (i in titles.indices) {
            if (titles[i].contains(q, ignoreCase = true)) {
                out.add(i)
                if (out.size >= limit) break
            }
        }
        return out
    }

    /**
     * 正文里命中关键词的位置。
     *
     * 一段只出一条：同一段落命中五次，列表里给五行一样的摘录是噪音，
     * 而读者真正想知道的是「这段里有」。段号 [Hit.line] 用段落下标，
     * 不是行号 —— 正文是一个 TextView 自动折行，没有可靠的行。
     */
    fun inText(
        text: String,
        query: String,
        limit: Int = 40,
        context: Int = 12,
    ): List<Hit> {
        val q = query.trim()
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        var paraStart = 0
        var line = 0
        while (paraStart <= text.length) {
            val nl = text.indexOf('\n', paraStart)
            val paraEnd = if (nl < 0) text.length else nl
            val para = text.substring(paraStart, paraEnd)
            val hit = indexOfIgnoreCase(para, q)
            if (hit >= 0) {
                out.add(Hit(start = paraStart + hit, line = line, excerpt = excerpt(para, hit, q.length, context)))
                if (out.size >= limit) return out
            }
            if (nl < 0) break
            paraStart = nl + 1
            line++
        }
        return out
    }

    /** 摘录：命中处左右各留 [context] 字，两侧加省略号。 */
    fun excerpt(para: String, at: Int, len: Int, context: Int): String {
        val from = (at - context).coerceAtLeast(0)
        val to = (at + len + context).coerceAtMost(para.length)
        val body = para.substring(from, to).replace('\t', ' ').trim()
        return (if (from > 0) "…" else "") + body + (if (to < para.length) "…" else "")
    }

    /**
     * 大小写无关的 indexOf。
     *
     * 不用 `lowercase()` 直接对位：少数语言小写后会变长（土耳其语 İ → i̇），
     * 长度一变，返回的下标就不再是原文坐标，滚动会滚到错的地方。
     * 长度不等就退回原串比较。
     */
    private fun indexOfIgnoreCase(s: String, q: String): Int {
        if (q.isEmpty() || s.length < q.length) return -1
        val a = s.lowercase()
        val b = q.lowercase()
        return if (a.length == s.length && b.length == q.length) a.indexOf(b) else s.indexOf(q)
    }
}

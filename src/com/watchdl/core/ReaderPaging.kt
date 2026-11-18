package com.watchdl.core

/** 阅读器的像素决策。不依赖 Android 排版。 */
object ReaderPaging {
    data class Anchor(val chars: Int, val delta: Int)

    fun step(viewport: Int, lineHeight: Int): Int {
        val line = lineHeight.coerceAtLeast(1)
        return (viewport - line).coerceAtLeast(line)
    }

    /** 到边界的剩余距离不再吸附，防止末屏只移动几像素。 */
    fun target(from: Int, forward: Boolean, step: Int, limit: Int, snap: (Int) -> Int): Int {
        val end = limit.coerceAtLeast(0)
        val raw = (from.toLong() + if (forward) step.toLong() else -step.toLong())
            .coerceIn(0L, end.toLong()).toInt()
        if (raw == 0 || raw == end) return raw
        val aligned = snap(raw).coerceIn(0, end)
        return if ((forward && aligned > from) || (!forward && aligned < from)) aligned else raw
    }

    /**
     * 视口顶落在哪一章。
     *
     * [bounds] 是「章号 → 块顶」的升序表：连续滚动会把后面几章接在同一屏里。
     * 表空、或视口还在首块之上时返回首块章号（空表退回 [fallback]）。
     */
    fun cursorAt(bounds: List<Pair<Int, Int>>, viewportTop: Int, fallback: Int): Int {
        if (bounds.isEmpty()) return fallback
        var at = bounds.first().first
        for ((ch, y) in bounds) if (y <= viewportTop) at = ch else break
        return at
    }

    /** [chapter] 的块高；末块用 [limit] 兜底，表里没有该章时退回 [limit]。 */
    fun chapSpan(bounds: List<Pair<Int, Int>>, chapter: Int, limit: Int): Int {
        val i = bounds.indexOfFirst { it.first == chapter }
        if (i < 0) return limit.coerceAtLeast(1)
        val top = bounds[i].second
        val next = bounds.getOrNull(i + 1)?.second ?: (top + limit)
        return (next - top).coerceAtLeast(1)
    }

    /** [chapter] 的章内偏移，滚过块顶为正。表里没有该章时按块顶 0 算。 */
    fun chapOffset(bounds: List<Pair<Int, Int>>, chapter: Int, viewportTop: Int): Int =
        (viewportTop - (bounds.firstOrNull { it.first == chapter }?.second ?: 0)).coerceAtLeast(0)

    /**
     * 存档该写的章内偏移。
     *
     * 拼接区读第 N 章时整屏 `scrollY` 已不是「这一章的位移」：拿它当 `off_` 存盘，
     * 读回时被 `mainLimit` 限幅成「读完」，下次打开就落到后一章 —— 表现就是
     * 显示的章节与实际读到的那章对不上。
     * 还没追加（[bounds] 为空）时退回整屏值并按 [limit] 限幅。
     */
    fun savedOffset(
        bounds: List<Pair<Int, Int>>,
        chapter: Int,
        scrollY: Int,
        paddingTop: Int,
        limit: Int,
    ): Int = if (bounds.isEmpty()) {
        scrollY.coerceIn(0, limit.coerceAtLeast(0))
    } else {
        chapOffset(bounds, chapter, scrollY + paddingTop)
            .coerceIn(0, chapSpan(bounds, chapter, limit))
    }

    /**
     * 三块窗口：游标 [cur] 前后各一章，越界自动收窄。
     *
     * 抄 legado 的固定三章窗口（`ReadBook.kt:184-186` 的 prev / cur / next）：
     * 窗口只由游标决定，跟屏上现有的块无关 —— 多余的一律按它摘掉。
     */
    fun windowAround(cur: Int, total: Int): List<Int> {
        if (total <= 0) return emptyList()
        val c = cur.coerceIn(0, total - 1)
        return ((c - 1).coerceAtLeast(0)..(c + 1).coerceAtMost(total - 1)).toList()
    }

    /**
     * 该从窗口里摘掉的块。
     *
     * [exempt] 是永不摘除的那一块：屏的主块（正文落点、章内查找、恢复口径都指着它）。
     */
    fun dropOutside(blocks: List<Int>, cur: Int, total: Int, exempt: Int?): List<Int> {
        val keep = windowAround(cur, total).toSet()
        return blocks.filter { it !in keep && it != exempt }
    }

    /** 保留行内距离，也保留视口仍在下一章标题上方时的负距离。 */
    fun anchor(chars: Int, desiredTop: Int, blockTop: Int, lineTop: Int): Anchor =
        Anchor(chars, desiredTop - blockTop - lineTop)

    fun restore(anchor: Anchor, blockTop: Int, lineTop: Int, paddingTop: Int): Int =
        blockTop + lineTop + anchor.delta - paddingTop
}

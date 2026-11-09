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

    /** 保留行内距离，也保留视口仍在下一章标题上方时的负距离。 */
    fun anchor(chars: Int, desiredTop: Int, blockTop: Int, lineTop: Int): Anchor =
        Anchor(chars, desiredTop - blockTop - lineTop)

    fun restore(anchor: Anchor, blockTop: Int, lineTop: Int, paddingTop: Int): Int =
        blockTop + lineTop + anchor.delta - paddingTop
}

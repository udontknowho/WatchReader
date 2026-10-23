package com.watchdl.core

/**
 * 一批章节的下载计划。照抄 TND 的策略（见笔记本页 `tnd-download-engine`）：
 * 先小批跑，整批失败就二分收窄，最后退到逐章。
 *
 * 手表端并发固定为 1，所以这里只负责「一次拿多少章」和「失败了怎么缩」。
 */
object BatchPlan {

    const val MIN_BATCH = 4
    const val MAX_BATCH = 20

    /** 初始批大小：章节多时用上限，章少时一把抓完。 */
    fun initialSize(total: Int): Int = when {
        total <= MAX_BATCH -> total
        else -> MAX_BATCH
    }

    /**
     * 一批失败后怎么缩。
     *
     * @return 下一批的大小；已到下限时返回 null，表示应转为逐章重试。
     */
    fun shrink(current: Int, remaining: Int): Int? {
        if (current <= MIN_BATCH) return null
        val next = maxOf(MIN_BATCH, current / 2)
        return if (next >= current) null else minOf(next, remaining)
    }

    /**
     * 把待下载的下标切成批次。整批失败时调用方负责再调 [shrink]。
     */
    fun batches(indexes: List<Int>, size: Int): List<List<Int>> {
        if (indexes.isEmpty()) return emptyList()
        val s = size.coerceIn(1, maxOf(1, indexes.size))
        return indexes.chunked(s)
    }
}

/** 退避等待。TND 用 `min << attempt` 封顶 max，这里照抄。 */
object Backoff {
    const val MIN_MS = 1_000L
    const val MAX_MS = 8_000L
    const val MAX_ATTEMPTS = 4

    /** 第 attempt 次重试前该等多久（attempt 从 0 起）。 */
    fun delayMs(attempt: Int): Long {
        if (attempt <= 0) return MIN_MS
        val shifted = MIN_MS shl minOf(attempt, 20)
        return minOf(shifted, MAX_MS)
    }

    /** 章与章之间的间隔，避免把对方打急。 */
    const val GAP_MS = 350L
}

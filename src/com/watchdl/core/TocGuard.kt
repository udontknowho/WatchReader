package com.watchdl.core

/**
 * 目录自动更新的守卫（照 legado `ReadBook.kt:1822-1843` 的 upToc）。
 *
 * 现状：只有章表为空才联网取目录（`readShelfBook` 里那一跳），这条本身就是最强的守卫 ——
 * 已经读过目录的书不会因为一次翻页就重新取。这里把 legado 的两条阈值也定下来，
 * 等接入「打开时自动刷新」时直接用，不必再讨论数字。
 *
 * 手点「更新目录」不该受这些限制：那是明确的意图，被节流挡住只会让人以为坏了。
 */
object TocGuard {
    /** 手上还剩这么多章没读就不必联网：读者够读，先省一次请求。 */
    const val MIN_REMAINING = 3

    /** 同一本书两次自动更新的最短间隔。连载书一天更两三章，十分钟一次已经够密。 */
    const val INTERVAL_MS = 10 * 60 * 1000L

    /**
     * 这本书现在该不该自动联网更新目录。
     *
     * @param total 当前章表章数；0 表示还没有目录，必须取。
     * @param progress 已读到的章下标，-1 表示没读过。
     * @param lastCheck 上次自动检查的时刻（毫秒）；0 表示从没查过。
     * @param now 当前时刻（毫秒）。
     */
    fun shouldUpdate(total: Int, progress: Int, lastCheck: Long, now: Long): Boolean {
        if (total <= 0) return true
        // 剩余章 = 总章数 - 已读到的位置（含当前章）。
        if (total - (progress + 1) >= MIN_REMAINING) return false
        if (lastCheck <= 0) return true
        return now - lastCheck >= INTERVAL_MS
    }
}

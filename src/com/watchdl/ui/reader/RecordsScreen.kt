package com.watchdl.ui.reader

import com.watchdl.MainActivity
import com.watchdl.core.BookInfo
import com.watchdl.core.Downloader
import com.watchdl.core.Screen
import com.watchdl.ui.my.showMy
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 阅读记录（#25）：跨书列出读过的书。
 *
 * 单书的进度屏只能从目录进，用户想「接着上次那本」得先想起是哪本；
 * 而进度本来就在 SharedPreferences 里存着，缺的只是这一页。
 * 排序用最后阅读时刻 —— 要接的永远是最近那本。
 */

/** 一行的数据。目录只取总量，不读正文。 */
private class RecordRow(
    val bookId: String,
    val name: String,
    val total: Int,
    val index: Int,
    val at: Long,
)

internal fun MainActivity.showRecords() {
    setScreen(Screen.Records)
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "阅读记录", onBack = { showMy() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val store = jobsStore()
    // 用 restoreLight：这里只列全库，restore 会把每本的 chapters.jsonl 整个读一遍。
    // readTime==0 表示没记录过（只有旧存档的 pos_），不算读过，不列。
    val rows = store.listBooks().mapNotNull { id ->
        val at = readTime(id)
        if (at <= 0L) return@mapNotNull null
        val job = Downloader.restoreLight(store, id) ?: return@mapNotNull null
        RecordRow(id, job.name.ifBlank { id }, job.chapters.size, readProgress(id), at)
    }.sortedByDescending { it.at }

    if (rows.isEmpty()) {
        col.addView(Widgets.emptyState(this, "history", "还没有阅读记录。\n读过的书会出现在这里。"))
        root.addView(body, Md3.lpWeight(1f))
        return
    }

    col.addView(Widgets.sectionLabel(this, "共 ${rows.size} 本"))
    for (r in rows) {
        col.addView(Widgets.listItem(
            ctx = this,
            title = r.name,
            subtitle = recordSubtitle(r.index, r.total),
        ) { openRecord(r.bookId) })
    }
    root.addView(body, Md3.lpWeight(1f))
}

/**
 * 副标题：「读至 12/345 章 · 3%」。
 *
 * 总量为 0 时（job.json 里没有章节表）不报百分比：分母是零，
 * 写成「0%」会让人以为刚开头，而实际是不知道全书多长。
 */
private fun recordSubtitle(index: Int, total: Int): String {
    if (total <= 0) return "读至 ${index + 1} 章"
    return "读至 ${index + 1}/$total 章 · ${index * 100 / total}%"
}

/** 点一条回到那本书那章。目录现读：换过源或删过，章号可能已经失效。 */
private fun MainActivity.openRecord(bookId: String) {
    val job = Downloader.restore(jobsStore(), bookId)
    if (job == null) {
        toast("这本书已经不在本地了")
        return
    }
    val info = BookInfo(bookId, job.name.ifBlank { bookId }, job.author, job.chapters)
    val index = readProgress(bookId)
    // 换了源之后目录长度会变，旧章号可能越界；说一句比默默打开第一章好。
    if (index !in info.chapters.indices) {
        toast("这一章已经失效了")
        return
    }
    rememberReturn()
    preview(info, index)
}

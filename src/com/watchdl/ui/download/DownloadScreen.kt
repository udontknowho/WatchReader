package com.watchdl.ui.download

import com.watchdl.MainActivity
import com.watchdl.DownloadService
import com.watchdl.core.*
import com.watchdl.ui.attachTabs
import com.watchdl.ui.shelf.*
import com.watchdl.ui.my.*
import com.watchdl.ui.reader.showAllMarks
import com.watchdl.ui.reader.showRecords
import com.watchdl.ui.source.showSources
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/** 独立根屏，复用设置的业务身份以免扩大屏模型改动范围。 */
internal const val DOWNLOAD_PAGE = "downloads"
internal fun MainActivity.isDownloadsScreen(): Boolean =
    (curScreen as? Screen.Settings)?.page == DOWNLOAD_PAGE

internal fun MainActivity.showDownloads(keepScroll: Boolean = false) {
    val oldY = if (keepScroll) Widgets.firstScroll(root)?.scrollY ?: 0 else 0
    setScreen(Screen.Settings(DOWNLOAD_PAGE))
    root.removeAllViews()
    root.addView(Widgets.headerRow(this, "下载", trailing = listOf("refresh" to { showDownloads(true) })))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val rows = downloadRows().filterNot { LocalImport.isLocal(it.job.bookId) }
    val running = DownloadService.runningBookId
    val queue = DownloadService.queueOf(filesDir)
    val waiting = queue.items().map { it.bookId }.toSet()
    col.addView(Md3.text(this, "${rows.size} 个任务 · 等待 ${waiting.size}", Md3.FS_SM, Md3.onSurfaceVariant))
    if (rows.isEmpty()) col.addView(Widgets.emptyState(this, "download", "暂无下载任务\n在书籍详情中缓存章节"))
    if (rows.any { !it.job.isFinished }) col.addView(Widgets.button(this, "继续全部", "tonal") {
        val pending = rows.map { it.job }.filter { !it.isFinished && it.bookId != DownloadService.runningBookId }
        queue.enqueue(pending.map { QueueItem(it.bookId, it.name, it.author) })
        if (DownloadService.runningBookId == null) DownloadService.startQueue(this)
        showDownloads(true)
    })
    rows.sortedByDescending { it.job.bookId == running }.forEach { row ->
        val job = row.job
        val done = job.count(ChapterStatus.DONE)
        val failed = job.count(ChapterStatus.FAILED)
        val state = when {
            job.bookId == running -> "正在下载"
            job.bookId in waiting -> "等待中"
            job.isFinished -> "已完成"
            job.state == JobState.CANCELLED -> "已取消，可续传"
            else -> "已暂停，可续传"
        }
        val item = Widgets.listItem(this, job.name.ifBlank { "未命名" },
            "$state · $done/${job.total} 章${if (failed > 0) " · 失败 $failed" else ""}",
            twoLine = true, trailing = "more", onTrailingClick = { downloadActions(job) }) {
            downloadActions(job)
        }
        col.addView(item)
        if (job.bookId == running) col.addView(Widgets.progressBar(this,
            if (job.total > 0) done.toFloat() / job.total else 0f))
    }
    root.addView(body, Md3.lpWeight(1f))
    attachTabs(2)
    if (oldY > 0) body.post { body.scrollTo(0, oldY) }
}

private fun MainActivity.downloadActions(job: BookJob) {
    val info = BookInfo(job.bookId, job.name, job.author, job.chapters)
    val running = DownloadService.runningBookId == job.bookId
    val queue = DownloadService.queueOf(filesDir)
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    if (running) {
        items += "暂停下载" to { DownloadService.cancel(this); toast("已请求暂停，稍后可续传"); showDownloads(true) }
        items += "查看进度" to { rememberReturn(); showProgress(info) }
    } else if (!job.isFinished) {
        items += "继续下载" to {
            if (DownloadService.runningBookId == null) { rememberReturn(); startDownload(info) }
            else { queue.enqueue(listOf(QueueItem(job.bookId, job.name, job.author))); showDownloads(true) }
        }
        if (job.failedIndexes.isNotEmpty()) items += "重试失败章节" to {
            if (DownloadService.runningBookId != null) toast("请先暂停当前任务")
            else { DownloadService.start(this, info, indexes = job.failedIndexes); showDownloads(true) }
        }
    }
    if (queue.items().any { it.bookId == job.bookId }) items += "移出等待队列" to { queue.remove(job.bookId); showDownloads(true) }
    items += "取消任务" to {
        queue.remove(job.bookId)
        if (running) DownloadService.cancel(this)
        if (!running) {
            jobsStore().readJob(job.bookId)?.let { raw ->
                jobsStore().writeJob(job.bookId, raw.replace(
                    Regex("(\\\"state\\\"\\s*:\\s*)\\\"[A-Z]+\\\""), "$1\"CANCELLED\""))
            }
        }
        toast("已取消任务，保留正文与收藏")
        showDownloads(true)
    }
    if (job.count(ChapterStatus.DONE) > 0) items += "导出 TXT" to { exportTxt(info) }
    items += "删除下载文件" to {
        Widgets.md3Dialog(this, "删除下载文件", body = "删除本书的目录与正文，保留收藏和阅读进度。", confirm = "删除",
            onConfirm = { purgeBookCache(listOf(job.bookId)) { n ->
                toast(if (n > 0) "下载文件已删除" else "删除失败")
                showDownloads(true)
            } }).show()
    }
    Widgets.longMenuDialog(this, job.name, items).show()
}

/** 设置根屏汇总已有真实入口，具体参数仍交给原设置子页。 */
internal fun MainActivity.showSettingsRoot() {
    setScreen(Screen.My)
    root.removeAllViews()
    root.addView(Widgets.headerRow(this, "设置"))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    fun entry(title: String, sub: String, action: () -> Unit) {
        col.addView(Widgets.listItem(this, title, sub, twoLine = true) { rememberReturn(); action() })
    }
    entry("阅读与外观", "字号、主题、表冠") { showSettings() }
    entry("书源管理", "启用、导入与检测") { showSources() }
    entry("书签与记录", "收藏位置、最近阅读") { showAllMarks() }
    entry("阅读记录", "继续上次阅读") { showRecords() }
    entry("文件管理", "本地文件与导出") { showFiles() }
    entry("缓存管理", "正文空间与清理") { showCache() }
    entry("替换净化", "正文规则") { showReplace() }
    entry("运行日志", "查看与导出") { showLogs() }
    entry("关于", appVersion()) { showAbout() }
    root.addView(body, Md3.lpWeight(1f))
    attachTabs(3)
}

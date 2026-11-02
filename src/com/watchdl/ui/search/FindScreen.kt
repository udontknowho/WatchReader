package com.watchdl.ui.search

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.my.showMy
import com.watchdl.ui.reader.preview
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 全文搜索（#23）：在已下载正文里找关键词。
 *
 * 与「搜索」页的分工：那边联网找书，这边离线找字。书全下到本地之后，
 * 「这句在哪一章」是手表上最常见的需求，而阅读页的查找只能搜当前章。
 *
 * 三个阶段共用一个屏身份（`find:关键词`）：等输入、扫、出结果。
 * 扫的过程里按书回报进度，切屏就中断（[FindText.scan] 的 cancelled）。
 */

/** 摘录在列表里的长度上限。屏宽 189 dp，一行放得下 12 个汉字。 */
private const val EXCERPT_MAX = 26

internal fun MainActivity.showFind(
    query: String = "",
    hits: List<FindText.Hit> = emptyList(),
    scanned: Int = 0,
    done: Boolean = false,
) {
    if (query.isBlank()) {
        setScreen(Screen.Find())
        askText("全文搜索", "搜已下载正文", "请先输入关键词") { q -> showFind(q) }
        return
    }
    // 还没扫过：起后台任务，进度上屏。replay 时 done 为 true，直接重画结果。
    if (!done && hits.isEmpty() && scanned == 0) {
        startFind(query)
        return
    }
    setScreen(Screen.Find(query, hits, scanned, done))
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "全文搜索", onBack = { showMy() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    if (hits.isEmpty()) {
        col.addView(Widgets.emptyState(this, "search",
            "没有找到「$query」\n已扫 $scanned 本已下载的书"))
    } else {
        col.addView(Widgets.sectionLabel(this, "「$query」命中 ${hits.size} 条 · $scanned 本"))
        for (h in hits) {
            // 标题用章名，副标题第一段是书名 —— 同一个关键词常常散在多本书里，
            // 不写书名就分不清这条属于哪本。
            val excerpt = h.excerpt.replace(Regex("\\s+"), " ").trim().take(EXCERPT_MAX)
            val sub = "《${h.bookName}》 $excerpt"
            col.addView(Widgets.listItem(
                ctx = this,
                title = h.title.ifBlank { "第${h.index + 1}章" },
                subtitle = sub,
                twoLine = true,
                onClick = { openFindHit(h) },
            ))
        }
    }
    root.addView(body, Md3.lpWeight(1f))

    // 换个词再搜：结果页留一个出口，不用退出重进。
    val again = Widgets.button(this, "换个关键词", "outline") {
        askText("全文搜索", "搜已下载正文", "请先输入关键词") { q -> showFind(q) }
    }
    root.addView(again)
}

/** 起一轮整库扫描。进度按书更新，切屏后 [FindText.scan] 会自然中断。 */
private fun MainActivity.startFind(query: String) {
    val owner = Screen.Find(query)
    setScreen(owner)
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "全文搜索中…", onBack = { showMy() }))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val progress = Md3.text(this, "搜「$query」\n已扫 0 本", Md3.FS_MD, Md3.onSurfaceVariant).apply {
        gravity = Gravity.CENTER
        setPadding(Md3.dp(this@startFind, 12), Md3.dp(this@startFind, 24),
                   Md3.dp(this@startFind, 12), Md3.dp(this@startFind, 24))
    }
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this@startFind, 3),
        )
    }
    col.addView(progress)
    col.addView(bar)
    root.addView(body)

    val scanned = java.util.concurrent.atomic.AtomicInteger(0)
    bg(owner, {
        FindText.scan(
            store = jobsStore(),
            query = query,
            onBook = { n, name ->
                scanned.set(n)
                postAlive(owner) { progress.text = "搜「$query」\n已扫 $n 本 · $name" }
            },
            cancelled = { Thread.currentThread().isInterrupted },
        )
    }) { res ->
        res.onSuccess { hits ->
            DevLog.log("WatchDL", "全文搜索「$query」：${hits.size} 条")
            showFind(query, hits, scanned = scanned.get(), done = true)
        }.onFailure { e ->
            toast("全文搜索失败：${e.message}")
            showMy()
        }
    }
}

/** 点一条命中：把这本书的目录读回来，直接翻到那一章。 */
private fun MainActivity.openFindHit(h: FindText.Hit) {
    val job = Downloader.restore(jobsStore(), h.bookId)
    if (job == null || h.index !in job.chapters.indices) {
        toast("这一章已经不在本地了")
        return
    }
    val info = BookInfo(h.bookId, job.name, job.author, job.chapters)
    // 记下这一屏，从目录退回来还能回到结果列表。
    rememberReturn()
    preview(info, h.index)
}

/** 从「我的」页进来的入口。 */
internal fun MainActivity.askFind() {
    askText("全文搜索", "搜已下载正文", "请先输入关键词") { q -> showFind(q) }
}

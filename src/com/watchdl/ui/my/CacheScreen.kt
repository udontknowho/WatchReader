package com.watchdl.ui.my

import com.watchdl.MainActivity
import com.watchdl.core.Downloader
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 缓存管理（#26）：已下载正文的占用，加一个「清理全部正文」。
 *
 * 只算 chapters.jsonl：job.json 与封面都是几十 KB 级，算进来反而糊掉
 * 「能清出多少」这个唯一要判断的数。清理保留目录与进度，清完还能接着读。
 */

/** 人读字节数。只要 B/KB/MB 三档，为这点格式引第三方库不值得。 */
internal fun humanBytes(n: Long): String = when {
    n >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024.0)
    n >= 1024L -> String.format(Locale.US, "%.0f KB", n / 1024.0)
    else -> "$n B"
}

internal fun MainActivity.showCache() {
    setScreen(Screen.Cache)
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "缓存管理", onBack = { showMy() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val store = jobsStore()
    // 大占用在前：这一页是用来决定清谁的，排在最上面就是最该看的。
    val rows = store.listBooks().mapNotNull { id ->
        val job = Downloader.restoreLight(store, id) ?: return@mapNotNull null
        Triple(job.name.ifBlank { id }, store.chaptersFile(id).length(), id)
    }.sortedByDescending { it.second }

    if (rows.isEmpty()) {
        col.addView(Widgets.emptyState(this, "download", "还没有下载过书。"))
        root.addView(body, Md3.lpWeight(1f))
        return
    }

    col.addView(Widgets.sectionLabel(this, "共 ${rows.size} 本"))
    for ((name, size, _) in rows) {
        col.addView(Widgets.listItem(
            ctx = this,
            title = name,
            subtitle = humanBytes(size),
        ))
    }
    col.addView(Md3.text(this, "正文合计 ${humanBytes(rows.sumOf { it.second })}",
        Md3.FS_SM, Md3.onSurfaceVariant))
    // 清空是整库动作，只留一个出口；单本清理由书架删除下载负责。
    col.addView(Widgets.button(this, "清理全部正文", "outline") { askClearChapters() })
    root.addView(body, Md3.lpWeight(1f))
}

/**
 * 清理前必须问一次。
 *
 * 正文是唯一要联网才能拿回来的东西，误点一次就得重下几百章；
 * 文案要点明「目录与进度保留」，否则用户会以为连同书一起删了。
 */
private fun MainActivity.askClearChapters() {
    Widgets.styleDialog(android.app.AlertDialog.Builder(this)
        .setTitle("清理全部正文")
        .setMessage("会删掉所有已下载正文，目录与阅读进度保留。\n清完要重新下载才能继续读。")
        .setNegativeButton("取消", null)
        .setPositiveButton("清理") { _, _ -> clearAllChapters() }
        .create()).show()
}

/** 逐本清正文。放线程里：每本要重写一次 job.json，几十本在主线程会卡一下。 */
private fun MainActivity.clearAllChapters() {
    val store = jobsStore()
    val ids = store.listBooks()
    thread {
        var n = 0
        for (id in ids) {
            // 单本失败不该中断整轮：目标是把能清的都清掉，报数量就够。
            runCatching { store.clearChapters(id) }.onSuccess { n++ }
        }
        postAlive(Screen.Cache) {
            toast("已清理 $n 本")
            showCache()
        }
    }
}

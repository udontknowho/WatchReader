package com.watchdl.ui.my

import android.app.AlertDialog
import com.watchdl.MainActivity
import com.watchdl.core.BookInfo
import com.watchdl.core.CacheUsage
import com.watchdl.core.Downloader
import com.watchdl.core.Screen
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 缓存管理：跟 legado 对齐，一行一本书，点开才动手。
 *
 * legado 那边这一页是「已缓存 X/总章节数」加每本的下载/删除按钮，
 * 删掉的是正文副本，目录、进度、书源都还在，能马上重新下。所以「删」和「下」
 * 必须成对出现 —— 上一版只有「清理全部正文」这一个大按钮，删完没有任何
 * 重新下载的入口，用户看到的就是「书废了」。
 *
 * 这里也因此不再提供整库一键清正文：整库动作在「文件管理」里，
 * 一屏一个按钮把几十本书的正文全删掉，误点的代价太大。
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
    // 大占用在前：这一页常用来决定先清谁，排最上面的就是最该看的。
    val rows = jobsStore().cacheUsages()

    if (rows.isEmpty()) {
        col.addView(Widgets.emptyState(this, "download", "还没有下载过书。"))
        root.addView(body, Md3.lpWeight(1f))
        return
    }

    col.addView(Widgets.sectionLabel(this,
        "共 ${rows.size} 本 · 正文 ${humanBytes(rows.sumOf { it.bytes })}"))
    for (u in rows) {
        col.addView(Widgets.listItem(
            ctx = this,
            title = u.name,
            subtitle = "已缓存 ${u.cached}/${u.total} · ${humanBytes(u.bytes)}",
            // 右角只标这一本能做什么：下完的没什么可点，缺章的给个数。
            trailing = if (u.cached >= u.total) "已缓存" else "缺 ${u.total - u.cached} 章",
            onClick = { showCacheBook(u) },
        ))
    }
    col.addView(Md3.text(this,
        "点一本书可以下载缺失章节，或者清理这本书的正文。\n" +
            "清正文只删本地副本，目录与阅读进度保留，但要联网重下才能离线读；" +
            "书源失效就取不回来。要整库腾空间，去「文件管理」。",
        Md3.FS_SM, Md3.onSurfaceVariant))
    root.addView(body, Md3.lpWeight(1f))
}

/** 单本菜单：下载与清理放在一起，跟 legado 一样成对给。 */
private fun MainActivity.showCacheBook(u: CacheUsage) {
    val pct = if (u.total > 0) u.cached * 100 / u.total else 0
    val missing = (u.total - u.cached).coerceAtLeast(0)
    val items = ArrayList<Pair<CharSequence, () -> Unit>>(2)
    items.add(
        (if (missing > 0) "下载缺失章节（$missing 章）" else "已全部缓存") to {
            downloadMissing(u)
        }
    )
    if (u.bytes > 0) {
        items.add("清理本书正文（${humanBytes(u.bytes)}）" to { askClearBook(u) })
    }
    Widgets.menuDialog(
        this,
        "${u.name} · 已缓存 ${u.cached}/${u.total}（$pct%）",
        items,
    ).show()
}

/**
 * 补下缺失章节。
 *
 * 走的是首页同一个下载入口：作业与目录都在 job.json 里，`run()` 会跳过已下载的章，
 * 所以这里不需要拼章节表，只要把目录交回去。
 */
private fun MainActivity.downloadMissing(u: CacheUsage) {
    if (u.cached >= u.total) {
        toast("这本书没有缺失章节")
        return
    }
    val job = Downloader.restoreLight(jobsStore(), u.bookId)
    if (job == null || job.chapters.isEmpty()) {
        toast("读不到这本书的目录，先去书页重新取一次")
        return
    }
    startDownload(BookInfo(job.bookId, job.name, job.author, job.chapters))
}

/**
 * 清理前的二次确认。文案必须说清三件事：删的是什么、目录与进度还在、之后要联网。
 *
 * 上一版的错误就是「清完还能接着读」这句许诺 —— 实际上源不通就再也读不了，
 * 用户按那句话操作，得到的是废书。
 */
private fun MainActivity.askClearBook(u: CacheUsage) {
    Widgets.styleDialog(AlertDialog.Builder(this)
        .setTitle("清理《${u.name}》正文")
        .setMessage(
            "删掉已下载的 ${u.cached} 章正文，约 ${humanBytes(u.bytes)}。\n" +
                "目录与阅读进度保留，但清完要联网重新下载才能离线读；" +
                "书源失效就取不回来。"
        )
        .setNegativeButton("取消", null)
        .setPositiveButton("清理") { _, _ -> clearBook(u) }
        .create()).show()
}

/** 逐本清理。放线程里：要重写一次 job.json，主线程会卡一下。 */
private fun MainActivity.clearBook(u: CacheUsage) {
    thread {
        val ok = runCatching { jobsStore().clearChapters(u.bookId) }.isSuccess
        postAlive(Screen.Cache) {
            toast(if (ok) "已清理《${u.name}》" else "清理失败")
            showCache()
        }
    }
}

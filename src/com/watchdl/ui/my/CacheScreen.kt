package com.watchdl.ui.my

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.DownloadService
import com.watchdl.MainActivity
import com.watchdl.core.BookInfo
import com.watchdl.core.CacheUsage
import com.watchdl.core.ChapterRef
import com.watchdl.core.ChapterStatus
import com.watchdl.core.Downloader
import com.watchdl.core.JobState
import com.watchdl.core.QueueItem
import com.watchdl.core.Screen
import com.watchdl.ui.search.askText
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

/**
 * 顶栏副标题：能算准的几个数。
 *
 * 「下载中」先看服务端的 [DownloadService.runningBookId]：那本是哪本磁盘读不出，
 * 但只要有作业在跑就该显一个 1。剩下的按 job.json 的 state 与 FAILED 章数统计。
 */
private fun MainActivity.cacheSummary(rows: List<CacheUsage>): String? {
    if (rows.isEmpty()) return null
    val running = if (DownloadService.runningBookId != null) 1 else 0
    val paused = rows.count {
        (it.state == JobState.PAUSED || it.state == JobState.CANCELLED) && it.cached < it.total
    }
    val failed = rows.sumOf { it.failed }
    val cached = rows.sumOf { it.cached }
    val parts = ArrayList<String>(4)
    if (running > 0) parts.add("下 $running 本")
    if (paused > 0) parts.add("停 $paused 本")
    if (failed > 0) parts.add("败 $failed 章")
    if (cached > 0) parts.add("存 $cached 章")
    return parts.joinToString(" · ").ifBlank { null }
}

/** 人读字节数。只要 B/KB/MB 三档，为这点格式引第三方库不值得。 */
internal fun humanBytes(n: Long): String = when {    n >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024.0)
    n >= 1024L -> String.format(Locale.US, "%.0f KB", n / 1024.0)
    else -> "$n B"
}

/**
 * 缓存页的屏内状态。
 *
 * 放文件级对象而不是 MainActivity 字段：加字段要改五处（replay、屏幕重建、返回），
 * 而这三个值只是「这一屏眼下怎么画的」，与屏身份无关。
 */
private object CacheUi {
    /** 搜索关键词，空串表示不过滤。 */
    var query: String = ""
    /** 状态筛选：all 全部 / missing 缺章 / full 已满。 */
    var filter: String = "all"
    /** 展开的那本书；null 表示都收起。 */
    var open: String? = null
    /** 章级窗口起点。 */
    var win: Int = 0

    /** 当前筛选的中文名，给分组标题用。 */
    fun filterName(): String = when (filter) {
        "missing" -> "缺章"
        "full" -> "已满"
        else -> "全部"
    }

    /** 这本书是否过当前筛选（搜索 + 状态）。 */    fun matches(u: CacheUsage): Boolean {
        val q = query.trim()
        if (q.isNotEmpty() && !u.name.contains(q, true) && !u.author.contains(q, true)) return false
        return when (filter) {
            "missing" -> u.cached < u.total
            "full" -> u.cached >= u.total
            else -> true
        }
    }
}

/**
 * 筛选菜单。legado 这里是「分组筛选」，靠书的分组字段；本项目没有分组字段，
 * 改按缓存状态筛 —— 同一个位置解决的是同一件事：把想看的书缩到一屏内。
 */
private fun MainActivity.showCacheFilter() {
    val opts = listOf("all" to "全部", "missing" to "缺章", "full" to "已满")
    Widgets.menuDialog(
        this, "筛选 · 当前「${CacheUi.filterName()}」",
        opts.map { (key, label) ->
            (if (key == CacheUi.filter) "✓ $label" else "   $label") to {
                CacheUi.filter = key
                CacheUi.open = null
                showCache()
            }
        },
    ).show()
}

/** 搜索书名或作者。空关键词等于清空过滤。 */
private fun MainActivity.askCacheQuery() {
    askText("搜索缓存", "书名或作者", null) { q ->
        CacheUi.query = q
        CacheUi.open = null
        showCache()
    }
}

internal fun MainActivity.showCache() {
    setScreen(Screen.Cache)
    root.removeAllViews()
    val rows = jobsStore().cacheUsages()
    // legado 顶栏副标题是「下载中|等待|暂停|失败|已缓存」。没有排队数（服务端不报），
    // 所以只报能算准的三个数，全为零就不显副标题。
    root.addView(Widgets.topBar(
        this, "缓存管理",
        onBack = { showMy() },
        subtitle = cacheSummary(rows),
        trailing = listOf("list" to { showCacheFilter() }),
    ))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    if (rows.isEmpty()) {
        col.addView(Widgets.emptyState(this, "download", "还没有下载过书。"))
        root.addView(body, Md3.lpWeight(1f))
        return
    }

    // 搜索框：提示里带上当前关键词，不用另开一行告诉用户在过滤什么。
    col.addView(Widgets.searchBar(this,
        if (CacheUi.query.isBlank()) "搜索书名或作者" else "「${CacheUi.query}」· 点按修改"
    ) { askCacheQuery() })

    val shown = rows.filter { CacheUi.matches(it) }
    if (shown.isEmpty()) {
        col.addView(Widgets.emptyState(this, "search", "没有匹配的书。换关键词或把筛选改回「全部」。"))
        root.addView(body, Md3.lpWeight(1f))
        return
    }

    col.addView(Widgets.sectionLabel(this,
        "${CacheUi.filterName()} ${shown.size}/${rows.size} 本 · 正文 ${humanBytes(shown.sumOf { it.bytes })}"))
    // 两段分区。legado 分「书架 / 未加入书架」，靠的是书是否在书架里；
    // 本地没有书架概念，但「有正文 / 正文已清空」是同一形状：一眼分出
    // 能离线读的书和要去「重新下载」的书。空的一组不画标题。
    val withBody = shown.filter { it.cached > 0 }
    val noBody = shown.filter { it.cached == 0 }
    if (withBody.isNotEmpty()) {
        col.addView(Widgets.sectionLabel(this, "有正文 ${withBody.size} 本"))
        for (u in withBody) addCacheBook(col, u)
    }
    if (noBody.isNotEmpty()) {
        col.addView(Widgets.sectionLabel(this, "正文已清空 ${noBody.size} 本"))
        for (u in noBody) addCacheBook(col, u)
    }
    col.addView(Md3.text(this,
        "点一本书展开章级列表，逐章看状态、删正文。\n" +
            "删的只是本地正文副本，目录与阅读进度保留，但要联网重下才能离线读；" +
            "书源失效就取不回来。要整库腾空间，去「文件管理」。",
        Md3.FS_SM, Md3.onSurfaceVariant))
    root.addView(body, Md3.lpWeight(1f))
    addCacheQueueBar(shown)
}

/**
 * 屏底固定动作：全部开始 / 全部停止。
 *
 * legado 那里是个 FAB。手表屏上 FAB 会把最后一张书卡的底遮掉，改成屏底一条通栅按钮，
 * 位置和语义一样：整库的动作只在这一个地方，不必逐本点。
 */
private fun MainActivity.addCacheQueueBar(rows: List<CacheUsage>) {
    val bar = Md3.row(this).apply {
        setPadding(Md3.dp(this@addCacheQueueBar, Md3.PAD), Md3.dp(this@addCacheQueueBar, Md3.GAP / 2),
            Md3.dp(this@addCacheQueueBar, Md3.PAD), Md3.dp(this@addCacheQueueBar, Md3.GAP))
    }
    if (DownloadService.runningBookId != null) {
        bar.addView(Widgets.button(this, "全部停止", "tonal") {
            DownloadService.cancel(this)
            toast("已要求停止，队列一并清空")
            // 服务先落盘再收工，过一会儿回来把按钮换回「全部开始」。
            thread {
                Thread.sleep(800)
                postAlive(Screen.Cache) { showCache() }
            }
        }, Md3.lpWeight(1f))
    } else {
        val missing = rows.filter { it.cached < it.total }
        val queued = DownloadService.queueOf(filesDir).items().size
        if (missing.isEmpty() && queued == 0) return
        val label = if (missing.isNotEmpty()) "全部开始（缺章 ${missing.size} 本）" else "继续队列（$queued 本）"
        bar.addView(Widgets.button(this, label, "filled") { startAllCache(missing) },
            Md3.lpWeight(1f))
    }
    root.addView(bar)
}

/**
 * 全部开始：把缺章的书排进队，再叫服务跑队头。
 *
 * 不在这里直接 startDownload 第一本：那会走 ACTION_START，而单本启动要清队列
 * （用户点明只要这本）。队列本身走 ACTION_QUEUE，两个意图分得开。
 */
private fun MainActivity.startAllCache(list: List<CacheUsage>) {
    val q = DownloadService.queueOf(filesDir)
    val n = q.enqueue(list.map { QueueItem(it.bookId, it.name, it.author) })
    if (n == 0) {
        toast("队列里没有书")
        return
    }
    DownloadService.startQueue(this)
    toast("已排队 $n 本，一本下完接着下一本")
    showCache()
}

/**
 * 书卡右边那一条状态词。
 *
 * 优先级按「用户此刻最需要知道什么」排：正在下的先说在跑；停下来的说停；
 * 有失败章的说失败数（比「缺 12 章」更准，缺章里有一部分就是失败）；
 * 都齐了才说已缓存。
 */
private fun cacheStateWord(u: CacheUsage): String = when {
    DownloadService.runningBookId == u.bookId -> "下载中"
    (u.state == JobState.PAUSED || u.state == JobState.CANCELLED) && u.cached < u.total -> "暂停"
    u.failed > 0 -> "失败 ${u.failed} 章"
    u.cached >= u.total -> "已缓存"
    else -> "未缓存 ${u.total - u.cached} 章"
}

/**
 * 一本书的卡：书名、状态词、线性进度条、已缓存数。
 *
 * 照 legado `BookCacheCard` 的元素顺序排。手表屏放不下它那两行按钮，
 * 动作仍走点卡的菜单 —— 但元素一个不少，看见的数和 legado 一致。
 */
private fun MainActivity.cacheCard(u: CacheUsage): LinearLayout {
    val card = Widgets.card(this, outlined = true)
    val head = Md3.row(this)
    head.addView(
        Md3.ellipsize(Md3.text(this, u.name, Md3.FS_MD, Md3.onSurface, bold = true)),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    head.addView(Md3.text(this, cacheStateWord(u), Md3.FS_SM, Md3.primary).apply {
        setPadding(Md3.dp(this@cacheCard, 6), 0, 0, 0)
    })
    card.addView(head)
    card.addView(Widgets.progressBar(this, if (u.total > 0) u.cached.toFloat() / u.total else 0f))
    // 末行：已缓存数 + legado 书卡右下角那两个动作键（下载、删除）。
    // 删除不能藏在菜单里：legado 这一页的删除一直是摆在卡上的图标，
    // 藏起来用户就会问「删除键呢」。
    val foot = Md3.row(this)
    foot.addView(
        Md3.ellipsize(Md3.text(this,
            "已缓存 ${u.cached}/${u.total} · ${humanBytes(u.bytes)}", Md3.FS_SM, Md3.onSurfaceVariant)),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    foot.addView(cacheIcon(
        "download",
        if (u.cached < u.total) Md3.primary else Md3.onSurfaceVariant,
        "下载《${u.name}》的缺失章节",
    ) { downloadMissing(u) })
    foot.addView(cacheIcon(
        "delete",
        if (u.bytes > 0) Md3.error else Md3.onSurfaceVariant,
        "删除《${u.name}》的正文缓存",
    ) { askClearBook(u) })
    card.addView(foot)
    card.isClickable = true
    card.setOnClickListener { toggleCacheBook(u) }
    return card
}

/**
 * 书卡上的动作图标。
 *
 * 热区 32dp 而不是 48dp：189dp 宽的卡里再放两个 48dp，书名就只剩一半。
 * legado 手机上这两个图标也是紧凑排布的。
 */
private fun MainActivity.cacheIcon(
    glyph: String,
    tint: Int,
    desc: String,
    onClick: () -> Unit,
): View {
    val v = Md3.iconButton(this, glyph, onClick, desc)
    Widgets.tintSubtree(v, tint)
    v.minimumWidth = 0
    v.minimumHeight = 0
    v.setPadding(Md3.dp(this, 4), 0, Md3.dp(this, 4), 0)
    v.layoutParams = LinearLayout.LayoutParams(Md3.dp(this, 32), Md3.dp(this, 26))
    return v
}

/** 一章窗口的行数。手表屏一次只能扫过这么多行，跟目录同做法只画一窗。 */
private const val CHAPTER_WINDOW = 30

/** 一本书的卡，展开时跟一片章级窗口。 */
private fun MainActivity.addCacheBook(col: LinearLayout, u: CacheUsage) {
    col.addView(cacheCard(u))
    if (CacheUi.open == u.bookId) cacheChapterWindow(col, u)
}

/**
 * 展开 / 收起一本书。
 *
 * 窗口一律从第 1 章起画，**不跳到第一个缺章**：上一版这么做了，结果已缓存的章
 * 全都落在窗口前面，用户看到的是「↑ 前面还有 200 章」，跟 legado 里那些
 * 「下载完成」的行一样点不着。缺章靠状态标记和缺章筛选去找，不靠把窗口搬过去。
 */
private fun MainActivity.toggleCacheBook(u: CacheUsage) {
    if (CacheUi.open == u.bookId) {
        CacheUi.open = null
        showCache()
        return
    }
    CacheUi.open = u.bookId
    CacheUi.win = 0
    showCache()
}

/**
 * 章级窗口：状态标记 + 单章操作，照 legado 的章行。
 *
 * 不新开一屏：加屏要改五处（sealed、screenId、replay、方向重画、返回），
 * 而这里只需要“在卡片下面接一段”，同屏内联更短。
 */
private fun MainActivity.cacheChapterWindow(col: LinearLayout, u: CacheUsage) {
    val job = Downloader.restoreLight(jobsStore(), u.bookId)
    if (job == null || job.chapters.isEmpty()) {
        col.addView(Md3.text(this, "读不到这本书的目录，先去书页重新取一次。", Md3.FS_SM, Md3.onSurfaceVariant))
        return
    }
    val cached = jobsStore().cachedItemIds(u.bookId)
    val last = job.chapters.lastIndex
    val start = CacheUi.win.coerceIn(0, last)
    val end = (start + CHAPTER_WINDOW - 1).coerceAtMost(last)
    if (start > 0) {
        col.addView(Widgets.button(this, "↑ 前面还有 $start 章", "text") {
            CacheUi.win = (start - CHAPTER_WINDOW).coerceAtLeast(0)
            showCache()
        })
    }
    for (i in start..end) {
        val ch = job.chapters[i]
        val done = ch.itemId in cached
        val failed = job.statuses[i] == ChapterStatus.FAILED
        val mark = when {
            done -> "✓"
            failed -> "✗"
            ch.locked -> "🔒"
            else -> null
        }
        val color = when {
            done -> Md3.primary
            failed -> Md3.error
            else -> Md3.onSurfaceVariant
        }
        col.addView(Widgets.chapterItem(
            ctx = this, index = i + 1, title = ch.title,
            status = mark, statusColor = color,
            onClick = { showCacheChapter(u, i, ch, done) },
        ))
    }
    if (end < last) {
        col.addView(Widgets.button(this, "↓ 后面还有 ${last - end} 章", "text") {
            CacheUi.win = end + 1
            showCache()
        })
    }
    val missing = (u.total - u.cached).coerceAtLeast(0)
    if (missing > 0) {
        col.addView(Widgets.button(this,
            if (missing > 0) "下载缺失章节（$missing 章）" else "已全部缓存", "tonal"
        ) { downloadMissing(u) })
    }
    if (u.bytes > 0) {
        col.addView(Widgets.button(this, "清理本书正文（${humanBytes(u.bytes)}）", "outline") {
            askClearBook(u)
        })
    }
    col.addView(Widgets.button(this, "收起", "text") {
        CacheUi.open = null
        showCache()
    })
}

/** 单章菜单：删除与（C7 的）重下成对给，跟全书一样。 */
private fun MainActivity.showCacheChapter(u: CacheUsage, index: Int, ch: ChapterRef, cached: Boolean) {
    val items = ArrayList<Pair<CharSequence, () -> Unit>>(2)
    if (cached) {
        items.add("删除本章正文" to { askDeleteChapter(u, index, ch) })
    } else if (ch.locked) {
        items.add("本章需登录，不能下载" to { toast("这本的付费章需要登录后才能下") })
    } else {
        items.add("下载本章" to { downloadChapter(u, index) })
    }
    Widgets.menuDialog(this, "第 ${index + 1} 章 · ${ch.title}", items).show()
}

/**
 * 补一章。
 *
 * 走服务里的 [DownloadService.EXTRA_INDEXES]：目录在 job.json 里，
 * 服务自己会读；这里只交下标，不拼章节表，也不会碰到 Binder 的 1 MB 限。
 */
private fun MainActivity.downloadChapter(u: CacheUsage, index: Int) {
    val running = DownloadService.runningBookId
    if (running != null && running != u.bookId) {
        toast("正在下载另一本，等它下完再试")
        return
    }
    val job = Downloader.restoreLight(jobsStore(), u.bookId)
    if (job == null || job.chapters.isEmpty()) {
        toast("读不到这本书的目录，先去书页重新取一次")
        return
    }
    DownloadService.start(
        this,
        BookInfo(job.bookId, job.name, job.author, job.chapters),
        freeOnly = false,
        indexes = listOf(index),
    )
    toast("已交给后台下载第 ${index + 1} 章")
}

/** 单章删除的确认。文案与整本清理一致：删的是正文副本，不是目录。 */
private fun MainActivity.askDeleteChapter(u: CacheUsage, index: Int, ch: ChapterRef) {
    Widgets.styleDialog(AlertDialog.Builder(this)
        .setTitle("删除第 ${index + 1} 章正文")
        .setMessage("只删这一章的本地正文，目录与阅读进度保留；要再读得联网重下。")
        .setNegativeButton("取消", null)
        .setPositiveButton("删除") { _, _ ->
            thread {
                val ok = runCatching { jobsStore().deleteChapter(u.bookId, ch.itemId) }.getOrDefault(false)
                postAlive(Screen.Cache) {
                    toast(if (ok) "已删除本章正文" else "删除失败，这章可能已不在缓存里")
                    showCache()
                }
            }
        }
        .create()).show()
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
    if (u.bytes <= 0 || u.cached <= 0) {
        toast("《${u.name}》没有缓存正文")
        return
    }
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

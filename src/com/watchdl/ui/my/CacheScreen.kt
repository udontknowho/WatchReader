package com.watchdl.ui.my

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.DownloadService
import com.watchdl.MainActivity
import com.watchdl.core.BookInfo
import com.watchdl.core.CacheUsage
import com.watchdl.core.ChapterCacheStatus
import com.watchdl.core.ChapterRef
import com.watchdl.core.ChapterStatus
import com.watchdl.core.Downloader
import com.watchdl.core.JobState
import com.watchdl.core.QueueItem
import com.watchdl.core.Screen
import com.watchdl.core.chapterCacheStatusText
import com.watchdl.core.resolveChapterCacheStatus
import com.watchdl.ui.search.askText
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 缓存管理：跟 legado 对齐，一行一本书，点开才动手。
 *
 * 删除是**整本删**：正文、目录、下载进度一起没，书源还在，要读再下一次。
 * 不再提供「只清正文」—— 用户判词「没有人需要只清正文」，那种中间态只会造出
 * 「书还在、又读不了」的废书。整库清正文的口子也不开，误点的代价太大。
 */

/**
 * 顶栏副标题，照 legado `buildDownloadSummary` 的五段式：
 * `下载中:N | 等待:N | 暂停:N | 失败:N | 已缓存:N`。
 *
 * 数都在章一级上算，与 legado 同口径（那边也是把每本的章数加起来，不是数书）：
 * - 下载中：只算正在跑的那一本，记 1。服务是 lanes 条线程并发取章，但具体几条在飞
 *   没有对外的计数，所以不编大数；legado 自己的 `downloadingCount` 也只记到 1 章级。
 * - 等待：跑着的书里还没下的章数减掉那 1 章 —— 队列就在磁盘的 status 里，算得准。
 * - 暂停：停下（PAUSED / CANCELLED）的书剩多少章没下。
 * - 失败 / 已缓存：直接数。已缓存按 `chapters.jsonl` 实际行数，不信 status。
 */
private fun MainActivity.cacheSummary(rows: List<CacheUsage>): String? {
    if (rows.isEmpty()) return null
    var downloading = 0
    var waiting = 0
    var paused = 0
    for (u in rows) {
        if (DownloadService.runningBookId == u.bookId) {
            downloading += 1
            waiting += (u.pending - 1).coerceAtLeast(0)
        } else if ((u.state == JobState.PAUSED || u.state == JobState.CANCELLED) && u.pending > 0) {
            paused += u.pending
        }
    }
    val failed = rows.sumOf { it.failed }
    val cached = rows.sumOf { it.cached }
    return "下载中:$downloading | 等待:$waiting | 暂停:$paused | 失败:$failed | 已缓存:$cached"
}

/** 人读字节数。只要 B/KB/MB 三档，为这点格式引第三方库不值得。 */
internal fun humanBytes(n: Long): String = when {
    n >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024.0)
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

    /** 这本书是否过当前筛选（搜索 + 状态）。 */
    fun matches(u: CacheUsage): Boolean {
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
    // legado 的副标题是五段固定格式，不算「有就显、没有就不显」。
    root.addView(Widgets.topBar(
        this, "缓存管理",
        onBack = { showMy() },
        subtitle = cacheSummary(rows),
        // 搜索在顶栏，跟 legado 同一个位置：列表上方不再占一行搜索框。
        trailing = listOf("search" to { askCacheQuery() }, "list" to { showCacheFilter() }),
    ))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    if (rows.isEmpty()) {
        col.addView(Widgets.emptyState(this, "download", "还没有下载过书。"))
        root.addView(body, Md3.lpWeight(1f))
        return
    }

    // 搜索条已搬到顶栏，这里不再画。
    val shown = rows.filter { CacheUi.matches(it) }
    if (shown.isEmpty()) {
        col.addView(Widgets.emptyState(this, "search", "没有匹配的书。换关键词或把筛选改回「全部」。"))
        root.addView(body, Md3.lpWeight(1f))
        return
    }

    // 段标题自带统计。上一版先叠一行「全部 12/12 本 · 正文 30 MB」，再叠
    // 「有正文 10 本」「正文已清空 2 本」，189 × 248 dp 上三行标题就把第一张
    // 书卡挤出屏了。现在只留分组标题，数写在标题里。
    val withBody = shown.filter { it.cached > 0 }
    val noBody = shown.filter { it.cached == 0 }
    if (withBody.isNotEmpty()) {
        col.addView(Widgets.sectionLabel(this,
            "有正文 ${withBody.size} 本 · ${humanBytes(withBody.sumOf { it.bytes })}"))
        for (u in withBody) addCacheBook(col, u)
    }
    if (noBody.isNotEmpty()) {
        col.addView(Widgets.sectionLabel(this, "待重新下载 ${noBody.size} 本"))
        for (u in noBody) addCacheBook(col, u)
    }
    // 搜索框搬到顶栏后，关键词得有个地方看得见，否则用户不知道自己在筛什么。
    if (CacheUi.query.isNotBlank()) {
        col.addView(Widgets.button(this,
            "「${CacheUi.query}」· 筛出 ${shown.size}/${rows.size} 本，点按清除", "text") {
            CacheUi.query = ""
            CacheUi.open = null
            showCache()
        }, Md3.lpDp(this, null, 32))
    } else if (shown.size != rows.size) {
        // 只有真在筛状态时才提一句，否则这句话每屏都占一行，读的人又不需要它。
        col.addView(Md3.ellipsize(Md3.text(this,
            "筛出 ${shown.size}/${rows.size} 本", Md3.FS_SM, Md3.onSurfaceVariant)).apply {
            setPadding(Md3.dp(this@showCache, 4), 0, Md3.dp(this@showCache, 4), 0)
        })
    }
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
        val label = if (missing.isNotEmpty()) "全部开始 · ${missing.size} 本" else "继续队列 · $queued 本"
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
 * 书卡末行的状态汇总，照 legado `cache_download_status_summary` 四项：
 * 「下载中 N · 等待 N · 暂停 N · 失败 N」。
 *
 * 口径与顶栏副标题同：跑着的书记 1 章在飞，其余未下的章算等待；停下的书整段算暂停。
 */
private fun cacheBookSummary(u: CacheUsage): String {
    val running = DownloadService.runningBookId == u.bookId
    val pausedBook = (u.state == JobState.PAUSED || u.state == JobState.CANCELLED) && u.pending > 0
    val downloading = if (running) 1 else 0
    val waiting = if (running) (u.pending - 1).coerceAtLeast(0) else 0
    val paused = if (pausedBook) u.pending else 0
    return "下载中 $downloading · 等待 $waiting · 暂停 $paused · 失败 ${u.failed}"
}

/**
 * 一本书的卡，元素顺序照 legado `BookCacheBookCard`：
 * 书名 / 作者、`已缓存/总章` 小牌、满宽进度条、状态汇总加两颗键。
 *
 * 与 legado 的差别只在排布：189 dp 宽的卡里放不下「汇总 + 两颗文字按钮」一行，
 * 所以汇总占满一行的剩余宽度、两颗键只留图标。元素一个不少。
 */
private fun MainActivity.cacheCard(u: CacheUsage): LinearLayout {
    val card = Widgets.card(this, outlined = true)
    val head = Md3.row(this)
    val titles = Md3.column(this)
    titles.addView(Md3.ellipsize(Md3.text(this, u.name, Md3.FS_MD, Md3.onSurface, bold = true)))
    titles.addView(Md3.ellipsize(Md3.text(this,
        u.author.ifBlank { "佚名" }, Md3.FS_SM, Md3.onSurfaceVariant)))
    head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(cacheCountChip(u))
    card.addView(head)
    card.addView(Widgets.progressBar(this, if (u.total > 0) u.cached.toFloat() / u.total else 0f))
    val foot = Md3.row(this)
    val summary = Md3.text(this, cacheBookSummary(u), Md3.FS_SM, Md3.onSurfaceVariant)
    foot.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    // 播放 / 停止：legado 只在「有下载任务」或「还有缺章」时才画这颗键。
    val running = DownloadService.runningBookId == u.bookId
    val pausedBook = (u.state == JobState.PAUSED || u.state == JobState.CANCELLED) && u.pending > 0
    if (running || pausedBook || u.cached < u.total) {
        foot.addView(cacheIcon(
            if (running) "stop" else "play",
            if (running) Md3.error else Md3.primary,
            when {
                running -> "暂停《${u.name}》的下载"
                pausedBook -> "继续《${u.name}》的下载"
                else -> "开始下载《${u.name}》"
            },
        ) { if (running) pauseCacheBook(u) else resumeCacheBook(u) })
    }
    foot.addView(cacheIcon("delete", Md3.error, "删除《${u.name}》的下载") { deleteCacheBook(u) })
    card.addView(foot)
    card.isClickable = true
    card.setOnClickListener { toggleCacheBook(u) }
    return card
}

/** `已缓存/总章` 小牌，对 legado 头行右侧那个 `TextCard`。 */
private fun MainActivity.cacheCountChip(u: CacheUsage): android.widget.TextView =
    Md3.text(this, "${u.cached}/${u.total}", Md3.FS_SM, Md3.primary, bold = true).apply {
        background = Md3.bg(Md3.surface4, Md3.R_GRID.toFloat())
        setPadding(Md3.dp(this@cacheCountChip, 6), Md3.dp(this@cacheCountChip, 2),
            Md3.dp(this@cacheCountChip, 6), Md3.dp(this@cacheCountChip, 2))
    }

/**
 * 暂停一本书：停服务就停在这本，不在跑就把它从队列里拿掉并把状态落成 PAUSED。
 *
 * 服务一次只跑一本，所以「暂停这本」与「停服务」是同一件事；
 * 不在跑的那本改 job.json 的 state 就够了，下次继续时从队列重新排。
 */
private fun MainActivity.pauseCacheBook(u: CacheUsage) {
    if (DownloadService.runningBookId == u.bookId) {
        // 服务的取消是整库级的：它会把 queue.json 一起作废。先取一份别的书，
        // 停完再放回去 —— 否则用户只想停这一本，排好的队列却没了。
        val rest = DownloadService.queueOf(filesDir).items().filter { it.bookId != u.bookId }
        DownloadService.cancel(this)
        toast("已暂停《${u.name}》，下次接着下")
        thread {
            // 等服务的 ACTION_CANCEL 落完（它清队列在同一个回调里），再写回剩下的。
            Thread.sleep(800)
            if (rest.isNotEmpty()) DownloadService.queueOf(filesDir).enqueue(rest)
            postAlive(Screen.Cache) { showCache() }
        }
        return
    }
    DownloadService.queueOf(filesDir).remove(u.bookId)
    thread {
        runCatching { jobsStore().writeState(u.bookId, JobState.PAUSED) }
        postAlive(Screen.Cache) { showCache() }
    }
    toast("已暂停《${u.name}》")
}

/**
 * 继续一本书：排进队列再叫醒服务。
 *
 * 不走单本启动（ACTION_START）—— 那条路会先把队列整个清空，用户排过的书就没了。
 * 排队则尊重 FIFO：前面那本跑完，服务收尾时自己取队头。
 */
private fun MainActivity.resumeCacheBook(u: CacheUsage) {
    val job = Downloader.restoreLight(jobsStore(), u.bookId)
    if (job == null || job.chapters.isEmpty()) {
        toast("读不到这本书的目录，先去书页重新取一次")
        return
    }
    DownloadService.queueOf(filesDir).enqueue(listOf(QueueItem(u.bookId, u.name, u.author)))
    DownloadService.startQueue(this)
    if (u.cached >= u.total) toast("《${u.name}》已经下全了") else toast("《${u.name}》已排进下载队列")
    showCache()
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
    // 翻页是轻动作：text 变体、矮一档（32dp）、满宽，居中。
    val gap = Md3.dp(this, Md3.GAP)
    if (start > 0) {
        col.addView(Widgets.button(this, "↑ 前面还有 $start 章", "text") {
            CacheUi.win = (start - CHAPTER_WINDOW).coerceAtLeast(0)
            showCache()
        }, Md3.lpDp(this, null, 32).apply { topMargin = gap / 2 })
    }
    for (i in start..end) {
        val ch = job.chapters[i]
        val done = ch.itemId in cached
        col.addView(cacheChapterRow(u, i, ch, job.statuses[i], done))
    }
    if (end < last) {
        col.addView(Widgets.button(this, "↓ 后面还有 ${last - end} 章", "text") {
            CacheUi.win = end + 1
            showCache()
        }, Md3.lpDp(this, null, 32).apply { topMargin = gap / 2 })
    }

    // 整本动作区。数字从按钮标签里搬出来单占一行：标签带「（371 章）」这种
    // 括号串，189 dp 上已经占满一行，再加上左右 16dp 内边距就会被截成省略号。
    // 数字放说明行、按钮只留动词，三项就能都站稳。
    val missing = (u.total - u.cached).coerceAtLeast(0)
    if (missing > 0 || u.bytes > 0) {
        // 一条 1dp 分隔线把章列表和整本动作分开。没有它，第一个按钮会紧贴
        // 最后一行章，看上去像章行的一部分。
        col.addView(android.view.View(this).apply { background = Md3.bg(Md3.outlineVariant, 0f) },
            Md3.lpDp(this, null, 1).apply { topMargin = gap })
        val facts = buildList {
            if (missing > 0) add("还缺 $missing 章")
            if (u.bytes > 0) add("正文占 ${humanBytes(u.bytes)}")
        }.joinToString(" · ")
        col.addView(Md3.ellipsize(Md3.text(this, facts, Md3.FS_SM, Md3.onSurfaceVariant)).apply {
            setPadding(Md3.dp(this@cacheChapterWindow, 4), 0, Md3.dp(this@cacheChapterWindow, 4), 0)
        }, Md3.lpDp(this, null, 20).apply { topMargin = gap / 2 })
    }
    if (missing > 0) {
        col.addView(Widgets.button(this, "下载缺失章节", "filled") { downloadMissing(u) },
            Md3.lpDp(this, null, Md3.BTN).apply { topMargin = gap / 2 })
    }
    // 不再给「只清正文」：用户判词「没有人需要只清正文」。正文、目录、进度
    // 分开处理只会造出「书还在、又读不了」的中间态。要腾空间就整本删，
    // 要离线读就整本下，两个动作各自闭合。
    col.addView(Widgets.button(this, "删除这本书", "tonal") { deleteCacheBook(u) },
        Md3.lpDp(this, null, Md3.BTN).apply { topMargin = gap })
    col.addView(Widgets.button(this, "收起", "text") {
        CacheUi.open = null
        showCache()
    }, Md3.lpDp(this, null, Md3.BTN).apply { topMargin = gap / 2 })
}

/**
 * 一行章，照 legado `BookCacheChapterRow`：左边章名 + 状态（下载中再加一条进度条），
 * 右边固定位置的三颗键 —— 停止 / 下载 / 删除。
 *
 * 与 legado 一致的两点：停止键与下载键按状态互斥，删除键恒有；
 * 已缓存的章不再出现下载键，只留删除。
 *
 * 章内进度条照 legado 的做法：`downloadProgress` 为空时就画一条空轨道。
 * 我们的引擎一章一次请求，没有分页进度可报，所以永远是空轨道 —— 它表达的是
 * 「这一章正在取」，不是一个百分比。
 */
private fun MainActivity.cacheChapterRow(
    u: CacheUsage,
    index: Int,
    ch: ChapterRef,
    status: ChapterStatus?,
    cached: Boolean,
): LinearLayout {
    val running = DownloadService.runningBookId == u.bookId
    val pausedBook = u.state == JobState.PAUSED || u.state == JobState.CANCELLED
    val pending = status == null || status == ChapterStatus.PENDING
    val state = resolveChapterCacheStatus(
        isDownloading = running && DownloadService.currentChapterIndex == index,
        isWaiting = running && pending,
        isPaused = !running && pausedBook && pending,
        isError = status == ChapterStatus.FAILED,
        isCached = cached,
        // 引擎不报章内页码，ProgressLabel 在我们的数据里永不成立；接口留着对齐 legado。
        hasProgressLabel = false,
    )
    val row = Md3.row(this)
    val h = Md3.dp(this, 4)
    row.setPadding(Md3.dp(this, 12), h, h, h)
    val col = Md3.column(this)
    col.addView(Md3.ellipsize(Md3.text(this, ch.title, Md3.FS_SM, Md3.onSurface, bold = true)))
    val color = when (state) {
        ChapterCacheStatus.Error, ChapterCacheStatus.ErrorWaitingRetry -> Md3.error
        ChapterCacheStatus.Cached -> Md3.primary
        else -> Md3.onSurfaceVariant
    }
    // 付费章在书源里取不回正文。legado 没有这个概念，但不说清楚用户会一直点。
    val label = if (ch.locked && !cached) "需登录" else chapterCacheStatusText(state)
    col.addView(Md3.ellipsize(Md3.text(this, label, Md3.FS_SM, color)))
    if (state == ChapterCacheStatus.Downloading || state == ChapterCacheStatus.ProgressLabel) {
        col.addView(Widgets.progressBar(this, 0f, 3))
    }
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    // 停：这一行在排队或在取。服务一次只跑一本，停这一书就是停这一行。
    if (state == ChapterCacheStatus.Downloading || state == ChapterCacheStatus.Waiting ||
        state == ChapterCacheStatus.ErrorWaitingRetry
    ) {
        row.addView(cacheIcon("stop", Md3.error, "暂停《${u.name}》的下载") { pauseCacheBook(u) })
    } else if (!cached && !ch.locked) {
        // 下载：已暂停或未缓存的章。已缓存的章不再出现这颗键，跟 legado 一致。
        row.addView(cacheIcon("download", Md3.primary, "下载第 ${index + 1} 章") {
            downloadChapter(u, index)
        })
    }
    row.addView(cacheIcon("delete", Md3.error, "删除第 ${index + 1} 章正文") {
        askDeleteChapter(u, index, ch)
    })
    return row
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
    Widgets.md3Dialog(this,
        title = "删除第 ${index + 1} 章正文",
        body = "只删这一章的本地正文，目录与阅读进度保留；要再读得联网重下。",
        confirm = "删除",
        onConfirm = {
            thread {
                val ok = runCatching { jobsStore().deleteChapter(u.bookId, ch.itemId) }.getOrDefault(false)
                postAlive(Screen.Cache) {
                    toast(if (ok) "已删除本章正文" else "删除失败，这章可能已不在缓存里")
                    showCache()
                }
            }
        },
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
 * 整本删除：正文、目录、下载进度，连同队列里的那条一起没。
 *
 * 缓存页的删除键因此必须是真删除，跟书架里那个 confirmDeleteJob 同一套做法：
 * 先取消在跑的服务，再等它收工，最后落墓碑删目录 —— 不等就删，后台线程
 * 下一章落盘会把目录连同 job.json 一起建回来，书就诈尸。
 *
 * 书源与书架条目不在这个目录里，删完不会把书从搜索里弄丢，重新下载即可。
 */
private fun MainActivity.deleteCacheBook(u: CacheUsage) {
    Widgets.md3Dialog(this,
        title = "删除《${u.name}》",
        body = "正文、目录、下载进度一起删，约 ${humanBytes(u.bytes)}。\n" +
            "书源还在，想离线读再下一次。",
        confirm = "删除",
        onConfirm = {
            if (DownloadService.runningBookId == u.bookId) DownloadService.cancel(this)
            // 队列里那条也要剔除，否则轮它当队头时又下一遭，用户以为没删掉。
            DownloadService.queueOf(filesDir).remove(u.bookId)
            thread {
                var waited = 0
                while (DownloadService.runningBookId == u.bookId && waited < 3000) {
                    Thread.sleep(50)
                    waited += 50
                }
                // 等不到也不要紧：墓碑会让后面所有落盘都落空。
                val gone = runCatching { jobsStore().markDeleted(u.bookId) }.getOrDefault(false)
                postAlive(Screen.Cache) {
                    toast(if (gone) "已删除《${u.name}》" else "删除失败：目录仍被占用")
                    if (CacheUi.open == u.bookId) CacheUi.open = null
                    showCache()
                }
            }
        },
    ).show()
}

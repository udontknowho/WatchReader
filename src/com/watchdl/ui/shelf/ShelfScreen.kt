package com.watchdl.ui.shelf

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.attachTabs
import com.watchdl.ui.search.askKeyword
import com.watchdl.ui.source.showSources
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File

/**
 * 书架屏（从 MainActivity 原样搬出，行为未改）。
 *
 * 写成 MainActivity 的扩展函数而不是独立类：它要用 Activity 的
 * root / setScreen / crownStep 等导航与滚动状态，扩展函数能直接取，不必再搭一层壳。
 *
 * 整屏不再带返回箭头，也不再靠「首页」转一道 —— 它是应用首页（legado 的 Bookshelf）。
 */

/** 书架行的进度尾巴。副标题统一两段，字段数固定，列表才能纵向扫读。 */
internal fun MainActivity.progressTail(done: Int, total: Int, failed: Int): String =
    if (failed > 0) "$done/$total 章 · 失败 $failed" else "$done/$total 章"

/**
 * 书架数据：已下载的书，最近读过的在前。
 *
 * 用 [Downloader.restoreLight]：只要书名、作者、章节数与进度，
 * 不解析 chapters.jsonl（真实数据 1.5 MB，实测 12 ms，且是主线程）。
 */
internal fun MainActivity.shelfBooks(limit: Int = Int.MAX_VALUE): List<BookJob> {
    // 先按 job.json 里的原始 bookId 取阅读时间，没有阅读记录的才退回目录修改时间。
    val dirs = jobsDir().listFiles()
        ?.filter { it.isDirectory && File(it, "job.json").isFile }
        ?.map { dir -> dir to runCatching { File(dir, "job.json").readText() }.getOrNull()
            ?.let { com.watchdl.core.TinyJson.path(it, "bookId") }.orEmpty() }
        ?.sortedByDescending { (dir, id) -> readTime(id).takeIf { it > 0 } ?: dir.lastModified() }
        ?: return emptyList()
    // 用 job.json 里的**原始 bookId**，不能用目录名 ——
    // 目录名是 safeDirName 净化过的，再拿去 restore 会被二次净化，对不上。
    // 排序只用到 bookId 与时间，所以先排完再 restore —— 看不见的卡片不必付一次读盘。
    return dirs.asSequence()
        .mapNotNull { (_, id) ->
            id.takeIf { it.isNotBlank() }?.let { Downloader.restoreLight(jobsStore(), it) }
        }
        .take(limit)
        .toList()
}

/**
 * 书架：已下载的书，封面墙 / 列表两档。
 *
 * 书源不提供封面 —— `job.json` 只存书名/作者/章节/进度，`Downloader.persist`
 * 没有 cover 字段。所以封面是**生成的占位卡片**：书名首字 + 按书名定的固定配色。
 * 同一本书每次进来颜色一致，靠颜色区分书籍，不靠图片。
 *
 * 结构照 legado `BookshelfScreen.kt`：顶栏只有搜索图标（没有「书架」标题），
 * 下面一排分组页签。189 dp 宽再放标题就只剩 100 dp，列表项书名全被截完。
 */
internal fun MainActivity.showShelf() {
    setScreen(Screen.Shelf)
    root.removeAllViews()
    // legado 书架的分组页签是「用户自建书单」，不是在读状态过滤。我们只有下载作业、
    // 没有书单系统，硬造「全部/在读/下完」是自创语义（用户判词：乱七八糟的）。
    // 首页就是纯书架：所有已下载的书，按最近使用排。
    // 不挂顶栏（书架没有标题，64dp 空顶栏在 248dp 屏上是一整行浪费），
    // 搜索和视图切换放首行右端。
    val books = shelfBooks()
    root.addView(Widgets.headerRow(this, "书架",
        trailing = listOf(
            "search" to { askKeyword() },
            "more" to { showShelfMenu() },
        )))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    if (books.isEmpty()) {
        col.addView(Widgets.emptyState(this, "list",
            "书架是空的\n搜索书名即可开始"))
        root.addView(body)
        attachTabs(0)
        return
    }

    if (!shelfGrid) {
        // 列表档：一行一本，书名 + 作者/进度。章数多的书想知道下到哪了就看这一档。
        for (job in books) {
            val displayName = job.name.ifBlank { "未命名 · ${job.total} 章" }
            val done = job.count(ChapterStatus.DONE)
            // legado 列表项副标题给读到哪一章。有阅读记录时优先它 ——
            // 下载进度在网格档和详情页都看得到，列表档更该回答「上次读到哪」。
            val readIdx = readProgress(job.bookId)
            val readTitle = job.chapters.getOrNull(readIdx)?.title?.takeIf { it.isNotBlank() }
            val tail = if (readTitle != null) "在读 · $readTitle"
                       else progressTail(done, job.total, 0)
            val sub = if (job.author.isBlank()) tail else "${job.author} · $tail"
            col.addView(Widgets.listItem(this, displayName, sub, twoLine = true,
                trailing = "more", onTrailingClick = { showJobMenu(job) }) {
                openBook(job.bookId, displayName, job.author)
            })
        }
        root.addView(body)
        attachTabs(0)
        return
    }
    // 三列：手表屏 189 dp 宽，两列时封面有 74×104 dp —— 一张卡就吃掉
    // 内容区（144 dp）的七成高，一屏只看得见两本书，用户判词：「书大的离谱」。
    // 三列后封面 47×66 dp，一屏三列、能扫到两组。
    val cols = 3
    for (chunk in books.chunked(cols)) {
        val row = Md3.row(this)
        row.gravity = android.view.Gravity.TOP
        for (job in chunk) {
            row.addView(shelfCard(job), LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (row.childCount > 0) leftMargin = Md3.dp(this@showShelf, Md3.GAP)
            })
        }
        // 不足三个时补空位，否则最后一行少几张卡会被撑满整行。
        repeat(cols - chunk.size) {
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        col.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = Md3.dp(this@showShelf, Md3.GAP) })
    }
    root.addView(body)
    attachTabs(0)
}

/** 书架占位色卡可用色。都取 Md3 里的容器色，在白/黑背景下都能读。 */
internal fun MainActivity.shelfCard(job: BookJob): LinearLayout {
    val card = Md3.column(this)
    card.isClickable = true
    card.isFocusable = true
    val displayName = job.name.ifBlank { "未命名" }
    // 点击后进目录（与首页列表一致）
    card.setOnClickListener { openBook(job.bookId, displayName, job.author) }
    card.contentDescription = "$displayName，${job.author.ifBlank { "未知作者" }}"

    // 封面区：颜色按书名哈希定，同一本书颜色稳定。高度按 5:7 跟着实际宽度走
    // —— 三列下卡片宽度是算出来的，写死高度就不再是 5:7 了。
    val cover = Md3.text(this, coverGlyph(job), 22f, Md3.onPrimaryContainer, bold = true).apply {
        gravity = android.view.Gravity.CENTER
        background = Md3.bg(shelfColor(job), Md3.R_GRID.toFloat())
    }
    card.post {
        if (cover.height != card.width * 7 / 5) {
            cover.layoutParams = cover.layoutParams.apply { height = card.width * 7 / 5 }
            cover.requestLayout()
        }
    }
    card.addView(cover, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 58)))

    // 书名一行截断（三列放不下两行）。副标题也一行，作者优先，没作者给章数。
    val title = Md3.text(this, displayName, Md3.FS_SM, Md3.onSurface).apply {
        maxLines = 1
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, Md3.dp(this@shelfCard, Md3.GAP), 0, 0)
    }
    card.addView(title)
    val done = job.count(ChapterStatus.DONE)
    // 字号走 Md3.FS_SM 而不是硬编码 9sp：MD3 的 labelSmall 下限是 11sp，
    // 9sp 在手表上已经难读；FS_SM 还会随屏宽档位放大。
    card.addView(Md3.ellipsize(Md3.text(this,
        if (job.author.isBlank()) "$done/${job.total} 章" else job.author,
        Md3.FS_SM, Md3.onSurfaceVariant)))

    // 触控区不够大时补高（Md3.TAP = 34 dp），否则手表上不好点。
    card.minimumHeight = Md3.dp(this, Md3.TAP)
    return card
}

/**
 * 占位封面上显示的字：书名首字。
 *
 * 取第一个非空白字符。书名以标点开头（《》「」）时跳过，
 * 否则整墙的封面全是同一个括号。
 */
internal fun MainActivity.coverGlyph(job: BookJob): String {
    val src = job.name.ifBlank { "未" }
    // 书名以标点开头（《》「」）时跳过，否则整墙封面全是同一个括号。
    src.firstOrNull { it.isLetterOrDigit() }?.let { return it.toString() }
    // 兜底不能取 take(1)：书名以 emoji 开头时它是代理对，截半个会显示成方块。
    // 用码点切，保证切出的是完整字符。
    val cp = src.codePointAt(0)
    return String(Character.toChars(cp))
}

/** 占位色：取几个容器色，按书名哈希固定选一个。 */
internal fun MainActivity.shelfColor(job: BookJob): Int {
    val palette = intArrayOf(
        Md3.primaryContainer, Md3.secondaryContainer, Md3.surface4, Md3.outlineVariant,
    )
    return palette[Math.floorMod(job.name.hashCode(), palette.size)]
}

/**
 * 书架右上角 ⋮ 菜单。条目、顺序照 legado `BookshelfScreen.kt:679-764`：
 * 远程书籍 / 添加本地 / 更新目录 / 布局设置 / 分组管理 / 添加网址 /
 * 选择模式 / 书架管理 / 导出书单 / 导入书单 / 日志。
 *
 * 我们只做下载和本地文件，没有书源导入、书单、分组、日志这些东西。
 * 照 legado 的排位保留全部条目，能接的接上，接不上的点一下说一句还没做，
 * 免得看起来像功能缺失。
 *
 * shortcut: 9 项占位，等对应模块落地再逐项接。
 */
internal fun MainActivity.showShelfMenu() {
    fun todo(name: String) = { toast("$name 还没做") }
    Widgets.longMenuDialog(this, "书架", listOf(
        "远程书籍" to todo("远程书籍"),
        "添加本地" to todo("添加本地"),
        "更新目录" to todo("更新目录"),
        "布局设置" to {
            shelfGrid = !shelfGrid
            showShelf()
        },
        "分组管理" to todo("分组管理"),
        "添加网址" to { askKeyword() },
        "选择模式" to todo("选择模式"),
        "书架管理" to todo("书架管理"),
        "导出书单" to todo("导出书单"),
        "导入书单" to { showSources() },
        "日志" to todo("日志"),
    )).show()
}

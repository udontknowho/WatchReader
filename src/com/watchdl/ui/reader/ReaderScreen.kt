package com.watchdl.ui.reader

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.concurrent.thread
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.book.showSwap
import com.watchdl.ui.search.askText
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File

/**
 * 阅读器与阅读设置（从 MainActivity 原样搬出，行为未改）。
 *
 * 正文排版状态（readerSize/readerSpacing/readerBackground/readerBody/滚动位置）
 * 仍留在 Activity：它们是 onPause / onSaveInstanceState 要存的进程状态，
 * 且扩展属性带初始化器在 Kotlin 里不成立。
 */

/**
 * 阅读页扩展设置与章内查找的进程内状态。
 *
 * 亮度、护眼、段距、缩进、上次查找位置都放这里，不往 MainActivity 加字段：
 * Activity 里那几个是 onPause 要落盘的阅读位置，这几项只影响本页显示。
 */
internal object ReaderUi {
    /** 亮度档：0 暗 / 1 中 / 2 亮。 */
    var brightTier = 1
    /** 护眼遮罩开关。 */
    var eyeCare = false
    /** 段距档：0 无 / 1 半行 / 2 一行。 */
    var paraLevel = 0
    /** 首行缩进开关。 */
    var indentOn = false
    var findQuery = ""
    var findHits: List<ReaderFind.Hit> = emptyList()
    /** 当前停在的命中下标，-1 表示还没定位。 */
    var findAt = -1
    /** 命中列表属于哪一章（书号#章号）。换章后旧偏移失效，必须丢掉。 */
    var findOwner = ""
}

/** 章内查找的结果条数上限。20 条刚好一屏滚两三下，再多就是噪音。 */
private const val FIND_MAX = 20

/** 从 SharedPreferences("reader") 读扩展设置。重复调用无副作用。 */
private fun MainActivity.loadReaderExtras() {
    val p = getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
    ReaderUi.brightTier = ReaderStyle.brightTier(p.getFloat("bright", ReaderStyle.BRIGHT_MID))
    ReaderUi.eyeCare = p.getBoolean("eye", false)
    ReaderUi.paraLevel = p.getInt("para", 0).coerceIn(0, 2)
    ReaderUi.indentOn = p.getBoolean("indent", false)
}

/** 扩展设置落盘，与字号/行距/背景同一处，键名各占一个。 */
private fun MainActivity.saveReaderExtras() {
    getSharedPreferences("reader", android.content.Context.MODE_PRIVATE).edit()
        .putFloat("bright", ReaderStyle.brightValue(ReaderUi.brightTier))
        .putBoolean("eye", ReaderUi.eyeCare)
        .putInt("para", ReaderUi.paraLevel)
        .putBoolean("indent", ReaderUi.indentOn)
        .apply()
}

/** 把亮度档写到当前窗口。legado 是 0–100 滑杆，手表三档就够分辨。 */
private fun MainActivity.applyBright() {
    val w = window ?: return
    val attrs = w.attributes ?: return
    attrs.screenBrightness = ReaderStyle.brightValue(ReaderUi.brightTier)
    w.attributes = attrs
}

/** 屏幕上的正文：缩进开关打开时每段前加两个全角空格。查找偏移必须按这个串算。 */
private fun shownText(text: String): String =
    if (ReaderUi.indentOn) ReaderStyle.indent(text) else text

/** ⑥ 阅读器。 */
internal fun MainActivity.showReader(info: BookInfo, index: Int, text: String, src: BookSource? = null) {
    setScreen(Screen.Reader(info, index, text, src))
    saveProgress(info.bookId, index)
    // 浅色底时整套前景色都要换：正文、顶栏、底栏按钮、进度。
    // 以前只换了正文，白底上顶栏还是黑块，上下章还是浅紫，几乎看不见。
    val light = Color.red(readerBackground) > 100
    val readerInk = if (light) Color.rgb(28, 27, 31) else Md3.onSurface
    val readerAccent = if (light) Color.rgb(56, 30, 114) else Md3.primary
    val readerMuted = if (light) Color.rgb(73, 69, 79) else Md3.onSurfaceVariant
    val hairline = if (light) 0x24000000 else 0x24FFFFFF
    // 扩展设置（亮度、护眼、段距、缩进）与字号/行距/背景同一处落盘。
    loadReaderExtras()
    applyBright()
    // 命中偏移只对当时那一章有意义，换章后旧列表全错位，直接清掉。
    val findKey = "${info.bookId}#$index"
    if (ReaderUi.findOwner != findKey) {
        ReaderUi.findOwner = findKey
        ReaderUi.findQuery = ""
        ReaderUi.findHits = emptyList()
        ReaderUi.findAt = -1
    }
    root.removeAllViews()
    // 整块页身先装进 page，外面再套一层 frame：护眼遮罩要能盖住全屏。
    // 189×248 dp 上遮罩只能做一层不可点的半透明层，撑不出旁的位置。
    val page = Md3.column(this)

    // legado 阅读页顶栏：书名在上，副行是「章名 · 书源名」。
    // 原来只有章名，看完一章不知道读的是哪本书、正文从哪个源来的。
    // BookSource 接口不带名字（规则源才有 displayName），没名字就只显章名。
    val srcName = (src as? LegadoSource)?.displayName
    val subLine = listOfNotNull(
        info.chapters.getOrNull(index)?.title?.takeIf { it.isNotBlank() },
        srcName?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    // 顶栏只留书签：189 dp 宽放不下「返回 + 两个动作 + 书名」。
    // 设置项改从 ⋮ 菜单进，工具格里本来就有一格。
    val bar = Widgets.topBar(this, info.name, onBack = { showToc(info) }, subtitle = subLine)
    bar.setBackgroundColor(readerBackground)
    if (light) Widgets.tintSubtree(bar, readerInk)
    // 书签徽标：对应 legado 的 BookmarkBadgeDelegate。本章有书签时图标转主色。
    // 点一下就地加/移除，不重建页面 —— 重建会把滚动位置打回章首。
    val marked = BookmarkStore.has(info.bookId, index)
    val mark = Icons.view(this, "bookmark", if (marked) readerAccent else readerMuted, Md3.ICON_ACTION)
    mark.minimumWidth = Md3.dp(this, Md3.TAP)
    mark.minimumHeight = Md3.dp(this, Md3.TOP_BAR)
    mark.setPadding(Md3.dp(this, 6), 0, Md3.dp(this, 6), 0)
    mark.isClickable = true
    mark.background = Md3.ripple(this, Color.TRANSPARENT, Md3.dp(this, Md3.TAP / 2).toFloat())
    mark.contentDescription = if (marked) "移除本章书签" else "加书签"
    mark.setOnClickListener {
        addMark(info, index, text)
        val on = BookmarkStore.has(info.bookId, index)
        mark.tint = if (on) readerAccent else readerMuted
        mark.contentDescription = if (on) "移除本章书签" else "加书签"
    }
    bar.addView(mark, LinearLayout.LayoutParams(
        Md3.dp(this, Md3.TAP), Md3.dp(this, Md3.TOP_BAR)))
    page.addView(bar)
    page.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    body.setBackgroundColor(readerBackground)
    val col = Widgets.contentColumn(body)

    // 正文用专用字号（比正文 UI 大），行距 1.8 在手表上偏松，手机上刚好。
    val shown = if (text.isEmpty()) "本章无正文" else shownText(text)
    val tv = Md3.text(this, shown, readerSize, readerInk)
    tv.setLineSpacing(Md3.dp(this, 2).toFloat(), readerSpacing)
    tv.justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
    tv.setPadding(Md3.dp(this, 3), Md3.dp(this, 2), Md3.dp(this, 3), Md3.dp(this, 24))
    applyParaSpacing(tv, shown)
    col.addView(tv)

    page.addView(body, Md3.lpWeight(1f))

    // 工具格：照 legado 底栅的图标网格（每行 5 个 × N 行）。
    // 189 dp 一行放 5 个只剩 30 dp，改成 3×2 六格。
    page.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    val grid = Md3.column(this)
    grid.setBackgroundColor(readerBackground)
    fun toolCell(glyph: String, label: String, act: () -> Unit): LinearLayout {
        val cell = Md3.column(this)
        cell.gravity = Gravity.CENTER
        cell.isClickable = true
        cell.contentDescription = label
        cell.background = Md3.ripple(this, Color.TRANSPARENT, Md3.dp(this, 6).toFloat())
        cell.setOnClickListener { act() }
        // 格子只有 30 dp 高，图标 16 dp + 一行 10 sp 标签刚好放下。
        val icon = Icons.name(glyph)
        cell.addView(
            if (icon != null) Icons.view(this, icon, readerAccent, 16)
            else Md3.text(this, glyph, Md3.FS_MD, readerAccent).apply { gravity = Gravity.CENTER },
            Md3.lpDp(this, null, 16))
        cell.addView(Md3.text(this, label, 10f, readerMuted).apply { gravity = Gravity.CENTER })
        return cell
    }
    val tools = listOf(
        Triple("menu", "目录") { showToc(info, src) },
        // legado 顶栏的搜索图标。书签已上顶栏徽标，这一格让给章内查找。
        Triple("search", "查找") { showFind(info, index, text, src) },
        Triple("swap", "换源") { rememberReturn(); showSwap(info, src) },
        Triple("settings", "设置") { showReaderSettings(info, index, text, src) },
        Triple("download", "缓存") { startDownload(info) },
        Triple("more", "更多") { showReaderMenu(info, index, text, src) },
    )
    for (chunk in tools.chunked(3)) {
        val row = Md3.row(this)
        for ((glyph, label, act) in chunk) {
            row.addView(toolCell(glyph, label, act), LinearLayout.LayoutParams(0,
                Md3.dp(this, 30), 1f))
        }
        grid.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 30)))
    }
    page.addView(grid)
    page.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))

    // 读到哪了：滚动停下再记，滚动过程中每帧写盘没有意义。
    // onPause / onDestroy 会再存一次，覆盖突然退出、切后台的情况。
    readerBody = body
    readerBookId = info.bookId
    readerIndex = index
    body.setOnScrollChangeListener { _, _, y, _, _ ->
        readerOffset = y
        scrollSave?.let { body.removeCallbacks(it) }
        val task = Runnable { saveOffset(info.bookId, index, y) }
        scrollSave = task
        body.postDelayed(task, 400)
    }
    // 恢复上次读到的地方。等布局完成再滚，否则拿不到 maxScroll。
    val restore = readOffset(info.bookId).takeIf { it > 0 }
    if (restore != null) {
        body.post {
            val child = body.getChildAt(0)
            val limit = ((child?.height ?: 0) - body.height +
                body.paddingTop + body.paddingBottom).coerceAtLeast(0)
            body.scrollTo(0, restore.coerceAtMost(limit))
        }
    }

    // 底部：进度 + 上/下一章。两边的点击目标都要够大，手机上单手也能摸到。
    // 底栏必须背实底色 + 一根分隔线：否则正文从按钮下面露出来，看着像排版坏了。
    val foot = Md3.row(this)
    foot.gravity = Gravity.CENTER_VERTICAL
    foot.setPadding(Md3.dp(this, 12), 0, Md3.dp(this, 12), 0)
    foot.setBackgroundColor(readerBackground)
    page.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))

    val prev = Md3.text(this, "‹ 上一章", Md3.FS_MD, readerAccent, bold = true).apply {
        gravity = Gravity.CENTER
    }
    prev.isClickable = true
    prev.minHeight = Md3.dp(this, Md3.HIT)
    prev.setPadding(Md3.dp(this, 4), 0, Md3.dp(this, 4), 0)
    // 与「下一节」对称：本章还有没读完的节时，回头必须先回上一节。
    prev.setOnClickListener {
        prevChapter(info, index)
    }
    // 第一章时置灰并真的禁用：以前只调 alpha，点了还能按，弹一句「已是第一章」。
    if (index <= 0) { prev.alpha = 0.35f; prev.isClickable = false }
    foot.addView(prev)

    val m = Md3.text(this, "${index + 1} / ${info.chapters.size}", Md3.FS_SM, readerMuted).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        // 宽度不够时会折行，把底栏撑成两行（与左右按钮不共基线）。
        setSingleLine(true)
        minHeight = Md3.dp(this@showReader, Md3.HIT)
    }
    foot.addView(m, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val next = Md3.text(this, "下一章 ›", Md3.FS_MD, readerAccent, bold = true).apply {
        gravity = Gravity.CENTER
    }
    next.isClickable = true
    next.minHeight = Md3.dp(this, Md3.HIT)
    next.setPadding(Md3.dp(this, 4), 0, Md3.dp(this, 4), 0)
    // 本章还有未读的节时，这一下必须先翻节 —— 否则读者点「下一章」，
    // 本章后半截就再也不会被看到。
    next.setOnClickListener {
        nextChapter(info, index)
    }
    if (index >= info.chapters.size - 1) {
        next.alpha = 0.35f
        next.isClickable = false
    }
    foot.addView(next)
    // 高度必须容得下 HIT 高的子项，否则页码被挤到第二行（P0-3）。
    foot.layoutParams = Md3.lpDp(this, null, Md3.HIT)
    page.addView(foot)

    // 护眼：整屏暖色半透明层盖在页身上，不消费触摸，滚动照常。
    // legado 是色温矩阵 + 强度滑杆 + 夜间自动；手表只留开关，写进差别表。
    if (ReaderUi.eyeCare) {
        val frame = FrameLayout(this)
        frame.addView(page, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(View(this).apply {
            setBackgroundColor(ReaderStyle.eyeColor())
            // 不拦截触摸：遮罩只上色，翻页与滚动必须还能用。
            isClickable = false
            isFocusable = false
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, Md3.lpWeight(1f))
    } else {
        root.addView(page, Md3.lpWeight(1f))
    }
}

/**
 * 段距：在段末换行符上挂行高 span。
 *
 * 正文是一个 TextView，没有“段落”这个对象，只能给换行符加高度。
 * legado 是滑杆，手表三档（无 / 半行 / 一行）已经能分辨。
 */
private fun MainActivity.applyParaSpacing(tv: TextView, text: CharSequence) {
    val metrics = resources.displayMetrics
    val sizeSp = tv.textSize / metrics.scaledDensity
    val extra = ReaderStyle.paraExtraPx(ReaderUi.paraLevel, sizeSp, readerSpacing, metrics.density)
    if (extra <= 0 || text.isEmpty()) return
    val sp = android.text.SpannableString(text)
    for (i in ReaderStyle.paraEnds(sp)) {
        sp.setSpan(ParaSpan(extra), i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    tv.setText(sp, TextView.BufferType.SPANNABLE)
}

/** 段末行高。chooseHeight 每行会被调两次，加一次就够。 */
private class ParaSpan(private val extraPx: Int) : android.text.style.LineHeightSpan {
    private var done = false
    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int, spanStartV: Int, lineHeight: Int,
        fm: android.graphics.Paint.FontMetricsInt,
    ) {
        if (done) return
        fm.bottom += extraPx
        fm.descent += extraPx
        done = true
    }
}

/**
 * 阅读页 ⋮ 菜单，对应 legado 的 ReadMenuActionDefs。
 *
 * 浮层菜单在 189 dp 宽的屏上几乎占满整屏又要留浮层留白，两头不讨好；
 * 用户判词已定：长菜单一律整屏对话框，所以走 longMenuDialog。
 */
private fun MainActivity.showReaderMenu(
    info: BookInfo, index: Int, text: String, src: BookSource?,
) {
    val items = listOf<Pair<CharSequence, () -> Unit>>(
        "章内查找" to { showFind(info, index, text, src) },
        // legado 顶栏的刷新：重跑一次取正文，复用 preview 的本地缓存 + 在线兜底。
        "重新取本章正文" to { preview(info, index) },
        "阅读设置" to { showReaderSettings(info, index, text, src) },
        "全部书签" to { showMarks(info) },
        "导出 TXT" to { exportTxt(info) },
        "阅读记录" to { showProgress(info) },
    )
    Widgets.longMenuDialog(this, "更多", items).show()
}

/**
 * 章内查找：对应 legado 的 ReadBookMenuBarSearch（输入）+ ReadBookSearchBar（翻处）。
 *
 * 手表只留“关键词 → 结果清单 → 点一条滚过去”。计数与上一处/下一处
 * 放进清单标题与头两行，不做常驻浮层 —— 189 dp 宽没有那块富余。
 */
private fun MainActivity.showFind(
    info: BookInfo, index: Int, text: String, src: BookSource?,
) {
    askText("章内查找", "输入关键词", "请输入关键词") { kw ->
        val hits = ReaderFind.inText(shownText(text), kw, limit = FIND_MAX)
        ReaderUi.findQuery = kw
        ReaderUi.findHits = hits
        ReaderUi.findAt = if (hits.isEmpty()) -1 else 0
        if (hits.isEmpty()) {
            toast("没找到“$kw”")
            return@askText
        }
        scrollToHit(hits[0])
        showFindResults(info, index, text, src)
    }
}

/** 查找结果：上一处 / 下一处 / 换词 + 最多 [FIND_MAX] 条摘录。 */
private fun MainActivity.showFindResults(
    info: BookInfo, index: Int, text: String, src: BookSource?,
) {
    val hits = ReaderUi.findHits
    if (hits.isEmpty()) { showFind(info, index, text, src); return }

    fun jump(at: Int) {
        ReaderUi.findAt = at.coerceIn(0, hits.size - 1)
        scrollToHit(hits[ReaderUi.findAt])
        // 跳完立刻重开清单：标题里的计数要跟着变，手表没有第二块屏放计数条。
        showFindResults(info, index, text, src)
    }

    val items = mutableListOf<Pair<CharSequence, () -> Unit>>(
        "↑ 上一处" to { jump(ReaderStyle.step(ReaderUi.findAt, -1, hits.size)) },
        "↓ 下一处" to { jump(ReaderStyle.step(ReaderUi.findAt, 1, hits.size)) },
        "换个关键词" to { showFind(info, index, text, src) },
    )
    hits.forEachIndexed { i, h ->
        val at = if (i == ReaderUi.findAt) "▶ " else ""
        items.add("$at${h.excerpt}" to { jump(i) })
    }
    Widgets.longMenuDialog(this,
        "查找“${ReaderUi.findQuery}” · ${ReaderStyle.counterText(ReaderUi.findAt, hits.size)}",
        items).show()
}

/**
 * 把正文滚到命中处。
 *
 * 正文是自动折行的 TextView，没有“行号”：用 getLineForOffset(字符偏移) 取行顶，
 * 再加上正文在滚动内容里的顶边与自身 paddingTop，才是该滚到的 y。
 */
private fun MainActivity.scrollToHit(hit: ReaderFind.Hit) {
    val body = readerBody ?: return
    val tv = ((body.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? TextView) ?: return
    if (tv.length() == 0) return
    val layout = tv.layout
    if (layout == null) {
        // 刚换章时 Layout 还没生成，等一帧再来。
        body.post { scrollToHit(hit) }
        return
    }
    val line = layout.getLineForOffset(hit.start.coerceIn(0, layout.text.length))
    val child = body.getChildAt(0)
    val maxY = ((child?.height ?: 0) - body.height +
        body.paddingTop + body.paddingBottom).coerceAtLeast(0)
    body.smoothScrollTo(0, ReaderStyle.scrollTarget(tv.top, tv.paddingTop, layout.getLineTop(line), maxY))
}

/** 上一章。跳过连续的锁定章（付费章取不到正文）。 */
private fun MainActivity.prevChapter(info: BookInfo, index: Int) {
    val prev = index - 1
    if (prev < 0) { toast("已是第一章"); return }
    if (info.chapters[prev].locked) {
        val free = (prev downTo 0).firstOrNull { !info.chapters[it].locked }
        if (free == null) { toast("前面都是付费章"); return }
        preview(info, free)
    } else preview(info, prev)
}
/** 从记录的位置续读：章号 + 章内节号。 */
internal fun MainActivity.preview(info: BookInfo, index: Int) {
    val ch = info.chapters[index]
    // 先读本地正文，只有缺章时才在线程中初始化源并联网。
    val src = (curScreen as? Screen.Toc)?.src
        ?: (curScreen as? Screen.Reader)?.src
        ?: tocSrc
    root.removeAllViews()
    root.addView(Widgets.topBar(this, ch.title, onBack = { showToc(info) }))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    (Widgets.contentColumn(body)).addView(Widgets.emptyState(this, "refresh", "正在取正文…"))
    root.addView(body)

    // 读者停在 Toc / Reader 屏上点章节，回来时应该还在原来的屏。
    val owner: Screen = curScreen

    thread {
        try {
            val line = jobsStore().readChapters(info.bookId)[ch.itemId]
            val localHtml = line?.let { TinyJson.path(it, "content") }
            val actualSrc = if (localHtml == null) (src ?: source) else src
            val html = localHtml ?: actualSrc!!.content(ch.itemId, info.bookId)
            val raw = Downloader.htmlToText(html).ifEmpty { html.replace(Regex("<[^>]+>"), "\n").trim() }
            // 只过滤正文，章标题**不过滤**：目录里的标题一旦被规则吃掉，用户就找不到章，
            // 这个代价比「标题里的广告尾巴没去掉」大得多。所以在 preview 这一处做，
            // 不在目录、不在 showReader。
            val text = ReplaceRuleStore.apply(raw, isTitle = false)
            postAlive(owner) { showReader(info, index, text, actualSrc) }
        } catch (e: Exception) {
            postAlive(owner) { toast("正文失败：${e.message}"); showToc(info, src) }
        }
    }
}

private fun MainActivity.nextChapter(info: BookInfo, index: Int) {
    val next = index + 1
    if (next >= info.chapters.size) { toast("已是最后一章"); return }
    if (info.chapters[next].locked) {
        // 跳过连续的锁定章
        val free = (next until info.chapters.size).firstOrNull { !info.chapters[it].locked }
        if (free == null) { toast("后面都是付费章"); return }
        preview(info, free)
    } else preview(info, next)
}
/**
 * 阅读设置。
 *
 * 一屏装不下：预览一行 + 七行「标签 + 三段」超过 248 dp，
 * 所以内容区可滚动、高度压到 112 dp，标题与「完成」始终可见。
 * 改值只重画预览与按钮配色，不重建视图树。
 */
private fun MainActivity.showReaderSettings(info: BookInfo, index: Int, text: String, src: BookSource?) {
    val sizes = listOf("小" to Md3.FS_MD, "中" to Md3.FS_READ, "大" to Md3.FS_XL)
    val spacings = listOf("紧" to 1.35f, "标准" to 1.75f, "松" to 1.9f)
    val bgs = listOf(
        "深" to Color.rgb(20, 18, 24),
        "浅" to Color.WHITE,
        "暖" to Color.rgb(255, 248, 230),
    )

    val paraLevels = listOf("无" to 0, "半行" to 1, "一行" to 2)
    val indents = listOf("无" to false, "2 字" to true)
    val brights = listOf("暗" to 0, "中" to 1, "亮" to 2)
    val eyes = listOf("关" to false, "开" to true)

    var curSize = readerSize
    var curSpacing = readerSpacing
    var curBg = readerBackground
    var curPara = ReaderUi.paraLevel
    var curIndent = ReaderUi.indentOn
    var curBright = ReaderUi.brightTier
    var curEye = ReaderUi.eyeCare
    var refresh: () -> Unit = {}

    // 内边距全交给 md3Dialog（16dp），这里不再自带 12dp。
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val preview = Md3.text(this, "示例正文，看看效果。", Md3.FS_READ, Md3.onSurface).apply {
        setPadding(Md3.dp(this@showReaderSettings, 8), Md3.dp(this@showReaderSettings, 6),
                   Md3.dp(this@showReaderSettings, 8), Md3.dp(this@showReaderSettings, 6))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    box.addView(preview)

    fun paintPreview() {
        preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, curSize)
        preview.setLineSpacing(Md3.dp(this, 1).toFloat(), curSpacing)
        preview.setTextColor(if (Color.red(curBg) > 100) Color.rgb(28, 27, 31) else Md3.onSurface)
        preview.background = Md3.bg(curBg, Md3.dp(this, 8).toFloat())
    }

    fun persist() {
        saveReaderPrefs(curSize, curSpacing, curBg)
        // 新四项与字号/行距/背景同处落盘；亮度立刻写到窗口，不必等关窗。
        ReaderUi.paraLevel = curPara
        ReaderUi.indentOn = curIndent
        ReaderUi.brightTier = curBright
        ReaderUi.eyeCare = curEye
        saveReaderExtras()
        applyBright()
        refresh()
    }

    /** 一行「标签 + 三段」。返回刷新函数；改值后由 refresh 统一调用。 */
    fun segmentRow(
        label: String,
        options: List<Pair<String, Any>>,
        get: () -> Any,
        set: (Any) -> Unit,
    ): () -> Unit {
        val row = Md3.row(this).apply { setPadding(0, Md3.dp(this@showReaderSettings, 3), 0, 0) }
        row.addView(Md3.text(this, label, Md3.FS_SM, Md3.onSurfaceVariant),
            LinearLayout.LayoutParams(Md3.dp(this, 26), ViewGroup.LayoutParams.WRAP_CONTENT))
        val chips = mutableListOf<Pair<TextView, Any>>()
        options.forEachIndexed { i, opt ->
            val chip = Md3.text(this, opt.first, Md3.FS_SM, Md3.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { set(opt.second); persist() }
            }
            chips.add(chip to opt.second)
            row.addView(chip, LinearLayout.LayoutParams(0, Md3.dp(this, 26), 1f).apply {
                leftMargin = if (i == 0) 0 else Md3.dp(this@showReaderSettings, 4)
            })
        }
        box.addView(row)
        return {
            val cur = get()
            chips.forEach { pair ->
                val sel = pair.second == cur
                pair.first.setTypeface(null,
                    if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                // 选中态与全局一致：浅紫填充 + 深字（发现页、按钮都用这套）。
                // 原来这里用 secondaryContainer 深紫，同一应用两套选中语义。
                pair.first.setTextColor(if (sel) Md3.onPrimary else Md3.onSurfaceVariant)
                pair.first.background = Md3.ripple(this,
                    if (sel) Md3.primary else Color.TRANSPARENT,
                    Md3.dp(this, 13).toFloat(),
                    if (sel) null else Md3.outlineVariant)
            }
        }
    }

    val rowSize = segmentRow("字号", sizes.map { it.first to (it.second as Any) },
        { curSize as Any }) { curSize = it as Float }
    val rowSpacing = segmentRow("行距", spacings.map { it.first to (it.second as Any) },
        { curSpacing as Any }) { curSpacing = it as Float }
    val rowBg = segmentRow("背景", bgs.map { it.first to (it.second as Any) },
        { curBg as Any }) { curBg = it as Int }
    // legado 的 TypographyPage 还有段距、首行缩进；亮度与护眼在阅读设置里。
    // 手表不照搬滑杆：每项三段，标签宽度沿用 26 dp，一行放得下。
    val rowPara = segmentRow("段距", paraLevels.map { it.first to (it.second as Any) },
        { curPara as Any }) { curPara = it as Int }
    val rowIndent = segmentRow("缩进", indents.map { it.first to (it.second as Any) },
        { curIndent as Any }) { curIndent = it as Boolean }
    val rowBright = segmentRow("亮度", brights.map { it.first to (it.second as Any) },
        { curBright as Any }) { curBright = it as Int }
    val rowEye = segmentRow("护眼", eyes.map { it.first to (it.second as Any) },
        { curEye as Any }) { curEye = it as Boolean }

    refresh = {
        rowSize(); rowSpacing(); rowBg(); rowPara(); rowIndent(); rowBright(); rowEye()
        paintPreview()
    }
    refresh()

    // 七行加一行预览在 248 dp 上装不下：内容改成可滚，高度压到 112 dp，
    // 标题与「完成」始终可见（legado 的 TypographyPage 在手机上也是整页滚动）。
    val sv = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(box)
    }
    val holder = FrameLayout(this)
    holder.addView(sv, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 112)))

    // 只有「完成」，没有确认动作：改值已经即时落盘（persist），关窗即生效。
    // 书签开关已上顶栏徽标，这里不再占一个 40 dp 的按钮位。
    val dialog = Widgets.md3Dialog(this, "阅读设置", content = holder,
        confirm = null, dismiss = "完成")
    dialog.setOnDismissListener { showReader(info, index, text, src) }
    dialog.show()
}

package com.watchdl.ui.reader

import android.graphics.Color
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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
    /** 窗口亮度：legado 是 0–100 滑杆，这里存 0.15–1.0 的实际窗口亮度。 */
    var bright = ReaderStyle.BRIGHT_MID
    /** 护眼遮罩开关。 */
    var eyeCare = false
    /** 护眼强度档：0 弱 / 1 中 / 2 强。对应遮罩 alpha。 */
    var eyeLevel = 1
    /** 夜间（22:00–07:00）自动开护眼。 */
    var eyeAuto = false
    /** 段距档：0 无 / 1 半行 / 2 一行。 */
    var paraLevel = 0
    /** 首行缩进档：0 无 / 1 一字 / 2 两字（legado 是 0–4 字滑杆）。 */
    var indentLevel = 0
    /** 字距（em）：负是收紧，legado 是 -0.5–0.5 滑杆。 */
    var letterEm = 0f
    /** 字重档：0 常规 / 1 中粗 / 2 粗。 */
    var weightLevel = 0
    /** 斜体。 */
    var italic = false
    /** 两端对齐（legado 的「文字两端对齐」）。 */
    var justify = true
    /** 标题位置：0 居左 / 1 居中 / 2 不显示。 */
    var titleMode = 0
    /** 标题字号相对正文的增量（sp）。 */
    var titleAdd = 2
    /** 标题加粗。 */
    var titleBold = true
    /** 标题与正文的间距档：0–3，每档 4 dp。 */
    var titleGap = 1
    /** 阅读边距档：0 窄 / 1 标准 / 2 宽。 */
    var marginLevel = 1
    /** 阅读时屏幕常亮（legado 的「屏幕超时」）。 */
    var keepOn = false
    /** 顶栏书签徽标（legado 的「书签徽标」）。 */
    var badgeOn = true

    /**
     * 正在显示的正文与标题。
     *
     * 设置面板要“改一项、正文立刻变”，重建视图树会把滚动位置打回章首，
     * 所以只重排这两个控件。离开阅读页时[ReaderMenu.reset] 一并置空。
     */
    var bodyView: TextView? = null
    var titleView: TextView? = null
    var findQuery = ""
    var findHits: List<ReaderFind.Hit> = emptyList()
    /** 当前停在的命中下标，-1 表示还没定位。 */
    var findAt = -1
    /** 命中列表属于哪一章（书号#章号）。换章后旧偏移失效，必须丢掉。 */
    var findOwner = ""
}

/**
 * 阅读页菜单的显隐状态。
 *
 * 菜单收起时屏上没有返回箭头，系统返回必须先收菜单、再退回目录 ——
 * 这条规则不能只写在某个 View 的点击里，所以状态放在对象上：
 * showReader 每次重建都覆盖 [onClose]，离开阅读页时 [reset]。
 */
internal object ReaderMenu {
    /** 菜单当前是否展开。 */
    var open = false
    /** 收起菜单的闭包，由当前阅读页挂上。 */
    var onClose: (() -> Unit)? = null

    /** 收起并断开与页面的连接。换章、回目录、离屏时调。 */
    fun reset() {
        open = false
        onClose = null
        // 正文/标题控件是上一屏的，留着就是泄漏旧视图树。
        ReaderUi.bodyView = null
        ReaderUi.titleView = null
    }

    /** 系统返回：菜单展开时先收菜单。返回 true 表示已消费这次返回。 */
    fun consumeBack(): Boolean {
        if (!open) return false
        open = false
        val close = onClose
        close?.invoke()
        return close != null
    }
}

/** 章内查找的结果条数上限。20 条刚好一屏滚两三下，再多就是噪音。 */
private const val FIND_MAX = 20

/** 从 SharedPreferences("reader") 读扩展设置。重复调用无副作用。 */
internal fun MainActivity.loadReaderExtras() {
    val p = getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
    ReaderUi.bright = ReaderStyle.brightClamp(p.getFloat("bright", ReaderStyle.BRIGHT_MID))
    ReaderUi.eyeCare = p.getBoolean("eye", false)
    ReaderUi.eyeLevel = p.getInt("eyeLevel", 1).coerceIn(0, 2)
    ReaderUi.eyeAuto = p.getBoolean("eyeAuto", false)
    ReaderUi.paraLevel = p.getInt("para", 0).coerceIn(0, 2)
    // 旧键 "indent" 是布尔（有/无 2 字），新键 "indentLevel" 是 0–2 档。
    // 读旧键时 getInt 会抛类型异常，所以先取布尔再折算，不能两把键混用一个名。
    ReaderUi.indentLevel = p.getInt("indentLevel", if (p.getBoolean("indent", false)) 2 else 0)
        .coerceIn(0, 2)
    ReaderUi.letterEm = ReaderStyle.letterEm(p.getFloat("letter", 0f))
    ReaderUi.weightLevel = p.getInt("weight", 0).coerceIn(0, 2)
    ReaderUi.italic = p.getBoolean("italic", false)
    ReaderUi.justify = p.getBoolean("justify", true)
    ReaderUi.titleMode = p.getInt("titleMode", 0).coerceIn(0, 2)
    ReaderUi.titleAdd = p.getInt("titleAdd", 2).coerceIn(0, 8)
    ReaderUi.titleBold = p.getBoolean("titleBold", true)
    ReaderUi.titleGap = p.getInt("titleGap", 1).coerceIn(0, 3)
    ReaderUi.marginLevel = p.getInt("margin", 1).coerceIn(0, 2)
    ReaderUi.keepOn = p.getBoolean("keepOn", false)
    ReaderUi.badgeOn = p.getBoolean("badge", true)
}

/** 扩展设置落盘，与字号/行距/背景同一处，键名各占一个。 */
internal fun MainActivity.saveReaderExtras() {
    getSharedPreferences("reader", android.content.Context.MODE_PRIVATE).edit()
        .putFloat("bright", ReaderStyle.brightClamp(ReaderUi.bright))
        .putBoolean("eye", ReaderUi.eyeCare)
        .putInt("eyeLevel", ReaderUi.eyeLevel)
        .putBoolean("eyeAuto", ReaderUi.eyeAuto)
        .putInt("para", ReaderUi.paraLevel)
        .putInt("indentLevel", ReaderUi.indentLevel)
        .putFloat("letter", ReaderUi.letterEm)
        .putInt("weight", ReaderUi.weightLevel)
        .putBoolean("italic", ReaderUi.italic)
        .putBoolean("justify", ReaderUi.justify)
        .putInt("titleMode", ReaderUi.titleMode)
        .putInt("titleAdd", ReaderUi.titleAdd)
        .putBoolean("titleBold", ReaderUi.titleBold)
        .putInt("titleGap", ReaderUi.titleGap)
        .putInt("margin", ReaderUi.marginLevel)
        .putBoolean("keepOn", ReaderUi.keepOn)
        .putBoolean("badge", ReaderUi.badgeOn)
        .apply()
}

/** 把亮度档写到当前窗口，顺手按「屏幕常亮」加减 FLAG_KEEP_SCREEN_ON。 */
private fun MainActivity.applyBright() {
    val w = window ?: return
    val attrs = w.attributes ?: return
    attrs.screenBrightness = ReaderStyle.brightClamp(ReaderUi.bright)
    w.attributes = attrs
    if (ReaderUi.keepOn) {
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** 护眼是否生效：开关打开，或「夜间自动」且当下在 22:00–07:00。 */
private fun eyeActive(): Boolean = ReaderUi.eyeCare ||
    (ReaderUi.eyeAuto && ReaderStyle.isNight(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)))

/** 屏幕上的正文：首行缩进按档位补全角空格。查找偏移必须按这个串算。 */
private fun shownText(text: String): String = ReaderStyle.indent(text, ReaderUi.indentLevel)

/** ⑥ 阅读器。 */
internal fun MainActivity.showReader(info: BookInfo, index: Int, text: String, src: BookSource? = null) {
    setScreen(Screen.Reader(info, index, text, src))
    // setScreen 只切状态、撤后台任务，**不碰视图树**。preview() 先铺了顶栏 +「正在取正文…」，
    // 这里不清干净，正文那一帧会与加载页各占 root 的一半（两个 weight=1）：
    // 上半屏卡着加载提示、下半屏才是正文，每次换章重画一次就是一闪一闪。
    root.removeAllViews()
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
    // 沉浸阅读：正文铺满整屏，顶栏 / 工具格 / 底栏收进一层浮层。
    // 照 legado 的 ReadBookMenuBar：上下两条 + 全屏透明关闭层，正文区不压暗、不推移。
    // 以前三段常驻，189×248 dp 上正文只剩 100 dp 出头。
    ReaderMenu.reset()
    val frame = FrameLayout(this)
    /** 菜单显隐。先置空再赋值：关闭层与点击分区都要先引用它。 */
    var setMenu: (Boolean) -> Unit = {}

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    body.setBackgroundColor(readerBackground)
    // 顶栏收走后首行会顶到表壳圆弧上：四边补回页边距（scrollBody 只给了左右下）。
    // 阅读边距按「边距」档走：legado 的边距是四边滑杆，手表三档就够。
    val pad = Md3.dp(this, ReaderStyle.marginDp(ReaderUi.marginLevel))
    body.setPadding(pad, pad, pad, pad)
    val col = Widgets.contentColumn(body)

    // 标题：legado 把章节标题排正文之前（TypographyTitleTab 的「标题位置」）。
    // 先建好再交给 restyleTitle 上样式，设置里改字号/字重/位置都不必重建页面。
    val titleTv = Md3.text(this, info.chapters.getOrNull(index)?.title.orEmpty(),
        readerSize + ReaderUi.titleAdd, readerInk, bold = ReaderUi.titleBold)
    col.addView(titleTv)
    ReaderUi.titleView = titleTv

    // 正文用专用字号（比正文 UI 大），行距 1.8 在手表上偏松，手机上刚好。
    val shown = if (text.isEmpty()) "本章无正文" else shownText(text)
    val tv = Md3.text(this, shown, readerSize, readerInk)
    tv.justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
    tv.setPadding(Md3.dp(this, 3), Md3.dp(this, 2), Md3.dp(this, 3), Md3.dp(this, 24))
    col.addView(tv)
    ReaderUi.bodyView = tv
    // 字重/斜体/字距/行距/段距/缩进一次性套上：与设置面板改值走同一个函数，
    // 两边样式不会走形。
    restyleReader(text)
    frame.addView(body, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    // 点屏幕中列 = 菜单显隐（legado 的九宫格里中间格是菜单）。
    // 左右两列沿用「上一章 / 下一章」：legado 那里是翻页，手表是滚动阅读，没有页可翻。
    // 用 OnTouchListener 旁听而不消费事件，竖滑仍由 ScrollView 接管，滚动手感不变。
    val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val w = body.width
            if (w > 0) {
                when {
                    e.x < w / 3f -> prevChapter(info, index)
                    e.x > w * 2 / 3f -> nextChapter(info, index)
                    else -> setMenu(!ReaderMenu.open)
                }
            }
            return true
        }
    })
    body.setOnTouchListener { _, e ->
        taps.onTouchEvent(e)
        // 返回 false：事件继续交给 ScrollView，滚动、表冠、长按都不受影响。
        false
    }

    // ── 菜单浮层 ──────────────────────────────────────
    // 关闭层全屏可点、不压暗：legado 的 ReaderMenuDismissLayer 就是一层透明可点区域。
    val menu = FrameLayout(this)
    menu.visibility = View.GONE
    menu.addView(View(this).apply {
        isClickable = true
        setOnClickListener { setMenu(false) }
    }, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    // 顶栏：返回 + 书名 / 「章名 · 书源名」+ 书签徽标。
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
    // 「书签徽标」关掉时顶栏右侧空着，只留返回。
    if (ReaderUi.badgeOn) {
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
    }
    val topStack = Md3.column(this)
    topStack.setBackgroundColor(readerBackground)
    // 顶栏空白处的点按不穿透到关闭层：整条是菜单的一部分，不是「点一下收菜单」。
    topStack.isClickable = true
    topStack.addView(bar)
    topStack.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    menu.addView(topStack, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

    // 工具格：照 legado 底栏的图标网格（手机上一行 5 格）。
    // 189 dp 一行放 5 个只剩 30 dp，改成一行 3 格 × 2 行。
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

    // 底部：进度 + 上/下一章。两边的点击目标都要够大，手机上单手也能摸到。
    // 底栏必须背实底色 + 一根分隔线：否则正文从按钮下面露出来，看着像排版坏了。
    val foot = Md3.row(this)
    foot.gravity = Gravity.CENTER_VERTICAL
    foot.setPadding(Md3.dp(this, 12), 0, Md3.dp(this, 12), 0)
    foot.setBackgroundColor(readerBackground)

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

    // 底栏 = 工具格 + 进度行。legado 是「进度行在上、工具格在下」，手表反过来：
    // 表壳是圆的，底部 48 dp 处可见弦宽约 164 dp，30 dp 的格子放到最底下一行会被切掉。
    val bottom = Md3.column(this)
    bottom.setBackgroundColor(readerBackground)
    bottom.isClickable = true
    bottom.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    bottom.addView(grid)
    bottom.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    bottom.addView(foot)
    menu.addView(bottom, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

    setMenu = { on ->
        ReaderMenu.open = on
        menu.animate().cancel()
        topStack.animate().cancel()
        bottom.animate().cancel()
        if (on) {
            menu.visibility = View.VISIBLE
            menu.alpha = 0f
            topStack.translationY = -Md3.dp(this, 20).toFloat()
            bottom.translationY = Md3.dp(this, 20).toFloat()
            menu.animate().alpha(1f).setDuration(120).start()
            topStack.animate().translationY(0f).setDuration(140).start()
            bottom.animate().translationY(0f).setDuration(140).start()
        } else {
            menu.animate().alpha(0f).setDuration(90).withEndAction {
                // 动画途中又展开了就不能隐藏：判断当前状态，不判断这一次的意图。
                if (!ReaderMenu.open) menu.visibility = View.GONE
            }.start()
        }
    }
    // 系统返回先收菜单（见 MainActivity.onBackPressed）：菜单是浮层，不是新的一屏。
    ReaderMenu.onClose = { setMenu(false) }

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

    // 护眼：整屏暖色半透明层盖在正文上，不消费触摸，滚动照常。
    // legado 是色温矩阵 + 强度滑杆 + 夜间自动；这里用同色遮罩 + 三档强度 + 时间窗自动。
    if (eyeActive()) {
        frame.addView(View(this).apply {
            setBackgroundColor(ReaderStyle.eyeColor(ReaderStyle.eyeAlpha(ReaderUi.eyeLevel)))
            // 不拦截触摸：遮罩只上色，翻页与滚动必须还能用。
            isClickable = false
            isFocusable = false
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    // 菜单最后加：z 序在正文与护眼层之上。
    frame.addView(menu, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.addView(frame, Md3.lpWeight(1f))

    // 第一次进阅读页给一次提示：菜单默认收起，不说一句没人知道点中间。
    val hint = getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
    if (!hint.getBoolean("hint_tap", false)) {
        hint.edit().putBoolean("hint_tap", true).apply()
        toast("点屏幕中间出菜单")
    }
}

/**
 * 把当前排版设置套到正在显示的正文与标题上。
 *
 * 设置面板每改一项都调这里，不重建视图树 —— 重建会把滚动位置打回章首，
 * 「关窗就跳章首」比设置不同步还刺眼。
 * 字重/斜体/字距/缩进这几项 legado 都有，原先一律写死，现在由这里统一上。
 */
private fun MainActivity.restyleReader(raw: String) {
    val tv = ReaderUi.bodyView ?: return
    val light = Color.red(readerBackground) > 100
    val ink = if (light) Color.rgb(28, 27, 31) else Md3.onSurface
    // 缩进改的是文本本身，先把文本重设一遍再挂段距 span：
    // 旧的 ParaSpan 不会自己脱落，段距改回「无」时必须整串重设。
    val shown = if (raw.isEmpty()) "本章无正文" else shownText(raw)
    tv.setText(shown, TextView.BufferType.SPANNABLE)
    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, readerSize)
    tv.setTextColor(ink)
    tv.setLineSpacing(Md3.dp(this, 2).toFloat(), readerSpacing)
    tv.setLetterSpacing(ReaderStyle.letterEm(ReaderUi.letterEm))
    tv.justificationMode = if (ReaderUi.justify) {
        android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
    } else {
        android.text.Layout.JUSTIFICATION_MODE_NONE
    }
    tv.setTypeface(
        android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF, ReaderStyle.weightValue(ReaderUi.weightLevel)),
        if (ReaderUi.italic) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL)
    applyParaSpacing(tv, shown)
    restyleTitle(ink)
}

/** 标题样式。位置 / 字号 / 字重 / 下间距四项都跟着设置变。 */
private fun MainActivity.restyleTitle(ink: Int) {
    val t = ReaderUi.titleView ?: return
    t.visibility = if (ReaderUi.titleMode == 2) View.GONE else View.VISIBLE
    if (ReaderUi.titleMode == 2) return
    t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, readerSize + ReaderUi.titleAdd)
    t.setTextColor(ink)
    t.setTypeface(
        android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF, if (ReaderUi.titleBold) 700 else 400))
    t.gravity = if (ReaderUi.titleMode == 1) Gravity.CENTER else Gravity.START
    t.setPadding(Md3.dp(this, 3), Md3.dp(this, 2), Md3.dp(this, 3),
        Md3.dp(this, ReaderStyle.titleGapDp(ReaderUi.titleGap)))
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
    // 直接拿正文控件：正文前面还排了标题，取「第一个子控件的第一个子控件」会拿到标题。
    val tv = ReaderUi.bodyView ?: return
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
    // 正文要重取，屏上的菜单连着的旧视图树跟着作废。
    com.watchdl.ui.reader.ReaderMenu.reset()
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
 * 阅读设置。分页照 legado 的 `ReadStyleSheet`：正文 / 标题 / 主题 / 更多。
 *
 * 每一项都照 legado 的 `TypographyTabs` / `BgTextConfigSheet` / `MoreConfigSheet` 取，
 * 连续量用滑杆（legado 的 `TinySliderSettingItem` 也是滑杆），离散量用档位段。
 * 改一项就套到正文上（[restyleReader]），关窗不再重建页面 —— 重建会把滚动位置打回章首。
 *
 * 高度预算：189×248 dp 上，对话框内边距 16×2 + 标题 28 + 间距 12 + 内容 120 + 按钮 40 = 244 dp，
 * 内容里页签占 24 dp、滚动区占 96 dp（约两行滑杆或三行档位）。再高就把按钮挤出屏。
 */
private fun MainActivity.showReaderSettings(info: BookInfo, index: Int, text: String, src: BookSource?) {
    fun save() {
        saveReaderPrefs(readerSize, readerSpacing, readerBackground)
        saveReaderExtras()
        applyBright()
        restyleReader(text)
    }

    val tabs = listOf("正文", "标题", "主题", "更多")
    val tabRow = Md3.row(this)
    val page = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
    val tabViews = mutableListOf<TextView>()
    var curTab = -1
    /** 当前页的「选中态刷新」集合，切页即弃。 */
    var refreshers: List<() -> Unit> = emptyList()
    /**
     * 切页。声明成变量是因为「恢复默认」「背景」「护眼」都要回头重画当前页，
     * 而它们自己的构建函数又在后面 —— Kotlin 的局部函数只能先声明后调用。
     */
    var showTab: (Int) -> Unit = {}

    /** 档位行：标签 + 若干等宽档。选中态沿用全局（浅紫填充 + 深字）。 */
    fun seg(col: LinearLayout, label: String, options: List<Pair<String, Any>>, get: () -> Any, set: (Any) -> Unit) {
        val row = Md3.row(this).apply { setPadding(0, Md3.dp(this@showReaderSettings, 3), 0, 0) }
        row.addView(Md3.text(this, label, Md3.FS_SM, Md3.onSurfaceVariant),
            LinearLayout.LayoutParams(Md3.dp(this, 30), ViewGroup.LayoutParams.WRAP_CONTENT))
        val chips = mutableListOf<Pair<TextView, Any>>()
        options.forEachIndexed { i, opt ->
            val chip = Md3.text(this, opt.first, Md3.FS_SM, Md3.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener {
                    set(opt.second)
                    save()
                    refreshers.forEach { r -> r() }
                }
            }
            chips.add(chip to opt.second)
            row.addView(chip, LinearLayout.LayoutParams(0, Md3.dp(this, 26), 1f).apply {
                leftMargin = if (i == 0) 0 else Md3.dp(this@showReaderSettings, 4)
            })
        }
        col.addView(row)
        refreshers = refreshers + {
            val cur = get()
            chips.forEach { pair ->
                val sel = pair.second == cur
                pair.first.setTypeface(null,
                    if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                pair.first.setTextColor(if (sel) Md3.onPrimary else Md3.onSurfaceVariant)
                pair.first.background = Md3.ripple(this,
                    if (sel) Md3.primary else Color.TRANSPARENT,
                    Md3.dp(this, 13).toFloat(),
                    if (sel) null else Md3.outlineVariant)
            }
        }
    }

    /** 滑杆行：标签与数值一行、滑杆一行（与 legado 的滑杆项同构，见 Widgets.sliderRow）。 */
    fun slider(
        col: LinearLayout, label: String, min: Float, max: Float, steps: Int,
        value: Float, fmt: (Float) -> String, set: (Float) -> Unit,
    ) {
        col.addView(Widgets.sliderRow(this, label, min, max, value, steps, fmt) { v ->
            set(v)
            save()
        })
    }

    // ── 正文：legado TypographyBodyTab ──────────────────────
    fun buildBody(): View {
        val col = Md3.column(this)
        slider(col, "字号", 10f, 24f, 14, readerSize, { "${it.toInt()}" }) { readerSize = it }
        slider(col, "行距", 1.2f, 2.4f, 12, readerSpacing, { String.format("%.1f", it) }) { readerSpacing = it }
        slider(col, "段距", 0f, 2f, 2, ReaderUi.paraLevel.toFloat(),
            { listOf("无", "半行", "一行")[it.toInt().coerceIn(0, 2)] }) {
            ReaderUi.paraLevel = it.toInt()
        }
        slider(col, "缩进", 0f, 2f, 2, ReaderUi.indentLevel.toFloat(),
            { listOf("无", "1 字", "2 字")[it.toInt().coerceIn(0, 2)] }) {
            ReaderUi.indentLevel = it.toInt()
        }
        slider(col, "字距", -0.05f, 0.10f, 15, ReaderUi.letterEm,
            { String.format("%.2f", it) }) { ReaderUi.letterEm = it }
        seg(col, "字重", listOf("常规" to 0 as Any, "中粗" to 1, "粗" to 2),
            { ReaderUi.weightLevel as Any }) { ReaderUi.weightLevel = it as Int }
        seg(col, "斜体", listOf("关" to false as Any, "开" to true),
            { ReaderUi.italic as Any }) { ReaderUi.italic = it as Boolean }
        seg(col, "两端", listOf("关" to false as Any, "开" to true),
            { ReaderUi.justify as Any }) { ReaderUi.justify = it as Boolean }
        return col
    }

    // ── 标题：legado TypographyTitleTab ─────────────────────
    fun buildTitle(): View {
        val col = Md3.column(this)
        seg(col, "位置", listOf("居左" to 0 as Any, "居中" to 1, "隐藏" to 2),
            { ReaderUi.titleMode as Any }) { ReaderUi.titleMode = it as Int }
        slider(col, "字号", 0f, 8f, 4, ReaderUi.titleAdd.toFloat(),
            { "+${it.toInt()}" }) { ReaderUi.titleAdd = it.toInt() }
        slider(col, "间距", 0f, 12f, 3, (ReaderUi.titleGap * 4).toFloat(),
            { "${it.toInt()} dp" }) { ReaderUi.titleGap = (it / 4f).toInt() }
        seg(col, "字重", listOf("常规" to false as Any, "加粗" to true),
            { ReaderUi.titleBold as Any }) { ReaderUi.titleBold = it as Boolean }
        return col
    }

    // ── 主题：legado GlobalThemePage + BgTextConfigSheet + EyeProtection ──
    fun buildTheme(): View {
        val col = Md3.column(this)
        val bgs = listOf(
            "深" to Color.rgb(20, 18, 24),
            "浅" to Color.WHITE,
            "暖" to Color.rgb(255, 248, 230),
            "米" to Color.rgb(232, 225, 210),
        )
        seg(col, "背景", bgs.map { it.first to (it.second as Any) }, { readerBackground as Any }) {
            readerBackground = it as Int
            // 底色一变，整套前景色（顶栏、按钮、进度）都要跟着换，只能重建页面。
            // 其余项都只重排正文，所以这一个开关允许重建。
            save()
            showReader(info, index, text, src)
        }
        slider(col, "亮度", 0.15f, 1f, 17, ReaderUi.bright,
            { "${(it * 100).toInt()}%" }) { ReaderUi.bright = it }
        seg(col, "护眼", listOf("关" to false as Any, "开" to true),
            { ReaderUi.eyeCare as Any }) { ReaderUi.eyeCare = it as Boolean; save(); showReader(info, index, text, src) }
        slider(col, "强度", 0f, 2f, 2, ReaderUi.eyeLevel.toFloat(),
            { listOf("弱", "中", "强")[it.toInt().coerceIn(0, 2)] }) {
            ReaderUi.eyeLevel = it.toInt()
        }
        seg(col, "夜间", listOf("关" to false as Any, "开" to true),
            { ReaderUi.eyeAuto as Any }) { ReaderUi.eyeAuto = it as Boolean }
        slider(col, "边距", 0f, 2f, 2, ReaderUi.marginLevel.toFloat(),
            { listOf("窄", "标准", "宽")[it.toInt().coerceIn(0, 2)] }) {
            ReaderUi.marginLevel = it.toInt()
        }
        return col
    }

    // ── 更多：legado MoreConfigSheet 里手表用得上的那几项 ────
    fun buildMore(): View {
        val col = Md3.column(this)
        seg(col, "常亮", listOf("关" to false as Any, "开" to true),
            { ReaderUi.keepOn as Any }) { ReaderUi.keepOn = it as Boolean }
        seg(col, "徽标", listOf("隐藏" to false as Any, "显示" to true),
            { ReaderUi.badgeOn as Any }) {
            ReaderUi.badgeOn = it as Boolean
            save()
            showReader(info, index, text, src)
        }
        // 恢复默认：正文各项回出厂值。书源与进度不动，只动排版。
        val reset = Md3.text(this, "恢复默认排版", Md3.FS_SM, Md3.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            isClickable = true
            background = Md3.ripple(this@showReaderSettings, Color.TRANSPARENT,
                Md3.dp(this@showReaderSettings, 13).toFloat(), Md3.outlineVariant)
            setOnClickListener {
                readerSize = Md3.FS_READ
                readerSpacing = 1.75f
                ReaderUi.paraLevel = 0
                ReaderUi.indentLevel = 0
                ReaderUi.letterEm = 0f
                ReaderUi.weightLevel = 0
                ReaderUi.italic = false
                ReaderUi.justify = true
                ReaderUi.titleMode = 0
                ReaderUi.titleAdd = 2
                ReaderUi.titleBold = true
                ReaderUi.titleGap = 1
                ReaderUi.marginLevel = 1
                ReaderUi.bright = ReaderStyle.BRIGHT_MID
                save()
                showTab(curTab)
                toast("排版已恢复默认")
            }
        }
        col.addView(reset, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 30)).apply {
            topMargin = Md3.dp(this@showReaderSettings, 4)
        })
        return col
    }

    showTab = { i ->
        curTab = i
        refreshers = emptyList()
        page.removeAllViews()
        val col = when (i) {
            1 -> buildTitle()
            2 -> buildTheme()
            3 -> buildMore()
            else -> buildBody()
        }
        page.addView(col)
        tabViews.forEachIndexed { k, v ->
            val sel = k == i
            v.setTextColor(if (sel) Md3.onPrimary else Md3.onSurfaceVariant)
            v.setTypeface(null, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            v.background = Md3.ripple(this, if (sel) Md3.primary else Color.TRANSPARENT,
                Md3.dp(this, 13).toFloat(), if (sel) null else Md3.outlineVariant)
        }
        refreshers.forEach { r -> r() }
    }

    tabs.forEachIndexed { i, name ->
        val t = Md3.text(this, name, Md3.FS_SM, Md3.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { showTab(i) }
        }
        tabViews.add(t)
        tabRow.addView(t, LinearLayout.LayoutParams(0, Md3.dp(this, 24), 1f).apply {
            leftMargin = if (i == 0) 0 else Md3.dp(this@showReaderSettings, 3)
        })
    }

    val holder = FrameLayout(this)
    holder.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tabRow)
        addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this@showReaderSettings, 96)))
    })

    // 关窗不再重建页面：设置已经即时套在正文上了，重建只会把滚动位置打回章首。
    val dialog = Widgets.md3Dialog(this, "阅读设置", content = holder,
        confirm = null, dismiss = "完成")
    dialog.show()
    // 重建默认页要在 dialog.show 之后：showTab 里要量高，视图得已经挂上窗。
    showTab(0)
}

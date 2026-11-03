package com.watchdl.ui.reader

import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
    /**
     * 亮度跟随系统（legado 的 BrightnessAuto）。
     *
     * 为真时 [applyBright] 不再写死窗口亮度，而是交回 -1f；用户一拖亮度滑条就自动关掉，
     * 与 legado 的 ToggleBrightnessAuto 同一个语义。
     */
    var brightAuto = false
    /** 工具格按钮（legado 的 `bottomBarButtons`），按顺序一行排开，csv 存 `tools` 键。 */
    var tools: List<String> = listOf("search", "auto_page", "catalog", "setting")
    /** 自动翻页间隔秒数（legado 的 `autoReadSpeed`，手机端 1–120，手表 1–60 够用）。 */
    var autoPageSec = 10
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

    /**
     * 换章的序号令牌。
     *
     * 换章不清屏了（旧页继续画着），连点两下就会有两个线程在跑，
     * 先发的那次可能后回来盖掉新章 —— 只认最后一次派发的令牌。
     */
    var pageToken = 0
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
        // 自动翻页的回调里握着上一屏的 ScrollView，不摘掉就会继续翻章。
        // 这里只取消待跑的回调，不动用户开关：换章重建后要接着翻（showReader 末尾重新排程）。
        ReaderAuto.cancel()
        ReaderAuto.reschedule = null
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

/**
 * 自动翻页（legado 的 `autoPage` 动作）。
 *
 * 只有一个 Handler：点一下开跑、每 [sec] 秒滚一屏，再点停。运行态不落盘：
 * 它是“现在要不要自动读”的临时状态，重启应用后接着自动翻章只会把人吓一跳。
 */
internal object ReaderAuto {
    /** 用户是否开着自动翻页。换章重建后要保持，所以不跟 [ReaderMenu.reset] 一起清。 */
    var on = false
    /** 间隔秒数，从 [ReaderUi.autoPageSec] 同步过来。 */
    var sec = 10
    val MAIN = Handler(Looper.getMainLooper())
    /** 待跑的滚动任务，停的时候必须摘掉，否则会叠出两条链。 */
    var handle: Runnable? = null

    /**
     * 重新排程的闭包，由当前阅读页挂上（换章重建覆盖，离开阅读页清空）。
     *
     * 更多操作 Sheet 与阅读设置都能改自动翻页的开关，它们手里没有页面局部的
     * `body`，所以排程这件事必须挂在状态对象上，不能只在 showReader 里。
     */
    var reschedule: (() -> Unit)? = null

    /** 只取消待跑的回调，不改开关。 */
    fun cancel() {
        handle?.let { MAIN.removeCallbacks(it) }
        handle = null
    }

    /** 取消并关掉开关。 */
    fun stop() {
        cancel()
        on = false
    }

    /** 开关自动翻页并立即生效：菜单里点一下就该开始滚，不必等下一次重建。 */
    fun applyOn(open: Boolean) {
        if (open) on = true else stop()
        reschedule?.invoke()
    }
}

/** 章内查找的结果条数上限。20 条刚好一屏滚两三下，再多就是噪音。 */
private const val FIND_MAX = 20

/** 工具格能放的按钮 id（legado 的 `ReadBookButtonIds` 里手表认得的那些）。 */
private val READER_TOOLS = listOf(
    "search", "auto_page", "catalog", "setting", "change_source", "cache", "bookmark", "more",
)

/** 默认四格 = legado 底栏实际显示的四个（read_aloud 在 legado 里没图标文案，永远不显示）。 */
private val READER_TOOLS_DEFAULT = listOf("search", "auto_page", "catalog", "setting")

/** 工具格 id → 图标。跟 legado 的 `ReadMenuActionDefs` 同一套图形。 */
private fun readerToolGlyph(id: String): String = when (id) {
    "catalog" -> "menu"
    "setting" -> "settings"
    "change_source" -> "swap"
    "cache" -> "download"
    "bookmark" -> "bookmark"
    "more" -> "more"
    "auto_page" -> "play"
    else -> "search"
}

/** 工具格 id → 文案。 */
private fun readerToolTitle(id: String): String = when (id) {
    "catalog" -> "目录"
    "setting" -> "设置"
    "change_source" -> "换源"
    "cache" -> "缓存"
    "bookmark" -> "书签"
    "more" -> "更多"
    "auto_page" -> "自动翻页"
    else -> "查找"
}

/**
 * 工具格 id 列表：csv → 合法 id 序列。
 *
 * 空、全是脏值、或存了不认得的 id 时退回默认四格：底栏不能出现空格子，
 * 也不能因为一个写错的键就整行消失。
 */
private fun readerTools(csv: String?): List<String> {
    val ids = csv.orEmpty().split(',').map { it.trim() }.filter { it in READER_TOOLS }.distinct()
    return if (ids.isEmpty()) READER_TOOLS_DEFAULT else ids
}

/** 从 SharedPreferences("reader") 读扩展设置。重复调用无副作用。 */
internal fun MainActivity.loadReaderExtras() {
    val p = getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
    ReaderUi.bright = ReaderStyle.brightClamp(p.getFloat("bright", ReaderStyle.BRIGHT_MID))
    ReaderUi.brightAuto = p.getBoolean("brightAuto", false)
    ReaderUi.tools = readerTools(p.getString("tools", null))
    ReaderUi.autoPageSec = p.getInt("autoPageSec", 10).coerceIn(1, 60)
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
        .putBoolean("brightAuto", ReaderUi.brightAuto)
        .putString("tools", ReaderUi.tools.joinToString(","))
        .putInt("autoPageSec", ReaderUi.autoPageSec)
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
    // 跟随系统时交回 -1f（BRIGHTNESS_OVERRIDE_NONE）：系统按环境光自己调，比锁死一档舒服。
    attrs.screenBrightness = if (ReaderUi.brightAuto) -1f else ReaderStyle.brightClamp(ReaderUi.bright)
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
internal fun MainActivity.showReader(
    info: BookInfo,
    index: Int,
    text: String,
    src: BookSource? = null,
    /** 直接把正文落在章末（从上一屏退上来的跨章）。 */
    atEnd: Boolean = false,
) {
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

    // ── 点击分区：上屏回滚一屏 / 中段出菜单 / 下屏前进一屏 ─────────────
    // 分区边界照 legado 九宫格的**行**分界（33% / 66%，`feature/reader/core/gesture/
    // ReaderTapAction.kt:44-48`）。手表屏只有 189 dp 宽，三列各 63 dp，窄于一个
    // 触摸目标，而竖滑又归 ScrollView，横向分列只会误触 —— 所以这里只分纵轴三段。
    //
    // 动作语义照 legado 的滚动模式：九宫格的「下一页」在滚动模式下被改调
    // tapScrollPage（`feature/reader/ReaderCanvasSurface.kt:718-729`），即平滑滚一屏，
    // 滚到章末才换章；「上一页」同理，退到章首才回上一章。以前左右三列直接
    // nextChapter / prevChapter，点一下就把整章读丢了，手感全错。
    //
    // 跳章没丢：底栏还有「上一章 / 下一章」。legado 也把跳章放底栏进度行
    // （`ReadBookMenuBarBottom`），不放在点击区。
    var pageAnim: android.animation.ValueAnimator? = null

    /** 平滑滚一屏。步距按行算，到章末 / 章首才换章。 */
    fun scrollPage(forward: Boolean) {
        val limit = scrollLimit(body)
        val from = body.scrollY
        // 先判边界：滚不动了才换章，不在章末之前抢跑。
        if (forward && from >= limit) { nextChapter(info, index); return }
        if (!forward && from <= 0) { prevChapter(info, index, atEnd = true); return }
        val vp = (body.height - body.paddingTop - body.paddingBottom).coerceAtLeast(1)
        val layout = tv.layout
        val target = if (layout == null) {
            // 刚换章那一帧还没排版：退回整屏，至少这一下是动的。
            (if (forward) from + vp else from - vp).coerceIn(0, limit)
        } else {
            // 正文行顶 = 正文控件在滚动内容里的顶边 + 自身 paddingTop，与章内查找同一套算法。
            val textTop = childTopIn(body, tv) + tv.paddingTop
            val perPage = (vp / tv.lineHeight.coerceAtLeast(1)).coerceAtLeast(2)
            val first = layout.getLineForVertical((from - textTop).coerceAtLeast(0))
            val line = ReaderStyle.pageFirstLine(first, perPage, layout.lineCount, forward)
            (textTop + layout.getLineTop(line)).coerceIn(0, limit)
        }
        if (target == from) {
            // 只剩不足一行可见：当作到边界。
            if (forward) nextChapter(info, index) else prevChapter(info, index, atEnd = true)
            return
        }
        // 匀速、时长按距离比例（legado 用 Animatable + LinearEasing，见
        // `ReaderCanvasSurface.kt:689-692`）。不用 smoothScrollTo：减速曲线前快后慢，
        // 一屏一屏读时看着像文字在滑走。
        pageAnim?.cancel()
        pageAnim = android.animation.ValueAnimator.ofInt(from, target).apply {
            duration = ReaderStyle.pageDuration(target - from, vp)
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a -> body.scrollTo(0, a.animatedValue as Int) }
            start()
        }
    }

    /**
     * 自动翻页排程：每 [ReaderUi.autoPageSec] 秒滚一屏。
     *
     * 换章会整页重建（showReader 末尾重调一次），所以排程抽成局部函数；
     * 正文已摘下视图树（离开阅读页）时自动停，否则后台会一直往下翻章。
     */
    fun scheduleAuto() {
        ReaderAuto.cancel()
        ReaderAuto.sec = ReaderUi.autoPageSec.coerceIn(1, 60)
        if (!ReaderAuto.on) return
        val r = object : Runnable {
            override fun run() {
                if (!ReaderAuto.on || !body.isAttachedToWindow) { ReaderAuto.stop(); return }
                scrollPage(true)
                ReaderAuto.MAIN.postDelayed(this, ReaderAuto.sec * 1000L)
            }
        }
        ReaderAuto.handle = r
        ReaderAuto.MAIN.postDelayed(r, ReaderAuto.sec * 1000L)
    }

    val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val h = body.height
            if (h > 0) {
                when {
                    e.y < h / 3f -> scrollPage(false)
                    e.y > h * 2 / 3f -> scrollPage(true)
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
    // 顶栏副行的 TextView（widgets.topBar 在有 subtitle 时是「返回 / 两行栈 / 动作」三层）。
    // 拖全书进度滑条时把它临时改成「第 n/N 章 · 章名」，松手再恢复。
    val subTv = ((bar.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView)
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

    // 工具格：照 legado `ReadBookMenuBarBottom.kt:447-478` 的图标网格。
    // 默认四格（legado 默认集合去掉永远不显示的 read_aloud）：查找 / 自动翻页 / 目录 / 设置。
    // 一格 40 dp：189 dp 一行放 4 格 × 40 = 160 dp，刚好放得下。
    val grid = Md3.column(this)
    grid.setBackgroundColor(readerBackground)
    fun toolCell(label: String, icon: View, act: () -> Unit): LinearLayout {
        val cell = Md3.column(this)
        cell.gravity = Gravity.CENTER
        cell.isClickable = true
        cell.contentDescription = label
        cell.background = Md3.ripple(this, Color.TRANSPARENT, Md3.dp(this, 6).toFloat())
        cell.setOnClickListener { act() }
        // 40 dp 里放 16 dp 图标 + 一行 10 sp 标签，剩下的当上下热区。
        cell.addView(icon, Md3.lpDp(this, null, 16))
        cell.addView(Md3.text(this, label, 10f, readerMuted).apply { gravity = Gravity.CENTER })
        return cell
    }

    // 自动翻页：点一下开跑并收菜单（legado 点完就回到正文），再点停。
    // IconView 的名字是构造期固定的，所以 play / stop 两枚并存、切可见性。
    val autoPlay = Icons.view(this, "play", readerAccent, 16)
    val autoStop = Icons.view(this, "stop", readerAccent, 16)
    val autoBox = FrameLayout(this)
    autoBox.addView(autoPlay,
        FrameLayout.LayoutParams(Md3.dp(this, 16), Md3.dp(this, 16), Gravity.CENTER))
    autoBox.addView(autoStop,
        FrameLayout.LayoutParams(Md3.dp(this, 16), Md3.dp(this, 16), Gravity.CENTER))
    fun paintAutoIcon() {
        autoPlay.visibility = if (ReaderAuto.on) View.GONE else View.VISIBLE
        autoStop.visibility = if (ReaderAuto.on) View.VISIBLE else View.GONE
    }
    paintAutoIcon()

    /** 一格的动作。换源 / 缓存 / 书签 / 更多默认不在工具格上，但配了就能回来。 */
    fun runTool(id: String) {
        when (id) {
            "catalog" -> showToc(info, src)
            "setting" -> showReaderSettings(info, index, text, src)
            "change_source" -> { rememberReturn(); showSwap(info, src) }
            "cache" -> startDownload(info)
            "bookmark" -> if (BookmarkStore.has(info.bookId, index)) {
                BookmarkStore.remove(info.bookId, index); toast("已移除本章书签")
            } else addMark(info, index, text)
            "more" -> showReaderMenu(info, index, text, src)
            else -> showFind(info, index, text, src)
        }
    }

    // 超过四格换行（legado 一行 5 格 × 1–2 行，手表一行 4 格）；默认四格只有一行。
    val cells = ReaderUi.tools.map { id ->
        if (id == "auto_page") {
            toolCell(readerToolTitle(id), autoBox) {
                if (ReaderAuto.on) {
                    ReaderAuto.stop()
                } else {
                    ReaderAuto.on = true
                    scheduleAuto()
                    // 点完就收菜单：自动读的时候底栏盖住半屏，没必要留着。
                    setMenu(false)
                }
                paintAutoIcon()
            }
        } else {
            toolCell(readerToolTitle(id), Icons.view(this, readerToolGlyph(id), readerAccent, 16)) {
                runTool(id)
            }
        }
    }
    for (chunk in cells.chunked(4)) {
        val toolRow = Md3.row(this)
        for (cell in chunk) {
            toolRow.addView(cell, LinearLayout.LayoutParams(0, Md3.dp(this, 40), 1f))
        }
        grid.addView(toolRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 40)))
    }

    // 底部进度行：‹ 上一章 / 全书进度滑条 / 下一章 ›
    // （legado `ReadBookMenuBarBottom.kt:161-207`，进度行 36 dp）。
    // 滑条跨全书：max = 章数 × 100，每章占 100 格，章内百分比落在余数里。
    // 底栏必须背实底色 + 一根分隔线：否则正文从按钮下面露出来，看着像排版坏了。
    val foot = Md3.row(this)
    foot.gravity = Gravity.CENTER_VERTICAL
    foot.setPadding(Md3.dp(this, 8), 0, Md3.dp(this, 8), 0)
    foot.setBackgroundColor(readerBackground)

    /** 上一章 / 下一章：28 dp 宽，回退图标旋转 180° 就是前进，不再造第二枚路径。 */
    fun chapterArrow(desc: String, flip: Boolean, act: () -> Unit): View {
        val v = Icons.view(this, "back", readerAccent, 16)
        v.minimumWidth = Md3.dp(this, 28)
        v.minimumHeight = Md3.dp(this, 28)
        v.contentDescription = desc
        v.isClickable = true
        if (flip) v.rotation = 180f
        v.background = Md3.ripple(this, Color.TRANSPARENT, Md3.dp(this, 14).toFloat())
        v.setOnClickListener { act() }
        return v
    }
    val total = info.chapters.size.coerceAtLeast(1)

    val prev = chapterArrow("上一章", false) { prevChapter(info, index) }
    // 第一章时置灰并真的禁用：以前只调 alpha，点了还能按，弹一句「已是第一章」。
    if (index <= 0) { prev.alpha = 0.35f; prev.isClickable = false }
    foot.addView(prev, LinearLayout.LayoutParams(Md3.dp(this, 28), Md3.dp(this, 28)))

    /** 章内百分比：正文滚到哪算哪，0 是章首、100 是章末。 */
    fun innerPct(): Int {
        val limit = scrollLimit(body)
        if (limit <= 0) return 0
        return (body.scrollY * 100 / limit).coerceIn(0, 100)
    }
    val atStart = index * 100 + innerPct()
    val seek = android.widget.SeekBar(this)
    seek.max = 100 * total
    seek.progress = atStart.coerceIn(0, 100 * total)
    // SeekBar 自带 48 dp 最小高，不归零会把 36 dp 的进度行顶高。
    seek.minimumHeight = 0
    // 拖动中正文滚动不去改滑块位置，否则正文一动就把用户手上的滑块抢回去。
    var draggingSeek = false
    seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        // 页码不占行高：拖动时写进顶栏副行，松手恢复「章名 · 书源名」。
        override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
            if (!fromUser) return
            val at = (p / 100).coerceIn(0, total - 1)
            subTv?.text = "第 ${at + 1}/$total 章 · ${info.chapters[at].title}"
        }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { draggingSeek = true }
        // 松手才跳章：拖的过程中每动一格就取一次正文，等于把整本书拉一遍。
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
            draggingSeek = false
            subTv?.text = subLine
            var target = ((sb?.progress ?: atStart) / 100).coerceIn(0, total - 1)
            if (info.chapters.getOrNull(target)?.locked == true) {
                // 付费章取不到正文：前后各找一章免费的，找不到就退回原处。
                val free = (target + 1 until total).firstOrNull { !info.chapters[it].locked }
                    ?: (target - 1 downTo 0).firstOrNull { !info.chapters[it].locked }
                if (free == null) {
                    toast("该章为付费章")
                    seek.progress = index * 100 + innerPct()
                    return
                }
                target = free
            }
            if (target != index) preview(info, target) else seek.progress = index * 100 + innerPct()
        }
    })
    foot.addView(seek, LinearLayout.LayoutParams(0, Md3.dp(this, 32), 1f))

    val next = chapterArrow("下一章", true) { nextChapter(info, index) }
    if (index >= total - 1) { next.alpha = 0.35f; next.isClickable = false }
    foot.addView(next, LinearLayout.LayoutParams(Md3.dp(this, 28), Md3.dp(this, 28)))
    foot.layoutParams = Md3.lpDp(this, null, 36)

    // 亮度行：照 legado `ReadBookMenuBarBottom.kt:138-158` 的「跟随系统按钮 + 亮度滑条」。
    // 按钮亮 = 跟随系统开着；一拖滑条就退出跟随系统（legado 的 ToggleBrightnessAuto 同义）。
    val brightRow = Md3.row(this)
    brightRow.setPadding(Md3.dp(this, 12), 0, Md3.dp(this, 12), 0)
    brightRow.setBackgroundColor(readerBackground)
    val autoBtn = Icons.view(this, "brightness", readerMuted, 18)
    autoBtn.minimumWidth = Md3.dp(this, 32)
    autoBtn.minimumHeight = Md3.dp(this, 32)
    autoBtn.isClickable = true
    autoBtn.contentDescription = "跟随系统亮度"
    autoBtn.background = Md3.ripple(this, Color.TRANSPARENT, Md3.dp(this, 16).toFloat())
    fun paintAuto() { autoBtn.tint = if (ReaderUi.brightAuto) readerAccent else readerMuted }
    paintAuto()
    brightRow.addView(autoBtn, LinearLayout.LayoutParams(Md3.dp(this, 32), Md3.dp(this, 32)))
    // max 取 85：亮度档是 0.15–1.0，每格 1% 正好 85 档。
    val brightSeek = android.widget.SeekBar(this)
    brightSeek.max = 85
    brightSeek.progress = (((ReaderUi.bright - 0.15f) / 0.85f) * 85).toInt().coerceIn(0, 85)
    // SeekBar 自带 48 dp 最小高，不归零会把 32 dp 的亮度行顶高。
    brightSeek.minimumHeight = 0
    brightSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
            if (!fromUser) return
            // 一动手就不再跟随系统：手动值优先，否则改了也看不出变化。
            if (ReaderUi.brightAuto) { ReaderUi.brightAuto = false; paintAuto() }
            ReaderUi.bright = 0.15f + p / 85f * 0.85f
            applyBright()
        }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
        // 拖动过程中每帧写盘没有意义，松手存一次。
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { saveReaderExtras() }
    })
    autoBtn.setOnClickListener {
        ReaderUi.brightAuto = !ReaderUi.brightAuto
        paintAuto()
        applyBright()
        saveReaderExtras()
    }
    brightRow.addView(brightSeek, LinearLayout.LayoutParams(0, Md3.dp(this, 32), 1f))
    brightRow.layoutParams = Md3.lpDp(this, null, 32)

    // 底栏三行（自上而下）：分隔线 / 亮度行 32 / 分隔线 / 进度行 36 / 分隔线 / 工具格。
    // 行序照 legado 的 ReadBookMenuBarBottom：亮度在上、进度居中、工具格在下。
    // 高度账（189×248 dp）：1 + 32 + 1 + 36 + 1 + 工具格 = 111 dp（工具格 40 时），
    // 占窗口 45%，比 legado 的 64% 上限还留 47 dp。
    val bottom = Md3.column(this)
    bottom.setBackgroundColor(readerBackground)
    bottom.isClickable = true
    bottom.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    bottom.addView(brightRow)
    bottom.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    bottom.addView(foot)
    bottom.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    bottom.addView(grid)
    menu.addView(bottom, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

    setMenu = { on ->
        ReaderMenu.open = on
        // 自动翻页在菜单展开时暂停、收起后继续（legado 浮动胶囊的 `!menuVisible` 条件同义）：
        // 一边看菜单一边被滑走，改设置就改不成了。只取消待跑的回调，不动用户开关。
        if (on) ReaderAuto.cancel() else if (ReaderAuto.on) scheduleAuto()
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
        // 进度滑条跟着正文走（不触发跳章：回调里只认 fromUser）。
        if (!draggingSeek) {
            val limit = scrollLimit(body)
            val pct = if (limit > 0) (y * 100 / limit).coerceIn(0, 100) else 0
            seek.progress = index * 100 + pct
        }
        scrollSave?.let { body.removeCallbacks(it) }
        val task = Runnable { saveOffset(info.bookId, index, y) }
        scrollSave = task
        body.postDelayed(task, 400)
    }
    // 恢复上次读到的地方。等布局完成再滚，否则拿不到 maxScroll。
    val restore = readOffset(info.bookId).takeIf { it > 0 }
    if (atEnd) {
        // 从上屏退上来的：落在上一章末屏，接着往上读。
        // legado 的窗口一次带四页，退一屏跨章时看到的正是上一章的末页，不是章首。
        body.post { body.fullScroll(View.FOCUS_DOWN) }
    } else if (restore != null) {
        body.post { body.scrollTo(0, restore.coerceAtMost(scrollLimit(body))) }
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
    // 换章会走 preview → ReaderMenu.reset（取消回调）→ 重建这一屏，
    // 所以自动翻页的排程必须在最后重新挂上，否则读一章就停。
    scheduleAuto()
    // 更多操作 Sheet / 阅读设置里改开关时，手里没有这里的 `scheduleAuto`，
    // 把排程挂在状态对象上给它们调（离开阅读页时 [ReaderMenu.reset] 会清掉）。
    ReaderAuto.reschedule = { scheduleAuto() }

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
 * 阅读页「更多操作」整屏 Sheet（legado 的 `ReaderMoreActionsSheet`，手表子集）。
 *
 * 一句话列表看不出层级也点不准，改成整屏 Sheet：骨架构同 [Widgets.longMenuDialog]
 * （不透明 surface 底、48 dp 顶栏「更多操作」+ 关闭、列表可滚），
 * 行换成带图标的 [Widgets.listItem]，图标取自 legado `ReadMenuActionDefs` 的同一套图形。
 * 自动翻页那一行把当前开关写进标题，省下一行副标题 —— 表上没高度给它。
 */
private fun MainActivity.showReaderMenu(
    info: BookInfo, index: Int, text: String, src: BookSource?,
) {
    val dlg = android.app.Dialog(this)
    val root = Md3.column(this).apply { setBackgroundColor(Md3.surface) }

    val bar = Md3.row(this)
    bar.setPadding(Md3.dp(this, Md3.PAD), 0, 0, 0)
    bar.addView(
        Md3.ellipsize(Md3.text(this, "更多操作", Md3.FS_LG, Md3.onSurface, bold = true))
            .apply { gravity = Gravity.CENTER_VERTICAL },
        LinearLayout.LayoutParams(0, Md3.dp(this, Md3.TAB_H), 1f),
    )
    // 这块表没有实体返回键，关闭按钮就是唯一出口。
    bar.addView(Md3.iconButton(this, "close", { dlg.dismiss() }, desc = "关闭"),
        LinearLayout.LayoutParams(Md3.dp(this, Md3.TAP), Md3.dp(this, Md3.TAB_H)))
    root.addView(bar, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, Md3.TAB_H)))

    val list = Md3.column(this)
    /** 一行带图标的菜单项。行高取 [Md3.TAP]：248 dp 高的屏上 56 dp 一行只能露出三行半。 */
    fun row(title: String, glyph: String, act: () -> Unit) {
        list.addView(
            Widgets.listItem(
                this, title,
                leading = Icons.view(this, glyph, Md3.onSurfaceVariant, 18),
                onClick = { dlg.dismiss(); act() },
            ),
            Md3.lpDp(this, null, Md3.TAP),
        )
    }

    val marked = BookmarkStore.has(info.bookId, index)
    row("目录", "menu") { showToc(info, src) }
    row("章内查找", "search") { showFind(info, index, text, src) }
    // 自动翻页：标题带当前开着还是关着。开的时候顺手收菜单，直接回正文看它滚。
    row("自动翻页 · ${if (ReaderAuto.on) "开" else "关"}", "play") {
        val open = !ReaderAuto.on
        ReaderAuto.applyOn(open)
        if (open) {
            ReaderMenu.consumeBack()
        } else {
            toast("自动翻页已关")
        }
    }
    row(if (marked) "移除本章书签" else "添加书签", "bookmark") {
        if (BookmarkStore.has(info.bookId, index)) {
            BookmarkStore.remove(info.bookId, index)
            toast("已移除本章书签")
        } else addMark(info, index, text)
    }
    row("换源", "swap") { rememberReturn(); showSwap(info, src) }
    // legado 顶栏的刷新：重跑一次取正文，复用 preview 的本地缓存 + 在线兜底。
    row("刷新本章", "refresh") { preview(info, index) }
    row("离线缓存", "download") { startDownload(info) }
    row("阅读设置", "settings") { showReaderSettings(info, index, text, src) }
    // 工具格里到底放哪几格，就是 legado 的 bottomBarButtons 配置。
    row("工具格按钮", "grid") { showToolsDialog(info, index, text, src) }
    row("全部书签", "file") { showMarks(info) }
    row("阅读记录", "history") { showProgress(info) }
    row("导出 TXT", "upload") { exportTxt(info) }

    val sv = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(list)
    }
    root.addView(sv, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    dlg.setContentView(root)
    dlg.window?.apply {
        setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Md3.surface))
    }
    dlg.setCanceledOnTouchOutside(true)
    dlg.show()
}

/**
 * 工具格按钮配置（legado 的 `bottomBarButtons` / `ReadMenuButtonConfig`）。
 *
 * 勾选集直接写回 [ReaderUi.tools] 并落盘，然后重建阅读页 —— 底栏那几个格是
 * 建页面时按列表排的，就地改要拆视图树，反而比重建更容易打回章首。
 * 至少留一个：全空的底栏既看不出是什么，也没了回正文的入口。
 */
private fun MainActivity.showToolsDialog(
    info: BookInfo, index: Int, text: String, src: BookSource?,
) {
    val chosen = ReaderUi.tools.toMutableList()
    val box = Md3.column(this)
    fun render() {
        box.removeAllViews()
        for (id in READER_TOOLS) {
            val on = id in chosen
            box.addView(
                Widgets.listItem(
                    this, readerToolTitle(id),
                    leading = Icons.view(this, readerToolGlyph(id),
                        if (on) Md3.primary else Md3.onSurfaceVariant, 18),
                    trailing = if (on) "check" else null,
                    onClick = {
                        if (on) {
                            if (chosen.size <= 1) {
                                toast("至少留一个按钮")
                            } else {
                                chosen.remove(id)
                                render()
                            }
                        } else {
                            chosen.add(id)
                            render()
                        }
                    },
                ),
                Md3.lpDp(this, null, Md3.TAP),
            )
        }
    }
    render()
    val sv = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(box)
    }
    Widgets.md3Dialog(this, "工具格按钮",
        body = "勾选要排在底栏的按钮，至少留一个。",
        content = sv,
        confirm = "保存", dismiss = "取消",
        onConfirm = {
            // 顺序按 [READER_TOOLS] 的固定序排，不按点选先后：底栏位置不该随勾选顺序跳。
            ReaderUi.tools = READER_TOOLS.filter { it in chosen }
                .ifEmpty { listOf("search") }
            saveReaderExtras()
            showReader(info, index, text, src)
        },
    ).show()
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
 * 滚动内容里能滚多远。
 *
 * ScrollView 的滚动范围是「子控件高 + 上下内边距 − 视口高」（clipToPadding=false 时
 * 内边距也算在内容里），不是简单的「子控件高 − 视口高」。
 */
private fun scrollLimit(body: ScrollView): Int {
    val child = body.getChildAt(0) ?: return 0
    return (child.height - body.height + body.paddingTop + body.paddingBottom).coerceAtLeast(0)
}

/**
 * [v] 顶边在 [body] 滚动内容坐标系里的 y。
 *
 * ScrollView 把子控件排在 `paddingTop` 处，所以正文的行顶不能只拿 `tv.top`：
 * 少算一层内边距，定位会偏上一小截（页边距档越大偏得越多）。
 */
private fun childTopIn(body: ScrollView, v: View): Int {
    var y = 0
    var cur: View = v
    while (cur !== body) {
        y += cur.top
        cur = cur.parent as? View ?: return y
    }
    return y
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
    val maxY = scrollLimit(body)
    body.smoothScrollTo(0,
        ReaderStyle.scrollTarget(childTopIn(body, tv), tv.paddingTop, layout.getLineTop(line), maxY))
}

/** 上一章。跳过连续的锁定章（付费章取不到正文）。 */
private fun MainActivity.prevChapter(info: BookInfo, index: Int, atEnd: Boolean = false) {
    val prev = index - 1
    if (prev < 0) { toast("已是第一章"); return }
    if (info.chapters[prev].locked) {
        val free = (prev downTo 0).firstOrNull { !info.chapters[it].locked }
        if (free == null) { toast("前面都是付费章"); return }
        preview(info, free, atEnd)
    } else preview(info, prev, atEnd)
}
/** 从记录的位置续读：章号 + 章内节号。[atEnd] 为真时直接落在章末。 */
internal fun MainActivity.preview(info: BookInfo, index: Int, atEnd: Boolean = false) {
    val ch = info.chapters[index]
    // 先读本地正文，只有缺章时才在线程中初始化源并联网。
    val src = (curScreen as? Screen.Toc)?.src
        ?: (curScreen as? Screen.Reader)?.src
        ?: tocSrc
    // 正文要重取，屏上的菜单连着的旧视图树跟着作废。
    com.watchdl.ui.reader.ReaderMenu.reset()
    // 换章不清屏（legado 是旧页一直画着、新页就绪后当帧换窗口，
    // `feature/reader/ReaderCanvasSurface.kt:607-636` 的 pendingPageWindow）：
    // 已在阅读页时旧正文继续显着，正文取到后同帧交给 showReader 替换。
    // 先铺一页「正在取正文…」再整屏重建，就是每换一章闪两下的根源。
    if (curScreen !is Screen.Reader) {
        root.removeAllViews()
        root.addView(Widgets.topBar(this, ch.title, onBack = { showToc(info) }))
        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        (Widgets.contentColumn(body)).addView(Widgets.emptyState(this, "refresh", "正在取正文…"))
        root.addView(body)
    }

    // 读者停在 Toc / Reader 屏上点章节，回来时应该还在原来的屏。
    val owner: Screen = curScreen
    // 连点两下就会有两个线程在跑，先发的可能后回来盖掉新章。
    val token = ++ReaderUi.pageToken

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
            postAlive(owner) {
                if (token != ReaderUi.pageToken) return@postAlive
                showReader(info, index, text, actualSrc, atEnd)
            }
        } catch (e: Exception) {
            postAlive(owner) {
                if (token != ReaderUi.pageToken) return@postAlive
                toast("正文失败：${e.message}"); showToc(info, src)
            }
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
            { "${(it * 100).toInt()}%" }) {
            // 与底栏亮度滑条同源：手动改亮度就退出跟随系统。
            ReaderUi.bright = it
            ReaderUi.brightAuto = false
        }
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
        // 自动翻页间隔：legado 的 autoReadSpeed（手机 1–120），手表 1–60 够用。
        slider(col, "自动翻页", 1f, 60f, 59, ReaderUi.autoPageSec.toFloat(),
            { "${it.toInt()} s" }) { ReaderUi.autoPageSec = it.toInt().coerceIn(1, 60) }
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

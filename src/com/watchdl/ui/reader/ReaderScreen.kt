package com.watchdl.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
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
    /** 翻页动画（legado 的 pageAnim）：0 滚一屏 / 1 覆盖。 */
    var pageAnim = 0
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
    /** 顶栏 ⋮ 的书签徽标（legado 的「书签徽标」）：开时本章有书签就把 ⋮ 染成主色。 */
    var badgeOn = true

    /**
     * 阅读锚点（legado 的「返回原阅读位置」）：(书号, 章号, 滚动偏移)。
     *
     * 从目录 / 进度滑条 / 章内查找跳到别的章之前记下，新章底就会出「返回刚才那章」。
     * 不落盘：重启后旧位置已无意义，留着只会在下次开书时弹一个过期胶囊。
     */
    var anchor: Triple<String, Int, Int>? = null

    /** 主题提醒（legado 的「切换到深色 / 浅色模式」胶囊）：刚换了深浅，新页提示一句。 */
    var themeHint = false

    /** 换源前正在读的章标题；换源成功后由 showToc 消费掉，用来回到原章。进程内，不落盘。 */
    var swapReturnTitle = ""

    /** 换源前那一屏的滚动偏移（px）；回来时落在原屏，不是章首。进程内，不落盘。 */
    var swapReturnAt = 0

    /** 下一次重建时要钉住的滚动位置：键是「书号#章号」，-1 表示按记录位置恢复。 */
    var restoreKey = ""
    var restoreAt = -1

    /** 页眉页脚总开关（legado readConfig 的 headerMode/footerMode 合成一个总闸）。 */
    var stripsOn = true
    /** 页眉显示项：chapter=章节名 / page=屏号。空列表 = 不显示页眉。 */
    var headerParts: List<String> = listOf("chapter", "page")
    /** 页脚显示项：book=书名 / pct=全书进度 / battery=电量。空列表 = 不显示页脚。 */
    var footerParts: List<String> = listOf("book", "pct", "battery")
    /** 页眉页脚的重画闭包，由当前阅读页挂上；改设置时就地重画，不重建页面。 */
    var stripRefresh: (() -> Unit)? = null

    /**
     * 正在显示的正文与标题。
     *
     * 设置面板要“改一项、正文立刻变”，重建视图树会把滚动位置打回章首，
     * 所以只重排这两个控件。离开阅读页时[ReaderMenu.reset] 一并置空。
     */
    var bodyView: TextView? = null
    var titleView: TextView? = null

    /**
     * 拼接块里的下一章标题与正文（章末不足一屏时把下一章开头接在同一屏）。
     *
     * 样式与正文同一条路（[styleBody]），[nextRaw] 存**原始文本**：
     * 存已挂段距 span 的串，改字号时会连旧 span 一起带上，段距会对不上。
     */
    var nextTitleView: TextView? = null
    var nextView: TextView? = null
    var nextRaw = ""
    /**
     * 拼接块的世代号。
     *
     * 下一章正文是异步取的，回来时页面可能已经换章 / 已重建：
     * 每次 showReader 自增一次，回调先比这个，旧世代的结果直接丢掉。
     */
    var spliceGen = 0
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
    /**
     * 顶栏 ⋮ 徽标的重画。
     *
     * 书签增删现在只能从「更多操作」Sheet 进，而 Sheet 关掉后阅读页还在，
     * 徽标不会自己变色 —— 这里挂一个只改染色的闭包，比重建整页（会打回章首）便宜得多。
     */
    var badgeRefresh: (() -> Unit)? = null

    /** 收起并断开与页面的连接。换章、回目录、离屏时调。 */
    fun reset() {
        open = false
        onClose = null
        badgeRefresh = null
        // 页眉页脚的重画闭包握着上一屏的 View，留着就会往已摘下的视图树里写字。
        ReaderUi.stripRefresh = null
        // 正文/标题控件是上一屏的，留着就是泄漏旧视图树。
        ReaderUi.bodyView = null
        ReaderUi.titleView = null
        // 拼接块的两个控件同样是上一屏的，留着会往已摘下的视图树里写字。
        ReaderUi.nextView = null
        ReaderUi.nextTitleView = null
        ReaderUi.nextRaw = ""
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

/** 深色底（legado 默认背景）。主题胶囊与阅读设置里的「深」共用同一个值。 */
private val DARK_BG = Color.rgb(20, 18, 24)

/**
 * 记下「刚才读到哪」（legado 的阅读锚点）。
 *
 * 目录 / 进度滑条 / 章内查找都可能把人带到别的章，跳之前存一份
 * (书号, 章号, 滚动偏移)，新章底就会出「返回刚才那章」。只放进程内，不落盘。
 */
private fun MainActivity.markAnchor(info: BookInfo, index: Int) {
    ReaderUi.anchor = Triple(info.bookId, index, readerBody?.scrollY ?: 0)
}

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

/** 页眉能显示的项，也是落盘顺序。 */
private val STRIP_HEADER = listOf("chapter", "page")

/** 页脚能显示的项，也是落盘顺序。 */
private val STRIP_FOOTER = listOf("book", "pct", "battery")

private val STRIP_HEADER_DEF = listOf("chapter", "page")
private val STRIP_FOOTER_DEF = listOf("book", "pct", "battery")

/**
 * 显示项 csv → 合法 id 列表。
 *
 * 空串是「用户主动把这一条全关掉」，要原样保留成空列表，**不能**退回默认；
 * 只有键不存在（null）或一个合法 id 都没剩下（全是脏值）时才用默认。
 * 顺序按 [allowed] 的固定序排，不按存入先后：页眉页脚的排布不该随点选顺序跳。
 */
private fun readerParts(csv: String?, allowed: List<String>, def: List<String>): List<String> {
    if (csv == null) return def
    if (csv.isBlank()) return emptyList()
    val ids = csv.split(',').map { it.trim() }.filter { it in allowed }.distinct()
    return if (ids.isEmpty()) def else allowed.filter { it in ids }
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
    ReaderUi.pageAnim = p.getInt("anim", 0).coerceIn(0, 1)
    ReaderUi.titleMode = p.getInt("titleMode", 0).coerceIn(0, 2)
    ReaderUi.titleAdd = p.getInt("titleAdd", 2).coerceIn(0, 8)
    ReaderUi.titleBold = p.getBoolean("titleBold", true)
    ReaderUi.titleGap = p.getInt("titleGap", 1).coerceIn(0, 3)
    ReaderUi.marginLevel = p.getInt("margin", 1).coerceIn(0, 2)
    ReaderUi.keepOn = p.getBoolean("keepOn", false)
    ReaderUi.badgeOn = p.getBoolean("badge", true)
    ReaderUi.stripsOn = p.getBoolean("strips", true)
    ReaderUi.headerParts = readerParts(p.getString("headerParts", null),
        STRIP_HEADER, STRIP_HEADER_DEF)
    ReaderUi.footerParts = readerParts(p.getString("footerParts", null),
        STRIP_FOOTER, STRIP_FOOTER_DEF)
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
        .putInt("anim", ReaderUi.pageAnim)
        .putInt("titleMode", ReaderUi.titleMode)
        .putInt("titleAdd", ReaderUi.titleAdd)
        .putBoolean("titleBold", ReaderUi.titleBold)
        .putInt("titleGap", ReaderUi.titleGap)
        .putInt("margin", ReaderUi.marginLevel)
        .putBoolean("keepOn", ReaderUi.keepOn)
        .putBoolean("badge", ReaderUi.badgeOn)
        .putBoolean("strips", ReaderUi.stripsOn)
        .putString("headerParts", ReaderUi.headerParts.joinToString(","))
        .putString("footerParts", ReaderUi.footerParts.joinToString(","))
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
    // 番茄档带显式字色（legado readConfig 的 bgStr/textColor），优先；其余底色仍由深浅反推。
    val readerInk = ReaderStyle.inkOverride(readerBackground)
        ?: if (light) Color.rgb(28, 27, 31) else Md3.onSurface
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
    // 上下各让出 14 dp 页眉页脚，再加 2 dp 留白。高度账：
    // 屏 248 dp；页眉页脚各 14 dp → 正文可视 248−28−4=216 dp；
    // 菜单展开时顶栏 TOP_BAR 64 + 底栏 111 以浮层盖住，不参与挤压正文。
    body.setPadding(pad, Md3.dp(this, 14 + 2), pad, Md3.dp(this, 14 + 2))
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

    // ── 拼接块：章末不足一屏时把下一章开头接在正文之后 ───────────
    // legado 是窗口替换、邻页同屏绘制；这里一次性只装一章，章末剩三行时用户必须再戳一下。
    // 做法：正文 tv 之后接一条分隔线 + 下一章标题 + 下一章开头，滚到底才真跳章。
    var tvNext: TextView? = null
    /** 实际塞进 tvNext 的字符数（按缩进后的串算，与下一章正文的偏移同一套）。 */
    var spliceChars = 0
    /** 本页的拼接世代号：回调里先比它，页面重建了就把旧结果丢掉。 */
    val spliceGen = ++ReaderUi.spliceGen

    /**
     * 拼接块存在时，「本章读完」的位置是正文 tv 的底边，不是整页底边。
     *
     * 进度、滑条分母都用它：用整页 [scrollLimit] 算，全书进度会在章末虚高
     * （分母里含了下一章那几百字）。可滚上限仍用 [scrollLimit]。
     * 正文未排版时 tv.height 为 0，这里退回 0，调用方自己当「无进度」处理。
     */
    fun mainLimit(): Int = if (tvNext == null) scrollLimit(body)
        else (childTopIn(body, tv) + tv.height + body.paddingBottom - body.height)
            .coerceAtLeast(0)

    /**
     * 拼接块读完那一行在滚动内容里的 y；下一章从这一行接着显示，不重读。
     *
     * 两块版式相同（同一条 [styleBody]），偏移可以直接搬到新页。
     * tvNext 还没排版时退回 0：宁可让新页落回章首（最多重读一屏），
     * 也不按猜的行高乱定位。
     */
    fun spliceResume(): Int {
        val nx = tvNext ?: return 0
        val layout = nx.layout ?: return 0
        val at = spliceChars.coerceIn(0, layout.text.length)
        return childTopIn(body, nx) + nx.paddingTop +
            layout.getLineTop(layout.getLineForOffset(at))
    }

    frame.addView(body, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    // ── 页眉 / 页脚：各 14 dp 细条，压在菜单浮层之下 ─────────────
    // 不进 ScrollView：放进去会跟着正文滚走。z 序：body → 页眉页脚 → 护眼遮罩 → 胶囊 → 菜单。
    // 两头的小字都是 10 sp、readerMuted、includeFontPadding=false。
    fun stripText(t: CharSequence, gravity: Int): TextView =
        Md3.text(this, t, 10f, readerMuted).apply {
            includeFontPadding = false
            Md3.ellipsize(this)
            this.gravity = gravity or Gravity.CENTER_VERTICAL
        }

    /** 本章共多少屏、现在第几屏。正文未排版时按 1/1。 */
    fun pageOf(): Pair<Int, Int> {
        val layout = tv.layout ?: return 1 to 1
        val vh = (body.height - body.paddingTop - body.paddingBottom).coerceAtLeast(1)
        val linesPerPage = (vh / tv.lineHeight.coerceAtLeast(1)).coerceAtLeast(1)
        val totalPages = ((layout.lineCount + linesPerPage - 1) / linesPerPage).coerceAtLeast(1)
        val textTop = childTopIn(body, tv) + tv.paddingTop
        val line = layout.getLineForVertical((body.scrollY - textTop).coerceAtLeast(0))
        return (line / linesPerPage + 1).coerceIn(1, totalPages) to totalPages
    }

    /** 章内百分比：正文滚到哪算哪，0 是章首、100 是章末（有拼接块时按 [mainLimit] 算）。 */
    fun innerPct(): Int {
        val limit = mainLimit()
        if (limit <= 0) return 0
        return (body.scrollY * 100 / limit).coerceIn(0, 100)
    }
    val total = info.chapters.size.coerceAtLeast(1)

    // 两条 14 dp 的条各建一次，内容由 paintStripsChrome 清空重建 ——
    // 页眉页脚的开关与显示项都在设置里改，改完就地重画，不重建整页（重建会打回章首）。
    val header = Md3.column(this)
    header.setBackgroundColor(readerBackground)
    val headerRow = Md3.row(this)
    header.addView(headerRow,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    // 番茄档 headerMode=1 就是「页眉有线」，所以这根线常显，不做开关。
    header.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    frame.addView(header, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 14), Gravity.TOP))

    // 页脚同样：左书名，中全书进度（与底栏滑条同一口径），右电量。
    val footer = Md3.column(this)
    footer.setBackgroundColor(readerBackground)
    footer.addView(View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    val footerRow = Md3.row(this)
    footer.addView(footerRow,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    frame.addView(footer, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 14), Gravity.BOTTOM))

    // 电量表常驻一个实例：重画条内容只是换个父容器，不重复注册广播。
    val battery = BatteryText(this).apply {
        setTextColor(readerMuted)
        setPadding(Md3.dp(this@showReader, 4), 0, 0, 0)
    }
    val chapterTitle = info.chapters.getOrNull(index)?.title.orEmpty()

    /** 屏号 / 全书进度两个控件；显示项里没勾的那一项就是 null。 */
    var pageTv: TextView? = null
    var pctTv: TextView? = null

    /** 页眉右字与页脚进度。两者只在滚动回调与重画时刷新。 */
    fun paintStrips() {
        val p = pageOf()
        pageTv?.text = "${p.first}/${p.second}"
        // 与底栏滑条同一口径：全书 = 章数 × 100，章内百分比落在余数里，取整到 0–100。
        pctTv?.text = "${((index * 100 + innerPct()) / total).coerceIn(0, 100)}%"
    }

    /**
     * 按当前配置重画页眉页脚。
     *
     * 清空两条的子控件再按 [ReaderUi.headerParts] / [ReaderUi.footerParts] 重建，
     * 所以能重复调（设置里改一下就调一次）而不碰视图树的其余部分。
     * 总开关关或某组显示项为空时，那一条 GONE，正文的上下内边距跟着退回 [pad] ——
     * 条不在就不必让位（关掉后正文多两行）。
     * 改内边距会改变可滚范围，滚动位置可能被 ScrollView 夹一下，这是允许的；
     * 但这里绝不主动 scrollTo(0, 0)。
     */
    fun paintStripsChrome() {
        val on = ReaderUi.stripsOn
        val hp = if (on) ReaderUi.headerParts else emptyList()
        val fp = if (on) ReaderUi.footerParts else emptyList()
        pageTv = null
        pctTv = null

        headerRow.removeAllViews()
        headerRow.setPadding(pad, 0, pad, 0)
        // 章节名是伸缩区；没勾它时补一个占位，屏号才不会被挤到左边。
        if ("chapter" !in hp) headerRow.addView(View(this),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        for (id in hp) when (id) {
            "chapter" -> headerRow.addView(stripText(chapterTitle, Gravity.START),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            "page" -> {
                val v = stripText("1/1", Gravity.END)
                pageTv = v
                headerRow.addView(v)
            }
        }
        header.visibility = if (hp.isEmpty()) View.GONE else View.VISIBLE

        footerRow.removeAllViews()
        footerRow.setPadding(pad, 0, pad, 0)
        if ("book" !in fp) footerRow.addView(View(this),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        for (id in fp) when (id) {
            "book" -> footerRow.addView(stripText(info.name, Gravity.START),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            // 进度条只做「中偏右」：14 dp 里做精确居中要给两侧配重，书名可用宽度会被砍一半。
            "pct" -> {
                val v = stripText("", Gravity.CENTER)
                pctTv = v
                footerRow.addView(v)
            }
            "battery" -> {
                (battery.parent as? ViewGroup)?.removeView(battery)
                footerRow.addView(battery)
            }
        }
        footer.visibility = if (fp.isEmpty()) View.GONE else View.VISIBLE

        val top = if (header.visibility == View.VISIBLE) Md3.dp(this, 14 + 2) else pad
        val bottom = if (footer.visibility == View.VISIBLE) Md3.dp(this, 14 + 2) else pad
        if (body.paddingTop != top || body.paddingBottom != bottom) {
            body.setPadding(pad, top, pad, bottom)
        }
        // 新控件刚建出来是空的：屏号与进度得再上一遍。
        paintStrips()
    }
    paintStripsChrome()
    // 设置面板改完显示项就地重画，不重建页面（重建会把滚动位置打回章首）。
    ReaderUi.stripRefresh = { paintStripsChrome() }

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
    /**
     * 覆盖动画在跑。
     *
     * 一屏 220 ms 里连点两下会叠出两层定格位图（且第一层的 withEndAction 会把第二层的层摘掉）。
     * 动画期间不再叠层，直接到位；动画结束复位。
     */
    var coverBusy = false

    /** 往下滚到章末：有拼接块就把「读完处」当偏移带过去，没有就整章换。 */
    fun toNext() {
        val resume = if (tvNext == null) 0 else spliceResume()
        if (resume > 0) {
            // 两块版式相同，偏移直接搬：新页从拼接块停下的那一行接上，不重读。
            ReaderUi.restoreKey = "${info.bookId}#${index + 1}"
            ReaderUi.restoreAt = resume
            preview(info, index + 1)
        } else nextChapter(info, index)
    }

    /** 平滑滚一屏。步距按行算，到章末 / 章首才换章。 */
    fun scrollPage(forward: Boolean) {
        val limit = scrollLimit(body)
        val from = body.scrollY
        // 先判边界：滚不动了才换章，不在章末之前抢跑。
        if (forward && from >= limit) { toNext(); return }
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
            if (forward) toNext() else prevChapter(info, index, atEnd = true)
            return
        }
        // 覆盖：先把当前屏定格成位图，正文瞬间滚到目标，再让定格的旧屏整体滑出，
        // 露出下面的新屏 —— 就是 legado 的「新一屏从下往上盖住旧的」。
        // 只影响章内翻屏；跳章（上面两个 return）不经过这里。
        if (ReaderUi.pageAnim == 1 && body.width > 0 && body.height > 0) {
            pageAnim?.cancel()
            if (coverBusy) {
                // 上一次覆盖还没走完：直接到位，不叠层。
                body.scrollTo(0, target)
                return
            }
            val vh = body.height
            val snap = Bitmap.createBitmap(body.width, vh, Bitmap.Config.ARGB_8888)
            body.draw(Canvas(snap))
            body.scrollTo(0, target)
            val iv = ImageView(this).apply {
                setImageBitmap(snap)
                scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false
            }
            // 压进页眉之下：页眉页脚不能被这层盖住。header 是帧内第一层浮条，
            // 插在它前面就位于 body 之上、页眉与页脚之下。
            frame.addView(iv, frame.indexOfChild(header),
                FrameLayout.LayoutParams(body.width, vh, Gravity.TOP))
            coverBusy = true
            iv.animate().translationY((if (forward) -vh else vh).toFloat())
                .setDuration(COVER_MS)
                .setInterpolator(android.view.animation.LinearInterpolator())
                .withEndAction {
                    // 连点或换页可能已经把它摘了：判空再摘，不重复回收位图。
                    (iv.parent as? ViewGroup)?.removeView(iv)
                    snap.recycle()
                    coverBusy = false
                }.start()
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

    // 顶栏：返回 + 书名 / 「章名 · 书源名」+ 刷新 / 换源 / ⋮ 三枚动作。
    // BookSource 接口不带名字（规则源才有 displayName），没名字就只显章名。
    val srcName = (src as? LegadoSource)?.displayName
    val subLine = listOfNotNull(
        info.chapters.getOrNull(index)?.title?.takeIf { it.isNotBlank() },
        srcName?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    // 顶栏三枚动作（刷新 / 换源 / ⋮）：legado 默认顶栏是「下载 / 刷新 / 换源 / 更多」四枚，
    // 手表 189 dp 宽只够三枚，下载归入「更多操作」Sheet。
    // 宽度预算：顶栏可用 189−8=181 dp（Widgets.topBar 自带 PAD/2 两侧内边距）
    // = 返回 32 + 书名约 45 + 刷新 28 + 换源 28 + ⋮ 48；书名只剩两字多，
    // 所以 14 sp 单行 ellipsize。
    val bar = Widgets.topBar(this, info.name, onBack = {
        // 去目录挑章之前先记下当前位置，回来时才有「返回刚才那章」。
        markAnchor(info, index)
        showToc(info)
    }, subtitle = subLine)
    bar.setBackgroundColor(readerBackground)
    if (light) Widgets.tintSubtree(bar, readerInk)
    // 顶栏副行的 TextView（widgets.topBar 在有 subtitle 时是「返回 / 两行栈 / 动作」三层）。
    // 拖全书进度滑条时把它临时改成「第 n/N 章 · 章名」，松手再恢复。
    val subTv = ((bar.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView)
    // 返回按钮由 Widgets.topBar 建成 Md3.TAP(48) 宽，收到 32 dp 才腾得出两枚动作。
    (bar.getChildAt(0) as? View)?.let { b ->
        (b.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = Md3.dp(this, 32)
            b.layoutParams = lp
        }
        b.minimumWidth = Md3.dp(this, 32)
    }
    // 书名让位：topBar 建出的是 FS_LG（22 sp），一行装不下「书名 + 三枚动作」。
    // 收到 14 sp 单行，ellipsize 保留。有 subtitle 时 0 是书名、1 是副行 subTv。
    val barTitle = (bar.getChildAt(1) as? LinearLayout)?.getChildAt(0) as? TextView
        ?: (bar.getChildAt(1) as? TextView)
    barTitle?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
    barTitle?.setSingleLine(true)
    // legado 顶栏没有书签按钮，只有 bookmarkBadge 这个「本章有书签」的染色信号；
    // 这里把它落在 ⋮ 上：本章有书签时 ⋮ 染主色。
    // 「阅读设置 → 更多 → 徽标」关掉后不再染色，但仍可点（徽标是提示，不是开关）。
    // 添加 / 移除书签走「更多操作」Sheet，不再就地改图标。
    val marked = BookmarkStore.has(info.bookId, index)
    val badged = marked && ReaderUi.badgeOn
    // 刷新与换源：与 ⋮ 同构（18 dp 图标挤在 28 dp 槽里，minimumWidth 覆盖 Md3.TAP=48）。
    val refreshBtn = Icons.view(this, "refresh", readerMuted, 18)
    refreshBtn.minimumWidth = Md3.dp(this, 28)
    refreshBtn.minimumHeight = Md3.dp(this, Md3.TOP_BAR)
    refreshBtn.setPadding(Md3.dp(this, 5), 0, Md3.dp(this, 5), 0)
    refreshBtn.isClickable = true
    refreshBtn.background = Md3.ripple(this, Color.TRANSPARENT, Md3.dp(this, 14).toFloat())
    refreshBtn.contentDescription = "刷新本章"
    refreshBtn.setOnClickListener { preview(info, index) }
    bar.addView(refreshBtn, LinearLayout.LayoutParams(
        Md3.dp(this, 28), Md3.dp(this, Md3.TOP_BAR)))
    val swapBtn = Icons.view(this, "swap", readerMuted, 18)
    swapBtn.minimumWidth = Md3.dp(this, 28)
    swapBtn.minimumHeight = Md3.dp(this, Md3.TOP_BAR)
    swapBtn.setPadding(Md3.dp(this, 5), 0, Md3.dp(this, 5), 0)
    swapBtn.isClickable = true
    swapBtn.background = Md3.ripple(this, Color.TRANSPARENT, Md3.dp(this, 14).toFloat())
    swapBtn.contentDescription = "换源"
    swapBtn.setOnClickListener {
        ReaderUi.swapReturnTitle = info.chapters.getOrNull(index)?.title.orEmpty()
        // 连章内滚动位置一起记：换源回来时落在原屏，不是章首。
        ReaderUi.swapReturnAt = readerBody?.scrollY ?: 0
        rememberReturn(); showSwap(info, src)
    }
    bar.addView(swapBtn, LinearLayout.LayoutParams(
        Md3.dp(this, 28), Md3.dp(this, Md3.TOP_BAR)))
    val more = Icons.view(this, "more",
        if (badged) readerAccent else readerMuted, Md3.ICON_ACTION)
    // ⋮ 用回标准 48 dp 槽：它是顶栏唯一一个“菜单”入口，28 dp 太窄，表盘上很难点到。
    more.minimumWidth = Md3.dp(this, Md3.TAP)
    more.minimumHeight = Md3.dp(this, Md3.TOP_BAR)
    more.setPadding(Md3.dp(this, 6), 0, Md3.dp(this, 6), 0)
    more.isClickable = true
    more.background = Md3.ripple(this, Color.TRANSPARENT, Md3.dp(this, 16).toFloat())
    // 染色是视觉信号，听读器读不到颜色，所以描述里同步说一声。
    more.contentDescription = if (badged) "更多操作，本章有书签" else "更多操作"
    more.setOnClickListener { showReaderMenu(info, index, text, src) }
    bar.addView(more, LinearLayout.LayoutParams(
        Md3.dp(this, Md3.TAP), Md3.dp(this, Md3.TOP_BAR)))
    // Sheet 里加/删了书签后只改染色，不重建页。
    ReaderMenu.badgeRefresh = {
        val on = BookmarkStore.has(info.bookId, index) && ReaderUi.badgeOn
        more.tint = if (on) readerAccent else readerMuted
        more.contentDescription = if (on) "更多操作，本章有书签" else "更多操作"
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
            "catalog" -> { markAnchor(info, index); showToc(info, src) }
            "setting" -> showReaderSettings(info, index, text, src)
            "change_source" -> {
                ReaderUi.swapReturnTitle = info.chapters.getOrNull(index)?.title.orEmpty()
                ReaderUi.swapReturnAt = readerBody?.scrollY ?: 0
                rememberReturn(); showSwap(info, src)
            }
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

    val prev = chapterArrow("上一章", false) { prevChapter(info, index) }
    // 第一章时置灰并真的禁用：以前只调 alpha，点了还能按，弹一句「已是第一章」。
    if (index <= 0) { prev.alpha = 0.35f; prev.isClickable = false }
    foot.addView(prev, LinearLayout.LayoutParams(Md3.dp(this, 28), Md3.dp(this, 28)))

    // 页眉屏号、页脚进度与章内百分比都在上方页眉页脚段（paintStrips / innerPct），
    // 它们要先于这一段声明：重画条内容的 paintStripsChrome 也要用到。
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
            if (target != index) {
                // 拖滑条跨章之前记下当前位置（legado 的阅读锚点）。
                markAnchor(info, index)
                preview(info, target)
            } else seek.progress = index * 100 + innerPct()
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

    // ── 浮动胶囊（legado 的 ReadBookFloatingActionBar）─────────────
    // 底部居中的一条圆角胶囊，到点自己消失；菜单一展开就立刻撤掉（legado 的 !menuVisible 条件）。
    // 必须先声明后给 setMenu 引用，所以摆在 setMenu 赋值之前。
    val pillHost = FrameLayout(this)
    val pillLive = mutableListOf<View>()
    val pillTimer = Handler(Looper.getMainLooper())
    fun clearPills() {
        pillHost.removeAllViews()
        pillLive.clear()
    }
    /**
     * 出一条浮动胶囊。
     *
     * [seconds] 秒后自己消失；点一下先收胶囊再跑 [act]（legado 两个胶囊都是点击即走）。
     * 只留一条：锚点与主题提醒同时到时不叠两层，后到的把前一条顶掉。
     */
    fun pill(glyph: String, label: String, seconds: Int = 5, act: (() -> Unit)? = null) {
        clearPills()
        val cap = Md3.row(this)
        cap.gravity = Gravity.CENTER_VERTICAL
        cap.setPadding(Md3.dp(this, 10), 0, Md3.dp(this, 12), 0)
        cap.minimumHeight = Md3.dp(this, 30)
        cap.background = Md3.ripple(this, Md3.surface3, 20f)
        cap.contentDescription = label
        cap.isClickable = true
        cap.addView(Icons.view(this, glyph, readerAccent, 14),
            LinearLayout.LayoutParams(Md3.dp(this, 14), Md3.dp(this, 14)).apply {
                rightMargin = Md3.dp(this@showReader, 6)
            })
        cap.addView(Md3.text(this, label, Md3.FS_SM, Md3.onSurface))
        cap.setOnClickListener { clearPills(); act?.invoke() }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = Md3.dp(this, 12)
        pillHost.addView(cap, lp)
        pillLive.add(cap)
        // 到点自己消失。已经被点掉或被菜单撤掉时 parent 为空，这里什么都不做。
        pillTimer.postDelayed({
            if (cap.parent === pillHost) {
                pillHost.removeView(cap)
                pillLive.remove(cap)
            }
        }, seconds * 1000L)
    }

    setMenu = { on ->
        ReaderMenu.open = on
        // 自动翻页在菜单展开时暂停、收起后继续（legado 浮动胶囊的 `!menuVisible` 条件同义）：
        // 一边看菜单一边被滑走，改设置就改不成了。只取消待跑的回调，不动用户开关。
        // 胶囊同理：菜单一开就撤掉，否则压在工具格上。
        if (on) { clearPills(); ReaderAuto.cancel() } else if (ReaderAuto.on) scheduleAuto()
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
            val limit = mainLimit()
            val pct = if (limit > 0) (y * 100 / limit).coerceIn(0, 100) else 0
            seek.progress = index * 100 + pct
        }
        // 页眉的屏号与页脚的进度跟着正文走。
        paintStrips()
        scrollSave?.let { body.removeCallbacks(it) }
        val task = Runnable {
            // 偏移只记到「本章读完」为止：存进拼接块，下次进这一章会落在下一章的字里。
            saveOffset(info.bookId, index, y.coerceIn(0, mainLimit()))
        }
        scrollSave = task
        body.postDelayed(task, 400)
    }
    // 恢复上次读到的地方。等布局完成再滚，否则拿不到 maxScroll。
    // 「返回刚才那章」胶囊回跳时位置是钉死的：落回当时那一屏，不是那一章上次读到的位置。
    val pinned = if (ReaderUi.restoreKey == findKey) ReaderUi.restoreAt else -1
    if (pinned >= 0) {
        ReaderUi.restoreKey = ""
        ReaderUi.restoreAt = -1
    }
    val restore = readOffset(info.bookId).takeIf { it > 0 }
    if (atEnd) {
        // 从上屏退上来的：落在上一章末屏，接着往上读。
        // legado 的窗口一次带四页，退一屏跨章时看到的正是上一章的末页，不是章首。
        body.post {
            // 有拼接块时章末是正文底边：整页底会落进下一章的开头（读的是别人的字）。
            if (tvNext != null) body.scrollTo(0, mainLimit()) else body.fullScroll(View.FOCUS_DOWN)
        }
    } else if (pinned >= 0) {
        body.post { body.scrollTo(0, pinned.coerceIn(0, mainLimit())) }
    } else if (restore != null) {
        body.post { body.scrollTo(0, restore.coerceIn(0, mainLimit())) }
    }

    // ── 下一章开头：异步取，接到正文之后 ──────────────────────
    // 同步取会卡住这一帧（联网最多几秒），所以走与预览同一套后台任务。
    // 取不到或拿到空正文就什么都不加，行为与以前一样（章末需再戳一下）。
    val nextCh = info.chapters.getOrNull(index + 1)
    if (nextCh != null && !nextCh.locked) {
        bg(curScreen, body = { loadOpening(info, nextCh, src) }) { r ->
            val raw = r.getOrNull().orEmpty().trim()
            // 页面已换代（换章 / 改设置重建）：旧章的开头不能往新页上接。
            if (raw.isEmpty() || spliceGen != ReaderUi.spliceGen) return@bg
            // 正文已从视图树摘下（已离开阅读页）也不再接。
            if (body.parent == null) return@bg
            col.addView(View(this).apply { setBackgroundColor(hairline) },
                Md3.lpDp(this, null, 1))
            val nxTitle = Md3.text(this, nextCh.title, readerSize + ReaderUi.titleAdd,
                readerInk, bold = ReaderUi.titleBold)
            col.addView(nxTitle)
            val nx = Md3.text(this, "", readerSize, readerInk)
            nx.setPadding(Md3.dp(this, 3), Md3.dp(this, 2), Md3.dp(this, 3), Md3.dp(this, 24))
            col.addView(nx)
            ReaderUi.nextTitleView = nxTitle
            ReaderUi.nextView = nx
            ReaderUi.nextRaw = raw
            tvNext = nx
            // 样式与正文完全同一条路：改字号 / 行距时两块一起变。
            styleBody(nx, raw)
            spliceChars = nx.length()
            // 新标题也得跟上「标题位置 / 字号」设置：它就是一个章标题。
            restyleTitle(readerInk)
            paintStrips()
            // 从下一章退上来、这一页本应落在「章末」：有拼接块时章末是正文底边。
            if (atEnd) body.post { body.scrollTo(0, mainLimit()) }
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
    // 菜单最后加：z 序在正文与护眼层之上；胶囊层在菜单之下。
    frame.addView(pillHost, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    frame.addView(menu, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.addView(frame, Md3.lpWeight(1f))

    // ① 阅读锚点：从目录 / 进度滑条 / 章内查找跳到了别的章，底出一条「返回刚才那章」。
    val anchor = ReaderUi.anchor
    if (anchor != null && anchor.first == info.bookId && anchor.second != index) {
        pill("back", "返回刚才那章") {
            // 先钉住位置再跳：这一章的滚动位置会被新页覆盖，不先记下就回不到原屏。
            ReaderUi.restoreKey = "${info.bookId}#${anchor.second}"
            ReaderUi.restoreAt = anchor.third
            preview(info, anchor.second)
        }
    }
    // ② 主题提醒：刚在阅读设置里换了深浅，新页底出「切换到深色 / 浅色模式」。
    if (ReaderUi.themeHint) {
        ReaderUi.themeHint = false
        pill("brightness", if (light) "切换到深色模式" else "切换到浅色模式") {
            // 只换底色，字号行距不动；重建页是因为整套前景色都得跟着换。
            saveReaderPrefs(readerSize, readerSpacing, if (light) DARK_BG else Color.WHITE)
            showReader(info, index, text, src)
        }
    }

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
    val ink = ReaderStyle.inkOverride(readerBackground)
        ?: if (Color.red(readerBackground) > 100) Color.rgb(28, 27, 31) else Md3.onSurface
    styleBody(tv, raw)
    // 拼接块里的下一章正文用同一条样式：不同步就会在翻过去时字号突换一档。
    ReaderUi.nextView?.let { styleBody(it, ReaderUi.nextRaw) }
    restyleTitle(ink)
}

/**
 * 正文样式：字号 / 行距 / 字色 / 对齐 / 字重斜体 / 字距 / 段距。
 *
 * 正文 tv 与拼接块里的下一章正文都走这一条 —— 两块版式相同，改设置时才能一起变。
 * [raw] 必须是**原始文本**（未缩进、未挂 span）。
 */
private fun MainActivity.styleBody(tv: TextView, raw: String) {
    val light = Color.red(readerBackground) > 100
    val ink = ReaderStyle.inkOverride(readerBackground)
        ?: if (light) Color.rgb(28, 27, 31) else Md3.onSurface
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
}

/** 标题样式。位置 / 字号 / 字重 / 下间距四项都跟着设置变。 */
private fun MainActivity.restyleTitle(ink: Int) {
    val t = ReaderUi.titleView ?: return
    val hide = ReaderUi.titleMode == 2
    // 拼接块的下一章标题也走这一套：它就是一个章标题，样式不能比正文标题差。
    for (v in listOfNotNull(t, ReaderUi.nextTitleView)) {
        v.visibility = if (hide) View.GONE else View.VISIBLE
        if (hide) continue
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, readerSize + ReaderUi.titleAdd)
        v.setTextColor(ink)
        v.setTypeface(
            android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, if (ReaderUi.titleBold) 700 else 400))
        v.gravity = if (ReaderUi.titleMode == 1) Gravity.CENTER else Gravity.START
        v.setPadding(Md3.dp(this, 3), Md3.dp(this, 2), Md3.dp(this, 3),
            Md3.dp(this, ReaderStyle.titleGapDp(ReaderUi.titleGap)))
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

/**
 * 页脚电量：读系统电池广播。
 *
 * 先不注册就读一次粘性广播：读到了才挂接收器；读不到就显示「—」且不注册 ——
 * 为一个永远不更新的数字挂着广播接收器没有意义。
 */
private class BatteryText(ctx: android.content.Context) : TextView(ctx) {
    private val filter =
        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
    private var registered = false

    /** 广播里的 level / scale 折成 0–100；读不到给 null。 */
    private fun pct(i: android.content.Intent?): Int? {
        if (i == null) return null
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale).coerceIn(0, 100)
    }

    private val rx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            pct(i)?.let { text = "$it%" }
        }
    }

    /** 粘性广播的初值；null 表示这台设备读不到电量。 */
    private val sticky: Int? =
        runCatching { pct(context.registerReceiver(null, filter)) }.getOrNull()

    init {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
        includeFontPadding = false
        setSingleLine(true)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        text = sticky?.let { "$it%" } ?: "—"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (sticky == null || registered) return
        // 注册失败也不影响显示：初值已经画上了。
        registered = runCatching { context.registerReceiver(rx, filter) }.isSuccess
    }

    override fun onDetachedFromWindow() {
        if (registered) {
            runCatching { context.unregisterReceiver(rx) }
            registered = false
        }
        super.onDetachedFromWindow()
    }
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

    // 行序照 legado 的 ReadMenuActionDefs：换源与刷新在前（读不下去时最常按的两个），
    // 目录与章内查找其次，工具与书签类收在后面。
    val marked = BookmarkStore.has(info.bookId, index)
    row("换源", "swap") {
        ReaderUi.swapReturnTitle = info.chapters.getOrNull(index)?.title.orEmpty()
        ReaderUi.swapReturnAt = readerBody?.scrollY ?: 0
        rememberReturn(); showSwap(info, src)
    }
    // legado 顶栏的刷新：重跑一次取正文，复用 preview 的本地缓存 + 在线兜底。
    row("刷新本章", "refresh") { preview(info, index) }
    row("离线缓存", "download") { startDownload(info) }
    row("目录", "menu") { markAnchor(info, index); showToc(info, src) }
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
        // 顶栏 ⋮ 的徽标跟着变色（不重建页面，否则滚动位置回章首）。
        ReaderMenu.badgeRefresh?.invoke()
    }
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
    // 查找会把人滚到命中处，跳之前先记下位置（legado 把查找也算进阅读锚点的入口）。
    markAnchor(info, index)
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

/** 覆盖动画时长：比滚动略快（一屏滚到底约 260 ms），覆盖感才利落。 */
private const val COVER_MS = 220L

/** 拼接块的长度：约两屏多一点，足够滚到底再跳章时不重读。 */
private const val SPLICE_CHARS = 800

/** 拼接块用的那一段：切在段落边界上，下一章正文不从半句接起。 */
private fun spliceHead(text: String): String {
    if (text.length <= SPLICE_CHARS) return text
    val cut = text.lastIndexOf('\n', SPLICE_CHARS)
    return if (cut > SPLICE_CHARS / 2) text.substring(0, cut) else text.substring(0, SPLICE_CHARS)
}

/**
 * 取一章正文（拼接块用），与 [preview] 同一条路。
 *
 * 先本地缓存（jobsStore 里该章 itemId 的 content）净化转纯文本，
 * 本地没有才联网取；任何异常一律吞掉返回空串 —— 拼接块是锦上添花，
 * 不能因为多取一章把正文页弄挂。
 * 注意这是阻塞调用，必须在 [bg] 的线程里跑。
 */
private fun MainActivity.loadOpening(
    info: BookInfo,
    ch: ChapterRef,
    src: BookSource?,
): String = try {
    val line = jobsStore().readChapters(info.bookId)[ch.itemId]
    val localHtml = line?.let { TinyJson.path(it, "content") }
    val html = localHtml ?: (src ?: source).content(ch.itemId, info.bookId)
    val raw = Downloader.htmlToText(html).ifEmpty { html.replace(Regex("<[^>]+>"), "\n").trim() }
    spliceHead(ReplaceRuleStore.apply(raw, isTitle = false))
} catch (e: Exception) {
    ""
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
    // 已经顺着读下去了，「刚才那章」不再有意义，锚点作废。
    ReaderUi.anchor = null
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
    // 已经顺着读下去了，「刚才那章」不再有意义，锚点作废。
    ReaderUi.anchor = null
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
        // 翻页动画：legado pageAnim 的两档（滚一屏 / 覆盖；仿真在手表上不必）。
        // 只影响章内翻屏，跳章仍是瞬间换页。
        seg(col, "翻页", listOf("滚一屏" to 0 as Any, "覆盖" to 1),
            { ReaderUi.pageAnim as Any }) { ReaderUi.pageAnim = it as Int }
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
        // 5 个 chip 等宽挤在 189 dp 里（每个约 23 dp），两字的「番茄」会被截断，
        // 所以与前四档一样取单字「茄」。底色与字色取自 legado readConfig 样本。
        // 番茄档日/夜两套（legado readConfig 的 bgStr / bgStrNight）：点「茄」时按**当前**底色深浅
        // 自动取，深底给夜茄、浅底给日茄 —— 多一格就多一次选择，用户已经说了只要一个「茄」。
        val darkNow = Color.red(readerBackground) <= 100
        val bgs = listOf(
            "深" to DARK_BG,
            "浅" to Color.WHITE,
            "暖" to Color.rgb(255, 248, 230),
            "米" to Color.rgb(232, 225, 210),
            "茄" to if (darkNow) ReaderStyle.BG_FANQIE_NIGHT else ReaderStyle.BG_FANQIE_DAY,
        )
        // 已知限：芯片选中态靠 `pair.second == cur` 判，而「茄」的取值是建这一页时算定的。
        // 在「茄」上点一下会把底色切到另一套（日↔夜），于是「茄」自己不再高亮，
        // 用户要切回去得再点一次「茄」，而不是点那一格高亮的。只多一步，不另加一格。
        // 字色仍由 ReaderStyle.inkOverride 负责。
        seg(col, "背景", bgs.map { it.first to (it.second as Any) }, { readerBackground as Any }) {
            val wasLight = Color.red(readerBackground) > 100
            readerBackground = it as Int
            // 深浅互换时，在新页底留一条「切换到深色 / 浅色模式」的浮动胶囊（legado 的主题提醒）。
            if (wasLight != (Color.red(readerBackground) > 100)) ReaderUi.themeHint = true
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

    // ── 页眉页脚：总开关 + 各自显示什么 ───────────────
    /**
     * 页眉页脚的配置对话框。
     *
     * 改一下就地重画那两条 14 dp 细条（[ReaderUi.stripRefresh]），不重建页面 ——
     * 重建会把滚动位置打回章首。
     */
    fun showStripsDialog() {
        val box = Md3.column(this)
        /** 立即生效：落盘 + 重画页眉页脚。 */
        fun commit() {
            saveReaderExtras()
            ReaderUi.stripRefresh?.invoke()
        }
        /** 重画对话框本体。声明成变量是因为多选行点完要回头重画自己（同 showToolsDialog）。 */
        var render: () -> Unit = {}
        /** 多选一行：点一下加入或移除，存盘顺序按固定表，不按点选先后。 */
        fun partRow(id: String, label: String, allowed: List<String>,
                    cur: () -> List<String>, set: (List<String>) -> Unit) {
            val on = id in cur()
            box.addView(
                Widgets.listItem(this, label,
                    trailing = if (on) "check" else null,
                    onClick = {
                        val ids = cur().toMutableList()
                        if (on) ids.remove(id) else ids.add(id)
                        set(allowed.filter { it in ids })
                        commit()
                        render()
                    },
                ),
                Md3.lpDp(this, null, Md3.TAP),
            )
        }
        render = {
            box.removeAllViews()
            box.addView(
                Widgets.listItem(this, "显示页眉页脚",
                    subtitle = if (ReaderUi.stripsOn) "开：上下各让出 14 dp" else "关：正文多两行",
                    trailing = if (ReaderUi.stripsOn) "check" else null,
                    onClick = {
                        ReaderUi.stripsOn = !ReaderUi.stripsOn
                        commit()
                        render()
                    },
                ),
                Md3.lpDp(this, null, Md3.ITEM_TALL),
            )
            box.addView(Md3.text(this, "页眉显示", Md3.FS_SM, Md3.onSurfaceVariant)
                .apply { setPadding(Md3.dp(this@showReaderSettings, Md3.PAD), 0, 0, 0) })
            partRow("chapter", "章节名", STRIP_HEADER,
                { ReaderUi.headerParts }, { ReaderUi.headerParts = it })
            partRow("page", "屏号", STRIP_HEADER,
                { ReaderUi.headerParts }, { ReaderUi.headerParts = it })
            box.addView(Md3.text(this, "页脚显示", Md3.FS_SM, Md3.onSurfaceVariant)
                .apply { setPadding(Md3.dp(this@showReaderSettings, Md3.PAD), 0, 0, 0) })
            partRow("book", "书名", STRIP_FOOTER,
                { ReaderUi.footerParts }, { ReaderUi.footerParts = it })
            partRow("pct", "进度", STRIP_FOOTER,
                { ReaderUi.footerParts }, { ReaderUi.footerParts = it })
            partRow("battery", "电量", STRIP_FOOTER,
                { ReaderUi.footerParts }, { ReaderUi.footerParts = it })
        }
        render()
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(box)
        }
        // 关窗不重建页面：改一项已经就地重画过了。
        Widgets.md3Dialog(this, "页眉页脚",
            body = "页眉页脚各占 14 dp，关掉后正文多两行。两项可留空，也能关两项里的某一项。",
            content = sv,
            confirm = null, dismiss = "完成",
        ).show()
    }

    // ── 更多：legado MoreConfigSheet 里手表用得上的那几项 ────
    fun buildMore(): View {
        val col = Md3.column(this)
        // 页眉页脚总开关与显示项。它们属于「显示什么」而不是排版，
        // 所以下面那个「恢复默认排版」**不**回退这三项。
        col.addView(
            Widgets.listItem(this, "页眉页脚", "总开关与显示项", twoLine = true,
                onClick = { showStripsDialog() }),
            Md3.lpDp(this, null, Md3.ITEM_TALL),
        )
        seg(col, "常亮", listOf("关" to false as Any, "开" to true),
            { ReaderUi.keepOn as Any }) { ReaderUi.keepOn = it as Boolean }
        // 徽标：开时本章有书签就把顶栏 ⋮ 染成主色（legado 的 bookmarkBadge）。
        // 染色在建顶栏时定下，所以这一个开关仍然重建页面。
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

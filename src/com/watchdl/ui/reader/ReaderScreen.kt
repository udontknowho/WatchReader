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
import com.watchdl.ui.book.showDetail
import com.watchdl.ui.book.srcName
import com.watchdl.ui.shelf.shelfStore
import com.watchdl.ui.shelf.sourceForBook
import com.watchdl.ui.shelf.matchingBookJob
import com.watchdl.ui.search.askText
import com.watchdl.ui.my.showReplace
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
    var brightAuto = true
    /**
     * 这次阅读是从书架直接点进来的（legado 点书架就进 ReadBook）。
     *
     * 只影响返回落点：书架进来的退回家架，从目录进来的退回目录。
     * 置真的地方只有 [com.watchdl.ui.shelf.readShelfBook]，其余入口一律清零。
     */
    var fromShelf = false
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

    /**
     * 翻屏基准时长（ms）：整屏滚完要多久。
     *
     * 不满一屏按距离比例缩（[ReaderStyle.pageDuration]），所以它同时是上限。
     * 缺省 [ReaderStyle.PAGE_BASE_MS]，设置页的「时长」滑杆可改。
     */
    var pageBaseMs = ReaderStyle.PAGE_BASE_MS
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

    /**
     * 同上，但落点存的是**正文字符偏移**而不是像素：拼接块读完换章时用。
     *
     * 两块文本同源（下一章正文的头部），字符偏移到新页仍指同一行；
     * 用像素的话拼接块在旧页里排在整章正文之后，绝对 y 会多出一个章高。
     * -1 表示没有这个落点。
     */
    var restoreChars = -1
    var restoreDelta = 0

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
     * 窗口里除主块以外的正文块（前 1 / 后 1），按章号升序。
     *
     * 存到 [ReaderUi] 而不留在 `showReader` 的局部变量里：阅读设置里改字号、行距、
     * 字重、缩进时要连同这些块一起重排，而 [restyleReader] 只拿得到 [ReaderUi]。
     * 原始文本与控件成对保存，改字号时才能丢掉旧 span 重排。
     */
    val appended: MutableList<Pair<TextView, String>> = ArrayList()

    /**
     * 上面那些块的章节标题控件，与 [appended] 逐块对齐。
     *
     * 单独存一份是因为标题的上下留白与主标题不同（块间要留 18 dp），
     * 不能跟 [titleView] 共用 [restyleTitle]。
     */
    val heads: MutableList<TextView> = ArrayList()

    /**
     * 屏上已经有一页占位页（书架点击进来的「正在打开书籍…」）。
     *
     * [preview] 建临时页前先看它：已经有一页等着，再建一页就是白做一屏重活。
     */
    var holdPlaceholder = false

    /**
     * 跨章入场方向：[ReaderTransition.FORWARD] 顺读 / [ReaderTransition.BACKWARD] 回读 / 0 不动画。
     *
     * 由 [preview] 在派发换章时置位，[showReader] 读走并立刻清零 —— 只在跨章那一次
     * 重建上生效。改字号、切主题同样走 showReader，但它们不经 preview，读到的永远是 0。
     *
     * 1.6.1.6 及以前这里是另一套：换章前 `frame.draw(Canvas)` 拓整屏位图，新页就位后
     * 整层向上滑出。拓帧与整章排版挤同一帧，掉帧是「卡」，位图上滑是「跳」，所以 1.6.1.7
     * 连着删了。现在换成对新页本身做 translationY 位移，位图不参与。
     */
    var enterDir = ReaderTransition.NONE
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
    /** 等待属于当前请求；失败时解除，旧页仍可翻动。 */
    var pendingToken: Int? = null
    /** 预取和主动加载共享进行中的请求。 */
    val requests = mutableMapOf<ChapterTextKey, java.util.concurrent.FutureTask<String>>()
    val completed = ChapterTextCache()
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
        // 正文/标题控件是上一屏的，留着就是泄漏旧视图树。
        ReaderUi.bodyView = null
        ReaderUi.titleView = null
        // 拼接块的两个控件同样是上一屏的，留着会往已摘下的视图树里写字。
        ReaderUi.nextView = null
        ReaderUi.nextTitleView = null
        ReaderUi.nextRaw = ""
        // 窗口里追加进来的块同样是上一屏的控件，留着就是抓着已摘下的视图树。
        ReaderUi.appended.clear()
        ReaderUi.heads.clear()
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

/** 独立阅读底色的前景色，不借用全局主题的深浅状态。 */
internal fun readerInkFor(bg: Int): Int = ReaderStyle.inkOverride(bg)
    ?: if (Color.red(bg) > 100) Color.rgb(28, 27, 31) else Color.rgb(230, 225, 229)

/**
 * 记下「刚才读到哪」（legado 的阅读锚点）。
 *
 * 目录 / 进度滑条 / 章内查找都可能把人带到别的章，跳之前存一份
 * (书号, 章号, 滚动偏移)，新章底就会出「返回刚才那章」。只放进程内，不落盘。
 */
private fun MainActivity.markAnchor(info: BookInfo, index: Int) {
    ReaderUi.anchor = Triple(readingId(info), index, readerBody?.scrollY ?: 0)
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
internal fun readerToolTitle(id: String): String = when (id) {
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
    ReaderUi.brightAuto = true
    ReaderUi.tools = readerTools(p.getString("tools", null))
    ReaderUi.autoPageSec = p.getInt("autoPageSec", 10).coerceIn(1, 60)
    // 护眼整组已下线（用户要求）。旧存档里可能还留着 eye=true，
    // 这里不再读：没有入口能关掉，留着就永远蒙一层黄。
    ReaderUi.eyeCare = false
    ReaderUi.eyeLevel = p.getInt("eyeLevel", 1).coerceIn(0, 2)
    ReaderUi.eyeAuto = false
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
    ReaderUi.pageBaseMs = ReaderStyle.pageBaseClamp(
        p.getInt("pageBaseMs", ReaderStyle.PAGE_BASE_MS.toInt()).toLong())
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
        .putBoolean("brightAuto", true)
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
        .putInt("pageBaseMs", ReaderUi.pageBaseMs.toInt())
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
internal fun MainActivity.applyBright() {
    val w = window ?: return
    val attrs = w.attributes ?: return
    // 跟随系统时交回 -1f（BRIGHTNESS_OVERRIDE_NONE）：系统按环境光自己调，比锁死一档舒服。
    attrs.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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
    // 跨章入场方向：读走即清零，改字号 / 切主题也走这个函数，不留残余。
    val enterDir = ReaderUi.enterDir.also { ReaderUi.enterDir = ReaderTransition.NONE }
    // 首帧耗时从这个点算起：换章到上屏的真实代价，用它判断还要不要做后台预排。
    val enterNs = System.nanoTime()
    ReaderUi.pendingToken = null
    setScreen(Screen.Reader(info, index, text, src))
    // setScreen 只切状态、撤后台任务，**不碰视图树**。preview() 先铺了顶栏 +「正在取正文…」，
    // 这里不清干净，正文那一帧会与加载页各占 root 的一半（两个 weight=1）：
    // 上半屏卡着加载提示、下半屏才是正文，每次换章重画一次就是一闪一闪。
    root.removeAllViews()
    saveProgress(info, index, rememberBook = false)
    // 浅色底时整套前景色都要换：正文、顶栏、底栏按钮、进度。
    // 以前只换了正文，白底上顶栏还是黑块，上下章还是浅紫，几乎看不见。
    readerBackground = Md3.surface
    val light = Color.red(readerBackground) > 100
    val readerInk = Md3.onSurface
    val readerAccent = Md3.primary
    val readerMuted = Md3.onSurfaceVariant
    val hairline = if (light) 0x24000000 else 0x24FFFFFF
    // 扩展设置（亮度、护眼、段距、缩进）与字号/行距/背景同一处落盘。
    loadReaderExtras()
    applyBright()
    // 命中偏移只对当时那一章有意义，换章后旧列表全错位，直接清掉。
    val findKey = "${readingId(info)}#$index"
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
    // 状态区高 12 dp；窄边距也必须留足空间，并裁剪滚动正文，避免文字穿过浮层。
    val statusH = Md3.dp(this, 12)
    body.clipToPadding = true
    body.setPadding(pad, pad, pad, maxOf(pad, statusH))
    val col = Widgets.contentColumn(body)

    // ── 章节标题 ────────────────────────────────────────
    // legado 把章节标题排正文之前（TypographyTitleTab 的「标题位置」）。
    // 先建好再交给 restyleTitle 上样式，设置里改字号/字重/位置都不必重建页面。
    val titleTv = Md3.text(this, info.chapters.getOrNull(index)?.title.orEmpty(),
        readerSize + ReaderUi.titleAdd, readerInk, bold = ReaderUi.titleBold)

    // 正文容器：连续滚动用的多章列。第一章（本章）先放进去，
    // 滚到接近底部再把下一章追加到下面 —— 这样身体一直往下滑，不会闪现新屏。
    val chapCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    chapCol.addView(titleTv)
    ReaderUi.titleView = titleTv

    // 正文用专用字号（比正文 UI 大），行距 1.8 在手表上偏松，手机上刚好。
    val shown = if (text.isEmpty()) "本章无正文" else shownText(text)
    val tv = Md3.text(this, shown, readerSize, readerInk)
    tv.justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
    tv.setPadding(Md3.dp(this, 3), Md3.dp(this, 2), Md3.dp(this, 3), Md3.dp(this, 24))
    chapCol.addView(tv)
    col.addView(chapCol)
    ReaderUi.bodyView = tv
    // 字重/斜体/字距/行距/段距/缩进一次性套上：与设置面板改值走同一个函数，
    // 两边样式不会走形。
    restyleReader(text)

    // 后台预排命中：换成预排件，首帧只剩断行与布局，不再做字形测量。
    // 断行一致性在后台排两遍逐行比过（ReaderPrecompute），这里只比参数与正文内容；
    // 不命中就走原来的排版路径，行为与 1.6.1.8 一样。
    ReaderPrecompute.take(preKey(info, index), shown, preSpec(tv))
        ?.let { tv.setText(it, TextView.BufferType.SPANNABLE) }

    // ── 三块窗口：前 1 / 当前 / 后 1 ────────────────────────
    //
    // 抄 legado 的固定三章窗口（`ReadBook.kt:184-186` 的 prev / cur / next 三个字段）：
    // 屏上最多留「前 1 / 当前 / 后 1」三章，游标一动就指针平移，第 4 章永不加载。
    // 以前只往后 append，往上一滑就到头，往上读完一章非得跳屏。
    //
    /**
     * 窗口块：章号 / 章节标题 / 正文 / 原始正文。
     *
     * 首块恒为**主块**（`b.body === tv`，就是建屏那一章的正文）。主块不参与摘除：
     * [mainLimit]、[ReaderUi.bodyView]、章内查找、`off_` 存档口径都指着它，摘掉它
     * 整屏就没有锚了。它只会在游标平移到后面时留在窗口里让位（多占一块正文，不上屏）。
     * 表按章号升序，同一章只在表里出现一次 —— 异步回调先查这张表，就不会插两遍。
     */
    class WinBlock(val ch: Int, val head: TextView, val body: TextView, val raw: String)
    val win = ArrayList<WinBlock>()
    /** 窗口块数上限：主块 + 前 1 + 当前 + 后 1，再多就是无限追下去。 */
    val winCap = 4
    /** 正在后台取的章号。滚动事件很密，不拦就会同一章叠出两条链。 */
    val loading = HashSet<Int>()
    /** 前插的 scrollY 补偿还没落地：这期间不再前插，否则两次位移会叠算两遍。 */
    var prependBusy = false
    /** 窗口第一块就是建屏那一章，先登记，后面所有块按章号往里插。 */
    win.add(WinBlock(index, titleTv, tv, text))

    /**
     * 追加/前插块的标题控件：字号、字重、位置跟主标题一起变，上下留白走块间距。
     *
     * 与 [restyleTitle] 分开：块与块之间要留 18 dp，主标题顶上是页边距，两者不能共用一个内边距。
     */
    fun blockHead(title: String): TextView {
        val v = Md3.text(this, title, readerSize, readerInk)
        styleBlockHead(v)
        return v
    }

    /** 追加/前插块的正文控件：与主正文同一套版式，改字号时才不会有两种行高。 */
    fun blockBody(ch: Int, raw: String): TextView {
        val shown = shownText(raw)
        val v = Md3.text(this, shown, readerSize, readerInk)
        v.justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
        v.setPadding(Md3.dp(this, 3), Md3.dp(this, 2), Md3.dp(this, 3), Md3.dp(this, 24))
        styleBody(v, raw)
        // 预排件：换块时不必在滚动中重排一整章。
        ReaderPrecompute.take(preKey(info, ch), shown, preSpec(v))
            ?.let { v.setText(it, TextView.BufferType.SPANNABLE) }
        return v
    }

    /** 窗口块边界：章号 / 块顶在滚动内容里的 y。
     *
     * 连续滚动会把邻章接在本章上下，读者滑过去就是下一章，但 [index] 是建屏时定死的，
     * **这一屏推不动自己的章号**（`:390` 起）。没有这张表，滚过追加区再前进仍按
     * `index + 1` 重建，就是重来一遍刚读完的章。
     * 表里按章号升序，[cursorAt] 用视口顶反查当前读到哪章。
     */
    val chapBounds = ArrayList<Pair<Int, Int>>()
    /** 当前游标章号：滚到哪里算哪里，标题 / 进度 / 章钮都跟它走。 */
    var cursor = index

    /** 这一章在窗口里的块；不在窗口里返回 null。 */
    fun blockOf(ch: Int): WinBlock? = win.firstOrNull { it.ch == ch }

    /**
     * 重建 [chapBounds]：主块按正文顶边，其余块按各自标题的顶边。
     *
     * 高度只在排版后才准，所以每次滚动与每次增删块后都重算一遍，不缓存像素值。
     * 刚插进来的块在这一次布局之前 `.top` 还是 0，算出来的表不是升序 ——
     * 那种表会让 [cursorAt] 把游标算到后一章去，所以整表不提交，等下次滚动再算。
     * 空表意味着排版还没落地，此时 [cursorAt] 不切游标，避免刚建屏就误判。
     */
    fun syncChapBounds() {
        if (body.height <= 0) return
        val list = ArrayList<Pair<Int, Int>>(win.size)
        var lastTop = Int.MIN_VALUE
        for (b in win) {
            val top = if (b.body === tv) childTopIn(body, tv) - tv.paddingTop
                      else childTopIn(body, b.head) - b.head.paddingTop
            if (top <= lastTop) return
            lastTop = top
            list.add(b.ch to top)
        }
        chapBounds.clear()
        chapBounds.addAll(list)
    }

    /** 视口顶落在哪章；表空时退回窗口首块（而不是建屏章：前面可能已经前插了一块）。 */
    fun cursorAt(): Int = ReaderPaging.cursorAt(
        chapBounds, body.scrollY + body.paddingTop, win.firstOrNull()?.ch ?: index)

    /** 把窗口块同步给 [ReaderUi]：改字号要能重排所有块，[ReaderMenu.reset] 要能清干净。 */
    fun publishWindow() {
        ReaderUi.appended.clear()
        ReaderUi.heads.clear()
        for (b in win) {
            if (b.body === tv) continue
            ReaderUi.appended.add(b.body to b.raw)
            ReaderUi.heads.add(b.head)
        }
    }

    /** 第 [i] 块在滚动内容里的身高：用相邻块顶差算，末块用内容总高兜底。 */
    fun blockSpan(i: Int): Int {
        val b = win.getOrNull(i) ?: return 0
        val top = childTopIn(body, b.head)
        val next = win.getOrNull(i + 1)?.let { childTopIn(body, it.head) }
            ?: (body.getChildAt(0)?.height ?: 0)
        return (next - top).coerceAtLeast(0)
    }

    /**
     * 把窗口收回到游标前后的三块：离窗的块从视图树里摘掉。
     *
     * 摘掉视口上方的块必须**同帧补偿 scrollY**（摘多高就减多少），否则整页往上跳一截；
     * 下方的块不影响当前视口，直接摘。主块永不摘（见 [WinBlock]）。
     */
    fun pruneWindow() {
        if (win.size <= 1) return
        val drop = ReaderPaging.dropOutside(win.map { it.ch }, cursor, info.chapters.size, index)
        if (drop.isEmpty()) return
        for (ch in drop) {
            val i = win.indexOfFirst { it.ch == ch }
            if (i < 0) continue
            val b = win[i]
            if (b.body === tv) continue
            // 摘之前先量它有多高：它上面的章要按这个高度把 scrollY 补回去。
            val span = blockSpan(i)
            val above = childTopIn(body, b.head) < body.scrollY + body.paddingTop
            win.removeAt(i)
            chapCol.removeView(b.head)
            chapCol.removeView(b.body)
            if (above && span > 0) body.scrollTo(0, (body.scrollY - span).coerceAtLeast(0))
        }
        publishWindow()
        syncChapBounds()
    }

    /**
     * 块内锚：把视口顶那一行换算成「字符偏移 + 行内余量」。
     *
     * 换章重建要拿它当落点：同一块的字符偏移到新页仍指同一行；用像素的话，
     * 窗口里这块上面还压着上一章，绝对 y 会多出一整个章高。取不到布局时返回 null。
     */
    fun anchorAt(desiredTop: Int): Pair<WinBlock, ReaderPaging.Anchor>? {
        var at = win.firstOrNull() ?: return null
        for (b in win) if (childTopIn(body, b.head) <= desiredTop) at = b else break
        val layout = at.body.layout ?: return null
        if (layout.lineCount == 0) return null
        val top = childTopIn(body, at.body) + at.body.paddingTop
        val line = layout.getLineForVertical((desiredTop - top).coerceAtLeast(0))
        return at to ReaderPaging.anchor(
            layout.getLineStart(line), desiredTop, top, layout.getLineTop(line))
    }

    /**
     * 把当前位置记成「第 [ch] 章的字符偏移」，写进 [ReaderUi.restoreChars]。
     *
     * 只在视口顶正落在 [ch] 那一块时成立：跨章时字符偏移属于另一段正文，
     * 落到新页会指错行。写不成返回 false，调用方退回像素路径（[ReaderUi.restoreAt]）。
     */
    fun rememberChars(ch: Int): Boolean {
        val (block, anchor) = anchorAt(body.scrollY + body.paddingTop) ?: return false
        if (block.ch != ch) return false
        ReaderUi.restoreKey = "${readingId(info)}#$ch"
        ReaderUi.restoreChars = anchor.chars
        ReaderUi.restoreDelta = anchor.delta
        ReaderUi.restoreAt = -1
        return true
    }

    /**
     * 把下一章接到窗口末尾。
     *
     * 只有游标已经走到窗口末尾才补（游标后面恒有一章待读，窗口就不会越堆越长）；
     * 正文不足一屏时例外，否则读者滑不动。
     * 失败不提示：章末再戳一下仍能走原来的换屏路径，行为不比以前差。
     * 空正文也不追加 —— 追加一条空块只会把读者卡在一段空白里。
     */
    fun appendNext() {
        val last = win.lastOrNull() ?: return
        if (last.ch > cursor && scrollLimit(body) > body.height) return
        val next = last.ch + 1
        if (next >= info.chapters.size) return
        // 付费章取不到正文：留给换屏路径去跳过，这里不伸手。
        if (info.chapters[next].locked) return
        if (win.size >= winCap || !loading.add(next)) return
        val ch = info.chapters[next]
        bg(curScreen, body = { loadOpening(info, ch, src) }) { r ->
            loading.remove(next)
            val raw = r.getOrNull()
            if (raw.isNullOrBlank()) return@bg
            // 三道硬守卫：这一屏还在、章号仍在游标 ±1 以内、窗口里还没有这一章。
            // 过期回调（读者已经翻走、或者预取与追加各回来一次）在这里被丢掉。
            if (ReaderUi.bodyView !== tv) return@bg
            if (next !in cursor - 1..cursor + 1) return@bg
            if (win.any { it.ch == next }) return@bg
            if (win.size >= winCap) return@bg
            val head = blockHead(ch.title)
            val nx = blockBody(next, raw)
            win.add(WinBlock(next, head, nx, raw))
            chapCol.addView(head)
            chapCol.addView(nx)
            publishWindow()
            // 块顶要等这一帧排完才有真实高度：先 post，拿不到就下次滚动再算。
            body.post { syncChapBounds() }
            // 埋点：连续滚动最容易出的错是同一章被追加两遍（预取与 appendNext 各挂一次）。
            // 只在开发者日志开启时落盘，用来数「同一章追加了几次」。
            com.watchdl.core.DevLog.log("Reader", "append idx=$next n=${win.size} chars=${raw.length} h=${scrollLimit(body)}")
            // 本章不满一屏：追加完立刻再试一次，否则读者滑不动。
            body.post { if (scrollLimit(body) <= body.height) appendNext() }
        }
    }

    /** 视图还没挂上窗口时先记下的落位闭包，挂上后由 preDraw 那一套取走。 */
    var pendingPlace: (() -> Int)? = null

    /**
     * 首帧之前落位。
     *
     * `body.post{}` 排在消息队列里，可能首帧已经画出章首才滚 —— 换章时就是“先闪一下章首再跳”。
     * `OnPreDraw` 在布局之后、绘制之前，这里到位当帧就是最终位置。
     * 目标位置要等排版完成（maxScroll、拼接块高度）才知道，所以传闭包。
     * 脱离窗口的 [android.view.ViewTreeObserver] 是浮动对象，注册的监听会丢，
     * 所以未挂窗口时先存着，由 showReader 末尾挂好后重调。
     */
    fun placeBeforeDraw(compute: () -> Int) {
        if (!body.isAttachedToWindow) {
            // 还没挂上窗口：浮动 ViewTreeObserver 上注册的监听会丢，等挂上来再试一次。
            pendingPlace = compute
            body.post {
                val p = pendingPlace ?: return@post
                pendingPlace = null
                if (body.isAttachedToWindow) placeBeforeDraw(p)
                else body.scrollTo(0, p().coerceIn(0, scrollLimit(body)))
            }
            return
        }
        val vto = body.viewTreeObserver
        val l = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (vto.isAlive) vto.removeOnPreDrawListener(this)
                body.scrollTo(0, compute().coerceIn(0, scrollLimit(body)))
                return true
            }
        }
        vto.addOnPreDrawListener(l)
    }

    /**
     * 往窗口前面插一块（[cursor] 的上一章）。
     *
     * 前插会把它下面的内容整体下移一整章高，所以插入前记下「旧首块顶」，
     * 布局完、绘制前把位移补进 scrollY —— 补在同一帧里，画面一动不动。
     * 不补就是「往上一滑，整页跳下去一章」，这就是从前的观感。
     */
    fun prependPrev() {
        val first = win.firstOrNull() ?: return
        if (prependBusy) return
        // 游标还没退到窗口首块：它上面那章已经在屏上，不用再补。
        if (cursor > first.ch) return
        val prev = first.ch - 1
        if (prev < 0) return
        if (info.chapters[prev].locked) return
        if (win.size >= winCap || !loading.add(prev)) return
        val ch = info.chapters[prev]
        bg(curScreen, body = { loadOpening(info, ch, src) }) { r ->
            loading.remove(prev)
            val raw = r.getOrNull()
            if (raw.isNullOrBlank()) return@bg
            if (ReaderUi.bodyView !== tv) return@bg
            if (prev !in cursor - 1..cursor + 1) return@bg
            if (win.any { it.ch == prev }) return@bg
            if (win.size >= winCap) return@bg
            val head = blockHead(ch.title)
            val nx = blockBody(prev, raw)
            // 插入前量一次旧首块顶，插入后同一点会整体下移一个块高。
            val keep = win.first()
            val before = childTopIn(body, keep.head)
            win.add(0, WinBlock(prev, head, nx, raw))
            chapCol.addView(nx, 0)
            chapCol.addView(head, 0)
            publishWindow()
            prependBusy = true
            placeBeforeDraw {
                prependBusy = false
                // 旧首块被顶下去多少，scrollY 就加多少：视口留在原处，一个字都不动。
                (body.scrollY + childTopIn(body, keep.head) - before).coerceAtLeast(0)
            }
            // 兜底：PreDraw 万一没跑到（视图被摘），别把前插永久锁死。
            body.postDelayed({ prependBusy = false }, 400L)
            body.post { syncChapBounds() }
        }
    }

    /** 游标变动后的显示回调，底栏建好后绑上（局部函数看不到后面声明的视图）。 */
    var onCursor: ((Int) -> Unit)? = null

    /**
     * 游标切到 [at]：进度滑条、章钮状态、落盘一起跟。
     *
     * 只改显示与落盘目标，不重建视图 —— 正文已经在屏上，重建就是当前这个 bug 的成因。
     * **不碰任何块的标题**：每块自带标题，改写主块标题只会让往上滑回去时看到别的章名
     * （从前那行 `titleTv.text = t` 就是这个毛病）。
     */
    fun applyCursor(at: Int) {
        if (at == cursor) return
        cursor = at
        readerIndex = at
        readerOffset = body.scrollY
        onCursor?.invoke(at)
        saveProgress(info, at, rememberBook = false)
    }

    fun chapterPending(): Boolean = ReaderUi.pendingToken != null
    ++ReaderUi.spliceGen

    /**
     * 拼接块存在时，「本章读完」的位置是正文 tv 的底边，不是整页底边。
     *
     * 进度、滑条分母都用它：用整页 [scrollLimit] 算，全书进度会在章末虚高
     * （分母里含了下一章那几百字）。可滚上限仍用 [scrollLimit]。
     * 正文未排版时 tv.height 为 0，这里退回 0，调用方自己当「无进度」处理。
     */
    fun mainLimit(): Int {
        // 连续滚动后滚动内容含后续章节，分母必须只算到本章正文的底边，
        // 否则进度条会把后面追加的几章也算进本章。
        // 单位统一到 scrollLimit 的口径（含 paddingTop）：childTopIn 返回的是
        // 「内容顶 + paddingTop」坐标，再减一个 paddingTop 就错位了。
        val vp = body.height - body.paddingTop - body.paddingBottom
        if (vp <= 0) return 0
        val end = childTopIn(body, tv) + tv.height + tv.paddingBottom
        return (end - vp).coerceIn(0, scrollLimit(body))
    }

    /**
     * 现在该落盘的章内偏移。
     *
     * 防抖存盘、换章前保存、onPause 落盘三处共用一条口径，不再各算各的：
     * 拼接区里整屏 `scrollY` 会被限幅成「读完」，回读就落到后一章
     * （表现的却是「显示第 3 章、实际停在第 5 章」）。
     */
    fun savedOffset(at: Int): Int = ReaderPaging.savedOffset(
        chapBounds, at, body.scrollY, body.paddingTop, mainLimit())

    /**
     * 滚动内容坐标 [y] 落在哪一块正文上：返回那块 layout 与它文字顶边的 y。
     *
     * 窗口里有前 1 / 后 1 两块，翻屏吸附、行顶计算不能再拿本章一个坐标系套下去。
     * 未排版时返回 null，调用方退回不吸附。
     */
    fun textAt(y: Int): Pair<android.text.Layout, Int>? {
        var at: WinBlock? = null
        for (b in win) if (childTopIn(body, b.head) <= y) at = b else break
        val b = at ?: win.firstOrNull() ?: return null
        val layout = b.body.layout ?: return null
        return layout to (childTopIn(body, b.body) + b.body.paddingTop)
    }

    // 底角状态浮层位于正文裁剪区之外，不参与滚动内容布局。
    // 两个标签均不消费触摸，底部翻页分区保持完整。
    fun statusLabel() = Md3.text(this, "", 9f, readerMuted).apply {
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        includeFontPadding = false
        background = null
        elevation = 0f
        isClickable = false
        isFocusable = false
    }
    val timeTv = statusLabel()
    val batteryTv = statusLabel()
    // 时间格式跟系统走：24 小时制 / 12 小时制由用户在系统里选的，写死 HH:mm 就是逆着用户。
    val timeFmt = android.text.format.DateFormat.getTimeFormat(this)
    fun refreshStatus() {
        // 电量不挂 receiver：直接读 ACTION_BATTERY_CHANGED 的粘性广播。
        // 每 15 秒重读一次，电量变化最晚 15 秒后到位，不差这几秒。
        val pct = runCatching {
            val i = registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
            )
            val lvl = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        }.getOrDefault(-1)
        timeTv.text = timeFmt.format(java.util.Date())
        batteryTv.text = if (pct >= 0) "$pct%" else ""
    }
    // 15 秒对一次表：分钟精度够用，秒级轮询在手表上纯费电。
    val statusTick = object : Runnable {
        override fun run() {
            refreshStatus()
            timeTv.postDelayed(this, 15_000L)
        }
    }
    // 出屏就摘定时器：换章会整页重建，留着就是每隔 15 秒往已摘下的视图树里写字。
    timeTv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeCallbacks(statusTick)
            v.post(statusTick)
        }

        override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(statusTick) }
    })

    frame.addView(body, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    frame.addView(timeTv, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, statusH, Gravity.BOTTOM or Gravity.START).apply {
        leftMargin = pad
    })
    frame.addView(batteryTv, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, statusH, Gravity.BOTTOM or Gravity.END).apply {
        rightMargin = pad
    })

    /** 章内百分比：正文滚到哪算哪，0 是章首、100 是章末（有拼接块时按 [mainLimit] 算）。 */
    fun innerPct(): Int {
        val limit = mainLimit()
        if (limit <= 0) return 0
        return (body.scrollY * 100 / limit).coerceIn(0, 100)
    }

    val total = info.chapters.size.coerceAtLeast(1)

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
    var clearCover: (() -> Unit)? = null
    fun cancelPageMotion() {
        pageAnim?.cancel()
        pageAnim = null
        clearCover?.invoke()
    }
    fun cancelMotion() {
        cancelPageMotion()
        body.cancelCrownAnimation()
    }
    body.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            cancelMotion()
            ReaderUi.pendingToken = null
            ReaderUi.pageToken++
        }
    })

    /** 往下滚到章末：窗口末尾再往下就是换屏换章。 */
    fun toNext() {
        if (chapterPending()) return
        // 从窗口末尾往后找：窗口里的章已经读过，不能按建屏时的 index 重来。
        val from = (win.lastOrNull()?.ch ?: cursor) + 1
        val next = (from until info.chapters.size).firstOrNull { !info.chapters[it].locked }
        if (next == null) { ReaderAuto.stop(); toast("已是最后一章"); return }
        cancelMotion()
        ReaderUi.anchor = null
        preview(info, next)
    }

    /** 往上到窗口之前的章：再往上要新取一章，只能换屏。 */
    fun toPrevious() {
        if (chapterPending()) return
        val from = win.firstOrNull()?.ch ?: cursor
        if (from <= 0) { toast("已是第一章"); return }
        cancelMotion()
        prevChapter(info, from, atEnd = true)
    }

    /**
     * 平滑滚一屏。步距按「一屏减一行」走，到内容底 / 顶才换章。
     *
     * 步距照 legado 的 `ReaderScrollPolicy.pageStep`：新屏顶上留一行旧内容做接续。
     * 关键是**跨块不另算**：章末接的下一章开头与本章正文同一条版式，
     * 「一屏减一行」在接缝处照样成立。
     * 落点再吸附到行顶，行号用落点所在那一块自己的 layout（见 [textAt]）。
     */
    fun scrollPage(forward: Boolean) {
        if (chapterPending() || !body.isLaidOut) return
        cancelMotion()
        val limit = scrollLimit(body)
        val from = body.scrollY
        val vp = (body.height - body.paddingTop - body.paddingBottom).coerceAtLeast(1)
        if (forward && from >= limit) { toNext(); return }
        if (!forward && from <= 0) { toPrevious(); return }
        // 行高按主正文取：三块走同一条 [styleBody]，行高本来就一致。
        val step = ReaderPaging.step(vp, tv.lineHeight.coerceAtLeast(1))
        val target = ReaderPaging.target(from, forward, step, limit) { raw ->
            // 吸附到落点所在那一块的行顶：窗口里有前/后两章，不能拿本章的坐标系算。
            val at = textAt(raw + body.paddingTop)
            val layout = at?.first
            val top = at?.second ?: 0
            if (layout == null || layout.lineCount == 0) raw else {
                val li = layout.getLineForVertical((raw + body.paddingTop - top).coerceAtLeast(0))
                top + layout.getLineTop(li) - body.paddingTop
            }
        }
        if (target == from) return
        // 覆盖：先把当前屏定格成位图，正文瞬间滚到目标，再让定格的旧屏整体滑出，
        // 露出下面的新屏 —— 就是 legado 的「新一屏从下往上盖住旧的」。
        // 只影响章内翻屏；跳章（上面两个 return）不经过这里。
        if (ReaderUi.pageAnim == 1 && body.width > 0 && body.height > 0) {
            pageAnim?.cancel()
            val vh = body.height
            val snap = Bitmap.createBitmap(body.width, vh, Bitmap.Config.ARGB_8888)
            body.draw(Canvas(snap))
            body.scrollTo(0, target)
            val iv = ImageView(this).apply {
                setImageBitmap(snap)
                scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false
            }
            // 插在 body 之后（index 1）：压住正文，又不盖住后面建的菜单浮层。
            frame.addView(iv, 1,
                FrameLayout.LayoutParams(body.width, vh, Gravity.TOP))
            val cleanup = {
                iv.animate().cancel()
                (iv.parent as? ViewGroup)?.removeView(iv)
                if (!snap.isRecycled) snap.recycle()
                clearCover = null
            }
            clearCover = cleanup
            iv.animate().translationY((if (forward) -vh else vh).toFloat())
                .setDuration(COVER_MS)
                .setInterpolator(android.view.animation.LinearInterpolator())
                .withEndAction {
                    // 连点或换页可能已经把它摘了：判空再摘，不重复回收位图。
                    cleanup()
                }.start()
            return
        }
        // 匀速、时长按距离比例（legado 用 Animatable + LinearEasing，见
        // `ReaderCanvasSurface.kt:689-692`）。不用 smoothScrollTo：减速曲线前快后慢，
        // 一屏一屏读时看着像文字在滑走。
        pageAnim?.cancel()
        pageAnim = android.animation.ValueAnimator.ofInt(from, target).apply {
            duration = ReaderStyle.pageDuration(target - from, vp, ReaderUi.pageBaseMs)
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
                if (ReaderMenu.open) return
                scrollPage(true)
                if (ReaderAuto.handle === this && ReaderAuto.on && !chapterPending()) {
                    ReaderAuto.MAIN.postDelayed(this, ReaderAuto.sec * 1000L)
                }
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
    body.onInputStart = {
        cancelPageMotion()
        ReaderAuto.cancel()
        if (!ReaderMenu.open && ReaderAuto.on) scheduleAuto()
    }
    body.onBoundary = { forward, distance ->
        if (!ReaderMenu.open && !chapterPending() && body.isLaidOut) {
            if (forward) toNext() else toPrevious()
        }
    }
    var touchY = 0f
    var touchScroll = 0
    body.setOnTouchListener { _, e ->
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            touchY = e.y
            touchScroll = body.scrollY
            cancelMotion()
            ReaderAuto.cancel()
        }
        if (e.actionMasked == MotionEvent.ACTION_UP && !ReaderMenu.open && !chapterPending()) {
            // 同一手势先滚到边界再继续拖：用未被正文消费的距离判断，不能要求再拖一次。
            val drag = touchY - e.y
            val unconsumed = drag - (body.scrollY - touchScroll)
            val threshold = android.view.ViewConfiguration.get(this).scaledTouchSlop * 2
            if (unconsumed > threshold && body.scrollY >= scrollLimit(body)) toNext()
            else if (unconsumed < -threshold && body.scrollY <= 0) toPrevious()
        }
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
    // 副行按章号取：连续滚动进了下一章时顶栏要跟着改，否则菜单里写着上一章的章名。
    fun subLineFor(at: Int): String = listOfNotNull(
        info.chapters.getOrNull(at)?.title?.takeIf { it.isNotBlank() },
        srcName?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val subLine = subLineFor(index)
    // 顶栏三枚动作（刷新 / 换源 / ⋮）：legado 默认顶栏是「下载 / 刷新 / 换源 / 更多」四枚，
    // 手表 189 dp 宽只够三枚，下载归入「更多操作」Sheet。
    // 宽度预算：顶栏可用 189−8=181 dp（Widgets.topBar 自带 PAD/2 两侧内边距）
    // = 返回 32 + 书名约 45 + 刷新 28 + 换源 28 + ⋮ 48；书名只剩两字多，
    // 所以 14 sp 单行 ellipsize。
    val bar = Widgets.topBar(this, info.name, onBack = {
        returnFromReader(info)
    }, subtitle = subLine)
    bar.setBackgroundColor(readerBackground)
    Widgets.tintSubtree(bar, readerInk)
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
    // 书名让位：topBar 建出的是 FS_LG（16 sp），一行装不下「书名 + 三枚动作」。
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
    refreshBtn.background = Md3.ripple(this, Color.TRANSPARENT, 14f)
    refreshBtn.contentDescription = "刷新本章"
    refreshBtn.setOnClickListener { preview(info, index) }
    bar.addView(refreshBtn, LinearLayout.LayoutParams(
        Md3.dp(this, 28), Md3.dp(this, Md3.TOP_BAR)))
    val swapBtn = Icons.view(this, "swap", readerMuted, 18)
    swapBtn.minimumWidth = Md3.dp(this, 28)
    swapBtn.minimumHeight = Md3.dp(this, Md3.TOP_BAR)
    swapBtn.setPadding(Md3.dp(this, 5), 0, Md3.dp(this, 5), 0)
    swapBtn.isClickable = true
    swapBtn.background = Md3.ripple(this, Color.TRANSPARENT, 14f)
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
    more.background = Md3.ripple(this, Color.TRANSPARENT, 16f)
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
        cell.background = Md3.ripple(this, Color.TRANSPARENT, 6f)
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
        v.background = Md3.ripple(this, Color.TRANSPARENT, 14f)
        v.setOnClickListener { act() }
        return v
    }

    val prev = chapterArrow("上一章", false) { prevChapter(info, cursor) }
    // 第一章时置灰并真的禁用：以前只调 alpha，点了还能按，弹一句「已是第一章」。
    if (index <= 0) { prev.alpha = 0.35f; prev.isClickable = false }
    foot.addView(prev, LinearLayout.LayoutParams(Md3.dp(this, 28), Md3.dp(this, 28)))

    // 章内百分比在上方声明（innerPct），底栏进度行要用它当起点。
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
            subTv?.text = "第 ${at + 1}/$total 章 · ${info.chapters.getOrNull(at)?.title.orEmpty()}"
        }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { draggingSeek = true }
        // 松手才跳章：拖的过程中每动一格就取一次正文，等于把整本书拉一遍。
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
            draggingSeek = false
            // 恢复当前真正在读的那章：拼接区里 cursor 已经进到追加章，
            // 退回 subLine（首章）会跟正文对不上。
            subTv?.text = subLineFor(cursor)
            var target = ((sb?.progress ?: atStart) / 100).coerceIn(0, total - 1)
            if (info.chapters.getOrNull(target)?.locked == true) {
                // 付费章取不到正文：前后各找一章免费的，找不到就退回原处。
                val free = (target + 1 until total).firstOrNull { info.chapters.getOrNull(it)?.locked == false }
                    ?: (target - 1 downTo 0).firstOrNull { info.chapters.getOrNull(it)?.locked == false }
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

    val next = chapterArrow("下一章", true) {
        // 已读进窗口的章不能再跳一遍：底栏从窗口末章往后取。
        nextChapter(info, maxOf(cursor, win.lastOrNull()?.ch ?: cursor))
    }
    if (index >= total - 1) { next.alpha = 0.35f; next.isClickable = false }
    foot.addView(next, LinearLayout.LayoutParams(Md3.dp(this, 28), Md3.dp(this, 28)))
    foot.layoutParams = Md3.lpDp(this, null, 36)

    // 游标一变就同步进度与章钮：正文不重建，只有这两样要跟。
    onCursor = { at ->
        val pct = if (mainLimit() > 0) (body.scrollY * 100 / mainLimit()).coerceIn(0, 100) else 0
        seek.progress = (at * 100 + pct).coerceIn(0, 100 * total)
        // 顶栏副行也跟游标走：不然滚进追加的下一章后，菜单里还写着首章章名，
        // 与屏上正在读的那章对不上。
        subTv?.text = subLineFor(at)
        prev.alpha = 1f
        prev.isClickable = true
        next.alpha = 1f
        next.isClickable = true
        if (at <= 0) { prev.alpha = 0.35f; prev.isClickable = false }
        if (at >= total - 1) { next.alpha = 0.35f; next.isClickable = false }
    }

    // 亮度始终交还系统，底栏仅保留进度和工具格。

    // 底栏三行（自上而下）：分隔线 / 亮度行 32 / 分隔线 / 进度行 36 / 分隔线 / 工具格。
    // 行序照 legado 的 ReadBookMenuBarBottom：亮度在上、进度居中、工具格在下。
    // 高度账（189×248 dp）：1 + 32 + 1 + 36 + 1 + 工具格 = 111 dp（工具格 40 时），
    // 占窗口 45%，比 legado 的 64% 上限还留 47 dp。
    val bottom = Md3.column(this)
    bottom.setBackgroundColor(readerBackground)
    bottom.isClickable = true
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
        val capHeight = 30
        cap.minimumHeight = Md3.dp(this, capHeight)
        cap.background = Md3.ripple(this, Md3.surfaceContainerHigh, capHeight / 2f)
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
        if (on) { cancelMotion(); clearPills(); ReaderAuto.cancel() } else if (ReaderAuto.on) scheduleAuto()
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
    readerBookId = readingId(info)
    readerIndex = index
    // 生命周期落盘 / 换章前保存都问这里要位置：章号跟游标走，偏移是章内值。
    // 给整屏 scrollY 就会在拼接区存下一大串数字，回读时被限幅到章末、跳进后一章。
    readerPos = { cursor to savedOffset(cursor) }
    body.setOnScrollChangeListener { _, _, y, _, _ ->
        val prevY = readerOffset
        readerOffset = y
        // 游标跟着滚动走：进窗口里别的块就把章号、标题、进度切到那一章。
        if (win.size > 1) {
            syncChapBounds()
            applyCursor(cursorAt())
            // 游标一动，离窗的块就该摘了：不摘窗口会一路长下去。
            pruneWindow()
        }
        // 往回滑到窗口顶就往前补一章。不补就只能顶在首块不动 —— 这就是「只能往下读」。
        // 阈值取一行高：贴边就取，读者不会看到顶死的空白。
        if (y < prevY && y <= tv.lineHeight.coerceAtLeast(1)) prependPrev()
        // 进度滑条跟着正文走（不触发跳章：回调里只认 fromUser）。
        if (!draggingSeek) {
            val limit = mainLimit()
            val pct = if (limit > 0) (y * 100 / limit).coerceIn(0, 100) else 0
            seek.progress = cursor * 100 + pct
        }
        scrollSave?.let { body.removeCallbacks(it) }
        val task = Runnable {
            // 章内偏移，不是整屏 scrollY：`off_` 是按章内限幅恢复的。
            // 在拼接区读第 N 章时，整屏值会远超首章可读高度，被限幅成「读完」，
            // 下次打开就从那一章的末尾甚至后一章开始，看着像跳到别处。
            saveOffset(info, cursor, savedOffset(cursor))
        }
        scrollSave = task
        body.postDelayed(task, 400)
        // 距底不足一屏就把下一章追加进来：继续往下滑就是下一章，不再重建整屏。
        val vp = body.height - body.paddingTop - body.paddingBottom
        if (vp > 0 && scrollLimit(body) - y <= vp) appendNext()
    }
    // 恢复上次读到的地方。等布局完成再滚，否则拿不到 maxScroll。
    // 「返回刚才那章」胶囊回跳时位置是钉死的：落回当时那一屏，不是那一章上次读到的位置。
    val pinned = if (ReaderUi.restoreKey == findKey) ReaderUi.restoreAt else -1
    val pinnedChars = if (ReaderUi.restoreKey == findKey) ReaderUi.restoreChars else -1
    val pinnedDelta = if (ReaderUi.restoreKey == findKey) ReaderUi.restoreDelta else 0
    if (ReaderUi.restoreKey == findKey) {
        ReaderUi.restoreKey = ""
        ReaderUi.restoreAt = -1
        ReaderUi.restoreChars = -1
        ReaderUi.restoreDelta = 0
    }
    val restore = readOffset(info).takeIf { it > 0 }
    if (atEnd) {
        // 从上屏退上来的：落在上一章末屏，接着往上读。
        // legado 的窗口一次带四页，退一屏跨章时看到的正是上一章的末页，不是章首。
        // 有拼接块时章末是正文底边：整页底会落进下一章的开头（读的是别人的字）。
        placeBeforeDraw { mainLimit() }
    } else if (pinnedChars >= 0) {
        // 拼接块里读完换过来的：按字符偏移落位。新页自己排版后的行号才是准的，
        // 旧页的像素坐标在这里没有意义（那个坐标里多着一整章正文）。
        placeBeforeDraw {
            val l = tv.layout
            if (l == null) 0 else ReaderPaging.restore(
                ReaderPaging.Anchor(pinnedChars, pinnedDelta), childTopIn(body, tv) + tv.paddingTop,
                l.getLineTop(l.getLineForOffset(pinnedChars.coerceIn(0, l.text.length))), body.paddingTop)
        }
    } else if (pinned >= 0) {
        placeBeforeDraw { pinned.coerceIn(0, mainLimit()) }
    } else if (restore != null) {
        placeBeforeDraw { restore.coerceIn(0, mainLimit()) }
    }

    // ── 下一章开头：异步取，接到正文之后 ──────────────────────
    // 同步取会卡住这一帧（联网最多几秒），所以走与预览同一套后台任务。
    // 取不到或拿到空正文就什么都不加，行为与以前一样（章末需再戳一下）。
    //
    // 这里只做**预热**：把下一章拉进缓存，真正挂视图的是 appendNext。
    // 两边各挂一次就会把同一章追加两遍 —— 预取回调不再自己 addView。
    val nextCh = info.chapters.getOrNull(index + 1)
    if (nextCh != null && !nextCh.locked) {
        bg(curScreen, body = { loadOpening(info, nextCh, src); Unit }) { _ -> }
        // 完成缓存只含数据，不再把全文挂在页面单槽，统一受条数和字符上限约束。
        //
        // 后台预排：把下一章的 shaping 挪到这条线程上，换章首帧只剩断行与布局。
        // 宽度要等这一帧布局完才知道，所以先 post 到下一帧取参数再起线程 ——
        // 控件宽度为 0 时排出来的东西验不出断行，[ReaderPrecompute.build] 会直接拒排。
        val pKey = preKey(info, index + 1)
        val pFp = preFp()
        body.post {
            val spec = preSpec(tv)
            bg(curScreen, body = {
                val rawNext = loadOpening(info, nextCh, src)
                ReaderPrecompute.build(pKey, pFp, shownText(rawNext), spec)
                Unit
            }) { _ -> }
        }
    }
    // 本章不满一屏时先追加一章，否则滚动上限为零，往下滑什么也不会发生。
    body.post { if (scrollLimit(body) <= body.height) appendNext() }

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

    // 视图挂上窗口后再注册首帧落位：脱离窗口时 ViewTreeObserver 是浮动对象，监听得丢。
    pendingPlace?.let { f ->
        pendingPlace = null
        placeBeforeDraw(f)
    }

    // ── 跨章入场 ──────────────────────
    // 新页整层从行进方向滑到位，只改 translationY：不抓位图、不触发 measure，
    // 不会把「卡一下再跳上去」那套搬回来。位移与方向由 [ReaderTransition] 给出。
    val liftCap = Md3.dp(this, ReaderTransition.MAX_LIFT_DP)
    fun slideIn() {
        if (enterDir == ReaderTransition.NONE) return
        // 高度未定时用整屏高：宁可幅度小一点，也不能不动。
        val vp = if (body.height > 0) body.height else frame.height
        val lift = ReaderTransition.liftPx(vp, liftCap).toFloat()
        frame.translationY = enterDir * lift
        frame.animate().translationY(0f)
            .setDuration(ReaderTransition.DURATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
    }
    // 首帧耗时与入场共用一次 PreDraw：动画时钟对齐首帧（排得慢时不会首帧已经滑掉半），
    // 同时把耗时记进 DevLog。
    fun onFirstFrame() {
        com.watchdl.core.DevLog.log("Reader",
            "idx=$index chars=${text.length} dir=$enterDir " +
                "firstFrame=${(System.nanoTime() - enterNs) / 1_000_000}ms")
        slideIn()
    }
    // 脱离窗口时 ViewTreeObserver 是浮动对象，注册上去等于没注册：退回 post。
    if (frame.isAttachedToWindow) {
        val vto = frame.viewTreeObserver
        val once = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (vto.isAlive) vto.removeOnPreDrawListener(this)
                onFirstFrame()
                return true
            }
        }
        vto.addOnPreDrawListener(once)
    } else {
        frame.post { onFirstFrame() }
    }

    // ① 阅读锚点：从目录 / 进度滑条 / 章内查找跳到了别的章，底出一条「返回刚才那章」。
    val anchor = ReaderUi.anchor
    if (anchor != null && anchor.first == readingId(info) && anchor.second != index) {
        pill("back", "返回刚才那章") {
            // 先钉住位置再跳：这一章的滚动位置会被新页覆盖，不先记下就回不到原屏。
            ReaderUi.restoreKey = "${readingId(info)}#${anchor.second}"
            ReaderUi.restoreAt = anchor.third
            preview(info, anchor.second)
        }
    }
    // ② 主题提醒：刚在阅读设置里换了深浅，新页底出「切换到深色 / 浅色模式」。
    if (ReaderUi.themeHint) {
        ReaderUi.themeHint = false
        pill("brightness", if (light) "切换到深色模式" else "切换到浅色模式") {
            // 只换底色，字号行距不动；重建页是因为整套前景色都得跟着换。
            ReaderUi.restoreKey = findKey
            ReaderUi.restoreAt = body.scrollY
            // 留在当前配色族里换亮度：米色主题下切到深色要变「米·夜」，不是夜间中性色。
            // 主题 id 显式传，不让 saveReaderPrefs 从底色反推 —— 反推只能分四档。
            val family = Palette.family(Md3.palette.id)
            val dark = !light
            saveReaderPrefs(readerSize, readerSpacing, ReaderStyle.bgOf(dark, family),
                themeId = Palette.combine(dark, family))
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
internal fun MainActivity.restyleReader(raw: String) {
    val tv = ReaderUi.bodyView ?: return
    val ink = Md3.onSurface
    styleBody(tv, raw)
    // 拼接块里的下一章正文用同一条样式：不同步就会在翻过去时字号突换一档。
    ReaderUi.nextView?.let { styleBody(it, ReaderUi.nextRaw) }
    // 连续滚动追加的后续章节：不跟着重排，改完字号再往下滑就是两种版式。
    for ((v, raw) in ReaderUi.appended) styleBody(v, raw)
    // 埋点：改设置时重排了几个追加块。为 0 就说明 restyle 够不到它们。
    com.watchdl.core.DevLog.log("Reader", "restyle size=$readerSize appended=${ReaderUi.appended.size}")
    restyleTitle(ink)
}

/**
 * 正文样式：字号 / 行距 / 字色 / 对齐 / 字重斜体 / 字距 / 段距。
 *
 * 正文 tv 与拼接块里的下一章正文都走这一条 —— 两块版式相同，改设置时才能一起变。
 * [raw] 必须是**原始文本**（未缩进、未挂 span）。
 */
internal fun MainActivity.styleBody(tv: TextView, raw: String) {
    val ink = Md3.onSurface
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

/**
 * 窗口块的章节标题样式。
 *
 * 与主标题唯一的差别是上下留白：块与块之间要空开 18 dp，主标题顶上不留。
 * 字号 / 字重 / 是否隐藏走同一套设置，改设置时整窗一起变。
 */
internal fun MainActivity.styleBlockHead(v: TextView) {
    v.visibility = if (ReaderUi.titleMode == 2) View.GONE else View.VISIBLE
    if (ReaderUi.titleMode == 2) return
    v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, readerSize + ReaderUi.titleAdd)
    v.setTextColor(Md3.onSurface)
    v.setTypeface(
        android.graphics.Typeface.SANS_SERIF,
        if (ReaderUi.titleBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    v.setPadding(0, Md3.dp(this, 18), 0, Md3.dp(this, 2))
}

/** 标题样式。位置 / 字号 / 字重 / 下间距四项都跟着设置变。 */
private fun MainActivity.restyleTitle(ink: Int) {
    val t = ReaderUi.titleView ?: return
    val hide = ReaderUi.titleMode == 2
    // 拼接块的下一章标题也走这一套：它就是一个章标题，样式不能比正文标题差。
    for (v in listOfNotNull(t, ReaderUi.nextTitleView) + ReaderUi.heads) {
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
    if (text.isEmpty()) return
    val extra = paraExtraPx(tv)
    if (extra <= 0) return
    tv.setText(ReaderPrecompute.paraSpanned(text, extra), TextView.BufferType.SPANNABLE)
}

/**
 * 段距额外像素。
 *
 * 与后台预排共用一条公式：两处算出来的值差 1 px，预排件的行高就与真控件不同，
 * 段距会从「设置生效」变成「时有时无」。
 */
private fun MainActivity.paraExtraPx(tv: TextView): Int {
    val metrics = resources.displayMetrics
    val sizeSp = tv.textSize / metrics.scaledDensity
    return ReaderStyle.paraExtraPx(ReaderUi.paraLevel, sizeSp, readerSpacing, metrics.density)
}

/**
 * 预排件缓存键：一本书的一章。
 *
 * 带 itemId：换源或重拉目录后同一个章号指向的可能是另一章，光靠章号会命中错件。
 */
private fun preKey(info: BookInfo, index: Int): String =
    "${info.bookId}#$index#${info.chapters.getOrNull(index)?.itemId ?: ""}"

/** 后台预排的输入：控件上的样式已是最终值，档位取当前设置。 */
private fun MainActivity.preSpec(tv: TextView): ReaderPrecompute.Spec =
    ReaderPrecompute.capture(tv, Md3.dp(this, 2), readerSpacing, paraExtraPx(tv), ReaderUi.justify)

/** 参数指纹。段距与对齐不在 [android.text.PrecomputedText.Params] 里，单独进串。 */
private fun MainActivity.preFp(): String = PrecomputeKey.of(
    readerSize, readerSpacing, ReaderUi.letterEm, ReaderUi.weightLevel,
    ReaderUi.italic, ReaderUi.indentLevel, ReaderUi.paraLevel, ReaderUi.justify)

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
        Md3.ellipsize(Md3.text(this, "更多操作", Md3.FS_XL, Md3.onSurface, bold = true))
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
    // 本地书没有第二个源，换源这一行不画：画出来也只是点一下弹一句话。
    if (!LocalImport.isLocal(info.bookId)) {
        row("换源", "swap") {
            ReaderUi.swapReturnTitle = info.chapters.getOrNull(index)?.title.orEmpty()
            ReaderUi.swapReturnAt = readerBody?.scrollY ?: 0
            rememberReturn(); showSwap(info, src)
        }
    }
    // legado 顶栏的刷新：重跑一次取正文，复用 preview 的本地缓存 + 在线兜底。
    row("刷新本章", "refresh") { preview(info, index) }
    row("离线缓存", "download") { startDownload(info) }
    row(if (shelfStore().contains(info)) "移出书架" else "放入书架", "bookmark") {
        runCatching {
            if (shelfStore().contains(info)) {
                shelfStore().remove(info)
                toast("已移出书架，历史与缓存保留")
            } else {
                shelfStore().add(info, srcName(src ?: sourceForBook(info)))
                toast("已加入书架，不会自动下载")
            }
        }.onFailure { toast("收藏保存失败：${it.message}") }
    }
    row("书籍详情", "file") { rememberReturn(); showDetail(info, src ?: sourceForBook(info)) }
    row("目录", "menu") { markAnchor(info, index); showToc(info, src) }
    row("章内查找", "search") { showFind(info, index, text, src) }
    row("替换净化", "edit") { rememberReturn(); showReplace() }
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
    row("阅读记录", "history") { rememberReturn(); showRecords() }
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
internal fun MainActivity.showToolsDialog(
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

/** 预取正文同时绑定逻辑身份，避免不同来源的相同地址复用另一书的正文。 */
private fun openingKey(info: BookInfo, ch: ChapterRef, src: BookSource?): ChapterTextKey =
    ChapterTextKey(src, info.bookId, info.name, info.author, ch.itemId, ch.title)

/**
 * 取一章正文（拼接块用），与 [preview] 同一条路。
 *
 * 先读本地缓存，缺章再联网。预取和主动加载共享正在执行的任务，
 * 异常交由调用方处理，失败不缓存空正文。
 * 注意这是阻塞调用，必须在 [bg] 的线程里跑。
 */
private fun MainActivity.loadOpening(
    info: BookInfo,
    ch: ChapterRef,
    src: BookSource?,
): String {
    val key = openingKey(info, ch, src)
    ReaderUi.completed.get(key)?.let { return applyChapterRules(info, it) }
    val task = synchronized(ReaderUi.requests) {
        ReaderUi.requests[key] ?: java.util.concurrent.FutureTask<String> {
            // 取得请求锁后再查一次，避免刚完成的预取与主动打开重复读取。
            ReaderUi.completed.get(key) ?: readChapterText(info, ch, src).also {
                ReaderUi.completed.put(key, it)
            }
        }.also { ReaderUi.requests[key] = it }
    }
    try {
        task.run() // FutureTask 只执行一次；其他调用等待同一结果。
        return applyChapterRules(info, task.get())
    } finally {
        synchronized(ReaderUi.requests) {
            if (ReaderUi.requests[key] === task && task.isDone) ReaderUi.requests.remove(key)
        }
    }
}

private fun MainActivity.readChapterText(info: BookInfo, ch: ChapterRef, src: BookSource?): String {
    val line = if (matchingBookJob(info) != null) jobsStore().readChapter(info.bookId, ch.itemId) else null
    val localHtml = line?.let { TinyJson.path(it, "content") }
    // 本地书缺章不联网：返回空串，拼接块自然跳过。
    val html = localHtml ?: if (LocalImport.isLocal(info.bookId)) error("这一章没有正文，导入时丢了")
                          else (src ?: sourceForBook(info) ?: error("原书源不可用，且本章没有缓存，请换源")).content(ch.itemId, info.bookId)
    return bodyText(html, LocalImport.isLocal(info.bookId))
}

private fun applyChapterRules(info: BookInfo, raw: String): String =
    ReplaceRuleStore.apply(raw, isTitle = false, bookId = info.bookId)

/**
 * 正文的纯文本化。
 *
 * 本地 txt 是**纯文本**，不能过 [Downloader.htmlToText]：它会把 `<` `>` 当标签删掉、
 * 把 `&amp;` 还成 `&`、每段强加全角缩进（叠加「缩进」设置变四字宽）。
 */
private fun bodyText(s: String, isLocal: Boolean): String = if (isLocal) {
    s.replace("\r\n", "\n").replace('\r', '\n').trim()
} else {
    Downloader.htmlToText(s).ifEmpty { s.replace(Regex("<[^>]+>"), "\n").trim() }
}

/**
 * 滚动内容里能滚多远。
 *
 * ScrollView 的滚动范围是「子控件高 + 上下内边距 − 视口高」，
 * clipToPadding 只改变绘制裁剪，不改变滚动范围。
 */
private fun scrollLimit(body: ScrollView): Int {    val child = body.getChildAt(0) ?: return 0
    return (child.height - body.height + body.paddingTop + body.paddingBottom).coerceAtLeast(0)
}

/**
 * 一段已排版的文字里最后一行的行顶。未排版（[android.text.Layout] 为空）时由调用方处理。
 *
 * 拼接块往回退时要落回本章末屏：直接拿最后一行的行顶，比“当前视口减一屏”稳 ——
 * 后者会被本章正文比拼接块短这一事实带偏。
 */
private fun android.text.Layout.lastLineTop(): Int =
    getLineTop((lineCount - 1).coerceAtLeast(0))

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
private fun MainActivity.scrollToHit(hit: ReaderFind.Hit, tries: Int = 0) {
    val body = readerBody ?: return
    // 直接拿正文控件：正文前面还排了标题，取「第一个子控件的第一个子控件」会拿到标题。
    val tv = ReaderUi.bodyView ?: return
    if (tv.length() == 0) return
    val layout = tv.layout
    if (layout == null) {
        // 刚换章时 Layout 还没生成，等一帧再来。
        // 但 view 脱窗或找不到测量时机时 layout 会一直为 null，
        // 无上限 post 就是把消息队列排满 —— 6 帧（约 100 ms）足够排版完成。
        if (tries >= 6) return
        body.post { scrollToHit(hit, tries + 1) }
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
    // 回读：跟顺读同一个落点规则，只是方向相反。
    if (info.chapters[prev].locked) {
        val free = (prev downTo 0).firstOrNull { !info.chapters[it].locked }
        if (free == null) { toast("前面都是付费章"); return }
        preview(info, free, atEnd)
    } else preview(info, prev, atEnd)
}
/** 从记录的位置续读：章号 + 章内节号。[atEnd] 为真时直接落在章末。 */
internal fun MainActivity.preview(
    info: BookInfo,
    index: Int,
    atEnd: Boolean = false,
    // 同书页面直接复用来源，冷启动来源解析放在下方工作线程。
    src: BookSource? = when (val screen = curScreen) {
        is Screen.Toc -> screen.src.takeIf { screen.info == info }
        is Screen.Reader -> screen.src.takeIf { screen.info == info }
        is Screen.Detail -> screen.src.takeIf { screen.info == info }
        else -> null
    },
) {
    val ch = info.chapters.getOrNull(index) ?: return
    // 等待期间保留旧页回调；失败后旧页仍可操作。
    // 换章不清屏（legado 是旧页一直画着、新页就绪后当帧换窗口，
    // `feature/reader/ReaderCanvasSurface.kt:607-636` 的 pendingPageWindow）：
    // 已在阅读页时旧正文继续显着，正文取到后同帧交给 showReader 替换。
    // 先铺一页「正在取正文…」再整屏重建，就是每换一章闪两下的根源。
    // 书架进来的那一跳，屏上已经有「正在打开书籍…」占位页（[readShelfBook] 刚建好），
    // 这里再建一页就是白丢一屏重活；[ReaderUi.holdPlaceholder] 由那边置位。
    val held = ReaderUi.holdPlaceholder
    ReaderUi.holdPlaceholder = false
    if (curScreen !is Screen.Reader && !held) {
        root.removeAllViews()
        root.addView(Widgets.topBar(this, ch.title, onBack = { showToc(info, src) }))
        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        (Widgets.contentColumn(body)).addView(Widgets.emptyState(this, "refresh", "正在取正文…"))
        root.addView(body)
    }

    // 读者停在 Toc / Reader 屏上点章节，回来时应该还在原来的屏。
    val owner: Screen = curScreen
    // 连点两下就会有两个线程在跑，先发的可能后回来盖掉新章。
    val token = ++ReaderUi.pageToken
    ReaderUi.pendingToken = token
    val previous = owner as? Screen.Reader
    // 只有同一本书、章号真的变了才给入场动画。首次进阅读、从目录跳章时
    // previous 为 null，没有「从哪儿读过来」的空间关系，硬加位移只是闪。
    ReaderUi.enterDir = ReaderTransition.dirFor(
        prevIndex = previous?.takeIf { it.info == info }?.index, index = index)
    val previousOffset = readerBody?.scrollY ?: 0
    scrollSave?.let { readerBody?.removeCallbacks(it) }
    if (previous != null) {
        // 章号跟游标走：拼接区里 previous.index 还是首章，拿它配整屏 scrollY 会存到错章。
        val pos = readerPos?.invoke()
        saveOffset(previous.info, pos?.first ?: previous.index, pos?.second ?: previousOffset)
    }

    fun clearRequest() {
        if (ReaderUi.pendingToken == token) ReaderUi.pendingToken = null
    }
    fun failed(error: Throwable) {
        clearRequest()
        ReaderUi.enterDir = ReaderTransition.NONE
        ReaderUi.restoreKey = ""
        ReaderUi.restoreAt = -1
        ReaderUi.restoreChars = -1
        ReaderUi.restoreDelta = 0
        toast("正文失败：${error.message}")
        if (previous == null) showToc(info, src)
        else ReaderAuto.reschedule?.invoke()
    }

    // 命中完成缓存也在后台应用替换规则，避免正则处理阻塞上屏。
    thread {
        try {
            val source = src ?: if (LocalImport.isLocal(info.bookId)) null else sourceForBook(info)
            val text = loadOpening(info, ch, source)
            postAlive(owner) {
                if (token != ReaderUi.pageToken) return@postAlive
                clearRequest()
                showReader(info, index, text, source, atEnd)
            }
        } catch (e: Exception) {
            postAlive(owner) {
                if (token != ReaderUi.pageToken) return@postAlive
                failed(e)
            }
        }
    }
}

private fun MainActivity.nextChapter(info: BookInfo, index: Int) {
    // 已经顺着读下去了，「刚才那章」不再有意义，锚点作废。
    ReaderUi.anchor = null
    val next = index + 1
    if (next >= info.chapters.size) { toast("已是最后一章"); return }
    // 顺读：直接换页，不叠旧屏。
    if (info.chapters[next].locked) {
        // 跳过连续的锁定章
        val free = (next until info.chapters.size).firstOrNull { !info.chapters[it].locked }
        if (free == null) { toast("后面都是付费章"); return }
        preview(info, free)
    } else preview(info, next)
}

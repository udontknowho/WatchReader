package com.watchdl

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.watchdl.core.*
import com.watchdl.ui.Md3
import com.watchdl.ui.Widgets
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 主界面。按 `ui/md3-final.html` 的定稿实现（189 × 248 dp）。
 *
 * 全程序化布局：项目走 kotlinc + aapt2 手工流水线，不用 XML 资源。
 * 每一屏的结构与设计稿一一对应。
 */
class MainActivity : Activity() {

    /**
     * 搜索用的源集合：番茄 + 本地导入的全部 legado 书源。
     *
     * 以前这里只有 [FanqieApp]，本地导入的 54 条书源只被统计数量、
     * **从未参与过搜索**（[SourceEngine] 全工程零调用）。
     * 现在改成多源聚合：只取前 [MAX_SOURCES] 个、并发 [CONCURRENCY]、
     * 总超时 15 秒、失败静默计数。
     *
     * 懒建：首次搜索时才读书源文件并注册设备（都要 IO/联网）。
     * **可作废**：启动同步云端书源后要重读（见 [rebuildSources]），
     * 所以不能写成 `by lazy` —— 那样云端更新完还拿旧列表，等于白拉。
     */
    private var multiCache: MultiSource? = null
    private val multi: MultiSource get() = multiCache ?: buildMulti().also { multiCache = it }

    private fun buildMulti(): MultiSource {
        DeviceRegister.init(File(filesDir, "fanqie"))
        val list = mutableListOf<MultiSource.NamedSource>()
        runCatching { list += MultiSource.NamedSource("番茄", FanqieApp(DeviceRegister.get())) }
        val dir = File(filesDir, "sources").apply { mkdirs() }
        // 按文件名排序：`listFiles()` 的顺序由文件系统决定，不保证稳定。
        // 下面 `take(MAX_SOURCES)` 取的是列表前几个，所以**文件顺序就是优先级**，
        // 不排序的话每次安装跑的可能都不是同一批源。
        // 先收集规则，再统一分配显示名：重名要加 host 后缀，
        // 否则 pickFor 只按名字找第一条，选 B 站的书会交给 A 站引擎取目录（N7，源库 88 组重名）。
        val pending = mutableListOf<Triple<String, com.watchdl.core.BookSourceRule, String>>()
        sourceFiles(dir).forEach { f ->
            runCatching {
                val text = f.readText()
                if (text.contains("bookSourceName")) {
                    LegadoImport.importAll(text).forEach { im ->
                        im.rule?.let { pending += Triple(im.name, it, it.host) }
                    }
                }
            }.onFailure { DevLog.log("WatchDL", "载入书源 ${f.name} 失败：${it.message}") }
        }
        val nameCount = pending.groupingBy { MultiSource.tidyName(it.first) }.eachCount()
        val seen = mutableSetOf<String>()
        for ((name, rule, host) in pending) {
            val bare = MultiSource.tidyName(name)
            val disambiguate = (nameCount[bare] ?: 0) > 1
            val shown = if (disambiguate) {
                val shortHost = host.removePrefix("https://").removePrefix("http://").trimEnd('/')
                "$name @$shortHost"
            } else name
            if (!seen.add(MultiSource.tidyName(shown))) continue   // 同一 host 重复注册，留第一条
            list += MultiSource.NamedSource(shown, LegadoSource.of(rule), host)
        }
        // 只搜前 MAX_SOURCES 个源。
        //
        // 以前把 80+ 个源全发出去，并发 5、总超时 15 秒 —— 排在后面的源根本
        // 轮不到就被超时取消，实测 81 个源里 59 个报「失败」，失败率 73%，
        // 而它们大多不是真的挂了，只是**没来得及跑**。
        //
        // 注意：被剔除的源**仍留在池子里**（下面 pickFor 要用），只是不参与搜索。
        // 否则从搜索结果里点进去时找不到源，只能退回番茄——那就会取不到目录。
        val pooled = list.drop(MAX_SOURCES)
        if (pooled.isNotEmpty()) {
            DevLog.log("WatchDL", "源共 ${list.size} 个，搜索用前 $MAX_SOURCES 个，其余 ${pooled.size} 个仅备用")
        }
        DevLog.log("WatchDL", "多源搜索就绪：${minOf(list.size, MAX_SOURCES)} 个源（并发 $CONCURRENCY）")
        return MultiSource(list.take(MAX_SOURCES), pool = list, concurrency = CONCURRENCY)
    }

    /** 兼容旧调用点：单源接口取第一个（番茄）。真正搜索走 [multi]。 */
    private val source: BookSource by lazy {
        DeviceRegister.init(File(filesDir, "fanqie"))
        FanqieApp(DeviceRegister.get())
    }
    private lateinit var root: LinearLayout
    private lateinit var input: EditText

    /** 当前屏。手表一次只显示一屏内容。 */
    private sealed interface Screen {
        object Home : Screen
        data class Hits(
            val keyword: String,
            val hits: List<BookHit>,
            /** 本轮从第几个源开始搜的（换一批源）。 */
            val offset: Int = 0,
            /** 本轮跑了几条源，还没跑的源为换源留钩子。 */
            val searchedSources: Int = 0,
        ) : Screen
        data class Toc(val info: BookInfo, val src: BookSource? = null) : Screen
        data class Reader(
            val info: BookInfo,
            val index: Int,
            val text: String,
            val src: BookSource? = null,
        ) : Screen
        object Sources : Screen
        /** 设置页。目前只有阅读显示与导出目录两块，都是本地项。 */
        object Settings : Screen
        /**
         * 发现页（榜单）。
         *
         * `key` 标识当前看的是哪个榜，`hits` 是已拉到的条目（空表示还在拉）。
         * 只带榜单代号与性别，重进这一屏时不缓存数据 —— 榜单本来就要新数据。
         */
        data class Discover(
            /** 站点：`fq` / `qd`。 */
            val site: String = "fq",
            val gender: String = "male",
            /** 选中的榜；空串表示还没选，只显示选择器。 */
            val rankKey: String = "",
            /** 这个榜拉到的条目。空表示正在拉或没选。 */
            val hits: List<BookHit> = emptyList(),
            /** 取数失败原因；空串表示没失败。 */
            val error: String = "",
        ) : Screen
        /** 书源市场（yiove）。`hits` 为拉到的列表，`page` 为已拉到的页码。 */
        data class Market(val hits: List<YioveSource.Item>, val total: Int, val page: Int) : Screen
        data class Progress(val info: BookInfo) : Screen
    }

    /**
     * 屏幕的**业务身份**：同一屏在不同时刻数据不同，但身份不变。
     *
     * 后台回调只能拿它比对，不能比整个 [Screen] —— `data class` 的 `equals`
     * 连数据一起比，而回调发起时手里的往往是「还没数据」的空白版，
     * 和屏上带着结果的那个永远不相等，回调就全被丢掉了。
     */
    private fun screenId(s: Screen?): String = when (s) {
        null -> "?"
        Screen.Home -> "home"
        Screen.Sources -> "sources"
        Screen.Settings -> "settings"
        is Screen.Hits -> "hits:${s.keyword}"
        // 目录屏身份用**书名**而不是 bookId：loadToc 时 bookId 还不确知（可能试多个源，
        // 每条源返回的 id 不同），拿 bookId 做身份会让回调认不出自己。
        is Screen.Toc -> "toc:${s.info.name}${s.info.author}"
        is Screen.Reader -> "reader:${s.info.bookId}"
        is Screen.Progress -> "progress:${s.info.bookId}"
        is Screen.Discover -> "discover:${s.site}:${s.gender}:${s.rankKey}"
        is Screen.Market -> "market:${s.page}"
    }

    private var screen: Screen = Screen.Home

    /** 切屏统一入口。换屏就意味着上一屏的后台任务全部作废。 */
    private fun setScreen(next: Screen) {
        if (next === screen || next == screen) return
        cancelTasks()
        screen = next
    }
    private var lastJob: BookJob? = null

    // ══════════════════ 后台任务的取消 ══════════════════
    //
    // 13 处 `thread {}` 原来全把 Thread 丢掉，无从取消：用户在搜索等的当口按返回，
    // 十几秒后结果回来照样 `showHits(...)` —— 页面自己跳回去，比崩溃还难理解；
    // 换源重试更狠，回调里递归调 loadToc，人走了还在把候选源一个个试完。
    //
    // 现在统一走 [bg]：任务登记在 [bgTasks]，屏一变就全部 cancel；
    // 回调走 [postAlive]，进主线程前再查一次「当初起它的那屏还是当前屏吗」。
    //
    // 两道关缺一不可：只 cancel 挡不住已经排进消息队列的那条；
    // 只查屏则线程还在空转（联网请求不会响应 interrupt，靠 Http 自身的超时收尾）。

    /** 起了还没结束的后台任务。切屏时全清。 */
    private val bgTasks = java.util.Collections.synchronizedSet(mutableSetOf<Thread>())

    /**
     * 起一个跟着当前屏走的后台任务。
     *
     * [owner] 是「谁的结果」：回调时拿它跟当前 [screen] 比，不是同一屏就丢弃。
     * 收一个 `(T) -> Unit` 而不是在内部调 `runOnUiThread`，是因为各个调用点
     * 要处理的返回值不一样（有的带游标，有的是 Result，有的只看异常）。
     */
    private fun <T> bg(owner: Screen, body: () -> T, then: (Result<T>) -> Unit) {
        val ownerId = screenId(owner)
        val t = thread(start = true, isDaemon = true) {
            try {
                val r = runCatching(body)
                // 线程可能已经被切屏 cancel 掉：那就连回调都别排。
                if (Thread.currentThread().isInterrupted) return@thread
                postAlive(ownerId) { then(r) }
            } finally {
                // 自己摘掉，免得集合无限长，也免得再起一个守护线程去 join。
                bgTasks.remove(Thread.currentThread())
            }
        }
        bgTasks.add(t)
    }

    /**
     * 回主线程执行，**但只在当初起它的那屏仍是当前屏时才执行**。
     *
     * [ownerId] 用 [screenId] 算，只比业务身份（书名、书 id、榜类），
     * 不比数据 —— 否则「还没结果」的回调永远等不到自己的结果屏。
     */
    private fun postAlive(ownerId: String, body: () -> Unit) {
        runOnUiThread {
            if (isGone()) return@runOnUiThread
            if (screenId(screen) != ownerId) return@runOnUiThread
            body()
        }
    }

    /** 重载：调用方手里只有屏对象时用它，内部转成 [screenId]。 */
    private fun postAlive(owner: Screen, body: () -> Unit) = postAlive(screenId(owner), body)

    /** Activity 已经不适合再碰界面了。 */
    private fun isGone(): Boolean = isFinishing || isDestroyed

    /** 切屏：把上一个屏起的所有任务停掉。由 [setScreen] 统一调。 */
    private fun cancelTasks() {
        synchronized(bgTasks) {
            bgTasks.forEach { runCatching { it.interrupt() } }
            bgTasks.clear()
        }
    }

    override fun onDestroy() {
        // onPause 之后可能直接被杀，这里再兜一次。
        saveReaderPosNow()
        cancelTasks()
        super.onDestroy()
    }

    /**
     * 目录页当前用的源。
     *
     * 从阅读页/下载页返回目录时不会再传 src，而原来的兜底是
     * `(screen as? Screen.Toc)?.src` —— 那时 screen 已经不是 Toc，
     * 于是源变 null，默默退回默认的番茄源，等于换个源进去、返回一次就串源。
     * 现在用一个固定字段记住，只有不传 src 时才用它。
     */
    private var tocSrc: BookSource? = null

    /**
     * 上一屏，用于「原路返回」。
     *
     * 搜索结果 → 目录 → 下载，按返回应该一层层退回去，
     * 而不是一路跳回首页。
     *
     * 以前只有一个变量，只能记一层：搜索→目录→阅读→设置，从设置退到目录时
     * 就把记录用掉了，再按返回直接回首页，跳过搜索结果。现在是栈。
     */
    private val returnStack = ArrayDeque<Screen>()

    /** 记下当前屏，供返回时重放。只在向前跳转前调。 */
    private fun rememberReturn() {
        // 同一屏不要重复压：重复调用会让返回要按两次才动。
        if (returnStack.lastOrNull() != screen) returnStack.addLast(screen)
        while (returnStack.size > 8) returnStack.removeFirst()
    }

    /** 回到进来的那一屏；没有记录就回首页。 */
    private fun goBackToPrevious() {
        replay(returnStack.removeLastOrNull())
    }

    /** 重放一屏（不重新联网）。记录里的屏都自带重绘所需的数据。 */
    private fun replay(s: Screen?) {
        when (s) {
            is Screen.Hits -> showHits(s.keyword, s.hits, offset = s.offset, batch = s.searchedSources, replay = true)
            is Screen.Toc -> showToc(s.info, s.src)
            is Screen.Reader -> showReader(s.info, s.index, s.text, s.src)
            is Screen.Market -> showMarket(s.page, s.hits)
            is Screen.Sources -> showSources()
            is Screen.Progress -> showProgress(s.info)
            // 发现页返回时**清空旧数据**重新拉：推荐流是时效内容，
            // 隔几分钟回来还显示旧列表，用户看不出它已过期。
            is Screen.Discover -> showDiscover(s.site, s.gender, s.rankKey)
            is Screen.Settings -> showSettings()
            else -> showHome()
        }
    }
    private var downloading = false
    private var progressDone = 0
    private var progressFailed = 0
    private var progressStartedAt = 0L
    private var readerSize = Md3.FS_READ
    private var readerSpacing = 1.75f
    private var readerBackground = Color.rgb(20, 18, 24)

    /** 当前阅读页的滚动视图与位置。onPause 时要靠它们落盘。 */
    private var readerBody: android.widget.ScrollView? = null
    private var readerBookId = ""
    private var readerIndex = -1
    private var readerOffset = 0
    private var scrollSave: Runnable? = null

    /** 表冠灵敏度：每格位移（px）。表冠一格系统只给 1 个单位，必须放大才看得出。 */
    private var crownStep = 60f

    /** 表冠跟手时长（ms）。越短越跟手，太短会抖。 */
    private var crownGlide = 50

    /** 阅读设置落盘。以前改完只活在内存里，杀进程就全丢。 */
    private fun loadReaderPrefs() {
        val p = getSharedPreferences("reader", MODE_PRIVATE)
        readerSize = p.getFloat("size", Md3.FS_READ)
        readerSpacing = p.getFloat("spacing", 1.75f)
        readerBackground = p.getInt("bg", Color.rgb(20, 18, 24))
        crownStep = p.getFloat("crownStep", 60f)
        crownGlide = p.getInt("crownGlide", 50)
    }

    /**
     * 上次读到第几章（下标）。跟着最后一次阅读走，回看会退回去。
     * 0 表示没记录过（和「读到第一章」等价，不影响使用）。
     */
    private fun readProgress(bookId: String): Int {
        val raw = readIndex(bookId)
        if (raw < 0) return 0
        // 旧版分节存档是 `章号 * 1000 + 节号`，而 last_ 是新版才写的。
        // 所以「有 last_」就说明存的已是纯章号，不能再用 1000 去猜大小。
        // 靠数值大小判断会把第 1001 章当成旧值，直接扇回第 1 章。
        val tag = bookId.take(64)
        val prefs = getSharedPreferences("reader", MODE_PRIVATE)
        if (prefs.contains("last_$tag")) return raw
        // 没有 last_ 就是没见过新版代码。先把它标上，下次不再走这条路。
        val fixed = if (raw >= SEG_BASE) raw / SEG_BASE else raw
        prefs.edit().putInt("pos_$tag", fixed).putLong("last_$tag", 0L).apply()
        return fixed
    }

    /** 原始存档章号。-1 = 没记录过，用来区分「没读过」和「读到第 0 章」。 */
    private fun readIndex(bookId: String): Int =
        getSharedPreferences("reader", MODE_PRIVATE).getInt("pos_" + bookId.take(64), -1)

    /** 最近一次把这本书翻开的时刻。0 = 没记录过。 */
    private fun readTime(bookId: String): Long =
        getSharedPreferences("reader", MODE_PRIVATE).getLong("last_" + bookId.take(64), 0L)

    private fun saveProgress(bookId: String, index: Int) {
        if (bookId.isBlank() || index < 0) return
        val prefs = getSharedPreferences("reader", MODE_PRIVATE)
        val tag = bookId.take(64)
        val prevIndex = prefs.getInt("oidx_$tag", -1)
        val e = prefs.edit().putInt("oidx_$tag", index)
            .putLong("last_$tag", System.currentTimeMillis())
        // 无条件写：和 legado 一致，进度就是最后一次停下的地方，回看会退回去。
        // 旧值不先解包：搬运工作由 readProgress 负责，存进来的永远是纯章号。
        e.putInt("pos_$tag", index)
        // 换章时滚动偏移作废，否则沿用上一章的像素位置，进新章可能直接顶到底。
        if (prevIndex != index) e.putInt("off_$tag", 0)
        // commit 而非 apply：进程被杀时 apply 的异步写入可能还没落盘。
        e.commit()
    }

    /** 历史包袱：分节版本用过 `章号 * 1000 + 节号`，只用来识别并还原旧值。 */
    private val SEG_BASE = 1000

    /** 上次读到本章的第几个像素。换章时清零。 */
    private fun readOffset(bookId: String): Int =
        getSharedPreferences("reader", MODE_PRIVATE).getInt("off_" + bookId.take(64), 0)

    private fun saveOffset(bookId: String, index: Int, offset: Int, sync: Boolean = false) {
        if (bookId.isBlank() || index < 0) return
        val prefs = getSharedPreferences("reader", MODE_PRIVATE)
        val kIndex = "oidx_" + bookId.take(64)
        // 偏移只对当时那一章有意义。章号对不上就丢弃，免得上一次读到第 3000 像素，
        // 换个更短的章一进去就顶到底。
        if (prefs.getInt(kIndex, -1) != index) return
        val e = prefs.edit().putInt("off_" + bookId.take(64), offset.coerceAtLeast(0))
        if (sync) e.commit() else e.apply()
    }

    /** 立刻把当前阅读位置写盘。切后台、息屏、进程被杀前都走这里。 */
    private fun saveReaderPosNow() {
        val body = readerBody ?: return
        if (readerBookId.isBlank() || readerIndex < 0) return
        scrollSave?.let { body.removeCallbacks(it) }
        // 章号也再写一次：翻开后原地滚动的会话不会触发 saveProgress，
        // 而首页按 last_ 排序，不刷新就一直显示旧时间。
        saveProgress(readerBookId, readerIndex)
        // 记住的是「屏幕上那行」的位置，不是上次防抖触发的旧值。
        saveOffset(readerBookId, readerIndex, body.scrollY, sync = true)
    }

    private fun saveReaderPrefs(size: Float, spacing: Float, bg: Int) {
        readerSize = size
        readerSpacing = spacing
        readerBackground = bg
        getSharedPreferences("reader", MODE_PRIVATE).edit()
            .putFloat("size", size).putFloat("spacing", spacing).putInt("bg", bg).apply()
    }

    private fun saveCrownPrefs() {
        getSharedPreferences("reader", MODE_PRIVATE).edit()
            .putFloat("crownStep", crownStep).putInt("crownGlide", crownGlide).apply()
    }

    /**
     * 下载并发线程数。默认 3，与番茄原来的固定值一致。
     *
     * legado 书源原来写死串行，实测 0.17 章/秒，一本 1600 章要近三小时；
     * 瓶颈是单章请求延迟，并发直接乘上去。各站限流强度差别很大，所以交给用户选。
     */
    private fun lanes(): Int =
        getSharedPreferences("reader", MODE_PRIVATE).getInt("lanes", DownloadService.DEFAULT_LANES)

    private fun pickLanes() {
        val cur = lanes()
        val items = listOf(1, 2, 3, 4, 6, 8).map { n ->
            (if (n == cur) "$n 线程（当前）" else "$n 线程") to {
                getSharedPreferences("reader", MODE_PRIVATE).edit().putInt("lanes", n).apply()
                toast("下载线程：$n")
                showSources()
            }
        }
        Widgets.menuDialog(this, "下载线程数（下一批生效）", items).show()
    }
    private val exportLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 尺寸按屏幕自适应。手表保持紧凑档，手机放大。
        Md3.configure(this)
        loadReaderPrefs()
        DevLog.file = File(filesDir, "dev.log")
        DevLog.setEnabled(getSharedPreferences("reader", MODE_PRIVATE).getBoolean("devlog", false))
        root = Widgets.screenRoot(this)
        setContentView(root)
        cleanDirtyJobs()
        showHome()
        syncSourcesOnStart()
    }

    /**
     * 启动时静默同步云端书源。
     *
     * 以前只有书源页里点「云端更新」才会拉，于是云端改了库、用户不点就永远停在旧版：
     * 实测设备上 `.ver` 卡在 1（84 条那版），后面推到 v4（42 条）一条都没下去。
     *
     * 三个约束：
     * - **不阻塞**：丢到后台线程，拿不到就用本地已有的。
     * - **不打扰**：成功只在有变化时 toast 一句，失败完全静默（离线、镜像全挂是常态）。
     * - **不降级**：[CloudSource.update] 自带的版本比对会跳过旧版。
     */
    private fun syncSourcesOnStart() {
        // 已有源在跑就不用等它；同步完成后再刷新 multi。
        thread {
            val r = runCatching { CloudSource.update(sourcesDir()) }.getOrNull() ?: return@thread
            if (!r.ok) {
                DevLog.log("WatchDL", "启动同步书源未更新：${r.message}")
                return@thread
            }
            // 库变了，multi 是 lazy 且已经缓存了旧列表 —— 必须作废重建成新的。
            runOnUiThread {
                // 不在首页就不弹：启动那几秒同步常落在「发现」屏上，一句 toast
                // 盖在空白列表上，看着就是「点进去没东西」的一部分。
                rebuildSources { if (screen is Screen.Home) toast(r.message) }
            }
        }
    }

    /** 把 multi 缓存作废并重读源目录。必须先重建才能拿到新库。 */
    private fun rebuildSources(then: () -> Unit = {}) {
        multiCache = buildMulti()
        then()
    }

    // ══════════════════ 屏幕 ══════════════════

    /** ① 首页：搜索框 + 最近下载。 */
    private fun showHome() {
        setScreen(Screen.Home)
        root.removeAllViews()
        // 主屏内容自明，但顶栏完全没字时用户看不出这是什么应用（测试报告 P1-6）。
        root.addView(Widgets.topBar(this, "小说下载器", onBack = null, backGlyph = "",
            trailing = listOf("⚙" to { rememberReturn(); showSettings() })))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)

        // 搜索框与按钮并成一行：分两行时按钮独自吃掉 46dp（约五分之一屏高），
        // 而两者做的是同一件事（开输入框）。
        val searchRow = Md3.row(this)
        val sb = Widgets.searchBar(this, "搜索书名或作者") { askKeyword() }
        searchRow.addView(sb, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.SEARCH), 1f))
        val btn = Widgets.button(this, "搜索", "filled", { askKeyword() })
        searchRow.addView(btn, LinearLayout.LayoutParams(Md3.dp(this, 56), Md3.dp(this, Md3.SEARCH)).apply {
            leftMargin = Md3.dp(this@MainActivity, Md3.GAP)
        })
        searchRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, Md3.SEARCH),
        ).apply { bottomMargin = Md3.dp(this@MainActivity, Md3.GAP * 2) }
        col.addView(searchRow)

        // 发现页入口用文字按钮，不用图标：首屏已经有搜索框和搜索按钮，
        // 再放一个放大镜会让人分不清哪个是「搜书」哪个是「看榜」。
        val discoverBtn = Widgets.button(this, "发现榜单", "tonal") { showDiscover() }
        discoverBtn.contentDescription = "发现榜单"
        discoverBtn.layoutParams = Md3.lpDp(this, null, Md3.BTN).apply {
            bottomMargin = Md3.dp(this@MainActivity, Md3.GAP * 2)
        }
        col.addView(discoverBtn)

        // 首页不放「设置」按钮：用户嫌碍事（2026-10-21）。
        // 字号/行距/背景在阅读页的 ⚙ 里都有，功能不丢；书源页从「发现」旁的入口进。

        // 最近下载
        // 只认真正含 job.json 的目录。
        // 以前只看「是目录」—— 空的、导出的、脏 bookId 留下的都会进列表，
        // 而 restore 全返回 null，于是有分组标题、下面却空空如也。
        // 先按 job.json 里的原始 bookId 取阅读时间，没有阅读记录的才退回目录修改时间。
        // 列表要 restore 才拿得到 bookId，所以这一步提前做，排序才有依据。
        val books = jobsDir().listFiles()
            ?.filter { it.isDirectory && File(it, "job.json").isFile }
            ?.map { dir -> dir to runCatching { File(dir, "job.json").readText() }.getOrNull()
                ?.let { com.watchdl.core.TinyJson.path(it, "bookId") }.orEmpty() }
            ?.sortedByDescending { (dir, id) -> readTime(id).takeIf { it > 0 } ?: dir.lastModified() }
            ?: emptyList()
        // 导出目录挪到 files/export，不再混在作业目录里（见 exportTxt）。
        if (books.isEmpty()) {
            col.addView(Widgets.emptyState(this, "▤", "还没有下载过\n搜索书名即可开始"))
        } else {
            col.addView(Widgets.sectionLabel(this, "最近下载"))
            var shown = 0
            for ((dir, id) in books) {
                if (shown >= 8) break
                // 用 job.json 里的**原始 bookId**，不能用目录名 ——
                // 目录名是 safeDirName 净化过的，再拿去 restore 会被二次净化，对不上。
                if (id.isBlank()) continue
                val job = Downloader.restore(jobsStore(), id) ?: continue
                shown++
                val done = job.count(ChapterStatus.DONE)
                val failed = job.count(ChapterStatus.FAILED)
                // 副标题统一两段：作者 · 进度。字段数固定，列表才能纵向扫读。
                val tail = if (failed > 0) "$done/${job.total} 章 · 失败 $failed"
                           else "$done/${job.total} 章"
                val sub = if (job.author.isBlank()) tail else "${job.author} · $tail"
                val displayName = job.name.ifBlank {
                    "未命名 · ${job.total} 章"
                }
                col.addView(Widgets.listItem(
                    this, displayName, sub, twoLine = true, highlight = false,
                    trailing = "⋮",
                    onTrailingClick = { showJobMenu(job) },
                ) { openBook(job.bookId, job.name.ifBlank { displayName }, job.author) })
            }
        }
        root.addView(body)
    }

    /** 匹配度打分，越小越靠前。 */

    /** ② 搜索结果。 */
    /**
     * 下一批要搜的起点。返回 null 表示源已经轮过一圈了。
     *
     * 只有 pool（含被 MAX_SOURCES 剔除的源）大于本轮用量时，换一批才能真正换出新源；
     * 否则转一圈回到原处，翻页就是白翻。
     */
    private fun nextSourceBatch(h: Screen.Hits): Int? =
        SourceRotation.next(h.offset, h.searchedSources, multi.searchable)

    /**
     * 换一批时把已经看过的条目从新结果里剔掉。
     *
     * 不同批的源会命中同一本书，不去掉的话换一批只能看到“又一张重复的书”。
     * 用书名+作者而不是 bookId —— 同书不同站的 id 完全不一样。
     */
    private var seenHits: MutableSet<String> = mutableSetOf()

    private fun hitKey(h: BookHit) = "${h.name.trim()}\u0001${h.author.trim()}"

    /** 搜索结果页的「换一批源」。 */
    private fun rotateSources(h: Screen.Hits) {
        val next = nextSourceBatch(h)
        if (next == null) {
            toast("源已经轮过一圈了")
        } else {
            seenHits.clear()
            doSearch(h.keyword, next)
        }
    }

    private fun showHits(keyword: String, allHits: List<BookHit>, stat: MultiSource.Result? = null, offset: Int = 0,
                        batch: Int = 0, replay: Boolean = false) {
        // 排序（P2）：各源拼接顺序完全随机，实测 1700+ 条里目标书可能落在
        // 第 900 位。手表小屏只看得到几条，不排等于没有。
        //
        // 两层排序：先按匹配度分档，档内**同名书必须相邻** —— 列表里同一本书
        // 现在按源各占一行，分散在各处就失去了「挑个能用的源」的意义。
        // 重放是「回到已经看过的那一屏」，条目必然都在 seenHits 里；
        // 再过滤一次会把整屏清空，用户看到的是「没有找到」。
        val all = allHits.asSequence()
            .filter { it.name.isNotBlank() }
            .filter { replay || hitKey(it) !in seenHits }
            .sortedWith(compareBy<BookHit> { MatchScore.of(it.name, keyword) }.thenBy { it.name.trim() })
            .toList()
        // 记下来，换一批时不再重复显示。
        if (!replay) all.forEach { seenHits += hitKey(it) }
        // 同一本书多个源只出一张卡：屏上只看得见 3.5 行，四张重复卡会把别的结果挤掉。
        // 点卡片仍是按书名重新搜索，所以合并不会丢掉「换个源」的能力。
        val hits = all.distinctBy { "${it.name.trim()}\u0001${it.author.trim()}" }
        // 同名书共几源：必须在合并前统计，合并后每个名字只剩一条。
        val sourceCount = all.groupingBy { "${it.name.trim()}\u0001${it.author.trim()}" }.eachCount()
        setScreen(Screen.Hits(keyword, hits, offset, stat?.total ?: 0))
        root.removeAllViews()
        root.addView(Widgets.topBar(this, "搜索结果", onBack = { goBackToPrevious() }))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)

        // 多源统计卡：告诉用户跑了几条源、几条没响应。
        // 不加这行的话，用户不知道「搜索结果少」是源少还是源挂了。
        if (stat != null) {
            // 文案长短会决定它是 1 行还是 2 行，下面列表就上下跳 24px（P1-3）。
            // 手表屏 378px 宽，失败源那段很容易溢出—— 限定单行 + 末尾省略，
            // 完整数字本来在下方书源页里看得到。
            val line = Md3.ellipsize(Md3.text(this,
                "找到 ${hits.size} 本",
                Md3.FS_SM, Md3.onSurfaceVariant))
            col.addView(line)
        }

        // 换一批源：**固定在滚动区外**，不跟着列表一起滑走。
        // 放列表顶部的话，一往下滑就看不见了 —— 而那正是想换一批的时候。
        // 重进这一屏时 stat 为 null，改用屏上记的批次大小。
        val batch = stat?.total ?: batch.takeIf { it > 0 }
            ?: (screen as? Screen.Hits)?.searchedSources ?: 0
        val hitsScreen = Screen.Hits(keyword, hits, offset, batch)
        val sourceTotal = multi.searchable
        val canRotate = batch > 0 && offset + batch < sourceTotal
        val remaining = sourceTotal - offset - batch
        // 当前屏的批次状态，换一批后 [showHits] 靠它算下一批。
        setScreen(hitsScreen)

        if (hits.isEmpty()) {
            // N6：15 个源全挂（断网、DNS 挂了）时，说「换个关键词」是错的建议 ——
            // 用户会去改关键词，而问题在网络。responded 为 0 说明一条源都没回话。
            val msg = when {
                stat != null && stat.responded == 0 && stat.total > 0 ->
                    "所有源都没有响应\n检查网络后再试"
                stat != null && stat.responded < stat.total ->
                    "没有找到「$keyword」\n${stat.total - stat.responded} 个源没响应，可换一批"
                else -> "没有找到「$keyword」\n换个关键词试试"
            }
            col.addView(Widgets.emptyState(this, "∅", msg), Md3.lpWeight(1f))
        } else {
            for (h in hits) {
                // 第二行显示作者 + 来源站名。源名放最后，因为它是用户挑源要看的。
                val cleanSource = h.sourceName.takeIf { it.isNotBlank() && !it.contains("{{") && !it.contains("@{") }
                val sub = listOf(h.author.takeIf { it.isNotBlank() }, cleanSource)
                    .filterNotNull().joinToString(" · ")
                // 同一本书有多个源时标出来，否则用户以为是两本不同的书。
                val n = sourceCount[hitKey(h)] ?: 1
                val title = if (n > 1) "${h.name}（$n 源）" else h.name
                col.addView(Widgets.listItem(this, title, sub, twoLine = true, highlight = true) {
                    openHit(h)
                })
            }
        }
        root.addView(body)

        // 换一批源：放在**固定底栏**，列表怎么滑都在。
        if (canRotate) {
            val next = Widgets.button(this, "换一批（还剩 $remaining 条）", "tonal", {
                rotateSources(hitsScreen)
            })
            next.contentDescription = "换一批源，还剩 $remaining 条"
            next.layoutParams = Md3.lpDp(this, null, Md3.BTN).apply {
                topMargin = Md3.dp(this@MainActivity, Md3.GAP / 2)
                bottomMargin = Md3.dp(this@MainActivity, Md3.GAP / 2)
            }
            root.addView(next)
        }
    }

    /** ③ 目录 + 下载。 */
    private fun showToc(info: BookInfo, src: BookSource? = null) {
        // 源跟着屏幕走：预览/下载要从同一个源取正文，否则多源下会串源。
        val useSrc = src ?: tocSrc
        tocSrc = useSrc
        setScreen(Screen.Toc(info, useSrc))
        root.removeAllViews()
        root.addView(Widgets.topBar(this, info.name, onBack = { goBackToPrevious() },
            trailing = listOf("⋮" to { showTocMenu(info) })))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)

        val free = info.chapters.count { !it.locked }
        val job = Downloader.restore(jobsStore(), info.bookId)
        val done = job?.count(ChapterStatus.DONE) ?: 0
        val failed = job?.count(ChapterStatus.FAILED) ?: 0

        // 进度卡片
        val card = Widgets.card(this)
        val tocMeta = listOf(info.author, "${info.chapters.size} 章", "免费 $free")
            .filter { it.isNotBlank() }.joinToString(" · ")
        card.addView(Md3.text(this, tocMeta, Md3.FS_SM, Md3.onSurfaceVariant))
        card.addView(Widgets.progressBar(this, if (info.total() > 0) done.toFloat() / info.total() else 0f))
        card.addView(Md3.text(this, "已下载 $done 章 · 失败 $failed 章", Md3.FS_SM, Md3.onSurfaceVariant))
        // 上次读到哪一章。不记的话，读完关掉 App 再进来只能从第一章重新找 ——
        // 五百章的书这是最痛的一件事。
        val lastRead = readProgress(info.bookId)
        if (lastRead > 0 && lastRead < info.chapters.size && !info.chapters[lastRead].locked) {
            val jump = Widgets.button(this, "继续读：第 ${lastRead + 1} 章", "tonal") {
                preview(info, lastRead)
            }
            card.addView(jump, Md3.lpDp(this, null, Md3.BTN).apply {
                topMargin = Md3.dp(this@MainActivity, Md3.GAP / 2)
            })
        }
        val finished = job?.isFinished == true
        // 后台是否正在跑这本书。磁盘上的 state 靠不住：作业跑着的时候 job.json 一直是
        // RUNNING，restore 会把 RUNNING 改判成 PAUSED，于是下载中的页面误报「已中断」。
        val running = DownloadService.runningBookId == info.bookId
        val hint: String? = when {
            finished || running -> null
            job?.state == JobState.CANCELLED -> "上次下载已取消。点「继续下载」接着跑。"
            job?.state == JobState.PAUSED -> "上次下载已中断（系统回收了后台）。点「继续下载」接着跑。"
            else -> null
        }
        hint?.let { card.addView(Md3.text(this, it, Md3.FS_SM, Md3.error)) }
        val row = Md3.row(this).apply { setPadding(0, Md3.dp(this@MainActivity, 7), 0, 0) }
        // 完成态不再给一个点了只弹 toast 的填充按钮：整屏最显眼的控件必须有动作。
        val go = Widgets.button(this, when {
            finished -> "导出 TXT"
            running -> "下载中…"
            done > 0 -> "继续下载"
            else -> "下载免费章"
        }, "filled") {
            when {
                finished -> exportTxt(info)
                running -> toast("正在下载，进度看通知")
                else -> startDownload(info)
            }
        }
        row.addView(go, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
        val exp = Widgets.button(this, "⤓", "tonal") { exportTxt(info) }
        row.addView(exp, LinearLayout.LayoutParams(Md3.dp(this, Md3.BTN), Md3.dp(this, Md3.BTN)).apply {
            leftMargin = Md3.dp(this@MainActivity, Md3.GAP)
        })
        card.addView(row)
        col.addView(card)

        col.addView(Widgets.sectionLabel(this, "章节"))
        info.chapters.forEachIndexed { i, ch ->
            val st = job?.statuses?.get(i)
            val (mark, color) = when {
                ch.locked -> "锁" to Md3.onSurfaceVariant
                st == ChapterStatus.DONE -> "✓" to Md3.ok
                st == ChapterStatus.FAILED -> "✗" to Md3.error
                else -> null to Md3.onSurfaceVariant
            }
            col.addView(Widgets.chapterItem(this, i + 1, ch.title, mark, color) {
                if (ch.locked) toast("这是付费章，取不到正文") else preview(info, i)
            })
        }
        // 直接滚到上次读到的那章：五百章的书手动滑到第 300 章是不可能的事。
        if (lastRead > 0) {
            val target = (col.getChildAt(col.childCount - info.chapters.size + lastRead)) as? android.view.View
            body.post { target?.let { body.smoothScrollTo(0, it.top) } }
        }
        root.addView(body)
    }

    /** ④ 下载中。 */
    private fun showProgress(info: BookInfo) {
        setScreen(Screen.Progress(info))
        root.removeAllViews()
        root.addView(Widgets.topBar(this, "下载中", onBack = { showToc(info) }))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)

        val total = info.chapters.size
        val card = Widgets.card(this, padDp = 10)
        val num = Md3.text(this, "$progressDone", Md3.FS_XL, Md3.onSurface, bold = true)
        num.text = android.text.SpannableStringBuilder("$progressDone").apply {
            append("  / $total")
        }
        card.addView(num)
        card.addView(Widgets.progressBar(this, if (total > 0) progressDone.toFloat() / total else 0f, 5))
        val elapsed = if (progressStartedAt > 0) ((System.currentTimeMillis() - progressStartedAt) / 1000).coerceAtLeast(1) else 0
        val speed = if (elapsed > 0) " · ${progressDone * 60 / elapsed} 章/分" else ""
        val remain = if (progressDone > 0 && total > progressDone) " · 约 ${(total - progressDone) * elapsed / progressDone} 秒" else ""
        card.addView(Md3.text(this, "已下载 $progressDone 章，失败 $progressFailed 章$speed$remain", Md3.FS_SM, Md3.onSurfaceVariant))
        val currentJob = Downloader.restore(jobsStore(), info.bookId)
        if (currentJob?.failedIndexes?.isNotEmpty() == true) {
            card.addView(Md3.text(this, "失败：" + currentJob.failedIndexes.take(5).joinToString { currentJob.chapters[it].title }, Md3.FS_SM, Md3.error))
        }
        col.addView(card)

        val row = Md3.row(this)
        val cancel = Widgets.button(this, "取消", "filled") {
            DownloadService.cancel(this)
            toast("已请求取消")
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
        val back = Widgets.button(this, "回到目录", "text") { showToc(info) }
        row.addView(back, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
        col.addView(row)

        col.addView(Md3.text(this, "下载在前台服务里跑，锁屏不会断", Md3.FS_SM, Md3.onSurfaceVariant))
        root.addView(body)
    }

    /** ⑤ 书源管理。 */
    private fun showSources() {
        setScreen(Screen.Sources)
        root.removeAllViews()
        root.addView(Widgets.topBar(this, "书源", onBack = { goBackToPrevious() }))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)

        // 副标题原本是「从 yiove 在线拉取，点选即可导入」，单行省略号把它压到卡片边缘（P1-5）。
        col.addView(Widgets.listItem(
            this, "书源市场", "yiove 在线库，点选导入",
            twoLine = true, highlight = false,
            leading = Md3.text(this, "☁", Md3.FS_MD, Md3.primary),
        ) { showMarket(1, emptyList()) })
        // 云端整包更新：改一个 JSON 推上去就不必重新发包。
        col.addView(Widgets.listItem(
            this, "更新书源库", "从云端拉取最新书源包",
            twoLine = true, highlight = false,
            leading = Md3.text(this, "↓", Md3.FS_MD, Md3.primary),
        ) { updateFromCloud() })
        // 并发线程数：通用书源默认串行太慢（实测 0.17 章/秒），交给用户自己定。
        col.addView(Widgets.listItem(
            this, "下载线程", "当前 ${lanes()} 线程 · 越多越易被限流",
            twoLine = true, highlight = false,
            leading = Md3.text(this, "≡", Md3.FS_MD, Md3.primary),
        ) { pickLanes() })
        col.addView(Widgets.sectionLabel(this, "本地书源"))

        val owner: Screen = Screen.Sources
        bg(owner, {
            // 整段必须包 try：这里任何一步抛异常，线程会静默死掉，
            // 界面停在空白状态 —— 看起来就是「书源 0 条」，却没有任何提示。
            runCatching {
                extractAssets()
                buildLocalSourceRows()
            }.getOrElse {
                DevLog.log("WatchDL", "读取本地书源失败：${it.message}")
                null
            }
        }) { res ->
            val data = res.getOrNull()
            if (data == null) {
                col.addView(Widgets.emptyState(this, "⚠", "读取本地书源失败\n请重装或重新导入"))
                root.addView(body)
                return@bg
            }
            val (files, rows, total, usable) = data
            run {
                val card = Widgets.card(this, outlined = true)
                card.addView(Md3.text(this, "可用 $usable / 共 $total 条", Md3.FS_MD, Md3.onSurface))
                // 路径是内部信息（P1-4），只在 debug 包显示 —— 用户拿它无用处。
                if (rows.count { it.mark == "✗" } > 0 && isDebugBuild()) {
                    card.addView(Md3.text(this,
                        "目录 ${files.size} 个文件 · ${srcDirPath}",
                        Md3.FS_SM, Md3.onSurfaceVariant))
                }
                // ── 诊断区：只在真的出问题时才显示 ──
                //
                // 手表屏幕小，这三行（正则自检 / 失败明细 / 导入抛点）是排障用的，
                // 源全可用时显示只会误导："失败 6 条" 看着像故障，实际是
                // "这 6 条需修补且已自动修补"。所以全好就藏，有问题才冒出来。
                // logcat 里始终写全量，需要时仍可读到。
                val badRe = SafeRegex.probe(RegexProbe.ALL) + SafeRegex.probe(RegexProbe.FROM_SOURCES)
                val lenientBad = SafeRegex.probeLenient(
                    badRe.map { it.substringBefore(" | ") to it.substringAfter("正则=") })
                    .filter { it.contains("仍失败") }
                val trace = ImportTrace.last
                val broken = rows.count { it.mark == "✗" }
                DevLog.log("WatchDL",
                    "正则自检：字面 ${RegexProbe.ALL.size} / 书源 ${RegexProbe.FROM_SOURCES.size} / " +
                    "裸编译失败 ${badRe.size}（走后兜底仍失败 ${lenientBad.size}）/ 导入抛点 ${trace ?: "无"}")
                badRe.take(6).forEach { DevLog.log("WatchDL", "裸编译失败 $it") }
                lenientBad.take(6).forEach { DevLog.log("WatchDL", "兜底仍失败 $it") }

                // 只有「有源不可用」或「修补后仍编不过 / 导入抛过异常」才展示
                if (broken > 0 || lenientBad.isNotEmpty() || trace != null) {
                    // 只给结论，不贴正则原文：189dp 宽的屏上那几行只会溢出卡片。
                    if (lenientBad.isNotEmpty()) {
                        card.addView(Md3.text(this,
                            "${lenientBad.size} 条书源已自动修正",
                            Md3.FS_SM, Md3.onSurfaceVariant))
                    }
                    if (trace != null) {
                        card.addView(Md3.text(this, "有源在导入时出错，详情见日志",
                            Md3.FS_SM, Md3.onSurfaceVariant))
                    }
                } else {
                    // 全好：一句结论就够了，不再列数字与明细（那是排障才需要的）
                    card.addView(Md3.text(this, "全部可导入", Md3.FS_SM, Md3.onSurfaceVariant))
                }
                // 按原因归类，比逐条列 780 行有用得多。
                // 全好时上面已经写过「全部可导入」，这里不再重复。
                val byReason = rows.filter { it.mark == "✗" }
                    .groupBy { shortReason(it.note) }
                    .entries.sortedByDescending { it.value.size }
                if (byReason.isNotEmpty()) {
                    card.addView(Md3.text(this,
                        "${rows.count { it.mark == "✗" }} 条暂不可用",
                        Md3.FS_SM, Md3.onSurfaceVariant))
                    byReason.take(3).forEach { (reason, list) ->
                        card.addView(Md3.ellipsize(
                            Md3.text(this, "· ${list.size} 条：$reason", Md3.FS_SM, Md3.onSurfaceVariant)))
                    }
                    if (byReason.size > 3) {
                        card.addView(Md3.text(this, "· 另有 ${byReason.size - 3} 类原因", Md3.FS_SM, Md3.onSurfaceVariant))
                    }
                }
                // 「来源」原来是逐条列内部文件名（2972e4a8_kk笔趣阁_http_…），
                // 对用户没有任何用处。文件数并进卡片一行，列表只留给真实书源。
                card.addView(Md3.text(this, "源文件 ${files.size} 个", Md3.FS_SM, Md3.onSurfaceVariant))
                col.addView(card)

                // 全部列出，可用的排前面。以前只列"可用"的，用户会以为源丢了 ——
                // 54 条里 11 条不可用是正常的，应当看得见、能知道原因。
                val sorted = rows.sortedBy { if (it.mark == "✓") 0 else if (it.mark == "⚠") 1 else 2 }
                col.addView(Widgets.sectionLabel(this, "全部书源（${rows.size}）"))
                sorted.take(400).forEach { r ->
                    val color = when (r.mark) {
                        "✓" -> Md3.ok
                        "⚠" -> Md3.primary
                        else -> Md3.error
                    }
                    // M7：声明明文、且实测 https 也升级不上去的源才标「明文」。
                    // 探测结论来自 [Http.cleartextHosts]：没发过请求的源还不知道，不预标。
                    val plain = r.declaredHttp && r.plainConfirmed
                    val sub = listOfNotNull(
                        r.note?.let { shortReason(it) },
                        if (plain) "明文传输" else null,
                    ).joinToString(" · ").ifEmpty { null }
                    col.addView(Widgets.listItem(
                        this, r.name, sub, twoLine = sub != null,
                        highlight = false,
                        leading = Md3.text(this, r.mark, Md3.FS_SM, color),
                    ))
                }
                if (sorted.size > 400) {
                    col.addView(Md3.text(this, "（还有 ${sorted.size - 400} 条未显示）",
                        Md3.FS_SM, Md3.onSurfaceVariant))
                }
            }
        }
        root.addView(body)
    }

    /**
     * 书源市场。列表只拿元数据（快），点某一条才拉完整规则（约 1 秒），
     * 装上后落盘缓存，下次不再拉。
     *
     * 全站 31606 条 / 2.5 GB，全量拉要 9 小时，所以不做整库导入。
     */
    /**
     * 发现页：选站点 → 选频道 → 选分类 → 看那一个榜。
     *
     * 以前是一条混合流，把几个榜各取一页轮流拼起来 —— 结果「番茄更新」那种新鲜但
     * 难看的书把好榜的份额挤掉了。现在番茄有 26 个分类 × 阅读/新书两榜，起点有 5 个榜，
     * 全并行拉一次就是 52 个请求，所以改成**选中后只拉一个榜**。
     *
     * 番茄分类榜走页面 SSR，一屏 10 本、不可翻页；起点每页 20 本、可翻到第 10 页。
     * 点书名走既有的多源搜索：起点没有对应 [BookSource]，番茄榜单的书也未必能直取目录。
     */
    private fun showDiscover(
        site: String = "fq",
        gender: String = "male",
        rankKey: String = "",
    ) {
        setScreen(Screen.Discover(site, gender, rankKey))
        root.removeAllViews()
        val sel = discoverRanks().firstOrNull { it.key == rankKey }
        root.addView(Widgets.topBar(this, sel?.name ?: "发现",
            onBack = { if (rankKey.isBlank()) goBackToPrevious() else showDiscover(site, gender, "") },
            trailing = listOf("⟳" to { showDiscover(site, gender, rankKey) })))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)
        root.addView(body)

        // 站点。换站点会把已选的榜清掉 —— 番茄的分类 ID 起点没有对应物。
        col.addView(Widgets.sectionLabel(this, "站点"))
        val tabs = Md3.row(this)
        for ((k, label) in listOf("fq" to "番茄", "qd" to "起点")) {
            val on = k == site
            val b = Widgets.button(this, label, if (on) "filled" else "tonal", {
                if (!on) showDiscover(k, gender, "")
            })
            b.contentDescription = label
            tabs.addView(b, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f).apply {
                if (tabs.childCount > 0) leftMargin = Md3.dp(this@MainActivity, Md3.GAP / 2)
            })
        }
        tabs.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, Md3.BTN),
        ).apply { bottomMargin = Md3.dp(this@MainActivity, Md3.GAP) }
        col.addView(tabs)

        // 频道。起点五个榜都分男女频，番茄分类 ID 更是男女频完全不通用。
        col.addView(Widgets.sectionLabel(this, "频道"))
        val genders = Md3.row(this)
        for ((g, label) in listOf("male" to "男频", "female" to "女频")) {
            val on = g == gender
            val b = Widgets.button(this, label, if (on) "filled" else "tonal", {
                if (!on) showDiscover(site, g, "")
            })
            genders.addView(b, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f).apply {
                if (genders.childCount > 0) leftMargin = Md3.dp(this@MainActivity, Md3.GAP / 2)
            })
        }
        genders.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, Md3.BTN),
        ).apply { bottomMargin = Md3.dp(this@MainActivity, Md3.GAP) }
        col.addView(genders)

        // 这个站点+频道下有哪些榜。
        val genderEnum = if (gender == "female") RankConfig.Gender.FEMALE else RankConfig.Gender.MALE
        val siteEnum = if (site == "qd") RankConfig.Site.QIDIAN else RankConfig.Site.FANQIE
        val avail = discoverRanks().filter { it.site == siteEnum && it.gender == genderEnum }
        if (avail.isEmpty()) {
            col.addView(Widgets.emptyState(this, "▤", "这个频道暂时没有榜单"))
            return
        }

        // 番茄：先选分类，再选阅读/新书。起点：一个平铺的榜列表。
        col.addView(Widgets.sectionLabel(this, if (site == "qd") "榜单" else "分类"))
        // 按分类名分组，一个分类一行两个按钮（阅读 / 新书）。
        val byCat = LinkedHashMap<String, MutableList<RankConfig.Rank>>()
        for (r in avail) {
            // 榜单名形如「西方奇幻 · 阅读」，分类名取分隔符前那段。
            val cat = r.name.substringBefore(" · ")
            byCat.getOrPut(cat) { mutableListOf() }.add(r)
        }
        for ((cat, list) in byCat) {
            if (site == "qd") {
                // 起点每个榜独立一个按钮，名字里没有「 · 」。
                for (r in list) col.addView(rankButton(r, rankKey, site, gender))
            } else {
                val row = Md3.row(this)
                row.addView(Md3.text(this, cat, Md3.FS_SM, Md3.onSurfaceVariant).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
                for (r in list) {
                    val on = r.key == rankKey
                    val b = Widgets.button(this, if (r.rankType == 1) "新书" else "阅读",
                        if (on) "filled" else "tonal", {
                        if (!on) showDiscover(site, gender, r.key)
                    })
                    row.addView(b, LinearLayout.LayoutParams(
                        Md3.dp(this, 64), Md3.dp(this, Md3.BTN)).apply {
                        leftMargin = Md3.dp(this@MainActivity, Md3.GAP / 2)
                    })
                }
                row.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, Md3.BTN),
                )
                col.addView(row)
            }
        }

        if (sel == null) return

        // 选中了榜：只拉这一个。
        col.addView(Widgets.sectionLabel(this, sel.name))
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(holder)
        holder.addView(Md3.text(this, "正在拉取…", Md3.FS_SM, Md3.onSurfaceVariant))

        bg(Screen.Discover(site, gender, rankKey), { loadOneRank(sel) }) { res ->
            holder.removeAllViews()
            res.onSuccess { hits ->
                if (hits.isEmpty()) {
                    holder.addView(Md3.text(this, "这个榜暂时没有内容",
                        Md3.FS_SM, Md3.onSurfaceVariant))
                } else {
                    for (h in hits) holder.addView(discoverItem(col, h))
                }
            }.onFailure { e ->
                holder.addView(Md3.text(this, "取不到：${e.message?.take(60)}",
                    Md3.FS_SM, Md3.error))
                holder.addView(Widgets.button(this, "重试", "tonal", {
                    showDiscover(site, gender, rankKey)
                }))
            }
        }
    }

    /** 起点用：一个榜一个按钮。 */
    private fun rankButton(r: RankConfig.Rank, cur: String, site: String, gender: String): View {
        val on = r.key == cur
        val b = Widgets.button(this, r.name, if (on) "filled" else "tonal", {
            if (!on) showDiscover(site, gender, r.key)
        })
        b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, Md3.BTN),
        ).apply { bottomMargin = Md3.dp(this@MainActivity, 2) }
        return b
    }

    /**
     * 发现页的一行。
     *
     * 副标题优先用 [BookHit.extra]（排名 · 字数 · 最新章节），没有就退回作者。
     * 点进去走多源搜索。
     */
    private fun discoverItem(col: LinearLayout, h: BookHit): LinearLayout {
        val sub = listOf(
            h.author.takeIf { it.isNotBlank() },
            h.extra.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString(" · ")
        return Widgets.listItem(this, h.name, sub, twoLine = true, highlight = true) {
            rememberReturn()
            openDiscoverHit(h)
        }
    }

    /**
     * 拉起一个榜。
     *
     * 番茄分类榜一屏 10 本、不可翻页；起点每页 20 本，这里把前 [RankConfig.Rank.pages]
     * 页串起来（最多 10 页 = 200 本）。整批共用一个死线，免得 10 页各自计时。
     */
    private fun loadOneRank(r: RankConfig.Rank): List<BookHit> {
        // 字符表只读一次。读不到也不致命，书名只是带私用区字符。
        val decoder = runCatching {
            CharsetFontDecoder.fromFile(File(filesDir, "charset.json").absolutePath)
        }.getOrNull()?.takeIf { it.isReady }

        val gender = if (r.gender == RankConfig.Gender.FEMALE) "female" else "male"
        val deadline = System.currentTimeMillis() + RankSource.PAGE_BUDGET_MS * 2
        val pages = if (r.kind == RankConfig.Kind.QIDIAN) r.pages.coerceIn(1, 10) else 1
        val out = LinkedHashMap<String, BookHit>()
        for (p in 1..pages) {
            if (System.currentTimeMillis() > deadline) break
            val got = runCatching { RankSource.fetch(r, gender, p, deadline, decoder) }
                .getOrElse { if (p == 1) throw it else break }
            for (h in got) out.putIfAbsent(h.bookId, h)
        }
        return out.values.toList()
    }


    /**
     * 当前可用的榜单。云端配置拉一次就缓存到内存，不每次翻页都去请求。
     *
     * 拉不到云端时用内置清单，所以发现页永远不会因此打不开。
     */
    private var rankCache: List<RankConfig.Rank>? = null

    private fun discoverRanks(): List<RankConfig.Rank> {
        rankCache?.let { return it }
        val got = RankConfig.load(File(filesDir, "sources")).first
        // 空表不缓存 —— 否则一次取数失败会让本次进程再也不重试。
        if (got.isNotEmpty()) rankCache = got
        return got
    }

    private fun showMarket(page: Int, accumulated: List<YioveSource.Item>) {
        setScreen(Screen.Market(accumulated, 0, page))
        root.removeAllViews()
        root.addView(Widgets.topBar(this, "书源市场", onBack = { showSources() }))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)
        root.addView(body)

        if (accumulated.isEmpty()) {
            col.addView(Widgets.emptyState(this, "☁", "正在拉取第 $page 页…"))
        } else {
            renderMarket(col, accumulated, page)
        }

        val owner: Screen = Screen.Market(accumulated, 0, page)
        bg(owner, { YioveSource.list(page = page, pageSize = 30) }) { res ->
            res.onSuccess { p ->
                val all = accumulated + p.items
                setScreen(Screen.Market(all, p.total, page))
                col.removeAllViews()
                renderMarket(col, all, page, p.total, p.pages)
            }.onFailure { e ->
                col.removeAllViews()
                col.addView(Widgets.emptyState(this, "⚠", "拉取失败：${e.message?.take(60)}"))
                col.addView(Widgets.button(this, "重试", "tonal", { showMarket(page, accumulated) }))
            }
        }
    }

    private fun renderMarket(
        col: LinearLayout,
        items: List<YioveSource.Item>,
        page: Int,
        total: Int = 0,
        pages: Int = 0,
    ) {
        // 已装过的标记出来（按 id 前 8 位找文件名）
        val dir = sourcesDir()
        val installed = YioveSource.installed(dir).map { it.name }.toSet()

        if (total > 0) {
            val card = Widgets.card(this, outlined = true)
            card.addView(Md3.text(this, "共 $total 条 · 第 $page/$pages 页", Md3.FS_MD, Md3.onSurface))
            card.addView(Md3.text(this,
                "点选一条即拉取并导入；全量太大，只按需拉", Md3.FS_SM, Md3.onSurfaceVariant))
            col.addView(card)
        }

        col.addView(Widgets.sectionLabel(this, "第 $page 页"))
        val startIdx = if (total > 0) items.size - 30 else 0
        items.drop(startIdx).forEach { it0 ->
            val has = installed.any { it.startsWith(it0.id.take(8)) }
            col.addView(Widgets.listItem(
                this, it0.name,
                if (has) "已导入" else "热度 ${it0.views}",
                twoLine = true, highlight = false,
                leading = Md3.text(this, if (has) "✓" else "↓", Md3.FS_SM,
                    if (has) Md3.ok else Md3.onSurfaceVariant),
            ) { if (!has) installFromMarket(it0.name, it0.id, page, items) })
        }

        val more = Widgets.button(this, "下一页", "tonal", { showMarket(page + 1, items) })
        more.layoutParams = Md3.lpDp(this, null, Md3.BTN).apply {
            topMargin = Md3.dp(this@MainActivity, Md3.GAP)
        }
        col.addView(more)
    }

    /** 拉单条规则并导入（约 1 秒），成功后回列表把该条标成已导入。 */
    private fun installFromMarket(name: String, id: String, page: Int, shown: List<YioveSource.Item>) {
        toast("正在拉取「$name」…")
        val owner: Screen = Screen.Market(shown, 0, page)
        bg(owner, { YioveSource.install(sourcesDir(), YioveSource.Item(id, name, "", 0)) }) { res ->
            res.onSuccess { r ->
                toast(if (r.ok) "已导入：${r.name}" else "不支持：${r.issue ?: "未知原因"}")
                showMarket(page, shown.filter { it.name != name })
            }.onFailure { toast("导入失败：${it.message?.take(50)}") }
        }
    }

    /** 从云端整包更新书源库。成功后回书源页，能看到新的条数。 */
    private fun updateFromCloud() {
        toast("正在检查…")
        val owner: Screen = Screen.Sources
        bg(owner, {
            CloudSource.update(sourcesDir(), onProgress = { msg -> postAlive(owner) { toast(msg) } })
        }) { res ->
            val r = res.getOrElse { e ->
                DevLog.log("WatchDL", "云端更新失败：${e.message}")
                CloudSource.Result(false, "更新异常：${e.message?.take(60)}")
            }
            toast(r.message)
            // 不管成没成都会书源页 —— 失败时用户能看到当前条数，知道没被弄坏。
            showSources()
        }
    }

    /** 本地书源一行。 */
    private data class SourceRow(
        val file: String,
        val name: String,
        val mark: String,
        val note: String?,
        /** 声明为 http:// 的源（M7）；实际是否回落到明文由 [Http.cleartextHosts] 决定。 */
        val declaredHttp: Boolean = false,
        /** 书源根地址，用来把探测结论对到这条行上。null = 导入器没给出。 */
        val host: String? = null,
    ) {
        /** 声明明文、且探测结果明确说这个 host 升不上 https。 */
        val plainConfirmed: Boolean
            get() = declaredHttp && host != null && Http.isCleartextHost(host)
    }

    /**
     * 读 [filesDir]/sources 下所有 json，导入并统计。
     *
     * 抽成独立函数是为了让调用方能用 try 包住 —— 以前这段直接写在 thread 里，
     * 一抛异常整屏就空白，表现成「书源 0 条」。
     */
    /** 诊断用：本地书源目录路径。 */
    private val srcDirPath: String get() = File(filesDir, "sources").absolutePath

    private fun buildLocalSourceRows(): Quad<List<File>, List<SourceRow>, Int, Int> {
        val dir = File(filesDir, "sources").apply { mkdirs() }
        val files = sourceFiles(dir)
        val rows = mutableListOf<SourceRow>()
        var total = 0
        var usable = 0
        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val imported = runCatching {
                if (text.contains("bookSourceName")) LegadoImport.importAll(text)
                else SonovelImport.importAllChecked(text).map { (n, r, issue) ->
                    // S16：以前 `.map { (n, r) -> Imported(n, r, null) }`，issue 永远 null，
                    // 缺字段被丢掉的条目在界面上完全不出声。
                    LegadoImport.Imported(n, r, issue)
                }
            }.getOrElse {
                DevLog.log("WatchDL", "导入 ${f.name} 失败：${it.message}")
                emptyList()
            }
            if (imported.isEmpty()) continue
            total += imported.size
            usable += imported.count { it.rule != null }
            imported.forEach { i ->
                rows += SourceRow(f.name, i.name,
                    if (i.rule == null) "✗" else if (i.issue != null) "⚠" else "✓", i.issue,
                    i.declaredHttp, i.rule?.host)
            }
        }
        // 探测是按 host 记的，回填到行上；没探过的 host 保留 null。
        return Quad(files, rows, total, usable)
    }

    /** 四元组。Kotlin 标准库只有 Pair/Triple。 */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /**
     * 书源目录里**真正参与搜索**的 json。
     *
     * 书源目录原本是「所有 .json 都要」—— 于是任何时候往这里放过第二个文件
     * （旧版留下的 `legado.qualified.json`、手工备份、云端整包的另一份），
     * 它们会跟正式源库叠在一起，用户看到两百多个源却说不清哪来的。
     * 2026-10-21 实测设备上 `legado.json`(175) + `legado.qualified.json`(42) = 217 条。
     *
     * 现在只认两类：
     * - `legado.json`：云端 / 内置的整包源库（唯一会被同步覆盖的文件）
     * - 不含 175 条整包特征的散装文件：书源市场单条导入、手动导入的
     *
     * 备份一律带 `.bak` 后缀，天然被 extension 过滤掉。
     */
    private fun sourceFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.filter { !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 书源目录。本地书源与市场导入的都放这里。 */
    private fun sourcesDir(): File = File(filesDir, "sources").apply { mkdirs() }

    /**
     * 把导入器的长原因压缩成一句能看的短句。
     *
     * `部分字段用脚本：searchUrl(脚本)、content(非@js脚本)` 这种在列表里
     * 只占一行、又看不出重点，所以归类到几个大类。
     */
    private fun shortReason(note: String?): String {
        val n = note ?: return ""
        return when {
            // 以前直接把「引擎正则错误：<字段> | <异常>」摊给用户看（P1-4）。
            // 完整信息 logcat 里有，界面只说人话。
            n.startsWith("引擎正则错误") -> "这条源的解析规则无法执行"
            n.startsWith("解析异常") -> "解析这条源时出错"
            n.contains("非@js脚本") || n.contains("非 @js 脚本") -> "用了不支持的脚本写法"
            n.contains("脚本") && n.contains("部分字段") -> "部分字段靠脚本"
            n.contains("无 searchUrl") -> "没有搜索地址（只能探索）"
            n.contains("不是绝对地址") || n.contains("代码而非地址") -> "搜索地址不合法"
            n.contains("源已禁用") -> "源已禁用"
            n.contains("请求头用脚本") -> "请求头需要脚本"
            n.length > 26 -> n.take(26) + "…"
            else -> n
        }
    }

    /** ⑥ 阅读器。 */
    private fun showReader(info: BookInfo, index: Int, text: String, src: BookSource? = null) {
        setScreen(Screen.Reader(info, index, text, src))
        saveProgress(info.bookId, index)
        // 浅色底时整套前景色都要换：正文、顶栏、底栏按钮、进度。
        // 以前只换了正文，白底上顶栏还是黑块，上下章还是浅紫，几乎看不见。
        val light = Color.red(readerBackground) > 100
        val readerInk = if (light) Color.rgb(28, 27, 31) else Md3.onSurface
        val readerAccent = if (light) Color.rgb(56, 30, 114) else Md3.primary
        val readerMuted = if (light) Color.rgb(73, 69, 79) else Md3.onSurfaceVariant
        val hairline = if (light) 0x24000000 else 0x24FFFFFF
        root.removeAllViews()

        val bar = Widgets.topBar(this, info.chapters[index].title, onBack = { showToc(info) },
            trailing = listOf("⚙" to { showReaderSettings(info, index, text, src) }))
        bar.setBackgroundColor(readerBackground)
        if (light) Widgets.tintSubtree(bar, readerInk)
        root.addView(bar)
        root.addView(android.view.View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        body.setBackgroundColor(readerBackground)
        val col = Widgets.contentColumn(body)

        // 正文用专用字号（比正文 UI 大），行距 1.8 在手表上偏松，手机上刚好。
        val tv = Md3.text(this, if (text.isEmpty()) "本章无正文" else text, readerSize, readerInk)
        tv.setLineSpacing(Md3.dp(this, 2).toFloat(), readerSpacing)
        tv.justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
        tv.setPadding(Md3.dp(this, 3), Md3.dp(this, 2), Md3.dp(this, 3), Md3.dp(this, 24))
        col.addView(tv)

        root.addView(body, Md3.lpWeight(1f))

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
        root.addView(android.view.View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))

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
            minHeight = Md3.dp(this@MainActivity, Md3.HIT)
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
        root.addView(foot)
    }

    /** 上一章。跳过连续的锁定章（付费章取不到正文）。 */
    private fun prevChapter(info: BookInfo, index: Int) {
        val prev = index - 1
        if (prev < 0) { toast("已是第一章"); return }
        if (info.chapters[prev].locked) {
            val free = (prev downTo 0).firstOrNull { !info.chapters[it].locked }
            if (free == null) { toast("前面都是付费章"); return }
            preview(info, free)
        } else preview(info, prev)
    }

    // ══════════════════ 动作 ══════════════════

    /** 用手表上的输入对话框要关键词。 */
    private fun askKeyword() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Md3.dp(this@MainActivity, 12), Md3.dp(this@MainActivity, 4),
                       Md3.dp(this@MainActivity, 12), Md3.dp(this@MainActivity, 2))
        }
        // 平台默认的输入框是绿色下划线、绿色「取消」、20sp 标题，与 MD3 紫系冲突。
        // 这里全部换成自绘：圆角底输入框 + 两个自绘按钮，对话框不再挂系统按钮。
        val dialog = Widgets.styleDialog(android.app.AlertDialog.Builder(this)
            .setTitle("搜索小说")
            .setView(box)
            .create())
        input = EditText(this).apply {
            hint = "书名或作者"
            setSingleLine()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Md3.FS_MD)
            setHintTextColor(Md3.onSurfaceVariant)
            setTextColor(Md3.onSurface)
            background = Md3.bg(Md3.surface4, Md3.dp(this@MainActivity, 8).toFloat())
            setPadding(Md3.dp(this@MainActivity, 10), 0, Md3.dp(this@MainActivity, 10), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        box.addView(input, Md3.lpDp(this, null, Md3.BTN + 4))

        fun submit() {
            val kw = input.text.toString().trim()
            // 空关键词不关窗（P1-1）：以前直接 dismiss，用户以为自己按错了。
            if (kw.isEmpty()) toast("请先输入书名") else { dialog.dismiss(); doSearch(kw) }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }

        val row = Md3.row(this)
        row.setPadding(0, Md3.dp(this@MainActivity, 8), 0, 0)
        row.addView(Widgets.button(this, "取消", "outline") { dialog.dismiss() },
            LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
        row.addView(Widgets.button(this, "搜索", "filled") { submit() },
            LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f).apply {
                leftMargin = Md3.dp(this@MainActivity, Md3.GAP)
            })
        box.addView(row)

        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        // 手机上自动弹键盘，省一次点击。非主屏（手表/虚拟屏）不弹：
        // 那里没有键盘，弹出来只会盖住界面 —— 实测在虚拟屏上会把整帧渲染卡住。
        if ((display?.displayId ?: android.view.Display.DEFAULT_DISPLAY) ==
            android.view.Display.DEFAULT_DISPLAY) {
            input.requestFocus()
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    private fun doSearch(keyword: String, offset: Int = 0) {
        // screen 必须同步跟上：进度回调靠它认屏，不换的话还停在上一屏，
        // 回调全被屏守卫丢掉 —— 进度条不动、结果也回不来。
        // 新的一轮搜索要清掉上一轮的「已见过」，否则同名书会被自己的旧记录过滤掉。
        if (offset == 0) seenHits.clear()
        setScreen(Screen.Hits(keyword, emptyList(), offset, 0))
        root.removeAllViews()
        root.addView(Widgets.topBar(this, "搜索中…", onBack = { showHome() }))
        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)
        // 进度文本每多一个源就改一次（P1-2）。以前 15~20 秒里只有一个静态的
        // 「正在搜索」，用户分不清是在跑还是卡死了。
        // 不能用 Widgets.emptyState —— 它内部自建 TextView，外面拿不到引用改字。
        val box = Md3.column(this).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(Md3.dp(this@MainActivity, 12), Md3.dp(this@MainActivity, 24),
                       Md3.dp(this@MainActivity, 12), Md3.dp(this@MainActivity, 24))
        }
        box.addView(Md3.text(this, "⟳", 30f, Md3.onSurfaceVariant).apply { alpha = 0.3f; gravity = Gravity.CENTER })
        val progress = Md3.text(this, "正在搜索「$keyword」\n已扫 0 个源", Md3.FS_MD, Md3.onSurfaceVariant)
            .apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, Md3.dp(this@MainActivity, 10), 0, 0)
            }
        box.addView(progress)
        // 进程条 + 出口提示：原来只有一个静态数字，卡住时用户不知道能不能退。
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this@MainActivity, 3),
            ).apply { topMargin = Md3.dp(this@MainActivity, 8) }
        }
        box.addView(bar)
        box.addView(Md3.text(this, "超过 15 秒可以返回重试", Md3.FS_SM, Md3.onSurfaceVariant).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, Md3.dp(this@MainActivity, 8), 0, 0)
        })
        col.addView(box)
        root.addView(body)

        val owner: Screen = Screen.Hits(keyword, emptyList(), offset, 0)
        bg(owner, {
            // 多源聚合：并发 5、总超时 15 秒，单源失败静默跳过。
            // offset 让「换一批源」从下一批开始，而不是重跑同一批。
            multi.searchDetailed(keyword, onProgress = { done, total ->
                postAlive(owner) {
                    progress.text = "正在搜索「$keyword」\n已扫 $done / $total 个源"
                    bar.isIndeterminate = false
                    bar.max = total.coerceAtLeast(1)
                    bar.progress = done
                }
            }, offset = offset)
        }) { res ->
            res.onSuccess { r ->
                DevLog.log("WatchDL",
                    "搜索「$keyword」：${r.hits.size} 条 / 响应 ${r.responded}/${r.total} 源" +
                    if (r.failed.isEmpty()) "" else "，失败 ${r.failed.size}: ${r.failed.take(5)}")
                showHits(keyword, r.hits, r, offset)
            }.onFailure { e ->
                toast("搜索失败：${e.message}")
                showHome()
            }
        }
    }

    /**
     * 按命中项选定该用哪个源。
     *
     * 多源聚合后，一本书可能来自任意一条 legado 书源，也可能来自番茄。
     * [BookHit.sourceName] 带上了源名，用它在 [multi] 里找回具体那条源。
     * 找不到（旧数据/空名）就退回番茄 —— 那是以前的行为，不会更差。
     */
    private fun sourceFor(hit: BookHit): BookSource = multi.pickFor(hit) ?: source

    /** 打开一条命中项（搜索结果的点击入口）。 */
    private fun openHit(hit: BookHit) {
        val src = sourceFor(hit)
        // 记下搜索结果这一屏，目录页返回时原样退回去。
        rememberReturn()
        val job = Downloader.restore(jobsStore(), hit.bookId)
        if (job != null) {
            showToc(BookInfo(hit.bookId, job.name.ifBlank { hit.name }, job.author.ifBlank { hit.author }, job.chapters), src)
            return
        }
        loadToc(hit.name, hit.author, siblingsOf(hit))
    }

    /**
     * 发现页的点击入口。
     *
     * 番茄榜的书带着真实 `bookId`，且[FanqieApp]能用它直接拉目录，
     * 所以**不搜全量源**：搜索会在 32 条 legado 源里再跑一遍，十几秒起步，
     * 而且搜出来的第一本未必是榜上这本。需求就是要番茄的书走番茄源。
     *
     * 起点榜没有对应的[BookSource]，仍走多源搜索。
     */
    private fun openDiscoverHit(hit: BookHit) {
        rememberReturn()
        if (hit.sourceName != "番茄" || hit.bookId.isBlank()) {
            doSearch(hit.name)
            return
        }
        loadToc(hit.name, hit.author, listOf(hit))
    }

    /**
     * 取同一本书的其它源：优先当前搜索结果里同名的，其次就是它自己。
     *
     * 按当前顺序依次试（列表已按源名排过，同名的挨着），
     * 让被点的那一条排第一。
     */
    private fun siblingsOf(hit: BookHit): List<BookHit> {
        val all = (screen as? Screen.Hits)?.hits.orEmpty()
        val same = all.filter {
            it.name.trim() == hit.name.trim() && it.author.trim() == hit.author.trim()
        }
        return (listOf(hit) + same).distinctBy { it.sourceName }
    }

    /**
     * 依次尝试同名书的多个源，取到非空目录就用。
     *
     * 单个源经常取不到目录（站点改版、要登录、规则失效），而搜索命中的
     * 同名书往往有十几个源可选。以前只试一个，试不中就报「目录失败」
     * 退回首页 —— 用户看到的就是「这本书打不开」，实际只要换个源就行。
     *
     * 这与手表外的 e2e 逻辑一致（e2e 会逐个试到能用为止）。
     */
    private fun loadToc(name: String, author: String, candidates: List<BookHit>, tried: Int = 0) {
        val hit = candidates.getOrNull(tried)
        if (hit == null) {
            showTocFailed(name, candidates)
            return
        }
        root.removeAllViews()
        root.addView(Widgets.topBar(this, name, onBack = { goBackToPrevious() }))
        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val hint = if (tried == 0) "正在取目录…" else "换源重试（${tried + 1}/${candidates.size}）…"
        (Widgets.contentColumn(body)).addView(Widgets.emptyState(this, "⏳", hint))
        root.addView(body)

        // screen 要跟着走：此时 bookId 还不确知，用书名做身份。
        // 不设的话仍停在搜索结果屏，屏守卫会把这个回调全丢掉，
        // 用户看到的就是永远停在「正在取目录…」。
        setScreen(Screen.Toc(BookInfo("", name, author, emptyList())))

        val owner: Screen = Screen.Toc(BookInfo("", name, author, emptyList()))
        bg(owner, {
            val src = sourceFor(hit)
            src to src.toc(hit.bookId, hit.name, hit.author)
        }) { res ->
            val pair = res.getOrNull()
            if (pair != null && pair.second.chapters.isNotEmpty()) {
                if (tried > 0) toast("已换到 ${hit.sourceName}")
                // N6：目录被预算/超时截断时必须说一声。不说的话用户以为
                // 目录就这么长，下载完标 DONE，书悄悄少几十章。
                if (pair.second.truncated) {
                    toast("目录可能不完整（${pair.second.chapters.size} 章），可稍后重试")
                }
                showToc(pair.second, pair.first)
            } else {
                loadToc(name, author, candidates, tried + 1)
            }
        }
    }

    /**
     * 所有源都试完了仍拿不到目录。
     *
     * 不能直接跳回首页（用户会以为点错了），也不能只弹个 toast
     * （一闪就没了，不知道发生了什么）。停在这一页把情况说清楚，
     * 返回键自己会回到搜索结果。
     */
    private fun showTocFailed(name: String, candidates: List<BookHit>) {
        val count = candidates.size
        root.removeAllViews()
        root.addView(Widgets.topBar(this, name, onBack = { goBackToPrevious() }))
        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)
        val msg = if (count <= 1)
            "「$name」取不到目录\n这条源可能需要登录或已失效"
        else
            "「$name」取不到目录\n试了 $count 个源都不行，可能是站点都改了"
        col.addView(Widgets.emptyState(this, "⚠", msg))
        col.addView(Widgets.button(this, "换源重试", "filled") {
            if (candidates.isNotEmpty()) loadToc(name, "", candidates) else toast("没有可重试的源")
        }, Md3.lpDp(this, null, Md3.BTN).apply { topMargin = Md3.dp(this@MainActivity, Md3.GAP) })
        col.addView(Widgets.button(this, "返回搜索", "text") { goBackToPrevious() },
            Md3.lpDp(this, null, Md3.BTN).apply { topMargin = Md3.dp(this@MainActivity, Md3.GAP) })
        root.addView(body)
    }

    /** 从作业反查书籍，恢复目录之后打开。 */
    private fun openBook(bookId: String, name: String, author: String) {
        // 书架/最近下载进来的，返回时回到首页（对应 rememberReturn=null 的默认行为）。
        rememberReturn()
        val job = Downloader.restore(jobsStore(), bookId)
        if (job != null) {
            showToc(BookInfo(bookId, job.name.ifBlank { name }, job.author.ifBlank { author }, job.chapters))
            return
        }

        setScreen(Screen.Toc(BookInfo(bookId, name, author, emptyList())))
        root.removeAllViews()
        root.addView(Widgets.topBar(this, name, onBack = { showHome() }))
        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        (Widgets.contentColumn(body)).addView(Widgets.emptyState(this, "⏳", "正在取目录…"))
        root.addView(body)

        val owner: Screen = Screen.Toc(BookInfo(bookId, name, author, emptyList()))
        bg(owner, { source.toc(bookId, name, author) }) { res ->
            res.onSuccess { showToc(it, source) }
                .onFailure { toast("目录失败：${it.message}"); showHome() }
        }
    }

    /** 从记录的位置续读：章号 + 章内节号。 */
    private fun preview(info: BookInfo, index: Int) {
        val ch = info.chapters[index]
        // 先读本地正文，只有缺章时才在线程中初始化源并联网。
        val src = (screen as? Screen.Toc)?.src
            ?: (screen as? Screen.Reader)?.src
            ?: tocSrc
        root.removeAllViews()
        root.addView(Widgets.topBar(this, ch.title, onBack = { showToc(info) }))
        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        (Widgets.contentColumn(body)).addView(Widgets.emptyState(this, "⟳", "正在取正文…"))
        root.addView(body)

        // 读者停在 Toc / Reader 屏上点章节，回来时应该还在原来的屏。
        val owner: Screen = screen

        thread {
            try {
                val line = jobsStore().readChapters(info.bookId)[ch.itemId]
                val localHtml = line?.let { TinyJson.path(it, "content") }
                val actualSrc = if (localHtml == null) (src ?: source) else src
                val html = localHtml ?: actualSrc!!.content(ch.itemId, info.bookId)
                val text = Downloader.htmlToText(html).ifEmpty { html.replace(Regex("<[^>]+>"), "\n").trim() }
                postAlive(owner) { showReader(info, index, text, actualSrc) }
            } catch (e: Exception) {
                postAlive(owner) { toast("正文失败：${e.message}"); showToc(info, src) }
            }
        }
    }

    private fun nextChapter(info: BookInfo, index: Int) {
        val next = index + 1
        if (next >= info.chapters.size) { toast("已是最后一章"); return }
        if (info.chapters[next].locked) {
            // 跳过连续的锁定章
            val free = (next until info.chapters.size).firstOrNull { !info.chapters[it].locked }
            if (free == null) { toast("后面都是付费章"); return }
            preview(info, free)
        } else preview(info, next)
    }

    private fun startDownload(info: BookInfo) {
        if (downloading) { showProgress(info); return }
        downloading = true
        progressDone = Downloader.restore(jobsStore(), info.bookId)?.count(ChapterStatus.DONE) ?: 0
        progressFailed = 0
        progressStartedAt = System.currentTimeMillis()
        showProgress(info)
        DownloadService.start(this, info, freeOnly = true)
    }

    private fun showTocMenu(info: BookInfo) {
        Widgets.menuDialog(this, null, listOf("导出 TXT" to { exportTxt(info) })).show()
    }

    /**
     * 设置页。
     *
     * 以前阅读设置只是阅读页里的一个对话框，主界面根本没有入口，
     * 用户不点进一本书就不知道字号/行距能改。
     * 这里提成独立一页：改完**立即落盘**（不用点「确定」），预览实时跟着变。
     *
     * 选段控件与阅读页共用同一个段式风格：浅紫填充 + 深字。
     */
    private fun showSettings() {
        setScreen(Screen.Settings)
        root.removeAllViews()
        root.addView(Widgets.topBar(this, "设置", onBack = { goBackToPrevious() }))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)

        col.addView(Widgets.sectionLabel(this, "阅读显示"))

        // 预览块：字号、行距、背景一起看，比三个独立选项直观。
        val preview = Md3.text(this, "示例正文，看看效果。\n第二行。", Md3.FS_READ, Md3.onSurface).apply {
            setPadding(Md3.dp(this@MainActivity, 10), Md3.dp(this@MainActivity, 8),
                       Md3.dp(this@MainActivity, 10), Md3.dp(this@MainActivity, 8))
        }
        col.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Md3.dp(this@MainActivity, Md3.GAP) })

        fun paintPreview() {
            preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, readerSize)
            preview.setLineSpacing(Md3.dp(this, 1).toFloat(), readerSpacing)
            preview.setTextColor(if (Color.red(readerBackground) > 100) Color.rgb(28, 27, 31) else Md3.onSurface)
            preview.background = Md3.bg(readerBackground, Md3.dp(this, 10).toFloat())
        }

        /** 一行「标签 + 多段」。选中后立即存盘并重画预览。 */
        fun segmentRow(label: String, options: List<Pair<String, Any>>, get: () -> Any, set: (Any) -> Unit) {
            val row = Md3.row(this).apply { setPadding(0, 0, 0, Md3.dp(this@MainActivity, 4)) }
            row.addView(Md3.text(this, label, Md3.FS_SM, Md3.onSurfaceVariant),
                LinearLayout.LayoutParams(Md3.dp(this, 34), ViewGroup.LayoutParams.WRAP_CONTENT))
            val chips = mutableListOf<Pair<TextView, Any>>()
            options.forEachIndexed { i, opt ->
                val chip = Md3.text(this, opt.first, Md3.FS_SM, Md3.onSurfaceVariant).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        set(opt.second)
                        saveReaderPrefs(readerSize, readerSpacing, readerBackground)
                        // 只重画选中态与预览，不重建整页（重建会丢滚动位置）。
                        chips.forEach { (v, val2) ->
                            val sel = val2 == get()
                            v.setTypeface(null, if (sel) android.graphics.Typeface.BOLD
                                                    else android.graphics.Typeface.NORMAL)
                            v.setTextColor(if (sel) Md3.onPrimary else Md3.onSurfaceVariant)
                            v.background = Md3.ripple(this@MainActivity,
                                if (sel) Md3.primary else Color.TRANSPARENT,
                                Md3.dp(this@MainActivity, 13).toFloat(),
                                if (sel) null else Md3.outlineVariant)
                        }
                        paintPreview()
                    }
                }
                chips.add(chip to opt.second)
                row.addView(chip, LinearLayout.LayoutParams(0, Md3.dp(this, 30), 1f).apply {
                    leftMargin = if (i == 0) 0 else Md3.dp(this@MainActivity, 4)
                })
            }
            // 初始选中态直接按当前值涂色，省一次额外刷。
            chips.forEach { (v, val2) ->
                val sel = val2 == get()
                v.setTypeface(null, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                v.setTextColor(if (sel) Md3.onPrimary else Md3.onSurfaceVariant)
                v.background = Md3.ripple(this@MainActivity,
                    if (sel) Md3.primary else Color.TRANSPARENT,
                    Md3.dp(this@MainActivity, 13).toFloat(),
                    if (sel) null else Md3.outlineVariant)
            }
            col.addView(row)
        }

        segmentRow("字号", listOf("小" to (Md3.FS_MD as Any), "中" to (Md3.FS_READ as Any), "大" to (Md3.FS_XL as Any)),
            { readerSize as Any }) { readerSize = it as Float }
        segmentRow("行距", listOf("紧" to (1.35f as Any), "标准" to (1.75f as Any), "松" to (1.9f as Any)),
            { readerSpacing as Any }) { readerSpacing = it as Float }
        val bgs = listOf(
            "深" to Color.rgb(20, 18, 24),
            "浅" to Color.WHITE,
            "暖" to Color.rgb(255, 248, 230),
        )
        segmentRow("背景", bgs.map { it.first to (it.second as Any) },
            { readerBackground as Any }) { readerBackground = it as Int }

        // 字号/行距改为滑杆：三档粒度太粗，字号差一档就过大。
        // 滑杆下面的档位行保留作为快捷选择，两者同步。
        col.addView(Widgets.sliderRow(this, "字号", 11f, 26f, readerSize, 0,
            { "%.1f sp".format(it) }) { v ->
            readerSize = v
            saveReaderPrefs(readerSize, readerSpacing, readerBackground)
            paintPreview()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Md3.dp(this@MainActivity, Md3.GAP) })

        col.addView(Widgets.sliderRow(this, "行距", 1.1f, 2.4f, readerSpacing, 0,
            { "%.2f 倍".format(it) }) { v ->
            readerSpacing = v
            saveReaderPrefs(readerSize, readerSpacing, readerBackground)
            paintPreview()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Md3.dp(this@MainActivity, Md3.GAP) })

        // 表冠灵敏度：刚改完平滑滚动后这两个常数直接决定手感，不同人差异很大。
        col.addView(Widgets.sectionLabel(this, "表冠"))
        col.addView(Widgets.sliderRow(this, "每格位移", 20f, 200f, crownStep, 0,
            { "%d px".format(it.toInt()) }) { v ->
            crownStep = v
            saveCrownPrefs()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Md3.dp(this@MainActivity, Md3.GAP) })

        col.addView(Widgets.sliderRow(this, "跟手时长", 0f, 200f, crownGlide.toFloat(), 0,
            { if (it < 1f) "立即" else "%d ms".format(it.toInt()) }) { v ->
            crownGlide = v.toInt()
            saveCrownPrefs()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Md3.dp(this@MainActivity, Md3.GAP) })

        // 设置页自身的 body 早就建好了，改参数后需要让下次进页面生效。
        col.addView(Md3.text(this, "表冠参数在下次进入页面时生效", Md3.FS_SM, Md3.onSurfaceVariant).apply {
            setPadding(0, Md3.dp(this@MainActivity, 4), 0, 0)
        })

        paintPreview()

        col.addView(Widgets.sectionLabel(this, "导出"))
        val dirHint = Md3.text(this, "文件名：书名 - 作者.txt（重名自动加 (2)）\n编码：UTF-8 带 BOM",
            Md3.FS_SM, Md3.onSurfaceVariant).apply {
            setPadding(0, Md3.dp(this@MainActivity, 4), 0, Md3.dp(this@MainActivity, Md3.GAP))
        }
        col.addView(dirHint)

        col.addView(Widgets.sectionLabel(this, "书源"))
        col.addView(Widgets.listItem(this, "管理书源", "启用/禁用与导入", twoLine = true) {
            rememberReturn(); showSources()
        })

        col.addView(Widgets.sectionLabel(this, "关于"))
        col.addView(Widgets.listItem(this, "版本", appVersion(), twoLine = false, highlight = false) { })

        // 开发者模式：默认关闭，关着时只有普通 logcat，不占内存不写盘。
        col.addView(Widgets.sectionLabel(this, "开发者"))
        val devRow = Widgets.listItem(this, "开发者模式", devLogHint(), twoLine = true,
            trailing = if (devLogOn()) "开" else "关") { toggleDevLog() }
        col.addView(devRow)
        if (devLogOn()) {
            col.addView(Widgets.listItem(this, "导出日志", "写入文件后系统分享", twoLine = true) { exportDevLog() })
            col.addView(Widgets.listItem(this, "清空日志", null, twoLine = false) {
                DevLog.clear(); toast("日志已清空")
            })
        }

        root.addView(body)
    }

    private fun devLogOn() = getSharedPreferences("reader", MODE_PRIVATE).getBoolean("devlog", false)

    private fun devLogHint(): String {
        val f = File(filesDir, "dev.log")
        val kb = if (f.exists()) (f.length() / 1024).coerceAtLeast(0) else 0
        return if (devLogOn()) "已开启 · 落盘 ${kb} KB · 只记本 App" else "关闭：仅打 logcat，不存不写盘"
    }

    private fun toggleDevLog() {
        val on = !devLogOn()
        getSharedPreferences("reader", MODE_PRIVATE).edit().putBoolean("devlog", on).apply()
        DevLog.file = File(filesDir, "dev.log")
        DevLog.setEnabled(on)
        showSettings()          // 重画，导出/清空按钮跟着开关出现或消失
    }

    /**
     * 写一份快照到公共「文档」目录，再走系统分享递出去。
     * 不用 FileProvider：本工程没有 androidx，而 file:// 从 targetSdk 24 起已被封。
     */
    private fun exportDevLog() {
        if (!ensureLegacyStorage()) {
            toast("需要存储权限才能导出")
            return
        }
        val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
        val out = File(filesDir, "dev-$stamp.log")
        runCatching {
            out.writeText(DevLog.dump().joinToString("") { it + "\n" })
        }.onFailure { toast("写日志失败：${it.message}"); return }
        val published = publishToDocuments(out)
        if (published == null) {
            toast("已写入 ${out.name}，但无法存到文档")
            return
        }
        toast("已存到文档/$published")
    }

    /** 版本号从 PackageManager 读，避免在代码里再写一份与 build.sh 不同步的常量。 */
    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private fun showJobMenu(job: BookJob) {
        Widgets.menuDialog(this, job.name.ifBlank { "未命名 · ${job.total} 章" }, listOf(
            "重命名" to { renameJob(job) },
            "导出 TXT" to { exportTxt(BookInfo(job.bookId, job.name, job.author, job.chapters)) },
            "删除" to { confirmDeleteJob(job) },
        )).show()
    }

    private fun renameJob(job: BookJob) {
        val box = EditText(this).apply {
            setSingleLine()
            hint = "输入书名"
            setText(job.name)
            selectAll()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Md3.FS_MD)
            setHintTextColor(Md3.onSurfaceVariant)
            setTextColor(Md3.onSurface)
            background = Md3.bg(Md3.surface4, Md3.dp(this@MainActivity, 8).toFloat())
            setPadding(Md3.dp(this@MainActivity, 10), 0, Md3.dp(this@MainActivity, 10), 0)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(Md3.dp(this@MainActivity, 12), Md3.dp(this@MainActivity, 4),
                       Md3.dp(this@MainActivity, 12), 0)
        }
        wrap.addView(box, Md3.lpDp(this, null, Md3.BTN + 4))
        Widgets.styleDialog(android.app.AlertDialog.Builder(this).setTitle("重命名")
            .setView(wrap).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = box.text.toString().trim()
                if (name.isEmpty()) { toast("书名不能为空"); return@setPositiveButton }
                val raw = jobsStore().readJob(job.bookId) ?: return@setPositiveButton
                val json = raw.replace(Regex("(\\\"name\\\"\\s*:\\s*)\\\"[^\\\"]*\\\""), "$1${Downloader.quote(name)}")
                jobsStore().writeJob(job.bookId, json)
                showHome()
            }.create()).show()
    }

    private fun confirmDeleteJob(job: BookJob) {
        val label = job.name.ifBlank {
            "未命名 · ${job.total} 章"
        }
        Widgets.styleDialog(android.app.AlertDialog.Builder(this).setTitle("删除下载")
            .setMessage("将删除“$label”的全部正文")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                File(jobsDir(), JobStore.safeDirName(job.bookId)).deleteRecursively()
                showHome()
            }.create()).show()
    }

    /**
     * 把导出文件放到系统「文档」目录。
     *
     * targetSdk 34 上不能直接用 File 写 /sdcard/Documents（分区存储），
     * 走 MediaStore 插入 relative_path=Documents/ —— 这一步不需要任何权限。
     * 返回最终文件名，失败返回 null。
     */
    private fun publishToDocuments(src: File): String? {
        val uri = android.provider.MediaStore.Files.getContentUri("external")
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOCUMENTS)
        }
        return try {
            val target = contentResolver.insert(uri, values) ?: return null
            val ok = contentResolver.openOutputStream(target)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
                true
            } ?: false
            if (ok) src.name else null
        } catch (e: Exception) {
            DevLog.log("WatchDL", "写入 Documents 失败：${e.message}")
            null
        }
    }

    /** Android 9 及以下写公开目录要权限；10+ 走 MediaStore，不需要。 */
    private fun ensureLegacyStorage(): Boolean {
        if (Build.VERSION.SDK_INT > 28) return true
        val granted = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 4021)
        }
        return granted
    }

    private fun exportTxt(info: BookInfo) {
        if (!ensureLegacyStorage()) {
            toast("需要存储权限才能导出")
            return
        }
        thread {
            try {
                val dir = exportDir().apply { mkdirs() }
                val safe = info.name.replace(Regex("[^\\w\\u4e00-\\u9fa5]"), "_").trim('_')
                    .take(40).ifEmpty { "未命名_${info.bookId.hashCode().toUInt().toString(16)}" }
                // 文件名固定为「书名 - 作者.txt」，**不带时间戳**：
                // 带戳的话同一本反复导出会堆出很多副本，同名也认不出是同一本。
                // 重名时自动加 (2) (3)，不静默覆盖已有文件。
                val author = info.author.replace(Regex("[^\\w\\u4e00-\\u9fa5]"), "_").trim('_').take(30)
                val base = if (author.isBlank()) safe else "$safe - $author"
                var out = File(dir, "$base.txt")
                var k = 2
                while (out.exists() && k < 1000) { out = File(dir, "$base ($k).txt"); k++ }
                val header = listOf(info.name.ifBlank { "未命名" }, info.author)
                    .filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
                val n = synchronized(exportLock) {
                    // 传目录顺序，否则导出文件按并发落盘顺序排，章节会乱。
                    Downloader.exportText(
                        jobsStore(), info.bookId, out, header,
                        order = info.chapters.map { it.itemId },
                    )
                }
                // 先落私有目录（exportText 要随机写），再复制到系统「文档」。
                val moved = if (n > 0) publishToDocuments(out) else null
                if (moved != null) out.delete()
                runOnUiThread {
                    when {
                        n == 0 -> toast("还没下到内容，先下载")
                        moved != null -> toast("已导出 $n 章到 文档/$moved")
                        else -> toast("已导出 $n 章（应用目录）：${out.name}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("导出失败：${e.message}") }
            }
        }
    }

    // ══════════════════ 服务回调 ══════════════════

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            // 进度广播只是刷新界面。
            // 这里抛的任何异常都会把整个进程搞崩（Receiver 在 Activity 主线程），
            // 用户看到的就是「下载时闪退」，而实际上只是刷新失败。宁可只丢一次刷新。
            runCatching {
                progressDone = intent.getIntExtra(DownloadService.EXTRA_DONE, progressDone)
                progressFailed = intent.getIntExtra(DownloadService.EXTRA_FAILED, progressFailed)
            if (progressStartedAt == 0L) progressStartedAt = System.currentTimeMillis()
            val finished = intent.getBooleanExtra(DownloadService.EXTRA_FINISHED, false)
            val info = (screen as? Screen.Progress)?.info
            if (finished) {
                downloading = false
                toast("下载结束：成功 $progressDone，失败 $progressFailed")
                info?.let { showToc(it) }
            } else if (info != null) {
                showProgress(info)
            }
            }.onFailure { e ->
                DevLog.log("WatchDL", "进度刷新失败（已忽略）：${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(DownloadService.ACTION_PROGRESS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(progressReceiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(progressReceiver, f)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(progressReceiver) }
        // 退到后台、切走、被杀之前，先把读到哪存下来。
        saveReaderPosNow()
    }

    @Deprecated("手表没有返回手势，按返回键回上一层")
    /**
     * 屏幕尺寸变了（旋转、折叠屏、手机接显示器）要重新算缩放，
     * 因为 manifest 里 configChanges 拦下了系统重建。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val before = Md3.scaleFactor()
        Md3.configure(this)
        if (Md3.scaleFactor() != before) {
            // 尺寸档位变了才重建界面，否则只是无谓闪烁
            when (val s = screen) {
                is Screen.Home -> showHome()
                is Screen.Toc -> showToc(s.info)
                is Screen.Reader -> showReader(s.info, s.index, s.text, s.src)
                // 传 null 会丢掉批次信息，「换一批源」按钮就没了；
                // 但 Result 需要真实统计，屏上只记了批次大小，
                // 所以改用 batch 参数直接传。
                is Screen.Hits -> showHits(s.keyword, s.hits, offset = s.offset,
                    batch = s.searchedSources)
                is Screen.Market -> showMarket(s.page, s.hits)
                is Screen.Sources -> showSources()
                is Screen.Progress -> showProgress(s.info)
                Screen.Settings -> showSettings()
                // 配置变更重建**不刷新**：这里只是重画，联网会让旋转/展开后卡一下。
                is Screen.Discover -> showDiscover(s.site, s.gender, s.rankKey)
            }
        }
    }

    override fun onBackPressed() {
        when (val s = screen) {
            Screen.Home -> super.onBackPressed()
            is Screen.Toc -> goBackToPrevious()
            is Screen.Hits -> goBackToPrevious()
            is Screen.Reader -> showToc(s.info)
            is Screen.Sources -> goBackToPrevious()
            is Screen.Market -> showSources()
            is Screen.Progress -> showToc(s.info)
            is Screen.Discover -> goBackToPrevious()
            Screen.Settings -> goBackToPrevious()
        }
    }

    // ══════════════════ 存储 ══════════════════

    /**
     * 清掉作业目录里的脏东西。只在启动时跑一次。
     *
     * 修 3 之前 bookId 直接当目录名，留下了 `https:` 这种不合法名字和一批
     * 空目录。新代码不会再产生，但旧设备上还躺着，清掉免得用户看着目录一团乱。
     *
     * **只删「空目录」和「名字不合法的目录」**，含 job.json 的一律不动。
     */
    private fun cleanDirtyJobs() {
        thread {
            runCatching {
                val dir = jobsDir()
                var removed = 0
                dir.listFiles()?.forEach { f ->
                    if (!f.isDirectory) return@forEach
                    if (File(f, "job.json").isFile) return@forEach
                    val bad = f.name != com.watchdl.core.JobStore.safeDirName(f.name)
                    val empty = f.listFiles()?.isEmpty() ?: true
                    if (bad || empty) {
                        if (f.deleteRecursively()) removed++
                    }
                }
                if (removed > 0) DevLog.log("WatchDL", "清掉 $removed 个脏作业目录")
            }
        }
    }

    private fun jobsDir(): File = File(filesDir, "books").apply { mkdirs() }

    /** 导出目录。**不能放进 jobsDir** —— 它会被当成一个下载作业。 */
    private fun exportDir(): File = File(filesDir, "export").apply { mkdirs() }
    private fun jobsStore(): JobStore = JobStore(jobsDir())

    /** 首次运行时把 assets 里的字符表与书源复制到私有目录。 */
    /**
     * 首次运行时把 assets 里的字符表与书源复制到私有目录。
     *
     * 两个必须做对的地方（都踩过）：
     *
     * 1. **文件大小为 0 也算「没解压」** —— 复制到一半被杀会留下空文件，
     *    只看 `exists()` 会永远不再重试，书源列表就变成 0 条。
     * 2. **失败要重试、不能静默** —— 以前包在 `runCatching` 里，
     *    解压失败时界面只是安静地显示 0 条，看不出原因。
     */
    private fun extractAssets() {
        copyAsset("charset.json", File(filesDir, "charset.json"))
        val srcDir = File(filesDir, "sources").apply { mkdirs() }
        val names = runCatching { assets.list("sources")?.toList() }.getOrNull().orEmpty()
        for (n in names) {
            if (!n.endsWith(".json")) continue
            val dest = File(srcDir, n)
            // 云端管的那个文件**只在缺失时铺底**。
            // 否则会跟 [CloudSource] 打架：云端己拉到 v5（43 条），一进书源页
            // 就被包里那份 v4（42 条）按“长度不同”盖回去，永远停在内置版本。
            if (n == CloudSource.FILE_NAME && dest.isFile && dest.length() > 0L) continue
            copyAsset("sources/$n", dest)
        }
    }

    /** 复制单个 asset。失败重试一次。 */
    private fun copyAsset(assetPath: String, dest: File) {
        // 已存在且非空就跳过 —— 但升级 APK 时要能换新源库，否则装了新版
        // 源还是旧的。用 asset 的未压缩长度做版本判据：长度不同就是新内容。
        // （书源库是整包替换，长度几乎不可能碰巧相同；比算 md5 便宜。）
        if (dest.isFile && dest.length() > 0L) {
            // 注意：不能用 openFd() —— aapt2 把 asset 压成了 DEFLATED，
            // 对压缩资源 openFd 会抛 FileNotFoundException，拿不到长度。
            // open().available() 对压缩资源也返回解压后的全文长度。
            val assetLen = runCatching {
                assets.open(assetPath).use { it.available().toLong() }
            }.getOrNull()
            if (assetLen == null || assetLen == dest.length()) return
            DevLog.log("WatchDL", "更新 $assetPath：${dest.length()} → $assetLen 字节")
        }
        repeat(2) { attempt ->
            val ok = runCatching {
                assets.open(assetPath).use { i ->
                    // 先写临时文件再改名：中途被杀不会留下半个文件冒充成功
                    val tmp = File(dest.parentFile, dest.name + ".tmp")
                    tmp.outputStream().use { o -> i.copyTo(o) }
                    if (tmp.length() == 0L) error("复制出的文件为空")
                    if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
                }
                true
            }.getOrElse { false }
            if (ok && dest.isFile && dest.length() > 0L) return
            if (attempt == 0) DevLog.log("WatchDL", "解压 $assetPath 失败，重试")
        }
        DevLog.log("WatchDL", "解压 $assetPath 最终失败；书源列表可能为空")
    }

    /**
     * 阅读设置。
     *
     * 一屏装得下：一行实时预览 + 三行「标签 + 三段选择」。
     * 旧版是三组上下堆叠的大按钮加一个固定高度预览块，总高超过对话框可见区，
     * 「行距」整行被裁掉半截，只能滚动；选中态还靠「● 」前缀表示。
     * 改值只重画预览与按钮配色，不重建视图树。
     */
    private fun showReaderSettings(info: BookInfo, index: Int, text: String, src: BookSource?) {
        val sizes = listOf("小" to Md3.FS_MD, "中" to Md3.FS_READ, "大" to Md3.FS_XL)
        val spacings = listOf("紧" to 1.35f, "标准" to 1.75f, "松" to 1.9f)
        val bgs = listOf(
            "深" to Color.rgb(20, 18, 24),
            "浅" to Color.WHITE,
            "暖" to Color.rgb(255, 248, 230),
        )

        var curSize = readerSize
        var curSpacing = readerSpacing
        var curBg = readerBackground
        var refresh: () -> Unit = {}

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Md3.dp(this@MainActivity, 12), Md3.dp(this@MainActivity, 2),
                       Md3.dp(this@MainActivity, 12), 0)
        }

        val preview = Md3.text(this, "示例正文，看看效果。", Md3.FS_READ, Md3.onSurface).apply {
            setPadding(Md3.dp(this@MainActivity, 8), Md3.dp(this@MainActivity, 6),
                       Md3.dp(this@MainActivity, 8), Md3.dp(this@MainActivity, 6))
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
            refresh()
        }

        /** 一行「标签 + 三段」。返回刷新函数；改值后由 refresh 统一调用。 */
        fun segmentRow(
            label: String,
            options: List<Pair<String, Any>>,
            get: () -> Any,
            set: (Any) -> Unit,
        ): () -> Unit {
            val row = Md3.row(this).apply { setPadding(0, Md3.dp(this@MainActivity, 3), 0, 0) }
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
                    leftMargin = if (i == 0) 0 else Md3.dp(this@MainActivity, 4)
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

        refresh = {
            rowSize(); rowSpacing(); rowBg(); paintPreview()
        }
        refresh()

        val dialog = Widgets.styleDialog(android.app.AlertDialog.Builder(this)
            .setTitle("阅读设置")
            .setView(box)
            .setNegativeButton("完成", null)
            .create())
        dialog.setOnDismissListener { showReader(info, index, text, src) }
        dialog.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 是否 debug 包。内部路径、诊断信息只给开发者看（P1-4）。 */
    private fun isDebugBuild(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private companion object {
        /** 一次搜索最多跑几个源。源太多时后面的根本轮不到，只会被超时记成失败。 */
        const val MAX_SOURCES = 15

        /** 同时打开的请求数。源限到 15 后可以开大，两轮内跑完。 */
        const val CONCURRENCY = 10
    }
}

/** 目录总章数。 */
private fun BookInfo.total(): Int = chapters.size

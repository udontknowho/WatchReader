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
import com.watchdl.ui.book.showDetail
import com.watchdl.ui.my.showMy
import com.watchdl.ui.reader.*
import com.watchdl.ui.search.*
import com.watchdl.ui.shelf.*
import com.watchdl.ui.source.*
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
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
    internal val multi: MultiSource get() = multiCache ?: buildMulti().also { multiCache = it }

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
    internal val source: BookSource by lazy {
        DeviceRegister.init(File(filesDir, "fanqie"))
        FanqieApp(DeviceRegister.get())
    }
    internal lateinit var root: LinearLayout
    internal lateinit var input: EditText

    private var screen: Screen = Screen.Shelf

    /**
     * 当前屏。搬出去的屏代码只读它，不写 —— 换屏一律走 [setScreen]。
     * 不能直接开放 `screen`：internal var 的 setter 与 [setScreen] 的 JVM 名撞车。
     */
    internal val curScreen: Screen get() = screen

    /** 切屏统一入口。换屏就意味着上一屏的后台任务全部作废。 */
    internal fun setScreen(next: Screen) {
        if (next === screen || next == screen) return
        // 同一屏换内容（搜索结果边跑边上屏、目录重载）时**不能撤任务**：
        // 撤了就把正在跑的那轮搜索自己干掉。屏身份由 screenId 定义，
        // 只看类型 + 关键词，不看列表内容。
        if (screenId(next) == screenId(screen)) {
            screen = next
            return
        }
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
    internal fun <T> bg(owner: Screen, body: () -> T, then: (Result<T>) -> Unit) {
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
    internal fun postAlive(ownerId: String, body: () -> Unit) {
        runOnUiThread {
            // S20：判据抽到 core 的 shouldDeliverCallback，测试调的是真实现。
            if (!shouldDeliverCallback(isGone(), ownerId, screenId(screen))) return@runOnUiThread
            body()
        }
    }

    /** 重载：调用方手里只有屏对象时用它，内部转成 [screenId]。 */
    internal fun postAlive(owner: Screen, body: () -> Unit) = postAlive(screenId(owner), body)

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
    internal var tocSrc: BookSource? = null

    /**
     * 进书详情页时手上带着的同名候选源。
     *
     * 详情页的「查看目录」要靠它走 [loadToc] 的逐源重试。
     * 不放进 [Screen.Detail] 是因为它是「怎么进来的」，不是「哪一屏」——
     * 与 [tocSrc] 同一类状态。
     */
    internal var detailCandidates: List<BookHit> = emptyList()

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
    internal fun rememberReturn() {
        // 同一屏不要重复压：重复调用会让返回要按两次才动。
        if (returnStack.lastOrNull() != screen) returnStack.addLast(screen)
        while (returnStack.size > 8) returnStack.removeFirst()
    }

    /** 回到进来的那一屏；没有记录就回首页。 */
    internal fun goBackToPrevious() {
        replay(returnStack.removeLastOrNull())
    }

    /** 重放一屏（不重新联网）。记录里的屏都自带重绘所需的数据。 */
    private fun replay(s: Screen?) {
        when (s) {
            is Screen.Hits -> showHits(s.keyword, s.all, offset = s.offset, batch = s.searchedSources, replay = true)
            is Screen.Toc -> showToc(s.info, s.src)
            is Screen.Reader -> showReader(s.info, s.index, s.text, s.src)
            is Screen.Market -> showMarket(s.page, s.hits)
            is Screen.Sources -> showSources()
            is Screen.Progress -> showProgress(s.info)
            // 发现页返回时**清空旧数据**重新拉：推荐流是时效内容，
            // 隔几分钟回来还显示旧列表，用户看不出它已过期。
            is Screen.Discover -> showDiscover(s.site, s.gender, s.rankKey)
            is Screen.Settings -> showSettings()
            is Screen.Marks -> showMarks(s.info)
            Screen.Shelf -> showShelf()
            is Screen.Detail -> showDetail(s.info, s.src)
            else -> showShelf()
        }
    }
    private var progressDone = 0
    private var progressFailed = 0
    private var progressStartedAt = 0L
    // 续传时的起点章数。速度/剩余时间只能用增量算：拿总数除以本次耗时
    // 会算出「2190 章/分 · 约 37 秒」，实际要跑 5 分钟。
    private var progressBase = 0
    /** 书架当前是不是封面墙档。两档共用一屏，切底不进返回栈。 */
    internal var shelfGrid = true
    /** 熔断原因，bookId → 一句人能读懂的话。开下时清掉。 */
    private val abortHints = HashMap<String, String>()
    internal var readerSize = Md3.FS_READ
    internal var readerSpacing = 1.75f
    internal var readerBackground = Color.rgb(20, 18, 24)

    /** 当前阅读页的滚动视图与位置。onPause 时要靠它们落盘。 */
    internal var readerBody: android.widget.ScrollView? = null
    internal var readerBookId = ""
    internal var readerIndex = -1
    internal var readerOffset = 0
    internal var scrollSave: Runnable? = null

    /** 表冠灵敏度：每格位移（px）。表冠一格系统只给 1 个单位，必须放大才看得出。 */
    internal var crownStep = 60f

    /** 表冠跟手时长（ms）。越短越跟手，太短会抖。 */
    internal var crownGlide = 50

    /**
     * 主题只存 id。存档里的 id 认不出来时 [Palette.find] 兜底回默认色 ——
     * 删掉一套配色不该让应用起不来。
     */
    private fun loadThemePrefs() {
        Md3.applyPalette(Palette.find(getSharedPreferences("reader", MODE_PRIVATE).getString("theme", null)))
    }

    /**
     * 换主题。配色是 [Md3] 单例上的值，只影响之后新建的视图，
     * 所以存盘后必须重画当前屏，否则屏上还是旧色。
     */
    internal fun pickTheme(id: String) {
        getSharedPreferences("reader", MODE_PRIVATE).edit().putString("theme", id).apply()
        Md3.applyPalette(Palette.find(id))
        // 只从设置页进得来，重画设置页即可；滚动会回顶（主题行本来就在顶上）。
        showSettings()
    }

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
    internal fun readProgress(bookId: String): Int {
        val tag = bookId.take(64)
        val prefs = getSharedPreferences("reader", MODE_PRIVATE)
        // S20：换算逻辑抽到 core 的 resolveStoredIndex，测试调的是真实现。
        val (fixed, writeBack) = resolveStoredIndex(
            raw = readIndex(bookId),
            lastSeen = prefs.contains("last_$tag"),
            segBase = SEG_BASE,
        )
        // 没有 last_ 就是没见过新版代码。先把它标上，下次不再走这条路。
        if (writeBack) prefs.edit().putInt("pos_$tag", fixed).putLong("last_$tag", 0L).apply()
        return fixed
    }

    /** 原始存档章号。-1 = 没记录过，用来区分「没读过」和「读到第 0 章」。 */
    private fun readIndex(bookId: String): Int =
        getSharedPreferences("reader", MODE_PRIVATE).getInt("pos_" + bookId.take(64), -1)

    /** 最近一次把这本书翻开的时刻。0 = 没记录过。 */
    internal fun readTime(bookId: String): Long =
        getSharedPreferences("reader", MODE_PRIVATE).getLong("last_" + bookId.take(64), 0L)

    internal fun saveProgress(bookId: String, index: Int) {
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
    internal fun readOffset(bookId: String): Int =
        getSharedPreferences("reader", MODE_PRIVATE).getInt("off_" + bookId.take(64), 0)

    internal fun saveOffset(bookId: String, index: Int, offset: Int, sync: Boolean = false) {
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

    internal fun saveReaderPrefs(size: Float, spacing: Float, bg: Int) {
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
    internal fun lanes(): Int =
        getSharedPreferences("reader", MODE_PRIVATE).getInt("lanes", DownloadService.DEFAULT_LANES)

    internal fun pickLanes() {
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
        // 版面表必须在建任何视图之前定下来：手写档和手机档的导航/页签高度不同。
        Md3.load(this)
        loadThemePrefs()
        loadReaderPrefs()
        DevLog.file = File(filesDir, "dev.log")
        BookmarkStore.file = File(filesDir, "bookmarks.json")
        DevLog.setEnabled(getSharedPreferences("reader", MODE_PRIVATE).getBoolean("devlog", false))
        // 幂等；无论开关开没开都挂上（崩溃现场是开关来不及开的那类问题），
        // 关着时只多一行 logcat。
        DevLog.installCrashHandler()
        // 开关状态会随升级保留，而 watchFrames 只在 toggleDevLog 里挂 ——
        // 不在这里补一句，升级后掉帧日志永远不会生效。
        if (DevLog.enabled) watchFrames()
        root = Widgets.screenRoot(this)
        setContentView(root)
        cleanDirtyJobs()
        ensureNotifyPermission()
        // S19：进程被回收后重建时回到原来那一屏，别让用户重搜书名。
        if (!restoreScreen(savedInstanceState)) showShelf()
        // 报告 P0-1：从下载通知点进来时落在进度屏，而不是首页。
        // 新建实例走 onCreate，已在前台则走 onNewIntent。
        openFromNotification(intent)
        syncSourcesOnStart()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromNotification(intent)
    }

    /**
     * 下载通知带 `EXTRA_BOOK_ID` 点进来时，直接落到那本书的进度屏。
     *
     * 报告 P0-1：以前通知点了只回首页，而首页那本下载中的书没有实时进度，
     * 用户只能自己找目录、找「下载中」。
     *
     * 只在服务确实在跑这本时才跳：作业停了还跳进度屏，屏上是一个不动的数，
     * 比留在首页更让人以为卡死。
     */
    private fun openFromNotification(intent: Intent?) {
        val id = intent?.getStringExtra(DownloadService.EXTRA_BOOK_ID)
            ?.takeIf { it.isNotBlank() } ?: return
        if ((screen as? Screen.Progress)?.info?.bookId == id) return
        if (DownloadService.runningBookId != id) return
        val job = runCatching { Downloader.restoreLight(jobsStore(), id) }.getOrNull() ?: return
        progressDone = job.count(ChapterStatus.DONE)
        progressFailed = job.count(ChapterStatus.FAILED)
        progressStartedAt = System.currentTimeMillis()
        progressBase = progressDone
        showProgress(BookInfo(job.bookId, job.name, job.author, job.chapters))
    }

    /**
     * 申请通知权限（S13）。
     *
     * manifest 早就声明了 `POST_NOTIFICATIONS`，但代码里从来没有申请过：
     * Android 13+ 首启后通知栏是空的，而进度屏明确叫用户「进度看通知」。
     * 下载是前台服务，没有这个权限连前台通知都看不到。
     */
    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val p = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching { requestPermissions(arrayOf(p), 4022) }
            .onFailure { DevLog.log("WatchDL", "通知权限申请失败：${it.message}") }
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
                // 不在书架就不弹：启动那几秒同步常落在「发现」屏上，一句 toast
                // 盖在空白列表上，看着就是「点进去没东西」的一部分。
                rebuildSources { if (screen is Screen.Shelf) toast(r.message) }
            }
        }
    }

    /** 把 multi 缓存作废并重读源目录。必须先重建才能拿到新库。 */
    private fun rebuildSources(then: () -> Unit = {}) {
        multiCache = buildMulti()
        then()
    }

    // ══════════════════ 屏幕 ══════════════════

    /** 目录页一次渲染多少章（M8）。1600 章的书分 16 段，不再一次建 6400 个 View。 */
    private val TOC_WINDOW = 100

    /** 简介卡片默认行数，点一下展开全文。 */
    private val INTRO_LINES = 3

    /** 搜索结果一次只建这么多张卡（理由见 showHits 里的注释）。 */
    internal val HITS_WINDOW = 60

    /** 搜索结果当前窗口起点。换关键词时不重置就会停在越界位置。 */
    internal var hitsWinStart = 0
    internal var seenHits: MutableSet<String> = mutableSetOf()
    /** 目录页当前窗口的起始章下标。进目录时按上次读到的那章重置。 */
    private var tocWinStart = 0
    /** 目录过滤词。空串 = 不过滤。存在 Activity 而不是 Screen 里：它只是屏内状态。 */
    internal var tocQuery = ""
    /**
     * 下一次进阅读页要补做的滚动。
     *
     * 书签记的是像素、正文搜索给的是字符偏移，两者都要等正文布局完成才能定位，
     * 所以统一成一个「拿到 ScrollView 后干什么」的回调，在 `showReader` 的 post 里消费一次。
     */
    internal var pendingJump: ((android.widget.ScrollView) -> Unit)? = null

    /** 匹配度打分，越小越靠前。 */


    /**
     * 换一批时把已经看过的条目从新结果里剔掉。
     *
     * 不同批的源会命中同一本书，不去掉的话换一批只能看到“又一张重复的书”。
     * 用书名+作者而不是 bookId —— 同书不同站的 id 完全不一样。
     */


    /** ③ 目录 + 下载。 */
    internal fun showToc(info: BookInfo, src: BookSource? = null) {
        // 源跟着屏幕走：预览/下载要从同一个源取正文，否则多源下会串源。
        val useSrc = src ?: tocSrc
        tocSrc = useSrc
        // M8：从别的屏进来时把窗口挪回上次读到的那章附近。
        // 翻页时调用方会自己设好 tocWinStart 再进来，这里只在换书或从别处进来时重置。
        val entering = (screen as? Screen.Toc)?.info?.bookId != info.bookId
        if (entering) {
            tocWinStart = (readProgress(info.bookId) - TOC_WINDOW / 2).coerceAtLeast(0)
        }
        setScreen(Screen.Toc(info, useSrc))
        root.removeAllViews()
        root.addView(Widgets.topBar(this, info.name, onBack = { goBackToPrevious() },
            trailing = listOf(
                "search" to { askTocQuery(info, useSrc) },
                "more" to { showTocMenu(info) },
            ),
            // legado 目录顶栏副标题就是「当前章 / 总章数」，一眼知道读到哪。
            subtitle = "${readProgress(info.bookId) + 1} / ${info.chapters.size}"))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)

        val free = info.chapters.count { !it.locked }
        val job = Downloader.restore(jobsStore(), info.bookId)
        val done = job?.count(ChapterStatus.DONE) ?: 0
        val failed = job?.count(ChapterStatus.FAILED) ?: 0

        // 简介卡片。默认只显 3 行，点一下展开 —— 手表屏幕就那么大，
        // 一整段简介会把目录挤到一屏之外。
        // 兜底的 abstractText 常是「...」这类占位，只判非空会画出一张只有省略号的卡片。
        // 有效字符不足 10 个就当没有简介，整张卡片不画。
        if (info.intro.trim().let { it.isNotBlank() && it.count { c -> c.isLetterOrDigit() } >= 10 }) {
            val ic = Widgets.card(this)
            val tv = Md3.text(this, info.intro, Md3.FS_SM, Md3.onSurfaceVariant)
            tv.maxLines = INTRO_LINES
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.setOnClickListener {
                val expanded = tv.maxLines != Int.MAX_VALUE
                tv.maxLines = if (expanded) Int.MAX_VALUE else INTRO_LINES
                tv.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
                tv.contentDescription = if (expanded) "书籍简介，点击收起" else "书籍简介，点击展开"
            }
            tv.contentDescription = "书籍简介，点击展开"
            ic.addView(tv)
            col.addView(ic)
        }

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
        // 差几章没下完也算完成态（P2-5）：1645/1650 的书，用户最想做的是导出，
        // 不该因为 5 章失败就把导出从主按钮挪到右边的 ⤓。finished 仍按「零失败」原义保留。
        val nearlyDone = !finished && job != null && job.total > 0 && done * 10 >= job.total * 9
        // 后台是否正在跑这本书。磁盘上的 state 靠不住：作业跑着的时候 job.json 一直是
        // RUNNING，restore 会把 RUNNING 改判成 PAUSED，于是下载中的页面误报「已中断」。
        val running = DownloadService.runningBookId == info.bookId
        // 熔断停下的书（P0-3-a）：状态仍是 PAUSED，光看 state 分不出是「系统杀了后台」
        // 还是「源坏了，自己停了」。所以熔断原因优先显示，下次开下时清掉。
        val abortHint = abortHints[info.bookId]
        val hint: String? = when {
            abortHint != null -> abortHint
            finished || running -> null
            job?.state == JobState.CANCELLED -> "上次下载已取消。点「继续下载」接着跑。"
            job?.state == JobState.PAUSED -> "上次下载已中断（系统回收了后台）。点「继续下载」接着跑。"
            else -> null
        }
        hint?.let { card.addView(Md3.text(this, it, Md3.FS_SM, Md3.error)) }
        val row = Md3.row(this).apply { setPadding(0, Md3.dp(this@MainActivity, 7), 0, 0) }
        // 完成态不再给一个点了只弹 toast 的填充按钮：整屏最显眼的控件必须有动作。
        val go = Widgets.button(this, when {
            finished || nearlyDone -> "导出 TXT"
            running -> "下载中…"
            done > 0 -> "继续下载"
            else -> "下载免费章"
        }, "filled") {
            when {
                finished || nearlyDone -> exportTxt(info)
                running -> toast("正在下载，进度看通知")
                else -> startDownload(info)
            }
        }
        row.addView(go, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
        // 差几章的书把导出提到主按钮后，右边这个按钮不能再是导出的复制品 ——
        // 否则那几章失败的永远没地方重试。它改成「续下」入口。
        val exp = Md3.squareButton(this, if (nearlyDone) "refresh" else "download", "tonal") {
            if (nearlyDone) startDownload(info) else exportTxt(info)
        }
        exp.contentDescription = if (nearlyDone) "继续下载" else "导出 TXT"
        row.addView(exp, LinearLayout.LayoutParams(Md3.dp(this, Md3.BTN), Md3.dp(this, Md3.BTN)).apply {
            leftMargin = Md3.dp(this@MainActivity, Md3.GAP)
        })
        card.addView(row)
        col.addView(card)

        col.addView(Widgets.sectionLabel(this, "章节"))
        // M8：以前一次性给全部章节建 View，1600 章约 6400 个，直接 ANR。
        // 现在只渲染一个以 [tocWinStart] 为起点的窗口，两端给「往前/往后」按钮，
        // 点了换窗口重绘整个列表 —— 不做增量插入，索引不会错。
        val winStart = tocWinStart.coerceIn(0, (info.chapters.size - 1).coerceAtLeast(0))
        val winEnd = (winStart + TOC_WINDOW - 1).coerceAtMost(info.chapters.lastIndex)
        // 过滤时不走窗口：命中数已被 ReaderFind 截住，直接给前 TOC_WINDOW 条。
        // 过滤态下不提供「前后还有 N 章」—— 那是窗口概念，和搜索结果是两回事。
        val found = if (tocQuery.isBlank()) null
                    else ReaderFind.titles(info.chapters.map { it.title }, tocQuery)
        if (found != null) {
            val head = Md3.row(this)
            head.addView(Md3.ellipsize(Md3.text(this, "「$tocQuery」命中 ${found.size} 章",
                Md3.FS_SM, Md3.onSurfaceVariant)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(Md3.text(this, "清空", Md3.FS_SM, Md3.primary, bold = true).apply {
                isClickable = true
                setPadding(Md3.dp(this@MainActivity, 8), 0, Md3.dp(this@MainActivity, 4), 0)
                contentDescription = "清空目录过滤"
                setOnClickListener { tocQuery = ""; showToc(info, useSrc) }
            })
            col.addView(head)
        }
        // 列表锚点必须在过滤头之后取：前面每加一个子项，下标就往后错一位。
        val listAnchor = col.childCount
        val shown = (found ?: (winStart..winEnd).toList()).take(TOC_WINDOW)
        if (found == null) {
            if (winStart > 0) {
                col.addView(Widgets.button(this, "↑ 前面还有 $winStart 章", "text") {
                    tocWinStart = (winStart - TOC_WINDOW).coerceAtLeast(0)
                    showToc(info, src)
                })
            }
        }
        for (i in shown) {
            val ch = info.chapters[i]
            val st = job?.statuses?.get(i)
            val (mark, color) = when {
                ch.locked -> "锁" to Md3.onSurfaceVariant
                st == ChapterStatus.DONE -> "✓" to Md3.ok
                st == ChapterStatus.FAILED -> "✗" to Md3.error
                else -> null to Md3.onSurfaceVariant
            }
            col.addView(Widgets.chapterItem(this, i + 1, ch.title, mark, color,
                highlight = i == lastRead) {
                if (ch.locked) toast("这是付费章，取不到正文") else preview(info, i)
            })
        }
        if (found != null) {
            if (shown.isEmpty()) {
                col.addView(Widgets.emptyState(this, "search", "没有标题含「$tocQuery」的章"))
            }
        } else {
            val rest = info.chapters.size - 1 - winEnd
            if (rest > 0) {
                col.addView(Widgets.button(this, "↓ 后面还有 $rest 章", "text") {
                    tocWinStart = winEnd + 1
                    showToc(info, src)
                })
            }
        }
        // 直接滚到上次读到的那章：五百章的书手动滑到第 300 章是不可能的事。
        val jumpAt = shown.indexOf(lastRead)
        if (jumpAt >= 0) {
            val target = col.getChildAt(listAnchor + jumpAt)
            body.post { target?.let { body.smoothScrollTo(0, it.top) } }
        }
        root.addView(body)
    }

    /** ④ 下载中。 */
    /**
     * 进度屏。**已经在这一屏时不重建**（M8）。
     *
     * 以前每次进来都 `removeAllViews()` 全套重建，还在这里读 `job.json`
     * 和整个 `chapters.jsonl`。进度广播每章发两次，一本 3000 章的书
     * 就是每秒数次整屏重建 + 数 MB 读盘，手表上直接掉帧。
     * 现在只在换书或首次进入时重建，之后走 [refreshProgressLive] 只改文字。
     */
    internal fun showProgress(info: BookInfo) {
        val same = (screen as? Screen.Progress)?.info?.bookId == info.bookId &&
            progressNum != null
        setScreen(Screen.Progress(info))
        if (same) { refreshProgressLive(); return }
        progressNum = null
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
        val delta = (progressDone - progressBase).coerceAtLeast(0)
        val speed = if (elapsed > 0 && delta > 0) " · ${delta * 60 / elapsed} 章/分" else ""
        val remain = if (delta > 0 && total > progressDone) " · 约 ${(total - progressDone) * elapsed / delta} 秒" else ""
        card.addView(Md3.text(this, "已下载 $progressDone 章，失败 $progressFailed 章$speed$remain", Md3.FS_SM, Md3.onSurfaceVariant))
        // shortcut: 进度屏首次进入时在主线程完整读一次，用来列出失败章。
        // 按章数线性增长：167 章实测 10.6 ms，3000 章约 190 ms（超一帧）。
        // 真要治得把失败章列表挪进后台线程或单独存文件，等有 3000 章的真实抱怨再说。
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

        // M8：留下三个要随进度变的 View，广播时只改它们。
        progressNum = num
        progressCard = card
        progressSub = card.getChildAt(2) as? android.widget.TextView
        progressTotal = total
    }

    /** 进度屏的三处可变 View。为 null 表示进度屏没在显示。 */
    private var progressNum: android.widget.TextView? = null
    private var progressCard: LinearLayout? = null
    private var progressSub: android.widget.TextView? = null
    private var progressTotal = 0

    /**
     * 只刷新进度文字与进度条，不重建视图（M8）。
     *
     * 不读盘：`Downloader.restore` 留给「这次下载结束」那一次，
     * 每章两次的刷新里读整个 chapters.jsonl 是卡顿的主因。
     */
    private fun refreshProgressLive() {
        val num = progressNum ?: return
        val card = progressCard ?: return
        val sub = progressSub ?: return
        val total = progressTotal
        num.text = android.text.SpannableStringBuilder("$progressDone").apply {
            append("  / $total")
        }
        // 进度条是两条带权重的 View，改比例得重建 —— 只有这一个控件，代价可忽略。
        card.removeViewAt(1)
        card.addView(Widgets.progressBar(this, if (total > 0) progressDone.toFloat() / total else 0f, 5), 1)
        progressSub = card.getChildAt(2) as? android.widget.TextView
        val elapsed = if (progressStartedAt > 0)
            ((System.currentTimeMillis() - progressStartedAt) / 1000).coerceAtLeast(1) else 0
        val delta = (progressDone - progressBase).coerceAtLeast(0)
        val speed = if (elapsed > 0 && delta > 0) " · ${delta * 60 / elapsed} 章/分" else ""
        val remain = if (delta > 0 && total > progressDone)
            " · 约 ${(total - progressDone) * elapsed / delta} 秒" else ""
        sub.text = "已下载 $progressDone 章，失败 $progressFailed 章$speed$remain"
    }

    // 发现榜单的缓存状态。屏代码在 ui/source/SourceScreen.kt，状态留在这里
    // —— 扩展属性不能有初始化器，搬到那边就没法直接持有。

    /**
     * 当前可用的榜单。云端配置拉一次就缓存到内存，不每次翻页都去请求。
     *
     * 拉不到云端时用内置清单，所以发现页永远不会因此打不开。
     */
    internal var rankCache: List<RankConfig.Rank>? = null

    /**
     * 云端刷新**成功过**就不再重复问镜像。
     *
     * 不能在发起前置位：`bg` 的任务会随切屏被 [cancelTasks] interrupt，
     * 那时 `then` 根本不执行。若发起前就置真，用户点开发现页、立刻返回，
     * 本次进程就再也不会刷新云端配置了 —— 内置表里的榜会一直用到重启。
     */
    internal var rankRefreshed = false

    /** 已有一个刷新任务在跑。**发起时置真、回调里清假**，与 [rankRefreshed] 分工不同。 */
    internal var rankRefreshing = false



    // ══════════════════ 动作 ══════════════════




    /**
     * 发现页的点击入口。
     *
     * 番茄榜的书带着真实 `bookId`，且[FanqieApp]能用它直接拉目录，
     * 所以**不搜全量源**：搜索会在 32 条 legado 源里再跑一遍，十几秒起步，
     * 而且搜出来的第一本未必是榜上这本。需求就是要番茄的书走番茄源。
     *
     * 起点榜没有对应的[BookSource]，仍走多源搜索。
     */
    internal fun openDiscoverHit(hit: BookHit) {
        rememberReturn()
        if (hit.sourceName != "番茄" || hit.bookId.isBlank()) {
            doSearch(hit.name)
            return
        }
        loadToc(hit.name, hit.author, listOf(hit))
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
    internal fun loadToc(name: String, author: String, candidates: List<BookHit>, tried: Int = 0) {
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
            val tocAt = System.currentTimeMillis()
            val got = src.toc(hit.bookId, hit.name, hit.author).let { t ->
                // 简介：书源自己的 ruleBookInfo.intro 优先；它为空时用手上已有的摘要
                // （搜索/榜单带回来的 abstractText），不再发一次请求。
                if (t.intro.isNotBlank()) t else t.copy(intro = hit.abstractText.trim())
            }
            DevLog.log("WatchDL", "目录「${hit.sourceName}」${got.chapters.size} 章 ${System.currentTimeMillis() - tocAt}ms")
            // 目录非空但正文规则已坏的书源很常见（实测 m.rulianshi.la 每章只回 21 字）。
            // 以前直接进目录页，用户下完 320 章才发现全书空白。
            // 这里先试一章正文，不合格就换下一个源。
            if (got.chapters.isNotEmpty()) {
                // 试前 3 章：有些书第一章是「作者的话」之类短章节，只看一章会误判整条源。
                val probes = got.chapters.filter { !it.locked }.take(3)
                // 探测异常以前被 runCatching 吃掉、`?: 0` 折成 0 字，日志只写「正文只有 0 字」
                // ——真实原因（签名错、接口改、资源包缺失）一点都看不到。
                var probeErr: Throwable? = null
                val best = probes.maxOfOrNull { ch ->
                    runCatching { src.content(ch.itemId, hit.bookId) }
                        .onFailure { probeErr = it }
                        .getOrNull()
                        ?.let { Downloader.bodyChars(it) } ?: 0
                } ?: Int.MAX_VALUE
                if (best < Downloader.MIN_BODY_CHARS) {
                    val why = probeErr?.let { "（探测异常：${it.javaClass.simpleName}: ${it.message}）" } ?: ""
                    DevLog.log("WatchDL", "源「${hit.sourceName}」正文只有 $best 字，换源$why")
                    return@bg null
                }
            }
            src to got
        }) { res ->
            val pair = res.getOrNull()
            if (pair != null && pair.second.chapters.isNotEmpty()) {
                if (tried > 0) toast("已换到 ${hit.sourceName}")
                // N6：目录被预算/超时截断时必须说一声。不说的话用户以为
                // 目录就这么长，下载完标 DONE，书悄悄少几十章。
                if (pair.second.truncated) {
                    toast("目录可能不完整（${pair.second.chapters.size} 章），可稍后重试")
                }
                // SourceEngine.toc 固定返回空 name/author（书源规则里没有稳定书名），
                // 不回填的话作业名、通知标题、导出文件名全变成「未命名」。
                showToc(
                    pair.second.copy(
                        name = pair.second.name.ifBlank { hit.name },
                        author = pair.second.author.ifBlank { hit.author },
                    ),
                    pair.first,
                )
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
        col.addView(Widgets.emptyState(this, "warning", msg))
        col.addView(Widgets.button(this, "换源重试", "filled") {
            if (candidates.isNotEmpty()) loadToc(name, "", candidates) else toast("没有可重试的源")
        }, Md3.lpDp(this, null, Md3.BTN).apply { topMargin = Md3.dp(this@MainActivity, Md3.GAP) })
        col.addView(Widgets.button(this, "返回搜索", "text") { goBackToPrevious() },
            Md3.lpDp(this, null, Md3.BTN).apply { topMargin = Md3.dp(this@MainActivity, Md3.GAP) })
        root.addView(body)
    }

    /** 从作业反查书籍，恢复目录之后打开。 */
    internal fun openBook(bookId: String, name: String, author: String) {
        // 书架/最近下载进来的，返回时回到首页（对应 rememberReturn=null 的默认行为）。
        rememberReturn()
        val job = Downloader.restore(jobsStore(), bookId)
        if (job != null) {
            showToc(BookInfo(bookId, job.name.ifBlank { name }, job.author.ifBlank { author }, job.chapters))
            return
        }

        setScreen(Screen.Toc(BookInfo(bookId, name, author, emptyList())))
        root.removeAllViews()
        root.addView(Widgets.topBar(this, name, onBack = { showShelf() }))
        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        (Widgets.contentColumn(body)).addView(Widgets.emptyState(this, "⏳", "正在取目录…"))
        root.addView(body)

        val owner: Screen = Screen.Toc(BookInfo(bookId, name, author, emptyList()))
        bg(owner, { source.toc(bookId, name, author) }) { res ->
            res.onSuccess { showToc(it, source) }
                .onFailure { toast("目录失败：${it.message}"); showShelf() }
        }
    }


    internal fun startDownload(info: BookInfo) {
        // S14：以前用内存里的 `downloading` 标志，唯一复位点在 [progressReceiver] 里，
        // 而接收器只在 onResume~onPause 之间注册。点下载 → 退回目录 → 按 Home（注销接收器）
        // → 服务被杀 → 回 App，标志还是 true，点「继续下载」直接 return，
        // 界面停在「下载中 0 章」再不动，只能杀进程。
        // 现在只看服务端的真实状态，进程被回收也不会残留。
        val running = DownloadService.runningBookId
        if (running != null) {
            // 已经在下**另一本**：不能悄悄排队，也不能假装开始了，
            // 更不能把这本书的进度屏顶出来（会显示别的书的章数）。
            if (running != info.bookId) { toast("正在下载另一本，请稍候"); return }
            showProgress(info)
            return
        }
        // M8：主线程只读 job.json。restore 会解析整个 chapters.jsonl。
        abortHints.remove(info.bookId)
        progressDone = Downloader.restoreLight(jobsStore(), info.bookId)?.count(ChapterStatus.DONE) ?: 0
        progressFailed = 0
        progressStartedAt = System.currentTimeMillis()
        progressBase = progressDone
        showProgress(info)
        DownloadService.start(this, info, freeOnly = true)
    }

    private fun showTocMenu(info: BookInfo) {
        val marks = BookmarkStore.forBook(info.bookId).size
        Widgets.menuDialog(this, null, listOf(
            "跳章" to { showJumpDialog(info, tocSrc) },
            "书签（$marks）" to { showMarks(info) },
            "导出 TXT" to { exportTxt(info) },
        )).show()
    }

    /**
     * 跳章：滑杆选大范围，下面的数字框填精确章号，两者互相跟随。
     *
     * 189 dp 屏上目录一次只画 [TOC_WINDOW] 章，五百章的书想读到第 300 章
     * 得点十几次「后面还有 N 章」。legado 的目录菜单也带跳章。
     */
    private fun showJumpDialog(info: BookInfo, src: BookSource?) {
        val total = info.chapters.size
        if (total <= 0) { toast("这本书还没有章节"); return }
        val start = (readProgress(info.bookId) + 1).coerceIn(1, total)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Md3.dp(this@MainActivity, 12), Md3.dp(this@MainActivity, 2),
                       Md3.dp(this@MainActivity, 12), Md3.dp(this@MainActivity, 2))
        }
        val dialog = Widgets.styleDialog(android.app.AlertDialog.Builder(this)
            .setTitle("跳到第几章").setView(box).create())

        // 章名跟着选中的章号一起显示：只看数字选章等于闭眼跳。
        val title = Md3.ellipsize(Md3.text(this, "", Md3.FS_SM, Md3.onSurfaceVariant))
        fun paintTitle(n: Int) {
            val t = info.chapters.getOrNull(n - 1)?.title.orEmpty()
            title.text = if (t.isBlank()) "第 $n 章" else "第 $n 章 · $t"
        }

        var pick = start
        // 两个控件互相写，不设守卫会来回触发、滑杆跳字。
        var syncing = false

        val num = EditText(this).apply {
            hint = "1 ~ $total"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText("$start")
            setSelection(text.length)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Md3.FS_MD)
            setTextColor(Md3.onSurface)
            setHintTextColor(Md3.onSurfaceVariant)
            background = Md3.bg(Md3.surface4, Md3.dp(this@MainActivity, 8).toFloat())
            setPadding(Md3.dp(this@MainActivity, 10), 0,
                       Md3.dp(this@MainActivity, 10), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }

        val slider = Widgets.sliderRow(this, "拖动选章", 1f, total.toFloat(), start.toFloat(),
            steps = (total - 1).coerceAtLeast(1),
            fmt = { v -> "第 ${v.toInt()} 章" }) { v ->
            pick = v.toInt().coerceIn(1, total)
            paintTitle(pick)
            if (!syncing) {
                syncing = true
                num.setText("$pick")
                num.setSelection(num.text.length)
                syncing = false
            }
        }
        box.addView(slider)
        box.addView(num, Md3.lpDp(this, null, Md3.BTN))
        box.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = Md3.dp(this@MainActivity, Md3.GAP / 2) })

        // 滑杆行的子项：0 = 标签行，1 = SeekBar。这里只借它同步位置。
        val bar = slider.getChildAt(1) as? android.widget.SeekBar
        num.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (syncing) return
                val v = s?.toString()?.toIntOrNull() ?: return
                if (v < 1 || v > total) return
                pick = v
                paintTitle(v)
                syncing = true
                bar?.progress = (v - 1) * 100
                syncing = false
            }
        })
        paintTitle(start)

        fun go() {
            val idx = (num.text.toString().toIntOrNull() ?: pick).coerceIn(1, total) - 1
            dialog.dismiss()
            // 先把目录窗口挪到目标章附近：从阅读页返回时直接落在这一页。
            tocWinStart = (idx - TOC_WINDOW / 2).coerceAtLeast(0)
            val ch = info.chapters[idx]
            if (ch.locked) { toast("第 ${idx + 1} 章是付费章，取不到正文"); showToc(info, src) }
            else preview(info, idx)
        }
        num.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { go(); true } else false
        }

        val row = Md3.row(this).apply { setPadding(0, Md3.dp(this@MainActivity, 8), 0, 0) }
        row.addView(Widgets.button(this, "取消", "outline") { dialog.dismiss() },
            LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
        row.addView(Widgets.button(this, "跳过去", "filled") { go() },
            LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f).apply {
                leftMargin = Md3.dp(this@MainActivity, Md3.GAP)
            })
        box.addView(row)

        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
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
    internal fun showSettings() {
        setScreen(Screen.Settings)
        root.removeAllViews()
        root.addView(Widgets.topBar(this, "设置", onBack = { goBackToPrevious() }))

        val body = Widgets.scrollBody(this, crownStep, crownGlide)
        val col = Widgets.contentColumn(body)

        col.addView(Widgets.sectionLabel(this, "外观"))
        themeChips(col)

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

    /**
     * 主题选择：六套两行三列。
     *
     * 用芯片而不是一行下拉：换主题后颜色立刻变，得看得见才选得动。
     * 选中态沿用全局段式风格（浅色填充 + 深字）。
     */
    private fun themeChips(col: LinearLayout) {
        Palette.all.chunked(3).forEach { group ->
            val row = Md3.row(this)
            group.forEachIndexed { i, p ->
                val on = p.id == Md3.palette.id
                val chip = Md3.text(this, p.name, Md3.FS_SM,
                    if (on) Md3.onPrimary else Md3.onSurfaceVariant, bold = on).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    background = Md3.ripple(this@MainActivity,
                        if (on) Md3.primary else Color.TRANSPARENT,
                        Md3.dp(this@MainActivity, 13).toFloat(),
                        if (on) null else Md3.outlineVariant)
                    setOnClickListener { pickTheme(p.id) }
                    contentDescription = "主题 ${p.name}" + if (on) "，当前" else ""
                }
                row.addView(chip, LinearLayout.LayoutParams(0, Md3.dp(this, 30), 1f).apply {
                    leftMargin = if (i == 0) 0 else Md3.dp(this@MainActivity, 4)
                })
            }
            col.addView(row, Md3.lp().apply { bottomMargin = Md3.dp(this@MainActivity, 2) })
        }
        // 下一节标题紧跟其后，给一点呼吸。
        col.addView(Md3.text(this, "", Md3.FS_SM, Md3.onSurfaceVariant),
            Md3.lpDp(this, null, 2))
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
        if (on) watchFrames()     // 只在开着时挂：关着不能给每一帧加一个回调
        showSettings()          // 重画，导出/清空按钮跟着开关出现或消失
    }

    @Volatile private var frameWatcherOn = false

    /**
     * 掉帧监听：相邻两帧间隔超 200ms 记一行，带当前屏名。
     *
     * 手表上「点了一下没反应」大多是主线程被卡，而卡在哪里只能靠这个量。
     * 开关关掉后不再续挂（否则每帧一个回调，本身就是负担）。
     */
    private fun watchFrames() {
        if (frameWatcherOn) return
        frameWatcherOn = true
        val choreographer = android.view.Choreographer.getInstance()
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            private var last = 0L
            override fun doFrame(frameTimeNanos: Long) {
                if (!DevLog.enabled) { frameWatcherOn = false; return }
                val prev = last
                last = frameTimeNanos
                if (prev != 0L) {
                    val ms = (frameTimeNanos - prev) / 1_000_000
                    if (ms > 200) DevLog.log("UI", "掉帧 ${ms}ms（$screenName）")
                }
                choreographer.postFrameCallback(this)
            }
        })
    }

    /** 崩溃/掉帧日志里用的屏名：只记枚举名，不把书名之类用户内容写进去。 */
    private val screenName: String get() = runCatching { screen.javaClass.simpleName }.getOrDefault("?")

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
        // 导文件而不是导 ring：ring 只有 3000 条，前面的上下文早被挤掉了。
        val body = DevLog.exportText()
        val lines = body.lines().count { it.isNotBlank() }
        runCatching {
            out.writeText(devLogHeader(lines) + "\n" + body)
        }.onFailure { toast("写日志失败：${it.message}"); return }
        val published = publishToDocuments(out)
        if (published == null) {
            toast("已写入 ${out.name}，但无法存到文档")
            return
        }
        toast("已存到文档/$published")
    }

    /**
     * 导出文件头：版本、机型、内存、存储、网络、时间。
     *
     * 以前导出的文件只有日志行，靠它完全无法判断「是手表内存不足被杀」
     * 还是「站点慢」——这些环境量必须先写清。
     */
    private fun devLogHeader(lines: Int): String {
        val vi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val mem = android.app.ActivityManager.MemoryInfo()
        runCatching { getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mem) }
        val st = android.os.StatFs(filesDir.absolutePath)
        val net = runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)
                ?.activeNetworkInfo?.typeName ?: "无"
        }.getOrDefault("未知（无权限）")
        return listOf(
            "WatchReader ${vi?.versionName ?: "?"} (code ${vi?.longVersionCode ?: 0})",
            "机型 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "可用内存 ${mem.availMem / 1024 / 1024}MB / ${mem.totalMem / 1024 / 1024}MB",
            "可用存储 ${st.availableBytes / 1024 / 1024}MB / ${st.totalBytes / 1024 / 1024}MB",
            "网络 $net · 导出 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} · $lines 行",
        ).joinToString("\n")
    }

    /** 版本号从 PackageManager 读，避免在代码里再写一份与 build.sh 不同步的常量。 */
    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    internal fun showJobMenu(job: BookJob) {
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
                showShelf()
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
                // 报告 P0-2：先取消再删。以前直接删目录，后台线程还在跑，
                // 它每章落盘一次就会把目录连同 job.json 一起建回来 —— 书「诈尸」。
                if (DownloadService.runningBookId == job.bookId) DownloadService.cancel(this)
                thread {
                    var waited = 0
                    while (DownloadService.runningBookId == job.bookId && waited < 3000) {
                        Thread.sleep(50)
                        waited += 50
                    }
                    // 等不到也不要紧：墓碑会让后面所有落盘都落空。
                    val gone = jobsStore().markDeleted(job.bookId)
                    runOnUiThread {
                        if (gone) {
                            toast("已删除")
                            showShelf()
                        } else toast("删除失败：目录仍被占用")
                    }
                }
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
            // MediaStore 对同名 DISPLAY_NAME 会自动加「(1)」，连点两次导出就在文档里
            // 堆两份 1 MB 副本。所以先查同名条目，有就覆盖写，没有才新建。
            val relPath = android.os.Environment.DIRECTORY_DOCUMENTS + "/"
            val old = contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relPath, src.name), null,
            )?.use { c ->
                if (c.moveToFirst()) android.content.ContentUris.withAppendedId(uri, c.getLong(0)) else null
            }
            val target = old ?: contentResolver.insert(uri, values) ?: return null
            // "wt"：截断后重写，否则覆盖短内容时旧文件尾巴会留下来。
            val ok = contentResolver.openOutputStream(target, "wt")?.use { out ->
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

    internal fun exportTxt(info: BookInfo) {
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
                // 熔断原因跟着广播回来（P0-3-a）。熔断时服务会发 finished=true，
                // 界面回到目录页——原因要写在那边，进度屏已经没了。
                val abort = intent.getStringExtra(DownloadService.EXTRA_ABORT)
                if (abort != null && info != null) abortHints[info.bookId] = abort
                if (finished) {
                    toast(abort ?: "下载结束：成功 $progressDone，失败 $progressFailed")
                    // 结束时书架上的进度数字要变成最终值。
                    if (info != null) showToc(info)
                    else if (screen is Screen.Shelf) showShelf()
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

    /**
     * 记住当前屏，进程被系统回收后能回来（S19）。
     *
     * `configChanges` 挡得住旋转，挡不住内存回收：读到第 300 章，切出去十分钟被杀，
     * 回来落在首页，要重新搜书名进目录才轮得到「继续读」。
     *
     * 只存 bookId 不存 [BookInfo]：目录动辄几千章，
     * N9 量过 6000 章就是 1 MB，超过 Binder 的 1 MB 上限会直接崩。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        when (val s = screen) {
            is Screen.Reader -> {
                outState.putString("screen", "reader")
                outState.putString("bookId", s.info.bookId)
                outState.putInt("index", s.index)
            }
            is Screen.Toc -> {
                outState.putString("screen", "toc")
                outState.putString("bookId", s.info.bookId)
            }
            is Screen.Progress -> {
                outState.putString("screen", "progress")
                outState.putString("bookId", s.info.bookId)
            }
            else -> {}
        }
    }

    /**
     * 重建被回收前的屏。目录从 `job.json` 读回，读不到就退到书库首页。
     *
     * 返回 true 表示已恢复，[onCreate] 不再走 `showShelf`。
     */
    private fun restoreScreen(state: Bundle?): Boolean {
        val kind = state?.getString("screen") ?: return false
        val bookId = state.getString("bookId")?.takeIf { it.isNotBlank() } ?: return false
        // M8：恢复走轻量读法。这是 onCreate 主线程，完整读法要解析整个
        // chapters.jsonl（真实数据 1.5 MB / 167 章，实测 12 ms；3000 章会到百毫秒级），
        // 正好是这次要消掉的卡顿。章节表本身完整，恢复阅读够用。
        val job = runCatching { Downloader.restoreLight(jobsStore(), bookId) }.getOrNull()
            ?: return false
        if (job.chapters.isEmpty()) return false
        val info = BookInfo(job.bookId, job.name, job.author, job.chapters)
        when (kind) {
            "reader" -> {
                val idx = state.getInt("index", readProgress(bookId)).coerceIn(0, info.chapters.lastIndex)
                preview(info, idx)
            }
            "toc" -> showToc(info)
            "progress" -> {
                // S19 × S14：只有服务真在跑这本书才回进度屏。
                // 进程被回收时服务也一起没了，硬跳回去会停在「下载中」永远不动 ——
                // 比落回目录更糟，用户会以为卡死。落回目录还能点「继续下载」。
                if (DownloadService.runningBookId != info.bookId) return false
                showProgress(info)
            }
            else -> return false
        }
        return true
    }

    @Deprecated("手表没有返回手势，按返回键回上一层")
    /**
     * 配色、字体缩放这些配置变了要重画：manifest 里 configChanges 拦下了系统重建。
     * 屏宽也可能变了（分屏、外接屏），所以先重选版面表再重画。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Md3.load(this)
        when (val s = screen) {
            is Screen.Toc -> showToc(s.info)
            is Screen.Reader -> showReader(s.info, s.index, s.text, s.src)
            // 传 null 会丢掉批次信息，「换一批源」按钮就没了；
            // 但 Result 需要真实统计，屏上只记了批次大小，
            // 所以改用 batch 参数直接传。
            is Screen.Hits -> showHits(s.keyword, s.all, offset = s.offset,
                batch = s.searchedSources)
            is Screen.Market -> showMarket(s.page, s.hits)
            is Screen.Sources -> showSources()
            is Screen.Progress -> showProgress(s.info)
            Screen.Settings -> showSettings()
            // 配置变更重建**不刷新**：这里只是重画，联网会让旋转/展开后卡一下。
            is Screen.Discover -> showDiscover(s.site, s.gender, s.rankKey)
            Screen.Shelf -> showShelf()
            is Screen.Marks -> showMarks(s.info)
            Screen.My -> showMy()
            is Screen.Detail -> showDetail(s.info, s.src)
        }
    }

    override fun onBackPressed() {
        when (val s = screen) {
            // 书架是根栏：在这一栏按返回才退出应用。
            Screen.Shelf -> super.onBackPressed()
            // 另外两栏按返回回书架栏，不退出 —— 手势返回在手表上很容易误触。
            is Screen.Discover -> showShelf()
            Screen.My -> showShelf()
            is Screen.Toc -> goBackToPrevious()
            is Screen.Hits -> goBackToPrevious()
            is Screen.Reader -> showToc(s.info)
            is Screen.Sources -> goBackToPrevious()
            is Screen.Market -> showSources()
            is Screen.Progress -> showToc(s.info)
            Screen.Settings -> goBackToPrevious()
            is Screen.Marks -> showToc(s.info)
            is Screen.Detail -> goBackToPrevious()
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

    internal fun jobsDir(): File = File(filesDir, "books").apply { mkdirs() }

    /** 导出目录。**不能放进 jobsDir** —— 它会被当成一个下载作业。 */
    private fun exportDir(): File = File(filesDir, "export").apply { mkdirs() }
    internal fun jobsStore(): JobStore = JobStore(jobsDir())

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
    internal fun extractAssets() {
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


    internal fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 是否 debug 包。内部路径、诊断信息只给开发者看（P1-4）。 */
    internal fun isDebugBuild(): Boolean =
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

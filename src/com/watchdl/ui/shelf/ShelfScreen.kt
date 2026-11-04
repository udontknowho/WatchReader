package com.watchdl.ui.shelf

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.attachTabs
import com.watchdl.ui.reader.preview
import com.watchdl.ui.my.showCache
import com.watchdl.ui.my.showExportList
import com.watchdl.ui.my.showLogs
import com.watchdl.ui.search.askKeyword
import com.watchdl.ui.source.showSources
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File

/**
 * 书架屏。参考：`~/ref/legado-novel` @2ed09db 3.26.21 的
 * `ui/main/bookshelf/BookshelfScreen.kt`（顶栏 + 多选）与 `BookItem.kt`（卡片）。
 *
 * 写成 MainActivity 的扩展函数而不是独立类：它要用 Activity 的
 * root / setScreen / crownStep 等导航与滚动状态，扩展函数能直接取，不必再搭一层壳。
 *
 * 整屏不带返回箭头，也不靠「首页」转一道 —— 它是应用首页（legado 的 Bookshelf）。
 */

/**
 * 书架的选择模式状态。
 *
 * 放文件级 object 而不是往 MainActivity 加字段：选择模式只属于这一屏，
 * 它不该跟着 Activity 走一辈子（离开书架再回来要回到干净态）。
 */
internal object ShelfUi {
    /** 是否处于多选模式（legado `isEditMode`）。 */
    var selecting = false

    // ── 布局与显示项（legado `BookshelfSettings` 里能落到 189 dp 上的那几条）──
    //
    // 落盘走既有的全局偏好桶 SharedPreferences("reader")：主题、字号、表冠灵敏度
    // 都在这一个桶里，不再新造存储机制。legado 那边这些开关存在 BookshelfSettings。

    /** 封面墙 / 列表两档。legado `bookshelfLayoutModePortrait`（0 列表、1 网格）。 */
    var grid = true

    /** 排序键，见 [ShelfSort]。 */
    var sort = ShelfSort.RECENT

    /** 排序方向：true = 倒序（legado `bookshelfSortOrder` = 1）。 */
    var sortDesc = true

    /** 书名最多几行（legado `bookshelfTitleMaxLines`，我们只给 1 / 2 两档）。 */
    var titleLines = 2

    /** 封面左下角来源标签（legado `showTip`）。 */
    var showTag = true

    /** 有新章的圆点（legado `showUnreadNew`）。 */
    var showNew = true

    /** 「更新目录」跑着时的一行提示；null = 不在更新。 */
    var updating: String? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
        grid = p.getBoolean("shelfGrid", true)
        sort = p.getInt("shelfSort", ShelfSort.RECENT)
        sortDesc = p.getBoolean("shelfSortDesc", ShelfSort.defaultDesc(sort))
        titleLines = p.getInt("shelfTitleLines", 2).coerceIn(1, 2)
        showTag = p.getBoolean("shelfShowTag", true)
        showNew = p.getBoolean("shelfShowNew", true)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("reader", Context.MODE_PRIVATE).edit()
            .putBoolean("shelfGrid", grid)
            .putInt("shelfSort", sort)
            .putBoolean("shelfSortDesc", sortDesc)
            .putInt("shelfTitleLines", titleLines)
            .putBoolean("shelfShowTag", showTag)
            .putBoolean("shelfShowNew", showNew)
            .apply()
    }

    /** 选中的 bookId。用 LinkedHashSet：顺序按点击先后，删除确认里列书名才稳定。 */
    val selected = linkedSetOf<String>()

    /** 进多选模式，并把长按的那本先选上（legado 长按即选中）。 */
    fun enter(bookId: String) {
        selecting = true
        selected.clear()
        if (bookId.isNotEmpty()) selected.add(bookId)
    }

    fun exit() {
        selecting = false
        selected.clear()
    }
}

/** 书架行的进度尾巴。副标题统一两段，字段数固定，列表才能纵向扫读。 */
internal fun MainActivity.progressTail(done: Int, total: Int, failed: Int): String =
    if (failed > 0) "$done/$total 章 · 失败 $failed" else "$done/$total 章"

/**
 * 书架一行：作业快照 + 封面左下角标签（书源名，没有就给「本地」）。
 *
 * [updatedAt] 是 job.json 里的「最近一次写入」时刻，排序「按更新时间」用它；
 * [newCount] 是上次「更新目录」查出的新章数，卡片上的圆点用它。
 */
internal class ShelfRow(
    val job: BookJob,
    val sourceTag: String,
    val updatedAt: Long = 0L,
    val newCount: Int = 0,
)

/**
 * 书架数据：已下载的书，最近读过的在前。
 *
 * 用 [Downloader.restoreLight]：只要书名、作者、章节数与进度，
 * 不解析 chapters.jsonl（真实数据 1.5 MB，实测 12 ms，且是主线程）。
 *
 * 一次读盘取三样：bookId（排序要用原始值）、书源名（封面左下角标签）、
 * 以及作业快照。分开读会把每个 job.json 读两遍。
 */
internal fun MainActivity.shelfRows(): List<ShelfRow> {
    val dirs = jobsDir().listFiles()
        ?.filter { it.isDirectory && File(it, "job.json").isFile }
        ?: return emptyList()
    // 用 job.json 里的**原始 bookId**，不能用目录名 ——
    // 目录名是 safeDirName 净化过的，再拿去 restore 会被二次净化，对不上。
    // 排序只用到 bookId 与时间，所以先排完再 restore —— 看不见的卡片不必付一次读盘。
    val entries = dirs.mapNotNull { dir ->
        val raw = runCatching { File(dir, "job.json").readText() }.getOrNull()
            ?: return@mapNotNull null
        val id = TinyJson.path(raw, "bookId").orEmpty()
        if (id.isBlank()) return@mapNotNull null
        // 老存档没有书源字段（`Downloader.persist` 只写书名/作者/章节/进度），
        // 读不到就落到「本地」，与 legado `bookTypeLabel` 的兜底一致。
        val tag = shelfSourceTag(
            TinyJson.path(raw, "originName") ?: TinyJson.path(raw, "sourceName"))
        Triple(dir, id, tag)
    }
    // 排序键：书名/作者从轻量快照拿，时间从 job.json 的 updatedAt 与阅读记录拿。
    // 四样都在这一轮读盘里带出，排序不再回读任何文件 —— 快照同时缓存下来，
    // 后面组行直接取，一本书只 `restoreLight` 一次（3000 章的 job.json 解析不便宜）。
    val lights = LinkedHashMap<String, BookJob>(entries.size * 2)
    val sortKeys = HashMap<String, ShelfSortKey>(entries.size * 2)
    for ((dir, id, _) in entries) {
        val light = Downloader.restoreLight(jobsStore(), id) ?: continue
        lights[id] = light
        val stamp = runCatching { File(dir, "job.json").readText() }
            .getOrNull()?.let { TinyJson.path(it, "updatedAt")?.toLongOrNull() }
            ?: dir.lastModified()
        sortKeys[id] = ShelfSortKey(
            name = light.name,
            author = light.author,
            updatedAt = stamp,
            lastRead = readTime(id),
        )
    }
    fun keyOf(id: String) = sortKeys[id] ?: ShelfSortKey("", "", 0L, 0L)
    return entries.sortedWith { a, b ->
        val c = shelfCompare(keyOf(a.second), keyOf(b.second), ShelfUi.sort)
        if (ShelfUi.sortDesc) -c else c
    }.mapNotNull { (_, id, tag) ->
        lights[id]?.let {
            ShelfRow(it, tag, keyOf(id).updatedAt, shelfNewCount(id))
        }
    }
}

/** 屏上现有滚动位置。重画同一屏（切选择模式）时要把位置带回去。 */
private fun MainActivity.bodyScrollOf(): Int {
    for (i in 0 until root.childCount) {
        val c = root.getChildAt(i)
        if (c is android.widget.ScrollView) return c.scrollY
    }
    return 0
}

/**
 * 书架：已下载的书，封面墙 / 列表两档。
 *
 * 书源不提供封面 —— `job.json` 只存书名/作者/章节/进度，`Downloader.persist`
 * 没有 cover 字段。所以封面是**生成的占位卡片**：书名首字 + 按书名定的固定配色。
 * 同一本书每次进来颜色一致，靠颜色区分书籍，不靠图片。
 *
 * 结构照 legado `BookshelfScreen.kt`：大标题行（右侧搜索 + ⋮），下面一排副标题。
 * 189 dp 宽再放页签就只剩 100 dp，列表项书名全被截完。
 */
internal fun MainActivity.showShelf(keepScroll: Boolean = false) {
    setScreen(Screen.Shelf)
    ShelfUi.load(this)
    // 切选择模式会重画整屏；不带回滚动位置，用户在第三屏长按一下就被弹回顶。
    val scrollY = if (keepScroll) bodyScrollOf() else 0
    root.removeAllViews()
    // legado 书架的分组页签是「用户自建书单」，不是在读状态过滤。我们只有下载作业、
    // 没有书单系统，硬造「全部/在读/下完」是自创语义（用户判词：乱七八糟的）。
    // 首页就是纯书架：所有已下载的书，按最近使用排。
    val rows = shelfRows()
    val books = rows.map { it.job }

    // 选中的书被删光或已不在架上时收起选择模式，否则会停在「已选 0」的死界面。
    if (ShelfUi.selecting && !shelfPruneSelection(ShelfUi.selected, books.map { it.bookId })) {
        ShelfUi.exit()
    }

    if (ShelfUi.selecting) {
        // legado 编辑态：导航位换成 ✕，标题下方写「已选 N 本 · 共 M 本」，右侧排动作图标。
        root.addView(shelfSelectBar(books))
        root.addView(shelfSubtitle(shelfSelectSubtitle(books.size)))
    } else {
        root.addView(Widgets.headerRow(this, "书架",
            trailing = listOf(
                "search" to { askKeyword() },
                "more" to { showShelfMenu() },
            )))
        // legado 顶栏副标题 `uiState.subtitle`。我们的 headerRow 只有一行标题位，
        // 副标题单独占一行（FS_SM）。189 dp 上「N 本 · 在读 M」约 90 dp，一行放得下。
        // 「更新目录」跑着时这一行改成进度：网络活没有别的着落处。
        root.addView(shelfSubtitle(ShelfUi.updating ?: shelfSubtitle(
            books.size, books.count { readProgress(it.bookId) > 0 })))
    }

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    if (rows.isEmpty()) {
        col.addView(Widgets.emptyState(this, "list",
            "书架是空的\n搜索书名即可开始"))
        root.addView(body)
        attachTabs(0)
        return
    }

    if (!ShelfUi.grid) {
        // 列表档：一行一本，书名 + 作者/在读章 + 最新章。章数多的书想知道下到哪了就看这一档。
        for (row in rows) {
            val job = row.job
            val displayName = job.name.ifBlank { "未命名 · ${job.total} 章" }
            val done = job.count(ChapterStatus.DONE)
            // legado 列表项 `desc = durChapterTitle`：读到哪一章。
            val readIdx = readProgress(job.bookId)
            val readTitle = job.chapters.getOrNull(readIdx)?.title?.takeIf { it.isNotBlank() }
            val tail = if (readTitle != null) "在读 · $readTitle"
                       else progressTail(done, job.total, 0)
            val sub = if (job.author.isBlank()) tail else "${job.author} · $tail"
            // legado 列表项 `extra = latestChapterTitle`：最新一章叫什么。
            // 与在读章同名时不重画，省一行 —— 手表上每一行都是稀缺资源。
            val latest = job.chapters.lastOrNull()?.title?.takeIf {
                it.isNotBlank() && it != readTitle
            }
            val item = Widgets.listItem(this, displayName, sub, twoLine = true,
                highlight = ShelfUi.selected.contains(job.bookId),
                trailing = "more", onTrailingClick = { showJobMenu(job) }) {
                if (ShelfUi.selecting) toggleShelfSelection(job)
                else openShelfBook(job.bookId, displayName, job.author)
            }
            if (latest != null) {
                // listItem 只画两行。第三行（最新章节）直接挂在它返回的竖排容器上，
                // 左缘天然与书名对齐（容器已有 PAD 内边距）。
                item.addView(
                    Md3.ellipsize(Md3.text(this, latest, Md3.FS_SM, Md3.onSurfaceVariant))
                        .apply {
                            alpha = 0.7f
                            setPadding(0, 0, 0, Md3.dp(this@showShelf, Md3.GAP / 2))
                        },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            item.setOnLongClickListener {
                if (!ShelfUi.selecting) ShelfUi.enter(job.bookId) else toggleShelfSelection(job)
                true
            }
            col.addView(item)
        }
        root.addView(body)
        if (scrollY > 0) body.post { body.scrollTo(0, scrollY) }
        attachTabs(0)
        return
    }
    // 三列：手表屏 189 dp 宽，两列时封面有 74×104 dp —— 一张卡就吃掉
    // 内容区（144 dp）的七成高，一屏只看得见两本书，用户判词：「书大的离谱」。
    // 三列后封面 47×65 dp，一屏三列、能扫到两组。
    val cols = 3
    for (chunk in rows.chunked(cols)) {
        val line = Md3.row(this)
        line.gravity = Gravity.TOP
        for (row in chunk) {
            line.addView(shelfCard(row), LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (line.childCount > 0) leftMargin = Md3.dp(this@showShelf, Md3.GAP)
            })
        }
        // 不足三个时补空位，否则最后一行少几张卡会被撑满整行。
        repeat(cols - chunk.size) {
            line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        col.addView(line, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = Md3.dp(this@showShelf, Md3.GAP) })
    }
    root.addView(body)
    if (scrollY > 0) body.post { body.scrollTo(0, scrollY) }
    attachTabs(0)
}

/** 点一下卡片：选择模式下切选中，平时进目录。两条路都要重画顶栏标题里的计数。 */
private fun MainActivity.toggleShelfSelection(job: BookJob) {
    shelfToggle(ShelfUi.selected, job.bookId)
    showShelf(keepScroll = true)
}

/**
 * 顶栏副标题。legado 的 `subtitle` 挂在 TopAppBar 里（标题下面一行）；
 * 我们的 `Widgets.headerRow` 只有一行标题位，所以副标题单独占一行。
 */
private fun MainActivity.shelfSubtitle(text: CharSequence): android.widget.TextView =
    Md3.ellipsize(Md3.text(this, text, Md3.FS_SM, Md3.onSurfaceVariant)).apply {
        alpha = 0.85f
        setPadding(Md3.dp(this@shelfSubtitle, Md3.PAD), 0,
            Md3.dp(this@shelfSubtitle, Md3.PAD), Md3.dp(this@shelfSubtitle, Md3.GAP / 2))
    }

/**
 * 选择模式顶栏。
 *
 * legado 的 `BookshelfTopBar` 在编辑态把 navigationIcon 换成 ✕，标题是当前分组名，
 * 副标题「已选 N 本 · 共 M 本」，右侧排 全选 / 反选 / 批量下载 / 分组 / 私密 / 删除 六枚。
 *
 * 189 dp 上这套排不下：M3 一个图标槽 48 dp，✕ + 全选 + 删除 就 144 dp，
 * 标题只剩 13 dp，等于没有标题。所以：
 * - 图标槽压到 34~36 dp（`Md3.iconButton` 只给图形本身，槽宽由这里定）；
 * - 标题降到 FS_MD（22 sp 的「已选 N」在这行里放不下）；
 * - 只留 ✕ / 全选 / 删除 三枚 —— 反选、批量下载、分组、私密在本应用没有对应物。
 *
 * 没有直接用 `Widgets.headerRow`：它的图标槽固定 TAP（48 dp），也不给左侧导航位。
 * 正常态的顶栏仍是 headerRow（见 showShelf），根屏不挂返回箭头的规则不变。
 */
private fun MainActivity.shelfSelectBar(books: List<BookJob>): LinearLayout {
    val bar = Md3.row(this)
    bar.setPadding(Md3.dp(this, Md3.PAD / 2), 0, Md3.dp(this, Md3.PAD / 2),
        Md3.dp(this, Md3.GAP))

    fun slot(glyph: String, desc: String, widthDp: Int, act: () -> Unit): View {
        val v = Md3.iconButton(this, glyph, act, desc)
        // 热区：横向按槽宽，纵向吃满行高（36 dp）。M3 的 48 dp 槽在这里放不下三个。
        v.minimumWidth = 0
        v.setPadding(Md3.dp(this, 2), 0, Md3.dp(this, 2), 0)
        v.layoutParams = LinearLayout.LayoutParams(Md3.dp(this, widthDp),
            Md3.dp(this, Md3.TAB_H))
        return v
    }

    bar.addView(slot("close", "退出选择", 34) {
        ShelfUi.exit()
        showShelf(keepScroll = true)
    })
    val title = Md3.ellipsize(
        Md3.text(this, shelfSelectTitle(ShelfUi.selected.size), Md3.FS_MD, Md3.onSurface,
            bold = true)).apply { gravity = Gravity.CENTER_VERTICAL }
    bar.addView(title, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.TAB_H), 1f))
    bar.addView(slot("select_all", "全选", 36) {
        shelfSelectAll(ShelfUi.selected, books.map { it.bookId })
        showShelf(keepScroll = true)
    })
    bar.addView(slot("delete", "删除选中", 36) { confirmDeleteSelected(books) })
    return bar
}

/**
 * 删除选中的书，走二次确认。
 *
 * 与 legado `DeleteBooksConfirmDialog` 同一条路：标题「删除选中」，
 * 关窗前不做任何落盘动作。区别是 legado 正文只写「已选 N 本」，把后果交给
 * 一组勾选（是否删源文件）；我们没有那个分叉 —— 正文直接把后果写清楚。
 */
private fun MainActivity.confirmDeleteSelected(books: List<BookJob>) {
    val chosen = books.filter { ShelfUi.selected.contains(it.bookId) }
    if (chosen.isEmpty()) {
        toast("先选一本书")
        return
    }
    Widgets.md3Dialog(this,
        title = "删除选中",
        body = shelfDeleteMessage(chosen.map { it.name.ifBlank { "未命名" } }),
        confirm = "删除",
        onConfirm = {
            val ids = chosen.map { it.bookId }
            ShelfUi.exit()
            // 与缓存页同一套整本删除：取消服务 → 踢出队列 → 等收工 → 落墓碑。
            purgeBookCache(ids) { gone ->
                toast(if (gone == ids.size) "已删除 $gone 本"
                      else "已删除 $gone 本，其余目录仍被占用")
                showShelf()
            }
        },
    ).show()
}

/**
 * 三列下每张卡的实际宽度（dp）。
 *
 * 卡宽由屏宽算出，不写死，也不靠 post 里回改高度：以前写死 58 dp 再在
 * `card.post` 里按 5:7 改一次，改之前那一帧是错的。这里先算清楚再建视图。
 */
private fun MainActivity.shelfCardWidthDp(): Int {
    val dm = resources.displayMetrics
    val content = dm.widthPixels - 2 * Md3.dp(this, Md3.PAD) - 2 * Md3.dp(this, Md3.GAP)
    return Math.round(content / 3f / dm.density)
}

/** 封面高：5:7，跟 legado `aspectRatio(5f / 7f)` 同一个比例。 */
private fun MainActivity.shelfCoverHeightDp(): Int = shelfCardWidthDp() * 7 / 5

/**
 * 网格卡：封面墙上一张。
 *
 * 抄 legado `BookItem.kt` 的 `BookshelfGridItem` + `BookshelfCover` 三处：
 * 左下角来源标签（`leftBottomText`）、
 * 选中态底色（`isSelected` → secondaryContainer）；书名居中最多两行
 * （`titleCenter = true`、`titleMaxLines = 2`）。
 *
 * 压了什么：legado 封面 84 dp 宽、书名下留 4 dp；这里封面 47 dp 宽，
 * 角标牌与标签都降到 8 sp 且压掉内外边距 —— 8 sp 是 47 dp 封面上还能
 * 认出来的下限，再小就是色块。
 */
private fun MainActivity.shelfCard(row: ShelfRow): LinearLayout {
    val job = row.job
    val selected = ShelfUi.selected.contains(job.bookId)
    val card = Md3.column(this)
    card.isClickable = true
    card.isFocusable = true
    val displayName = job.name.ifBlank { "未命名" }
    // 选中态给卡片铺底色（legado 网格项就是这么标的）。47 dp 的卡只靠底色
    // 在表盘上看不出来，所以封面再叠一层遮罩 + 勾（见下）。
    card.background = Md3.ripple(this,
        if (selected) Md3.secondaryContainer else android.graphics.Color.TRANSPARENT,
        Md3.R_ITEM.toFloat())
    card.contentDescription = buildString {
        append(displayName)
        append("，").append(job.author.ifBlank { "未知作者" })
        val unread = shelfUnreadCount(job.total, readProgress(job.bookId))
        if (unread > 0) append("，未读 $unread 章")
        if (row.sourceTag.isNotBlank()) append("，").append(row.sourceTag)
        if (selected) append("，已选中")
    }
    card.setOnClickListener {
        if (ShelfUi.selecting) toggleShelfSelection(job)
        else openShelfBook(job.bookId, displayName, job.author)
    }
    card.setOnLongClickListener {
        // legado 长按在浏览态是进详情页（它另有「选择模式」菜单项）；
        // 我们这一屏的目标就是多选，长按直接进选择模式并把这本选上。
        if (ShelfUi.selecting) toggleShelfSelection(job) else {
            ShelfUi.enter(job.bookId)
            showShelf(keepScroll = true)
        }
        true
    }

    // 封面区：颜色按书名哈希定，同一本书颜色稳定。宽高都算出来再建，不走 post 回改。
    val coverW = shelfCardWidthDp()
    val coverH = shelfCoverHeightDp()
    val cover = FrameLayout(this)
    val glyph = Md3.text(this, coverGlyph(job), 22f, Md3.onPrimaryContainer, bold = true).apply {
        gravity = Gravity.CENTER
        background = Md3.bg(shelfColor(job), Md3.R_GRID.toFloat())
    }
    cover.addView(glyph, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    // 来源标签：legado `Alignment.BottomStart`，底色取卡片容器色。
    // 47 dp 宽减去两个 1 dp 边距只剩 45 dp，8 sp 下正好 4 个汉字。
    // 受 `showTip` 控制（legado 那个开关管的就是这个类型标签）。
    if (ShelfUi.showTag) {
        val tag = Md3.text(this, row.sourceTag, 8f, Md3.onSurfaceVariant).apply {
            background = Md3.bg(Md3.surface3, 3f)
            setPadding(Md3.dp(this@shelfCard, 1), 0, Md3.dp(this@shelfCard, 1), 0)
            maxWidth = Md3.dp(this@shelfCard, coverW - 2)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        cover.addView(tag, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Md3.dp(this@shelfCard, 1)
            bottomMargin = Md3.dp(this@shelfCard, 1)
        })
    }

    // 更新圆点：legado `showBadgeDot`（`showUnread && showUnreadNew && book.isNew`）。
    // 上次「更新目录」查出新章就点一下。左上角原本让给了未读角标，角标去掉后仍留在这里。
    if (ShelfUi.showNew && row.newCount > 0) {
        cover.addView(View(this).apply {
            background = Md3.bg(Md3.primary, 3f)
        }, FrameLayout.LayoutParams(
            Md3.dp(this, 6), Md3.dp(this, 6), Gravity.TOP or Gravity.START).apply {
            topMargin = Md3.dp(this@shelfCard, 2)
            leftMargin = Md3.dp(this@shelfCard, 2)
        })
    }

    // 选中遮罩：legado 只换卡片底色，手表上认不出，所以封面上再压一层
    // 半透明 secondaryContainer 加一枚勾。
    if (selected) {
        cover.addView(View(this).apply {
            background = Md3.bg(Md3.secondaryContainer, Md3.R_GRID.toFloat())
            alpha = 0.72f
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val check = Icons.view(this, "check", Md3.onSecondaryContainer, 18).apply {
            contentDescription = "已选中"
        }
        cover.addView(check, FrameLayout.LayoutParams(
            Md3.dp(this, 18), Md3.dp(this, 18), Gravity.CENTER))
    }
    card.addView(cover, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, coverH)))

    // 书名：居中、最多 [ShelfUi.titleLines] 行（legado `titleCenter` / `titleMaxLines`）。
    // 47 dp 三列下每行 3 个汉字，两行 6 个字，中文书名基本够。
    val title = Md3.text(this, displayName, Md3.FS_SM, Md3.onSurface).apply {
        gravity = Gravity.CENTER
        maxLines = ShelfUi.titleLines
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, Md3.dp(this@shelfCard, Md3.GAP / 2), 0, 0)
    }
    card.addView(title, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val done = job.count(ChapterStatus.DONE)
    // 字号走 Md3.FS_SM 而不是硬编码 9sp：MD3 的 labelSmall 下限是 11sp，
    // 9sp 在手表上已经难读；FS_SM 还会随屏宽档位放大。作者/进度行同样居中。
    card.addView(Md3.ellipsize(Md3.text(this,
        if (job.author.isBlank()) "$done/${job.total} 章" else job.author,
        Md3.FS_SM, Md3.onSurfaceVariant)).apply {
        gravity = Gravity.CENTER
        alpha = 0.7f
    })

    // 触控区不够大时补高（Md3.TAP = 48 dp），否则手表上不好点。
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
 * 我们只做下载和本地文件，没有书源导入、书单、分组、WebDav 这些东西。
 * 照 legado 的排位保留全部条目：能接的接上（更新目录 / 布局设置 / 添加网址 / 选择模式 /
 * 书架管理 / 导出书单 / 日志），接不上的点一下说清缺什么，
 * 保留占位优于删条目 —— 删了就看不出这一屏还差哪些能力。
 */
internal fun MainActivity.showShelfMenu() {
    fun todo(what: String, why: String) = { toast("$what：$why") }
    Widgets.longMenuDialog(this, "书架", listOf(
        // legado 的「远程书籍」是 WebDav 同步，本版没有网络备份。
        "远程书籍" to todo("远程书籍", "要 WebDav，本版只有本机目录"),
        // legado 的「添加本地」走 SAF 文件选择器。手表系统自带「文件」选择器，
        // 用 ACTION_OPEN_DOCUMENT 就能拉到，不需要任何存储权限，也不用 androidx。
        "添加本地" to { pickLocalBook() },
        "更新目录" to { refreshShelfTocs() },
        "布局设置" to { showShelfLayoutSheet() },
        "分组管理" to todo("分组管理", "本应用没有书单分组"),
        "添加网址" to { askKeyword() },
        "选择模式" to {
            ShelfUi.enter("")
            showShelf(keepScroll = true)
        },
        // legado 的「书架管理」是书架自身的批量管理页；我们对应的是缓存管理。
        "书架管理" to { showCache() },
        "导出书单" to { showExportList() },
        // 与「我的」页同一处规矩：书源页返回靠返回栈，进来前要压一条。
        "导入书单" to { rememberReturn(); showSources() },
        "日志" to { showLogs() },
    )).show()
}

// ══════════════════ 本地导入 ══════════════════

/** 选择本地文件的请求码，与 [MainActivity.onActivityResult] 对齐。 */
internal const val REQ_LOCAL_BOOK = 4101

/**
 * 「添加本地」：拉起系统文件选择器，让用户挑一个 txt / epub。
 *
 * 用通配类型加 [Intent.EXTRA_MIME_TYPES]，而不是直接写 text/plain：
 * 部分 ROM 的文件选择器对单一 MIME 过滤得太死，txt 常被标成 `application/octet-stream`，
 * 只给 text/plain 就选不中自己的文件。导入时按扩展名与文件头判格式，不靠 MIME。
 *
 * 拿不到选择器（手表上没装 DocumentsUI）时给出明确说法，不能静默失败。
 */
internal fun MainActivity.pickLocalBook() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("text/plain", "application/epub+zip", "application/octet-stream"),
        )
    }
    runCatching { startActivityForResult(i, REQ_LOCAL_BOOK) }
        .onFailure { toast("这台设备没有文件选择器，没法导入本地书") }
}

// ══════════════════ 新章计数 ══════════════════

/**
 * 上次「更新目录」在这本书上查出的新章数，圆点用它（legado `Book.lastCheckCount`）。
 *
 * 存既有偏好桶（键 `new_<bookId>`）。bookId 是完整链接，做键要转义 `%` `/` 之类，
 * 直接拼进键名里 SharedPreferences 能存（键就是个字符串），不做净化。
 */
internal fun MainActivity.shelfNewCount(bookId: String): Int =
    getSharedPreferences("reader", Context.MODE_PRIVATE).getInt("new_$bookId", 0)

internal fun MainActivity.setShelfNewCount(bookId: String, n: Int) {
    getSharedPreferences("reader", Context.MODE_PRIVATE).edit()
        .putInt("new_$bookId", n.coerceAtLeast(0)).apply()
}

/** 点卡片直接进正文。顺手清掉新章圆点 —— legado 翻开书也清 `lastCheckCount`。 */
private fun MainActivity.openShelfBook(bookId: String, name: String, author: String) {
    if (shelfNewCount(bookId) > 0) setShelfNewCount(bookId, 0)
    readShelfBook(bookId, name, author)
}

/**
 * 点书架进正文，照 legado：`onOpenBookshelfBook` 直接 `MainRouteReadBook`，
 * 中间没有目录、也没有书详情。
 *
 * 章节表在 job.json 里（restoreLight 只读它），从上次读到的章续上。
 * 还没取过目录的书没有正文可读，退回旧行为进目录去取。
 *
 * 返回落点交给 [com.watchdl.ui.reader.ReaderUi.fromShelf]：从书架进来的退回书架，
 * 从目录进来的退回目录，不再一律跳目录。
 */
internal fun MainActivity.readShelfBook(bookId: String, name: String, author: String) {
    val job = Downloader.restoreLight(jobsStore(), bookId)
    if (job == null || job.chapters.isEmpty()) { openBook(bookId, name, author); return }
    val info = BookInfo(bookId, job.name.ifBlank { name }, job.author.ifBlank { author }, job.chapters)
    val at = readProgress(bookId).coerceIn(0, job.chapters.size - 1)
    com.watchdl.ui.reader.ReaderUi.fromShelf = true
    rememberReturn()
    preview(info, at)
}

// ══════════════════ 布局设置 ══════════════════

/**
 * 布局设置。legado 是 `BookshelfConfigSheet`（六组分区、六百多行）。
 *
 * 手表上只取能落地的子集：
 * - **布局**（列表/网格）、**顺序**（升序/倒序）、**书名行数**、
 *   **来源标签**、**更新提示** —— 一行两枚档位，点了就地切；
 * - **排序**有四个值，一行放不下四枚档位，改成「一行显示当前值 + 点开选择」，
 *   这正是 legado 里那个下拉控件的交互（`DropdownSettingItem`）。
 *
 * 高度预算：189×248 dp 上对话框内边距 16×2 + 标题 28 + 间距 12 + 内容 120 + 按钮 40 = 244 dp，
 * 所以内容区固定 120 dp 内部滚动（约四行），再高会把按钮挤出屏外。
 *
 * 没做的三档：分组、封面形状（`grid_style`）、字号缩放。前两个要分组体系与封面位图，
 * 我们封面是生成的占位卡片；字号缩放与全局主题字号冲突，不在这里造第二套。
 */
internal fun MainActivity.showShelfLayoutSheet() {
    fun save() {
        ShelfUi.save(this)
        showShelf(keepScroll = true)
    }

    val col = Md3.column(this)
    /** 当前页的选中态刷新集合：改一项就地重画，不重建对话框。 */
    var refreshers: List<() -> Unit> = emptyList()

    /** 档位行：标签 + 若干等宽档，样式与阅读设置同一套（填充 primary + 深字）。 */
    fun seg(label: String, options: List<Pair<String, Boolean>>, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = Md3.row(this).apply { setPadding(0, Md3.dp(this@showShelfLayoutSheet, 3), 0, 0) }
        row.addView(Md3.text(this, label, Md3.FS_SM, Md3.onSurfaceVariant),
            LinearLayout.LayoutParams(Md3.dp(this, 44), ViewGroup.LayoutParams.WRAP_CONTENT))
        val chips = mutableListOf<Pair<TextView, Boolean>>()
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
                leftMargin = if (i == 0) 0 else Md3.dp(this@showShelfLayoutSheet, 4)
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
                    if (sel) Md3.primary else android.graphics.Color.TRANSPARENT,
                    Md3.dp(this, 13).toFloat(),
                    if (sel) null else Md3.outlineVariant)
            }
        }
    }

    /** 排序行：标签 + 当前值，点一下开选择框（legado 的 DropdownSettingItem）。 */
    fun sortRow() {
        val row = Md3.row(this).apply { setPadding(0, Md3.dp(this@showShelfLayoutSheet, 3), 0, 0) }
        row.addView(Md3.text(this, "排序", Md3.FS_SM, Md3.onSurfaceVariant),
            LinearLayout.LayoutParams(Md3.dp(this, 44), ViewGroup.LayoutParams.WRAP_CONTENT))
        val value = Md3.text(this, "", Md3.FS_SM, Md3.onSurface).apply {
            gravity = Gravity.CENTER
            background = Md3.ripple(this@showShelfLayoutSheet, Md3.surface4,
                Md3.dp(this@showShelfLayoutSheet, 13).toFloat(), Md3.outlineVariant)
            isClickable = true
            setOnClickListener {
                Widgets.menuDialog(this@showShelfLayoutSheet, "排序",
                    ShelfSort.keys.map { key -> ShelfSort.label(key) to {
                        ShelfUi.sort = key
                        // 换键给一个说得通的方向：时间新的在前，名字从 A 到 Z。
                        // 不这么做的话「按书名」会先落到倒序，用户第一眼就是乱的。
                        ShelfUi.sortDesc = ShelfSort.defaultDesc(key)
                        save()
                        refreshers.forEach { r -> r() }
                    } }).show()
            }
        }
        row.addView(value, LinearLayout.LayoutParams(0, Md3.dp(this, 26), 1f))
        col.addView(row)
        refreshers = refreshers + { value.text = ShelfSort.label(ShelfUi.sort) }
    }

    sortRow()
    seg("布局", listOf("列表" to false, "网格" to true), { ShelfUi.grid }) { ShelfUi.grid = it }
    seg("顺序", listOf("正序" to false, "倒序" to true), { ShelfUi.sortDesc }) { ShelfUi.sortDesc = it }
    seg("书名", listOf("1 行" to false, "2 行" to true), { ShelfUi.titleLines >= 2 }) {
        ShelfUi.titleLines = if (it) 2 else 1
    }
    seg("来源", listOf("关" to false, "开" to true), { ShelfUi.showTag }) { ShelfUi.showTag = it }
    seg("新章", listOf("关" to false, "开" to true), { ShelfUi.showNew }) { ShelfUi.showNew = it }
    refreshers.forEach { r -> r() }

    // 内容区固定 120 dp 内部滚动：六行约 180 dp，直接塞进对话框会把按钮顶出屏外。
    // 外面套一层定高 FrameLayout —— md3Dialog 会给 content 重设 LayoutParams，
    // 把高度写在 ScrollView 上会被覆盖，写在容器里才管用。
    val scroller = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(col)
    }
    val page = FrameLayout(this).apply {
        addView(scroller, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this@showShelfLayoutSheet, 120)))
    }
    Widgets.md3Dialog(this, "布局设置", content = page,
        confirm = "完成", dismiss = null).show()
}

// ══════════════════ 更新目录 ══════════════════

/**
 * 「更新目录」：照 legado `RefreshTocUseCase` 逐本重取目录，但**只追加新章**。
 *
 * 为什么不是整表替换（legado 的做法）：我们的正文按 itemId 存在 chapters.jsonl，
 * 整表换掉等于让已下正文对不上章号。见 [mergeShelfCatalog]。
 *
 * 失败一本不影响别的：连载书里常有一两本源挂掉，用户要的是「能更的都更了」。
 */
internal fun MainActivity.refreshShelfTocs() {
    // 本地书的目录来自导入时那个文件，没有「更新」这回事；
    // 混在一起跑只会给它算一次失败。
    val rows = shelfRows().filter { !LocalImport.isLocal(it.job.bookId) }
    if (rows.isEmpty()) { toast("书架里没有需要联网更新目录的书"); return }
    ShelfUi.updating = "正在更新目录 0/${rows.size}"
    showShelf(keepScroll = true)
    bg(Screen.Shelf, {
        var grew = 0
        var failed = 0
        var added = 0
        rows.forEachIndexed { i, row ->
            val src = sourceForBook(row.job.bookId)
            val got = if (src == null) null else runCatching {
                src.toc(row.job.bookId, row.job.name, row.job.author)
            }.getOrNull()
            if (got == null || got.chapters.isEmpty()) {
                failed++
            } else {
                val merged = mergeShelfCatalog(
                    jobsStore().readJob(row.job.bookId).orEmpty(), got.chapters)
                if (merged == null) {
                    // 没有新章：上一轮的计数作废，圆点要跟着灭。
                    if (shelfNewCount(row.job.bookId) > 0) setShelfNewCount(row.job.bookId, 0)
                } else if (runCatching {
                        jobsStore().writeJob(row.job.bookId, merged.json)
                    }.isSuccess) {
                    setShelfNewCount(row.job.bookId, merged.added)
                    grew++
                    added += merged.added
                } else {
                    failed++
                }
            }
            ShelfUi.updating = "正在更新目录 ${i + 1}/${rows.size}"
        }
        Triple(grew, added, failed)
    }) { res ->
        ShelfUi.updating = null
        res.onSuccess { (grew, added, failed) ->
            val tail = if (failed > 0) "，$failed 本失败" else ""
            toast(when {
                grew > 0 -> "更新完成：$grew 本共新增 $added 章$tail"
                failed > 0 -> "更新完成：没有新章$tail"
                else -> "全部已是最新"
            })
        }.onFailure { toast("更新失败：${it.message}") }
        showShelf(keepScroll = true)
    }
}

/**
 * 这本书该用哪个源取目录。
 *
 * 与 `DownloadService.resolveSource` 同一判据：链接以源主机开头就算这本归它。
 * 走 [multi] 而不是重读书源目录，是因为 `multi` 里已经把 `DeviceRegister` 起好，
 * 番茄源的构造依赖它。
 */
private fun MainActivity.sourceForBook(bookId: String): BookSource? {
    // 本地书没有源。不拦住的话会按「非 http」落到番茄，拿 local:xxx 去发请求。
    if (LocalImport.isLocal(bookId)) return null
    val pool = runCatching { multi.allNamed() }.getOrNull().orEmpty()
    if (bookId.startsWith("http", ignoreCase = true)) {
        val host = pool.firstOrNull { ns ->
            val h = ns.host.trimEnd('/')
            h.isNotBlank() && bookId.startsWith(h, ignoreCase = true)
        }
        if (host != null) return host.source
    }
    // 番茄的 bookId 是纯数字，不带主机；池子里第一个就是它（buildMulti 先加）。
    return pool.firstOrNull { it.name == "番茄" }?.source ?: pool.firstOrNull()?.source
}

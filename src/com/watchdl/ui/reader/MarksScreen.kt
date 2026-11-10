package com.watchdl.ui.reader

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.my.showMy
import com.watchdl.ui.search.askText
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 书签：单书页（[showMarks]）与全书页（[showAllMarks]）。
 *
 * 手表上没有文字选中，「书签」只能是「这一章我停在这儿」，所以一条 = 章 + 摘录。
 * 两条路都走 [BookmarkStore]，每次进来现读 —— 加删之后回来不会看到旧列表。
 *
 * 抄的是本地参考仓 `~/ref/legado-novel`（Compose/MD3，3.26.21）的
 * `ui/book/bookmark/AllBookmarkScreen.kt` 与 `ui/widget/components/bookmark/BookmarkItem.kt`：
 *
 * | legado | 这里（189 × 248 dp 表盘） |
 * |---|---|
 * | 两层：按书总览 → 本书详情（书签 / 笔记两 Tab） | 单屏平铺，等于它的 `DetailList` |
 * | 行 = 章名（主色）+ 摘录 2 行 + 备注 | 行 = 章名（主色）+ 摘录 2 行 + 《书名》· 时间 |
 * | 点行开 `BookmarkEditSheet` 改摘录/备注 | 点整行跳到那章；改摘录要文本选中，手表没有 |
 * | 长按进编辑 | 长按进多选 |
 * | 顶栏 search + ⋮（导出 JSON / Markdown） | 顶栏 search + ⋮（全选 / 清空全部） |
 * | 删除前过一遍 `sure_del` | 同样二次确认，正文写「找不回来」 |
 *
 * 不做总览层与 Tab：189 dp 上「总览 → 详情」要多点一次才看到内容，
 * 而我们没有笔记体系，Tab 只剩一个也白占一行。
 */

/** 本页最多显示多少条。200 条书签全画出来，手表上滚半天也找不到。 */
private const val MARKS_SHOWN = 60

/** 摘录长度。屏宽 189 dp，一行正文能放 12 个汉字左右。 */
private const val EXCERPT_MAX = 24

/**
 * 全书页的屏内状态：搜索词与多选。
 *
 * 放文件级 object 而不是往 MainActivity 加字段：这两样只属于这一屏，
 * 离开就重来。选择集存 [markKey] 而不是下标 —— 重画之后下标会变。
 */
internal object MarksUi {

    var query = ""

    var selecting = false

    val selected = linkedSetOf<String>()

    fun enter(key: String) {
        selecting = true
        selected.clear()
        selected += key
    }

    fun exit() {
        selecting = false
        selected.clear()
    }
}

internal fun MainActivity.showMarks(info: BookInfo) {
    setScreen(Screen.Marks(info))
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "书签 · ${info.name}", onBack = { showToc(info) }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val all = BookmarkStore.forBook(info.bookId)

    if (all.isEmpty()) {
        col.addView(Widgets.emptyState(this, "bookmark", "还没有书签。\n阅读页点「设置」可以加一条。"))
        root.addView(body)
        return
    }

    col.addView(Widgets.sectionLabel(this, "共 ${all.size} 条"))
    for (b in all.take(MARKS_SHOWN)) {
        // 章号可能因为换源而失效：书源换了目录长度不同，旧的 index 会指到别的章。
        // 越界就显示但跳不了，比悄悄少一条好 —— 用户至少知道它还在。
        val valid = b.index in info.chapters.indices
        col.addView(marksRow(
            b = b,
            // 章名取目录里的新标题：书签里那份可能是换源之前抄的。
            label = if (valid) info.chapters[b.index].title else "${markChapterLabel(b)}（已失效）",
            // 单书页的书名已经在顶栏，第三行只留时间。
            meta = markTimeLabel(b.at),
            // 行菜单只给全书页：单书页里点一行就是跳，没有第二种动作。
            showMenu = false,
            onTap = {
                if (valid) jumpToMark(info, b) else toast("这一章已经失效了")
            },
        ))
    }

    if (all.size > MARKS_SHOWN) {
        col.addView(Md3.text(this, "另有 ${all.size - MARKS_SHOWN} 条未显示", Md3.FS_SM, Md3.onSurfaceVariant))
    }

    col.addView(Widgets.button(this, "清空本书书签", "outline") {
        val bookId = info.bookId
        Widgets.md3Dialog(this,
            title = "清空本书书签",
            body = markDeleteMessage(all.size),
            confirm = "清空",
            onConfirm = {
                BookmarkStore.save(BookmarkStore.list().filterNot { it.bookId == bookId })
                toast("已清空")
                showMarks(info)
            },
        ).show()
    })
    root.addView(body)
}

/** 跳到一条书签：恢复章 + 章内位置。 */
private fun MainActivity.jumpToMark(info: BookInfo, b: Bookmark) {
    // 位置要在 preview 之前写：preview 建完视图才读 readOffset，
    // 那时候再写就和「上次读到哪」混在一起了。
    if (b.offset > 0) saveOffset(info, b.index, b.offset, sync = true)
    preview(info, b.index)
}

/**
 * 全书书签（#24）：把所有书的书签聚到一屏。
 *
 * 单书书签页要从那本书的目录进，用户想找「上次停住的地方」时
 * 得先想起是哪个书 —— 而 [BookmarkStore.list] 本来就是全量的，
 * 差的就是这一页。排序按书名分组、组内按章号，与 legado 总览的分组同义。
 */
internal fun MainActivity.showAllMarks() {
    setScreen(Screen.MarksAll)

    val all = markSorted(BookmarkStore.list())
    val shown = markShown(all, MarksUi.query)
    // 选中的书签可能被删掉或被搜索过滤掉：留着会停在「已选 0」的死界面。
    if (MarksUi.selecting && !markSelectionPrune(MarksUi.selected, shown.map { markKey(it) })) {
        MarksUi.exit()
    }

    root.removeAllViews()
    if (MarksUi.selecting) {
        root.addView(marksSelectBar())
    } else {
        // 统计放顶栏副标题而不是列表上方一张统计行：248 dp 高的屏上，
        // 多一行统计就少一条书签（书源与替换两页同样的处理）。
        root.addView(Widgets.topBar(this, "书签", onBack = { showMy() },
            subtitle = markSubtitle(all, shown, MarksUi.query),
            trailing = listOf(
                "search" to { askMarksQuery() },
                "more" to { showMarksMenu(all) },
            )))
    }

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    col.addView(Widgets.sectionLabel(this, markGroupLabel(MarksUi.query, shown.size)))

    if (shown.isEmpty()) {
        col.addView(Widgets.emptyState(this, "bookmark", when {
            MarksUi.query.isNotBlank() -> "没有书名、章名或摘录含「${MarksUi.query}」的书签"
            else -> "还没有书签。\n阅读页点「设置」可以加一条。"
        }))
    } else {
        for (b in shown.take(MARKS_SHOWN)) {
            col.addView(marksRow(
                b = b,
                label = markChapterLabel(b),
                meta = markMeta(b),
                showMenu = true,
                onTap = { jumpToAnyMark(b) },
            ))
        }
        if (shown.size > MARKS_SHOWN) {
            col.addView(Md3.text(this, "另有 ${shown.size - MARKS_SHOWN} 条未显示", Md3.FS_SM, Md3.onSurfaceVariant))
        }
    }

    root.addView(body)
    // 批量删除固定在屏底一行 —— legado 的选择动作也在底部工具条，不在顶栏。
    if (MarksUi.selecting) root.addView(marksBatchBar())
}

/**
 * 一条书签，卡片式。
 *
 * 照 legado `DetailList` 的 `GlassCard`（圆角容器 + 16 dp 内边距）：那里一屏能放
 * 五六张卡，189 × 248 dp 上内边距收到 8 dp，行高才回到约 74 dp ——
 * 一张卡从章名到第三行共三行字，再压就只有章名能看见。
 *
 * @param label 章名（单书页用目录里的新标题，全书页用书签里那份）
 * @param meta 第三行小字；null 表示不画
 * @param showMenu 是否给右侧 ⋮。选择态下统一收起
 * @param onTap 非选择态点整行要做的事
 */
private fun MainActivity.marksRow(
    b: Bookmark,
    label: CharSequence,
    meta: CharSequence?,
    showMenu: Boolean,
    onTap: () -> Unit,
): LinearLayout {
    val key = markKey(b)
    val picked = key in MarksUi.selected

    val card = Widgets.card(this)
    card.background = Md3.ripple(this,
        if (picked) Md3.surface4 else Md3.surface3, Md3.R_CARD.toFloat())
    card.setPadding(Md3.dp(this, Md3.PAD * 3 / 4), Md3.dp(this, 8),
        Md3.dp(this, Md3.PAD / 2), Md3.dp(this, 8))

    val line = Md3.row(this)

    if (MarksUi.selecting) {
        line.addView(Icons.view(this, "check",
            if (picked) Md3.primary else Md3.onSurfaceVariant, 16).apply {
            alpha = if (picked) 1f else 0.3f
        }, LinearLayout.LayoutParams(Md3.dp(this, 24), Md3.dp(this, 24)).apply {
            rightMargin = Md3.dp(this@marksRow, Md3.GAP / 2)
        })
    }

    val text = Md3.column(this)
    // 章名用主色：legado 的 `isDur`（正在读的这一章）与 `DetailList` 都是主色。
    text.addView(Md3.ellipsize(Md3.text(this, label, Md3.FS_MD, Md3.primary)))
    val excerpt = markExcerpt(b)
    if (excerpt.isNotEmpty()) {
        // Md3.ellipsize 只做单行；摘录照 legado 给两行，所以自己设 maxLines。
        text.addView(Md3.text(this, excerpt, Md3.FS_SM, Md3.onSurfaceVariant).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(Md3.dp(this@marksRow, 1).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    if (meta != null) {
        // 第三行比摘录再淡一档：它在卡片里只是注脚，压暗比换字号更省行高。
        text.addView(Md3.ellipsize(Md3.text(this, meta, Md3.FS_SM, Md3.onSurfaceVariant))
            .apply { alpha = 0.7f })
    }
    line.addView(text, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    if (showMenu && !MarksUi.selecting) {
        val menu = Md3.iconButton(this, "more", { showMarkRowMenu(b) }, desc = "更多操作")
        // iconButton 的最小热区是 48 dp 宽（M3 顶栏规格），行里只给 28 dp，放开限制。
        menu.minimumWidth = 0
        menu.minimumHeight = 0
        menu.setPadding(Md3.dp(this, 1), 0, Md3.dp(this, 1), 0)
        line.addView(menu, LinearLayout.LayoutParams(
            Md3.dp(this, 28), Md3.dp(this, Md3.ITEM)).apply { gravity = Gravity.CENTER_VERTICAL })
    }

    card.addView(line, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    card.isClickable = true
    card.setOnClickListener {
        if (MarksUi.selecting) toggleMarkSelection(key) else onTap()
    }
    card.setOnLongClickListener {
        // 长按进多选，与书源 / 替换两页一致；已经在选择态里长按等于再点一次。
        if (!MarksUi.selecting) {
            MarksUi.enter(key)
            showAllMarks()
        } else {
            toggleMarkSelection(key)
        }
        true
    }
    return card
}

/** 从全书书签跳到一章：先读回这本书的目录，再确认章号没失效。 */
private fun MainActivity.jumpToAnyMark(b: Bookmark) {
    val job = Downloader.restore(jobsStore(), b.bookId)
    if (job == null) {
        toast("《${b.bookName}》已经不在本地了")
        return
    }
    val info = BookInfo(b.bookId, job.name.ifBlank { b.bookName }, job.author, job.chapters)
    // 换源之后旧书签的章号会指错章，越界就不跳，说一句比默默打开第一章好。
    if (b.index !in info.chapters.indices) {
        toast("这一章已经失效了")
        return
    }
    rememberReturn()
    jumpToMark(info, b)
}

/**
 * 行菜单：跳转 / 删除。
 *
 * legado 点行开编辑表（摘录 + 备注 + 删除），长按也开它。手表上没有文字选中，
 * 摘录改不了，所以点行留给我最常用的一件事 —— 跳回去，改不了的东西删掉就行。
 */
private fun MainActivity.showMarkRowMenu(b: Bookmark) {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += "跳到这一章" to { jumpToAnyMark(b) }
    items += "删除这条" to { confirmDeleteMarks(listOf(markKey(b))) }
    Widgets.longMenuDialog(this, markChapterLabel(b), items).show()
}

/**
 * 选择态顶栏：✕ / 已选 N / 全选。
 *
 * 写法照书源屏、替换屏那两处：M3 一枚图标槽 48 dp，三枚就吃掉 144 dp，
 * 189 dp 的屏上标题会被压成 0。所以 34 / 标题 / 36 三槽。
 * 删除不放这里（放屏底批量条），否则顶栏要第四槽。
 */
private fun MainActivity.marksSelectBar(): LinearLayout {
    val bar = Md3.row(this)
    bar.setPadding(Md3.dp(this, Md3.PAD / 2), 0, Md3.dp(this, Md3.PAD / 2),
        Md3.dp(this, Md3.GAP))

    fun slot(glyph: String, desc: String, widthDp: Int, act: () -> Unit): View {
        val v = Md3.iconButton(this, glyph, act, desc)
        v.minimumWidth = 0
        v.setPadding(Md3.dp(this, 2), 0, Md3.dp(this, 2), 0)
        v.layoutParams = LinearLayout.LayoutParams(Md3.dp(this, widthDp),
            Md3.dp(this, Md3.TAB_H))
        return v
    }

    bar.addView(slot("close", "退出选择", 34) {
        MarksUi.exit()
        showAllMarks()
    })
    bar.addView(Md3.ellipsize(
        Md3.text(this, "已选 ${MarksUi.selected.size}", Md3.FS_MD, Md3.onSurface, bold = true))
        .apply { gravity = Gravity.CENTER_VERTICAL },
        LinearLayout.LayoutParams(0, Md3.dp(this, Md3.TAB_H), 1f))
    bar.addView(slot("select_all", "全选", 36) {
        markSelectionSelectAll(MarksUi.selected, markSorted(BookmarkStore.list()).map { markKey(it) })
        showAllMarks()
    })
    return bar
}

/** 屏底批量条：删除。legado 的批量删除也在底部工具条，不在 ⋮ 里。 */
private fun MainActivity.marksBatchBar(): LinearLayout {
    val bar = Md3.row(this)
    bar.setPadding(Md3.dp(this, Md3.PAD / 2), 0, Md3.dp(this, Md3.PAD / 2),
        Md3.dp(this, Md3.GAP))
    val keys = MarksUi.selected.toList()
    val b = Widgets.button(this, "删除书签", "tonal") {
        if (keys.isEmpty()) toast("先选书签") else confirmDeleteMarks(keys)
    }
    b.contentDescription = "删除书签"
    bar.addView(b, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
    return bar
}

/** 搜索书签。legado 是顶栏内联 SearchBar；手表上沿用本应用统一的输入对话框。 */
private fun MainActivity.askMarksQuery() {
    askText("搜索书签", "书名或章名关键词（留空清除）", null) { q ->
        MarksUi.query = q.trim()
        showAllMarks()
    }
}

/**
 * 顶栏 ⋮。
 *
 * legado 这里是「导出书签 JSON / 导出 Markdown」。我们没有导出通道
 * （没有存储权限、也没有可写目录），所以换成两条本地动作：
 * 全选（批量删之前省一次长按）与清空全部。
 */
private fun MainActivity.showMarksMenu(all: List<Bookmark>) {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    if (MarksUi.query.isNotEmpty()) {
        items += "清除搜索「${MarksUi.query}」" to {
            MarksUi.query = ""
            showAllMarks()
        }
    }
    if (all.isNotEmpty()) {
        items += "全选 ${all.size} 条" to {
            MarksUi.selecting = true
            MarksUi.selected.clear()
            MarksUi.selected += all.map { markKey(it) }
            showAllMarks()
        }
    }
    items += "清空全部书签（${all.size} 条）" to {
        if (all.isEmpty()) toast("还没有书签") else confirmClearMarks(all.size)
    }
    Widgets.longMenuDialog(this, "书签", items).show()
}

/** 多选：点一条切选中。 */
private fun MainActivity.toggleMarkSelection(key: String) {
    markSelectionToggle(MarksUi.selected, key)
    showAllMarks()
}

/**
 * 删书签，必须二次确认（legado 也过一遍 `sure_del`）。
 *
 * 存档是整份 JSON，删掉就没了，确认文案要把「找不回来」说清。
 */
private fun MainActivity.confirmDeleteMarks(keys: List<String>) {
    if (keys.isEmpty()) return toast("先选书签")
    Widgets.md3Dialog(this,
        title = "删除书签",
        body = markDeleteMessage(keys.size),
        confirm = "删除",
        onConfirm = {
            BookmarkStore.save(BookmarkStore.list().filterNot { markKey(it) in keys })
            MarksUi.exit()
            toast("已删除 ${keys.size} 条")
            showAllMarks()
        },
    ).show()
}

private fun MainActivity.confirmClearMarks(count: Int) {
    Widgets.md3Dialog(this,
        title = "清空全部书签",
        body = markDeleteMessage(count),
        confirm = "清空",
        onConfirm = {
            BookmarkStore.save(emptyList())
            MarksUi.exit()
            toast("已清空 $count 条")
            showAllMarks()
        },
    ).show()
}

/**
 * 在当前位置加一条书签。
 *
 * 摘录取当前章正文的前 [EXCERPT_MAX] 字 —— 阅读页并没有「光标」，
 * 拿不到用户正在看的那一句，只能给章首。这比空摘录强，至少能认出哪一章。
 * shortcut: 摘录是章首不是所见位置，等阅读页有滚动位置反查段落再改。
 */
internal fun MainActivity.addMark(info: BookInfo, index: Int, text: String) {
    val title = info.chapters.getOrNull(index)?.title ?: ""
    if (BookmarkStore.has(info.bookId, index)) {
        BookmarkStore.remove(info.bookId, index)
        toast("已移除本章书签")
        return
    }
    val excerpt = text.replace(Regex("\\s+"), " ").trim().take(EXCERPT_MAX)
    BookmarkStore.add(
        Bookmark(
            bookId = info.bookId,
            bookName = info.name,
            index = index,
            title = title,
            offset = readOffset(info),
            excerpt = excerpt,
            at = System.currentTimeMillis(),
        )
    )
    toast("已加书签")
}

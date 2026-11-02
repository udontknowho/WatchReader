package com.watchdl.ui.reader

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.BookInfo
import com.watchdl.core.Downloader
import com.watchdl.core.Screen
import com.watchdl.core.markSelectionPrune
import com.watchdl.core.markSelectionSelectAll
import com.watchdl.core.markSelectionToggle
import com.watchdl.core.recordClearMessage
import com.watchdl.core.recordDeleteMessage
import com.watchdl.core.recordMatch
import com.watchdl.core.recordSubtitle
import com.watchdl.core.recordSubtitleText
import com.watchdl.ui.my.showMy
import com.watchdl.ui.search.askText
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 阅读记录（#25）：跨书列出读过的书。
 *
 * 单书的进度屏只能从目录进，用户想「接着上次那本」得先想起是哪本；
 * 而进度本来就在 SharedPreferences 里存着，缺的只是这一页。
 *
 * 抄的是本地参考仓 `~/ref/legado-novel`（Compose/MD3，3.26.21）的
 * `ui/book/readRecord/ReadRecordScreen.kt`：
 *
 * | legado | 这里（189 × 248 dp 表盘） |
 * |---|---|
 * | 三种显示模式（聚合 / 时间线 / 最新）+ 切模式按钮 | 只有一档「按最近阅读排列」，等于它的 LATEST |
 * | 顶栏 切模式 / search / ⋮ 三动作 | 顶栏 search + ⋮ 两动作，副标题写死排序口径 |
 * | 长按进选择态，选择态顶栏改删除 | 长按进选择态，✕ / 已选 N / 全选 + 屏底「删除记录」 |
 * | 行 = 书名 + 章节 + 时间 + 进度 | 行 = 书名 + 「读至 12/345 章 · 3%」+ 28 dp ⋮ |
 * | 空态 no_read_record | 同口径文案：没有阅读记录 / 读过的书会出现在这里 |
 * | ⋮ 更多动作里有导出与统计 | 只有「清空全部阅读记录」 |
 *
 * 不做三模式：聚合与时间线要逐章历史，我们只存每本书的最后位置与最后时刻，
 * 造不出那两档历史，就不该在菜单里给假选项。
 */

/** 一行的数据。目录只取总量，不读正文。 */
private class RecordRow(
    val bookId: String,
    val name: String,
    val author: String,
    val total: Int,
    val index: Int,
    val at: Long,
)

/**
 * 屏内状态：搜索词与多选。
 *
 * 与书签页同样放文件级 object：只属于这一屏，离开就重来。
 * 选择集存 bookId —— 一本书只有一条记录，不会重名。
 */
internal object RecordsUi {

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

internal fun MainActivity.showRecords() {
    setScreen(Screen.Records)

    val all = loadRecords()
    val shown = all.filter { recordMatch(it.name, it.author, RecordsUi.query) }
    // 选中的书可能被删掉或被搜索过滤掉：留着会停在「已选 0」的死界面。
    if (RecordsUi.selecting && !markSelectionPrune(RecordsUi.selected, shown.map { it.bookId })) {
        RecordsUi.exit()
    }

    root.removeAllViews()
    if (RecordsUi.selecting) {
        root.addView(recordsSelectBar(shown.map { it.bookId }))
    } else {
        // 统计与排序口径都放顶栏副标题：248 dp 高的屏上，多一行统计就少一本书记录。
        root.addView(Widgets.topBar(this, "阅读记录", onBack = { showMy() },
            subtitle = recordSubtitleText(shown.size),
            trailing = listOf(
                "search" to { askRecordsQuery() },
                "more" to { showRecordsMenu(all) },
            )))
    }

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    if (shown.isEmpty()) {
        // 照 legado `no_read_record` 的口径：一句说没有，一句说什么时候会有。
        col.addView(Widgets.emptyState(this, "history", when {
            RecordsUi.query.isNotBlank() -> "没有书名或作者含「${RecordsUi.query}」的记录"
            else -> "还没有阅读记录\n读过的书会出现在这里"
        }))
        root.addView(body)
        return
    }

    col.addView(Widgets.sectionLabel(this, "共 ${shown.size} 本"))
    for (r in shown) col.addView(recordsRow(r))

    root.addView(body)
    if (RecordsUi.selecting) root.addView(recordsBatchBar())
}

/**
 * 读出全部记录行，按最后阅读时刻倒序。
 *
 * 用 restoreLight：这里只列全库，restore 会把每本的 chapters.jsonl 整个读一遍。
 * readTime==0 表示没记录过（只有旧存档的 pos_），不算读过，不列。
 */
private fun MainActivity.loadRecords(): List<RecordRow> {
    val store = jobsStore()
    return store.listBooks().mapNotNull { id ->
        val at = readTime(id)
        if (at <= 0L) return@mapNotNull null
        val job = Downloader.restoreLight(store, id) ?: return@mapNotNull null
        RecordRow(id, job.name.ifBlank { id }, job.author, job.chapters.size,
            readProgress(id), at)
    }.sortedByDescending { it.at }
}

/**
 * 一条记录。
 *
 * 照 legado 记录行的信息量（书名 + 章节 + 进度），但把「时间」从行里去掉：
 * 列表本来就按时间倒序，再写一遍「昨天」只是多占一行。
 * 选择态收起 ⋮，整行点击变成选中。
 */
private fun MainActivity.recordsRow(r: RecordRow): LinearLayout {
    val picked = r.bookId in RecordsUi.selected

    val row = Md3.row(this)
    row.minimumHeight = Md3.dp(this, Md3.ITEM_TALL)
    // 左右边距比分组标题各收一点：给 24 dp 勾与 28 dp ⋮ 腾位置。
    row.setPadding(Md3.dp(this, Md3.PAD * 3 / 4), 0, Md3.dp(this, Md3.PAD / 2), 0)
    row.background = Md3.ripple(this,
        if (picked) Md3.surface3 else android.graphics.Color.TRANSPARENT, Md3.R_ITEM.toFloat())

    if (RecordsUi.selecting) {
        row.addView(Icons.view(this, "check",
            if (picked) Md3.primary else Md3.onSurfaceVariant, 16).apply {
            alpha = if (picked) 1f else 0.3f
        }, LinearLayout.LayoutParams(Md3.dp(this, 24), Md3.dp(this, 24)).apply {
            rightMargin = Md3.dp(this@recordsRow, Md3.GAP / 2)
        })
    }

    val text = Md3.column(this)
    text.addView(Md3.ellipsize(Md3.text(this, r.name, Md3.FS_MD, Md3.onSurface)))
    text.addView(Md3.ellipsize(Md3.text(this, recordSubtitle(r.index, r.total), Md3.FS_SM,
        Md3.onSurfaceVariant)).apply { alpha = 0.7f })
    row.addView(text, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    if (!RecordsUi.selecting) {
        val menu = Md3.iconButton(this, "more", { showRecordRowMenu(r) }, desc = "更多操作")
        // iconButton 的最小热区是 48 dp 宽（M3 顶栏规格），行里只给 28 dp，放开限制。
        menu.minimumWidth = 0
        menu.minimumHeight = 0
        menu.setPadding(Md3.dp(this, 1), 0, Md3.dp(this, 1), 0)
        row.addView(menu, LinearLayout.LayoutParams(
            Md3.dp(this, 28), Md3.dp(this, Md3.ITEM_TALL)).apply { gravity = Gravity.CENTER_VERTICAL })
    }

    row.isClickable = true
    row.setOnClickListener {
        if (RecordsUi.selecting) toggleRecordSelection(r.bookId) else openRecord(r.bookId)
    }
    row.setOnLongClickListener {
        if (!RecordsUi.selecting) {
            RecordsUi.enter(r.bookId)
            showRecords()
        } else {
            toggleRecordSelection(r.bookId)
        }
        true
    }
    return row
}

/**
 * 选择态顶栏：✕ / 已选 N / 全选。
 *
 * 与书源、替换、书签三页同一个写法：M3 一枚图标槽 48 dp，三枚就吃掉 144 dp，
 * 189 dp 的屏上标题会被压成 0。删除放屏底批量条，顶栏不留第四槽。
 */
private fun MainActivity.recordsSelectBar(keys: List<String>): LinearLayout {
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
        RecordsUi.exit()
        showRecords()
    })
    bar.addView(Md3.ellipsize(
        Md3.text(this, "已选 ${RecordsUi.selected.size}", Md3.FS_MD, Md3.onSurface, bold = true))
        .apply { gravity = Gravity.CENTER_VERTICAL },
        LinearLayout.LayoutParams(0, Md3.dp(this, Md3.TAB_H), 1f))
    bar.addView(slot("select_all", "全选", 36) {
        markSelectionSelectAll(RecordsUi.selected, keys)
        showRecords()
    })
    return bar
}

/** 屏底批量条：删除记录。 */
private fun MainActivity.recordsBatchBar(): LinearLayout {
    val bar = Md3.row(this)
    bar.setPadding(Md3.dp(this, Md3.PAD / 2), 0, Md3.dp(this, Md3.PAD / 2),
        Md3.dp(this, Md3.GAP))
    val keys = RecordsUi.selected.toList()
    val b = Widgets.button(this, "删除记录", "tonal") {
        if (keys.isEmpty()) toast("先选记录") else confirmDeleteRecords(keys)
    }
    b.contentDescription = "删除记录"
    bar.addView(b, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
    return bar
}

/** 搜索记录。legado 是顶栏内联 SearchBar；手表上沿用本应用统一的输入对话框。 */
private fun MainActivity.askRecordsQuery() {
    askText("搜索阅读记录", "书名或作者关键词（留空清除）", null) { q ->
        RecordsUi.query = q.trim()
        showRecords()
    }
}

/**
 * 顶栏 ⋮。
 *
 * legado 的「更多动作」里有导出与统计。我们没有导出通道，也没有逐章历史可统计，
 * 所以只剩「清空全部」—— 这一项在 legado 里是选择态的全选后删除。
 */
private fun MainActivity.showRecordsMenu(all: List<RecordRow>) {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    if (RecordsUi.query.isNotEmpty()) {
        items += "清除搜索「${RecordsUi.query}」" to {
            RecordsUi.query = ""
            showRecords()
        }
    }
    items += "清空全部阅读记录（${all.size} 条）" to {
        if (all.isEmpty()) toast("还没有阅读记录") else confirmClearRecords(all.map { it.bookId })
    }
    Widgets.longMenuDialog(this, "阅读记录", items).show()
}

/**
 * 行菜单：跳转 / 删除这条记录。
 *
 * legado 点行开章节详情，删除靠长按多选；这里点行仍是跳到那本书（最常用的动作），
 * 删除留在 ⋮ 与多选两处，省一次长按。
 */
private fun MainActivity.showRecordRowMenu(r: RecordRow) {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += "跳到这一章" to { openRecord(r.bookId) }
    items += "删除这条记录" to { confirmDeleteRecords(listOf(r.bookId)) }
    Widgets.longMenuDialog(this, r.name, items).show()
}

/** 多选：点一条切选中。 */
private fun MainActivity.toggleRecordSelection(bookId: String) {
    markSelectionToggle(RecordsUi.selected, bookId)
    showRecords()
}

/**
 * 删记录，二次确认写明「只删记录」。
 *
 * 我们的「记录」就是 SharedPreferences 里的最后位置与时刻，
 * 所以删记录 = 清掉那四个键，书与正文（job 目录）一个字都不动。
 */
private fun MainActivity.confirmDeleteRecords(bookIds: List<String>) {
    if (bookIds.isEmpty()) return toast("先选记录")
    Widgets.md3Dialog(this,
        title = "删除记录",
        body = recordDeleteMessage(bookIds.size),
        confirm = "删除",
        onConfirm = {
            for (id in bookIds) clearRecord(id)
            RecordsUi.exit()
            toast("已删除 ${bookIds.size} 条")
            showRecords()
        },
    ).show()
}

private fun MainActivity.confirmClearRecords(bookIds: List<String>) {
    Widgets.md3Dialog(this,
        title = "清空阅读记录",
        body = recordClearMessage(),
        confirm = "清空",
        onConfirm = {
            for (id in bookIds) clearRecord(id)
            RecordsUi.exit()
            toast("已清空 ${bookIds.size} 条")
            showRecords()
        },
    ).show()
}

/**
 * 清掉一本书的阅读记录。
 *
 * 四个键一起删：`last_` 是时刻、`pos_` 与 `oidx_` 是章号、`off_` 是章内位置。
 * 只删 `last_` 的话这本书还在 ShelfScreen 里带进度，与「记录已删」自相矛盾。
 * tag 截 64 位与 [readTime] / [readProgress] 同口径，否则键对不上。
 */
private fun MainActivity.clearRecord(bookId: String) {
    val tag = bookId.take(64)
    getSharedPreferences("reader", android.content.Context.MODE_PRIVATE).edit()
        .remove("last_$tag")
        .remove("pos_$tag")
        .remove("oidx_$tag")
        .remove("off_$tag")
        // commit 而非 apply：删完立刻重画列表，异步写入会读到旧值。
        .commit()
}

/** 点一条回到那本书那章。目录现读：换过源或删过，章号可能已经失效。 */
private fun MainActivity.openRecord(bookId: String) {
    val job = Downloader.restore(jobsStore(), bookId)
    if (job == null) {
        toast("这本书已经不在本地了")
        return
    }
    val info = BookInfo(bookId, job.name.ifBlank { bookId }, job.author, job.chapters)
    val index = readProgress(bookId)
    // 换了源之后目录长度会变，旧章号可能越界；说一句比默默打开第一章好。
    if (index !in info.chapters.indices) {
        toast("这一章已经失效了")
        return
    }
    rememberReturn()
    preview(info, index)
}

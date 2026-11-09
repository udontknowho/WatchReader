package com.watchdl.ui.search

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.attachTabs
import com.watchdl.ui.book.showDetail
import com.watchdl.ui.shelf.showShelf
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 搜索：关键词输入、多源聚合结果、来源筛选、换源翻页。
 *
 * 照本地参考仓 `~/ref/legado-novel` 的 `ui/book/search/SearchScreen.kt` 对齐：
 * 顶栏 ⋮ 菜单（来源筛选 / 精准搜索 / 搜索历史 / 重搜）、搜索条 + 统计行、
 * 结果行右侧源数牌、长按看详情、搜索中的停止出口、按匹配档与源数排序。
 * 窗口状态（HITS_WINDOW / hitsWinStart / seenHits）留在 Activity。
 */

/** 搜索屏的界面状态。放文件级是因为它要跨屏活：重放结果屏时统计数字不能丢。 */
object SearchUi {
    /** 精准搜索，对应 legado 的 `MatchMode.EXACT`：只留书名或作者含关键词的命中。 */
    var exact = false

    /** 最近一轮的源进度。重放这一屏时 stat 为 null，靠它把统计牌画对。 */
    var responded = 0
    var total = 0

    /** 用户点了「停止搜索」。置上之后已发出的源一律不再上屏。 */
    var stopped = false

    /** 最近 10 条关键词，最新在前。进屏现读一次，改完立刻回写。 */
    var history: List<String> = emptyList()

    /**
     * 来源筛选：空串 = 看全部。
     *
     * legado 的结果屏有「按源分节」布局（`SearchSourceSection`），189 dp 宽的屏上
     * 一列卡片组根本放不下，这里压成「只看某一个源」的单选。
     * 不落盘：换关键词就清，属于当轮浏览状态。
     */
    var sourceFilter: String = ""
}

/** 搜索历史目录：`filesDir/search`。真写文件时才建，没搜过就不留空目录。 */
internal fun MainActivity.searchHistoryDir(): java.io.File = java.io.File(filesDir, "search")

/** ② 搜索结果。 */
/**
 * 下一批要搜的起点。返回 null 表示源已经轮过一圈了。
 *
 * 只有 pool（含被 MAX_SOURCES 剔除的源）大于本轮用量时，换一批才能真正换出新源；
 * 否则转一圈回到原处，翻页就是白翻。
 */
private fun MainActivity.nextSourceBatch(h: Screen.Hits): Int? =
    SourceRotation.next(h.offset, h.searchedSources, multi.searchable)
private fun MainActivity.hitKey(h: BookHit) = "${h.name.trim()}\u0001${h.author.trim()}"

/** 搜索结果页的「换一批源」。 */
private fun MainActivity.rotateSources(h: Screen.Hits) {
    val next = nextSourceBatch(h)
    if (next == null) {
        toast("源已经轮过一圈了")
    } else {
        seenHits.clear()
        doSearch(h.keyword, next)
    }
}

internal fun MainActivity.showHits(keyword: String, allHits: List<BookHit>, stat: MultiSource.Result? = null, offset: Int = 0,
                    batch: Int = 0, replay: Boolean = false) {
    // 排序（P2）：各源拼接顺序完全随机，实测 1700+ 条里目标书可能落在
    // 第 900 位。手表小屏只看得到几条，不排等于没有。
    //
    // 新的一次搜索要把窗口拍回开头：不重置就会停在上次翻到的位置，
    // 用户看到的是「新搜索结果从第 61 条开始」。
    if (!replay) hitsWinStart = 0
    // 换关键词就丢掉上一轮的来源筛选，否则新结果可能被筛成空屏。
    if (!replay) SearchUi.sourceFilter = ""
    if (stat != null) {
        SearchUi.responded = stat.responded
        SearchUi.total = stat.total
    }
    // 历史只记首发那一批：边跑边上屏时每个源回来都会重放这一屏，
    // 每来一次就写一遍盘，既白写也会把同一词反复顶到最前。
    if (!replay) SearchUi.history = SearchHistoryStore.add(searchHistoryDir(), keyword)
    //
    // 排序：档位（等值 > 包含 > 其它）+ 档内源数降序，见 [searchRanked]。
    // 合并同名书放在排序之后，保证留下的是「源数最多」的那一条。
    // 重放是「回到已经看过的那一屏」，条目必然都在 seenHits 里；
    // 再过滤一次会把整屏清空，用户看到的是「没有找到」。
    val all = allHits.asSequence()
        .filter { it.name.isNotBlank() }
        .filter { replay || hitKey(it) !in seenHits }
        // 精准搜索：关掉时这一句是恒等变换。开启后命中里都含关键词，
        // 分档自然落到 0–2 档，所以排序规则不必另写一套。
        .filter { !SearchUi.exact || searchPreciseKeep(it, keyword) }
        .toList()
    // 记下来，换一批时不再重复显示。
    if (!replay) all.forEach { seenHits += hitKey(it) }
    // 同名书共几源：必须在合并前统计，合并后每个名字只剩一条。
    val sourceCount = all.groupingBy { hitKey(it) }.eachCount()
    // 来源筛选要排在合并之前：同名书在别的源的命中本来就会被合并掉。
    val visible = if (SearchUi.sourceFilter.isEmpty()) all
                  else all.filter { it.sourceName.trim() == SearchUi.sourceFilter }
    // 排序照 legado `sortedWithSearchPriority`：档位（等值 > 包含 > 其它），
    // 档内源数多的靠前 —— 多源都收了的书更可信。合并放在排序之后，
    // 留下的才是「源数最多」的那一条。
    val hits = searchRanked(visible, keyword) { sourceCount[hitKey(it)] ?: 1 }
        .distinctBy { hitKey(it) }
    setScreen(Screen.Hits(keyword, hits, offset, stat?.total ?: 0, all))
    root.removeAllViews()
    // legado 顶栏三个动作（设置 / 精准搜索 / 筛选范围）。手表只挂一个 ⋮，
    // 三个动作都进整屏菜单 —— 189dp 宽的栏放第三个图标，标题就只剩四个字。
    root.addView(Widgets.topBar(this, "搜索结果", subtitle = keyword,
        onBack = { goBackToPrevious() },
        trailing = listOf("more" to {
            searchMenu(keyword, allHits) {
                // 改完筛选/精准档要重新排序，窗口也得从第一条起。
                hitsWinStart = 0
                showHits(keyword, allHits, stat, offset, batch, replay = true)
            }
        })))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    // 搜索条 + 统计牌合成一条（见 [searchBand]）：关键词一行、统计一行。
    col.addView(searchBand(
        keyword = keyword,
        summary = searchSummaryText(hits.size, SearchUi.responded, SearchUi.total),
        // 点条身改词，右侧 × 清空后回到搜索历史（我们的「建议面板」等价物）。
        onEdit = { askKeyword(initial = keyword) },
        onClear = { openHistory(keyword) },
    ), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = Md3.dp(this@showHits, Md3.GAP / 2) })

    // 换一批源：**固定在滚动区外**，不跟着列表一起滑走。
    // 放列表顶部的话，一往下滑就看不见了 —— 而那正是想换一批的时候。
    // 重进这一屏时 stat 为 null，改用屏上记的批次大小。
    val batch = stat?.total ?: batch.takeIf { it > 0 }
        ?: (curScreen as? Screen.Hits)?.searchedSources ?: 0
    val hitsScreen = Screen.Hits(keyword, hits, offset, batch, all)
    val sourceTotal = multi.searchable
    val canRotate = batch > 0 && offset + batch < sourceTotal
    val remaining = sourceTotal - offset - batch
    // 当前屏的批次状态，换一批后 [showHits] 靠它算下一批。
    setScreen(hitsScreen)

    if (hits.isEmpty()) {
        // N6：15 个源全挂（断网、DNS 挂了）时，说「换个关键词」是错的建议 ——
        // 用户会去改关键词，而问题在网络。responded 为 0 说明一条源都没回话。
        val msg = when {
            // 筛选成空最容易误会成「这本书没有」：先说是看的哪个源。
            SearchUi.sourceFilter.isNotEmpty() ->
                "「$keyword」在这个来源没有命中\n点 ⋮ 换成全部来源"
            SearchUi.responded == 0 && SearchUi.total > 0 ->
                "所有源都没有响应\n检查网络后再试"
            SearchUi.responded < SearchUi.total ->
                "没有找到「$keyword」\n${SearchUi.total - SearchUi.responded} 个源没响应，可换一批"
            else -> "没有找到「$keyword」\n换个关键词试试"
        }
        col.addView(Widgets.emptyState(this, "∅", msg), Md3.lpWeight(1f))
    } else {
        // 510 条结果一次性建 510 个 View，手表上量到 616ms 掉帧（2026-10-27 日志）。
        // 跟目录那一屏用同一个办法：只建一窗，翻页换窗。
        val winStart = hitsWinStart.coerceIn(0, maxOf(0, hits.size - HITS_WINDOW))
        hitsWinStart = winStart
        val winEnd = (winStart + HITS_WINDOW).coerceAtMost(hits.size)
        for (h in hits.subList(winStart, winEnd)) {
            // 同一本书多个源时在书名右侧挂一枚数字小牌，否则用户以为是两本不同的书。
            col.addView(searchHitItem(h, sourceCount[hitKey(h)] ?: 1) { openHit(h) })
        }
        // 部分源没回话：legado 把这句放在列表尾部（`search_partial_footer`），
        // 不占首屏。以前它压在统计牌第二行，一屏 248 dp 里白吃一行列表。
        searchPartialHint(SearchUi.responded, SearchUi.total)?.let { hint ->
            col.addView(Md3.ellipsize(Md3.text(this, hint, Md3.FS_SM, Md3.onSurfaceVariant))
                .apply {
                    alpha = 0.8f
                    setPadding(0, Md3.dp(this@showHits, 8), 0, 0)
                })
        }
        // 翻页条：只在结果超过一窗时才出，否则会让小屏多占两行。
        if (hits.size > HITS_WINDOW) {
            val nav = Widgets.button(this, "${winStart + 1}–$winEnd / ${hits.size} 条", "tonal") {
                hitsWinStart = if (winEnd >= hits.size) 0 else winEnd
                showHits(keyword, allHits, stat, offset, batch, replay = true)
            }
            // 一窗是 HITS_WINDOW（60）条，不是一百条：说「一百」会让用户以为丢了一半。
            nav.contentDescription = "翻到下一批，一次 $HITS_WINDOW 条"
            col.addView(nav)
        }
    }
    root.addView(body)

    // 换一批源：放在**固定底栏**，列表怎么滑都在。
    if (canRotate) {
        val next = Widgets.button(this, "换一批（还剩 $remaining 条源）", "tonal") {
            rotateSources(hitsScreen)
        }
        next.contentDescription = "换一批源，还有 $remaining 条源没搜过"
        next.layoutParams = Md3.lpDp(this, null, Md3.BTN).apply {
            topMargin = Md3.dp(this@showHits, Md3.GAP / 2)
            bottomMargin = Md3.dp(this@showHits, Md3.GAP / 2)
        }
        root.addView(next)
    }
}

/**
 * 顶部搜索条：关键词一行，统计一行，右侧 × 清空。
 *
 * legado 的 `SearchScreen` 把常驻 `SearchBar` 放在顶栏下面，另有独立的浮动统计牌
 * （`SearchFloatingSummary`）。两块加起来近 90 dp，248 dp 高的屏上列表只剩一行，
 * 所以并成一条：点条身改关键词，点 × 回到搜索历史。
 */
private fun MainActivity.searchBand(
    keyword: String,
    summary: CharSequence,
    onEdit: () -> Unit,
    onClear: () -> Unit,
): LinearLayout {
    val band = Md3.row(this)
    band.background = Md3.ripple(this, Md3.surface3, Md3.R_ITEM.toFloat())
    band.setPadding(Md3.dp(this, Md3.PAD * 0.6), Md3.dp(this, 4),
                    Md3.dp(this, 2), Md3.dp(this, 4))
    band.isClickable = true
    band.setOnClickListener { onEdit() }
    band.contentDescription = "当前关键词 $keyword，点按改词"

    val col = Md3.column(this)
    col.addView(Md3.ellipsize(Md3.text(this, keyword, Md3.FS_MD, Md3.onSurface)))
    col.addView(Md3.ellipsize(Md3.text(this, summary, Md3.FS_SM, Md3.onSurfaceVariant))
        .apply { alpha = 0.8f })
    band.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    // × 只有 32 dp 宽：搜索条本身才 40 多 dp 高，再给 48 dp 触控槽就把关键词挤没了。
    // 纵向 36 dp 仍高于条高，手指能点到。
    val clear = Icons.view(this, "close", Md3.onSurfaceVariant, 18).apply {
        background = Md3.ripple(this@searchBand, android.graphics.Color.TRANSPARENT, Md3.TAP / 2f)
        isClickable = true
        contentDescription = "清除关键词，从搜索历史另选"
        setOnClickListener { onClear() }
    }
    band.addView(clear, LinearLayout.LayoutParams(Md3.dp(this, 32), Md3.dp(this, 36)))
    return band
}

/**
 * 一条搜索结果，照 legado `SearchBookListItem` 的三层结构。
 *
 * 不复用 [Widgets.listItem]：它给的是「标题 + 副标题」两层，
 * 而这里要「标题行右侧挂源数小牌 + 两行副信息」，压不进那套参数。
 */
private fun MainActivity.searchHitItem(h: BookHit, sourceCount: Int, onClick: () -> Unit): LinearLayout {
    val row = Md3.row(this)
    // 结果屏沿用 highlight 底色（surface3）+ 水波纹，与其它列表项同一观感。
    row.background = Md3.ripple(this, Md3.surface3, Md3.R_ITEM.toFloat())
    row.setPadding(Md3.dp(this, Md3.PAD), Md3.dp(this, 4),
                   Md3.dp(this, Md3.PAD), Md3.dp(this, 4))
    row.minimumHeight = Md3.dp(this, Md3.ITEM)
    row.isClickable = true
    row.setOnClickListener { onClick() }
    // legado 长按弹预览层（看详情 / 加入书架）。手表上换成一屏长菜单。
    row.setOnLongClickListener { showHitMenu(h); true }
    row.contentDescription = "${h.name}，${h.author}"

    // 封面 40 × 56 dp。legado 给 72 dp（5:7），在这块屏上要占掉四成宽，
    // 书名就只剩十来个字；比例仍按 5:7 压，与书架、详情页的占位封面同一算法。
    val first = h.name.trim().let { if (it.isEmpty()) "书" else it.substring(0, 1) }
    val cover = Md3.text(this, first, Md3.FS_MD, Md3.onPrimaryContainer, bold = true).apply {
        gravity = Gravity.CENTER
        background = Md3.bg(Md3.nameColor(h.name), Md3.R_GRID.toFloat())
    }
    row.addView(cover, LinearLayout.LayoutParams(Md3.dp(this, 40), Md3.dp(this, 56)).apply {
        rightMargin = Md3.dp(this@searchHitItem, 7)
    })

    val col = Md3.column(this)
    val titleRow = Md3.row(this)
    titleRow.addView(Md3.ellipsize(Md3.text(this, h.name, Md3.FS_MD, Md3.onSurface)),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    // 源数小牌：legado 把它画成独立 TextCard，不塞进书名（塞进去书名会被括号吃掉一截）。
    // 189 dp 上写「3 源」还嫌宽，只留数字，含义交给 contentDescription。
    searchSourceBadge(sourceCount)?.let { text ->
        titleRow.addView(Md3.text(this, text, Md3.FS_SM, Md3.onSecondaryContainer).apply {
            gravity = Gravity.CENTER
            // 4dp 圆角是 M3 小标签档，比列表卡的 12dp 轻一档，不抢书名。
            background = Md3.bg(Md3.secondaryContainer, 4f)
            setPadding(Md3.dp(this@searchHitItem, 4), 0, Md3.dp(this@searchHitItem, 4),
                       Md3.dp(this@searchHitItem, 1))
            contentDescription = "$sourceCount 个源"
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = Md3.dp(this@searchHitItem, 4) })
    }
    col.addView(titleRow, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    // 第二行「作者 · 最新: 章名」，没有最新章就退回源名（见 searchHitLine）。
    col.addView(Md3.ellipsize(Md3.text(this,
        searchHitLine(h.author, h.extra, h.sourceName), Md3.FS_SM, Md3.onSurfaceVariant))
        .apply { alpha = 0.7f }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    // 简介压 1 行。legado 给 2 行，189dp 高的一屏只放得下两张卡就翻不动了，
    // 压成 1 行后一窗能看到三张半，摘要仍够判断「是不是这本」。
    val intro = h.abstractText.trim().replace(Regex("\\s+"), " ")
    if (intro.isNotEmpty()) {
        col.addView(Md3.ellipsize(Md3.text(this, intro, Md3.FS_SM, Md3.onSurfaceVariant))
            .apply { alpha = 0.55f }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return row
}

/**
 * 顶栏 ⋮ 菜单。搜索结果屏与搜索中屏共用。
 *
 * 对应 legado 顶栏的三个动作（设置 / 精准搜索 / 筛选范围）：
 * 189 dp 宽的栏放三个图标，标题就只剩四个字，所以全部压进这个整屏菜单。
 *
 * [hits] 是这一轮的全部命中（合并前），用来列「来源」筛选的条数；
 * [redraw] 在改完状态后重画这一屏：结果屏是重放，搜索中屏什么都不做。
 */
private fun MainActivity.searchMenu(
    keyword: String,
    hits: List<BookHit> = emptyList(),
    redraw: () -> Unit,
) {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    // 搜中屏没结果可筛，就不出这一项（否则点进去是个空菜单）。
    if (hits.isNotEmpty()) {
        val scope = SearchUi.sourceFilter.ifEmpty { "全部来源" }
        items += "来源：$scope" to { openSourceFilter(hits) { redraw() } }
    }
    items += "精准搜索：${if (SearchUi.exact) "开" else "关"}" to {
        SearchUi.exact = !SearchUi.exact
        toast(if (SearchUi.exact) "只留书名或作者命中" else "恢复按匹配度排序")
        redraw()
    }
    items += "搜索历史" to { openHistory(keyword) }
    items += "重新搜索当前词" to { doSearch(keyword) }
    // 不做「内容质量检测」「类型筛选（有声/小说）」：两者都要再拉一遍正文或
    // 新增类型字段，189 dp 上还各要一层筛选条。见提交说明。
    Widgets.longMenuDialog(this, "搜索", items).show()
}

/**
 * 来源筛选：legado 的「按源分节」布局在手表上压成单选。
 *
 * legado 的节头写「源名 + 命中数」（`SearchSourceSection`），这里直接把
 * 命中数写进选项里，选中后只看这一条源的命中。
 */
private fun MainActivity.openSourceFilter(hits: List<BookHit>, apply: () -> Unit) {
    val counts = hits.groupingBy { it.sourceName.trim().ifEmpty { "未标注来源" } }
        .eachCount()
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += "全部来源（${hits.size} 条）" to {
        SearchUi.sourceFilter = ""
        apply()
    }
    for ((name, n) in counts.entries.sortedWith(
        compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })) {
        items += "$name（$n 条）" to {
            SearchUi.sourceFilter = name
            toast("只看「$name」")
            apply()
        }
    }
    Widgets.longMenuDialog(this, "按来源看", items).show()
}

/** 最近 8 条关键词，点一条就重搜；末项可单删或清空（都要能反悔）。 */
private fun MainActivity.openHistory(currentKeyword: String) {
    val words = SearchUi.history.take(SEARCH_HISTORY_SHOWN)
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    for (w in words) items += w to { doSearch(w) }
    if (words.isEmpty()) {
        // 空历史也给一行可点的东西：整屏菜单没有任何条目时，
        // 屏上只剩标题和关闭键，看着像坏了。
        items += "还没有搜索记录" to { toast("搜过的词会出现在这里") }
    } else {
        // legado 的历史行右侧有 × 单删。整屏菜单的行没有尾部按钮位，
        // 拆成二级菜单：再列一遍，点谁删谁。
        items += "删除记录…" to { openHistoryDelete() }
        items += "清空历史" to { confirmClearHistory() }
    }
    Widgets.longMenuDialog(this, "搜索历史", items).show()
}

/** 单删一条历史。删完回不到这一屏（菜单已关），再点一次就再删一条。 */
private fun MainActivity.openHistoryDelete() {
    val words = SearchUi.history.take(SEARCH_HISTORY_MAX)
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    for (w in words) items += "删除「$w」" to {
        SearchUi.history = SearchHistoryStore.remove(searchHistoryDir(), w)
        toast("已删除「$w」")
    }
    Widgets.longMenuDialog(this, "删除记录", items).show()
}

/** 清空历史：legado 也是二次确认，误点一次就没了太亏。 */
private fun MainActivity.confirmClearHistory() {
    Widgets.md3Dialog(this, "清空搜索历史",
        body = "最近 ${SearchUi.history.size} 条关键词会被删掉，已经搜到的结果不受影响。",
        confirm = "清空",
        onConfirm = {
            SearchUi.history = SearchHistoryStore.clear(searchHistoryDir())
            toast("已清空搜索历史")
        }).show()
}

/** 长按命中项：legado 弹预览层，能看详情也能上架。手表上只留「查看详情」。 */
private fun MainActivity.showHitMenu(h: BookHit) {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += "查看详情" to { openDetailOnly(h) }
    items += "重新搜索《${h.name}》" to { doSearch(h.name) }
    // 「加入书架」暂缺：本项目的书架由下载任务驱动（Downloader / JobStore），
    // 没有「只上架不下载」的入口，造一个会和现有书架过滤打架。见提交说明。
    Widgets.longMenuDialog(this, h.name, items).show()
}

/**
 * 只进详情页，不碰目录。
 *
 * [openHit] 是点击入口，本地已有下载时会直接跳目录；长按要看的是书本身。
 */
private fun MainActivity.openDetailOnly(h: BookHit) {
    val src = sourceFor(h)
    rememberReturn()
    detailCandidates = siblingsOf(h)
    showDetail(BookInfo(h.bookId, h.name, h.author, emptyList(), intro = h.abstractText), src)
}

/** 用手表上的输入对话框要关键词。[initial] 是带进来的旧词，结果屏改词时用。 */
internal fun MainActivity.askKeyword(initial: String = "") {
    askText("搜索小说", "书名或作者", "请先输入书名", initial = initial) { doSearch(it) }
}

/** 目录过滤：按章名找。空关键词等于清空过滤。 */
internal fun MainActivity.askTocQuery(info: BookInfo, src: BookSource?) {
    askText("找章节", "章名关键词", null) { q ->
        tocQuery = q
        showToc(info, src)
    }
}

/**
 * 要一段文字的输入对话框。
 *
 * [emptyHint] 非空时空输入会提示这句并留在窗里；为 null 时空输入也算确认
 * （目录过滤靠空值清空）。
 */
internal fun MainActivity.askText(
    title: String,
    hintText: String,
    emptyHint: String?,
    /** 预填内容，光标落在末尾。改词时用户多半只改一两个字。 */
    initial: String = "",
    onOk: (String) -> Unit,
) {
    // 空关键词要留在窗里（P1-1）。md3Dialog 的确认键是「先关窗再回调」，
    // 所以「空就不关」只能重开一次：整套视图放进 open()，每次新建控件。
    // 复用同一个 EditText 会撞上 "already has a parent"，复用同一个对话框
    // 又要和 dismiss 抢时序，两条都更容易出怪状态。
    fun open() {
        // 平台默认输入框是绿色下划线、绿色按钮、20sp 标题，与 MD3 紫系冲突。
        // 这里换成自绘圆角输入框，按钮交给 md3Dialog（text 变体 + 主色确认）。
        val field = EditText(this).apply {
            hint = hintText
            setSingleLine()
            if (initial.isNotEmpty()) {
                setText(initial)
                setSelection(initial.length)
            }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Md3.FS_MD)
            setHintTextColor(Md3.onSurfaceVariant)
            setTextColor(Md3.onSurface)
            background = Md3.bg(Md3.surface4, Md3.dp(this@askText, 8).toFloat())
            setPadding(Md3.dp(this@askText, 10), 0, Md3.dp(this@askText, 10), 0)
            // 不再外套一层带 padding 的容器：md3Dialog 已给 16dp，
            // 高度只要给输入框自身的最小高度。
            minimumHeight = Md3.dp(this@askText, Md3.BTN + 4)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        input = field
        val dialog = Widgets.md3Dialog(this, title, content = field,
            confirm = "确定", dismiss = "取消",
            onConfirm = {
                val kw = field.text.toString().trim()
                // 确认键已经关过窗了，空的时候补一句重开，别让用户白输。
                if (kw.isEmpty() && emptyHint != null) { toast(emptyHint); open() } else onOk(kw)
            })
        // 键盘上的「搜索」不经过对话框按钮，关窗要自己做。
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val kw = field.text.toString().trim()
                if (kw.isEmpty() && emptyHint != null) toast(emptyHint)
                else { dialog.dismiss(); onOk(kw) }
                true
            } else false
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        // 手机上自动弹键盘，省一次点击。非主屏（手表/虚拟屏）不弹：
        // 那里没有键盘，弹出来只会盖住界面 —— 实测在虚拟屏上会把整帧渲染卡住。
        if ((display?.displayId ?: android.view.Display.DEFAULT_DISPLAY) ==
            android.view.Display.DEFAULT_DISPLAY) {
            field.requestFocus()
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }
    open()
}
internal fun MainActivity.doSearch(keyword: String, offset: Int = 0) {
    // screen 必须同步跟上：进度回调靠它认屏，不换的话还停在上一屏，
    // 回调全被屏守卫丢掉 —— 进度条不动、结果也回不来。
    // 新的一轮搜索要清掉上一轮的「已见过」，否则同名书会被自己的旧记录过滤掉。
    if (offset == 0) {
        seenHits.clear()
        // 统计牌要跟着这一轮走：不清掉就会显示上一轮的进度。
        SearchUi.responded = 0
        SearchUi.total = 0
    }
    // 每一轮都是新的：上一轮点过「停止」不能把这一轮的源也挡掉。
    SearchUi.stopped = false
    setScreen(Screen.Hits(keyword, emptyList(), offset, 0))
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "搜索中…", subtitle = keyword,
        onBack = { showShelf() },
        trailing = listOf("more" to { searchMenu(keyword) {} })))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    // 进度文本每多一个源就改一次（P1-2）。以前 15~20 秒里只有一个静态的
    // 「正在搜索」，用户分不清是在跑还是卡死了。
    // 不能用 Widgets.emptyState —— 它内部自建 TextView，外面拿不到引用改字。
    val box = Md3.column(this).apply {
        gravity = Gravity.CENTER
        setPadding(Md3.dp(this@doSearch, 12), Md3.dp(this@doSearch, 24),
                   Md3.dp(this@doSearch, 12), Md3.dp(this@doSearch, 24))
    }
    box.addView(Icons.view(this, "refresh", Md3.onSurfaceVariant, 32).apply { alpha = 0.3f },
        Md3.lpDp(this, null, 32))
    val progress = Md3.text(this, "正在搜索「$keyword」\n已扫 0 个源", Md3.FS_MD, Md3.onSurfaceVariant)
        .apply {
            gravity = Gravity.CENTER
            setPadding(0, Md3.dp(this@doSearch, 10), 0, 0)
        }
    box.addView(progress)
    // 进程条 + 出口提示：原来只有一个静态数字，卡住时用户不知道能不能退。
    val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this@doSearch, 3),
        ).apply { topMargin = Md3.dp(this@doSearch, 8) }
    }
    box.addView(bar)
    box.addView(Md3.text(this, "超过 15 秒可以返回重试", Md3.FS_SM, Md3.onSurfaceVariant).apply {
        gravity = Gravity.CENTER
        setPadding(0, Md3.dp(this@doSearch, 8), 0, 0)
    })
    col.addView(box)
    root.addView(body)

    // 搜索中的出口（legado 底部 FAB「停止」）。189 dp 上不放 FAB：
    // 圆形按钮盖住列表右下角，这里用一条等宽按钮，位置与结果屏的「换一批」一致。
    // 停止后同一个位置变成「返回书架」—— 回调已被挡掉，屏上不会再变，
    // 不留出口的话用户只剩顶栏返回键这一条路。
    val stop = Widgets.button(this, "停止搜索", "tonal")
    stop.contentDescription = "停止搜索，已发出的源仍会返回但不再上屏"
    stop.setOnClickListener {
        if (SearchUi.stopped) { showShelf(); return@setOnClickListener }
        SearchUi.stopped = true
        stop.text = "返回书架"
        stop.contentDescription = "返回书架"
        // 进度条停在那里不动会看着像还在跑，换成明确的一句话。
        bar.isIndeterminate = false
        progress.text = "已停止，等已发出的源返回"
        toast("已停止，等已发出的源返回")
    }
    stop.layoutParams = Md3.lpDp(this, null, Md3.BTN).apply {
        topMargin = Md3.dp(this@doSearch, Md3.GAP / 2)
        bottomMargin = Md3.dp(this@doSearch, Md3.GAP / 2)
    }
    root.addView(stop)

    val owner: Screen = Screen.Hits(keyword, emptyList(), offset, 0)
    // 边跑边上屏：第一个带回命中的源到达就先显示，不等最慢那个源（实测 15 秒）。
    // 累加器在 UI 线程外被写，同步表包一层。
    val shown = java.util.Collections.synchronizedList(mutableListOf<BookHit>())
    bg(owner, {
        // 多源聚合：并发 5、总超时 15 秒，单源失败静默跳过。
        // offset 让「换一批源」从下一批开始，而不是重跑同一批。
        multi.searchDetailed(keyword, onProgress = { done, total ->
            postAlive(owner) {
                // 用户点过「停止」：已发出的源还在跑，但它们的结果一律不上屏。
                if (SearchUi.stopped) return@postAlive
                progress.text = "正在搜索「$keyword」\n已扫 $done / $total 个源"
                bar.isIndeterminate = false
                bar.max = total.coerceAtLeast(1)
                bar.progress = done
            }
        }, offset = offset, onPartial = { fresh, done, total ->
            shown.addAll(fresh)
            postAlive(owner) {
                if (SearchUi.stopped) return@postAlive
                // 只在「还停在同一关键词的结果屏」且「列表没被滚动」时重绘。
                // 用户已经滚下去看别的书时刷屏，比慢更不能忍。
                val cur = curScreen as? Screen.Hits
                if (cur != null && cur.keyword == keyword && hitsWinStart == 0) {
                    // replay = true：不重置窗口、不写 seenHits。
                    // 写了的话下面的最终结果会被自己过滤成空表。
                    showHits(keyword, shown.toList(),
                        MultiSource.Result(shown.toList(), total, done, emptyList()),
                        offset = offset, replay = true)
                }
            }
        })
    }) { res ->
        res.onSuccess { r ->
            DevLog.log("WatchDL",
                "搜索「$keyword」：${r.hits.size} 条 / 响应 ${r.responded}/${r.total} 源" +
                if (r.failed.isEmpty()) "" else "，失败 ${r.failed.size}: ${r.failed.take(5)}")
            if (SearchUi.stopped) return@onSuccess
            showHits(keyword, r.hits, r, offset)
        }.onFailure { e ->
            toast("搜索失败：${e.message}")
            showShelf()
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
internal fun MainActivity.sourceFor(hit: BookHit): BookSource = multi.pickFor(hit) ?: source

/** 打开一条命中项（搜索结果的点击入口）：先进书详情，再往目录去。 */
internal fun MainActivity.openHit(hit: BookHit) {
    val src = sourceFor(hit)
    // 记下搜索结果这一屏，详情页返回时原样退回去。
    rememberReturn()
    val job = Downloader.restore(jobsStore(), hit.bookId)
    if (job != null) {
        // 已经下到本地的书，用户点它就是要接着读，不必再绕一层详情。
        showToc(BookInfo(hit.bookId, job.name.ifBlank { hit.name }, job.author.ifBlank { hit.author }, job.chapters), src)
        return
    }
    // 详情页的「查看目录」还要走逐源重试，候选源得跟着带过去。
    detailCandidates = siblingsOf(hit)
    showDetail(BookInfo(hit.bookId, hit.name, hit.author, emptyList(),
        intro = hit.abstractText), src)
}
/**
 * 取同一本书的其它源：优先当前搜索结果里同名的，其次就是它自己。
 *
 * 按当前顺序依次试（列表已按源名排过，同名的挨着），
 * 让被点的那一条排第一。
 */
internal fun MainActivity.siblingsOf(hit: BookHit): List<BookHit> {
    val all = (curScreen as? Screen.Hits)?.all.orEmpty()
    val same = all.filter {
        it.name.trim() == hit.name.trim() && it.author.trim() == hit.author.trim()
    }
    return (listOf(hit) + same).distinctBy { it.sourceName }
}

/** 发现页仅保留本次会话快照；主动刷新和更改筛选才重新取数。 */
private object RankUiSession {
    var site = RankConfig.Site.QIDIAN
    var gender = RankConfig.Gender.MALE
    var requestSerial = 0L
    val snapshots = LinkedHashMap<String, Screen.Discover>()
}

private fun MainActivity.availableRanks(): List<RankConfig.Rank> =
    rankCache ?: RankConfig.local(java.io.File(filesDir, "sources")).also { rankCache = it }

internal fun MainActivity.showDiscoverPage(rankKey: String = "") {
    if (rankKey.isEmpty()) {
        showDiscoverSnapshot(Screen.Discover(site = RankUiSession.site, gender = RankUiSession.gender))
        return
    }
    val rank = availableRanks().firstOrNull { it.key == rankKey }
    val state = Screen.Discover(rankKey, site = rank?.site ?: RankUiSession.site,
        gender = rank?.gender ?: RankUiSession.gender)
    val cached = RankUiSession.snapshots.values.lastOrNull { it.rankKey == rankKey }
    if (cached != null) showDiscoverSnapshot(cached) else requestRank(state)
}

internal fun MainActivity.backFromDiscover(s: Screen.Discover) {
    when (s.stage) {
        "content" -> goBackToPrevious()
        "boards" -> goBackToPrevious()
        else -> showShelf()
    }
}

/** 每个快照自带 filter、hits、error、loaded 与 scrollY；重放绝不发请求。 */
internal fun MainActivity.showDiscoverSnapshot(state: Screen.Discover) {
    setScreen(state)
    RankUiSession.site = state.site
    RankUiSession.gender = state.gender
    root.removeAllViews()
    val rank = availableRanks().firstOrNull { it.key == state.rankKey }
    val title = when (state.stage) {
        "content" -> rank?.rankLabel() ?: "榜单不存在"
        "boards" -> "选择榜单"
        else -> "发现"
    }
    if (state.stage == "sites") {
        root.addView(Widgets.headerRow(this, title,
            trailing = listOf("search" to { rememberReturn(); askKeyword() })))
    } else {
        root.addView(Widgets.topBar(this, title, onBack = { backFromDiscover(state) },
            trailing = if (state.stage == "content") listOf("refresh" to {
                requestRank(state.copy(scrollY = 0))
            }) else emptyList()))
    }
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    root.addView(body)
    when (state.stage) {
        "sites" -> {
            col.addView(Widgets.button(this, "搜索书名或作者", "tonal") {
                rememberReturn(); askKeyword()
            })
            for ((site, label) in listOf(RankConfig.Site.QIDIAN to "起点中文网",
                RankConfig.Site.FANQIE to "番茄小说")) {
                col.addView(Widgets.listItem(this, label,
                    if (site == RankConfig.Site.QIDIAN) "九类榜单 · 男生 / 女生" else "分类阅读榜 · 男生 / 女生",
                    twoLine = true, highlight = site == state.site) {
                    rememberReturn()
                    showDiscoverSnapshot(state.copy(site = site, stage = "boards", scrollY = 0))
                })
            }
        }
        "boards" -> {
            col.addView(Widgets.button(this, "${state.site.siteLabel()} · ${state.gender.genderLabel()} · 切换", "tonal") {
                chooseRankChannel(state)
            })
            for (r in RankConfig.select(availableRanks(), state.site, state.gender)) {
                col.addView(Widgets.listItem(this, r.rankLabel(), "查看榜单", highlight = false) {
                    rememberReturn()
                    showDiscover(r.key)
                })
            }
        }
        else -> {
            col.addView(Widgets.button(this, "${state.site.siteLabel()} · ${state.gender.genderLabel()} · 换榜", "tonal") {
                chooseRankBoard(state)
            })
            if (rank != null && (RankConfig.qidianCategories(rank).size > 1 ||
                    RankConfig.qidianPeriods(rank).isNotEmpty())) {
                val category = RankConfig.qidianCategories(rank).firstOrNull { it.id == state.filter.categoryId }?.name ?: "全站"
                val period = if (RankConfig.qidianPeriods(rank).isEmpty()) "" else " · ${state.filter.period.label}"
                col.addView(Widgets.button(this, "$category$period · 筛选", "tonal") { chooseRankFilter(state, rank) })
            }
            if (state.error.isNotBlank()) {
                col.addView(Widgets.emptyState(this, "warning", state.error))
                col.addView(Widgets.button(this, "重试当前筛选", "tonal") { requestRank(state.copy(scrollY = 0)) })
            }
            if (!state.loaded && state.error.isEmpty()) {
                col.addView(Widgets.emptyState(this, "refresh", "正在加载榜单…"))
                // 离开加载页会取消请求；返回快照时提供显式继续入口。
                col.addView(Widgets.button(this, "重新加载", "text") { requestRank(state.copy(scrollY = 0)) })
            }
            state.hits.forEachIndexed { i, hit ->
                col.addView(Widgets.listItem(this, "${i + 1}  ${hit.name}",
                    listOf(hit.author, hit.extra).filter { it.isNotBlank() }.joinToString(" · "),
                    twoLine = true, highlight = false) { openDiscoverHit(hit) })
            }
            if (state.loaded && state.error.isEmpty()) {
                col.addView(Md3.text(this, when {
                    state.hits.isEmpty() -> "该筛选下暂无书目"
                    state.site == RankConfig.Site.QIDIAN -> "已展示本站首批 · 后续分页尚未确认"
                    else -> "已展示全部榜单书目"
                }, Md3.FS_SM, Md3.onSurfaceVariant))
            }
        }
    }
    attachTabs(1)
    // 在离开屏幕前，rememberReturn 还能再取得最新的实际像素位置。
    body.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
        val current = curScreen as? Screen.Discover
        if (current != null && screenId(current) == screenId(state)) {
            val updated = current.copy(scrollY = y)
            setScreen(updated)
            if (updated.loaded) {
                RankUiSession.snapshots.remove(screenId(updated))
                RankUiSession.snapshots[screenId(updated)] = updated
            }
        }
    }
    body.post { if (screenId(curScreen) == screenId(state)) body.scrollTo(0, state.scrollY) }
}

private fun RankConfig.Site.siteLabel(): String = if (this == RankConfig.Site.QIDIAN) "起点" else "番茄"
private fun RankConfig.Gender.genderLabel(): String = if (this == RankConfig.Gender.FEMALE) "女生" else "男生"
private fun RankConfig.Rank.rankLabel(): String = if (site == RankConfig.Site.QIDIAN)
    name.removePrefix("起点").removePrefix("女频") else name.substringBefore(" · ")

private fun MainActivity.chooseRankChannel(state: Screen.Discover) {
    val items: List<Pair<CharSequence, () -> Unit>> = RankConfig.Gender.values().map { gender ->
        "${gender.genderLabel()}${if (gender == state.gender) " · 当前" else ""}" to {
            showDiscoverSnapshot(state.copy(gender = gender, scrollY = 0))
        }
    }
    Widgets.longMenuDialog(this, "${state.site.siteLabel()}频道", items).show()
}

private fun MainActivity.chooseRankBoard(state: Screen.Discover) {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += "切换站点与频道" to {
        rememberReturn()
        showDiscoverSnapshot(Screen.Discover(site = state.site, gender = state.gender))
    }
    for (rank in RankConfig.select(availableRanks(), state.site, state.gender)) {
        items += (rank.rankLabel() + if (rank.key == state.rankKey) " · 当前" else "") to {
            if (rank.key != state.rankKey) showDiscover(rank.key)
        }
    }
    Widgets.longMenuDialog(this, "选择榜单", items).show()
}

private fun MainActivity.chooseRankFilter(state: Screen.Discover, rank: RankConfig.Rank) {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    for (category in RankConfig.qidianCategories(rank)) {
        items += (category.name + if (category.id == state.filter.categoryId) " · 当前" else "") to {
            val next = state.filter.copy(categoryId = category.id)
            if (next != state.filter) requestRank(state.copy(filter = next, scrollY = 0))
        }
    }
    for (period in RankConfig.qidianPeriods(rank)) {
        items += (period.label + if (period == state.filter.period) " · 当前" else "") to {
            val next = state.filter.copy(period = period)
            if (next != state.filter) requestRank(state.copy(filter = next, scrollY = 0))
        }
    }
    Widgets.longMenuDialog(this, "榜单筛选", items).show()
}

private fun MainActivity.requestRank(state: Screen.Discover) {
    val rank = availableRanks().firstOrNull { it.key == state.rankKey }
    if (rank == null) {
        showDiscoverSnapshot(state.copy(loaded = true, error = "这个榜已不在榜单清单里"))
        return
    }
    val requestSerial = ++RankUiSession.requestSerial
    val loading = state.copy(hits = emptyList(), error = "", loaded = false, canLoadMore = false)
    showDiscoverSnapshot(loading)
    bg(loading, {
        val decoder = runCatching { CharsetFontDecoder.fromFile(java.io.File(filesDir, "charset.json").absolutePath) }
            .getOrNull()?.takeIf { it.isReady }
        RankSource.fetch(rank, if (rank.gender == RankConfig.Gender.FEMALE) "female" else "male",
            1, decoder = decoder, filter = state.filter)
    }) { result ->
        if (requestSerial != RankUiSession.requestSerial) return@bg
        val current = (curScreen as? Screen.Discover) ?: return@bg
        val ready = result.fold(
            onSuccess = { current.copy(hits = it, loaded = true, error = "", canLoadMore = false) },
            onFailure = { current.copy(loaded = true, error =
                if (it is RankSource.RankAccessException) it.message ?: "起点访问受限"
                else "榜单暂时无法获取：${it.message ?: "网络不可用"}", canLoadMore = false) })
        RankUiSession.snapshots.remove(screenId(ready))
        RankUiSession.snapshots[screenId(ready)] = ready
        while (RankUiSession.snapshots.size > 24) RankUiSession.snapshots.remove(RankUiSession.snapshots.keys.first())
        showDiscoverSnapshot(ready)
    }
}

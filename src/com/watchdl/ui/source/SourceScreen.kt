package com.watchdl.ui.source

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.attachTabs
import com.watchdl.ui.search.askText
import com.watchdl.ui.search.showHits
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File
import kotlin.concurrent.thread

/**
 * 发现榜单、书源管理、书源市场三屏（从 MainActivity 原样搬出，行为未改）。
 *
 * 与 ui/shelf 同样写成 MainActivity 的扩展函数：这些屏要用 Activity 的
 * root / setScreen / crownStep / toast 等状态。
 * 榜单缓存三个字段（rankCache/rankRefreshed/rankRefreshing）留在 Activity，
 * 因为扩展属性不能有初始化器。
 */

/**
 * 书源管理屏的界面状态。
 *
 * 放文件级 object 而不是往 MainActivity 加字段：筛选、排序、搜索词、多选只属于这一屏。
 * 其中「启用 / 移除 / 手动顺序」是要落盘的偏好（见 core/SourcePrefsStore），
 * 另几项是纯屏内状态，离开就重来。
 */
internal object SourceUi {

    /** 上次读到的源快照。非空就不必再读盘 —— 开关一次源不该重新解析 175 条规则。 */
    var data: SourceData? = null

    /** 读盘失败过。为 true 时直接给错误态，否则「读不到 → 重画 → 再起线程」会死循环。 */
    var loadFailed = false

    /** 落盘状态：禁用 / 移除 / 手动顺序（名字集合，不含文件信息）。 */
    val disabled = linkedSetOf<String>()
    val removed = linkedSetOf<String>()
    val order = mutableListOf<String>()
    private var loaded = false

    /** 屏内状态。筛选与排序留着 —— 用户下次进来还想看同一批。 */
    var filter = SourceFilter.ALL
    var sort = SourceSort.Order
    var query = ""
    var diagOpen = false
    var selecting = false
    val selected = linkedSetOf<String>()

    fun prefs(): SourcePrefs =
        SourcePrefs(LinkedHashSet(disabled), LinkedHashSet(removed), order.toList())

    fun load(dir: File) {
        if (loaded) return
        val p = SourcePrefsStore.load(dir)
        disabled.clear(); disabled += p.disabled
        removed.clear(); removed += p.removed
        order.clear(); order += p.order
        loaded = true
    }

    fun save(dir: File) = SourcePrefsStore.save(dir, prefs())

    /** 长按进多选，并把长按的那条先选上（legado 同样如此）。 */
    fun enter(name: String) {
        selecting = true
        selected.clear()
        if (name.isNotEmpty()) selected.add(name)
    }

    fun exit() {
        selecting = false
        selected.clear()
    }
}

/** 屏幕上的滚动位置。切选择模式会重画整屏，要把位置带回去（与 ui/shelf 同一写法）。 */
private fun MainActivity.sourceBodyScroll(): Int {
    for (i in 0 until root.childCount) {
        val c = root.getChildAt(i)
        if (c is ScrollView) return c.scrollY
    }
    return 0
}

/**
 * ⑤ 书源管理。
 *
 * 参考 legado-novel `ui/book/source/manage/BookSourceScreen.kt`（主体 155-740，
 * 行菜单 841-898，分组筛选 sheet 913-1000）与 `BookSourceViewModel.kt`。
 *
 * 结构对照 legado：顶栏（返回 / 标题 / 副标题 / 搜索 / ⋮）→ 列表（每条带启用位与 ⋮）
 * → 多选态换标题与动作。差异集中在两处，注释里逐条写明：
 * 一是 189×248 dp 的物理约束（Switch 换成勾、图标槽压窄、诊断收进列表尾部），
 * 二是本应用没有登录页 / 调试页 / 发现源 / 分组字段。
 */
internal fun MainActivity.showSources(keepScroll: Boolean = false) {
    setScreen(Screen.Sources)
    SourceUi.load(sourcesDir())

    val snap = SourceUi.data
    if (snap == null) {
        root.removeAllViews()
        if (SourceUi.loadFailed) {
            root.addView(Widgets.topBar(this, "书源", onBack = { goBackToPrevious() }))
            root.addView(Widgets.emptyState(this, "warning", "读取本地书源失败\n请重装或重新导入"),
                Md3.lpWeight(1f))
            return
        }
        // 首屏骨架：顶栏先出来，列表位给一句「正在读取」。读完重建整屏。
        // 读盘要解析上百条规则（几十毫秒到一秒），压在主线程会让点「书源管理」像卡死。
        root.addView(Widgets.topBar(this, "书源", onBack = { goBackToPrevious() },
            subtitle = "正在读取本地书源…"))
        root.addView(Widgets.emptyState(this, "refresh", "正在读取本地书源…"), Md3.lpWeight(1f))
        val owner: Screen = Screen.Sources
        bg(owner, { loadSourceSnapshot() }) { res ->
            val got = res.getOrNull()
            if (got == null) SourceUi.loadFailed = true else SourceUi.data = got
            showSources()
        }
        return
    }
    renderSources(snap, keepScroll)
}

/**
 * 读盘一次：源行 + 诊断（正则自检 / 导入抛点）一起备好。
 *
 * 正则自检要编译几百条规则，原来每进一次屏就跑一遍；现在只在首次读盘跑，
 * 结果挂在快照上，之后开关源、切筛选都不再付这笔钱。
 */
private fun MainActivity.loadSourceSnapshot(): SourceData? = runCatching {
    extractAssets()
    val data = buildLocalSourceRows()
    val badRe = SafeRegex.probe(RegexProbe.ALL) + SafeRegex.probe(RegexProbe.FROM_SOURCES)
    val lenientBad = SafeRegex.probeLenient(
        badRe.map { it.substringBefore(" | ") to it.substringAfter("正则=") })
        .filter { it.contains("仍失败") }
    val trace = ImportTrace.last
    DevLog.log("WatchDL",
        "正则自检：字面 ${RegexProbe.ALL.size} / 书源 ${RegexProbe.FROM_SOURCES.size} / " +
        "裸编译失败 ${badRe.size}（走后兜底仍失败 ${lenientBad.size}）/ 导入抛点 ${trace ?: "无"}")
    badRe.take(6).forEach { DevLog.log("WatchDL", "裸编译失败 $it") }
    lenientBad.take(6).forEach { DevLog.log("WatchDL", "兜底仍失败 $it") }
    data.copy(badRegex = badRe.size, patched = lenientBad.size, importTrace = trace)
}.getOrElse {
    DevLog.log("WatchDL", "读取本地书源失败：${it.message}")
    null
}

/**
 * 整屏重建。所有交互（开关、筛选、排序、置顶置底、多选）都走这里，不各自改局部视图 ——
 * 局部改看着更快，但「已选 N」「共 N · 启用 M」「分组标题里的条数」三处要同步，
 * 漏一处就是假数据。
 */
private fun MainActivity.renderSources(d: SourceData, keepScroll: Boolean) {
    val scrollY = if (keepScroll) sourceBodyScroll() else 0
    root.removeAllViews()

    // 移除的源不再进列表；启用计数只算留下的那些。
    val all = d.rows.filter { it.name !in SourceUi.removed }
    val enabled = all.count { sourceEnabled(SourceUi.disabled, it.name) }

    // 选中的源可能被筛掉或被删掉：留着会停在「已选 0」的死界面。
    if (SourceUi.selecting && !sourcePruneSelection(SourceUi.selected, all.map { it.name })) {
        SourceUi.exit()
    }

    val q = SourceUi.query.trim()
    val usableNames = all.filter { it.mark == "✓" }.map { it.name }.toSet()
    val hits = all.filter {
        sourceMatchFilter(sourceEnabled(SourceUi.disabled, it.name), SourceUi.filter) &&
            (q.isEmpty() || it.name.contains(q, ignoreCase = true))
    }
    // 过滤与排序都在名字上做（core 的纯函数），再回填成行：
    // 行里有 rule，排序键却只有名字，两者分开最容易断言。
    val byName = hits.associateBy { it.name }
    val shown = sourceSortNames(hits.map { it.name }, SourceUi.sort, SourceUi.order, usableNames)
        .mapNotNull { byName[it] }

    if (SourceUi.selecting) {
        root.addView(sourceSelectBar())
    } else {
        // 顶栏副标题放统计。legado 那里放的是分组名（`groupFilterName ?: 全部`），
        // 我们的源没有分组字段，把「共 N · 启用 M」放这里（原统计卡的位置），
        // 列表就能第一眼看见 —— 248 dp 高的屏上，一张统计卡 + 一张诊断卡
        // 会把首屏的列表挤到只剩两行。
        root.addView(Widgets.topBar(this, "书源", onBack = { goBackToPrevious() },
            subtitle = sourceSubtitle(all.size, enabled),
            trailing = listOf(
                "search" to { askSourceQuery() },
                "more" to { showSourceMenu() },
            )))
    }

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    col.addView(Widgets.sectionLabel(this, sourceListLabel(SourceUi.filter, q, shown.size)))

    if (shown.isEmpty()) {
        col.addView(Widgets.emptyState(this, "search", when {
            q.isNotEmpty() -> "没有名字含「$q」的书源"
            SourceUi.filter == SourceFilter.ENABLED -> "没有已启用的书源"
            SourceUi.filter == SourceFilter.DISABLED -> "没有已禁用的书源"
            else -> "本地书源目录是空的\n在 ⋮ 里更新书源库"
        }))
    } else {
        // 400 条上限照旧：真实数据几十到一百多条够用，真到几千条时先保证不卡死。
        for (r in shown.take(400)) col.addView(sourceRow(r))
        if (shown.size > 400) {
            col.addView(Md3.text(this, "（还有 ${shown.size - 400} 条未显示）",
                Md3.FS_SM, Md3.onSurfaceVariant))
        }
    }
    // 诊断收在列表尾部（原来在首屏顶部）：排障时才看，全好时它只是噪声。
    col.addView(sourceDiagCard(d, all))

    root.addView(body)
    // 选择态的批量动作放屏底固定一行 —— legado 的选择动作本来就在
    // SelectionBottomBar（底部浮动工具条），不在顶栏。
    if (SourceUi.selecting) root.addView(sourceBatchBar())
    if (scrollY > 0) body.post { body.scrollTo(0, scrollY) }
}

/** 标记 → 颜色。✓ 可用 / ⚠ 需修补 / ✗ 不可用。 */
private fun sourceMarkColor(mark: String): Int = when (mark) {
    "✓" -> Md3.ok
    "⚠" -> Md3.primary
    else -> Md3.error
}

/** 标记 → 字形。 */
private fun sourceMarkGlyph(mark: String): String = when (mark) {
    "✓" -> "check"
    "⚠" -> "warning"
    else -> "close"
}

/**
 * 一条书源。
 *
 * legado 的行是 `ReorderableSelectionItem`：标题 + 副标题（分组 / 校验消息 / 发现位）、
 * 右侧 M3 Switch、再加一枚「编辑」与一枚 ⋮。
 *
 * 189 dp 装不下那四件：M3 Switch 本身约 52 dp，Switch + 编辑 + ⋮ 就要 148 dp，
 * 书名只剩二十来 dp。所以这里压成：
 * - 启用位用一枚 20 dp 的勾（`Md3.iconButton`），启用=主色实心、禁用=压到 0.3 透明；
 *   这不是 legado 的 Switch 形态，是它在表盘上的等价物；
 * - 勾 30 dp + ⋮ 28 dp（legado 各 48 dp 的触控槽），书名拿到约 88 dp；
 * - 没做「编辑」：本应用没有书源编辑页，点整行就落到行菜单（legado 点行是进编辑）。
 * 选择态下收起勾与 ⋮，整行点击=选中 —— 与 legado `inSelectionMode` 一致。
 */
private fun MainActivity.sourceRow(r: SourceRow): LinearLayout {
    val enabled = sourceEnabled(SourceUi.disabled, r.name)
    // M7：声明明文、且实测 https 也升级不上去的源才标「明文」。
    val plain = r.declaredHttp && r.plainConfirmed
    val sub = listOfNotNull(
        r.note?.let { shortReason(it) },
        if (plain) "明文传输" else null,
    ).joinToString(" · ").ifEmpty { null }
    val picked = r.name in SourceUi.selected

    val row = Md3.row(this)
    row.minimumHeight = Md3.dp(this, if (sub != null) Md3.ITEM_TALL else Md3.ITEM)
    // 左右边距比 listItem 各收 4 dp：给两枚动作槽腾位置，文字仍与分组标题对齐。
    row.setPadding(Md3.dp(this, Md3.PAD * 3 / 4), 0, Md3.dp(this, Md3.PAD / 2), 0)
    row.background = Md3.ripple(this,
        if (picked) Md3.surface3 else Color.TRANSPARENT, Md3.R_ITEM.toFloat())

    row.addView(Icons.view(this, sourceMarkGlyph(r.mark), sourceMarkColor(r.mark), 16),
        LinearLayout.LayoutParams(Md3.dp(this, 18), Md3.dp(this, 18)).apply {
            rightMargin = Md3.dp(this@sourceRow, 7)
        })

    val text = Md3.column(this)
    // 禁用的源压暗而不是加斜体或删除线：legado 也靠透明区分（`isEnabled` 传进列表项，
    // 标题与正文一起降透明度）。
    text.addView(Md3.ellipsize(Md3.text(this, r.name, Md3.FS_MD, Md3.onSurface))
        .apply { alpha = if (enabled) 1f else 0.45f })
    if (sub != null) {
        text.addView(Md3.ellipsize(Md3.text(this, sub, Md3.FS_SM, Md3.onSurfaceVariant))
            .apply { alpha = if (enabled) 0.7f else 0.3f })
    }
    row.addView(text, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    if (!SourceUi.selecting) {
        val toggle = Md3.iconButton(this, "check", { toggleSource(r.name) },
            desc = if (enabled) "已启用，点按禁用" else "已禁用，点按启用")
        // iconButton 的最小热区是 48×64 dp（M3 顶栏规格），行里放不下两个，这里放开。
        toggle.minimumWidth = 0
        toggle.minimumHeight = 0
        toggle.setPadding(Md3.dp(this, 2), 0, Md3.dp(this, 2), 0)
        toggle.alpha = if (enabled) 1f else 0.3f
        row.addView(toggle, LinearLayout.LayoutParams(
            Md3.dp(this, 30), Md3.dp(this, Md3.ITEM)))

        val menu = Md3.iconButton(this, "more", { showSourceRowMenu(r) }, desc = "更多操作")
        menu.minimumWidth = 0
        menu.minimumHeight = 0
        menu.setPadding(Md3.dp(this, 1), 0, Md3.dp(this, 1), 0)
        row.addView(menu, LinearLayout.LayoutParams(
            Md3.dp(this, 28), Md3.dp(this, Md3.ITEM)))
    }

    row.isClickable = true
    row.setOnClickListener {
        if (SourceUi.selecting) toggleSourceSelection(r.name) else showSourceRowMenu(r)
    }
    row.setOnLongClickListener {
        if (!SourceUi.selecting) {
            SourceUi.enter(r.name)
            showSources(keepScroll = true)
        } else {
            toggleSourceSelection(r.name)
        }
        true
    }
    return row
}

/**
 * 选择态顶栏：✕ / 已选 N / 全选 / 删除。
 *
 * 写法照书架屏 `81edb94` 的 `shelfSelectBar`（34/36/36 dp 三槽 + FS_MD 标题），
 * 理由也一样：M3 一个图标槽 48 dp，三个就吃掉 144 dp，189 dp 的屏上标题会被压成 0。
 */
private fun MainActivity.sourceSelectBar(): LinearLayout {
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
        SourceUi.exit()
        showSources(keepScroll = true)
    })
    bar.addView(Md3.ellipsize(
        Md3.text(this, sourceSelectTitle(SourceUi.selected.size), Md3.FS_MD, Md3.onSurface,
            bold = true)).apply { gravity = Gravity.CENTER_VERTICAL },
        LinearLayout.LayoutParams(0, Md3.dp(this, Md3.TAB_H), 1f))
    bar.addView(slot("select_all", "全选", 36) {
        sourceSelectAll(SourceUi.selected, SourceUi.data?.rows
            ?.filter { it.name !in SourceUi.removed }?.map { it.name } ?: emptyList())
        showSources(keepScroll = true)
    })
    bar.addView(slot("delete", "删除选中", 36) {
        confirmRemoveSources(SourceUi.selected.toList())
    })
    return bar
}

/**
 * 选择态的批量动作条（屏底固定一行）：启用所选 / 禁用所选。
 *
 * legado 把这些放在 `SelectionBottomBar` 的次要动作里（⋮ 弹出）。我们顶栏三个槽已经
 * 占满（✕ / 全选 / 删除），189 dp 没有第四槽的位置，所以照 legado 的**位置**
 * （底部工具条）把两枚并排摊开 —— 一屏内看得见、点得着，不用再进一层菜单。
 */
private fun MainActivity.sourceBatchBar(): LinearLayout {
    val bar = Md3.row(this)
    bar.setPadding(Md3.dp(this, Md3.PAD / 2), 0, Md3.dp(this, Md3.PAD / 2),
        Md3.dp(this, Md3.GAP))
    val names = SourceUi.selected.toList()
    for ((i, it) in listOf("启用所选" to true, "禁用所选" to false).withIndex()) {
        val b = Widgets.button(this, it.first, "tonal") {
            if (names.isEmpty()) toast("先选书源") else setSourceEnabled(names, it.second)
        }
        b.contentDescription = it.first
        bar.addView(b, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f).apply {
            if (i > 0) leftMargin = Md3.dp(this@sourceBatchBar, Md3.GAP / 2)
        })
    }
    return bar
}

/**
 * 诊断卡：折叠一行，点开才是明细。
 *
 * 原来这块（正则自检 / 失败原因归类 / 源文件数）在列表上方，首屏一进去先看到两张卡。
 * legado 的首屏只有列表，所以这里整块下移到列表尾部并且默认折叠。
 */
private fun MainActivity.sourceDiagCard(d: SourceData, all: List<SourceRow>): LinearLayout {
    val card = Widgets.card(this, outlined = true)
    val broken = all.count { it.mark == "✗" }
    card.isClickable = true
    card.setOnClickListener {
        SourceUi.diagOpen = !SourceUi.diagOpen
        showSources(keepScroll = true)
    }
    card.addView(Md3.ellipsize(Md3.text(this,
        if (SourceUi.diagOpen) "诊断信息"
        else if (broken > 0) "诊断 · $broken 条不可用 · 点开看原因"
        else "诊断 · 全部可导入 · 点开看详情",
        Md3.FS_MD, Md3.onSurface)))
    if (!SourceUi.diagOpen) return card

    card.addView(Md3.text(this, "可用 ${d.usable} / 共 ${d.total} 条",
        Md3.FS_SM, Md3.onSurfaceVariant))
    if (d.patched > 0) {
        card.addView(Md3.text(this, "${d.patched} 条书源已自动修正",
            Md3.FS_SM, Md3.onSurfaceVariant))
    }
    if (d.importTrace != null) {
        card.addView(Md3.text(this, "有源在导入时出错，详情见日志",
            Md3.FS_SM, Md3.onSurfaceVariant))
    }
    // 按原因归类，比逐条列一百多行有用得多。
    val byReason = all.filter { it.mark == "✗" }
        .groupBy { shortReason(it.note) }
        .entries.sortedByDescending { it.value.size }
    if (byReason.isNotEmpty()) {
        card.addView(Md3.text(this, "$broken 条暂不可用",
            Md3.FS_SM, Md3.onSurfaceVariant))
        byReason.take(3).forEach { (reason, list) ->
            card.addView(Md3.ellipsize(
                Md3.text(this, "· ${list.size} 条：$reason", Md3.FS_SM, Md3.onSurfaceVariant)))
        }
        if (byReason.size > 3) {
            card.addView(Md3.text(this, "· 另有 ${byReason.size - 3} 类原因",
                Md3.FS_SM, Md3.onSurfaceVariant))
        }
    }
    // 路径是内部信息（P1-4），只在 debug 包显示。
    card.addView(Md3.text(this,
        "源文件 ${d.files.size} 个" + (if (isDebugBuild()) " · $srcDirPath" else ""),
        Md3.FS_SM, Md3.onSurfaceVariant))
    card.addView(Md3.text(this, "收起", Md3.FS_SM, Md3.primary))
    return card
}

/** 搜索书源名。legado 是顶栏内联 SearchBar；手表上沿用本应用统一的输入对话框。 */
private fun MainActivity.askSourceQuery() {
    askText("搜索书源", "书源名关键词（留空清除）", null) { q ->
        SourceUi.query = q.trim()
        SourceUi.filter = SourceFilter.ALL
        showSources()
    }
}

/**
 * 顶栏 ⋮。条目照 legado `dropDownMenuContent`：分组筛选、排序，之后是导入/导出/校验
 * 一类的批量动作。
 *
 * 本应用没有导入/导出/校验（源库靠云端整包与书源市场同步），这一段的三个入口
 * 是本项目独有的（书源市场 / 更新书源库 / 下载线程），功能不变，只是从列表上方
 * 挪到这里，把首屏让给书源列表。
 */
private fun MainActivity.showSourceMenu() {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += "分组：${sourceFilterLabel(SourceUi.filter)}" to { showSourceFilterMenu() }
    items += "排序：${sourceSortLabel(SourceUi.sort)}" to { showSourceSortMenu() }
    if (SourceUi.query.isNotEmpty()) {
        items += "清除搜索「${SourceUi.query}」" to {
            SourceUi.query = ""
            showSources()
        }
    }
    if (SourceUi.removed.isNotEmpty()) {
        items += "恢复已移除的 ${SourceUi.removed.size} 条" to {
            SourceUi.removed.clear()
            SourceUi.save(sourcesDir())
            toast("已恢复")
            showSources()
        }
    }
    items += "书源市场" to { showMarket(1, emptyList()) }
    items += "更新书源库" to { updateFromCloud() }
    // 并发线程数：通用书源默认串行太慢（实测 0.17 章/秒），交给用户自己定。
    items += "下载线程（${lanes()}）" to { pickLanes() }
    Widgets.longMenuDialog(this, "书源", items).show()
}

/**
 * 分组筛选。照 legado `BookSourceGroupFilterSheet`：默认组（全部 / 启用 / 禁用 /
 * 需登录 / 无分组 / 启用发现 / 禁用发现）+ 用户自建分组。
 *
 * 我们只有前三项：需登录要有 `loginUrl`、发现要有 `exploreUrl`、自建分组要有
 * `bookSourceGroup` —— 这三个字段我们的导入器都不读，源库里也没有。
 */
private fun MainActivity.showSourceFilterMenu() {
    val items: List<Pair<CharSequence, () -> Unit>> =
        listOf(SourceFilter.ALL, SourceFilter.ENABLED, SourceFilter.DISABLED).map { f ->
            val mark = if (SourceUi.filter == f) " ✓" else ""
            (sourceFilterLabel(f) + mark) to {
                SourceUi.filter = f
                showSources()
            }
        }
    Widgets.longMenuDialog(this, "分组", items).show()
}

/** 排序。legado `BookSourceSort` 七档，我们三档；选中项后面加「✓」。 */
private fun MainActivity.showSourceSortMenu() {
    val items: List<Pair<CharSequence, () -> Unit>> = SourceSort.values().map { s ->
        val mark = if (SourceUi.sort == s) " ✓" else ""
        (sourceSortLabel(s) + mark) to {
            SourceUi.sort = s
            showSources()
        }
    }
    // legado 还有「反序」（sort_desc）。可用性排序已经把可用的排前，反序意义不大；
    // 手动顺序靠置顶/置底调，名称排序反不反序都是同一批，所以不造这一项。
    Widgets.longMenuDialog(this, "排序", items).show()
}

/**
 * 行菜单。照 legado `BookSourceItemMenu`：置顶 / 置底 / 登录 / 搜索 / 调试 / 删除 /
 * 启用·禁用发现。
 *
 * 只做本应用真有的五项：
 * - 置顶 / 置底：legado 只在手动排序下给这两项（`canMoveToEdge`）。我们保留常显，
 *   点下去自动切回手动排序 —— 手表上「菜单项忽隐忽现」比「操作后自动换档」更难懂。
 * - 搜索：用这一条源搜一本书（legado `onSearchSource`，进搜索屏且只查这一源）。
 * - 启用 / 禁用：legado 用行内 Switch 做，我们两种入口都给。
 * - 删除：二次确认（legado 也有确认对话框 `sure_del`）。
 * 没做的三项与原因：
 * - **登录**：源没有 `loginUrl`，本应用也没有登录页。
 * - **调试**：没有书源调试屏（legado 的 `onDebugSource` 进调试日志界面）。
 * - **启用发现 / 禁用发现**：源没有 `exploreUrl`，本应用没有发现源概念。
 */
private fun MainActivity.showSourceRowMenu(r: SourceRow) {
    val enabled = sourceEnabled(SourceUi.disabled, r.name)
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += "置顶" to { moveSourceToEdge(r.name, true) }
    items += "置底" to { moveSourceToEdge(r.name, false) }
    if (r.rule != null) {
        items += "搜索" to { askSourceKeyword(r) }
    }
    items += (if (enabled) "禁用" else "启用") to { setSourceEnabled(listOf(r.name), !enabled) }
    items += "删除" to { confirmRemoveSources(listOf(r.name)) }
    Widgets.longMenuDialog(this, r.name, items).show()
}

/** 用这一条源搜一本书。搜不到/取不到都只 toast，不进空结果屏。 */
private fun MainActivity.askSourceKeyword(r: SourceRow) {
    val rule = r.rule ?: return toast("这条源不可用，没法搜索")
    askText("用「${r.name}」搜索", "书名或作者", "请先输入书名") { kw ->
        toast("正在用「${r.name}」搜索…")
        val owner: Screen = Screen.Sources
        bg(owner, { LegadoSource.of(rule).search(kw, 1) }) { res ->
            res.onSuccess { hits ->
                // 上一轮的「已见过」会把这批同名书全过滤掉，先清。
                seenHits.clear()
                showHits(kw, hits, MultiSource.Result(hits, 1,
                    if (hits.isEmpty()) 0 else 1,
                    if (hits.isEmpty()) listOf(r.name) else emptyList()))
            }.onFailure { toast("搜索失败：${it.message?.take(40)}") }
        }
    }
}

/** 点行上的勾：切这一条的启用位，落盘并让多源搜索立刻跟着变。 */
private fun MainActivity.toggleSource(name: String) {
    val on = sourceToggle(SourceUi.disabled, name)
    SourceUi.save(sourcesDir())
    rebuildSourcesInBackground()
    showSources(keepScroll = true)
    if (!on) toast("已禁用「${name.take(8)}」")
}

/** 批量启用 / 禁用（legado `SetEnabledForSelection`）。 */
private fun MainActivity.setSourceEnabled(names: Collection<String>, enabled: Boolean) {
    if (names.isEmpty()) return
    sourceSetEnabled(SourceUi.disabled, names, enabled)
    SourceUi.save(sourcesDir())
    rebuildSourcesInBackground()
    toast(if (enabled) "已启用 ${names.size} 条" else "已禁用 ${names.size} 条")
    showSources(keepScroll = true)
}

/** 置顶 / 置底（legado `MoveToEdge`）。落的是名字顺序，源 JSON 不动。 */
private fun MainActivity.moveSourceToEdge(name: String, toTop: Boolean) {
    val all = SourceUi.data?.rows?.filter { it.name !in SourceUi.removed }?.map { it.name }
        ?: return
    val next = sourceMoveToEdge(all, SourceUi.order, listOf(name), toTop)
    SourceUi.order.clear()
    SourceUi.order += next
    // 置顶/置底只在手动排序下有视觉结果，顺手把档位切过去（legado 直接隐藏这两项）。
    SourceUi.sort = SourceSort.Order
    SourceUi.save(sourcesDir())
    showSources()
}

/**
 * 删除 = 从列表移除（记墓碑）。
 *
 * legado 删的是数据库里的条目，源文件由它自己管。我们的源是整包 JSON
 * （`legado.json` 里就有一百多条），改文件的代价是把整包写坏，用户也明确要求
 * 别动 JSON 本体，所以只记名字：从界面与多源搜索里拿掉，源库更新后也不会「复活」。
 */
private fun MainActivity.confirmRemoveSources(names: List<String>) {
    if (names.isEmpty()) return
    Widgets.md3Dialog(this, "删除", sourceDeleteMessage(names),
        confirm = "删除", dismiss = "取消",
        onConfirm = {
            SourceUi.removed += names
            SourceUi.selected -= names.toSet()
            if (SourceUi.selected.isEmpty()) SourceUi.selecting = false
            SourceUi.save(sourcesDir())
            rebuildSourcesInBackground()
            toast("已移除 ${names.size} 条")
            showSources(keepScroll = true)
        }).show()
}

/** 多选：点一条切选中。 */
private fun MainActivity.toggleSourceSelection(name: String) {
    sourceToggleSelect(SourceUi.selected, name)
    showSources(keepScroll = true)
}

/**
 * 启用/移除落盘后让 `multi` 立刻生效。
 *
 * `buildMulti()` 会重读全部源文件（约百毫秒），所以丢后台；重画由调用方负责。
 * 不做这一步的话，刚禁用的源还留在内存里的搜索池中 —— 开关看着生效、搜索照旧用它。
 */
private fun MainActivity.rebuildSourcesInBackground() {
    thread { rebuildSources() }
}

/**
 * 书源市场。列表只拿元数据（快），点某一条才拉完整规则（约 1 秒），
 * 装上后落盘缓存，下次不再拉。
 *
 * 全站 31606 条 / 2.5 GB，全量拉要 9 小时，所以不做整库导入。
 */
/**
 * 一个榜的内容页。
 *
 * 顶端是榜单名（带返回箭头回目录）与刷新；正文是该榜的书目。
 * 番茄分类榜一屏 10 本、不可翻页；起点每页 20 本、可翻到第 10 页。
 * 点书名走既有的多源搜索：起点没有对应 [BookSource]，番茄榜单的书也未必能直取目录。
 */
private fun MainActivity.showRankContent(rankKey: String) {
    setScreen(Screen.Discover(rankKey))
    root.removeAllViews()

    // 榜单条目自己带着站点与频道，按 key 查一次就够。
    val rank = discoverRanks().firstOrNull { it.key == rankKey }
    // 查不到（云端配置改过名、缓存被清）也得给一条退路，不能卡在这屏。
    root.addView(Widgets.topBar(this, rank?.name ?: "榜单不存在",
        onBack = { showDiscover() },
        trailing = if (rank == null) emptyList()
        else listOf(
            // legado 内容页顶栏有「分类选择」：在榜里就能换到另一个榜，不用退回目录。
            "more" to { showRankSwitchMenu(rankKey) },
            "refresh" to { showDiscover(rankKey) },
        )))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    root.addView(body)

    if (rank == null) {
        col.addView(Widgets.emptyState(this, "warning", "这个榜已不在榜单清单里"))
        attachTabs(1)
        return
    }

    val holder = Md3.column(this)
    col.addView(holder)
    holder.addView(Md3.text(this, "正在加载…", Md3.FS_SM, Md3.onSurfaceVariant))

    // 云端配置照旧在后台刷：拉到了且清单变了就重画一次，没变就不动。
    refreshRanks(Screen.Discover(rankKey)) { showDiscover(rankKey) }

    bg(Screen.Discover(rankKey), { loadOneRank(rank) }) { res ->
        holder.removeAllViews()
        res.onSuccess { hits ->
            if (hits.isEmpty()) {
                holder.addView(Widgets.emptyState(this, "explore", "这个榜暂时没有内容"))
            } else {
                for (h in hits) holder.addView(discoverItem(col, h))
                // legado 的 ExploreShow 在列表末尾挂 LoadMoreFooter，到底写「没有下一页」。
                // 我们一次把该榜的页数全拉完，没有「加载更多」可点，只留这句说明到底了。
                holder.addView(endOfListNote())
            }
        }.onFailure { e ->
            holder.addView(Widgets.emptyState(this, "warning",
                "加载失败：${e.message?.take(60) ?: "网络不可用"}"))
            holder.addView(Widgets.button(this, "重试", "tonal") {
                showDiscover(rankKey)
            })
        }
    }
    attachTabs(1)
}

/**
 * 发现页：榜单目录。
 *
 * **以前**首屏是「番茄／起点／男频／女频」四颗等分按钮选组合，两栏里再选分类。
 * 问题是起点 5 个榜原本没建女频条目 —— 选「起点 + 女频」直接空屏；
 * 就算补上，四颗按钮也白占掉一行 34dp，而手表屏高只有 248dp。
 *
 * **现在**直接列榜：站点与频道合成分组标题，一组一张卡，点一行进那个榜。
 * 没有能落到空屏的组合，首屏也不用先做两次选择。
 */
internal fun MainActivity.showDiscover(rankKey: String = "") {
    setScreen(Screen.Discover(rankKey))
    root.removeAllViews()
    // 指定了榜就是内容页；目录页在下面搭。
    if (rankKey.isNotBlank()) {
        showRankContent(rankKey)
        return
    }

    // 根屏（底栏三栏之一），不挂返回箭头，动作图标钉在标题行右端。
    root.addView(Widgets.headerRow(this, "发现",
        trailing = listOf(
            "more" to { showDiscoverMenu() },
            "refresh" to { showDiscover() },
        )))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    root.addView(body)

    val all = discoverRanks()
    // 分组顺序固定：番茄在前（榜多有分类），起点在后（各 5 个总榜）。
    // 选了某一组就只画那一组，与 legado 顶栏的「分组」下拉同一个意思。
    val groups = DISCOVER_GROUPS.filter { DiscoverUi.group.isEmpty() || it.key == DiscoverUi.group }
    for (g in groups) {
        val group = all.filter { it.site == g.site && it.gender == g.gender }
        if (group.isEmpty()) continue
        // 分组标题降成小字说明，不再画卡片：卡片会再吃掉一圈内外边距。
        col.addView(Md3.text(this, g.title, Md3.FS_SM, Md3.onSurfaceVariant).apply {
            setPadding(Md3.dp(this@showDiscover, 4), Md3.dp(this@showDiscover, 6),
                0, Md3.dp(this@showDiscover, 2))
        })
        // 两列。一列时 20 个榜排下来是 20 × 48 dp = 960 dp，四屏才能到底；
        // 两列 × 32 dp 压到 341 dp，一屏半就完。
        group.chunked(2).forEach { pair ->
            val row = Md3.row(this)
            pair.forEachIndexed { i, r ->
                row.addView(rankChip(r), LinearLayout.LayoutParams(
                    0, Md3.dp(this, 32), 1f).apply {
                    if (i > 0) leftMargin = Md3.dp(this@showDiscover, 4)
                    topMargin = Md3.dp(this@showDiscover, 4)
                })
            }
            // 落单的最后一格补一个空位，撑住半列宽度，不让它变成整行宽。
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(
                0, Md3.dp(this, 1), 1f).apply { leftMargin = Md3.dp(this@showDiscover, 4) })
            col.addView(row)
        }
    }
    if (col.childCount == 0) {
        // legado `explore_empty`：「当前没有发现源！」。我们没有发现源，只有榜。
        col.addView(Widgets.emptyState(this, "explore", "当前没有发现榜单！"))
    }

    // 云端配置照旧在后台刷：拉到了且清单变了就重画一次，没变就不动。
    refreshRanks(Screen.Discover("")) { showDiscover() }
    attachTabs(1)
}

/**
 * 发现页的四个分组。
 *
 * [key] 用站点与频道的枚举名拼，不用列表下标 —— 将来插一组，下标全串味。
 */
private data class DiscoverGroup(
    val site: RankConfig.Site,
    val gender: RankConfig.Gender,
    val title: String,
) {
    val key: String get() = "${site.name}|${gender.name}"
}

private val DISCOVER_GROUPS = listOf(
    DiscoverGroup(RankConfig.Site.FANQIE, RankConfig.Gender.MALE, "番茄 · 男频"),
    DiscoverGroup(RankConfig.Site.FANQIE, RankConfig.Gender.FEMALE, "番茄 · 女频"),
    DiscoverGroup(RankConfig.Site.QIDIAN, RankConfig.Gender.MALE, "起点 · 男频"),
    DiscoverGroup(RankConfig.Site.QIDIAN, RankConfig.Gender.FEMALE, "起点 · 女频"),
)

/**
 * 发现页的分组筛选。空串表示全部。
 *
 * 放文件级 object 而不是 Activity 字段：它只属于这一屏，离开发现页就重来，不落盘。
 */
internal object DiscoverUi {
    var group: String = ""
}

/**
 * 发现页顶栏 ⋮。
 *
 * legado 的发现页顶栏有「分组」下拉加刷新，这里同构：先选组，再单独给一条
 * 「重新拉取榜单配置」—— 云端榜单改过之后，用户不必杀进程等它自己拉。
 */
private fun MainActivity.showDiscoverMenu() {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += ("全部" + if (DiscoverUi.group.isEmpty()) " ✓" else "") to {
        DiscoverUi.group = ""
        showDiscover()
    }
    for (g in DISCOVER_GROUPS) {
        items += (g.title + if (g.key == DiscoverUi.group) " ✓" else "") to {
            DiscoverUi.group = g.key
            showDiscover()
        }
    }
    items += "重新拉取榜单配置" to {
        // rankRefreshed 是一次性开关，手动刷新要显式复位，否则 refreshRanks 直接返回。
        rankRefreshed = false
        refreshRanks(Screen.Discover("")) { showDiscover() }
        toast("正在刷新榜单配置")
    }
    Widgets.longMenuDialog(this, "发现", items).show()
}

/**
 * 内容页的换榜菜单。
 *
 * legado `ExploreShowScreen` 顶栏有「分类选择」sheet：在某个分类里就能换到另一个分类。
 * 189dp 屏放不下分类网格，退成长菜单 —— 一屏四行，可按组滚。
 */
private fun MainActivity.showRankSwitchMenu(currentKey: String) {
    val all = discoverRanks()
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    for (g in DISCOVER_GROUPS) {
        for (r in all.filter { it.site == g.site && it.gender == g.gender }) {
            val mark = if (r.key == currentKey) " ✓" else ""
            items += ("${g.title} · ${r.listLabel()}$mark") to { showDiscover(r.key) }
        }
    }
    Widgets.longMenuDialog(this, "换榜", items).show()
}

/** 列表末尾的「没有下一页」，照 legado `no_next_page`。 */
private fun MainActivity.endOfListNote(): TextView =
    Md3.text(this, "没有下一页", Md3.FS_SM, Md3.onSurfaceVariant).apply {
        gravity = Gravity.CENTER
        setPadding(0, Md3.dp(this@endOfListNote, Md3.GAP),
            0, Md3.dp(this@endOfListNote, Md3.GAP))
    }

/**
 * 榜在目录里的名字。
 *
 * 内置名带前缀：番茄是「西方奇幻 · 阅读」，起点是「起点女频月票榜」。
 * 分组标题已经写明站点与频道，行里只留分类名，189dp 的屏才放得下。
 */
/**
 * 发现页的一个榜：32 dp 高的两列按钮。
 *
 * 文字 13 sp 单行截断 —— 榜名最长的「西方奇幻」四个字，两列各 76 dp 放得下。
 */
private fun MainActivity.rankChip(r: RankConfig.Rank): View =
    Md3.text(this, r.listLabel(), 13f, Md3.onSurface).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        background = Md3.ripple(this@rankChip, Md3.surface2, 10f)
        isClickable = true
        contentDescription = r.name
        setOnClickListener { showDiscover(r.key) }
    }

private fun RankConfig.Rank.listLabel(): String =
    if (site == RankConfig.Site.QIDIAN) name.removePrefix("起点").removePrefix("女频")
    else name.substringBefore(" · ")

/**
 * 发现页的一行。
 *
 * 副标题优先用 [BookHit.extra]（排名 · 字数 · 最新章节），没有就退回作者。
 * 点进去走多源搜索。
 */
private fun MainActivity.discoverItem(col: LinearLayout, h: BookHit): LinearLayout {
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
private fun MainActivity.loadOneRank(r: RankConfig.Rank): List<BookHit> {
    // 字符表只读一次。读不到也不致命，书名只是带私用区字符。
    val decoder = runCatching {
        CharsetFontDecoder.fromFile(File(filesDir, "charset.json").absolutePath)
    }.getOrNull()?.takeIf { it.isReady }

    val gender = if (r.gender == RankConfig.Gender.FEMALE) "female" else "male"
    val deadline = System.currentTimeMillis() + RankSource.PAGE_BUDGET_MS * 2
    // S18：以前非起点榜一律 1 页，配置里写 pages:5 也无效。
    // 现在按配置走，0 表示不可翻页。
    val pages = r.pages.coerceIn(0, 10).coerceAtLeast(1)
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
 * 当前可用的榜单。**已缓存的纯内存读，首次也只读磁盘和内置表，都不联网。**
 *
 * 以前这里直接调 [RankConfig.load]，而 load 最坏串行问 4 个镜像、
 * 总预算 20 秒。它在主线程被调 —— 点「发现榜单」就冻住十几秒，看着就是卡死。
 * 现在联网的部分全交给 [refreshRanks] 在后台做。
 */
private fun MainActivity.discoverRanks(): List<RankConfig.Rank> {
    rankCache?.let { return it }
    val got = RankConfig.local(File(filesDir, "sources"))
    // 空表不缓存 —— 否则一次取数失败会让本次进程再也不重试。
    if (got.isNotEmpty()) rankCache = got
    return got
}

/**
 * 后台拉一次云端配置；拉到了就重新渲染当前发现页。
 *
 * 只在榜选择器画完之后调，所以即使慢或失败也不影响用户先看到本地榜单。
 * 重渲染前已经靠 [postAlive] 核过屏身份 —— 用户已经走了就不重画。
 */
private fun MainActivity.refreshRanks(owner: Screen, rerender: () -> Unit) {
    if (rankRefreshing || rankRefreshed) return
    rankRefreshing = true
    bg(owner, { RankConfig.load(File(filesDir, "sources")) }) { res ->
        rankRefreshing = false
        val got = res.getOrNull()?.first ?: return@bg
        if (got.isEmpty()) return@bg
        rankRefreshed = true
        val before = rankCache
        rankCache = got
        // 内容没变就不重画，避免回到发现页时闪一下。
        if (got != before) rerender()
    }
}

/** 市场一页多少条。取数与分段显示共用，改一处两边一起走。 */
private const val MARKET_PAGE_SIZE = 30

internal fun MainActivity.showMarket(page: Int, accumulated: List<YioveSource.Item>) {
    setScreen(Screen.Market(accumulated, 0, page))
    root.removeAllViews()
    // 刷新从头拉：legado 的列表刷新都是「回到第一页」，不是就地重拉当前页。
    root.addView(Widgets.topBar(this, "书源市场",
        onBack = { showSources() },
        trailing = listOf("refresh" to { showMarket(1, emptyList()) })))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    root.addView(body)

    if (accumulated.isEmpty()) {
        col.addView(Widgets.emptyState(this, "cloud", "正在加载第 $page 页…"))
    } else {
        renderMarket(col, accumulated, page)
    }

    val owner: Screen = Screen.Market(accumulated, 0, page)
    bg(owner, { YioveSource.list(page = page, pageSize = MARKET_PAGE_SIZE) }) { res ->
        res.onSuccess { p ->
            // 导入一条后会带着旧清单再跑一遍本函数，同一页会被重复拉回。
            // 按 id 去重，否则第 N 页在屏上出现两次。
            val seen = accumulated.mapTo(HashSet()) { it.id }
            val all = accumulated + p.items.filter { seen.add(it.id) }
            setScreen(Screen.Market(all, p.total, page))
            col.removeAllViews()
            renderMarket(col, all, page, p.total, p.pages)
        }.onFailure { e ->
            col.removeAllViews()
            col.addView(Widgets.emptyState(this, "warning",
                "加载失败：${e.message?.take(60) ?: "网络不可用"}"))
            col.addView(Widgets.button(this, "重试", "tonal") { showMarket(page, accumulated) })
        }
    }
}

private fun MainActivity.renderMarket(
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
        card.addView(Md3.text(this, "共 $total 条 · 已看 $page/$pages 页", Md3.FS_MD, Md3.onSurface))
        card.addView(Md3.text(this,
            "点一条即拉取并导入。全量太大，只按需拉", Md3.FS_SM, Md3.onSurfaceVariant))
        col.addView(card)
    }

    // legado 的列表是「加载更多」往下接，不换页：前面几页留在屏上，滚回去还能点。
    // 这里按 MARKET_PAGE_SIZE 分段，每段前挂一条页码，翻页后旧条目不会跳走。
    items.chunked(MARKET_PAGE_SIZE).forEachIndexed { i, chunk ->
        col.addView(Widgets.sectionLabel(this, "第 ${i + 1} 页"))
        for (it0 in chunk) {
            val has = installed.any { it.startsWith(it0.id.take(8)) }
            col.addView(Widgets.listItem(
                this, it0.name,
                if (has) "已导入" else "热度 ${it0.views}",
                twoLine = true, highlight = false,
                leading = Icons.view(this, if (has) "check" else "download",
                    if (has) Md3.ok else Md3.onSurfaceVariant, 18),
            ) { if (!has) installFromMarket(it0.name, it0.id, page, items) })
        }
    }

    // pages 未知（首屏还没拿到响应）时不能把「下一页」藏掉，否则第 1 页就没出路。
    if (pages <= 0 || page < pages) {
        val more = Widgets.button(this, "下一页", "tonal") { showMarket(page + 1, items) }
        more.layoutParams = Md3.lpDp(this, null, Md3.BTN).apply {
            topMargin = Md3.dp(this@renderMarket, Md3.GAP)
        }
        col.addView(more)
    } else {
        col.addView(endOfListNote())
    }
}

/** 拉单条规则并导入（约 1 秒），成功后回列表把该条标成已导入。 */
private fun MainActivity.installFromMarket(name: String, id: String, page: Int, shown: List<YioveSource.Item>) {
    toast("正在导入「$name」…")
    val owner: Screen = Screen.Market(shown, 0, page)
    bg(owner, { YioveSource.install(sourcesDir(), YioveSource.Item(id, name, "", 0)) }) { res ->
        res.onSuccess { r ->
            toast(if (r.ok) "已导入：${r.name}" else "不支持：${r.issue ?: "未知原因"}")
            showMarket(page, shown.filter { it.name != name })
        }.onFailure { toast("导入失败：${it.message?.take(50)}") }
    }
}

/** 从云端整包更新书源库。成功后回书源页，能看到新的条数。 */
private fun MainActivity.updateFromCloud() {
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
internal data class SourceRow(
    val file: String,
    val name: String,
    val mark: String,
    val note: String?,
    /** 声明为 http:// 的源（M7）；实际是否回落到明文由 [Http.cleartextHosts] 决定。 */
    val declaredHttp: Boolean = false,
    /** 书源根地址，用来把探测结论对到这条行上。null = 导入器没给出。 */
    val host: String? = null,
    /** 已导入的规则。行菜单靠它判断「搜索」这一项给不给。 */
    val rule: BookSourceRule? = null,
) {
    /** 声明明文、且探测结果明确说这个 host 升不上 https。 */
    val plainConfirmed: Boolean
        get() = declaredHttp && host != null && Http.isCleartextHost(host)
}

/** 一次读盘的结果：行 + 诊断计数。缓存在 [SourceUi.data]，重画时不再解析规则。 */
internal data class SourceData(
    val files: List<File>,
    val rows: List<SourceRow>,
    val total: Int,
    val usable: Int,
    /** 裸编译就失败的正则数（导入时会走兜底修一次）。 */
    val badRegex: Int = 0,
    /** 兜底也没修好的正则数（这些源大概率跑不起来）。 */
    val patched: Int = 0,
    /** 导入抛点的最后一条记录。null = 没有。 */
    val importTrace: String? = null,
)

/** 诊断用：本地书源目录路径。 */
private val MainActivity.srcDirPath: String get() = File(filesDir, "sources").absolutePath

/**
 * 读 [filesDir]/sources 下所有 json，导入并统计。
 *
 * 抽成独立函数是为了让调用方能用 try 包住 —— 以前这段直接写在 thread 里，
 * 一抛异常整屏就空白，表现成「书源 0 条」。
 */
private fun MainActivity.buildLocalSourceRows(): SourceData {
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
                i.declaredHttp, i.rule?.host, i.rule)
        }
    }
    return SourceData(files, rows, total, usable)
}

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
internal fun MainActivity.sourceFiles(dir: File): List<File> =
    dir.listFiles()
        ?.filter { it.isFile && it.extension == "json" }
        ?.filter { !it.name.startsWith(".") }
        ?.sortedBy { it.name }
        ?: emptyList()

/** 书源目录。本地书源与市场导入的都放这里。 */
internal fun MainActivity.sourcesDir(): File = File(filesDir, "sources").apply { mkdirs() }

/**
 * 把导入器的长原因压缩成一句能看的短句。
 *
 * `部分字段用脚本：searchUrl(脚本)、content(非@js脚本)` 这种在列表里
 * 只占一行、又看不出重点，所以归类到几个大类。
 */
private fun MainActivity.shortReason(note: String?): String {
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

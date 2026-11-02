package com.watchdl.core

import java.io.File

/**
 * 书源管理屏的本地状态与纯函数：启用/禁用、移除名单、手动顺序、筛选、排序、多选。
 *
 * 依据本地参考仓 `~/ref/legado-novel`（Compose/MD3，3.26.21）的
 * `ui/book/source/manage/BookSourceScreen.kt`、`BookSourceViewModel.kt`
 * （`FILTER_ENABLED` / `FILTER_DISABLED` / `PREFIX_GROUP`）、`BookSourceSort.kt`。
 * legado 把 `enabled` / `customOrder` / `bookSourceGroup` 存在 Room 的 booksource 表里；
 * 我们的书源是磁盘上的整包 JSON，用户也明确要求「不要改书源 JSON 本体」，
 * 所以这些状态全部落在单独的小文件 `sources/.state.json`：
 * 点号开头，[WatchDownloader 的 `sourceFiles()`] 与云端整包同步都不会碰它。
 *
 * 为什么纯函数留在这里而不是写进屏：这一页的过滤、排序、置顶置底、批量开关
 * 全是「给一组名字算一组名字」，抽出来 JVM 自检能直接断言，界面只负责摆放。
 */

/** 筛选常量，取值照 legado `BookSourceViewModel.FILTER_*`（同一套 @ 前缀）。 */
object SourceFilter {
    /** 全部。legado 用 null，这里用空串：Kotlin 侧少一层可空判断。 */
    const val ALL = ""

    /** 已启用。 */
    const val ENABLED = "@enabled"

    /** 已禁用。 */
    const val DISABLED = "@disabled"

    // legado 还有 @login（需登录）、@noGroup（未分组）、@enabledExplore / @disabledExplore
    // 以及 `group:<名>`（用户自建分组）。这些全部来自书源 JSON 的
    // `bookSourceGroup` / `loginUrl` / `exploreUrl` 字段，我们的导入器根本不读，
    // 源库里也没有发现地址与登录地址，所以不造这三个筛选项。
}

/** 排序档，照 legado `BookSourceSort`。只留我们真有数据的三种。 */
enum class SourceSort {
    /** 手动排序（legado 的 Default）：置顶/置底改的就是这个顺序。 */
    Order,

    /** 名称排序（legado Name）。 */
    Name,

    /** 可用性排序（本项目独有）：✓ 在前、⚠ 与 ✗ 在后。 */
    Usable,

    // legado 另外还有 Url / Weight / Update / Respond / Enable 五档：
    // 我们既没有权重数据，也没有响应时间统计（校验一律不联网），故不造。
}

/** 书源管理屏的落盘状态。存的是**源名**，不是文件位置 —— 源名是界面认得的东西。 */
data class SourcePrefs(
    /** 被禁用（不参与搜索）的源名。 */
    val disabled: Set<String> = emptySet(),
    /** 被用户从列表删掉的源名。源文件不动，只记墓碑，避免整包 JSON 被写坏。 */
    val removed: Set<String> = emptySet(),
    /** 手动顺序：名字在这个表里排前面，不在的按原顺序跟在后头。 */
    val order: List<String> = emptyList(),
)

/** 是否启用。legado 的 `enabled` 字段，这里用「禁用集合」的反面表示。 */
fun sourceEnabled(disabled: Set<String>, name: String): Boolean = name !in disabled

/**
 * 点一下行的启用位。返回操作后是否启用。
 *
 * 用可变集合而不是返回新集合：屏上只有一份状态（`SourceUi`），
 * 每次改完立刻落盘，不必在界面层再造一层不可变复制。
 */
fun sourceToggle(disabled: MutableSet<String>, name: String): Boolean =
    if (disabled.remove(name)) true else { disabled.add(name); false }

/** 批量启用 / 禁用（legado `SetEnabledForSelection`）。 */
fun sourceSetEnabled(disabled: MutableSet<String>, names: Collection<String>, enabled: Boolean) {
    if (enabled) names.forEach { disabled.remove(it) }
    else names.forEach { disabled.add(it) }
}

/** 这条源是否该按 [filter] 显示。 */
fun sourceMatchFilter(enabled: Boolean, filter: String): Boolean = when (filter) {
    SourceFilter.ENABLED -> enabled
    SourceFilter.DISABLED -> !enabled
    else -> true
}

/** 筛选项文案。照 legado：全部 / 已启用 / 已禁用。 */
fun sourceFilterLabel(filter: String): String = when (filter) {
    SourceFilter.ENABLED -> "已启用"
    SourceFilter.DISABLED -> "已禁用"
    else -> "全部"
}

/** 顶栏副标题：`共 N · 启用 M`。legado 那里放的是分组名，我们没分组，改放统计。 */
fun sourceSubtitle(total: Int, enabled: Int): String {
    val n = total.coerceAtLeast(0)
    return "共 $n · 启用 ${enabled.coerceIn(0, n)}"
}

/** 列表分组标题：搜索优先，其次才是筛选项。 */
fun sourceListLabel(filter: String, query: String, count: Int): String {
    val n = count.coerceAtLeast(0)
    val q = query.trim()
    return if (q.isNotEmpty()) "「$q」$n 条"
    else if (filter == SourceFilter.ALL) "全部书源（$n）"
    else "${sourceFilterLabel(filter)}（$n）"
}

/** 排序档文案。前两档照 legado `sort_manual` / `sort_by_name` 的措辞。 */
fun sourceSortLabel(sort: SourceSort): String = when (sort) {
    SourceSort.Order -> "手动排序"
    SourceSort.Name -> "名称排序"
    SourceSort.Usable -> "可用性排序"
}

/**
 * 按 [order] 摆一遍，不在表里的名字按 [names] 的原顺序接在后面。
 *
 * 稳定排序：同分不动，所以用户看到的顺序不会因为一次重画就跳。
 */
fun sourceApplyOrder(names: List<String>, order: List<String>): List<String> {
    if (order.isEmpty()) return names
    val rank = HashMap<String, Int>(order.size * 2)
    order.forEachIndexed { i, n -> rank.putIfAbsent(n, i) }
    // withIndex 保证 sortedWith 的稳定性不依赖实现细节。
    return names.withIndex()
        .sortedWith(compareBy({ rank[it.value] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}

/**
 * 置顶 / 置底（legado `MoveToEdge`）。返回覆盖全部 [names] 的新顺序。
 *
 * legado 改的是每行的 `customOrder` 整数；我们直接摆名字表，
 * 结果等价且自检能一眼断言。
 */
fun sourceMoveToEdge(
    names: List<String>,
    order: List<String>,
    move: Collection<String>,
    toTop: Boolean,
): List<String> {
    val base = sourceApplyOrder(names, order).toMutableList()
    val picked = base.filter { it in move }
    if (picked.isEmpty()) return base
    base.removeAll(picked.toSet())
    return if (toTop) picked + base else base + picked
}

/**
 * 排序落地：三档都先按 [order] 摆一遍，再按档位稳定重排。
 *
 * 先摆 order 再排的好处：名称排序之外的两档都保留用户手动调过的相对顺序。
 * [usable] 是带 ✓ 标记的源名集合 —— 可用性只有界面知道，所以从外面传进来。
 */
fun sourceSortNames(
    names: List<String>,
    sort: SourceSort,
    order: List<String>,
    usable: Set<String>,
): List<String> = when (sort) {
    SourceSort.Order -> sourceApplyOrder(names, order)
    // 名称排序按拼音，不按码位。中文码位序（丙 U+4E19 < 乙 U+4E59 < 甲 U+7532）
    // 对用户是乱序；legado 也是用 Collator 比中文（utils/StringExtensions.kt:100）。
    SourceSort.Name -> names.sortedWith(collator)
    SourceSort.Usable -> sourceApplyOrder(names, order)
        .sortedBy { if (it in usable) 0 else 1 }
}

/** 中文按拼音比。Collator 不是线程安全的，每次调用新建一个，别放成全局单例。 */
private val collator: Comparator<String>
    get() = java.text.Collator.getInstance(java.util.Locale.CHINA) as Comparator<String>

/** 多选标题。照书架屏的「已选 N」；legado 是 `list_selected_count`「已选择 %d/%d」。 */
fun sourceSelectTitle(count: Int): String = "已选 ${count.coerceAtLeast(0)}"

/** 选中即移出、未选即加入。返回操作后是否选中。 */
fun sourceToggleSelect(selected: MutableSet<String>, name: String): Boolean =
    if (selected.remove(name)) false else { selected.add(name); true }

/** 全选 / 全不选。返回操作后是否变成全选。 */
fun sourceSelectAll(selected: MutableSet<String>, all: Collection<String>): Boolean {
    val wantAll = !(all.isNotEmpty() && selected.containsAll(all))
    selected.clear()
    if (wantAll) selected.addAll(all)
    return wantAll
}

/** 删掉已经不在列表里的选中项。返回是否还有选中。 */
fun sourcePruneSelection(selected: MutableSet<String>, alive: Collection<String>): Boolean {
    val keep = alive.toSet()
    selected.retainAll(keep)
    return selected.isNotEmpty()
}

/**
 * 删除确认正文。
 *
 * legado 的正文只有一句 `sure_del`「是否确认删除?」—— 它删的是数据库里的条目。
 * 我们删的只是列表里的一条（源文件不动），所以必须把「文件不动」写进正文：
 * 手表上一句话讲不清后果，用户就会以为源文件被删了。
 */
fun sourceDeleteMessage(names: List<String>): String = when (names.size) {
    0 -> "没有选中任何书源"
    1 -> "移除《${names[0]}》\n源文件不动，只是不再显示"
    else -> "移除 ${names.size} 条书源\n源文件不动，只是不再显示"
}

/** 状态落盘成 JSON。禁用/移除是名字集合，顺序是名字数组。 */
fun sourceStateJson(prefs: SourcePrefs): String {
    fun arr(xs: Collection<String>) = xs.joinToString(",", "[", "]") {
        "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
    return "{\"disabled\":${arr(prefs.disabled)}," +
        "\"removed\":${arr(prefs.removed)}," +
        "\"order\":${arr(prefs.order)}}"
}

/** 读回 JSON。坏文件当成空状态 —— 少一个源的状态不该让整屏打不开。 */
fun sourceStateParse(json: String): SourcePrefs = runCatching {
    SourcePrefs(
        disabled = TinyJson.stringArray(json, "disabled").toSet(),
        removed = TinyJson.stringArray(json, "removed").toSet(),
        order = TinyJson.stringArray(json, "order"),
    )
}.getOrElse { SourcePrefs() }

/** `sources/.state.json` 的读写。 */
object SourcePrefsStore {
    const val NAME = ".state.json"

    fun file(dir: File): File = File(dir, NAME)

    fun load(dir: File): SourcePrefs {
        val f = file(dir)
        if (!f.isFile) return SourcePrefs()
        return sourceStateParse(runCatching { f.readText() }.getOrElse { return SourcePrefs() })
    }

    fun save(dir: File, prefs: SourcePrefs) {
        runCatching {
            dir.mkdirs()
            file(dir).writeText(sourceStateJson(prefs), Charsets.UTF_8)
        }
    }
}

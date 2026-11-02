package com.watchdl.core

import java.io.File

/**
 * 替换净化屏的纯逻辑与落盘状态：搜索、排序、置顶置底、多选、编辑后的合并。
 *
 * 依据本地参考仓 `~/ref/legado-novel`（Compose/MD3，3.26.21）的
 * `ui/replace/ReplaceRuleScreen.kt`、`ReplaceRuleViewModel.kt`：
 * legado 的排序档是 `asc` / `desc` / `name_asc` / `name_desc`，
 * 置顶置底改的是每行的 `customOrder`，多选走 `ToggleSelection` / `SelectAll` /
 * `InvertSelection`，删除前必过一遍 `sure_del` 确认框。
 *
 * legado 把这些存在 Room 的 `replaceRule` 表里，我们的规则是磁盘上整份
 * `replace.json`（用户手工放进应用目录），所以只有「排序与手动顺序」另存一个
 * 小文件 `replace.state.json`，规则本体仍归 [ReplaceRuleStore] 写。
 *
 * 为什么纯函数留在这里而不写进屏：这一页的搜索、排序、置顶置底、
 * 多选全删都是「给一批规则算另一批规则」，抽出来 JVM 自检能直接断言，
 * 界面只负责摆放。规则没有稳定 id（文件里只有 name 与 pattern），
 * 所以一律用 [replaceKey] 当身份。
 */

/** 排序档。照 legado `SetSortMode` 四档，但我们没有时间字段，只留三档。 */
enum class ReplaceSort {
    /** 文件原文顺序 + 用户手动调过的顺序（legado 的 `default`）。 */
    Order,

    /** 名称升序（legado `name_asc`）。 */
    Name,

    /** 启用优先（本项目独有）：开着的排前面，关着的沉底。 */
    Enabled,

    // legado 另有 `asc`（旧到新）/ `desc`（新到旧）：那两档靠 `id` 或
    // `updateTime` 排，我们的规则文件里没有这两个字段，造不出来。
    // `name_desc`（名称降序）也没做：名称只有几十条时正序反序看到的是同一批。
}

/** 落盘状态。存的是规则的「名称+模式」键，不是文件位置。 */
data class ReplacePrefs(
    /** 排序档。 */
    val sort: ReplaceSort = ReplaceSort.Order,
    /** 手动顺序。键在这个表里排前面，不在的按文件原顺序接在后面。 */
    val order: List<String> = emptyList(),
)

/**
 * 规则身份键：名称 + 模式。
 *
 * 分隔符取不可见控制符 U+0000：规则名里不可能出现它，拼出来的键不会撞。
 * 落盘时转义成 `\u0000`，见 [replaceStateJson]。
 */
fun replaceKey(name: String, pattern: String): String = "$name\u0000$pattern"

/** 一条规则的键。 */
fun replaceKeyOf(r: ReplaceRule): String = replaceKey(r.name, r.pattern)

/** 一批规则的键，顺序同传入顺序。 */
fun replaceKeys(rules: List<ReplaceRule>): List<String> = rules.map { replaceKeyOf(it) }

/** 作用范围文案。legado 的 `ReplaceRule.scope` 取值同这四个字面量。 */
fun replaceScopeLabel(scope: String): String = when (scope) {
    "title" -> "标题"
    "both" -> "正文+标题"
    else -> "正文"
}

/**
 * 行的副标题：正则/纯文本 · 作用范围 · 替换为「…」或「空」。
 *
 * legado 行副标题只给 `pattern`；我们把「正则/纯文本」和「替换成什么」也塞进来，
 * 因为手表上没有编辑页可点进去看（本页编辑要长按菜单才够得着），
 * 一眼看清「这条会不会把字删掉」比照抄副标题更重要。
 */
fun replaceRowSubtitle(r: ReplaceRule): String {
    val kind = if (r.isRegex) "正则" else "纯文本"
    val to = if (r.replacement.isBlank()) "空" else "「${r.replacement}」"
    return "$kind · ${replaceScopeLabel(r.scope)} · 替换为$to"
}

/** 顶栏副标题：`N 条 · 启用 M 条`。legado 那里没有统计，这里补一个。 */
fun replaceSubtitle(total: Int, enabled: Int): String {
    val n = total.coerceAtLeast(0)
    return "$n 条 · 启用 ${enabled.coerceIn(0, n)} 条"
}

/** 列表分组标题：搜索优先，其次才是排序档。 */
fun replaceListLabel(query: String, count: Int): String {
    val q = query.trim()
    return if (q.isNotEmpty()) "「$q」${count.coerceAtLeast(0)} 条" else "全部规则（${count.coerceAtLeast(0)}）"
}

/** 排序档文案。照 legado 菜单措辞（旧到新那两档不存在，见 [ReplaceSort]）。 */
fun replaceSortLabel(sort: ReplaceSort): String = when (sort) {
    ReplaceSort.Order -> "原文顺序"
    ReplaceSort.Name -> "名称排序"
    ReplaceSort.Enabled -> "启用优先"
}

/**
 * 搜索。名称与模式都参与匹配 —— 用户记得住的多半是模式里的关键词
 * （比如「请记住本站」），不是自己起的规则名。
 */
fun replaceMatch(rules: List<ReplaceRule>, query: String): List<ReplaceRule> {
    val q = query.trim()
    if (q.isEmpty()) return rules
    return rules.filter {
        it.name.contains(q, ignoreCase = true) || it.pattern.contains(q, ignoreCase = true)
    }
}

/** 按 [order] 摆一遍，不在表里的键按 [rules] 的原顺序接在后面。 */
fun replaceApplyOrder(rules: List<ReplaceRule>, order: List<String>): List<ReplaceRule> {
    if (order.isEmpty()) return rules
    val byKey = rules.associateBy { replaceKeyOf(it) }
    val head = order.mapNotNull { byKey[it] }
    val seen = head.map { replaceKeyOf(it) }.toSet()
    return head + rules.filter { replaceKeyOf(it) !in seen }
}

/** 中文按拼音比，不按码位：中文码位序对用户是乱序（同 SourceState）。 */
private val replaceCollator: Comparator<String>
    get() = java.text.Collator.getInstance(java.util.Locale.CHINA) as Comparator<String>

/**
 * 排序落地。三档都先按 [order] 摆一遍，再按档位稳定重排。
 *
 * 先摆 order 的好处：名称排序之外两档都保留用户手动调过的相对顺序；
 * 同名同档时顺序不会因为一次重画就跳。
 */
fun replaceSorted(
    rules: List<ReplaceRule>,
    sort: ReplaceSort,
    order: List<String>,
): List<ReplaceRule> = when (sort) {
    ReplaceSort.Order -> replaceApplyOrder(rules, order)
    ReplaceSort.Name -> rules.sortedWith(compareBy(replaceCollator) { it.name })
    ReplaceSort.Enabled -> replaceApplyOrder(rules, order)
        .sortedBy { if (it.enabled) 0 else 1 }
}

/**
 * 置顶 / 置底（legado `ToTop` / `ToBottom`）。返回覆盖全部规则的新手动顺序。
 *
 * 与书源屏一致：置顶置底在非手动排序下也常显，点下去由界面把排序档切回
 * [ReplaceSort.Order] —— 手表上「菜单项忽隐忽现」比「操作后自动换档」更难懂。
 */
fun replaceMoveToEdge(
    rules: List<ReplaceRule>,
    order: List<String>,
    keys: Collection<String>,
    toTop: Boolean,
): List<String> {
    val base = replaceApplyOrder(rules, order).map { replaceKeyOf(it) }.toMutableList()
    val picked = base.filter { it in keys }
    if (picked.isEmpty()) return base
    base.removeAll(picked.toSet())
    return if (toTop) picked + base else base + picked
}

/** 批量启用 / 禁用。返回新表，不改入参（[ReplaceRule] 是不可变的）。 */
fun replaceSetEnabled(
    rules: List<ReplaceRule>,
    keys: Collection<String>,
    enabled: Boolean,
): List<ReplaceRule> = rules.map {
    if (replaceKeyOf(it) in keys) ReplaceRule(
        it.name, it.pattern, it.replacement, it.isRegex, it.scope, enabled,
    ) else it
}

/** 点一下行的启用位：只翻这一条。 */
fun replaceFlipEnabled(rules: List<ReplaceRule>, key: String): List<ReplaceRule> {
    val cur = rules.firstOrNull { replaceKeyOf(it) == key }?.enabled ?: return rules
    return replaceSetEnabled(rules, listOf(key), !cur)
}

/** 删除。返回剩下的规则。 */
fun replaceRemove(rules: List<ReplaceRule>, keys: Collection<String>): List<ReplaceRule> =
    rules.filter { replaceKeyOf(it) !in keys }

/**
 * 编辑保存 / 新建：按 [oldKey] 找到原行替换，找不到就追加。
 *
 * oldKey 允许改名的情形：用户在编辑框里把名称改了，键也就变了，
 * 所以必须用「编辑前的键」定位，而不是用新规则自己的键。
 */
fun replaceUpsert(
    rules: List<ReplaceRule>,
    oldKey: String?,
    rule: ReplaceRule,
): List<ReplaceRule> {
    val idx = if (oldKey == null) -1 else rules.indexOfFirst { replaceKeyOf(it) == oldKey }
    if (idx < 0) return rules + rule
    return rules.toMutableList().also { it[idx] = rule }
}

/**
 * 编辑 / 新建的校验。返回错误文案，null 表示可以保存。
 *
 * 模式为空是最常见的手误：空模式的正则匹配所有位置，
 * 一条空规则会把整章正文删干净，所以这里必须拦住。
 */
fun replaceValidate(name: String, pattern: String): String? = when {
    name.isBlank() -> "名称不能空"
    pattern.isBlank() -> "查找内容不能空"
    else -> null
}

// ── 多选（照 SourceState 的 sourceToggleSelect 一组） ──────────────

/** 多选标题。照书架屏的「已选 N」。 */
fun replaceSelectTitle(count: Int): String = "已选 ${count.coerceAtLeast(0)}"

/** 选中即移出、未选即加入。返回操作后是否选中。 */
fun replaceToggleSelect(selected: MutableSet<String>, key: String): Boolean =
    if (selected.remove(key)) false else { selected.add(key); true }

/** 全选 / 全不选。返回操作后是否变成全选。 */
fun replaceSelectAll(selected: MutableSet<String>, all: Collection<String>): Boolean {
    val wantAll = !(all.isNotEmpty() && selected.containsAll(all))
    selected.clear()
    if (wantAll) selected.addAll(all)
    return wantAll
}

/** 删掉已经不在列表里的选中项。返回是否还有选中。 */
fun replacePruneSelection(selected: MutableSet<String>, alive: Collection<String>): Boolean {
    val keep = alive.toSet()
    selected.retainAll(keep)
    return selected.isNotEmpty()
}

/** 删除确认正文。手表上没有回收站，所以把「怎么找回」写进去。 */
fun replaceDeleteMessage(count: Int): String =
    "会删掉 $count 条规则，删掉就找不回来。\n要恢复只能重新导入 replace.json。"

// ── 落盘 ───────────────────────────────────────────────────────────

/** 状态文件内容。键里有 U+0000，必须转义成 `\u0000` 才写得出合法 JSON。 */
fun replaceStateJson(prefs: ReplacePrefs): String {
    fun esc(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u").append(String.format("%04x", c.code))
            else -> append(c)
        }
    }
    val order = prefs.order.joinToString(",", "[", "]") { "\"${esc(it)}\"" }
    return "{\"sort\":\"${prefs.sort.name}\",\"order\":$order}"
}

/**
 * 读回状态文件内容。
 *
 * 文件坏了、字段缺了都退回默认值：排序档坏了顶多顺序变回原文顺序，
 * 不能让整个替换净化屏起不来。
 */
fun replaceStateParse(json: String): ReplacePrefs {
    val sort = ReplaceSort.values().firstOrNull {
        json.contains("\"sort\":\"${it.name}\"")
    } ?: ReplaceSort.Order
    val start = json.indexOf("\"order\":[")
    if (start < 0) return ReplacePrefs(sort)
    val from = start + "\"order\":[".length
    val end = json.indexOf(']', from).let { if (it < 0) json.length else it }
    return ReplacePrefs(sort, parseReplaceStrings(json.substring(from, end)))
}

/** 扫一段 `"a","b\u0000c"` 里的字符串字面量。 */
private fun parseReplaceStrings(s: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < s.length) {
        if (s[i] != '"') { i++; continue }
        val sb = StringBuilder()
        i++
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (val c = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 4
                    }
                    else -> sb.append(c)
                }
                i += 2
            } else {
                sb.append(s[i]); i++
            }
        }
        out += sb.toString()
        i++
    }
    return out
}

/** 状态文件读写。放在 filesDir 根上，与 replace.json 平级但互不影响。 */
object ReplaceStateStore {
    /** 独立小文件：不写进 replace.json，规则本体与界面偏好各归各的。 */
    const val NAME = "replace.state.json"

    fun file(dir: File): File = File(dir, NAME)

    fun load(dir: File): ReplacePrefs {
        val f = file(dir)
        if (!f.isFile) return ReplacePrefs()
        return replaceStateParse(runCatching { f.readText() }.getOrElse { return ReplacePrefs() })
    }

    fun save(dir: File, prefs: ReplacePrefs) {
        runCatching {
            dir.mkdirs()
            file(dir).writeText(replaceStateJson(prefs), Charsets.UTF_8)
        }
    }
}

package com.watchdl.ui.my

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.watchdl.MainActivity
import com.watchdl.core.ReplacePrefs
import com.watchdl.core.ReplaceRule
import com.watchdl.core.ReplaceRuleStore
import com.watchdl.core.ReplaceSort
import com.watchdl.core.ReplaceStateStore
import com.watchdl.core.Screen
import com.watchdl.core.replaceDeleteMessage
import com.watchdl.core.replaceFlipEnabled
import com.watchdl.core.replaceKeyOf
import com.watchdl.core.replaceKeys
import com.watchdl.core.replaceListLabel
import com.watchdl.core.replaceMatch
import com.watchdl.core.replaceMoveToEdge
import com.watchdl.core.replacePruneSelection
import com.watchdl.core.replaceRemove
import com.watchdl.core.replaceRowSubtitle
import com.watchdl.core.replaceSelectAll
import com.watchdl.core.replaceSelectTitle
import com.watchdl.core.replaceSetEnabled
import com.watchdl.core.replaceSortLabel
import com.watchdl.core.replaceSorted
import com.watchdl.core.replaceSubtitle
import com.watchdl.core.replaceToggleSelect
import com.watchdl.core.replaceUpsert
import com.watchdl.core.replaceValidate
import com.watchdl.ui.search.askText
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File

/**
 * 替换净化（#28）：正文替换规则的列表、搜索、排序与编辑。
 *
 * 照本地参考仓 `~/ref/legado-novel`（Compose/MD3，3.26.21）的
 * `ui/replace/ReplaceRuleScreen.kt` 与 `SelectionItemCard.kt` 的
 * `ReorderableSelectionItem` 抄的是**位置与语义**：
 *
 * | legado | 这里（189 × 248 dp 表盘） |
 * |---|---|
 * | 顶栏搜索切换 + ⋮（导入 / 分组 / 排序四档） | 顶栏 search + ⋮（排序三档 / 全启停 / 新建 / 导入） |
 * | 行 = 勾 + 标题 + pattern + Switch + 编辑 + ⋮ | 行 = 30dp 勾（兼启用位）+ 标题 + 副标题 + 28dp ⋮ |
 * | 长按选择、顶栏 全选 / 反选 / 清除 | 长按选择、顶栏 ✕ / 已选 N / 全选 |
 * | 选择动作在底部工具条（启用 / 停用 / 置顶 / 置底 / 导出） | 底部工具条三枚（启用 / 停用 / 删除） |
 * | 点行进 ReplaceRuleEditScreen | 行菜单「编辑」进三行输入对话框 |
 * | 删除二次确认（`sure_del`） | 同样二次确认，正文写明「找不回来」 |
 * | 拖动排序 | 不拖动：行菜单置顶 / 置底（表盘上拖拽手势与翻页抢） |
 *
 * 尺寸压在哪、为什么压，写在每个函数头上。
 */

/**
 * 屏内状态。放文件级 object 而不是往 MainActivity 加字段：
 * 搜索词、排序档、多选只属于这一屏，离开就重来；
 * 排序档与手动顺序要落盘（[ReplaceStateStore]），其余不落。
 */
internal object ReplaceUi {

    var sort = ReplaceSort.Order
    val order = mutableListOf<String>()
    var query = ""
    var selecting = false
    val selected = linkedSetOf<String>()
    private var loaded = false

    /** 只在第一次进屏时读盘：每次重画都读文件会在点按后多一次磁盘 IO。 */
    fun load(dir: File) {
        if (loaded) return
        val p = ReplaceStateStore.load(dir)
        sort = p.sort
        order.clear(); order += p.order
        loaded = true
    }

    fun save(dir: File) = ReplaceStateStore.save(dir, ReplacePrefs(sort, order.toList()))

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

internal fun MainActivity.showReplace() {
    setScreen(Screen.Replace)
    ReplaceUi.load(filesDir)

    val all = ReplaceRuleStore.list()
    val on = all.count { it.enabled }
    // 选中的规则可能被删掉或被过滤掉：留着会停在「已选 0」的死界面。
    if (ReplaceUi.selecting && !replacePruneSelection(ReplaceUi.selected, replaceKeys(all))) {
        ReplaceUi.exit()
    }

    root.removeAllViews()
    if (ReplaceUi.selecting) {
        root.addView(replaceSelectBar())
    } else {
        // 统计放顶栏副标题而不是列表上方一张统计卡：248 dp 高的屏上，
        // 卡片会把首屏列表挤到只剩两行（书源页同样的处理）。
        root.addView(Widgets.topBar(this, "替换净化", onBack = { showMy() },
            subtitle = replaceSubtitle(all.size, on),
            trailing = listOf(
                "search" to { askReplaceQuery() },
                "more" to { showReplaceMenu() },
            )))
    }

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val q = ReplaceUi.query.trim()
    val shown = replaceSorted(replaceMatch(all, q), ReplaceUi.sort, ReplaceUi.order)
    col.addView(Widgets.sectionLabel(this, replaceListLabel(q, shown.size)))

    if (shown.isEmpty()) {
        col.addView(Widgets.emptyState(this, "search", when {
            q.isNotEmpty() -> "没有名称或模式含「$q」的规则"
            all.isEmpty() -> "还没有替换规则。\n在 ⋮ 里新建，或导入 replace.json"
            else -> "规则表是空的。\n在 ⋮ 里新建一条"
        }))
    } else {
        for (r in shown) col.addView(replaceRow(r))
    }

    root.addView(body)
    // 批量动作固定在屏底一行 —— legado 的选择动作本来也在底部工具条，
    // 不在顶栏（顶栏三槽在 189 dp 上已经占满）。
    if (ReplaceUi.selecting) root.addView(replaceBatchBar())
}

/**
 * 启用位的勾。legado 用 M3 Switch（约 52 dp 宽），加上 ⋮ 与「编辑」两枚按钮
 * 就要 148 dp，189 dp 的屏上标题只剩二十来 dp。这里换成 30 dp 的勾：
 * 启用=主色、停用=压到 0.3 透明，是 Switch 在表盘上的等价物。
 *
 * @param act null 表示勾只做显示，点击交给整行（选择态下整行=选中，与 legado 一致）
 */
private fun MainActivity.replaceCheck(on: Boolean, act: (() -> Unit)?): View {
    val v = Icons.view(this, "check", if (on) Md3.primary else Md3.onSurfaceVariant, 16)
    v.alpha = if (on) 1f else 0.3f
    v.contentDescription = if (on) "已启用" else "已停用"
    if (act != null) {
        v.isClickable = true
        v.background = Md3.ripple(this, Color.TRANSPARENT, Md3.R_GRID.toFloat())
        v.setOnClickListener { act() }
    }
    return v
}

/**
 * 一条规则。
 *
 * 副标题给「正则/纯文本 · 作用范围 · 替换为什么」：legado 那里只有 pattern，
 * 我们多给两项 —— 手表上没有一按就进的编辑页，能不能看出「这条会不会把字删光」
 * 比照抄副标题更要紧。
 * 选择态收起 ⋮，整行点击变成选中；非选择态点整行切启用（沿用本页原有习惯，
 * legado 那里点行是进编辑，编辑改到 ⋮ 里）。
 */
private fun MainActivity.replaceRow(r: ReplaceRule): LinearLayout {
    val key = replaceKeyOf(r)
    val picked = key in ReplaceUi.selected
    val on = if (ReplaceUi.selecting) picked else r.enabled

    val row = Md3.row(this)
    row.minimumHeight = Md3.dp(this, Md3.ITEM_TALL)
    // 左右边距比 listItem 各收一点：给 30 dp 勾与 28 dp ⋮ 腾位置，文字仍与分组标题对齐。
    row.setPadding(Md3.dp(this, Md3.PAD * 3 / 4), 0, Md3.dp(this, Md3.PAD / 2), 0)
    row.background = Md3.ripple(this,
        if (picked) Md3.surface3 else Color.TRANSPARENT, Md3.R_ITEM.toFloat())

    row.addView(replaceCheck(on, null), LinearLayout.LayoutParams(
        Md3.dp(this, 30), Md3.dp(this, Md3.ITEM_TALL)).apply { gravity = Gravity.CENTER_VERTICAL })

    val text = Md3.column(this)
    text.addView(Md3.ellipsize(Md3.text(this, r.name, Md3.FS_MD, Md3.onSurface))
        .apply { alpha = if (r.enabled) 1f else 0.45f })
    // 副标题长（模式、范围、替换值三段），单行截断：两行副标题会把行高顶到 88 dp，
    // 一屏就只剩两条规则。
    text.addView(Md3.ellipsize(Md3.text(this, replaceRowSubtitle(r), Md3.FS_SM, Md3.onSurfaceVariant))
        .apply { alpha = if (r.enabled) 0.7f else 0.3f })
    row.addView(text, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    if (!ReplaceUi.selecting) {
        val menu = Md3.iconButton(this, "more", { showReplaceRowMenu(r) }, desc = "更多操作")
        // iconButton 的最小热区是 48 dp 宽（M3 顶栏规格），行里只给 28 dp，放开限制。
        menu.minimumWidth = 0
        menu.minimumHeight = 0
        menu.setPadding(Md3.dp(this, 1), 0, Md3.dp(this, 1), 0)
        row.addView(menu, LinearLayout.LayoutParams(
            Md3.dp(this, 28), Md3.dp(this, Md3.ITEM_TALL)).apply { gravity = Gravity.CENTER_VERTICAL })
    }

    row.isClickable = true
    row.setOnClickListener {
        if (ReplaceUi.selecting) toggleReplaceSelection(key) else flipReplace(key)
    }
    row.setOnLongClickListener {
        if (!ReplaceUi.selecting) {
            ReplaceUi.enter(key)
            showReplace()
        } else {
            toggleReplaceSelection(key)
        }
        true
    }
    return row
}

/**
 * 选择态顶栏：✕ / 已选 N / 全选。
 *
 * 写法照书源屏 `sourceSelectBar`：M3 一枚图标槽 48 dp，三枚就吃掉 144 dp，
 * 189 dp 的屏上标题会被压成 0。所以 34 / 标题 / 36 三槽。
 * 删除不放这里（放屏底批量条），否则顶栏要第四槽。
 */
private fun MainActivity.replaceSelectBar(): LinearLayout {
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
        ReplaceUi.exit()
        showReplace()
    })
    bar.addView(Md3.ellipsize(
        Md3.text(this, replaceSelectTitle(ReplaceUi.selected.size), Md3.FS_MD, Md3.onSurface,
            bold = true)).apply { gravity = Gravity.CENTER_VERTICAL },
        LinearLayout.LayoutParams(0, Md3.dp(this, Md3.TAB_H), 1f))
    bar.addView(slot("select_all", "全选", 36) {
        replaceSelectAll(ReplaceUi.selected, replaceKeys(ReplaceRuleStore.list()))
        showReplace()
    })
    return bar
}

/**
 * 屏底批量条：启用 / 停用 / 删除。
 *
 * legado 这几项在选择态次要动作里（⋮ 弹出）。189 dp 的宽度上再进一层菜单
 * 就是三次点击办一件事，所以照 legado 的位置（底部工具条）摊开 ——
 * 标签都压成两个字，14 sp 下每枚约 48 dp，三枚加间隔刚好装进 173 dp。
 */
private fun MainActivity.replaceBatchBar(): LinearLayout {
    val bar = Md3.row(this)
    bar.setPadding(Md3.dp(this, Md3.PAD / 2), 0, Md3.dp(this, Md3.PAD / 2),
        Md3.dp(this, Md3.GAP))
    val keys = ReplaceUi.selected.toList()

    fun add(label: String, act: () -> Unit) {
        val b = Widgets.button(this, label, "tonal") {
            if (keys.isEmpty()) toast("先选规则") else act()
        }
        b.contentDescription = label
        bar.addView(b, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f).apply {
            if (bar.childCount > 0) leftMargin = Md3.dp(this@replaceBatchBar, Md3.GAP / 2)
        })
    }

    add("启用") { setReplaceEnabled(keys, true) }
    add("停用") { setReplaceEnabled(keys, false) }
    add("删除") { confirmDeleteRules(keys) }
    return bar
}

/** 搜索规则。legado 是顶栏内联 SearchBar；手表上沿用本应用统一的输入对话框。 */
private fun MainActivity.askReplaceQuery() {
    askText("搜索规则", "名称或模式关键词（留空清除）", null) { q ->
        ReplaceUi.query = q.trim()
        showReplace()
    }
}

/**
 * 顶栏 ⋮。条目照 legado `dropDownMenuContent`：排序在前，其后是导入与新建。
 *
 * 与 legado 的差别：
 * - 排序四档压成三档（`asc` / `desc` 要 id 或时间字段，规则文件里没有）；
 * - **分组管理不做**：legado 的分组来自 Room 实体的 `group` 列，
 *   我们的 `ReplaceRule` 只有 name/pattern/replacement/isRegex/scope/enabled，
 *   没有分组字段，造不出来也不该造；
 * - 「全部启用 / 全部停用 / 从 replace.json 导入」原来是屏底两个整宽按钮，
 *   248 dp 上两行按钮把列表首屏挤掉一大块，现在收进这里。
 */
private fun MainActivity.showReplaceMenu() {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += "排序：${replaceSortLabel(ReplaceUi.sort)}" to { showReplaceSortMenu() }
    if (ReplaceUi.query.isNotEmpty()) {
        items += "清除搜索「${ReplaceUi.query}」" to {
            ReplaceUi.query = ""
            showReplace()
        }
    }
    val rules = ReplaceRuleStore.list()
    items += "全部启用" to { setReplaceEnabled(replaceKeys(rules), true) }
    items += "全部停用" to { setReplaceEnabled(replaceKeys(rules), false) }
    items += "新建规则" to { askReplaceRule(null) }
    items += "从 replace.json 导入" to { importRules() }
    Widgets.longMenuDialog(this, "替换净化", items).show()
}

/** 排序三档，选中项后面加「✓」（照书源屏的写法）。 */
private fun MainActivity.showReplaceSortMenu() {
    val items: List<Pair<CharSequence, () -> Unit>> = ReplaceSort.values().map { s ->
        val mark = if (ReplaceUi.sort == s) " ✓" else ""
        (replaceSortLabel(s) + mark) to {
            ReplaceUi.sort = s
            ReplaceUi.save(filesDir)
            showReplace()
        }
    }
    Widgets.longMenuDialog(this, "排序", items).show()
}

/**
 * 行菜单。照 legado `dropdownContent`：置顶 / 置底 / 删除，另外补一项「编辑」——
 * legado 点行就进编辑页，我们的点行留给启用位，编辑只能放这里。
 */
private fun MainActivity.showReplaceRowMenu(r: ReplaceRule) {
    val key = replaceKeyOf(r)
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    items += "置顶" to { moveReplaceToEdge(key, true) }
    items += "置底" to { moveReplaceToEdge(key, false) }
    items += "编辑" to { askReplaceRule(r) }
    items += "删除" to { confirmDeleteRules(listOf(key)) }
    Widgets.longMenuDialog(this, r.name, items).show()
}

/** 点整行切启用。按「名称+模式」定位：规则没有稳定 id，按下标改会改错行。 */
private fun MainActivity.flipReplace(key: String) {
    val rules = ReplaceRuleStore.list()
    val r = rules.firstOrNull { replaceKeyOf(it) == key } ?: return
    ReplaceRuleStore.save(replaceFlipEnabled(rules, key))
    toast("${r.name}已${if (r.enabled) "停用" else "启用"}")
    showReplace()
}

/** 批量启用 / 停用。 */
private fun MainActivity.setReplaceEnabled(keys: List<String>, enabled: Boolean) {
    if (keys.isEmpty()) return toast("先选规则")
    ReplaceRuleStore.save(replaceSetEnabled(ReplaceRuleStore.list(), keys, enabled))
    toast("已${if (enabled) "启用" else "停用"} ${keys.size} 条")
    showReplace()
}

/**
 * 置顶 / 置底。改的是手动顺序，所以顺手把排序档切回原文顺序 ——
 * 不切回来，用户在名称排序下点完看不到任何变化（照书源屏的处理）。
 */
private fun MainActivity.moveReplaceToEdge(key: String, toTop: Boolean) {
    val rules = ReplaceRuleStore.list()
    ReplaceUi.order.clear()
    ReplaceUi.order += replaceMoveToEdge(rules, ReplaceUi.order.toList(), listOf(key), toTop)
    ReplaceUi.sort = ReplaceSort.Order
    ReplaceUi.save(filesDir)
    toast(if (toTop) "已置顶" else "已置底")
    showReplace()
}

/** 多选：点一条切选中。 */
private fun MainActivity.toggleReplaceSelection(key: String) {
    replaceToggleSelect(ReplaceUi.selected, key)
    showReplace()
}

/**
 * 删除，必须二次确认（legado 也过一遍 `sure_del`）。
 *
 * 两处与 legado 不同，都因为我们的存储是整份 JSON：
 * - 空表会被 `ReplaceRuleStore.list()` 当成坏文件退回内置默认规则，
 *   删到一条不剩等于删了个寂寞，所以这里直接拦住；
 * - 删除后写回的是「剩下的规则」，文件里没有回收站可言，
 *   确认文案要把「只能重新导入 replace.json」说清。
 */
private fun MainActivity.confirmDeleteRules(keys: List<String>) {
    if (keys.isEmpty()) return toast("先选规则")
    if (replaceRemove(ReplaceRuleStore.list(), keys).isEmpty()) {
        toast("至少留一条：空表会被当成坏文件")
        return
    }
    Widgets.md3Dialog(this,
        title = "删除规则",
        body = replaceDeleteMessage(keys.size),
        confirm = "删除",
        onConfirm = {
            ReplaceRuleStore.save(replaceRemove(ReplaceRuleStore.list(), keys))
            ReplaceUi.exit()
            toast("已删除 ${keys.size} 条")
            showReplace()
        },
    ).show()
}

/** 一行输入框。平台默认是绿色下划线，与 MD3 紫系冲突，所以照 `askText` 换自绘圆角。 */
private fun MainActivity.replaceField(hintText: String, value: String): EditText =
    EditText(this).apply {
        hint = hintText
        setText(value)
        setSingleLine()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Md3.FS_MD)
        setHintTextColor(Md3.onSurfaceVariant)
        setTextColor(Md3.onSurface)
        background = Md3.bg(Md3.surface4, Md3.dp(this@replaceField, 8).toFloat())
        setPadding(Md3.dp(this@replaceField, 10), 0, Md3.dp(this@replaceField, 10), 0)
        // 30 dp 比 M3 的 56 dp 输入框矮：248 dp 高的屏上三个 56 dp 输入框
        // 加按钮就超过一屏，第四行永远看不见。
        minimumHeight = Md3.dp(this@replaceField, 30)
    }

/**
 * 编辑 / 新建。legado 有整页 `ReplaceRuleEditScreen`（名称 / 模式 / 替换为 /
 * 正则勾 / 作用范围 / 分组），一页六行在 248 dp 上放不下，所以压成一个对话框：
 * 三个输入框 + 正则勾 + 作用范围三枚小牌。分组不做（规则没有该字段）。
 *
 * 对话框里的内容装进固定高度的 ScrollView：字号放大或输入框变高时，
 * 多出来的是滚动而不是被窗口裁掉（`wrapDialog` 高度是 WRAP_CONTENT，
 * 超过屏高的部分会直接看不见）。
 */
private fun MainActivity.askReplaceRule(existing: ReplaceRule?) {
    val oldKey = existing?.let { replaceKeyOf(it) }
    fun open() {
        var scope = existing?.scope ?: "text"

        val name = replaceField("名称", existing?.name ?: "")
        val pattern = replaceField("查找内容（纯文本或正则）", existing?.pattern ?: "")
        val repl = replaceField("替换为（可留空）", existing?.replacement ?: "")
        val regex = CheckBox(this).apply {
            text = "正则"
            isChecked = existing?.isRegex ?: true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Md3.FS_SM)
            setTextColor(Md3.onSurface)
            // 平台方框默认是强调绿，勾选后与 MD3 主色撞色；只改色调，方框图形留给系统。
            buttonTintList = ColorStateList.valueOf(Md3.primary)
            minimumHeight = 0
            minimumWidth = 0
            setPadding(0, 0, Md3.dp(this@askReplaceRule, 4), 0)
        }

        // 作用范围。legado 用 RadioButton 竖排三行；竖排要 3 × 40 dp，
        // 所以改成 2 字小牌「正文 / 标题 / 两者」并排，与正则勾同一行。
        val scopes = listOf("text" to "正文", "title" to "标题", "both" to "两者")
        val chips = mutableListOf<TextView>()
        fun refreshChips() {
            for ((i, tv) in chips.withIndex()) {
                val on = scopes[i].first == scope
                tv.setTextColor(if (on) Md3.primary else Md3.onSurfaceVariant)
                tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                tv.background = Md3.ripple(this,
                    if (on) Md3.surface3 else Color.TRANSPARENT, Md3.R_GRID.toFloat())
            }
        }
        val chipRow = Md3.row(this)
        chipRow.addView(regex, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, Md3.dp(this, 28)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        for ((value, label) in scopes) {
            val tv = Md3.text(this, label, Md3.FS_SM, Md3.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { scope = value; refreshChips() }
            }
            chips += tv
            chipRow.addView(tv, LinearLayout.LayoutParams(0, Md3.dp(this, 28), 1f).apply {
                leftMargin = Md3.dp(this@askReplaceRule, 2)
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        refreshChips()

        val col = Md3.column(this)
        for (v in listOf<View>(name, pattern, repl)) {
            col.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 30)).apply {
                topMargin = Md3.dp(this@askReplaceRule, 5)
            })
        }
        col.addView(chipRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 28)).apply {
            topMargin = Md3.dp(this@askReplaceRule, 5)
        })

        // 内容区高 122 dp：对话框上下内边距 32 + 标题 26 + 按钮 40 + 两处间隔 24
        // 已经把 248 dp 用掉，剩下的就是这一块。
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        Widgets.md3Dialog(this,
            title = if (existing == null) "新建规则" else "编辑规则",
            content = scroller,
            confirm = "保存",
            dismiss = "取消",
            onConfirm = {
                val n = name.text.toString().trim()
                val p = pattern.text.toString().trim()
                val err = replaceValidate(n, p)
                if (err != null) {
                    // 确认键已经关过窗了：校验不过就补一句再开一次，别让用户白输。
                    toast(err)
                    open()
                } else {
                    val rule = ReplaceRule(
                        n, p, repl.text.toString().trim(), regex.isChecked, scope,
                        existing?.enabled ?: true,
                    )
                    ReplaceRuleStore.save(replaceUpsert(ReplaceRuleStore.list(), oldKey, rule))
                    // 改名或改模式就换了键，手动顺序表里的旧键要一起换，
                    // 否则顺序表指向一条不存在的规则，置顶对它失效。
                    val newKey = replaceKeyOf(rule)
                    if (oldKey != null && oldKey != newKey) {
                        val i = ReplaceUi.order.indexOf(oldKey)
                        if (i >= 0) {
                            ReplaceUi.order.removeAt(i)
                            ReplaceUi.order.add(i, newKey)
                            ReplaceUi.save(filesDir)
                        }
                    }
                    toast("已保存「$n」")
                    showReplace()
                }
            },
        ).show()
    }
    open()
}

/** 文件由用户自己放进应用私有目录（adb push），本页只负责解析与合并。 */
private fun MainActivity.importRules() {
    val f = ReplaceRuleStore.file
    if (f == null || !f.isFile) {
        toast("还没有 replace.json：先把规则文件放进应用目录")
        return
    }
    val raw = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    if (raw == null) {
        toast("replace.json 读不出来")
        return
    }
    val n = ReplaceRuleStore.importJson(raw)
    when {
        n < 0 -> toast("replace.json 不是规则数组")
        n == 0 -> toast("没有新规则（已有的按名称+模式跳过）")
        else -> toast("导入 $n 条")
    }
    showReplace()
}

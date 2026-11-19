package com.watchdl.ui.my

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.watchdl.MainActivity
import com.watchdl.core.DevLog
import com.watchdl.core.Palette
import com.watchdl.core.Screen
import com.watchdl.ui.reader.ReaderUi
import com.watchdl.ui.reader.loadReaderExtras
import com.watchdl.ui.reader.saveReaderExtras
import com.watchdl.ui.source.showSources
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 设置（从 `MainActivity.kt` 搬出，行为不变；对应 legado `ui/config/`）。
 *
 * legado 的设置是一张导航列表 + 十来个子页（外观 / 阅读界面 / 高级 / 封面 / 缓存 /
 * 备份 / 隐私 / 实验室）。手表只留我们真有能力的四页：外观、高级、阅读界面，
 * 外加本应用独有的表冠。每页的项都按 legado 的分组语义摆，尺寸按 189 × 248 dp 压。
 *
 * 屏名沿用 legado 的中文原名：`theme_setting` 是「外观」、`other_setting` 是「高级」、
 * `read_config` 是「阅读界面」。不叫「主题设置 / 其他设置 / 阅读设置」——
 * 后者还与阅读页里的「阅读设置」重名，两个屏同名会把返回路径看糊。
 */

/** 设置子页代号。字符串进 [Screen.Settings]，进程重建后能回到同一子页。 */
internal val PAGE_MAIN = "main"
internal val PAGE_THEME = "theme"
internal val PAGE_READ = "read"
internal val PAGE_CROWN = "crown"
internal val PAGE_OTHER = "other"

/**
 * 段式芯片高 30 dp。
 *
 * 不是 [Md3.BTN]（40）：一页要排 5 行段式，40 dp 就把「正文」组顶出屏外。
 * 芯片整条可点，横向铺满等分宽（约 39 dp），纵向由行距补，实测点得中。
 */
private const val CHIP_H = 30

/** 两字标签列宽。三段芯片要等分剩下的宽度，标签列一宽芯片就只剩两个字。 */
private const val LABEL_W = 40

/** 四字标签列宽（「首行缩进」）。 */
private const val LABEL_W_LONG = 58

/**
 * 设置页：首屏导航 + 四个子页，照 legado `ui/config/ConfigNavScreen`。
 *
 * 原来 7 组挤在一页，189 × 248 dp 上要滚三屏，找一项得靠记忆滑。
 * 阅读界面**改完立即落盘**（不用点「确定」），预览实时跟着变。
 *
 * [page] 取 [PAGE_MAIN] / [PAGE_THEME] / [PAGE_READ] / [PAGE_CROWN] / [PAGE_OTHER]。
 */
internal fun MainActivity.showSettings(page: String = PAGE_MAIN) {
    setScreen(Screen.Settings(page))
    // 子页不参与根栏横滑，不能沿用上一屏的 tabActive。
    tabActive = -1
    root.animate().cancel()
    root.translationX = 0f
    root.removeAllViews()
    val title = when (page) {
        PAGE_THEME -> "外观"
        PAGE_READ -> "阅读界面"
        PAGE_CROWN -> "表冠"
        PAGE_OTHER -> "高级"
        else -> "设置"
    }
    root.addView(Widgets.topBar(this, title, onBack = { goBackToPrevious() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    when (page) {
        PAGE_THEME -> settingsTheme(col)
        PAGE_READ -> settingsRead(col)
        PAGE_CROWN -> settingsCrown(col)
        PAGE_OTHER -> settingsOther(col)
        else -> settingsMain(col)
    }
    root.addView(body)
}

/**
 * 首屏：单组四项，纯标题行，无副标题。
 *
 * legado `ConfigNavScreen` 首屏就是八个 `ClickableSettingItem`，一个都不带
 * description。我们原来每项都写副标题，双行 72 dp × 4 = 288 dp，一屏放不下；
 * 去掉副标题后单行 48 dp × 4 = 192 dp，顶栏之后刚好一屏全见。
 *
 * 行尾照 legado 的 `ClickableSettingItem` 挂 ChevronRight：这四行都是「进去还有一屏」，
 * 不挂箭头时整行看着像开关，点下去却换页。
 *
 * 顺序照 legado 的语义：外观 → 高级 → 阅读界面。表冠放末位 ——
 * 本机靠旋转表冠滚动列表与正文，每格位移与追平时长直接决定手感，
 * 不同人差异很大，所以给一个入口；legado 没有表冠页，这是本应用独有的。
 */
private fun MainActivity.settingsMain(col: LinearLayout) {
    val card = Widgets.groupCard(this, null) { g ->
        fun e(title: String, page: String) {
            // 单行项压到 HIT（48 dp）：M3 最小热区，再矮点不中。
            g.addView(Widgets.groupItem(ctx = this, title = title, trailing = "chevron") {
                rememberReturn()
                showSettings(page)
            }.apply { minimumHeight = Md3.dp(this@settingsMain, Md3.HIT) })
        }
        e("外观", PAGE_THEME)
        e("高级", PAGE_OTHER)
        e("阅读界面", PAGE_READ)
        e("表冠", PAGE_CROWN)
    }
    // groupCard 自带 16 dp 下边距，这里清掉：192 dp 的行 + 16 dp 就会多出半行滚动。
    (card.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = 0
    col.addView(card)
}

/**
 * 外观（legado `ThemeConfigScreen` 里我们能做到的部分）：亮度与配色两条 + 一行恢复默认。
 *
 * 原来一张卡逐行列三套配色。加第四套（米·夜）后列表会变成四行，其中两组其实是
 * 同一个配色族的两种亮度 —— 用户要的是「先挑明暗，再挑配色」，拆成两条段式条正好：
 * 一行两格，选中态一眼可辨，也省下两行高度。
 *
 * legado 这页还有主题模式（跟随/浅/深）、纯黑、字体、切图标、字体缩放、背景图、模糊：
 * 本应用仅提供共享日夜主题，不提供字体与背景图开关。
 */
private fun MainActivity.settingsTheme(col: LinearLayout) {
    val cur = Md3.palette
    val family = Palette.family(cur.id)
    // 色点要预告「切到这一档会变成什么颜色」，所以按**当前亮度**取对应那套的主色：
    // 日间看到的米色点是浅蓝，夜间看到的是深色版的浅蓝，与真切过去的结果一致。
    val defDot = Palette.find(Palette.combine(cur.dark, Palette.FAMILY_DEFAULT)).primary
    val miuixDot = Palette.find(Palette.combine(cur.dark, Palette.FAMILY_MIUIX)).primary

    col.addView(Widgets.groupCard(this, "亮度") { g ->
        g.addView(Widgets.segBar(this, "", listOf(
            Widgets.SegOption("day", "日"), Widgets.SegOption("night", "夜")),
            selected = if (cur.dark) "night" else "day",
            onPick = {
                // 一次写入一次重画，切主题不允许分两步：
                // 目标亮度与当前配色族一起算进 Palette.combine，再交给 pickTheme 一次写定、
                // 只重画本页一次。以前 pickTheme 内部先重画首屏、这里再重画子页，白建一遍。
                pickTheme(Palette.combine(it == "night", family), PAGE_THEME)
            }))
    })

    col.addView(Widgets.groupCard(this, "配色") { g ->
        g.addView(Widgets.segBar(this, "", listOf(
            Widgets.SegOption(Palette.FAMILY_DEFAULT, "默认", defDot),
            Widgets.SegOption(Palette.FAMILY_MIUIX, "米", miuixDot)),
            selected = family,
            onPick = {
                // 同上：配色族与当前亮度一次算好，只重画一次本页。
                pickTheme(Palette.combine(cur.dark, it), PAGE_THEME)
            }))
    })

    // legado 这页末尾是一行「恢复默认」+ 确认框（`AppAlertDialog`）。
    // 行尾挂 chevron：它点下去会弹框，不挂箭头看着像直接生效的开关。
    col.addView(Widgets.groupCard(this, null) { g ->
        g.addView(Widgets.groupItem(
            ctx = this,
            title = "恢复默认",
            subtitle = "（日间 · 默认）",
            trailing = "chevron",
            onClick = { askResetPalette() },
        ))
    })
}

/** 恢复默认配色前先问一句：换配色会整屏变色，误点一次很难自己找回是哪套。 */
private fun MainActivity.askResetPalette() {
    Widgets.md3Dialog(
        this, "恢复默认配色",
        body = "应用与阅读恢复日间主题，不改阅读排版与书架排序。",
        confirm = "恢复",
        onConfirm = {
            pickTheme(Palette.DEFAULT_ID, PAGE_THEME)
        },
    ).show()
}

/* 配色行首的色点定位函数已随旧配色列表一并移除：
 * 现在色点由 `Widgets.segBar` 的 `SegOption.dot` 直接画在段内文字左边，
 * 不再需要单独造一个 View（少了两次 dp 换算，也不会与段式条圆角打架）。 */

/**
 * 阅读界面：共享主题预览与正文排版。亮度始终交还系统。
 */
private fun MainActivity.settingsRead(col: LinearLayout) {
    // 进页先读盘：ReaderUi 只在打开阅读页时加载过，开机直接进设置会看到默认档。
    loadReaderExtras()

    // 预览块：字号、行距、背景一起看，比三个独立选项直观。
    val preview = Md3.text(this, "示例正文，看看效果。\n第二行。", Md3.FS_READ, Md3.onSurface).apply {
        setPadding(Md3.dp(this@settingsRead, 10), Md3.dp(this@settingsRead, 8),
                   Md3.dp(this@settingsRead, 10), Md3.dp(this@settingsRead, 8))
    }
    col.addView(preview, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = Md3.dp(this@settingsRead, Md3.GAP) })

    fun paintPreview() {
        preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, readerSize)
        preview.setLineSpacing(Md3.dp(this, 1).toFloat(), readerSpacing)
        preview.setTextColor(Md3.onSurface)
        preview.background = Md3.bg(Md3.surface, 10f)
    }

    col.addView(Md3.text(this, "日夜主题与应用外观一致，亮度跟随系统",
        Md3.FS_SM, Md3.onSurfaceVariant))

    col.addView(Widgets.groupCard(this, "正文") { g ->
        // 字号与行距用滑杆：三档粒度太粗，字号差一档就过大（legado 是 −/+ 与滑杆）。
        fun sliderLp() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = Md3.dp(this@settingsRead, Md3.PAD)
            rightMargin = Md3.dp(this@settingsRead, Md3.PAD)
            topMargin = Md3.dp(this@settingsRead, Md3.GAP)
        }

        g.addView(Widgets.sliderRow(this, "字号", 11f, 26f, readerSize, 150,
            { "%.1f sp".format(it) }) { v ->
            readerSize = v
            saveReaderPrefs(readerSize, readerSpacing, readerBackground)
            paintPreview()
        }, sliderLp())

        g.addView(Widgets.sliderRow(this, "行距", 1.1f, 2.4f, readerSpacing, 130,
            { "%.2f 倍".format(it) }) { v ->
            readerSpacing = v
            saveReaderPrefs(readerSize, readerSpacing, readerBackground)
            paintPreview()
        }, sliderLp())

        segmentRow(g, "段距", listOf("无" to 0, "半行" to 1, "一行" to 2).map { it.first to (it.second as Any) },
            get = { ReaderUi.paraLevel as Any },
            set = { ReaderUi.paraLevel = it as Int }) {
            saveReaderExtras()
        }
        segmentRow(g, "首行缩进", listOf("无" to 0, "1 字" to 1, "2 字" to 2).map { it.first to (it.second as Any) },
            get = { ReaderUi.indentLevel as Any },
            set = { ReaderUi.indentLevel = it as Int }) {
            saveReaderExtras()
        }
    })

    paintPreview()
}

/**
 * 表冠灵敏度：刚改完平滑滚动后这两个常数直接决定手感，不同人差异很大。
 *
 * legado 没有表冠页（手机不吃表冠事件），所以这一页只是沿用原来的滑杆，
 * 不套 legado 的列表形态：两个滑杆本身已经是最短路径。
 */
private fun MainActivity.settingsCrown(col: LinearLayout) {
    // 0—10 px，按 1 px 取档。0 关闭表冠滚动，触摸仍可滚动。
    col.addView(Widgets.sliderRow(this, "每格位移", 0f, 10f, crownStep,
        10, { "%d px".format(it.toInt()) }) { v ->
        crownStep = v
        saveCrownPrefs()
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = Md3.dp(this@settingsCrown, Md3.GAP) })

    col.addView(Widgets.sliderRow(this, "跟手时长", 0f, 200f, crownGlide.toFloat(), 200,
        { if (it < 1f) "立即" else "%d ms".format(it.toInt()) }) { v ->
        crownGlide = v.toInt()
        saveCrownPrefs()
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = Md3.dp(this@settingsCrown, Md3.GAP) })

    // body 早在进页时就建好了，改参数后要下次进页面才生效。
    col.addView(Md3.text(this, "表冠参数在下次进入页面时生效", Md3.FS_SM, Md3.onSurfaceVariant).apply {
        setPadding(0, Md3.dp(this@settingsCrown, 4), 0, 0)
    })
}

/**
 * 高级：导出、书源、开发者三组，组与行都照 legado `OtherConfigScreen`。
 *
 * legado 的分组是「（无标题：语言 / 更新 / 启动检查更新）/ 主界面 / 阅读 / 高级」，
 * 每项都带 description。我们没有语言、更新、主界面与阅读类的全局开关，
 * 留下三组，每行补一句 description —— 一句话说清点了会怎样，比只写两字强。
 *
 * 原来第四组「关于」只剩一个只读的版本行，已整组删掉：版本信息归独立「关于」页
 * （`ui/my/AboutScreen.kt`，从「我的」进）。legado 的 OtherConfigScreen 也没有
 * 关于分组 —— 它只在 `ui/main/my/MyScreen` 里有一个 `about` 入口。
 */
private fun MainActivity.settingsOther(col: LinearLayout) {
    col.addView(Widgets.groupCard(this, "导出") { g ->
        // 导出规则是一句话说明，不占一行按钮：真导出入口在作业菜单里。
        g.addView(Md3.text(this, "文件名：书名 - 作者.txt（重名自动加 (2)）\n编码：UTF-8 带 BOM",
            Md3.FS_SM, Md3.onSurfaceVariant).apply {
            setPadding(Md3.dp(this@settingsOther, Md3.PAD), Md3.dp(this@settingsOther, Md3.GAP / 2),
                       Md3.dp(this@settingsOther, Md3.PAD), Md3.dp(this@settingsOther, Md3.GAP / 2))
        })
    })

    col.addView(Widgets.groupCard(this, "书源") { g ->
        g.addView(Widgets.listItem(this, "管理书源", "启用/禁用、分组与导入", twoLine = true,
            trailing = "chevron") {
            rememberReturn(); showSources()
        })
    })

    // 开发者模式：默认关闭，关着时只有普通 logcat，不占内存不写盘。
    col.addView(Widgets.groupCard(this, "开发者") { g ->
        g.addView(Widgets.listItem(this, "开发者模式", devLogHint(), twoLine = true,
            trailing = if (devLogOn()) "开" else "关") {
            toggleDevLog()
            // toggleDevLog 自己会重画成设置首屏（日志页那边也共用一个入口）。
            // 这里再回到「高级」：否则点一下开关就被弹回首屏，开关像是没了反馈。
            showSettings(PAGE_OTHER)
        })
        if (devLogOn()) {
            g.addView(Widgets.listItem(this, "导出日志", "写入文件后系统分享", twoLine = true) { exportDevLog() })
            g.addView(Widgets.listItem(this, "清空日志", "删掉 dev.log，阅读记录不受影响", twoLine = true) {
                clearDevLog()
            })
        }
    })
}

/** 清空开发者日志。抽成一个函数只为让上面那行的回调短一点。 */
private fun MainActivity.clearDevLog() {
    DevLog.clear()
    toast("日志已清空")
}

/**
 * 一行「标签 + 若干段」。
 *
 * 段式比下拉省一次点击，比滑杆省一行高度；手表屏一屏只放得下五六行，
 * 这两种控件都太贵。改值后只重画选中态，不重建整页 —— 重建会丢滚动位置。
 *
 * @param onChanged 值已由 [set] 写入后的收尾（落盘、重画预览）
 */
private fun MainActivity.segmentRow(
    col: LinearLayout,
    label: String,
    options: List<Pair<String, Any>>,
    get: () -> Any,
    set: (Any) -> Unit,
    onChanged: () -> Unit = {},
) {
    val row = Md3.row(this)
    row.addView(Md3.text(this, label, Md3.FS_SM, Md3.onSurfaceVariant),
        LinearLayout.LayoutParams(
            Md3.dp(this, if (label.length >= 4) LABEL_W_LONG else LABEL_W),
            ViewGroup.LayoutParams.WRAP_CONTENT))

    val chips = mutableListOf<Pair<TextView, Any>>()

    fun paint() {
        val cur = get()
        chips.forEach { (v, value) ->
            val sel = value == cur
            // 选中态与全局一致：浅紫填充 + 深字（发现页、按钮都用这套）。
            v.setTypeface(null, if (sel) android.graphics.Typeface.BOLD
                                    else android.graphics.Typeface.NORMAL)
            v.setTextColor(if (sel) Md3.onPrimary else Md3.onSurfaceVariant)
            v.background = Md3.ripple(this,
                if (sel) Md3.primary else Color.TRANSPARENT,
                Md3.dp(this, 13).toFloat(),
                if (sel) null else Md3.outlineVariant)
        }
    }

    options.forEachIndexed { i, opt ->
        val chip = Md3.text(this, opt.first, Md3.FS_SM, Md3.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                set(opt.second)
                paint()
                onChanged()
            }
        }
        chips.add(chip to opt.second)
        row.addView(chip, LinearLayout.LayoutParams(0, Md3.dp(this, CHIP_H), 1f).apply {
            leftMargin = if (i == 0) 0 else Md3.dp(this@segmentRow, 4)
        })
    }
    paint()
    col.addView(row, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        // 卡片内左右各留 PAD，与 listItem 的文字左缘对齐。
        leftMargin = Md3.dp(this@segmentRow, Md3.PAD)
        rightMargin = Md3.dp(this@segmentRow, Md3.PAD)
        topMargin = Md3.dp(this@segmentRow, Md3.GAP / 2)
        bottomMargin = Md3.dp(this@segmentRow, Md3.GAP / 2)
    })
}

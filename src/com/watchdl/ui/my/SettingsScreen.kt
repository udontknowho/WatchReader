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
 * legado 的设置是一张导航列表 + 十来个子页（主题 / 阅读 / 其他 / 封面 / 缓存 /
 * 备份 / 隐私 / 实验室）。手表只留我们真有的四页：主题、其他、阅读，
 * 外加本应用独有的表冠。每页的项都按 legado 的分组语义摆，尺寸按 189 × 248 dp 压。
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
 * 阅读设置**改完立即落盘**（不用点「确定」），预览实时跟着变。
 *
 * [page] 取 [PAGE_MAIN] / [PAGE_THEME] / [PAGE_READ] / [PAGE_CROWN] / [PAGE_OTHER]。
 */
internal fun MainActivity.showSettings(page: String = PAGE_MAIN) {
    setScreen(Screen.Settings(page))
    root.removeAllViews()
    val title = when (page) {
        PAGE_THEME -> "主题设置"
        PAGE_READ -> "阅读设置"
        PAGE_CROWN -> "表冠"
        PAGE_OTHER -> "其他设置"
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
 * 顺序照 legado 的语义：主题 → 其他 → 阅读。表冠放末位 ——
 * 本机靠旋转表冠滚动列表与正文，每格位移与追平时长直接决定手感，
 * 不同人差异很大，所以给一个入口；legado 没有表冠页，这是本应用独有的。
 */
private fun MainActivity.settingsMain(col: LinearLayout) {
    val card = Widgets.groupCard(this, null) { g ->
        fun e(title: String, page: String) {
            // 单行项压到 HIT（48 dp）：M3 最小热区，再矮点不中。
            g.addView(Widgets.groupItem(ctx = this, title = title) {
                rememberReturn()
                showSettings(page)
            }.apply { minimumHeight = Md3.dp(this@settingsMain, Md3.HIT) })
        }
        e("主题设置", PAGE_THEME)
        e("其他设置", PAGE_OTHER)
        e("阅读设置", PAGE_READ)
        e("表冠", PAGE_CROWN)
    }
    // groupCard 自带 16 dp 下边距，这里清掉：192 dp 的行 + 16 dp 就会多出半行滚动。
    (card.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = 0
    col.addView(card)
}

/**
 * 主题设置：一个「配色」组，每套配色一行，当前那行右侧写「当前」。
 *
 * 原来是 2 × 3 芯片网格。legado 主题页的配色是列表行（`SettingItem` 带 option
 * 显示当前值），行里能直接写名字，芯片只有 39 dp 宽、两个字要缩到 12 sp 才塞得下。
 * 六行 × 56 dp 超出一屏，靠 body 滚动：行高不再压，56 dp 是 M3 单行标准，
 * 压了热区不够点。
 */
private fun MainActivity.settingsTheme(col: LinearLayout) {
    col.addView(Widgets.groupCard(this, "配色") { g ->
        Palette.all.forEach { p ->
            val on = p.id == Md3.palette.id
            g.addView(Widgets.listItem(
                ctx = this,
                title = p.name,
                // legado 的 option 用主色显示当前值；这里就是「当前」两个字。
                trailing = if (on) "当前" else null,
                onClick = {
                    // pickTheme 存盘后会把设置首屏重画（配色是单例，只影响新建视图），
                    // 这里紧接着回到配色列表：换一套还能接着比下一套。
                    pickTheme(p.id)
                    showSettings(PAGE_THEME)
                },
            ))
        }
    })
}

/**
 * 阅读设置：预览块 + 「屏幕设置」组 + 「正文」组。
 *
 * 分组照 legado `ReadConfigScreen`：屏幕设置（方向/状态栏/护眼/亮度）、翻页控制、
 * 其他。我们没有方向和翻页方式（手表只有表冠与触摸滚动），留下的项按语义归两组：
 * 背景、亮度、护眼是「屏幕」，字号、行距、段距、首行缩进是「正文」。
 * 亮度、护眼、段距、缩进本来就有（阅读页菜单里能改），这里只是把它们露出来。
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
        preview.setTextColor(if (Color.red(readerBackground) > 100) Color.rgb(28, 27, 31) else Md3.onSurface)
        preview.background = Md3.bg(readerBackground, Md3.dp(this, 10).toFloat())
    }

    val bgs = listOf(
        "深" to Color.rgb(20, 18, 24),
        "浅" to Color.WHITE,
        "暖" to Color.rgb(255, 248, 230),
    )

    col.addView(Widgets.groupCard(this, "屏幕设置") { g ->
        segmentRow(g, "背景", bgs.map { it.first to (it.second as Any) },
            get = { readerBackground as Any },
            set = { readerBackground = it as Int }) {
            saveReaderPrefs(readerSize, readerSpacing, readerBackground)
            paintPreview()
        }
        segmentRow(g, "亮度", listOf("暗" to 0, "中" to 1, "亮" to 2).map { it.first to (it.second as Any) },
            get = { ReaderUi.brightTier as Any },
            set = { ReaderUi.brightTier = it as Int }) {
            // 只落盘，不在这里改窗口亮度：applyBright 压的是整个窗口，
            // 而离开阅读页本来就没复位（已知遗留）。从设置里改会连书架一起变暗。
            saveReaderExtras()
        }
        segmentRow(g, "护眼", listOf("关" to false, "开" to true).map { it.first to (it.second as Any) },
            get = { ReaderUi.eyeCare as Any },
            set = { ReaderUi.eyeCare = it as Boolean }) {
            saveReaderExtras()
        }
    })

    col.addView(Widgets.groupCard(this, "正文") { g ->
        // 字号与行距用滑杆：三档粒度太粗，字号差一档就过大（legado 是 −/+ 与滑杆）。
        fun sliderLp() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = Md3.dp(this@settingsRead, Md3.PAD)
            rightMargin = Md3.dp(this@settingsRead, Md3.PAD)
            topMargin = Md3.dp(this@settingsRead, Md3.GAP)
        }

        g.addView(Widgets.sliderRow(this, "字号", 11f, 26f, readerSize, 0,
            { "%.1f sp".format(it) }) { v ->
            readerSize = v
            saveReaderPrefs(readerSize, readerSpacing, readerBackground)
            paintPreview()
        }, sliderLp())

        g.addView(Widgets.sliderRow(this, "行距", 1.1f, 2.4f, readerSpacing, 0,
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
        segmentRow(g, "首行缩进", listOf("关" to false, "开" to true).map { it.first to (it.second as Any) },
            get = { ReaderUi.indentOn as Any },
            set = { ReaderUi.indentOn = it as Boolean }) {
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
    col.addView(Widgets.sliderRow(this, "每格位移", 20f, 200f, crownStep, 0,
        { "%d px".format(it.toInt()) }) { v ->
        crownStep = v
        saveCrownPrefs()
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = Md3.dp(this@settingsCrown, Md3.GAP) })

    col.addView(Widgets.sliderRow(this, "跟手时长", 0f, 200f, crownGlide.toFloat(), 0,
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
 * 其他设置：导出、书源、关于、开发者四组，组与行都照 legado `OtherConfigScreen`。
 *
 * legado 的分组是「语言 / 更新 / 主界面 / 阅读 / 其他」，每项都带 description。
 * 我们没有语言、更新、主界面，留下四组，每行补一句 description ——
 * 一句话说清点了会发生什么，比只写「版本」两字强。
 */
private fun MainActivity.settingsOther(col: LinearLayout) {
    col.addView(Widgets.groupCard(this, "导出") { g ->
        // 导出规则是一句话说明，不占一行按钮：真导出入口在书单与作业菜单里。
        g.addView(Md3.text(this, "文件名：书名 - 作者.txt（重名自动加 (2)）\n编码：UTF-8 带 BOM",
            Md3.FS_SM, Md3.onSurfaceVariant).apply {
            setPadding(Md3.dp(this@settingsOther, Md3.PAD), Md3.dp(this@settingsOther, Md3.GAP / 2),
                       Md3.dp(this@settingsOther, Md3.PAD), Md3.dp(this@settingsOther, Md3.GAP / 2))
        })
    })

    col.addView(Widgets.groupCard(this, "书源") { g ->
        g.addView(Widgets.listItem(this, "管理书源", "启用/禁用、分组与导入", twoLine = true) {
            rememberReturn(); showSources()
        })
    })

    col.addView(Widgets.groupCard(this, "关于") { g ->
        // 版本号从清单读，不在这里再写一份常量（会与 build.sh 不同步）。
        // 独立「关于」页是下一步的事，本轮只把版本挪到副标题位。
        g.addView(Widgets.listItem(this, "版本", appVersion(), twoLine = true, highlight = false) { })
    })

    // 开发者模式：默认关闭，关着时只有普通 logcat，不占内存不写盘。
    col.addView(Widgets.groupCard(this, "开发者") { g ->
        g.addView(Widgets.listItem(this, "开发者模式", devLogHint(), twoLine = true,
            trailing = if (devLogOn()) "开" else "关") { toggleDevLog() })
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

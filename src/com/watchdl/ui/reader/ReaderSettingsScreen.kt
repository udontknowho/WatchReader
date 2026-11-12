package com.watchdl.ui.reader

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.BookInfo
import com.watchdl.core.BookSource
import com.watchdl.core.ReaderStyle
import com.watchdl.core.Screen
import com.watchdl.core.readingId
import com.watchdl.ui.my.showReplace
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/** 预览块用的示范文字：两行，够看字号、行距、段距，又不把卡片撑高。 */
private const val PREVIEW_TEXT = "夜里风声很轻。\n他翻过一页。"

/**
 * 阅读设置页。
 *
 * **不再是弹窗。** 189×248 dp 的对话框减去内边距、标题与按钮行只剩 120 dp，
 * 排版十来项要在一屏两行多的窗口里滚，分段一多就分不清哪儿是哪儿。
 * 改成独立一屏，高度交给 [Widgets.scrollBody]：滚动、滚轮步进、边缘渐隐都与书架、目录一致。
 *
 * 分组口径照 legado（`ReadStyleSheet` 的全局主题页 + `TypographyPage` 的信息页）：
 *
 * | 段 | 内容 | legado |
 * |---|---|---|
 * | 外观 | 日 / 夜、亮度 | `GlobalThemePage` |
 * | 正文 | 字号、行距、段距、缩进、字距、字重、斜体、两端 | `TypographyBodyTab` |
 * | 标题 | 位置、字号、间距、字重 | `TypographyTitleTab` |
 * | 边距 | 窄 / 标准 / 宽 | `TypographyTabs` 的边距页 |
 * | 翻页 | 滚一屏 / 覆盖 | `page_anim` |
 * | 菜单 | 工具格按钮、书签徽标 | `SystemMenuPage` |
 * | 其他 | 常亮、自动翻页、恢复默认 | `MoreConfigSheet` |
 *
 * 已按用户要求砍掉：护眼（开关 / 强度 / 按钟点自动）、页眉页脚、五档底色。
 * 底色只留日、夜两档：日间用番茄那套（`BG_FANQIE_DAY`，棕褐字），夜间 `BG_FANQIE_NIGHT`。
 * 两档的字色都由 `ReaderStyle.inkOverride` 显式给出，不靠深浅反推。
 *
 * 每一项都即时生效：改完写盘 + 当场重排正文（[restyleReader]），回阅读页看到的就是新样子。
 * 只有底色要重建整屏 —— 顶栏、按钮、进度条的前景色全由底色推。
 */
internal fun MainActivity.showReaderSettings(
    info: BookInfo, index: Int, text: String, src: BookSource?,
) {
    // 设置离开正文前固定当前位置，返回和切主题都恢复同一屏。
    ReaderUi.restoreKey = "${readingId(info)}#$index"
    ReaderUi.restoreAt = readerBody?.scrollY ?: readerOffset
    setScreen(Screen.ReaderSettings(info, index, text, src))
    root.removeAllViews()

    root.addView(Widgets.topBar(this, "阅读设置",
        onBack = { showReader(info, index, text, src) }))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    root.addView(body)

    /** 预览块：套当前正文样式的一小段示范文字，滑杆一动就重排。 */
    var previewView: android.widget.TextView? = null

    /** 落盘 + 当场重排正文。不重建整屏，回来时滚动位置还在原处。 */
    fun applyNow() {
        saveReaderPrefs(readerSize, readerSpacing, readerBackground)
        saveReaderExtras()
        applyBright()
        restyleReader(text)
        // 滑杆不重建整屏，预览块得自己跟着重排。
        previewView?.let { styleBody(it, PREVIEW_TEXT) }
    }

    /** 整页重画：芯片选中态、恢复默认之后都要回头重排自己。 */
    fun paint() {
        col.removeAllViews()
        previewView = null

        /** 卡内左右内边距，与 listItem 一致；组标题才会和卡内容落在同一条竖线上。 */
        val ip = Md3.dp(this, Md3.PAD)

        /** 一张分组卡：段标题在卡上方，项在卡里，卡与卡之间的留白由 groupCard 自带。 */
        fun card(title: String, build: (LinearLayout) -> Unit) {
            col.addView(Widgets.groupCard(this, title) { g ->
                g.setPadding(0, Md3.dp(this, 4), 0, Md3.dp(this, 4))
                build(g)
            })
        }

        /**
         * 档位行：标签 + 若干等宽档。
         *
         * 选中态靠比较取值判：连续量用滑杆，只有离散量才走这里（与 legado 的
         * `TinySliderSettingItem` 与下拉型设置项同一分工）。
         */
        fun chips(parent: LinearLayout, label: String, options: List<Pair<String, Any>>,
                  get: () -> Any, set: (Any) -> Unit) {
            val p = Md3.dp(this, 3)
            val row = Md3.row(this).apply { setPadding(ip, p, ip, p) }
            // 标签列收窄到 26 dp：卡里比整页少两档边距，三档芯片才排得下。
            row.addView(Md3.text(this, label, Md3.FS_SM, Md3.onSurfaceVariant),
                LinearLayout.LayoutParams(Md3.dp(this, 26), ViewGroup.LayoutParams.WRAP_CONTENT))
            val cur = get()
            options.forEachIndexed { i, opt ->
                val sel = opt.second == cur
                val chip = Md3.text(this, opt.first, Md3.FS_SM,
                    if (sel) Md3.onPrimary else Md3.onSurfaceVariant).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setTypeface(null, if (sel) android.graphics.Typeface.BOLD
                        else android.graphics.Typeface.NORMAL)
                    background = Md3.ripple(this@showReaderSettings,
                        if (sel) Md3.primary else Color.TRANSPARENT,
                        Md3.dp(this@showReaderSettings, 13).toFloat(),
                        if (sel) null else Md3.outlineVariant)
                    setOnClickListener {
                        set(opt.second)
                        applyNow()
                        paint()
                    }
                }
                row.addView(chip, LinearLayout.LayoutParams(0, Md3.dp(this, 26), 1f).apply {
                    leftMargin = if (i == 0) 0 else Md3.dp(this@showReaderSettings, 4)
                })
            }
            parent.addView(row)
        }

        fun slider(parent: LinearLayout, label: String, min: Float, max: Float, steps: Int,
                   value: Float, fmt: (Float) -> String, set: (Float) -> Unit) {
            val row = Widgets.sliderRow(this, label, min, max, value, steps, fmt) { v ->
                set(v)
                applyNow()
            }.apply {
                setPadding(ip, Md3.dp(this@showReaderSettings, 2), ip,
                    Md3.dp(this@showReaderSettings, 2))
            }
            parent.addView(row)
        }

        // ── 外观 ───────────────────────────────────────────
        card("外观") { g ->
            chips(g, "主题", listOf("日" to false as Any, "夜" to true),
                { Color.red(Md3.surface) <= 100 }) { night ->
                // 底色一换，整套前景色都要跟着换，只能重建整屏；这也是本页唯一离开此屏的项。
                val bg = if (night as Boolean) ReaderStyle.BG_FANQIE_NIGHT else ReaderStyle.BG_FANQIE_DAY
                saveReaderPrefs(readerSize, readerSpacing, bg)
                saveReaderExtras()
                showReader(info, index, text, src)
            }

        }

        // ── 正文 ───────────────────────────────────────────
        card("正文") { g ->
            // 预览块放段首：改字号、行距、字重不用退回阅读页才知道多大。
            val pv = Md3.text(this, PREVIEW_TEXT, Md3.FS_SM, Md3.onSurfaceVariant).apply {
                setPadding(Md3.dp(this@showReaderSettings, 8), Md3.dp(this@showReaderSettings, 7),
                    Md3.dp(this@showReaderSettings, 8), Md3.dp(this@showReaderSettings, 7))
                background = Md3.bg(readerBackground, Md3.R_ITEM.toFloat())
            }
            styleBody(pv, PREVIEW_TEXT)
            g.addView(pv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(ip, Md3.dp(this@showReaderSettings, 4), ip,
                    Md3.dp(this@showReaderSettings, 2))
            })
            previewView = pv

            slider(g, "字号", 10f, 24f, 14, readerSize, { "${it.toInt()}" }) { readerSize = it }
            slider(g, "行距", 1.2f, 2.4f, 12, readerSpacing, { String.format("%.1f", it) }) {
                readerSpacing = it
            }
            slider(g, "段距", 0f, 2f, 2, ReaderUi.paraLevel.toFloat(),
                { listOf("无", "半行", "一行")[it.toInt().coerceIn(0, 2)] }) {
                ReaderUi.paraLevel = it.toInt()
            }
            slider(g, "缩进", 0f, 2f, 2, ReaderUi.indentLevel.toFloat(),
                { listOf("无", "1 字", "2 字")[it.toInt().coerceIn(0, 2)] }) {
                ReaderUi.indentLevel = it.toInt()
            }
            slider(g, "字距", -0.05f, 0.10f, 15, ReaderUi.letterEm, { String.format("%.2f", it) }) {
                ReaderUi.letterEm = it
            }
            chips(g, "字重", listOf("常规" to 0 as Any, "中粗" to 1, "粗" to 2),
                { ReaderUi.weightLevel as Any }) { ReaderUi.weightLevel = it as Int }
            chips(g, "斜体", listOf("关" to false as Any, "开" to true),
                { ReaderUi.italic as Any }) { ReaderUi.italic = it as Boolean }
            chips(g, "两端", listOf("关" to false as Any, "开" to true),
                { ReaderUi.justify as Any }) { ReaderUi.justify = it as Boolean }
        }

        // ── 标题 ───────────────────────────────────────────
        card("标题") { g ->
            chips(g, "位置", listOf("居左" to 0 as Any, "居中" to 1, "隐藏" to 2),
                { ReaderUi.titleMode as Any }) { ReaderUi.titleMode = it as Int }
            slider(g, "字号", 0f, 8f, 4, ReaderUi.titleAdd.toFloat(), { "+${it.toInt()}" }) {
                ReaderUi.titleAdd = it.toInt()
            }
            slider(g, "间距", 0f, 12f, 3, (ReaderUi.titleGap * 4).toFloat(), { "${it.toInt()} dp" }) {
                ReaderUi.titleGap = (it / 4f).toInt()
            }
            chips(g, "字重", listOf("常规" to false as Any, "加粗" to true),
                { ReaderUi.titleBold as Any }) { ReaderUi.titleBold = it as Boolean }
        }

        // ── 边距 ───────────────────────────────────────────
        card("边距") { g ->
            slider(g, "边距", 0f, 2f, 2, ReaderUi.marginLevel.toFloat(),
                { listOf("窄", "标准", "宽")[it.toInt().coerceIn(0, 2)] }) {
                ReaderUi.marginLevel = it.toInt()
            }
        }

        // ── 翻页 ───────────────────────────────────────────
        card("翻页") { g ->
            chips(g, "动画", listOf("滚一屏" to 0 as Any, "覆盖" to 1),
                { ReaderUi.pageAnim as Any }) { ReaderUi.pageAnim = it as Int }
            // 一屏整屏滚完的时长；不满一屏按距离比例缩，所以它也是上限。
            slider(g, "时长", ReaderStyle.PAGE_BASE_MIN_MS.toFloat(), ReaderStyle.PAGE_BASE_MAX_MS.toFloat(),
                ((ReaderStyle.PAGE_BASE_MAX_MS - ReaderStyle.PAGE_BASE_MIN_MS) /
                    ReaderStyle.PAGE_BASE_STEP_MS).toInt(),
                ReaderUi.pageBaseMs.toFloat(), { "${it.toInt()} ms" }) {
                ReaderUi.pageBaseMs = ReaderStyle.pageBaseClamp(it.toLong())
            }
        }

        // ── 菜单 ───────────────────────────────────────────
        card("菜单") { g ->
            g.addView(
                Widgets.groupItem(this, "替换净化", "管理正文替换规则",
                    onClick = { rememberReturn(); showReplace() }),
                Md3.lpDp(this, null, Md3.ITEM_TALL),
            )
            g.addView(
                Widgets.groupItem(this, "工具格按钮",
                    ReaderUi.tools.joinToString(" · ") { readerToolTitle(it) },
                    onClick = { showToolsDialog(info, index, text, src) }),
                Md3.lpDp(this, null, Md3.ITEM_TALL),
            )
            chips(g, "徽标", listOf("隐藏" to false as Any, "显示" to true),
                { ReaderUi.badgeOn as Any }) { ReaderUi.badgeOn = it as Boolean }
        }

        // ── 其他 ───────────────────────────────────────────
        card("其他") { g ->
            chips(g, "常亮", listOf("关" to false as Any, "开" to true),
                { ReaderUi.keepOn as Any }) { ReaderUi.keepOn = it as Boolean }
            slider(g, "自动翻页", 1f, 60f, 59, ReaderUi.autoPageSec.toFloat(), { "${it.toInt()} s" }) {
                ReaderUi.autoPageSec = it.toInt().coerceIn(1, 60)
            }
            g.addView(Widgets.button(this, "恢复默认排版", "outline") {
                Widgets.md3Dialog(this, "恢复默认排版",
                    body = "重置字号、间距、标题与页边距。保留书签、进度及常亮设置。",
                    confirm = "恢复", onConfirm = {
                readerSize = Md3.FS_READ
                readerSpacing = 1.75f
                ReaderUi.paraLevel = 0
                ReaderUi.indentLevel = 0
                ReaderUi.letterEm = 0f
                ReaderUi.weightLevel = 0
                ReaderUi.italic = false
                ReaderUi.justify = true
                ReaderUi.titleMode = 0
                ReaderUi.titleAdd = 2
                ReaderUi.titleBold = true
                ReaderUi.titleGap = 1
                ReaderUi.marginLevel = 1
                ReaderUi.pageBaseMs = ReaderStyle.PAGE_BASE_MS
                applyNow()
                paint()
                toast("排版已恢复默认")
                }).show()
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, Md3.BTN)).apply {
                setMargins(ip, Md3.dp(this@showReaderSettings, 4), ip,
                    Md3.dp(this@showReaderSettings, 4))
            })
        }
    }

    paint()
}

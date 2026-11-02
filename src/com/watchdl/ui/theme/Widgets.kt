package com.watchdl.ui.theme

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * MD3 组件。全部程序化构造 —— 项目不用 XML 资源，构建链路是 kotlinc + aapt2 手工流水线。
 *
 * 每个函数的尺寸都取自 [Md3]，对应 `ui/md3-final.html` 的定稿。
 */
object Widgets {

    /** ScrollView 能滚到的最大 Y。内容比视口短时为 0。 */
    private fun maxScrollOf(sv: ScrollView): Int {
        val child = sv.getChildAt(0) ?: return 0
        return (child.height - sv.height + sv.paddingTop + sv.paddingBottom).coerceAtLeast(0)
    }


    /** 应用栏。36dp 高，左返回 / 标题 / 右侧动作。传 [subtitle] 时标题行分两行。 */
    fun topBar(
        ctx: Context,
        title: CharSequence,
        onBack: (() -> Unit)? = null,
        backGlyph: String = "back",
        trailing: List<Pair<String, () -> Unit>> = emptyList(),
        subtitle: CharSequence? = null,
    ): LinearLayout {
        val bar = Md3.row(ctx)
        bar.setPadding(Md3.dp(ctx, Md3.PAD / 2), 0, Md3.dp(ctx, Md3.PAD / 2), 0)
        if (onBack != null || backGlyph.isNotEmpty()) {
            val ib = Md3.iconButton(ctx, backGlyph, onBack)
            bar.addView(ib, LinearLayout.LayoutParams(Md3.dp(ctx, Md3.TAP), Md3.dp(ctx, Md3.TAP)))
        }
        val t = Md3.ellipsize(Md3.text(ctx, title, Md3.FS_LG, Md3.onSurface, bold = true))
        t.setPadding(Md3.dp(ctx, Md3.PAD * 0.6), 0, 0, 0)
        if (subtitle.isNullOrBlank()) {
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            // 两行标题：legado 顶栏是「书名」加「章名 · 书源名」。189 dp 一行装不下。
            val stack = Md3.column(ctx)
            stack.addView(t, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            stack.addView(
                Md3.ellipsize(Md3.text(ctx, subtitle, Md3.FS_SM, Md3.onSurfaceVariant))
                    .apply { alpha = 0.85f },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            bar.addView(stack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        for ((glyph, act) in trailing) {
            bar.addView(Md3.iconButton(ctx, glyph, act),
                LinearLayout.LayoutParams(Md3.dp(ctx, Md3.TAP), Md3.dp(ctx, Md3.TAP)))
        }
        bar.layoutParams = Md3.lpDp(ctx, null, Md3.TOP_BAR)
        return bar
    }
    /** 搜索框。34dp 高，圆角胶囊。传入 onTap 可点开输入。 */
    fun searchBar(ctx: Context, hint: CharSequence, onTap: (() -> Unit)? = null): LinearLayout {
        val bar = Md3.row(ctx)
        bar.background = Md3.ripple(ctx, Md3.surface4, Md3.SEARCH / 2f)
        bar.setPadding(Md3.dp(ctx, 12), 0, Md3.dp(ctx, 12), 0)
        // 不再放彩色 🔍 emoji：它与全局单色字形体系冲突，提示文字本身已说明用途。
        val ph = Md3.ellipsize(Md3.text(ctx, hint, Md3.FS_MD, Md3.onSurfaceVariant))
        ph.alpha = 0.8f
        bar.addView(ph, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onTap != null) {
            bar.isClickable = true
            bar.setOnClickListener { onTap() }
            bar.contentDescription = "搜索框，点按输入书名"
        }
        bar.layoutParams = Md3.lpDp(ctx, null, Md3.SEARCH).apply { bottomMargin = Md3.dp(ctx, Md3.GAP) }
        return bar
    }

    /**
     * 一行「标签 + 当前值 + 滑杆」。
     *
     * 档位式选项（小/中/大）粒度太粗，字号差一档就过大。滑杆给连续值。
     * [steps] 为 0 表示无级。
     */
    fun sliderRow(
        ctx: Context,
        label: String,
        min: Float,
        max: Float,
        value: Float,
        steps: Int = 0,
        fmt: (Float) -> String,
        onChange: (Float) -> Unit,
    ): LinearLayout = sliderRow(ctx, label, min, max, value, steps, fmt, onChange, null)

    fun sliderRow(
        ctx: Context,
        label: String,
        min: Float,
        max: Float,
        value: Float,
        steps: Int,
        fmt: (Float) -> String,
        onChange: (Float) -> Unit,
        lp: LinearLayout.LayoutParams?,
    ): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(Md3.text(ctx, label, Md3.FS_SM, Md3.onSurfaceVariant),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valText = Md3.text(ctx, fmt(value), Md3.FS_SM, Md3.primary)
        head.addView(valText)
        row.addView(head)

        val bar = android.widget.SeekBar(ctx).apply {
            this.max = ((max - min) * 100).toInt()
            progress = ((value - min) * 100).toInt()
            if (steps > 0) {
                // SeekBar 的 keyProgressIncrement 只在触屏上按步进吸附；
                // 这里靠 onChange 自己取整，避免两套吸附逻辑打架。
                keyProgressIncrement = (((max - min) * 100 / steps).toInt()).coerceAtLeast(1)
            }
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val raw = min + p / 100f
                    val v = if (steps > 0) {
                        // 按步取整，保证落到合法档位
                        val q = Math.round((raw - min) / ((max - min) / steps))
                        min + q * ((max - min) / steps)
                    } else raw
                    valText.text = fmt(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        if (lp != null) row.layoutParams = lp
        return row
    }

    /** 分组标题。 */
    fun sectionLabel(ctx: Context, label: CharSequence): TextView =
        Md3.text(ctx, label, Md3.FS_MD, Md3.primary, bold = true).apply {
            letterSpacing = 0.05f
            // 缩进与卡片内容左缘对齐（legado 分组标题也是 16dp），原来 2dp 会顶到屏边。
            setPadding(Md3.dp(ctx, Md3.PAD), Md3.dp(ctx, 5), Md3.dp(ctx, Md3.PAD), Md3.dp(ctx, 3))
        }

    /**
     * 分组卡：照 legado `SplicedColumnGroup` —— 圆角容器包住整组条目，
     * 组标题在卡上方，卡与卡之间留白。组内条目一律 [groupItem]。
     */
    fun groupCard(ctx: Context, title: CharSequence?, build: (LinearLayout) -> Unit): LinearLayout {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (title != null) {
            wrap.addView(sectionLabel(ctx, title).apply {
                setPadding(Md3.dp(ctx, Md3.PAD), Md3.dp(ctx, Md3.GAP),
                    Md3.dp(ctx, Md3.PAD), Md3.dp(ctx, Md3.GAP / 2))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Md3.bg(Md3.surface2, Md3.R_CARD.toFloat())
        }
        build(card)
        wrap.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Md3.dp(ctx, Md3.GAP * 2) }
        return wrap
    }

    /** 组内条目：保留 [listItem] 自己算好的高度，只换背景（卡片底不用再画一层）。 */
    fun groupItem(
        ctx: Context,
        title: CharSequence,
        subtitle: CharSequence? = null,
        glyph: String? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = listItem(
        ctx = ctx, title = title, subtitle = subtitle, twoLine = subtitle != null,
        leading = glyph?.let { Icons.view(ctx, it, Md3.onSurfaceVariant, Md3.ICON_LEAD) },
        onClick = onClick,
    ).apply {
        background = Md3.ripple(ctx, Color.TRANSPARENT, Md3.R_ITEM.toFloat())
    }

    /** 按钮。转发到 [Md3.button]，让调用处只认 Widgets 一个入口。 */
    fun button(ctx: Context, label: CharSequence, variant: String = "filled", onClick: (() -> Unit)? = null): TextView =
        Md3.button(ctx, label, variant, onClick)

    /** 应用栏图标按钮。转发到 [Md3.iconButton]，让调用处只认 Widgets 一个入口。 */
    fun iconButton(ctx: Context, glyph: String, onClick: (() -> Unit)? = null): View =
        Md3.iconButton(ctx, glyph, onClick)

    /** 把整棵子树里的文字改成指定颜色。阅读器切浅色底时，顶栏字形要跟着变深。 */
    fun tintSubtree(v: View, color: Int) {
        when (v) {
            is TextView -> v.setTextColor(color)
            // 矢量图标不是 TextView，漏了它阅读页浅色底下图标还是浅色，看不见。
            is IconView -> v.tint = color
            is ViewGroup -> for (i in 0 until v.childCount) tintSubtree(v.getChildAt(i), color)
        }
    }

    /**
     * 紧凑菜单。代替 `setItems` 版的 AlertDialog：后者行高由系统定（约 60px），
     * 三项就占满一屏，字号也与全局不一致。选完自动关，点外部也关。
     */
    fun menuDialog(
        ctx: Context,
        title: CharSequence?,
        items: List<Pair<CharSequence, () -> Unit>>,
    ): android.app.AlertDialog {
        // 容器与对话框同一套：surface-container-high + 大圆角 + 一层内边距。
        // 菜单在 M3 里是 4dp 圆角的 surface-container，但同一块屏上
        // 4dp 和 28dp 两种弹层并存看着是两个应用，这里统一取 R_ITEM。
        val box = Md3.column(ctx).apply {
            background = Md3.bg(Md3.surface3, Md3.R_ITEM.toFloat())
            setPadding(0, Md3.dp(ctx, 6), 0, Md3.dp(ctx, 6))
        }
        if (!title.isNullOrBlank()) {
            box.addView(Md3.ellipsize(Md3.text(ctx, title, Md3.FS_SM, Md3.onSurfaceVariant)).apply {
                setPadding(Md3.dp(ctx, 16), Md3.dp(ctx, 4), Md3.dp(ctx, 16), Md3.dp(ctx, 4))
            })
        }
        var dlg: android.app.AlertDialog? = null
        for ((label, act) in items) {
            val row = Md3.ellipsize(Md3.text(ctx, label, Md3.FS_MD, Md3.onSurface)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Md3.dp(ctx, 16), 0, Md3.dp(ctx, 16), 0)
                background = Md3.ripple(ctx, Color.TRANSPARENT, 0f)
                isClickable = true
                setOnClickListener { dlg?.dismiss(); act() }
            }
            box.addView(row, Md3.lpDp(ctx, null, 46))
        }
        // 手表没有返回键，菜单必须自带关闭条目。以前只靠“点外部关闭”，
        // 可在 189 dp 宽的屏上菜单几乎占满，两侧剩余几像素，实测点不中，
        // 选完导出就卡在弹窗里。
        box.addView(Md3.text(ctx, "取消", Md3.FS_MD, Md3.onSurfaceVariant).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Md3.dp(ctx, 16), 0, Md3.dp(ctx, 16), 0)
            background = Md3.ripple(ctx, Color.TRANSPARENT, 0f)
            isClickable = true
            setOnClickListener { dlg?.dismiss() }
        }, Md3.lpDp(ctx, null, 46))
        dlg = wrapDialog(ctx, box)
        dlg.setCanceledOnTouchOutside(true)
        return dlg
    }

    /**
     * 长清单菜单：整屏对话框（MD3 full-screen dialog）。
     *
     * 以前是「两列 × 34 dp 行 × 11sp」硬挤进一个浮层 —— 11 项挤两列后每列只有
     * 86 dp 宽，字缩到 11sp 还贴边；而浮层在 189 dp 宽的屏上又几乎占满整屏，
     * 于是既没有浮层的留白感，也没有整屏的秩序感。用户判词：「弹窗好丑」。
     *
     * 现在按整屏处理：不透明底色、48 dp 顶栏（标题 + 关闭）、列表可滚。
     * 手表屏只有 248 dp 高，一屏放不下 11 项，滚动比分页可靠 ——
     * 分页的「更多…」实测会穿透到下层页面。
     */
    fun longMenuDialog(
        ctx: Context,
        title: CharSequence?,
        items: List<Pair<CharSequence, () -> Unit>>,
    ): android.app.Dialog {
        val dlg = android.app.Dialog(ctx)
        val root = Md3.column(ctx).apply { setBackgroundColor(Md3.surface) }

        val bar = Md3.row(ctx)
        bar.setPadding(Md3.dp(ctx, Md3.PAD), 0, 0, 0)
        bar.addView(
            Md3.ellipsize(Md3.text(ctx, title ?: "", Md3.FS_LG, Md3.onSurface, bold = true))
                .apply { gravity = Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(0, Md3.dp(ctx, Md3.TAB_H), 1f),
        )
        // 这块表没有实体返回键，关闭按钮就是唯一出口。
        bar.addView(Md3.iconButton(ctx, "close", { dlg.dismiss() }, desc = "关闭"),
            LinearLayout.LayoutParams(Md3.dp(ctx, Md3.TAP), Md3.dp(ctx, Md3.TAB_H)))
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(ctx, Md3.TAB_H)))

        val list = Md3.column(ctx)
        for ((label, act) in items) {
            list.addView(Md3.ellipsize(Md3.text(ctx, label, Md3.FS_MD, Md3.onSurface)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Md3.dp(ctx, Md3.PAD), 0, Md3.dp(ctx, Md3.PAD), 0)
                background = Md3.ripple(ctx, Color.TRANSPARENT, 0f)
                isClickable = true
                setOnClickListener { dlg.dismiss(); act() }
            }, Md3.lpDp(ctx, null, Md3.TAP))
        }
        val sv = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        root.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dlg.setContentView(root)
        dlg.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Md3.surface))
        }
        dlg.setCanceledOnTouchOutside(true)
        return dlg
    }

    /** 对话框内边距。M3 规范是 24dp，189dp 屏上取 16 才留得下正文。 */
    private const val DIALOG_PAD = 16

    /**
     * 把自绘的盒子装进对话框：窗口底透明、宽度按屏宽自己定。
     *
     * 平台窗口有 280dp 最小宽（M3 的 mobile 值），这块屏只有 189dp：
     * 系统会把窗口裁到屏宽，再套一层 24dp 面板内边距，正文只剩 140dp
     * 还被二次挤压 —— 这就是上一版弹窗「特别丑」的根因。
     */
    private fun wrapDialog(ctx: Context, box: View): android.app.AlertDialog {
        val dlg = android.app.AlertDialog.Builder(ctx).setView(box).create()
        dlg.setOnShowListener {
            dlg.window?.apply {
                setBackgroundDrawable(Md3.bg(Color.TRANSPARENT, 0f))
                val w = ctx.resources.configuration.screenWidthDp
                setLayout(
                    Md3.dp(ctx, (w - 12).coerceAtLeast(160)),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        }
        return dlg
    }

    /**
     * MD3 基本对话框。
     *
     * 规范（m3.material.io/components/dialogs/specs）：容器 28dp 圆角、
     * 容器色 surface-container-high、四边 24dp 内边距、标题与正文 16dp、
     * 正文与按钮 24dp、按钮间距 8dp，按钮用 text 变体。这里逐条落地，
     * 只有内边距按屏宽收到 [DIALOG_PAD] —— 189dp 上照抄 24dp 会把字挤成一条。
     *
     * @param content 自定义内容（输入框、滑杆等），插在正文与按钮之间
     * @param confirm 主操作，null 表示只有关闭
     */
    fun md3Dialog(
        ctx: Context,
        title: CharSequence,
        body: CharSequence? = null,
        content: View? = null,
        confirm: CharSequence? = null,
        dismiss: CharSequence? = "取消",
        onConfirm: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
    ): android.app.AlertDialog {
        val pad = Md3.dp(ctx, DIALOG_PAD)
        val box = Md3.column(ctx).apply {
            background = Md3.bg(Md3.surface3, 28f)
            setPadding(pad, pad, pad, pad)
        }
        box.addView(Md3.ellipsize(Md3.text(ctx, title, Md3.FS_LG, Md3.onSurface, bold = true)))
        if (!body.isNullOrBlank()) {
            box.addView(Md3.text(ctx, body, Md3.FS_MD, Md3.onSurfaceVariant).apply {
                setLineSpacing(Md3.dp(ctx, 3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Md3.dp(ctx, 8) })
        }
        if (content != null) {
            box.addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Md3.dp(ctx, 12) })
        }

        var dlg: android.app.AlertDialog? = null
        val actions = Md3.row(ctx).apply { gravity = Gravity.END }
        fun action(label: CharSequence, primary: Boolean, run: () -> Unit) {
            val b = Md3.text(
                ctx, label, Md3.FS_MD,
                if (primary) Md3.primary else Md3.onSurfaceVariant, bold = true,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(Md3.dp(ctx, 12), 0, Md3.dp(ctx, 12), 0)
                minWidth = Md3.dp(ctx, 56)
                background = Md3.ripple(ctx, Color.TRANSPARENT, Md3.BTN / 2f)
                isClickable = true
                setOnClickListener { dlg?.dismiss(); run() }
            }
            actions.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Md3.dp(ctx, Md3.BTN),
            ).apply { leftMargin = Md3.dp(ctx, 8) })
        }
        if (!dismiss.isNullOrBlank()) action(dismiss, false) { onCancel?.invoke() }
        if (!confirm.isNullOrBlank()) action(confirm, true) { onConfirm?.invoke() }
        if (actions.childCount > 0) {
            box.addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Md3.dp(ctx, 12) })
        }

        dlg = wrapDialog(ctx, box)
        dlg.setCanceledOnTouchOutside(true)
        return dlg
    }

    /**
     * 列表项。
     *
     * @param twoLine 两行（标题 + 副标题）；false 为单行
     * @param highlight 加底色（用于搜索结果、最近下载）
     * @param leading 左侧标记（如 ✓ / ✗）
     * @param trailing 右侧文本（如「196」）
     */
    fun listItem(
        ctx: Context,
        title: CharSequence,
        subtitle: CharSequence? = null,
        twoLine: Boolean = subtitle != null,
        highlight: Boolean = false,
        leading: View? = null,
        trailing: CharSequence? = null,
        // legado 的列表项右侧文字用主色，不是次级灰。滑杆行早就这么做了。
        trailingColor: Int = Md3.primary,
        /** 标题文字色。当前阅读章要用 primary，所以开个口子。 */
        titleColor: Int = Md3.onSurface,
        onTrailingClick: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val item = LinearLayout(ctx).apply {
            orientation = if (twoLine) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (twoLine) Gravity.CENTER_VERTICAL else Gravity.CENTER_VERTICAL
        }
        item.setPadding(
            Md3.dp(ctx, Md3.PAD), 0,
            Md3.dp(ctx, Md3.PAD), 0,
        )
        // M3 lists：单行 56dp、双行 72dp，由最高的元素决定。
        item.minimumHeight = Md3.dp(ctx, if (twoLine) Md3.ITEM_TALL else Md3.ITEM)
        val bgColor = if (highlight) Md3.surface3 else Color.TRANSPARENT
        item.background = Md3.ripple(ctx, bgColor, Md3.R_ITEM.toFloat())
        if (onClick != null) {
            item.isClickable = true
            item.setOnClickListener { onClick() }
        }

        // 图标只在单行项直接挂在行上；双行项要挂到内层横排里（见下面 twoLine 分支），
        // 否则竖排会把图标顶到标题上方独占一行，文字整块左移。
        val leadLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = Md3.dp(ctx, 7) }
        if (leading != null && !twoLine) item.addView(leading, leadLp)

        val titleView = Md3.ellipsize(Md3.text(ctx, title, Md3.FS_MD, titleColor))
        val trailingView = trailing?.let { t ->
            val icon = Icons.name(t.toString())
            if (icon != null) {
                Icons.view(ctx, icon, Md3.onSurfaceVariant, 18).apply {
                    contentDescription = when (t.toString()) {
                        "more", "⋮" -> "更多操作"; else -> t
                    }
                    if (onTrailingClick != null) {
                        isClickable = true
                        // 行右侧的 ⋮ 原来热区约 10 dp，手指点不中；补到 HIT。
                        minimumWidth = Md3.HIT
                        minimumHeight = Md3.HIT
                        setOnClickListener { onTrailingClick() }
                    }
                }
            } else {
                Md3.text(ctx, t, Md3.FS_SM, trailingColor).apply {
                    contentDescription = t
                    gravity = Gravity.CENTER
                    setPadding(Md3.dp(ctx, 6), 0, 0, 0)
                    if (onTrailingClick != null) {
                        isClickable = true
                        minimumWidth = Md3.HIT
                        minimumHeight = Md3.HIT
                        setOnClickListener { onTrailingClick() }
                    }
                }
            }
        }
        if (twoLine) {
            // 外层横排：图标在左，标题与副标题在右侧内层竖排。
            val outer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (leading != null) outer.addView(leading, leadLp)
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(titleView, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            trailingView?.let { titleRow.addView(it) }
            col.addView(titleRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (subtitle != null) {
                col.addView(
                    // legado 副标题压 30% 透明，否则两行权重一样，主次不分。
                    Md3.ellipsize(Md3.text(ctx, subtitle, Md3.FS_SM, Md3.onSurfaceVariant))
                        .apply { alpha = 0.7f },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            outer.addView(col, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            item.addView(outer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            // 单行分支以前直接丢掉 subtitle —— 调用方算好了「作者 · 源名」传进来，
            // 界面上却只剩书名。现在补成「书名 —— 副标题」跟在标题末尾。
            val fullTitle = if (subtitle.isNullOrBlank()) title.toString()
                            else "$title —— $subtitle"
            item.addView(
                Md3.ellipsize(Md3.text(ctx, fullTitle, Md3.FS_MD, titleColor)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            trailingView?.let { item.addView(it) }
        }

        // 两行项不能固定 42dp：标题 + 副标题 + 左侧 emoji 的实际行高会超，
        // 超出的部分上下同时被裁（书源页那两个卡片的副标题就被切了半行）。
        val lp = if (twoLine) {
            item.minimumHeight = Md3.dp(ctx, Md3.ITEM)
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        } else {
            Md3.lpDp(ctx, null, Md3.ITEM)
        }
        item.layoutParams = lp.apply { bottomMargin = Md3.dp(ctx, 2) }
        return item
    }

    /** 两行列表项，右侧带状态标记。 */
    fun chapterItem(
        ctx: Context,
        index: Int,
        title: CharSequence,
        status: CharSequence? = null,
        statusColor: Int = Md3.onSurfaceVariant,
        highlight: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val mark = if (status != null) {
            Md3.text(ctx, status, Md3.FS_SM, statusColor).apply {
                // 定宽 + 居中：✓ ✗ 🔒 字宽不同，不定宽的话行尾会跳动。
                gravity = Gravity.CENTER
                minWidth = Md3.dp(ctx, 18)
                setPadding(Md3.dp(ctx, 6), 0, 0, 0)
            }
        } else null
        return listItem(
            ctx = ctx,
            title = title,
            subtitle = null,
            twoLine = false,
            highlight = highlight,
            titleColor = if (highlight) Md3.primary else Md3.onSurface,
            leading = Md3.text(ctx, "$index", Md3.FS_SM, Md3.onSurfaceVariant).apply {
                minWidth = Md3.dp(ctx, 22)
            },
            trailing = null,
            onClick = onClick,
        ).also { item ->
            // 把状态标记挂到最右
            if (mark != null) item.addView(mark)
        }
    }

    /** 卡片。 */
    fun card(ctx: Context, outlined: Boolean = false, padDp: Number = Md3.PAD * 0.9): LinearLayout {
        val c = Md3.column(ctx)
        c.background = if (outlined) Md3.bg(Color.TRANSPARENT, Md3.R_CARD.toFloat(), Md3.outlineVariant)
                       else Md3.bg(Md3.surface3, Md3.R_CARD.toFloat())
        c.setPadding(Md3.dp(ctx, padDp), Md3.dp(ctx, padDp), Md3.dp(ctx, padDp), Md3.dp(ctx, padDp))
        c.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Md3.dp(ctx, Md3.GAP) }
        return c
    }

    /** 线性进度条。4dp 高，圆角。轨道用 outlineVariant：surface4 在卡片上几乎看不见。 */
    fun progressBar(ctx: Context, fraction: Float, heightDp: Number = 4): LinearLayout {
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Md3.bg(Md3.outlineVariant, heightDp.toFloat() / 2)
        }
        if (fraction > 0f) {
            val fill = View(ctx).apply {
                background = Md3.bg(Md3.primary, heightDp.toFloat() / 2)
            }
            track.addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fraction.coerceIn(0f, 1f)))
        }
        // 剩余部分占位，保证 track 有高度
        if (fraction < 1f) {
            track.addView(View(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fraction.coerceIn(0f, 1f)))
        }
        track.layoutParams = Md3.lpDp(ctx, null, heightDp).apply {
            topMargin = Md3.dp(ctx, 5); bottomMargin = Md3.dp(ctx, 4)
        }
        return track
    }

    /**
     * 可滚动内容区。
     *
     * 不再做「限宽居中」：这是手表屏，189 dp 宽本来就只有一条内容列。
     *
     * 滚动条显式打开并加粗：Android 默认在 2.0 density 下只有约 4px，
     * 手表上既看不见也够不着。
     */
    fun scrollBody(ctx: Context, crownStep: Float = 60f, crownGlide: Int = 50): ScrollView =
        ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(Md3.dp(ctx, Md3.PAD), 0, Md3.dp(ctx, Md3.PAD), Md3.dp(ctx, Md3.PAD))
            clipToPadding = false
            layoutParams = Md3.lpWeight(1f)
            isVerticalScrollBarEnabled = true
            // 画在内容内侧，否则会和页面边距一起被挤到屏外
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            // 表冠：ColorOS 把转动投成 ACTION_SCROLL，而 ScrollView 默认只认触摸。
            //
            // 不能每来一格就 scrollBy：事件之间没有插值，转快了是「跳格子」，
            // 看着不跟手。这里维护一个目标位置，每格把位移累加进目标，
            // 再用 ValueAnimator 每帧逼近，手感连续，快转也不丢距离。
            // 方向键事件不用管：ScrollView 自身就会响应（实测 DPAD_DOWN 能滚）。
            var crownTarget = 0f
            var crownAnim: android.animation.ValueAnimator? = null
            setOnGenericMotionListener { v, e ->
                if (e.action != android.view.MotionEvent.ACTION_SCROLL) return@setOnGenericMotionListener false
                val dy = e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
                if (dy == 0f) return@setOnGenericMotionListener false
                val sv = v as ScrollView
                // 上一次动画还在跑时，crownTarget 已经是未到达的目标，不能重置，
                // 否则快转时的位移会丢（表现为越转越不跟手）。
                if (crownAnim?.isRunning != true) crownTarget = sv.scrollY.toFloat()
                crownTarget = (crownTarget - dy * crownStep).coerceIn(0f, maxScrollOf(sv).toFloat())
                crownAnim?.cancel()
                crownAnim = android.animation.ValueAnimator.ofFloat(sv.scrollY.toFloat(), crownTarget).apply {
                    // 追平时长：再短看着抖，再长有拖尾。由设置页给。
                    duration = crownGlide.toLong()
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { a -> sv.scrollTo(0, (a.animatedValue as Float).toInt()) }
                    start()
                }
                true
            }
            val col = Md3.column(ctx)
            addView(col, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /**
     * 取出 [scrollBody] 里真正放内容的列。
     */
    fun contentColumn(body: ScrollView): LinearLayout =
        (body.getChildAt(0) as? LinearLayout) ?: LinearLayout(body.context)

    /** 空状态。传图标名（或旧字形）就在上面画一个大图标。 */
    fun emptyState(ctx: Context, glyph: String, message: CharSequence): LinearLayout {
        val box = Md3.column(ctx)
        box.gravity = Gravity.CENTER
        box.setPadding(Md3.dp(ctx, 12), Md3.dp(ctx, 24), Md3.dp(ctx, 12), Md3.dp(ctx, 24))
        val icon = Icons.name(glyph)
        if (icon != null) {
            box.addView(Icons.view(ctx, icon, Md3.onSurfaceVariant, 32).apply {
                alpha = 0.3f
                // 大图标是装饰，真正要读的是下面那句 message（P1-7）
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Md3.lpDp(ctx, null, 32))
        } else {
            box.addView(Md3.text(ctx, glyph, 30f, Md3.onSurfaceVariant).apply {
                alpha = 0.3f
                // 容器居中只有子 View 宽度不撑满时才生效；文字自己还得居中一次，
                // 否则符号靠在左边（实测偏左 122 px）。
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        val m = Md3.text(ctx, message, Md3.FS_MD, Md3.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            setPadding(0, Md3.dp(ctx, 10), 0, 0)
        }
        box.addView(m)
        return box
    }

    /**
     * 底部导航。三栏共用一条，对应 legado 的书籍/发现/我的。
     *
     * 图标走 [Icons] 矢量图，标签仍是文字：手表上光图标分不清「书架」与「发现」。
     */
    fun navBar(ctx: Context, items: List<Triple<String, String, Boolean>>, onSelect: ((Int) -> Unit)? = null): LinearLayout {
        val nav = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Md3.bg(Md3.surface2, 0)
            // 高度 64dp 内只放得下 32dp 指示器 + 4dp + 标签，上下不再加固定内边距，
            // 交给格子的 gravity=CENTER 居中 —— 写 12/16 会把内容顶出容器。
        }
        items.forEachIndexed { i, (glyph, label, active) ->
            val tint = if (active) Md3.onSecondaryContainer else Md3.onSurfaceVariant
            val cell = Md3.column(ctx).apply {
                gravity = Gravity.CENTER
                background = Md3.ripple(ctx, Color.TRANSPARENT, 0f)
                contentDescription = label
                isClickable = onSelect != null
                onSelect?.let { cb -> setOnClickListener { cb(i) } }
            }
            val icon = Icons.name(glyph)
            // legado AppNavigationBar：胶囊固定 64×32 RoundedCornerShape(16)，
            // 居中放，不随格子缩水。上一版用 MATCH_PARENT+margin 让格子决定宽度，
            // 结果屏上置成 46dp 的扁块。格子 63dp 放 64dp 胶囊差 1dp，
            // 居中后左右各溢 0.5dp，被裁掉的是圆弧端点，看不出束。
            val ind = FrameLayout(ctx).apply {
                background = if (active) Md3.bg(Md3.secondaryContainer, Md3.NAV_IND_H / 2f) else null
            }
            val ico: View = if (icon != null) {
                Icons.view(ctx, icon, tint, Md3.ICON_ACTION).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            } else {
                Md3.text(ctx, glyph, Md3.FS_MD, tint).apply { gravity = Gravity.CENTER }
            }
            ind.addView(ico, FrameLayout.LayoutParams(
                Md3.dp(ctx, Md3.ICON_ACTION), Md3.dp(ctx, Md3.ICON_ACTION), Gravity.CENTER))
            val cellInd = LinearLayout(ctx)
            cellInd.gravity = Gravity.CENTER
            cellInd.addView(ind, FrameLayout.LayoutParams(
                Md3.dp(ctx, Md3.NAV_IND_W), Md3.dp(ctx, Md3.NAV_IND_H)))
            cell.addView(cellInd, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(ctx, Md3.NAV_IND_H)))
            // 指示器到标签 4dp。标签也撑满格子并居中，不与图标各自的偏移叠在一起。
            cell.addView(Md3.text(ctx, label, Md3.FS_LABEL, tint).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Md3.dp(ctx, 4)
            })
            nav.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        nav.layoutParams = Md3.lpDp(ctx, null, Md3.NAV)
        return nav
    }

    /**
     * 分组页签行。照 legado 的 `AppTabRow`：选中页签给胶囊底色，未选中只有文字。
     *
     * 宽了能横向滑：手表 189dp 上三四个页签就满，再多也不挤成一团。
     */
    /**
     * 大标题行：左边一个标题，右边动作图标，动作钉在右端不随标题滚。
     *
     * 和 [tabRow] 的区别就在标题的画法 —— 页签是「可选项」，选中项要给
     * secondaryContainer 胶囊；标题不是可选项，画成 22sp 加粗的 onSurface 纯文字。
     * 之前书架把标题塞进 tabRow，只有一个「页签」且恒为选中，看起来永远亮着，
     * 用户判词：「大标题还是被选中状态」。
     */
    fun headerRow(
        ctx: Context,
        title: CharSequence,
        trailing: List<Pair<String, () -> Unit>> = emptyList(),
    ): LinearLayout {
        val bar = Md3.row(ctx)
        bar.setPadding(Md3.dp(ctx, Md3.PAD), 0, 0, Md3.dp(ctx, Md3.GAP))
        bar.addView(
            Md3.ellipsize(Md3.text(ctx, title, Md3.FS_LG, Md3.onSurface, bold = true)).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(0, Md3.dp(ctx, Md3.TAB_H), 1f),
        )
        for ((glyph, act) in trailing) {
            bar.addView(Md3.iconButton(ctx, glyph, act), LinearLayout.LayoutParams(
                Md3.dp(ctx, Md3.TAP), Md3.dp(ctx, Md3.TAB_H)))
        }
        return bar
    }

    fun tabRow(
        ctx: Context,
        titles: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
        trailing: List<Pair<String, () -> Unit>> = emptyList(),
    ): LinearLayout {
        val row = Md3.row(ctx)
        titles.forEachIndexed { i, t ->
            val on = i == selected
            val cell = Md3.text(ctx, t, 14f,
                if (on) Md3.onSecondaryContainer else Md3.onSurfaceVariant, bold = on).apply {
                gravity = Gravity.CENTER
                background = Md3.ripple(ctx, if (on) Md3.secondaryContainer else Color.TRANSPARENT, 20f)
                setPadding(Md3.dp(ctx, 10), 0, Md3.dp(ctx, 10), 0)
                minimumHeight = Md3.dp(ctx, Md3.TAB_H)
                isClickable = true
                contentDescription = t
                setOnClickListener { if (i != selected) onSelect(i) }
            }
            row.addView(cell, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) leftMargin = Md3.dp(ctx, 2) })
        }
        // 页签可横滑，但动作图标必须钉在右边、不随页签滚走。
        // 上一版把图标塞进滚动行里：三个页签 198dp 就占满 189dp 屏，图标直接被推出屏外。
        val scroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val bar = Md3.row(ctx)
        bar.setPadding(Md3.dp(ctx, Md3.PAD), 0, 0, Md3.dp(ctx, Md3.GAP))
        bar.addView(scroller, LinearLayout.LayoutParams(0, Md3.dp(ctx, Md3.TAB_H), 1f))
        for ((glyph, act) in trailing) {
            bar.addView(Md3.iconButton(ctx, glyph, act), LinearLayout.LayoutParams(
                Md3.dp(ctx, Md3.TAP), Md3.dp(ctx, Md3.TAB_H)))
        }
        return bar
    }

    /** Snackbar。 */
    fun snackbar(ctx: Context, message: CharSequence, action: CharSequence? = null, onAction: (() -> Unit)? = null): LinearLayout {
        val bar = Md3.row(ctx)
        bar.background = Md3.bg(Md3.surface4, 9f)
        bar.setPadding(Md3.dp(ctx, 10), Md3.dp(ctx, 9), Md3.dp(ctx, 10), Md3.dp(ctx, 9))
        bar.addView(Md3.text(ctx, message, Md3.FS_SM, Md3.onSurface))
        if (action != null) {
            val a = Md3.text(ctx, action, Md3.FS_SM, Md3.primary, bold = true)
            a.setPadding(Md3.dp(ctx, 10), 0, 0, 0)
            if (onAction != null) {
                a.isClickable = true
                a.setOnClickListener { onAction() }
            }
            bar.addView(a)
        }
        return bar
    }

    /** 屏幕根容器。 */
    fun screenRoot(ctx: Context): LinearLayout =
        Md3.column(ctx).apply {
            setBackgroundColor(Md3.surface)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
}

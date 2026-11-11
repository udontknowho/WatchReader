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
            bar.contentDescription = "搜索框，$hint"
        }
        bar.layoutParams = Md3.lpDp(ctx, null, Md3.SEARCH).apply { bottomMargin = Md3.dp(ctx, Md3.GAP) }
        return bar
    }

    /**
     * 一行「标签 + 当前值 + 滑杆」。
     *
     * 档位式选项（小/中/大）粒度太粗，字号差一档就过大。滑杆给连续值。
     * [steps] 是区间数；0 使用 0.01 单位档位。按钮每次移动一档。
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
        require(min.isFinite() && max.isFinite() && max > min) { "滑条范围无效" }
        // progress 直接代表合法档位，显示、拖动和按钮共用同一套量化。
        val ticks = if (steps > 0) steps else Math.round((max - min) * 100).coerceAtLeast(1)
        fun valueAt(index: Int): Float = when (index) {
            0 -> min
            ticks -> max
            else -> (min.toDouble() + (max.toDouble() - min) * index / ticks).toFloat()
        }
        fun indexOf(v: Float): Int = Math.round(
            ((if (v.isFinite()) v else min).coerceIn(min, max) - min).toDouble() /
                (max.toDouble() - min) * ticks).toInt().coerceIn(0, ticks)
        val valText = Md3.text(ctx, fmt(valueAt(indexOf(value))), Md3.FS_SM, Md3.primary)
        head.addView(valText)
        row.addView(head)

        val controls = Md3.row(ctx)
        val minus = Md3.text(ctx, "−", Md3.FS_LG, Md3.primary)
        val plus = Md3.text(ctx, "+", Md3.FS_LG, Md3.primary)
        val bar = android.widget.SeekBar(ctx).apply {
            this.max = ticks
            progress = indexOf(value)
            keyProgressIncrement = 1
            minimumHeight = Md3.dp(ctx, 48)
            splitTrack = false
            progressTintList = android.content.res.ColorStateList.valueOf(Md3.primary)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Md3.outlineVariant)
            thumb = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Md3.primary)
                setSize(Md3.dp(ctx, 28), Md3.dp(ctx, 28))
            }
            thumbOffset = Md3.dp(ctx, 14)
            contentDescription = label
            // 全局横滑判定也应按命中子树排除 SeekBar；父 ViewGroup 走标准拦截协议。
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        fun paint(index: Int) {
            valText.text = fmt(valueAt(index))
            minus.isEnabled = index > 0
            plus.isEnabled = index < ticks
            minus.alpha = if (minus.isEnabled) 1f else 0.35f
            plus.alpha = if (plus.isEnabled) 1f else 0.35f
        }
        fun adjust(offset: Int) {
            val next = (bar.progress + offset).coerceIn(0, ticks)
            if (next != bar.progress) {
                bar.progress = next
                onChange(valueAt(next))
            }
        }
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                paint(p)
                if (fromUser) onChange(valueAt(p))
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
            }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
            }
        })
        listOf(minus to -1, plus to 1).forEach { (button, offset) ->
            button.gravity = Gravity.CENTER
            button.background = Md3.ripple(ctx, Md3.surface3, 16f)
            button.contentDescription = "$label，${if (offset < 0) "减少" else "增加"}"
            button.isClickable = true
            button.setOnClickListener { adjust(offset) }
        }
        controls.addView(minus, LinearLayout.LayoutParams(Md3.dp(ctx, 40), Md3.dp(ctx, 48)))
        controls.addView(bar, LinearLayout.LayoutParams(0, Md3.dp(ctx, 48), 1f))
        controls.addView(plus, LinearLayout.LayoutParams(Md3.dp(ctx, 40), Md3.dp(ctx, 48)))
        paint(bar.progress)
        row.addView(controls, LinearLayout.LayoutParams(
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
        trailing: CharSequence? = null,
        onTrailingClick: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = listItem(
        ctx = ctx, title = title, subtitle = subtitle, twoLine = subtitle != null,
        leading = glyph?.let { Icons.view(ctx, it, Md3.onSurfaceVariant, Md3.ICON_LEAD) },
        trailing = trailing, onTrailingClick = onTrailingClick,
        onClick = onClick,
    ).apply {
        background = Md3.ripple(ctx, Color.TRANSPARENT, Md3.R_ITEM.toFloat())
    }

    /** 按钮。转发到 [Md3.button]，让调用处只认 Widgets 一个入口。 */
    /**
     * 按钮。宽度按变体定，页面不再各自写 LayoutParams：
     *
     * - filled / tonal / outline：主操作，铺满一行（危险动作也走这里，够大才好点）。
     * - text：次操作，宽度随字、靠右，不再拉成一条横杠。
     *
     * 一行两枚时调用处自己传等分 weight，会覆盖这里的值。
     *
     * @param topMarginPx 上边距，传像素（[Md3.dp] 换算），0 表示不设
     */
    fun button(
        ctx: Context,
        label: CharSequence,
        variant: String = "filled",
        topMarginPx: Int = 0,
        onClick: (() -> Unit)? = null,
    ): TextView {
        val b = Md3.button(ctx, label, variant, onClick)
        // text 变体矮一档：它是轻动作，占满 34 dp 只会把列表挤下去。
        b.layoutParams = if (variant == "text") {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Md3.dp(ctx, 32)
            ).apply {
                gravity = Gravity.END
                topMargin = topMarginPx
            }
        } else {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(ctx, Md3.BTN)
            ).apply { topMargin = topMarginPx }
        }
        return b
    }

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
        // 内容超出表屏时整体可滚，避免确认与取消被窗口裁掉。
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            addView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dlg = android.app.AlertDialog.Builder(ctx).setView(scroll).create()
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
        // 窄屏长文案使用整行按钮，避免确认文字被挤出窗口。
        val actions = Md3.column(ctx)
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
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(ctx, Md3.BTN),
            ).apply { topMargin = Md3.dp(ctx, 4) })
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
                        // HIT 是 dp，必须换算像素，避免高密度表屏热区缩水。
                        minimumWidth = Md3.dp(ctx, Md3.HIT)
                        minimumHeight = Md3.dp(ctx, Md3.HIT)
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
                        minimumWidth = Md3.dp(ctx, Md3.HIT)
                        minimumHeight = Md3.dp(ctx, Md3.HIT)
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
            // 操作占整行尾槽，标题与副标题共用同一文字宽度。
            trailingView?.let { outer.addView(it) }
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
            item.minimumHeight = Md3.dp(ctx, Md3.ITEM_TALL)
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
    fun scrollBody(ctx: Context, crownStep: Float = 60f, crownGlide: Int = 50): CrownScrollView =
        CrownScrollView(ctx, crownStep, crownGlide).apply {
            isFillViewport = true
            setPadding(Md3.dp(ctx, Md3.PAD), 0, Md3.dp(ctx, Md3.PAD), Md3.dp(ctx, Md3.PAD))
            clipToPadding = false
            layoutParams = Md3.lpWeight(1f)
            isVerticalScrollBarEnabled = true
            // 画在内容内侧，否则会和页面边距一起被挤到屏外
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            val col = Md3.column(ctx)
            addView(col, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /** 保留原生拖动与外部触摸监听器，只把主动越界输入交给阅读器。 */
    class CrownScrollView(
        ctx: Context,
        private val crownStep: Float = 60f,
        private val crownGlide: Int = 50,
    ) : ScrollView(ctx) {
        var onInputStart: (() -> Unit)? = null

        /** true 表示向后（向下），false 表示向前；位移为正数像素。 */
        var onBoundary: ((Boolean, Int) -> Unit)? = null

        private val crownMotion = com.watchdl.core.CrownMotion(crownGlide)
        private var crownFramePosted = false
        private var nativeFlingPending = false

        override fun fling(velocityY: Int) {
            nativeFlingPending = velocityY != 0
            super.fling(velocityY)
        }

        private fun stopNativeFling() {
            if (!nativeFlingPending) return
            nativeFlingPending = false
            super.fling(0)
        }

        private val crownFrame = object : Runnable {
            override fun run() {
                crownFramePosted = false
                scrollTo(0, crownMotion.frame(maxScrollOf(this@CrownScrollView),
                    android.view.animation.AnimationUtils.currentAnimationTimeMillis()))
                if (crownMotion.running) {
                    crownFramePosted = true
                    postOnAnimation(this)
                }
            }
        }
        private val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastTouchY = 0f
        private var touchActive = false
        private var dragging = false
        private var boundaryDrag = 0f

        /** 同时终止旧表冠动画和原生惯性，避免多个输入同时改写 scrollY。 */
        fun cancelCrownAnimation() {
            if (crownFramePosted) removeCallbacks(crownFrame)
            crownFramePosted = false
            crownMotion.cancel()
            stopNativeFling()
        }

        override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
            if (event.action != android.view.MotionEvent.ACTION_SCROLL) {
                return super.onGenericMotionEvent(event)
            }
            val rotary = event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)
            val delta = com.watchdl.core.CrownMotion.delta(
                rotary,
                event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL),
                event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL), crownStep,
            )
            // 关闭表冠时吞掉旋转事件，不能回落到 ScrollView 的默认滚动。
            if (crownStep <= 0f) {
                cancelCrownAnimation()
                return true
            }
            if (!delta.isFinite() || delta == 0f) return super.onGenericMotionEvent(event)
            onInputStart?.invoke()
            // 只在原生 fling 留有状态时终止一次，不对每个表冠事件重建原生惯性。
            stopNativeFling()
            // 保留尚未追平的表冠目标，快转不丢位移。
            val max = maxScrollOf(this)
            val forward = delta > 0f
            if ((forward && scrollY >= max) || (!forward && scrollY <= 0)) {
                cancelCrownAnimation()
                onBoundary?.invoke(forward, kotlin.math.abs(delta).toInt().coerceAtLeast(1))
                return true
            }
            crownMotion.add(scrollY, max, delta,
                android.view.animation.AnimationUtils.currentAnimationTimeMillis())
            if (!crownFramePosted) {
                crownFramePosted = true
                postOnAnimation(crownFrame)
            }
            return true
        }

        private fun trackBoundaryDrag(event: android.view.MotionEvent) {
            val delta = lastTouchY - event.y
            lastTouchY = event.y
            if (!dragging) {
                val vertical = kotlin.math.abs(event.y - downY)
                dragging = vertical > touchSlop && vertical > kotlin.math.abs(event.x - downX)
            }
            // 只累计事件到来前已经位于边界的外拖，不把到达边界的正常滚动算进去。
            val outward = (delta > 0f && scrollY >= maxScrollOf(this)) ||
                (delta < 0f && scrollY <= 0)
            if (!outward) {
                if (delta != 0f) boundaryDrag = 0f
            } else {
                if ((boundaryDrag > 0f && delta < 0f) || (boundaryDrag < 0f && delta > 0f)) {
                    boundaryDrag = 0f
                }
                boundaryDrag += delta
            }
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    cancelCrownAnimation()
                    onInputStart?.invoke()
                    downX = event.x
                    downY = event.y
                    lastTouchY = event.y
                    touchActive = true
                    dragging = false
                    boundaryDrag = 0f
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (touchActive) trackBoundaryDrag(event)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (touchActive) trackBoundaryDrag(event)
                    val distance = kotlin.math.abs(boundaryDrag).toInt()
                    val forward = boundaryDrag > 0f
                    val atBoundary = if (forward) scrollY >= maxScrollOf(this) else scrollY <= 0
                    val callback = onBoundary
                    val shouldNotify = touchActive && dragging && distance > touchSlop &&
                        atBoundary && callback != null
                    touchActive = false
                    boundaryDrag = 0f
                    if (shouldNotify) {
                        // 不让同一次松手再产生原生 fling；回调只能发生一次。
                        val cancel = android.view.MotionEvent.obtain(event)
                        cancel.action = android.view.MotionEvent.ACTION_CANCEL
                        try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
                        cancelCrownAnimation()
                        callback?.invoke(forward, distance)
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL,
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    touchActive = false
                    boundaryDrag = 0f
                }
            }
            return super.onTouchEvent(event)
        }

        override fun executeKeyEvent(event: android.view.KeyEvent): Boolean {
            val direction = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN -> true
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_PAGE_UP -> false
                else -> return super.executeKeyEvent(event)
            }
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return super.executeKeyEvent(event)
            cancelCrownAnimation()
            onInputStart?.invoke()
            val boundary = if (direction) scrollY >= maxScrollOf(this) else scrollY <= 0
            if (isLaidOut && boundary && onBoundary != null) {
                onBoundary?.invoke(direction, crownStep.toInt().coerceAtLeast(1))
                return true
            }
            return super.executeKeyEvent(event)
        }

        override fun onDetachedFromWindow() {
            touchActive = false
            boundaryDrag = 0f
            cancelCrownAnimation()
            super.onDetachedFromWindow()
        }
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
        box.setPadding(Md3.dp(ctx, 8), Md3.dp(ctx, 12), Md3.dp(ctx, 8), Md3.dp(ctx, 12))
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
        box.addView(m, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            // 两处着色分开，照 M3 `NavigationBarItemDefaults`：
            // 图标坐在 secondaryContainer 胶囊里，所以取 onSecondaryContainer；
            // 标签在胶囊下方、不在胶囊里，取 onSurface。
            // 之前两者都吃 onSecondaryContainer，标签成了胶囊外挂的一行淡紫字。
            val iconTint = if (active) Md3.onSecondaryContainer else Md3.onSurfaceVariant
            val labelTint = if (active) Md3.onSurface else Md3.onSurfaceVariant
            val cell = Md3.column(ctx).apply {
                gravity = Gravity.CENTER
                background = Md3.ripple(ctx, Color.TRANSPARENT, 0f)
                contentDescription = label
                // TalkBack 靠 isSelected 报「已选择」。legado 在 Compose 侧是
                // `semantics(mergeDescendants = true) { contentDescription = 标签 }`，
                // 合并语义我们只能用「父级写描述 + 子级退出无障碍树」来等效。
                isSelected = active
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
                Icons.view(ctx, icon, iconTint, Md3.ICON_ACTION).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            } else {
                Md3.text(ctx, glyph, Md3.FS_MD, iconTint).apply {
                    gravity = Gravity.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
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
            cell.addView(Md3.text(ctx, label, Md3.FS_LABEL, labelTint).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
                // 文字已由 cell 的 contentDescription 承担，留在树里会被念两遍。
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
     * 底栏指示点。
     *
     * 用户判词（2026-11-05）：底栏 64 dp 太大，改成三个小圆点，当前栏是一条短横。
     * 8 dp 一条，省下的 56 dp 全给正文。
     *
     * 点本身只有 3 dp，没法直接点：热区是三个等宽格子（约 63×8），点只是格子里的图案。
     * 主入口是左右滑动切栏，点按是给「知道自己在哪一栏」的备用路径。
     *
     * @param labels 三栏名，只用于无障碍描述（TalkBack 读「书架，已选择」）。
     */
    fun navDots(
        ctx: Context,
        labels: List<String>,
        active: Int,
        onSelect: ((Int) -> Unit)? = null,
    ): LinearLayout {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Md3.bg(Md3.surface, 0)
        }
        labels.forEachIndexed { i, label ->
            val on = i == active
            val cell = FrameLayout(ctx).apply {
                contentDescription = label
                isSelected = on
                isClickable = onSelect != null
                onSelect?.let { cb -> setOnClickListener { cb(i) } }
            }
            val w = if (on) Md3.NAV_DOT * 3 else Md3.NAV_DOT
            val dot = View(ctx).apply {
                background = Md3.bg(
                    if (on) Md3.primary else Md3.outlineVariant,
                    Md3.NAV_DOT / 2f,
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            cell.addView(dot, FrameLayout.LayoutParams(
                Md3.dp(ctx, w), Md3.dp(ctx, Md3.NAV_DOT), Gravity.CENTER))
            bar.addView(cell, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        bar.layoutParams = Md3.lpDp(ctx, null, Md3.NAV_DOT_H)
        return bar
    }

    /**
     * 顶栏自动收起：下滚累计到阈值就滑走，上滚拉回。
     *
     * 用户判词（2026-11-05）：「他会自己收上去」。要点是**不把高度绑在滚动位置上** ——
     * 绑着做，滚得慢时顶栏会一顿一顿地跟着挪。这里改成「方向触发 + 一次动画走完」，
     * 手感才是自己收上去。
     *
     * 收出去的是真高度（25 → 0）：列表跟着长高，这 25 dp 才算真省下来。
     * 代价是动画期间列表要重排；真机掉帧就退回「只位移不塌高」的做法。
     */
    fun autoHideHeader(ctx: Context, root: ViewGroup) {
        val header = root.getChildAt(0) ?: return
        val scroller = firstScroll(root) ?: return
        val full = Md3.dp(ctx, Md3.HEADER_H)
        if (full <= 0) return

        val lp = (header.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, full).also { header.layoutParams = it }
        lp.height = full
        header.layoutParams = lp

        var lastY = scroller.scrollY
        var acc = 0
        var collapsed = false
        var anim: android.animation.ValueAnimator? = null
        val threshold = Md3.dp(ctx, 8).coerceAtLeast(1)

        fun animateTo(target: Int) {
            if (lp.height == target) return
            anim?.cancel()
            anim = android.animation.ValueAnimator.ofInt(lp.height, target).apply {
                duration = 180
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { a ->
                    lp.height = a.animatedValue as Int
                    header.layoutParams = lp
                }
                start()
            }
        }

        scroller.setOnScrollChangeListener { _, _, y, _, _ ->
            val dy = y - lastY
            lastY = y
            // 回到顶上一定展开，不看方向：否则滚回开头会看到一个没有标题的空白页。
            if (y <= 0) {
                acc = 0
                if (collapsed) { collapsed = false; animateTo(full) }
                return@setOnScrollChangeListener
            }
            if (dy == 0) return@setOnScrollChangeListener
            // 换向就清零累计：不这么做，手指微抖一下能来回翻好几次。
            if (acc != 0 && (acc > 0) != (dy > 0)) acc = 0
            acc += dy
            if (!collapsed && acc >= threshold) {
                collapsed = true
                animateTo(0)
            } else if (collapsed && acc <= -threshold) {
                collapsed = false
                animateTo(full)
            }
        }
    }

    /** 第一层纵向滚动区。找不到就返回 null —— 空屏与不可滚的屏都是合法状态。 */
    fun firstScroll(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) firstScroll(v.getChildAt(i))?.let { return it }
        return null
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
        // 25 dp 一行：标题 13 sp 与 18 dp 图标同行，不留底边距。
        // 旧版是 40 dp 行高 + 8 dp 间隔 = 48 dp，占掉屏高 19%；现在 25 dp 占 10%。
        // 字号与图标一起降：25 dp 里放 22 sp 的标题会上下顶满，看着像被裁过。
        val bar = Md3.row(ctx)
        bar.setPadding(Md3.dp(ctx, Md3.PAD), 0, 0, 0)
        bar.addView(
            Md3.ellipsize(Md3.text(ctx, title, 13f, Md3.onSurface, bold = true)).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(0, Md3.dp(ctx, Md3.HEADER_H), 1f),
        )
        for ((glyph, act) in trailing) {
            // 槽宽给 HEADER_H + 10：25 dp 是高度，热区靠横向补。
            bar.addView(Md3.iconButton(ctx, glyph, act, size = 18), LinearLayout.LayoutParams(
                Md3.dp(ctx, Md3.HEADER_H + 10), Md3.dp(ctx, Md3.HEADER_H)))
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
        bar.addView(Md3.text(ctx, message, Md3.FS_SM, Md3.onSurface),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

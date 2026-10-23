package com.watchdl.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * MD3 组件。全部程序化构造 —— 项目不用 XML 资源，构建链路是 kotlinc + aapt2 手工流水线。
 *
 * 每个函数的尺寸都取自 [Md3]，对应 `ui/md3-final.html` 的定稿。
 */
object Widgets {

    /** 应用栏。36dp 高，左返回 / 标题 / 右侧动作。 */
    fun topBar(
        ctx: Context,
        title: CharSequence,
        onBack: (() -> Unit)? = null,
        backGlyph: String = "←",
        trailing: List<Pair<String, () -> Unit>> = emptyList(),
    ): LinearLayout {
        val bar = Md3.row(ctx)
        bar.setPadding(Md3.dp(ctx, Md3.PAD / 2), 0, Md3.dp(ctx, Md3.PAD / 2), 0)
        if (onBack != null || backGlyph.isNotEmpty()) {
            val ib = Md3.iconButton(ctx, backGlyph, onBack)
            bar.addView(ib, LinearLayout.LayoutParams(Md3.dp(ctx, Md3.TAP), Md3.dp(ctx, Md3.TAP)))
        }
        val t = Md3.ellipsize(Md3.text(ctx, title, Md3.FS_LG, Md3.onSurface, bold = true))
        t.setPadding(Md3.dp(ctx, Md3.PAD * 0.6), 0, 0, 0)
        bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    /** 分组标题。 */
    fun sectionLabel(ctx: Context, label: CharSequence): TextView =
        Md3.text(ctx, label, Md3.FS_SM, Md3.primary, bold = true).apply {
            letterSpacing = 0.05f
            setPadding(Md3.dp(ctx, 2), Md3.dp(ctx, 5), Md3.dp(ctx, 2), Md3.dp(ctx, 3))
        }

    /** 按钮。转发到 [Md3.button]，让调用处只认 Widgets 一个入口。 */
    fun button(ctx: Context, label: CharSequence, variant: String = "filled", onClick: (() -> Unit)? = null): TextView =
        Md3.button(ctx, label, variant, onClick)

    /** 应用栏图标按钮。 */
    fun iconButton(ctx: Context, glyph: String, onClick: (() -> Unit)? = null): TextView =
        Md3.iconButton(ctx, glyph, onClick)

    /** 把整棵子树里的文字改成指定颜色。阅读器切浅色底时，顶栏字形要跟着变深。 */
    fun tintSubtree(v: View, color: Int) {
        when (v) {
            is TextView -> v.setTextColor(color)
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
        val box = Md3.column(ctx).apply { setPadding(0, Md3.dp(ctx, 2), 0, Md3.dp(ctx, 4)) }
        if (!title.isNullOrBlank()) {
            box.addView(Md3.ellipsize(Md3.text(ctx, title, Md3.FS_SM, Md3.onSurfaceVariant)).apply {
                setPadding(Md3.dp(ctx, 14), Md3.dp(ctx, 6), Md3.dp(ctx, 14), Md3.dp(ctx, 2))
            })
        }
        var dlg: android.app.AlertDialog? = null
        for ((label, act) in items) {
            val row = Md3.text(ctx, label, Md3.FS_MD, Md3.onSurface).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Md3.dp(ctx, 14), 0, Md3.dp(ctx, 14), 0)
                background = Md3.ripple(ctx, Color.TRANSPARENT, 0f)
                isClickable = true
                setOnClickListener { dlg?.dismiss(); act() }
            }
            box.addView(row, Md3.lpDp(ctx, null, 40))
        }
        dlg = android.app.AlertDialog.Builder(ctx).setView(box).create()
        dlg.setCanceledOnTouchOutside(true)
        return dlg
    }

    /**
     * 把平台对话框的默认配色拉回主题：标题缩小、按钮改紫。
     * 不改的话「取消」是系统绿，标题是 20sp，与 MD3 紫系冲突。
     */
    fun styleDialog(dlg: android.app.AlertDialog): android.app.AlertDialog = dlg.apply {
        setOnShowListener {
            // 面板底色与圆角：平台默认取主题色（这台 ROM 是偏绿的 #1D211B），
            // 与应用的 #141218 不同源。这里直接换掉，不等主题。
            window?.setBackgroundDrawable(Md3.bg(Md3.surface3, 28))
            // 标题 id 是系统的隐藏资源，只能按名字查。
            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            if (titleId != 0) findViewById<TextView>(titleId)?.apply {
                setTextColor(Md3.onSurface)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Md3.FS_LG)
            }
            for (b in intArrayOf(
                android.content.DialogInterface.BUTTON_POSITIVE,
                android.content.DialogInterface.BUTTON_NEGATIVE,
                android.content.DialogInterface.BUTTON_NEUTRAL,
            )) getButton(b)?.apply {
                setTextColor(Md3.primary)
                minimumHeight = Md3.HIT
            }
        }
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
        leading: TextView? = null,
        trailing: CharSequence? = null,
        trailingColor: Int = Md3.onSurfaceVariant,
        onTrailingClick: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val item = LinearLayout(ctx).apply {
            orientation = if (twoLine) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (twoLine) Gravity.CENTER_VERTICAL else Gravity.CENTER_VERTICAL
        }
        item.setPadding(
            Md3.dp(ctx, Md3.PAD * 0.75), Md3.dp(ctx, 3),
            Md3.dp(ctx, Md3.PAD * 0.75), Md3.dp(ctx, 3),
        )
        val bgColor = if (highlight) Md3.surface3 else Color.TRANSPARENT
        item.background = Md3.ripple(ctx, bgColor, Md3.R_ITEM.toFloat())
        if (onClick != null) {
            item.isClickable = true
            item.setOnClickListener { onClick() }
        }

        if (leading != null) {
            item.addView(leading, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = Md3.dp(ctx, 7) })
        }

        val titleView = Md3.ellipsize(Md3.text(ctx, title, Md3.FS_MD, Md3.onSurface))
        val trailingView = trailing?.let { t ->
            Md3.text(ctx, t, Md3.FS_SM, trailingColor).apply {
                contentDescription = if (t.toString() == "⋮") "更多操作" else t
                gravity = Gravity.CENTER
                setPadding(Md3.dp(ctx, 6), 0, 0, 0)
                if (onTrailingClick != null) {
                    isClickable = true
                    // 行右侧的 ⋮ 原来热区约 10 dp，手指点不中；补到 HIT。
                    minimumWidth = Md3.HIT
                    minimumHeight = Md3.HIT
                    setOnClickListener { onTrailingClick() }
                }
            }
        }
        if (twoLine) {
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(titleView, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            trailingView?.let { titleRow.addView(it) }
            item.addView(titleRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (subtitle != null) {
                item.addView(
                    Md3.ellipsize(Md3.text(ctx, subtitle, Md3.FS_SM, Md3.onSurfaceVariant)),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        } else {
            // 单行分支以前直接丢掉 subtitle —— 调用方算好了「作者 · 源名」传进来，
            // 界面上却只剩书名。现在补成「书名 —— 副标题」跟在标题末尾。
            val fullTitle = if (subtitle.isNullOrBlank()) title.toString()
                            else "$title —— $subtitle"
            item.addView(
                Md3.ellipsize(Md3.text(ctx, fullTitle, Md3.FS_MD, Md3.onSurface)),
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
     * 手机上内容列限宽居中：否则阅读器一行铺满整屏，视线很难跑。
     * 手表上 [Md3.CONTENT_MAX] 不参与限宽 —— 屏只有 189dp，限定宽只会把内容列撑出屏外。
     *
     * 滚动条显式打开并加粗：Android 默认在 2.0 density 下只有约 4px，
     * 手表上既看不见也够不着。
     */
    fun scrollBody(ctx: Context): ScrollView =
        ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(Md3.dp(ctx, Md3.PAD), 0, Md3.dp(ctx, Md3.PAD), Md3.dp(ctx, Md3.PAD))
            clipToPadding = false
            layoutParams = Md3.lpWeight(1f)
            isVerticalScrollBarEnabled = true
            // 画在内容内侧，否则会和页面边距一起被挤到屏外
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            // 表冠：ColorOS 把转动投成 ACTION_SCROLL，而 ScrollView 默认只认触摸。
            // 一格通常只有 1 个单位，直接滚几乎看不见，这里放大到 60px 一档。
            // 方向键事件不用管：ScrollView 自身就会响应（实测 DPAD_DOWN 能滚）。
            setOnGenericMotionListener { v, e ->
                if (e.action == android.view.MotionEvent.ACTION_SCROLL) {
                    val dy = e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
                    if (dy != 0f) {
                        (v as ScrollView).scrollBy(0, (-dy * 60).toInt())
                        true
                    } else false
                } else false
            }
            // 外层横向容器负责居中，内层 col 限宽
            val outer = Md3.row(ctx)
            outer.gravity = Gravity.CENTER_HORIZONTAL
            val col = Md3.column(ctx)
            // 限宽只在真的比屏窄时才生效。以前无条件给 CONTENT_MAX，
            // 手表档是个 4000dp 的「不限宽」哨兵值，落到 LinearLayout 上就是溢出屏外。
            val maxW = Md3.dp(ctx, Md3.CONTENT_MAX)
            val screenW = ctx.resources.displayMetrics.widthPixels
            col.layoutParams = if (maxW in 1 until screenW) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    width = maxW
                }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            outer.addView(col)
            addView(outer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /**
     * 取出 [scrollBody] 里真正放内容的列。
     *
     * 外面多包了一层横向容器用于居中限宽，所以不能直接 `getChildAt(0)`。
     */
    fun contentColumn(body: ScrollView): LinearLayout {
        val outer = body.getChildAt(0) as? LinearLayout ?: return LinearLayout(body.context)
        return (outer.getChildAt(0) as? LinearLayout) ?: outer
    }

    /** 空状态。 */
    fun emptyState(ctx: Context, glyph: String, message: CharSequence): LinearLayout {
        val box = Md3.column(ctx)
        box.gravity = Gravity.CENTER
        box.setPadding(Md3.dp(ctx, 12), Md3.dp(ctx, 24), Md3.dp(ctx, 12), Md3.dp(ctx, 24))
        box.addView(Md3.text(ctx, glyph, 30f * (1f + (Md3.scaleFactor() - 1f) * 0.6f), Md3.onSurfaceVariant)
            .apply {
                alpha = 0.3f
                // 容器居中只有子 View 宽度不撑满时才生效；文字自己还得居中一次，
                // 否则符号靠在左边（实测偏左 122 px）。
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                // 大字 emoji 是装饰，真正要读的是下面那句 message（P1-7）
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        val m = Md3.text(ctx, message, Md3.FS_MD, Md3.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            setPadding(0, Md3.dp(ctx, 10), 0, 0)
        }
        box.addView(m)
        return box
    }

    /** 底部导航。 */
    fun navBar(ctx: Context, items: List<Triple<String, String, Boolean>>, onSelect: ((Int) -> Unit)? = null): LinearLayout {
        val nav = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Md3.bg(Md3.surface2, 0)
        }
        items.forEachIndexed { i, (glyph, label, active) ->
            val cell = Md3.column(ctx).apply {
                gravity = Gravity.CENTER
                isClickable = onSelect != null
                onSelect?.let { cb -> setOnClickListener { cb(i) } }
            }
            val ico = Md3.text(ctx, glyph, Md3.FS_MD, if (active) Md3.onSecondaryContainer else Md3.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
            }
            ico.background = if (active) Md3.bg(Md3.secondaryContainer, 11f) else Md3.bg(Color.TRANSPARENT, 11f)
            cell.addView(ico, Md3.lpDp(ctx, 44, 22))
            cell.addView(Md3.text(ctx, label, 9f, if (active) Md3.onSecondaryContainer else Md3.onSurfaceVariant))
            nav.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        nav.layoutParams = Md3.lpDp(ctx, null, Md3.NAV)
        return nav
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

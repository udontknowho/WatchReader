package com.watchdl.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * MD3 设计令牌与组件工厂。
 *
 * 尺寸依据 OPPO Watch 3 Pro 实测（2026-10-16 定稿）：
 *   物理 378 × 496 px @ 326 ppi  →  density 2.0（320 dpi）  →  逻辑 189 × 248 dp
 * 屏高只有 248dp，MD3 标准尺寸（应用栏 56 + 搜索框 48 + 按钮 40 = 144dp）会吃掉 58%，
 * 所以全部按紧凑档取值。详见 `ui/md3-final.html`。
 */
object Md3 {

    // ── 配色（MD3 深色主题）─────────────────────────────
    val primary = 0xFFD0BCFF.toInt()
    val onPrimary = 0xFF381E72.toInt()
    val primaryContainer = 0xFF4F378B.toInt()
    val onPrimaryContainer = 0xFFEADDFF.toInt()

    val secondaryContainer = 0xFF4A4458.toInt()
    val onSecondaryContainer = 0xFFE8DEF8.toInt()

    val surface = 0xFF141218.toInt()
    val surface1 = 0xFF1D1B20.toInt()
    val surface2 = 0xFF211F26.toInt()
    val surface3 = 0xFF2B2930.toInt()
    val surface4 = 0xFF36343B.toInt()

    val onSurface = 0xFFE6E0E9.toInt()
    val onSurfaceVariant = 0xFFCAC4D0.toInt()
    val outline = 0xFF938F99.toInt()
    val outlineVariant = 0xFF49454F.toInt()

    val error = 0xFFF2B8B5.toInt()
    val ok = 0xFF7BD88F.toInt()

    // ── 尺寸（dp，真机即所见）───────────────────────────
    // ── 尺寸（dp）────────────────────────────────────────
    //
    // 基准值按手表定（OPPO Watch 3 Pro：逻辑 189 × 248 dp）。手机屏幕宽得多，
    // 照搬这些数会显得又小又挤，所以按屏宽线性放大，并给上下限。
    //
    // [configure] 在 Activity/Service 启动时调一次。没调就用基准值（等于手表档）。
    private var scale = 1f

    /**
     * 按屏幕宽度定缩放。宽屏（手机/平板）放大，窄屏（手表）保持基准。
     *
     * 手表 189dp → 1.0；手机 360~430dp → 1.9~2.3（上限 2.4）。
     */
    fun configure(ctx: Context) {
        val wDp = ctx.resources.configuration.screenWidthDp
        // 分段定档，而不是从 189dp 线性外推。
        //
        // 手表界面的尺寸是**故意压紧**的（189dp 宽要塞下搜索框+按钮+列表），
        // 直接按比例放大到手机，正文会变 18sp、按钮 79dp，明显过大。
        // 所以手机档按 Material 常见值给：正文 14~15sp、按钮 44~48dp。
        scale = when {
            wDp >= 600 -> 1.30f      // 平板/折叠屏展开
            wDp >= 400 -> 1.22f      // 大屏手机
            wDp >= 320 -> 1.15f      // 普通手机
            wDp >= 240 -> 1.05f      // 小屏手表/方表
            else -> 1f               // 圆表基准
        }
        maxContentDp = if (wDp >= 320) 420 else 4000
    }

    /** 当前缩放。调试与自检用。 */
    fun scaleFactor(): Float = scale

    private fun d(v: Number): Int = Math.round(v.toFloat() * scale)

    /** 是否大屏（手机/平板）。决定单列限宽、字号档位等。 */
    fun isWide(ctx: Context): Boolean = ctx.resources.configuration.screenWidthDp >= 320

    val PAD: Int get() = d(8)              // 页面边距
    val GAP: Int get() = d(6)              // 纵向间隔
    val TOP_BAR: Int get() = d(36)         // 应用栏
    val SEARCH: Int get() = d(34)          // 搜索框
    val BTN: Int get() = d(38)             // 按钮（手机上要够高才好点）
    val ITEM: Int get() = d(42)            // 列表项
    val TAP: Int get() = d(34)             // 点击目标基准（视觉）
    /**
     * 最小热区。MD3 要求 48 dp，但 248 dp 高的表盘放不下：顶栏本身只有 36 dp。
     * 手表档取 44 dp（Wear M3 的宽松值），手机上仍按 44。
     */
    val HIT: Int get() = d(44)
    val NAV: Int get() = d(48)             // 底部导航
    val TAB_BAR: Int get() = d(30)         // 阅读器底栏

    val R_CARD: Int get() = d(12)
    val R_ITEM: Int get() = d(10)

    // ── 字号（sp）────────────────────────────────────────
    // 同样按屏宽放大，但幅度小一些：手表上 10sp 能看清，手机要 14 左右。
    private fun f(v: Float): Float = v * (1f + (scale - 1f) * 0.9f)

    val FS_XL: Float get() = f(17f)        // 进度大数
    val FS_LG: Float get() = f(14f)        // 标题
    val FS_MD: Float get() = f(12f)        // 正文
    val FS_SM: Float get() = f(11f)        // 次要（MD3 labelSmall 下限 11sp）

    /** 正文阅读字号（阅读器专用，比 FS_MD 大）。 */
    val FS_READ: Float get() = f(13f)

    /**
     * 内容列最大宽度（dp）。手机上别铺满，否则阅读一行太长；
     * 手表上给一个很大的值，等于不限宽。
     */
    private var maxContentDp = 4000
    val CONTENT_MAX: Int get() = d(maxContentDp)

    /** dp → px。 */
    @JvmStatic
    fun dp(ctx: Context, v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    @JvmStatic
    fun sp(v: Number): Float = v.toFloat()

    /** 圆角实心背景。 */
    @JvmStatic
    fun bg(color: Int, radiusDp: Number, strokeColor: Int? = null, strokeDp: Number = 1): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = radiusDp.toFloat()
        if (strokeColor != null) d.setStroke(strokeDp.toInt().coerceAtLeast(1), strokeColor)
        return d
    }

    /** 带水波纹的可点击背景（MD3 交互反馈）。 */
    @JvmStatic
    fun ripple(ctx: Context, color: Int, radiusDp: Number, strokeColor: Int? = null): RippleDrawable {
        val content = bg(color, radiusDp, strokeColor)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = radiusDp.toFloat()
        }
        val rippleColor = ColorStateList.valueOf(0x22FFFFFF)
        return RippleDrawable(rippleColor, content, mask)
    }

    /** 统一工厂：纯文本控件。 */
    @JvmStatic
    fun text(
        ctx: Context,
        content: CharSequence,
        sizeSp: Number = FS_MD,
        color: Int = onSurface,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = content
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        includeFontPadding = false
    }

    /** 单行截断。 */
    @JvmStatic
    fun ellipsize(tv: TextView): TextView = tv.apply {
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** 应用栏图标按钮。[desc] 给螢幕阅读器用（P1-7）；不传就按字形自动推。 */
    @JvmStatic
    fun iconButton(
        ctx: Context,
        glyph: String,
        onClick: (() -> Unit)? = null,
        desc: String? = null,
    ): TextView =
        text(ctx, glyph, FS_MD + 1, onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            background = ripple(ctx, Color.TRANSPARENT, TAP / 2)
            // 顶栏只有 36 dp 高，纵向上不可能给到 48 dp；横向用 padding 补到 44 dp。
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
            minimumWidth = HIT
            minimumHeight = TAP
            // emoji 字形 TalkBack 读作「霙轮符号」，听不懂。
            // 没显式给描述时按常见字形兜底，总比念符号强。
            contentDescription = desc ?: GLYPH_NAMES[glyph] ?: "按钮"
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

    /** 常见字形 → 可读名字。没收录的返回 null，由调用方给。 */
    private val GLYPH_NAMES = mapOf(
        "⚙" to "书源设置", "⋮" to "更多", "⤓" to "导出", "☁" to "在线书源",
        "🔍" to "搜索", "⟳" to "重试", "←" to "返回", "↓" to "刷新", "✓" to "已导入",
        "▤" to "书架", "⚠" to "警告", "🗑" to "删除",
    )

    /** 按钮。variant: filled / tonal / outline / text。 */
    @JvmStatic
    fun button(
        ctx: Context,
        label: CharSequence,
        variant: String = "filled",
        onClick: (() -> Unit)? = null,
    ): TextView {
        val (bgColor, fgColor, stroke) = when (variant) {
            "filled" -> Triple(primary, onPrimary, null)
            "tonal" -> Triple(secondaryContainer, onSecondaryContainer, null)
            "outline" -> Triple(Color.TRANSPARENT, primary, outline)
            else -> Triple(Color.TRANSPARENT, primary, null)
        }
        return text(ctx, label, FS_MD, fgColor, bold = true).apply {
            gravity = Gravity.CENTER
            background = ripple(ctx, bgColor, BTN / 2f, stroke)
            // 视觉高度仍是 BTN，热区补到 HIT（原来 34 dp 直接越界）。
            minimumHeight = HIT
            isClickable = onClick != null
            onClick?.let { setOnClickListener { it() } }
        }
    }

    /** 布局参数快捷方式。 */
    @JvmStatic
    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h)

    /** 撑满剩余高度。 */
    @JvmStatic
    fun lpWeight(weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { this.weight = weight }

    /** 固定 dp 尺寸的布局参数。 */
    @JvmStatic
    fun lpDp(ctx: Context, wDp: Number?, hDp: Number): LinearLayout.LayoutParams {
        val w = if (wDp == null) ViewGroup.LayoutParams.MATCH_PARENT else dp(ctx, wDp)
        return LinearLayout.LayoutParams(w, dp(ctx, hDp))
    }

    /** 垂直容器。 */
    @JvmStatic
    fun column(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    /** 水平容器。 */
    @JvmStatic
    fun row(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
}

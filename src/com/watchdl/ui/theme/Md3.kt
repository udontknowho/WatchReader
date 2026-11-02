package com.watchdl.ui.theme

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
import com.watchdl.core.Palette

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
    //
    // 全部可变：主题切换走 [applyPalette]，把 [Palette] 的 16 个色角色灌进来。
    // 改的是单例上的值，所以只影响**之后新建**的视图 —— 换主题后要重画当前屏，
    // 这就是 `MainActivity.pickTheme` 里再调一次 `showSettings()` 的原因。
    var primary = 0xFFD0BCFF.toInt(); private set
    var onPrimary = 0xFF381E72.toInt(); private set
    var primaryContainer = 0xFF4F378B.toInt(); private set
    var onPrimaryContainer = 0xFFEADDFF.toInt(); private set

    var secondaryContainer = 0xFF4A4458.toInt(); private set
    var onSecondaryContainer = 0xFFE8DEF8.toInt(); private set

    var surface = 0xFF141218.toInt(); private set
    var surface1 = 0xFF1D1B20.toInt(); private set
    var surface2 = 0xFF211F26.toInt(); private set
    var surface3 = 0xFF2B2930.toInt(); private set
    var surface4 = 0xFF36343B.toInt(); private set

    var onSurface = 0xFFE6E0E9.toInt(); private set
    var onSurfaceVariant = 0xFFCAC4D0.toInt(); private set
    var outline = 0xFF938F99.toInt(); private set
    var outlineVariant = 0xFF49454F.toInt(); private set

    var error = 0xFFF2B8B5.toInt(); private set

    /** 成功/正常态的绿。语义色不跟主题走：换主题后「已下载」还是得是绿的。 */
    val ok = 0xFF7BD88F.toInt()

    /** 占位封面色：按书名哈希固定选一个容器色。同一本书颜色稳定。 */
    fun nameColor(name: String): Int {
        val palette = intArrayOf(primaryContainer, secondaryContainer, surface4, outlineVariant)
        return palette[Math.floorMod(name.hashCode(), palette.size)]
    }

    /** 当前主题，供设置页标选中态。 */
    var palette: Palette = Palette.all[0]; private set

    /** 灌一套配色。调用方负责落盘与重画。 */
    fun applyPalette(p: Palette) {
        palette = p
        primary = p.primary
        onPrimary = p.onPrimary
        primaryContainer = p.primaryContainer
        onPrimaryContainer = p.onPrimaryContainer
        secondaryContainer = p.secondaryContainer
        onSecondaryContainer = p.onSecondaryContainer
        surface = p.surface
        surface1 = p.surface1
        surface2 = p.surface2
        surface3 = p.surface3
        surface4 = p.surface4
        onSurface = p.onSurface
        onSurfaceVariant = p.onSurfaceVariant
        outline = p.outline
        outlineVariant = p.outlineVariant
        error = p.error
    }

    // ── 尺寸（dp）—— 两张表，启动时按屏宽选 ──────────
    //
    // 手表档 < 320dp：OPPO Watch 3 Pro 是 189 × 248 dp，导航栏取 64、页签 40，
    // 否则 80 + 48 就吃掉半屏（实测确实如此）。
    // 手机档 ≥ 320dp：直接用 M3 规范原值 —— 导航 80（NavigationBarTokens.
    // ContainerHeight）、页签 48（最小触控目标）。
    // 字阶两档本来就一致（M3 基线型），不分叉。
    var PAD = 16             // 页面边距（≥600dp 宽屏提到 24）
    var GAP = 8              // 纵向间隔
    var TOP_BAR = 64         // 小号应用栏
    var SEARCH = 56          // 搜索栏（M3 search bar）
    var BTN = 40             // 按钮（M3 filled button 高 40）
    var ITEM = 56            // 列表项：单行
    var ITEM_TALL = 72       // 列表项：双行
    var TAP = 48             // 图标按钮触控槽
    var HIT = 48             // 最小热区，M3 规范值
    var NAV = 64             // 底部导航（手机档 80）
    var NAV_IND_W = 48       // 选中指示器宽（手表 48，手机 64）
    var NAV_IND_H = 32       // 选中指示器高
    var TAB_BAR = 48         // 阅读器底栏
    var TAB_H = 40           // 页签行高（手机档 48）

    /**
     * 按屏宽选版面表。必须在建界面前调一次；屏幕尺寸变化时（onConfigurationChanged）重调。
     *
     * 用 screenWidthDp 而不是 smallestScreenWidthDp：手表横过来还是 248dp 宽，
     * 该拿的是当前可用宽度。
     */
    fun load(ctx: Context) {
        val w = ctx.resources.configuration.screenWidthDp
        if (w >= 320) {
            NAV = 80
            TAB_H = 48
            NAV_IND_W = 64
            PAD = if (w >= 600) 24 else 16
        } else {
            NAV = 64
            TAB_H = 40
            NAV_IND_W = 48
            PAD = 16
        }
    }

    // 圆角取 M3 shape scale：extra-small 4 / small 8 / medium 12。
    val R_CARD = 12          // 分组卡 / 大块容器（medium）
    val R_ITEM = 12          // 列表卡 / 菜单项（medium）
    val R_GRID = 8           // 网格项 / 角标标签（small）

    // 图标：M3 顶栏动作、导航项、列表前导都是 24dp。
    val ICON_ACTION = 24
    val ICON_LEAD = 24

    // ── 字阶（sp）—— M3 基线型字阶原值 ──────────────
    //
    //   titleLarge 22 / titleMedium 16 / titleSmall 14
    //   bodyLarge 16 / bodyMedium 14 / bodySmall 12
    //   labelLarge 14 / labelMedium 12 / labelSmall 11
    //
    // 不再整体压两档：压完落在 11–17sp，表盘塞得下，但整屏比 legado 细一号。
    // 保留旧名做别名（FS_LG/FS_MD/FS_SM），调用点不必逐个改。
    val FS_XL = 22f        // titleLarge：页面标题、进度大数
    val FS_LG = 22f        // titleLarge
    val FS_MD = 14f        // bodyMedium
    val FS_SM = 12f        // labelMedium
    val FS_LABEL = 12f     // labelMedium（导航标签）
    val FS_BODY_LARGE = 16f

    /** 正文阅读字号（阅读器专用，取 bodyLarge）。 */
    val FS_READ = 16f

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

    /**
     * 应用栏图标按钮。[desc] 给螢幕阅读器用（P1-7）；不传就按字形自动推。
     *
     * 传入的字形能对上 [Icons] 就画矢量图形，对不上才退回文字字符 ——
     * 这样新加的图标不必再改所有调用点。
     */
    @JvmStatic
    fun iconButton(
        ctx: Context,
        glyph: String,
        onClick: (() -> Unit)? = null,
        desc: String? = null,
    ): View {
        val icon = Icons.name(glyph)
        if (icon != null) {
            val v = Icons.view(ctx, icon, onSurfaceVariant, ICON_ACTION)
            // 顶栏只有 TOP_BAR 高，热区横向补到 HIT，纵向就到顶栏高为止。
            v.minimumWidth = HIT
            v.minimumHeight = TOP_BAR
            v.setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
            v.contentDescription = desc ?: ICON_NAMES[icon] ?: "按钮"
            if (onClick != null) {
                v.isClickable = true
                v.background = ripple(ctx, Color.TRANSPARENT, TAP / 2)
                v.setOnClickListener { onClick() }
            }
            return v
        }
        return text(ctx, glyph, FS_MD + 1, onSurfaceVariant).apply {
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
    }

    /** 图标名 → 可读名字。没收录的读作「按钮」，也比念符号强。 */
    private val ICON_NAMES = mapOf(
        "back" to "返回", "menu" to "菜单", "settings" to "设置", "search" to "搜索",
        "refresh" to "刷新", "download" to "下载", "more" to "更多", "close" to "关闭",
        "delete" to "删除", "list" to "列表视图", "grid" to "封面视图", "shelf" to "书架",
        "explore" to "发现", "mine" to "我的", "bookmark" to "书签", "history" to "阅读记录",
        "swap" to "换源", "plus" to "新增", "play" to "开始", "stop" to "停止",
        "warning" to "警告", "check" to "已完成", "cloud" to "云端",
    )

    /** 常见字形 → 可读名字。没收录的返回 null，由调用方给。 */
    private val GLYPH_NAMES = mapOf(
        "⚙" to "设置", "⋮" to "更多", "⤓" to "导出", "☁" to "在线书源",
        "🔍" to "搜索", "⟳" to "重试", "←" to "返回", "↓" to "刷新", "✓" to "已导入",
        "▤" to "列表", "⚠" to "警告", "🗑" to "删除",
    )

    /** 方形图标按钮。尺寸同 [button]，内容是一枚图标（导出/续下这类只有图形的动作）。 */
    @JvmStatic
    fun squareButton(
        ctx: Context,
        glyph: String,
        variant: String = "tonal",
        desc: String? = null,
        onClick: (() -> Unit)? = null,
    ): View {
        val bgColor = when (variant) {
            "filled" -> primary
            "tonal" -> secondaryContainer
            else -> Color.TRANSPARENT
        }
        val fg = when (variant) {
            "filled" -> onPrimary
            "tonal" -> onSecondaryContainer
            else -> primary
        }
        val icon = Icons.name(glyph)
        val v: View = if (icon != null) {
            Icons.view(ctx, icon, fg, ICON_ACTION)
        } else {
            text(ctx, glyph, FS_MD + 1, fg).apply { gravity = Gravity.CENTER }
        }
        v.background = ripple(ctx, bgColor, BTN / 2)
        v.contentDescription = desc ?: icon?.let { ICON_NAMES[it] } ?: glyph
        v.minimumHeight = HIT
        if (onClick != null) {
            v.isClickable = true
            v.setOnClickListener { onClick() }
        }
        return v
    }

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
            // M3 按钮规范：左右 16dp（text 变体是 12dp，这里统一取大的），
            // 最小宽 64dp。原来左右零内边距，长标签「下载缺失章节（3735 章）」
            // 直接顶到圆角边缘 —— 用户判词「那个特别细」的一半来源就是它。
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
            minWidth = dp(ctx, 64)
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

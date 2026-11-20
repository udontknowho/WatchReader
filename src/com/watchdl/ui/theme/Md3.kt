package com.watchdl.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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

    // MD3 色角色补齐：容器五档、色调叠加与反色组。
    var surfaceTint = 0xFFD0BCFF.toInt(); private set
    var surfaceContainerLowest = 0xFF0F0D13.toInt(); private set
    var surfaceContainerLow = 0xFF1D1B20.toInt(); private set
    var surfaceContainer = 0xFF211F26.toInt(); private set
    var surfaceContainerHigh = 0xFF2B2930.toInt(); private set
    var surfaceContainerHighest = 0xFF36343B.toInt(); private set
    var inverseSurface = 0xFFE6E0E9.toInt(); private set
    var inverseOnSurface = 0xFF322F35.toInt(); private set
    var inversePrimary = 0xFF6750A4.toInt(); private set

    /**
     * 成功/正常态的绿。语义色不跟主题走：换主题后「已下载」还是得是绿的，
     * 只是深底要浅绿、浅底要深绿才看得清。判据取 [Palette.dark] 而不是 id：
     * 深色配色现在有两套（夜、米·夜），写 id 必漏一套。
     */
    val ok: Int get() = if (palette.dark) 0xFF7BD88F.toInt() else 0xFF286838.toInt()

    /** 占位封面色：按书名哈希固定选一个容器色。同一本书颜色稳定。 */
    fun nameColor(name: String): Int {
        val palette = intArrayOf(primaryContainer, secondaryContainer, surface4, outlineVariant)
        return palette[Math.floorMod(name.hashCode(), palette.size)]
    }

    /** 当前主题，供设置页标选中态。 */
    var palette: Palette = Palette.all[0]; private set

    /**
     * 当前几何是否走米族（MIUIX）那套。由 [load] 写入，随主题切换重算。
     *
     * 只影响形状（边距、圆角、底栏高度），颜色一律由 [applyPalette] 决定。
     * 因此 [load] 必须排在 [applyPalette] 之后调用：这里靠 palette.id 反查族。
     */
    var miuixGeo = false; private set

    /** 手机/平板档为 true，手表档为 false。由 [load] 写入，底栏据此换形态。 */
    var phoneTier = false; private set

    /** 灌一套配色。调用方负责落盘与重画。 */
    fun applyPalette(selected: Palette) {
        // 直接采用传入的配色。以前这里丢弃参数、按硬编码常量重建日/夜两套，
        // 与 [Palette.all] 早已漂移：设置页色点画的是派生值，屏上是另一套，
        // PaletteTest 验的对比度也不是真跑的那一套。真源只留 [Palette.all] 一处。
        val p = selected
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
        surfaceTint = p.surfaceTint
        surfaceContainerLowest = p.surfaceContainerLowest
        surfaceContainerLow = p.surfaceContainerLow
        surfaceContainer = p.surfaceContainer
        surfaceContainerHigh = p.surfaceContainerHigh
        surfaceContainerHighest = p.surfaceContainerHighest
        inverseSurface = p.inverseSurface
        inverseOnSurface = p.inverseOnSurface
        inversePrimary = p.inversePrimary
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
     * 主屏顶栏高度。用户判词（2026-11-05）：顶栏太大，压到 25 dp。
     *
     * 与 [TAB_H] 分开：TAB_H 是「可选项」行（书源/书签/记录/替换净化的选择条），
     * 那些行要留 36 dp 的图标槽，压下来点不准；这条只放标题与一两枚动作图标。
     */
    var HEADER_H = 25

    /** 底栏指示点条高度。8 dp 是用户定的：再高就白吃正文，再低点按不到。 */
    var NAV_DOT_H = 8

    /** 指示点直径。3 dp 是实测可用下限，2 dp 在这块屏上圆角看不出来。 */
    var NAV_DOT = 3f

    /** 底栏指示点间距。 */
    var NAV_DOT_GAP = 5f

    /**
     * 按屏宽选版面表。必须在建界面前调一次；屏幕尺寸变化时（onConfigurationChanged）重调。
     *
     * 用 screenWidthDp 而不是 smallestScreenWidthDp：手表横过来还是 248dp 宽，
     * 该拿的是当前可用宽度。
     */
    fun load(ctx: Context) {
        // 圆角令牌按 dp 记，GradientDrawable 收 px。密度只在 [bg]/[ripple] 里换算一次，
        // 调用点一律写 dp —— 以前令牌直接当 px 塞进去，2.0 密度下所有圆角只有标称值一半。
        densityScale = ctx.resources.displayMetrics.density
        val w = ctx.resources.configuration.screenWidthDp
        // 两轴正交：颜色 = 族 × 亮度（[applyPalette] 灌），形状 = 族 × 档位（这里选）。
        // 亮度不参与形状，所以四套配色只有两套几何。
        // 手表档不认族：189×248 上两套几何挤不出差别，且手表是逐屏调过的。
        miuixGeo = w >= 320 && Palette.family(palette.id) == Palette.FAMILY_MIUIX
        phoneTier = w >= 320
        if (w >= 320) {
            GAP = 8; BTN = 40
            FS_XL = 22f; FS_LG = 16f; FS_MD = 14f; FS_SM = 12f; FS_LABEL = 12f
            TAB_H = 48
            HEADER_H = 34
            NAV_DOT_H = 12
            NAV_DOT = 4f
            if (miuixGeo) {
                // 米族（MIUIX / HyperOS）手机档：密度高、圆角大、底栏收窄成 64。
                PAD = if (w >= 600) 24 else 12
                TOP_BAR = 56; SEARCH = 40
                ITEM = 56; ITEM_TALL = 60; TAP = 44; HIT = 44
                NAV = 64; NAV_IND_W = 56
                R_XS = 6; R_GRID = 12; R_CARD = 16; R_ITEM = 14; R_LG = 20; R_XL = 32
            } else {
                // 默认族（MD3）手机档：M3 规范原值 —— 应用栏 64、搜索 56、双行列表 72，
                // 触控目标 48（M3 硬下限，原值 40 不符合规范）。
                PAD = if (w >= 600) 24 else 16
                TOP_BAR = 64; SEARCH = 56
                ITEM = 56; ITEM_TALL = 72; TAP = 48; HIT = 48
                NAV = 80; NAV_IND_W = 64
                R_XS = 4; R_GRID = 8; R_CARD = 12; R_ITEM = 12; R_LG = 16; R_XL = 28
            }
        } else {
            // 手表档按 Miuix（HyperOS）缩放：原值 56dp 行高 ×0.78 → 44，
            // 内边距 16dp ×0.75 → 12，行内间距 8dp → 4。
            // 圆角形状全局走超椭圆（见 [squirclePath]），这里只管数值。
            GAP = 4; TOP_BAR = 36; SEARCH = 32; BTN = 32
            ITEM = 44; ITEM_TALL = 52; TAP = 40; HIT = 40
            FS_XL = 22f; FS_LG = 16f; FS_MD = 13f; FS_SM = 10f; FS_LABEL = 10f
            NAV = 28
            TAB_H = 32
            HEADER_H = 30
            NAV_DOT_H = 8
            NAV_DOT = 3f
            NAV_IND_W = 48
            PAD = 12
            R_XS = 4; R_GRID = 8; R_CARD = 12; R_ITEM = 12; R_LG = 16; R_XL = 28
        }
    }

    // 圆角取 M3 shape scale：extra-small 4 / small 8 / medium 12 / large 16 / extra-large 28。
    // 数值随族变（米族用 MIUIX 的 6/12/16/14/20/32），所以是 var —— 由 [load] 写入。
    // 默认值保持 M3 那套：手表档与 MD3 档都用它。
    var R_XS = 4             // extraSmall：角标、小标签
        private set
    var R_GRID = 8           // 网格项 / 角标标签（small）
        private set
    var R_CARD = 12          // 分组卡 / 大块容器（medium）
        private set
    var R_ITEM = 12          // 列表卡 / 菜单项（medium）
        private set
    var R_LG = 16            // large：抽屉、侧栏容器
        private set
    var R_XL = 28            // extraLarge：对话框、全屏 Sheet
        private set

    // 图标：M3 顶栏动作、导航项、列表前导都是 24dp。
    val ICON_ACTION = 24
    val ICON_LEAD = 24

    // ── 字阶（sp）—— M3 基线型字阶原值 ──────────────
    //
    //   titleLarge 22 / titleMedium 16 / titleSmall 14
    //   bodyLarge 16 / bodyMedium 14 / bodySmall 12
    //   labelLarge 14 / labelMedium 12 / labelSmall 11
    //
    // 手表使用紧凑字阶，宽屏使用常规字阶；正文阅读字号保持独立。
    var FS_XL = 22f        // titleLarge：页面标题、进度大数
    var FS_LG = 16f        // titleMedium：分组标题、顶栏标题、对话框标题
    var FS_MD = 14f        // bodyMedium
    val FS_TITLE_SM = 14f  // titleSmall
    var FS_SM = 12f        // labelMedium
    var FS_LABEL = 12f     // labelMedium（导航标签）
    val FS_BODY_LARGE = 16f
    val FS_BODY_SM = 12f   // bodySmall

    /** 正文阅读字号（阅读器专用，取 bodyLarge）。 */
    val FS_READ = 16f

    /** dp → px。 */
    @JvmStatic
    fun dp(ctx: Context, v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    @JvmStatic
    fun sp(v: Number): Float = v.toFloat()

    /** 屏幕密度，由 [load] 灌入。[bg]/[ripple] 用它把 dp 圆角换成 px。 */
    private var densityScale = 2f

    /**
     * 圆角实心背景。[radiusDp] 是 dp，内部换算成 px。
     *
     * 返回 [Drawable] 而不是 [GradientDrawable]：圆角走超椭圆时要用自定义形状，
     * GradientDrawable 只有圆弧一种。调用点都只当 background 用，没有类型依赖。
     */
    @JvmStatic
    fun bg(color: Int, radiusDp: Number, strokeColor: Int? = null, strokeDp: Number = 1): Drawable =
        bgPx(color, radiusDp.toFloat() * densityScale, strokeColor, strokeDp)

    /**
     * 超椭圆圆角的控制点系数。
     *
     * 三次贝塞尔逼近 90° 圆弧时，控制点距离取 0.5523r 才是正圆；取更大值，曲线在
     * 角内更"扁"、与直边的衔接更缓，也就是 HyperOS 那种"鼓"的观感。0.68 是目视
     * 调出来的档位：再大（>0.75）会显出方肩，再小就看不出与圆弧的差别。
     */
    private const val SQUIRCLE_K = 0.68f

    /** 已经是 px 的圆角背景。只给 [ripple] 复用，避免二次换算。 */
    private fun bgPx(color: Int, radiusPx: Float, strokeColor: Int?, strokeDp: Number): Drawable {
        if (radiusPx <= 0f) return flatRect(color, strokeColor, strokeDp)
        val d = SquircleDrawable(radiusPx, color, strokeColor, strokeDp.toInt().coerceAtLeast(1))
        return d
    }

    /** 直角块。半径 0 的退化情形，避免为它构造 Path。 */
    private fun flatRect(color: Int, strokeColor: Int?, strokeDp: Number): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            if (strokeColor != null) setStroke(strokeDp.toInt().coerceAtLeast(1), strokeColor)
        }

    /**
     * 固定尺寸的圆角色点。
     *
     * 给 `setCompoundDrawables` 用：那条路径不看边界，只读 intrinsic size，
     * 而 [Drawable] 没有 `setSize`（那是 [GradientDrawable] 自己的）。
     */
    @JvmStatic
    fun dot(ctx: Context, color: Int, sizeDp: Number, radiusDp: Number = 4): Drawable {
        val px = dp(ctx, sizeDp)
        return SquircleDrawable(radiusDp.toFloat() * densityScale, color, null, 1)
            .also { it.setIntrinsicSize(px) }
    }

    /**
     * 超椭圆圆角矩形。
     *
     * 每个角一段三次贝塞尔，控制点沿两条直边各偏 [SQUIRCLE_K] × 半径。
     * 半径按短边一半截断，保证退化尺寸（进度条、发丝线）不出现负半径。
     */
    private fun squirclePath(l: Float, t: Float, r: Float, b: Float, rad: Float): Path {
        val path = Path()
        val w = r - l
        val h = b - t
        if (w <= 0f || h <= 0f) return path
        val R = rad.coerceAtMost(minOf(w, h) / 2f)
        if (R <= 0f) {
            path.addRect(l, t, r, b, Path.Direction.CW)
            return path
        }
        val c = R * SQUIRCLE_K
        path.moveTo(l + R, t)
        path.lineTo(r - R, t)
        path.cubicTo(r - R + c, t, r, t + R - c, r, t + R)
        path.lineTo(r, b - R)
        path.cubicTo(r, b - R + c, r - R + c, b, r - R, b)
        path.lineTo(l + R, b)
        path.cubicTo(l + R - c, b, l, b - R + c, l, b - R)
        path.lineTo(l, t + R)
        path.cubicTo(l, t + R - c, l + R - c, t, l + R, t)
        path.close()
        return path
    }

    /** 超椭圆圆角矩形背景。边界变了才重建 Path，滚动时不做无谓分配。 */
    private class SquircleDrawable(
        private val radiusPx: Float,
        color: Int,
        strokeColor: Int?,
        private val strokePx: Int,
    ) : Drawable() {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        private val stroke = strokeColor?.let {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                setColor(it)
                style = Paint.Style.STROKE
                strokeWidth = strokePx.toFloat()
            }
        }
        private val path = Path()
        private var built = false

        /** 固定尺寸（px）。compound drawable 靠 intrinsic size 定大小，-1 表示跟边界走。 */
        private var intrinsicPx = -1

        fun setIntrinsicSize(px: Int) {
            intrinsicPx = px
            invalidateSelf()
        }

        override fun getIntrinsicWidth(): Int = if (intrinsicPx > 0) intrinsicPx else super.getIntrinsicWidth()

        override fun getIntrinsicHeight(): Int = if (intrinsicPx > 0) intrinsicPx else super.getIntrinsicHeight()

        override fun onBoundsChange(bounds: Rect) {
            built = false
        }

        private fun rebuild() {
            val b = bounds
            // 描边居中画在路径上，外半宽会溢出边界；整体内缩半线宽把它收回框内。
            val inset = if (stroke != null) strokePx / 2f else 0f
            val p = squirclePath(
                b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, radiusPx,
            )
            path.set(p)
            built = true
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            if (!built) rebuild()
            canvas.drawPath(path, fill)
            stroke?.let { canvas.drawPath(path, it) }
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            stroke?.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
            stroke?.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * 色调叠加：把 [surfaceTint] 按比例混进容器色，给浮层一层 elevation 观感。
     *
     * MD3 1.2 起优先用 surfaceContainer 各档代替叠加，这里只在真正浮起的
     * 弹层上补很轻的一层（4–5%），两套机制同时存在也不至于发灰。
     */
    @JvmStatic
    fun tonal(base: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val b = (base shr shift) and 0xFF
            val t = (surfaceTint shr shift) and 0xFF
            return (b + (t - b) * a).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** 带水波纹的可点击背景（MD3 交互反馈）。 */
    @JvmStatic
    fun ripple(ctx: Context, color: Int, radiusDp: Number, strokeColor: Int? = null): RippleDrawable {
        val px = radiusDp.toFloat() * densityScale
        val content = bgPx(color, px, strokeColor, 1)
        // 遮罩跟内容同形状：圆弧遮罩配超椭圆内容，水波纹会在四角露出方角。
        val mask: Drawable = if (px <= 0f) {
            flatRect(Color.WHITE, null, 1)
        } else {
            SquircleDrawable(px, Color.WHITE, null, 1)
        }
        val rippleColor = ColorStateList.valueOf((onSurface and 0x00FFFFFF) or 0x22000000)
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
        if (bold) setTypeface(Typeface.create(typeface, 500, false))
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
        size: Int = ICON_ACTION,
    ): View {
        val icon = Icons.name(glyph)
        if (icon != null) {
            val v = Icons.view(ctx, icon, onSurfaceVariant, size)
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
            setPadding(dp(ctx, GAP + 2), 0, dp(ctx, GAP + 2), 0)
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

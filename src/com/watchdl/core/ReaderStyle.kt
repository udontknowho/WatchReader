package com.watchdl.core

/**
 * 阅读页排版与查找的换算。
 *
 * 纯算术，不碰 Android 图形类 —— 手表上每加一个滑杆就要多占一行，
 * 档位换算一旦写进界面代码就没法自检，所以全部搬到这里。
 */
object ReaderStyle {

    /**
     * 一屏滚动的基准时长（ms）。
     *
     * legado 的手机基准是 360 ms（`ReaderPageTurnSpeed.kt:41`），照搬到 248 dp 的表屏
     * 上就显得拖：一屏才十来行，眼扫完动画还在跑。240 ms 是实测“跟手但不跳”的值，
     * 与覆盖翻页的同档 220 ms（`COVER_MS`）接近。
     */
    const val PAGE_BASE_MS = 240L

    // ── 亮度：legado 是 0–100 滑杆，手表屏只够三档 ──────────
    const val BRIGHT_DIM = 0.35f
    const val BRIGHT_MID = 0.6f
    const val BRIGHT_FULL = 1.0f

    /** 亮度档对应的窗口亮度。越界的档按最近一档处理。 */
    fun brightValue(tier: Int): Float = when (tier.coerceIn(0, 2)) {
        0 -> BRIGHT_DIM
        2 -> BRIGHT_FULL
        else -> BRIGHT_MID
    }

    /** 连续亮度（设置面板的滑杆）夹在可读范围：再暗就看不清字，再亮就刺眼。 */
    fun brightClamp(v: Float): Float = v.coerceIn(0.15f, 1f)

    /**
     * 亮度值落回档位。
     *
     * 负值来自系统默认（-1，表示跟随系统亮度），落回“中”。
     * 档位边界取相邻两档的中点，读回来的值原样再存也不会漂。
     */
    fun brightTier(value: Float): Int = when {
        value <= 0f -> 1
        value <= 0.45f -> 0
        value < 0.85f -> 1
        else -> 2
    }

    // ── 段距：legado 是滑杆，手表取三档 ────────────────────
    /** 段距额外行高占“一行”的比例。0 / 半行 / 一行。 */
    fun paraRatio(level: Int): Float = when (level.coerceIn(0, 2)) {
        1 -> 0.5f
        2 -> 1f
        else -> 0f
    }

    /**
     * 段距的额外像素。
     *
     * 一行的像素 = 字号 sp × 行距倍数 × 密度；sp 与 dp 都按 density 换算，
     * 所以这里直接乘密度，不做二次缩放。
     */
    fun paraExtraPx(level: Int, sizeSp: Float, spacingMult: Float, density: Float): Int {
        val ratio = paraRatio(level)
        if (ratio <= 0f || density <= 0f) return 0
        return (sizeSp * spacingMult * density * ratio).toInt().coerceAtLeast(0)
    }

    /**
     * 段落结尾的字符下标，最多 [limit] 条。
     *
     * 正文是一个 TextView，段距只能靠段末那个换行符上的行高 span 实现，
     * 所以这里只给换行符位置。末段没有换行符就不加段距 —— 章末本来就不需要。
     */
    fun paraEnds(text: CharSequence, limit: Int = 2000): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < text.length && out.size < limit) {
            if (text[i] == '\n') out.add(i)
            i++
        }
        return out.toIntArray()
    }

    // ── 首行缩进：legado 是 0–4 字滑杆，手表取 0 / 1 / 2 字 ──────────
    /** 缩进档对应的全角空格个数。 */
    fun indentCount(level: Int): Int = level.coerceIn(0, 2)

    /** 首行缩进：每个非空段落前加 [level] 个字宽的全角空格。legado 叫“首行缩进 N 字”。 */
    fun indent(text: String, level: Int = 2): String {
        val n = indentCount(level)
        if (text.isEmpty() || n == 0) return text
        val pad = "\u3000".repeat(n)
        return text.split('\n').joinToString("\n") { p -> if (p.isBlank()) p else pad + p }
    }

    // ── 字距：legado 是 -0.5–0.5 em 滑杆，手表给 -0.05–0.10 的可用区间 ──
    /** 字距（em）夹在可用区间：正文汉字收到 -0.05 em 已明显挤，放到 0.10 em 已显碎。 */
    fun letterEm(v: Float): Float = v.coerceIn(-0.05f, 0.10f)

    // ── 字重：legado 是连续滑杆，手表取三档（Android 只保证 400/500/700 有字形） ──
    /** 字重档对应的 Typeface 权重数值。 */
    fun weightValue(level: Int): Int = when (level.coerceIn(0, 2)) {
        1 -> 500
        2 -> 700
        else -> 400
    }

    // ── 阅读边距：正文离表壳圆弧留多少 ──────────────────
    /** 边距档对应的 dp。手表屏 189 dp 宽，24 dp 已是极限。 */
    fun marginDp(level: Int): Int = when (level.coerceIn(0, 2)) {
        0 -> 8
        2 -> 24
        else -> 16
    }

    /** 标题边距档对应的 dp（标题与正文之间的距离）。 */
    fun titleGapDp(level: Int): Int = level.coerceIn(0, 3) * 4

    // ── 章内查找：定位与计数 ───────────────────────────────
    /**
     * 命中处应滚到的 y。
     *
     * [textTop] 是正文在滚动内容里的顶边，[padTop] 是正文自身的内边距，
     * 两者相加才是行顶的屏幕坐标；结果夹在可滚范围内，免得上一次读到的
     * 位置比新章更长时滚过头。
     */
    fun scrollTarget(textTop: Int, padTop: Int, lineTop: Int, maxScroll: Int): Int =
        (textTop + padTop + lineTop).coerceIn(0, maxScroll.coerceAtLeast(0))

    // ── 点击翻屏：一屏步进 ─────────────────────────────────
    /**
     * 「下一屏」该把正文的第几行顶到屏首。
     *
     * legado 的滚动模式按行分页，步距取「下一屏首行的 top」，落在上一屏最后一行
     * 的下一行（`feature/reader/core/transition/ReaderScrollState.kt:64-69`），
     * 效果是新一屏从上一屏最后一行开始，保留一行重叠，读者不丢行。
     * 直接「滚一屏高」不行：屏高通常不是行高的整数倍，会把一行切成两半顶在最上面。
     */
    fun pageFirstLine(first: Int, linesPerPage: Int, lineCount: Int, forward: Boolean): Int {
        if (lineCount <= 0) return 0
        val keep = (linesPerPage - 1).coerceAtLeast(1)
        val raw = if (forward) first + keep else first - keep
        return raw.coerceIn(0, lineCount - 1)
    }

    /**
     * 一屏滚动要花多少毫秒。
     *
     * 照 legado 的「基准 × 距离 / 视口」（`ReaderScrollState.kt:83-91`，
     * `core/transition/ReaderPageTurnSpeed.kt:41`）。基准从 legado 的 360 ms 降到
     * [PAGE_BASE_MS]：表屏只有 248 dp，一屏十来行，眼扫完了动画还在跑就显得拖。
     * 下限 1 ms：距离为 0 时不能让动画库收到 0 时长。
     */
    fun pageDuration(distancePx: Int, viewportPx: Int, base: Long = PAGE_BASE_MS): Long {
        if (viewportPx <= 0) return 1L
        return (base * kotlin.math.abs(distancePx) / viewportPx).coerceIn(1L, base)
    }

    /**
     * “上一处 / 下一处”的目标下标，首尾循环。
     *
     * [current] 为 -1 表示还没有当前位置：往下走从 0 开始，往上走从末尾开始。
     * [count] 为 0 时返回 -1，界面据此关掉翻页。
     */
    fun step(current: Int, delta: Int, count: Int): Int {
        if (count <= 0) return -1
        val from = if (current < 0) if (delta >= 0) -1 else 0 else current
        return ((from + delta) % count + count) % count
    }

    // ── 主题底色：legado 的 readConfig 样本 ──────────────────
    //
    // 番茄小说那份 readConfig.json 给了五档底色，第 5 档日间 #ded9c5 / 夜间 #0e0e0e
    // 并各自带显式字色（bgStr、textColor 与 Night 变体），不是「深底配白字、浅底配黑字」
    // 那种由深浅反推的关系。米黄底上的字是棕褐色，反推会得到近黑，读着发硬。
    /** 番茄主题的底色（ARGB，不透明）。 */
    const val BG_FANQIE_DAY = 0xFFDED9C5.toInt()
    const val BG_FANQIE_NIGHT = 0xFF0E0E0E.toInt()

    /**
     * 底色带显式前景色时返回它，否则 null（交回「由深浅反推」）。
     *
     * 用位运算拼色，不调 android.graphics.Color：自检要能在纯 JVM 上跑。
     */
    fun inkOverride(bg: Int): Int? = when (bg) {
        BG_FANQIE_DAY -> 0xFF5B4928.toInt()
        BG_FANQIE_NIGHT -> 0xFFBFBFBF.toInt()
        else -> null
    }

    /** 命中计数。legado 写作“n / 共 m”，这里补上单位免得误读成页码。 */
    fun counterText(current: Int, count: Int): String =
        if (count <= 0) "共 0 处" else "第 ${current.coerceIn(0, count - 1) + 1} / 共 $count 处"

    /**
     * 按章标题找回原章下标。先全等，再忽略空白与标点，最后双向包含；找不到返回 -1。
     *
     * 换源之后同一个故事在不同站的章名常带差异（`第12章 风起` / `第 12 章 风起（上）`），
     * 全等对不上就一路放宽，宁可在原章附近也不要退回目录页。
     *
     * 三级顺序固定：全等优先，所以同名的多章取第一条；包含级取长度最接近的一条，
     * 免得 `第一章` 撞上 `第一千章`。
     */
    fun chapterIndex(titles: List<String>, want: String): Int {
        val w = want.trim()
        if (w.isEmpty() || titles.isEmpty()) return -1
        // 一级：全等（只去首尾空白）
        val exact = titles.indexOfFirst { it.trim() == w }
        if (exact >= 0) return exact
        // 二级：归一化后全等（滤掉空白与标点，`第 12 章 风起` 对得上 `第12章风起`）
        val kw = normTitle(w)
        if (kw.isNotEmpty()) {
            val loose = titles.indexOfFirst { normTitle(it) == kw }
            if (loose >= 0) return loose
        }
        if (kw.isEmpty()) return -1
        // 三级：双向包含。取归一化长度最接近的那条，同距取前面那条。
        var bestAt = -1
        var bestGap = Int.MAX_VALUE
        titles.forEachIndexed { i, t ->
            val k = normTitle(t)
            if (k.isEmpty()) return@forEachIndexed
            if (!k.contains(kw) && !kw.contains(k)) return@forEachIndexed
            val gap = kotlin.math.abs(k.length - kw.length)
            if (gap < bestGap) { bestGap = gap; bestAt = i }
        }
        return bestAt
    }

    /** 标题归一化：只留字母与数字。CJK 也是字母，标点与空白自然被滤掉。 */
    private fun normTitle(s: String): String = s.filter { it.isLetterOrDigit() }

    // ── 护眼：整屏暖色遮罩 ─────────────────────────────────
    const val EYE_ALPHA = 28

    /** 护眼强度档：0 弱 / 1 中 / 2 强。手表三档能分辨，再细的滑杆拖不准。 */
    fun eyeAlpha(level: Int): Int = when (level.coerceIn(0, 2)) {
        0 -> 16
        2 -> 44
        else -> EYE_ALPHA
    }

    /** 夜间自动护眼的时间窗：22 点到次日 7 点。 */
    fun isNight(hour: Int): Boolean = hour >= 22 || hour < 7

    /**
     * 护眼遮罩色。ARGB 整数，暖橙、低透明。
     *
     * 用位运算拼，不调 android.graphics.Color：自检要能在纯 JVM 上跑。
     */
    fun eyeColor(alpha: Int = EYE_ALPHA): Int {
        val a = alpha.coerceIn(0, 255)
        return (a shl 24) or (255 shl 16) or (180 shl 8) or 80
    }
}

/**
 * 主题配色表自检。
 *
 * 色表为日 / 夜 / 米 / 米·夜四套，校验旧主题迁移方向与「亮度 × 配色」两维组合。
 * 深色层级逐级变亮，浅色层级逐级变暗。
 * 正文、次要文字、按钮及容器分别校验对比度。
 */
import com.watchdl.core.Palette

fun main() {
    var bad = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("  ✓ $name")
        else { bad++; println("  ✗ $name${if (detail.isEmpty()) "" else " → $detail"}") }
    }

    // WCAG 相对亮度与对比度
    fun lin(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    fun lum(color: Int): Double =
        0.2126 * lin((color shr 16) and 0xFF) +
        0.7152 * lin((color shr 8) and 0xFF) +
        0.0722 * lin(color and 0xFF)
    fun contrast(a: Int, b: Int): Double {
        val la = lum(a); val lb = lum(b)
        val hi = maxOf(la, lb); val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * 两个颜色是否同色系：色调角相差 ≤ 30°。
     *
     * 单纯比相等等于自己验自己（默认值就是同一个字段），改用色相才有约束力。
     * 灰阶（饱和度近 0）没有可信色调，直接算通过，交给对比度那几条去管。
     */
    fun sameHue(a: Int, b: Int): Boolean {
        fun hue(c: Int): Pair<Double, Double> {
            val r = ((c shr 16) and 0xFF) / 255.0
            val g = ((c shr 8) and 0xFF) / 255.0
            val bl = (c and 0xFF) / 255.0
            val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
            val d = mx - mn
            if (d < 1e-6) return 0.0 to 0.0
            val h = when (mx) {
                r -> ((g - bl) / d) % 6.0
                g -> (bl - r) / d + 2.0
                else -> (r - g) / d + 4.0
            }
            return ((h * 60.0) + 360.0) % 360.0 to d / mx
        }
        val (ha, sa) = hue(a); val (hb, sb) = hue(b)
        if (sa < 0.08 || sb < 0.08) return true
        val diff = kotlin.math.abs(ha - hb)
        return kotlin.math.min(diff, 360.0 - diff) <= 30.0
    }

    println("[Palette] 表结构")
    val legacyIds = setOf("inkgreen", "yuuka", "elink", "mujika", "gr", "phoebe", "sora")
    // 底色断言必须跟 `Palette.all` 的真实值一致。
    // 这两项以前写的是派生源（warmpaper 0xFFF2EFE7 / elink 0xFF0E0E0E），
    // 而 `Md3.applyPalette` 当时另用一套硬编码常量（0xFFF3F0E8 / 0xFF101113），
    // 断言与实际渲染早就不是一回事。现在 `all` 是唯一真源，断言直接跟它。
    val previewThemes = listOf(
        Triple("day", "日间", 0xFFF3F0E8.toInt()),
        Triple("night", "夜间", 0xFF101113.toInt()),
        Triple("miuix", "米", 0xFFF7F7F9.toInt()),
        Triple("miuixnight", "米·夜", 0xFF121316.toInt()),
        Triple("astryx", "astryx", 0xFFF1F1F1.toInt()),
        Triple("astryxnight", "astryx·夜", 0xFF1B1B1B.toInt()),
    )
    val newIds = setOf("day", "night", "miuix", "miuixnight", "astryx", "astryxnight")
    // 浅色只看实际亮度、不看名字：米·夜是深色，混进来会让层级单调方向反过来。
    val lightIds = setOf("day", "miuix", "astryx")
    check("色表为日 / 夜 / 米 / 米·夜 / 中性两套",
        Palette.all.map { it.id } ==
            listOf("day", "night", "miuix", "miuixnight", "astryx", "astryxnight"))
    check("七套旧深色主题迁移夜间", legacyIds.all { Palette.find(it).id == "night" })
    check("旧浅色主题迁移日间", listOf("warmpaper", "seasalt").all { Palette.find(it).id == "day" })
    check("旧新增深色主题迁移夜间", listOf("duskblue", "amberblack", "violetmist", "terracotta").all { Palette.find(it).id == "night" })
    check("默认日间", Palette.DEFAULT_ID == "day")
    for ((id, name, surface) in previewThemes) {
        val p = Palette.find(id)
        check("$name 可按 ID 切换", p.id == id && p.name == name)
        check("$name 保留预览底色", p.surface == surface)
    }
    check("id 唯一", Palette.all.map { it.id }.toSet().size == Palette.all.size,
        Palette.all.joinToString { it.id })
    check("name 非空", Palette.all.all { it.name.isNotBlank() })
    check("默认色在表内", Palette.all.any { it.id == Palette.DEFAULT_ID }, Palette.DEFAULT_ID)
    check("默认解析为日间", Palette.find(null).id == "day")
    check("认不出 id 回默认", Palette.find("no-such-id").id == Palette.DEFAULT_ID)
    check("null 回默认", Palette.find(null).id == Palette.DEFAULT_ID)
    check("日夜米中性原样返回",
        listOf("day", "night", "miuix", "miuixnight", "astryx", "astryxnight")
            .all { Palette.find(it).id == it })

    // 深浅标记是「判夜」的唯一依据（状态栏字色、平台主题样式、ok 绿都用它），
    // 写错一处就把深色当浅色用，而且编译不报错。
    check("day 为浅色", Palette.find("day").dark == false)
    check("miuix 为浅色", Palette.find("miuix").dark == false)
    check("astryx 为浅色", Palette.find("astryx").dark == false)
    check("night 为深色", Palette.find("night").dark == true)
    check("miuixnight 为深色", Palette.find("miuixnight").dark == true)
    check("astryxnight 为深色", Palette.find("astryxnight").dark == true)
    check("深色恰为三套", Palette.all.count { it.dark } == 3,
        Palette.all.joinToString { "${it.id}=${it.dark}" })

    // 「亮度 × 配色」两维组合：设置页两条段式条靠 combine 拼 id，靠 family 回显。
    check("两维组合六套",
        Palette.combine(false, Palette.FAMILY_DEFAULT) == "day" &&
            Palette.combine(true, Palette.FAMILY_DEFAULT) == "night" &&
            Palette.combine(false, Palette.FAMILY_MIUIX) == "miuix" &&
            Palette.combine(true, Palette.FAMILY_MIUIX) == "miuixnight" &&
            Palette.combine(false, Palette.FAMILY_ASTRYX) == "astryx" &&
            Palette.combine(true, Palette.FAMILY_ASTRYX) == "astryxnight")
    check("组合出的 id 都在表内",
        listOf(false, true).all { d ->
            listOf(Palette.FAMILY_DEFAULT, Palette.FAMILY_MIUIX, Palette.FAMILY_ASTRYX)
                .all { fam -> Palette.all.any { p -> p.id == Palette.combine(d, fam) } }
        })
    check("反查配色族",
        Palette.family("day") == Palette.FAMILY_DEFAULT &&
            Palette.family("night") == Palette.FAMILY_DEFAULT &&
            Palette.family("miuix") == Palette.FAMILY_MIUIX &&
            Palette.family("miuixnight") == Palette.FAMILY_MIUIX &&
            Palette.family("astryx") == Palette.FAMILY_ASTRYX &&
            Palette.family("astryxnight") == Palette.FAMILY_ASTRYX)
    check("旧主题也能反查到配色族",
        Palette.family("inkgreen") == Palette.FAMILY_DEFAULT &&
            Palette.family("warmpaper") == Palette.FAMILY_DEFAULT &&
            Palette.family(null) == Palette.FAMILY_DEFAULT)
    check("族与亮度的组合能覆盖全部四套",
        Palette.all.all { p -> Palette.combine(p.dark, Palette.family(p.id)) == p.id })

    println("[Palette] 层级与对比度")
    for (p in Palette.all) {
        val surfaces = listOf(p.surface, p.surface1, p.surface2, p.surface3, p.surface4)
        val lums = surfaces.map { lum(it) }
        val light = p.id in lightIds
        val mono = lums.zipWithNext().all { (a, b) -> if (light) b < a else b > a }
        check("${p.name} surface1..4 ${if (light) "逐级变暗" else "逐级变亮"}", mono,
            lums.joinToString(" ") { "%.3f".format(it) })

        // MD3 正文对比度下限 4.5:1
        val cBody = contrast(p.onSurface, p.surface)
        check("${p.name} 正文对比 ≥4.5", cBody >= 4.5, "%.2f".format(cBody))
        // 次要文字在小字号下仍要看得清，取 3.0（ABNT/large text 档）
        val cSub = contrast(p.onSurfaceVariant, p.surface2)
        check("${p.name} 次要文字对比 ≥3.0", cSub >= 3.0, "%.2f".format(cSub))
        // 主色按钮上的字
        val cBtn = contrast(p.onPrimary, p.primary)
        check("${p.name} 主按钮可读 ≥4.5", cBtn >= 4.5, "%.2f".format(cBtn))
        // 主色本身要在底色上看得出来（进度条、选中态靠它）
        val cAcc = contrast(p.primary, p.surface)
        check("${p.name} 主色可见 ≥3.0", cAcc >= 3.0, "%.2f".format(cAcc))
        // 报错红不能和底色混
        val cErr = contrast(p.error, p.surface)
        check("${p.name} 错误色可见 ≥3.0", cErr >= 3.0, "%.2f".format(cErr))
        if (p.id in newIds) {
            val bodyMin = surfaces.minOf { contrast(p.onSurface, it) }
            check("${p.name} 所有层级正文 ≥4.5", bodyMin >= 4.5, "%.2f".format(bodyMin))
            check("${p.name} 小字号次要文字 ≥4.5", cSub >= 4.5, "%.2f".format(cSub))
            val primaryText = contrast(p.onPrimaryContainer, p.primaryContainer)
            check("${p.name} 主容器文字 ≥4.5", primaryText >= 4.5, "%.2f".format(primaryText))
            val secondaryText = contrast(p.onSecondaryContainer, p.secondaryContainer)
            check("${p.name} 次容器文字 ≥4.5", secondaryText >= 4.5, "%.2f".format(secondaryText))
            check("${p.name} 深浅文字方向匹配", (lum(p.onSurface) < lum(p.surface)) == light)
            val opaque = listOf(p.primary, p.onPrimary, p.primaryContainer, p.onPrimaryContainer,
                p.secondaryContainer, p.onSecondaryContainer, p.onSurface, p.onSurfaceVariant,
                p.outline, p.outlineVariant, p.error,
                p.surfaceTint, p.surfaceContainerLowest, p.surfaceContainerLow,
                p.surfaceContainer, p.surfaceContainerHigh, p.surfaceContainerHighest,
                p.inverseSurface, p.inverseOnSurface, p.inversePrimary) + surfaces
            check("${p.name} 色角色均不透明", opaque.all { (it ushr 24) == 255 })

            // MD3 容器五档：日间由亮到暗（Lowest 最亮），夜间由暗到亮（Lowest 最暗）。
            val containers = listOf(p.surfaceContainerLowest, p.surfaceContainerLow,
                p.surfaceContainer, p.surfaceContainerHigh, p.surfaceContainerHighest)
            val cl = containers.map { lum(it) }
            val monoC = cl.zipWithNext().all { (a, b) -> if (light) b < a else b > a }
            check("${p.name} 容器五档 ${if (light) "逐级变暗" else "逐级变亮"}", monoC,
                cl.joinToString(" ") { "%.3f".format(it) })
            // 容器色上也要看得清正文
            val cCont = containers.minOf { contrast(p.onSurface, it) }
            check("${p.name} 容器上正文 ≥4.5", cCont >= 4.5, "%.2f".format(cCont))
            // 反色组必须真的反：日间底色亮则 inverseSurface 暗，夜间反过来。
            check("${p.name} inverseSurface 明暗反向", (lum(p.inverseSurface) < lum(p.surface)) == light,
                "%.3f / %.3f".format(lum(p.inverseSurface), lum(p.surface)))
            val cInv = contrast(p.inverseOnSurface, p.inverseSurface)
            check("${p.name} 反色组文字 ≥4.5", cInv >= 4.5, "%.2f".format(cInv))
            // surfaceTint 取主色（同值或极相近），保证 tone 叠加与原观感一致。
            // 不断言 `== primary`：Palette 的字段默认值就是 primary，两套又都显式写成同值，
            // 这条断言恒真，改敢 surfaceTint 忘了改 all 也一样绿。改验色相接近。
            check("${p.name} surfaceTint 与主色同色系", sameHue(p.surfaceTint, p.primary),
                "%08X vs %08X".format(p.surfaceTint, p.primary))
        }
    }

    if (bad > 0) { println("\nPaletteTest: $bad 项不通过"); kotlin.system.exitProcess(1) }
    println("\nPaletteTest: 全部通过")
}

/**
 * 主题配色表自检。
 *
 * 仅保留日夜主题，并校验旧主题迁移方向。
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

    println("[Palette] 表结构")
    val legacyIds = setOf("inkgreen", "yuuka", "elink", "mujika", "gr", "phoebe", "sora")
    val previewThemes = listOf(
        Triple("day", "日间", 0xFFF2EFE7.toInt()),
        Triple("night", "夜间", 0xFF0E0E0E.toInt()),
    )
    val newIds = setOf("day", "night")
    val lightIds = setOf("day")
    check("仅保留日夜主题", Palette.all.map { it.id } == listOf("day", "night"))
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
    check("日夜原样返回", listOf("day", "night").all { Palette.find(it).id == it })

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
                p.outline, p.outlineVariant, p.error) + surfaces
            check("${p.name} 色角色均不透明", opaque.all { (it ushr 24) == 255 })
        }
    }

    if (bad > 0) { println("\nPaletteTest: $bad 项不通过"); kotlin.system.exitProcess(1) }
    println("\nPaletteTest: 全部通过")
}

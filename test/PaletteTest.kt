/**
 * 主题配色表自检。
 *
 * 六套值是从 legado-novel 的 Compose 色方案里抠出来的字面量，搬错了不会编译报错，
 * 只会在表盘上变成一片看不清的灰。这里用可算的指标兜住两件事：
 *   ① surface1..4 必须逐级抬升 —— 抄错一条，卡片与底色就分不开；
 *   ② 正文与次要文字必须够对比 —— 深色底上的浅字最容易踩这个坑。
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
    check("至少 4 套", Palette.all.size >= 4, "只有 ${Palette.all.size} 套")
    check("id 唯一", Palette.all.map { it.id }.toSet().size == Palette.all.size,
        Palette.all.joinToString { it.id })
    check("name 非空", Palette.all.all { it.name.isNotBlank() })
    check("默认色在表内", Palette.all.any { it.id == Palette.DEFAULT_ID }, Palette.DEFAULT_ID)
    check("默认就是表头", Palette.all[0].id == Palette.DEFAULT_ID)
    check("认不出 id 回默认", Palette.find("no-such-id").id == Palette.DEFAULT_ID)
    check("null 回默认", Palette.find(null).id == Palette.DEFAULT_ID)
    check("认得出的原样返回", Palette.find("sora").id == "sora")

    println("[Palette] 层级与对比度")
    for (p in Palette.all) {
        val surfaces = listOf(p.surface, p.surface1, p.surface2, p.surface3, p.surface4)
        val lums = surfaces.map { lum(it) }
        val mono = lums.zipWithNext().all { (a, b) -> b >= a }
        check("${p.name} surface1..4 逐级抬升", mono,
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
    }

    if (bad > 0) { println("\nPaletteTest: $bad 项不通过"); kotlin.system.exitProcess(1) }
    println("\nPaletteTest: 全部通过")
}

/**
 * 图标路径数据自检。
 *
 * 图标是手抄的 Material 24dp 路径字符串，写错一个数字画面就是一团乱码，
 * 而 UI 层进不了 JVM 自检（依赖 Android 框架）。所以这里直接扫源码文本：
 * 把 `Icons.DATA` 的每一对名字与路径抽出来，验证语法与坐标范围。
 */
import java.io.File

fun main() {
    var bad = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("  ✓ $name")
        else { bad++; println("  ✗ $name${if (detail.isEmpty()) "" else " → $detail"}") }
    }

    val f = File("src/com/watchdl/ui/theme/Icons.kt")
    if (!f.isFile) { println("  ✗ 找不到 Icons.kt"); return kotlin.system.exitProcess(1) }
    val text = f.readText()

    // 一行一条：`"name" to "M..."`，长的用 `+` 续到下一行，所以先把续行拼回去。
    val joined = text.replace(Regex("\"\\s*\\+\\s*\\n\\s*\""), "")
    val entry = Regex("\"([a-z_]+)\" to \"([^\"]+)\"")
    val entries = entry.findAll(joined).toList()
    check("抽到图标条目", entries.size >= 20, "${entries.size} 条")

    val token = Regex("[A-Za-z]|-?(?:[0-9]*\\.)?[0-9]+")
    val supported = "MmLlHhVvCcSsQqTtZz"
    val badCmd = mutableListOf<String>()
    val badNum = mutableListOf<String>()
    val outOfGrid = mutableListOf<String>()

    for (m in entries) {
        val name = m.groupValues[1]
        val d = m.groupValues[2]
        val toks = token.findAll(d).map { it.value }.toList()
        if (toks.isEmpty() || toks[0].uppercase() != "M") {
            badCmd.add("$name 不以 M 开头"); continue
        }
        if (!d.trim().endsWith("z") && !d.trim().endsWith("Z")) badCmd.add("$name 没有闭合")
        // 指令必须在支持集内；数字必须是数字。
        for (t in toks) {
            if (t.length == 1 && t[0].isLetter()) {
                if (!supported.contains(t[0])) badCmd.add("$name 用了 $t")
            } else if (t.toFloatOrNull() == null) {
                badNum.add("$name 的 $t")
            }
        }
        // 坐标范围：先把指令展开成绝对坐标，再量。
        // 相对指令的负值是相对偏移（`l-8 8`），直接拿去当坐标量会全部误判。
        val oob = outOfGridAfterWalk(d)
        if (oob.isNotEmpty()) outOfGrid.add("$name 的 $oob")
    }

    check("指令都在支持集内", badCmd.isEmpty(), badCmd.joinToString(", "))
    check("数字都能解析", badNum.isEmpty(), badNum.joinToString(", "))
    check("坐标都在 24 网格内", outOfGrid.isEmpty(), outOfGrid.joinToString(", "))

    // ALIAS 指向的名字必须存在，否则调用点会静默退成文字字形。
    val names = entries.map { it.groupValues[1] }.toSet()
    val aliasBlock = Regex("private val ALIAS = mapOf\\(([\\s\\S]*?)\\n    \\)")
        .find(text)?.groupValues?.get(1).orEmpty()
    val dangling = Regex("\"([^\"]+)\" to \"([a-z_]+)\"")
        .findAll(aliasBlock)
        .map { it.groupValues[2] }
        .filter { it !in names }
        .toList()
    check("别名都指向已有图标", dangling.isEmpty(), dangling.joinToString(", "))

    // 每个图标至少两个指令：只有一个 M 的路径是画不出东西的。
    val thin = entries.filter { m ->
        Regex("[A-Za-z]|-?(?:[0-9]*\\.)?[0-9]+").findAll(m.groupValues[2])
            .count { it.value.length == 1 && it.value[0].isLetter() } < 2
    }.map { it.groupValues[1] }
    check("没有空图标", thin.isEmpty(), thin.joinToString(", "))

    if (bad == 0) println("IconDataTest: 全部通过") else kotlin.system.exitProcess(1)
}

/**
 * 展开相对指令，返回超出 24 网格（宽容 ±2）的绝对坐标坐标值。
 *
 * 只做坐标走查，不画图：H/V 只改一个轴，C/S/Q/T 取端点与控制点一起走。
 */
private fun outOfGridAfterWalk(d: String): List<Float> {
    val toks = Regex("[MmLlHhVvCcSsQqTtZz]|-?(?:[0-9]*\\.)?[0-9]+")
        .findAll(d).map { it.value }.toList()
    val bad = ArrayList<Float>()
    var x = 0f; var y = 0f
    var sx = 0f; var sy = 0f          // 子路径起点：M/m 记下，Z/z 后当前点回到这里
    var i = 0
    var cmd = 'M'
    while (i < toks.size) {
        val t = toks[i]
        if (t.length == 1 && t[0].isLetter()) {
            cmd = t[0]; i++
            // Z 不是“没有参数所以结束”，而是“回到子路径起点”。
            // 以前这里直接 break，后面所有子路径都不再检查；又不复位 x/y，
            // 紧跟的相对指令全部错位（carousel 就这样被误判为超出网格）。
            if (cmd.uppercaseChar() == 'Z') { x = sx; y = sy }
            continue
        }
        val rel = cmd.isLowerCase()
        val n = when (cmd.uppercaseChar()) {
            'M', 'L', 'T' -> 2
            'H', 'V' -> 1
            'C' -> 6
            'S', 'Q' -> 4
            else -> 0
        }
        if (n == 0 || i + n > toks.size) break
        val a = FloatArray(n) { toks[i + it].toFloat() }
        i += n
        fun walk(px: Float, py: Float) {
            if (px < -2f || px > 26f || py < -2f || py > 26f) bad.add(px)
        }
        when (cmd.uppercaseChar()) {
            'M', 'L' -> {
                x = if (rel) x + a[0] else a[0]
                y = if (rel) y + a[1] else a[1]
                if (cmd.uppercaseChar() == 'M') { sx = x; sy = y }
                walk(x, y)
            }
            'H' -> { x = if (rel) x + a[0] else a[0]; walk(x, y) }
            'V' -> { y = if (rel) y + a[0] else a[0]; walk(x, y) }
            'C' -> {
                walk(if (rel) x + a[0] else a[0], if (rel) y + a[1] else a[1])
                walk(if (rel) x + a[2] else a[2], if (rel) y + a[3] else a[3])
                x = if (rel) x + a[4] else a[4]
                y = if (rel) y + a[5] else a[5]
                walk(x, y)
            }
            'S', 'Q' -> {
                walk(if (rel) x + a[0] else a[0], if (rel) y + a[1] else a[1])
                x = if (rel) x + a[2] else a[2]
                y = if (rel) y + a[3] else a[3]
                walk(x, y)
            }
            'T' -> {
                x = if (rel) x + a[0] else a[0]
                y = if (rel) y + a[1] else a[1]
                walk(x, y)
            }
        }
        if (cmd.uppercaseChar() == 'M') cmd = if (rel) 'l' else 'L'
    }
    return bad
}

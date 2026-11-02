/**
 * 每一屏都必须自带回退按钮 —— 除了底栏那三个根屏。
 *
 * 这块表没有实体返回键，也没有手势导航栏 —— 屏上不画「←」就等于死路，
 * 用户只能长按手表按钮杀进程。所以把「新增一屏忘了画返回」变成一条自检，
 * 而不是每个新屏靠人记。
 *
 * 2026-11-01 起分两类：
 * - **子屏**用 `Widgets.topBar(...)`，必须传 `onBack = {`。
 * - **根屏**（底栏三栏：书架 / 发现 / 我的）用 `Widgets.headerRow(...)`，不挂返回 ——
 *   它们是平级入口，靠底栏互相切换，给返回箭头等于暗示「上一步」存在，是错的。
 *
 * 判据仍是源码文本：扫调用点，不跑 Activity。
 */
import java.io.File

fun main() {
    var bad = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("  ✓ $name")
        else { bad++; println("  ✗ $name${if (detail.isEmpty()) "" else " → $detail"}") }
    }

    val src = File("src")
    if (!src.isDirectory) { println("  ✗ 找不到 src/"); return kotlin.system.exitProcess(1) }

    val files = src.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    check("扫到源码文件", files.size > 20, "${files.size} 个")

    var topBars = 0
    val dead = mutableListOf<String>()

    for (f in files) {
        val text = f.readText()
        var from = 0
        while (true) {
            val i = text.indexOf("Widgets.topBar(", from)
            if (i < 0) break
            from = i + 1
            topBars++
            // 参数列表不会长过 300 字；截到这里足够判定，也避免跨调用误判。
            val args = text.substring(i, minOf(i + 300, text.length))
            if (!args.contains("onBack = {")) {
                dead.add("${f.name}:${text.substring(0, i).count { it == '\n' } + 1}")
            }
        }
    }

    println("[Back] 子屏应用栏")
    check("子屏调用点还在（≥12）", topBars >= 12, "$topBars 处")
    check("每屏都有返回", dead.isEmpty(), dead.joinToString(", "))

    // 三个根屏：必须是 headerRow，且不得再挂 topBar（那会带出返回箭头）。
    println("[Back] 根屏")
    val roots = listOf(
        "src/com/watchdl/ui/shelf/ShelfScreen.kt",
        "src/com/watchdl/ui/source/SourceScreen.kt",
        "src/com/watchdl/ui/my/MyScreen.kt",
    )
    for (path in roots) {
        val f = File(path)
        val text = if (f.isFile) f.readText() else ""
        check("${f.name} 有 headerRow", text.contains("Widgets.headerRow("))
    }
    // 书架不挂返回，搜索入口在标题行右端。
    val shelf = File(roots[0]).readText()
    check("书架没有返回箭头", !shelf.contains("Widgets.topBar("))
    check("书架有搜索入口", shelf.contains("askKeyword()"))
    check("书架有 ⋮ 菜单", shelf.contains("showShelfMenu()"))

    // 根屏只允许这三个：把「新开一屏忘了给回路」卡在这里。
    check("发现页不挂返回", !File(roots[1]).let { p ->
        var from = 0; var hit = false
        val t = p.readText()
        // 发现页是根屏；同一文件里的书源/市场/详情是子屏，仍走 topBar。
        val i = t.indexOf("internal fun MainActivity.showDiscover(")
        if (i >= 0) {
            val seg = t.substring(i, minOf(i + 1200, t.length))
            hit = seg.contains("Widgets.topBar(")
        }
        hit
    })
    check("我的页不挂返回", !File(roots[2]).readText().contains("Widgets.topBar("))

    // 底部三栏是三屏各自收尾挂上的：漏一屏，那屏就没有跨栏入口。
    for ((f, n) in listOf(
        roots[0] to 3,
        roots[1] to 2,
        roots[2] to 1,
    )) {
        val c = File(f).readText().split("attachTabs(").size - 1
        check("${File(f).name} 挂着底部三栏", c >= n, "$c 处，应 ≥$n")
    }

    if (bad > 0) { println("\nBackAffordanceTest: $bad 项不通过"); kotlin.system.exitProcess(1) }
    println("\nBackAffordanceTest: 全部通过")
}

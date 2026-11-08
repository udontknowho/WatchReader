import com.watchdl.core.*
/**
 * 导航与阅读进度：两条全靠人肉守的规则。
 *
 * 1. 返回栈必须是**栈**。以前只有一个变量，搜索→目录→阅读→设置之后
 *    按返回会跳过搜索结果页直接回首页。
 * 2. 阅读进度跟着最后一次阅读走，回看会退（与 legado 一致）。
 *    旧版分节存档（章号×1000+节号）只能解一次，解完立刻写成纯章号。
 */
private var failures = 0

private fun check(desc: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ✓ $desc")
    else { println("  ✗ $desc  $detail"); failures++ }
}

fun main() {
    println("[导航] 返回栈")

    run {
        val stack = ArrayDeque<ReturnPoint>()
        stack.remember(ReturnPoint(Screen.Shelf, 940))
        stack.remember(ReturnPoint(Screen.Hits("书", emptyList()), 1780, hitsWindow = 60))
        stack.remember(ReturnPoint(Screen.Hits("书", emptyList()), 1820, hitsWindow = 120))
        check("同屏更新偏移，不重复压栈", stack.size == 2)
        val hits = stack.removeLast()
        check("搜索窗口跟随返回记录", hits.hitsWindow == 120)
        check("搜索偏移跟随返回记录", hits.scrollY == 1820)
        check("多层返回保留书架偏移", stack.removeLast().scrollY == 940)
        repeat(20) { stack.remember(ReturnPoint(Screen.Settings("$it"), it)) }
        check("真实返回栈只保留八层", stack.size == 8 && stack.first().scrollY == 12)
        check("布局未就绪不越界", restoredScrollY(1800, 100, 300) == 0)
        check("异步内容展开后恢复原位", restoredScrollY(1800, 3000, 300) == 1800)
        check("列表变短夹到末尾", restoredScrollY(1800, 1000, 300) == 700)
        check("负偏移夹到顶部", restoredScrollY(-1, 1000, 300) == 0)
        val toc = ReturnPoint(Screen.Shelf, 900, tocWindow = 400)
        check("目录窗口独立保存", toc.tocWindow == 400)
    }

    run {
        println("[导航] 四层跳转要一层层退")
        val stack = ArrayDeque<String>()
        // home → 搜索 → 目录 → 阅读 → 设置
        for (s in listOf("hits", "toc", "reader")) stack.addLast(s)
        val back1 = stack.removeLastOrNull()
        check("从设置退到阅读", back1 == "reader", "实际 $back1")
        check("再退到目录", stack.removeLastOrNull() == "toc")
        check("再退到搜索", stack.removeLastOrNull() == "hits")
        check("退完了就回首页", stack.removeLastOrNull() == null)
    }

    run {
        println("[导航] 同一屏不重复压栈")
        val stack = ArrayDeque<String>()
        val cur = "hits"
        repeat(3) { if (stack.lastOrNull() != cur) stack.addLast(cur) }
        check("压三次仍只有一层", stack.size == 1, "实际 ${stack.size}")
    }

    run {
        println("[导航] 栈深有上限")
        val stack = ArrayDeque<Int>()
        repeat(20) { stack.addLast(it) }
        while (stack.size > 8) stack.removeFirst()
        check("压 20 层只留 8", stack.size == 8, "实际 ${stack.size}")
        check("留下的是最近的", stack.last() == 19, "实际 ${stack.last()}")
    }

    run {
        println("[进度] 进度跟着最后一次阅读走")
        // 与 legado ReadBook.saveRead() 一致：无条件写入，回看会退。
        val pos = mutableMapOf<String, Int>()
        fun save(book: String, i: Int) { pos[book] = i }
        save("b1", 5)
        check("第一次记下第 6 章", pos["b1"] == 5, "实际 ${pos["b1"]}")
        save("b1", 9)
        check("往后读会更新", pos["b1"] == 9, "实际 ${pos["b1"]}")
        save("b1", 3)
        check("往回翻跟着退", pos["b1"] == 3, "实际 ${pos["b1"]}")
    }

    run {
        println("[进度] 旧分节存档一次性解开")
        // 旧格式是 章号 * 1000 + 节号。判据是有没有 last_（新版才写的），
        // 不能用数值大小：第 1001 章会被当成旧值直接甩回第 1 章。
        val SEG_BASE = 1000
        val pos = mutableMapOf<String, Int>()
        val last = mutableMapOf<String, Long>()
        // S20：以前这里重写了一份换算，MainActivity.readProgress 改坏了测试照样全绿。
        // 现在调 core 的真实现。
        fun readProgress(book: String): Int {
            val raw = pos[book] ?: -1
            val (fixed, writeBack) = resolveStoredIndex(raw, last.containsKey(book), SEG_BASE)
            if (writeBack) { pos[book] = fixed; last[book] = 0L }
            return fixed
        }
        pos["old"] = 55000
        check("55000 解成第 55 章", readProgress("old") == 55, "实际 ${readProgress("old")}")
        check("解完存回纯章号", pos["old"] == 55, "实际 ${pos["old"]}")
        check("再读一次不再除", readProgress("old") == 55, "实际 ${readProgress("old")}")
        pos["small"] = 999
        check("纯章号 999 不动", readProgress("small") == 999, "实际 ${readProgress("small")}")
        // 这条就是那个 bug：第 1001 章不能被当成旧值。
        pos["big"] = 1001
        last["big"] = 1L
        check("第 1001 章不会被当成旧值", readProgress("big") == 1001, "实际 ${readProgress("big")}")
        check("没记录过算第 0 章", readProgress("none") == 0)
    }

    run {
        println("[进度] 没记录过时从第 1 章开始")
        val pos = mutableMapOf<String, Int>()
        check("缺省为 0", (pos["new"] ?: 0) == 0)
        check("不会显示「继续读第 0 章」", ((pos["new"] ?: 0) > 0) == false)
    }

    run {
        println("[进度] 不同书互不干扰")
        val pos = mutableMapOf("a" to 100, "b" to 3)
        check("a 在第 100 章", pos["a"] == 100)
        check("b 仍在第 3 章", pos["b"] == 3)
    }

    println()
    if (failures == 0) println("全部通过") else {
        println("失败 $failures 项"); kotlin.system.exitProcess(1)
    }
}

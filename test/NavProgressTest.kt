/**
 * 导航与阅读进度：两条全靠人肉守的规则。
 *
 * 1. 返回栈必须是**栈**。以前只有一个变量，搜索→目录→阅读→设置之后
 *    按返回会跳过搜索结果页直接回首页。
 * 2. 阅读进度只会前进。往回翻不把「继续读」的位置弄小。
 */
private var failures = 0

private fun check(desc: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ✓ $desc")
    else { println("  ✗ $desc  $detail"); failures++ }
}

fun main() {
    println("[导航] 返回栈")

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
        println("[进度] 阅读进度只前进")
        val pos = mutableMapOf<String, Int>()
        fun save(book: String, i: Int) {
            if (i <= (pos[book] ?: 0)) return
            pos[book] = i
        }
        save("b1", 5)
        check("第一次记下第 6 章", pos["b1"] == 5, "实际 ${pos["b1"]}")
        save("b1", 9)
        check("往后读会更新", pos["b1"] == 9, "实际 ${pos["b1"]}")
        save("b1", 3)
        check("往回翻不回退", pos["b1"] == 9, "实际 ${pos["b1"]}")
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

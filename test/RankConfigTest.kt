import com.watchdl.core.RankConfig

/** 云端榜单配置自检：解析容错、非法项拒绝、页数封顶。不碰网络。 */
fun main() {
    var failures = 0
    fun check(n: String, ok: Boolean, d: String = "") {
        if (ok) println("  ✓ $n") else { failures++; println("  ✗ $n  $d") }
    }

    println("[RankConfig] 正常解析")
    val ok = RankConfig.parse("""
        {"version":2,"ranks":[
          {"key":"fanqie","name":"番茄推荐","kind":"fanqie"},
          {"key":"fqup","name":"番茄更新","kind":"fanqieUpdate","pages":5},
          {"key":"qd:x","name":"起点某榜","kind":"qidian","board":"yuepiaolist","pages":3,"gendered":true}
        ]}
    """)
    check("三条全解析", ok.size == 3, "实际 ${ok.size}")
    check("名字正确", ok[1].name == "番茄更新")
    check("页码正确", ok[1].pages == 5)
    check("起点 board 带上", ok[2].board == "yuepiaolist")
    check("gendered 读成 true", ok[2].gendered)

    println("[RankConfig] 非法项要被拒（远程不能塞新逻辑）")
    val bad = RankConfig.parse("""
        {"ranks":[
          {"key":"evil","name":"远程执行","kind":"shell"},
          {"key":"qd","name":"缺 board","kind":"qidian"},
          {"name":"缺 key","kind":"fanqie"},
          {"key":"good","name":"唯一合法","kind":"fanqie"}
        ]}
    """)
    check("只留合法的一条", bad.size == 1 && bad[0].key == "good", "实际 ${bad.map { it.key }}")

    println("[RankConfig] 页数封顶")
    val cap = RankConfig.parse("""{"ranks":[{"key":"a","kind":"fanqie","pages":10000}]}""")
    check("10000 被压到 50", cap.firstOrNull()?.pages == 50, "实际 ${cap.firstOrNull()?.pages}")
    val neg = RankConfig.parse("""{"ranks":[{"key":"a","kind":"fanqie","pages":-3}]}""")
    check("负数压到 0", neg.firstOrNull()?.pages == 0, "实际 ${neg.firstOrNull()?.pages}")

    println("[RankConfig] 坏输入不炸")
    check("空串返回空表", RankConfig.parse("").isEmpty())
    check("纯垃圾返回空表", RankConfig.parse("not json at all").isEmpty())
    check("ranks 不是数组返回空表", RankConfig.parse("""{"ranks":"oops"}""").isEmpty())

    println("[RankConfig] 内置兜底清单非空")
    check("内置有榜单", RankConfig.BUILTIN.isNotEmpty())
    check("内置都有 key", RankConfig.BUILTIN.all { it.key.isNotBlank() })
    check("内置起点榜都带 board", RankConfig.BUILTIN.filter { it.kind == RankConfig.Kind.QIDIAN }
        .all { it.board.isNotBlank() })

    if (failures == 0) println("\n全部通过") else { println("\n失败 $failures 项"); kotlin.system.exitProcess(1) }
}

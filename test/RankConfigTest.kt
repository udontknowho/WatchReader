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
    check("10000 被压到 10", cap.firstOrNull()?.pages == 10, "实际 ${cap.firstOrNull()?.pages}")
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

    println("[RankConfig] 全量榜单")
    // 番茄 26 个分类 × 只留阅读榜 = 26；起点 5 个。这组数是 2026-10-24 实测的范围，
    // 少一个说明分类表写漏了。
    // 2026-10-25：番茄「新书」榜（rankType=1）已按用户要求移除，所以不再是 52。
    val fq = RankConfig.BUILTIN.filter { it.kind == RankConfig.Kind.FANQIE_RANK }
    check("番茄分类榜 26 个", fq.size == 26, "实际 ${fq.size}")
    check("男频 19 类", fq.count { it.gender == RankConfig.Gender.MALE } == 19,
        "实际 ${fq.count { it.gender == RankConfig.Gender.MALE }}")
    check("女频 7 类", fq.count { it.gender == RankConfig.Gender.FEMALE } == 7,
        "实际 ${fq.count { it.gender == RankConfig.Gender.FEMALE }}")
    check("起点 5 个榜",
        RankConfig.BUILTIN.count { it.site == RankConfig.Site.QIDIAN } == 5,
        "实际 ${RankConfig.BUILTIN.count { it.site == RankConfig.Site.QIDIAN }}")
    check("key 不重复", RankConfig.BUILTIN.map { it.key }.toSet().size == RankConfig.BUILTIN.size)
    // 新书榜已移除。这条守着「别哪天又加回来」。
    check("没有番茄新书榜", fq.none { it.rankType == 1 },
        "还有 ${fq.count { it.rankType == 1 }} 个")
    check("番茄榜都是阅读榜", fq.all { it.rankType == 2 })
    check("每个分类各一个榜", fq.map { it.gender to it.category }.toSet().size == fq.size)
    check("番茄分类榜都带 category", fq.all { it.category > 0 })
    check("番茄分类榜都带频道", fq.all { it.gendered })

    println("[RankConfig] local() 不联网也能立刻出榜单")
    // 主线程只用 local()。它必须不依赖任何文件就返回内置表 ——
    // 以前主线程直接调 load()，最坏串行问 4 个镜像共 20 秒，
    // 点「发现榜单」整屏冻住，用户报的就是「卡死」。
    run {
        val emptyDir = java.io.File(
            System.getProperty("java.io.tmpdir"), "rankcfg-empty-${System.nanoTime()}")
        val started = System.currentTimeMillis()
        val got = RankConfig.local(emptyDir)
        val spent = System.currentTimeMillis() - started
        check("空目录返回内置表", got.size == RankConfig.BUILTIN.size, "实际 ${got.size}")
        check("不建目录也能读", !emptyDir.exists())
        // 本地读不该接近任何网络超时。留 1 秒余量给慢设备。
        check("耗时远低于网络超时", spent < 1_000, "实际 ${spent}ms")

        // 有缓存时就该用缓存，不能一直退内置表。
        emptyDir.mkdirs()
        val cfg = java.io.File(emptyDir, "ranks.json")
        cfg.writeText("""{"version":99,"ranks":[{"key":"only","name":"只有这一条","kind":"fanqie"}]}""")
        val cached = RankConfig.local(emptyDir)
        check("有缓存就用缓存", cached.size == 1 && cached[0].key == "only",
            "实际 ${cached.map { it.key }}")
        emptyDir.deleteRecursively()
    }

    if (failures == 0) println("\n全部通过") else { println("\n失败 $failures 项"); kotlin.system.exitProcess(1) }
}

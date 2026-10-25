import com.watchdl.core.BookHit
import com.watchdl.core.RankConfig
import com.watchdl.core.RankSource

/**
 * 榜单接口活体自测。真联网，所以不进 `build.sh test`，单独跑 `build.sh rank`。
 *
 * 测的是发现页真正走的入口 [RankSource.fetch]，不是底层的单站函数 ——
 * 否则探针全绿而发现页空白，就白测了。
 */
fun main() {
    var failed = 0

    fun check(label: String, hits: List<BookHit>, need: Int = 5) {
        val ok = hits.count { it.name.isNotBlank() } >= need
        println("%-22s %s  条数=%d %s".format(
            label, if (ok) "OK  " else "FAIL", hits.size,
            hits.take(2).joinToString(" / ") { "${it.name}·${it.author}" },
        ))
        if (!ok) failed++
    }

    fun run(r: RankConfig.Rank, page: Int = 1) = runCatching {
        RankSource.fetch(r, if (r.gender == RankConfig.Gender.FEMALE) "female" else "male", page)
    }.getOrElse { e ->
        println("  ${r.name} 异常：${e.message}")
        emptyList()
    }

    val all = RankConfig.BUILTIN

    // 番茄：男女频各抽第一个分类的两种榜型。全 52 个都打一遍太慢，抽样足够。
    println("[番茄分类榜]")
    for (g in listOf(RankConfig.Gender.MALE, RankConfig.Gender.FEMALE)) {
        val cats = all.filter { it.kind == RankConfig.Kind.FANQIE_RANK && it.gender == g }
        for (t in listOf(2, 1)) {
            val r = cats.firstOrNull { it.rankType == t } ?: continue
            check(r.name, run(r), need = 8)   // 服务端上限 10 条，要求至少 8 条
        }
    }

    // 起点：五个榜都测，男女频各一遍里的代表。
    println("[起点榜]")
    for (r in all.filter { it.site == RankConfig.Site.QIDIAN }) {
        check(r.name, run(r), need = 10)
    }

    // 翻页必须出新书 —— 服务端若忽略 pageNum，串 10 页就是同一个榜重复 10 次。
    println("[起点翻页]")
    val yue = all.first { it.board == "yuepiaolist" }
    val p1 = run(yue, 1)
    val p2 = run(yue, 2)
    val overlap = p1.map { it.bookId }.intersect(p2.map { it.bookId }.toSet()).size
    val pagingOk = p1.isNotEmpty() && p2.isNotEmpty() && overlap < p1.size / 2
    println("%-22s %s  第1页=%d 第2页=%d 重复=%d".format(
        "起点月票", if (pagingOk) "OK  " else "FAIL", p1.size, p2.size, overlap,
    ))
    if (!pagingOk) failed++

    if (failed > 0) {
        println("榜单自检：$failed 项失败")
        kotlin.system.exitProcess(1)
    }
    println("榜单自检：全部通过")
}

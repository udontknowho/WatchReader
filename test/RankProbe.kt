import com.watchdl.core.BookHit
import com.watchdl.core.RankSource

/**
 * 榜单接口活体自测。真联网，所以不进 `build.sh test`，单独跑 `build.sh rank`。
 *
 * 检查点：番茄推荐榜、起点男频月票榜、起点女频畅销榜各能拿到 ≥5 条带书名的条目。
 */
fun main() {
    var failed = 0

    fun check(label: String, hits: List<BookHit>) {
        val ok = hits.count { it.name.isNotBlank() } >= 5
        println("%-16s %s  条数=%d %s".format(
            label, if (ok) "OK  " else "FAIL", hits.size,
            hits.take(2).joinToString(" / ") { "${it.name}·${it.author}" },
        ))
        if (!ok) failed++
    }

    check("番茄推荐榜", runCatching { RankSource.fanqie() }.getOrElse { e ->
        println("番茄异常：${e.message}"); emptyList()
    })
    check("番茄更新榜", runCatching { RankSource.fanqieUpdate(1) }.getOrElse { e ->
        println("番茄更新榜异常：${e.message}"); emptyList()
    })
    check("起点男频月票", runCatching { RankSource.qidian("yuepiaolist", "male", 1) }.getOrElse { e ->
        println("起点男频异常：${e.message}"); emptyList()
    })
    check("起点女频畅销", runCatching { RankSource.qidian("hotsaleslist", "female", 1) }.getOrElse { e ->
        println("起点女频异常：${e.message}"); emptyList()
    })

    // 翻页必须出新书 —— 服务端若忽略 pageNum，界面上的「下一页」就是假按钮。
    val p1 = runCatching { RankSource.qidian("yuepiaolist", "male", 1) }.getOrDefault(emptyList())
    val p2 = runCatching { RankSource.qidian("yuepiaolist", "male", 2) }.getOrDefault(emptyList())
    val overlap = p1.map { it.bookId }.intersect(p2.map { it.bookId }.toSet()).size
    val pagingOk = p1.isNotEmpty() && p2.isNotEmpty() && overlap < p1.size / 2
    println("%-16s %s  第1页=%d 第2页=%d 重复=%d".format(
        "起点翻页", if (pagingOk) "OK  " else "FAIL", p1.size, p2.size, overlap,
    ))
    if (!pagingOk) failed++

    // 番茄更新榜也要能翻：offset 是它唯一的翻页手段。
    val f1 = runCatching { RankSource.fanqieUpdate(1) }.getOrDefault(emptyList())
    val f2 = runCatching { RankSource.fanqieUpdate(2) }.getOrDefault(emptyList())
    val fOverlap = f1.map { it.bookId }.intersect(f2.map { it.bookId }.toSet()).size
    val fOk = f1.isNotEmpty() && f2.isNotEmpty() && fOverlap < f1.size / 2
    println("%-16s %s  第1页=%d 第2页=%d 重复=%d".format(
        "番茄更新翻页", if (fOk) "OK  " else "FAIL", f1.size, f2.size, fOverlap,
    ))
    if (!fOk) failed++

    if (failed > 0) {
        println("榜单自检：$failed 项失败")
        kotlin.system.exitProcess(1)
    }
    println("榜单自检：全部通过")
}

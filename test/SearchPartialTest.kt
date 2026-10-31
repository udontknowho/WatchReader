import com.watchdl.core.BookHit
import com.watchdl.core.BookInfo
import com.watchdl.core.BookSource
import com.watchdl.core.MultiSource

/**
 * 边跑边上屏自检：慢源不能挡住快源。
 *
 * 以前 `invokeAll` 等全部完成才返回 —— 15 个源里最慢那个决定首屏时间（实测 15 秒）。
 * 这套用三个假源复现：快源 80ms 命中、中源 250ms 命中、慢源 5s 不回。
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("  ✓ $name") else { failures++; println("  ✗ $name  $detail") }
    }

    fun fake(name: String, delayMs: Long, hits: List<Pair<String, String>>) =
        MultiSource.NamedSource(name, object : BookSource {
            override fun search(keyword: String, page: Int): List<BookHit> {
                Thread.sleep(delayMs)
                return hits.mapIndexed { i, (n, a) ->
                    BookHit(bookId = "$name-$i", name = n, author = a, abstractText = "")
                }
            }
            override fun toc(bookId: String, name: String, author: String): BookInfo =
                BookInfo(bookId, name, author, emptyList())
            override fun open(hit: BookHit): BookInfo =
                BookInfo(hit.bookId, hit.name, hit.author, emptyList())
            override fun content(itemId: String, bookId: String): String = ""
        })

    println("[SearchPartial] 首屏不被慢源挡住")
    val sources = listOf(
        fake("快源", 80, listOf("斗破苍穹" to "天蚕土豆", "斗破苍穹外传" to "某某")),
        fake("中源", 250, listOf("斗破苍穹" to "天蚕土豆")),      // 同名书，跨源各占一行
        fake("慢源", 5_000, listOf("斗破苍穹之冰皇之子" to "谁")),
    )
    val t0 = System.nanoTime()
    val partials = mutableListOf<Pair<Int, Long>>()   // (累计条数, 到达时刻)
    var firstAt = -1L
    val res = MultiSource(sources, timeoutMs = 1_200).searchDetailed(
        "斗破苍穹",
        onPartial = { fresh, _, _ ->
            val n = partials.sumOf { it.first } + fresh.size
            partials += fresh.size to (System.nanoTime() - t0) / 1_000_000
            if (firstAt < 0) firstAt = (System.nanoTime() - t0) / 1_000_000
        },
    )
    val totalMs = (System.nanoTime() - t0) / 1_000_000

    check("首屏在 600ms 内到达", firstAt in 0..600, "firstAt=${firstAt}ms")
    check("至少 2 次增量回调", partials.size >= 2, "partials=$partials")
    check("慢源被记失败", res.failed.contains("慢源"), "${res.failed}")
    check("响应源数 2", res.responded == 2, "${res.responded}")
    check("最终 3 条（同名书跨源各占一行）", res.hits.size == 3, "${res.hits.map { it.sourceName }}")
    // 第二次调用是「热」的：第一次包含类加载开销，不能拿它判预算是否生效。
    val t1 = System.nanoTime()
    MultiSource(sources, timeoutMs = 1_200).searchDetailed("斗破苍穹")
    val warmMs = (System.nanoTime() - t1) / 1_000_000
    println("    冷 ${totalMs}ms / 热 ${warmMs}ms")
    check("预算生效（热跑 < 2s）", warmMs < 2_000, "warm=${warmMs}ms cold=${totalMs}ms")

    println("[SearchPartial] 增量条目与最终列表一致")
    val sentNames = mutableListOf<String>()
    val res2 = MultiSource(sources.take(2), timeoutMs = 1_200).searchDetailed(
        "斗破苍穹", onPartial = { fresh, _, _ -> fresh.forEach { h -> sentNames += "${h.name}|${h.sourceName}" } },
    )
    check("没有重复发同一本书", sentNames.size == sentNames.toSet().size, "$sentNames")
    check("发出去的条数不超过最终条数", sentNames.size <= res2.hits.size + 1,
        "sent=${sentNames.size} final=${res2.hits.size}")

    if (failures == 0) println("[SearchPartial] 全部通过") else
        throw AssertionError("SearchPartial 自检失败 $failures 项")
}

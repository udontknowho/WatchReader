import com.watchdl.core.*
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.TimeoutException

/** 目录抓取实测：每条源搜一本书 → 取目录 → 报章节数与分页页数。 */
fun main(args: Array<String>) {
    val src = File(args.getOrElse(0) { System.getenv("HOME") + "/novel/sources/merged/sources-ranked.json" })
    val keyword = args.getOrElse(1) { "斗破苍穹" }
    val limit = args.getOrElse(2) { "175" }.toInt()
    val conc = args.getOrElse(3) { "10" }.toInt()

    @Suppress("UNCHECKED_CAST")
    val items = (JsonParser.parse(src.readText()) as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: return
    val rules = items.mapNotNull { m ->
        val n = m["bookSourceName"]?.toString() ?: return@mapNotNull null
        LegadoImport.importOne(n, m as Map<String, Any?>).rule
    }.take(limit)
    println("源 ${rules.size} 条，关键词「$keyword」，并发 $conc")

    val pool = Executors.newFixedThreadPool(conc)
    val ok = java.util.concurrent.atomic.AtomicInteger()
    val fail = java.util.concurrent.atomic.AtomicInteger()
    val lines = java.util.Collections.synchronizedList(mutableListOf<String>())

    val futures = rules.map { r ->
        pool.submit {
            val tag = "%-22s".format(r.name.take(20))
            val e = SourceEngine(r) { u, m, b, h -> Http.fetch(u, m, b, h, null, 8000).body }
            val hits = runCatching { e.search(keyword, 1) }.getOrNull().orEmpty()
            if (hits.isEmpty()) { fail.incrementAndGet(); lines += "$tag 搜索无结果"; return@submit }
            val book = hits.first().bookId
            val t0 = System.currentTimeMillis()
            val info = runCatching { e.toc(book) }.getOrNull()
            val ms = System.currentTimeMillis() - t0
            val n = info?.chapters?.size ?: 0
            if (n > 0) {
                ok.incrementAndGet()
                lines += "$tag 目录 ${"%4d".format(n)} 章  ${"%5d".format(ms)}ms"
            } else {
                fail.incrementAndGet()
                lines += "$tag 目录空/异常 ${"%5d".format(ms)}ms"
            }
        }
    }
    // 单源硬上限 45 秒：个别源会把 TCP 握在 SYN 里，Http 的读超时管不到
    // （没连上就没有读）。不设的话整批会被一条源拖死 —— 实测 175 条卡过 10 分钟。
    futures.forEach { f -> runCatching { f.get(45, TimeUnit.SECONDS) }
        .onFailure { if (it is TimeoutException) { fail.incrementAndGet(); lines += "%-22s 超时".format("(timeout)") } } }
    pool.shutdown()

    lines.sortedBy { it }.forEach { println(it) }
    println("\n=== 目录成功 ${ok.get()} / 失败 ${fail.get()} / 共 ${rules.size} ===")
}

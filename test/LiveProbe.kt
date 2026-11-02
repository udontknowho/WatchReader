import com.watchdl.core.*
import java.io.File
import java.util.concurrent.*

/**
 * 合格书源统计：搜索 → 目录 → **取正文**。
 *
 * 「能取到正文」才算合格，只看目录会被大量「目录能出但不给正文」的源虚高。
 * 每条源单线程跑，带硬超时；结果按「搜索命中 / 目录非空 / 正文合格」三档分桶。
 *
 * 用法：`bash build.sh live [源文件] [关键词] [条数] [并发]`
 */
fun main(args: Array<String>) {
    val src = File(args.getOrElse(0) { "app/assets/sources/legado.json" })
    // 多关键词：任一命中就算可用。单关键词对只收某一类书的源误杀很大（实测 60 条里
    // 十几条「搜索无结果」其实是站点不收这本书）。逗号分隔，短路：命中就不跑后面的。
    val kws = args.getOrElse(1) { "玄鉴仙族" }.split(',', '，')
        .map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("玄鉴仙族") }
    val limit = args.getOrElse(2) { "999" }.toInt()
    val conc = args.getOrElse(3) { "10" }.toInt()
    // 分批跑全量：drop(offset).take(limit)，多进程并行不互相等超时。
    val offset = args.getOrElse(4) { "0" }.toInt()

    @Suppress("UNCHECKED_CAST")
    val items = (JsonParser.parse(src.readText()) as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: return
    data class Src(val name: String, val rule: BookSourceRule)
    val srcs = items.mapNotNull { m ->
        val n = m["bookSourceName"]?.toString() ?: return@mapNotNull null
        LegadoImport.importOne(n, m as Map<String, Any?>).rule?.let { Src(n, it) }
    }.drop(offset).take(limit)
    println("源 ${srcs.size} 条，关键词「${kws.joinToString(",")}」，并发 $conc")

    val pool = Executors.newFixedThreadPool(conc)
    val out = java.util.Collections.synchronizedList(mutableListOf<String>())
    val okC = java.util.concurrent.atomic.AtomicInteger()
    val tocC = java.util.concurrent.atomic.AtomicInteger()

    val futures = srcs.map { s ->
        pool.submit {
            val t0 = System.currentTimeMillis()
            fun ms() = "%5d".format(System.currentTimeMillis() - t0)
            val eng = SourceEngine(s.rule) { u, m, body, h ->
                Http.fetch(u, normaliseMethod(m), body, h).body
            }
            var line = "%-22s ".format(s.name.take(22))
            try {
                // 短路取第一个有命中的关键词：死源会在第一个上就失败，不多跑四次。
                var book: BookHit? = null
                var hitKw = ""
                for (kw in kws) {
                    val hs = eng.search(kw)
                    if (hs.isNotEmpty()) { book = hs.first(); hitKw = kw; break }
                }
                if (book == null) { out += "$line 搜索无结果 ${ms()}ms"; return@submit }
                val toc = eng.toc(book.bookId)
                if (toc.chapters.isEmpty()) { out += "$line 目录空 ${ms()}ms"; return@submit }
                tocC.incrementAndGet()
                // 从中间抽一章，避开卷名/前言这类非正文条目。
                val pick = toc.chapters.getOrNull(toc.chapters.size / 2) ?: toc.chapters.first()
                val text = eng.content(pick.itemId)
                val plain = text.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
                if (plain.length >= 300) {
                    okC.incrementAndGet()
                    // 正文哈希：判断两条源是否命中同一内容池（去重用）。
                    val h = java.security.MessageDigest.getInstance("MD5")
                        .digest(plain.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
                    out += "$line ✓ [$hitKw] 正文 ${plain.length} 字 / 目录 ${toc.chapters.size} 章 / 书 ${book.name.take(12)} / h=$h / ${ms()}ms"
                } else {
                    out += "$line 正文过短 ${plain.length} 字 ${ms()}ms"
                }
            } catch (e: Exception) {
                out += "$line 异常 ${e.javaClass.simpleName}: ${e.message?.take(60)} ${ms()}ms"
            }
        }
    }
    futures.forEach { f -> runCatching { f.get(90, TimeUnit.SECONDS) } }
    pool.shutdownNow()

    out.sorted().forEach { println(it) }
    println("\n=== 合格（取到正文）${okC.get()} / 目录非空 ${tocC.get()} / 共 ${srcs.size} ===")
}

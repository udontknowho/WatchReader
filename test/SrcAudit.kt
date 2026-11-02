import com.watchdl.core.*
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全量书源体检：多文件、跨文件去重、逐条落盘、断点续跑。
 *
 * 与 [LiveProbe] 的区别是这三件事，其余判定逻辑一致（搜索 → 目录 → 正文）。
 * 结果按 `名字 + bookSourceUrl` 去重，写进 JSONL：一行一条，跑一半被杀也有结果。
 *
 * 用法：
 * ```
 * bash build.sh audit <结果.jsonl> <关键词> <源文件…>
 * ```
 * 已存在的 jsonl 会被读回来，跳过跑过的源（断点续跑）。
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("用法: audit <结果.jsonl> <关键词> <源文件…>")
        kotlin.system.exitProcess(2)
    }
    val outFile = File(args[0])
    val kws = args[1].split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf("斗破苍穹") }
    val files = args.drop(2).map { File(it) }.filter { it.exists() }
    val conc = (System.getenv("AUDIT_CONC") ?: "8").toInt()

    // 断点续跑：已跑过的键跳过。
    val done = LinkedHashSet<String>()
    if (outFile.exists()) {
        outFile.forEachLine { line ->
            if (line.isNotBlank()) runCatching {
                // 只需 name 与 url，正则取出来比解析整行便宜。
                val n = Regex("\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)
                val u = Regex("\"url\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)
                if (n != null) done += "$n\u0000${u ?: ""}"
            }
        }
    }
    println("已完成 ${done.size} 条，续跑")

    data class Src(val name: String, val url: String, val rule: BookSourceRule)
    val seen = HashSet<String>(done)
    val srcs = mutableListOf<Src>()
    for (f in files) {
        @Suppress("UNCHECKED_CAST")
        val items = runCatching { JsonParser.parse(f.readText()) as? List<*> }.getOrNull()
            ?: run { println("跳过（不是数组）: ${f}"); null } ?: continue
        var added = 0
        for (it in items) {
            val m = it as? Map<*, *> ?: continue
            val n = m["bookSourceName"]?.toString() ?: continue
            val u = m["bookSourceUrl"]?.toString() ?: ""
            // 跨文件去重：同一名字+地址只跑一次，先出现的赢。
            if (!seen.add("$n\u0000$u")) continue
            val rule = LegadoImport.importOne(n, m as Map<String, Any?>).rule ?: continue
            srcs += Src(n, u, rule); added++
        }
        println("${f.name}: 新增 $added 条")
    }
    println("待跑 ${srcs.size} 条，关键词「${kws.joinToString(",")}」，并发 $conc")

    val pool = Executors.newFixedThreadPool(conc)
    val lock = Any()
    val okC = AtomicInteger(); val tocC = AtomicInteger(); val finishC = AtomicInteger()
    val w = java.io.BufferedWriter(
        java.io.OutputStreamWriter(java.io.FileOutputStream(outFile, true), Charsets.UTF_8)
    )
    val total = srcs.size

    fun emit(name: String, url: String, status: String, detail: String, ms: Long, hash: String = "") {
        // JSONL：字段名短，便于事后 jq/py 统计。手拼而不引写入器：
        // 项目里只有 JSON 读取器，这五个字段全是字符串/数字，转义几个字符就够。
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "") + "\""
        val json = "{\"name\":${q(name)},\"url\":${q(url)},\"status\":${q(status)}," +
            "\"detail\":${q(detail)},\"ms\":$ms,\"hash\":${q(hash)}}"
        synchronized(lock) {
            w.write(json); w.newLine(); w.flush()
            val n = finishC.incrementAndGet()
            if (n % 20 == 0 || n == total) println("进度 $n/$total  合格 ${okC.get()}  目录 ${tocC.get()}")
        }
    }

    val futures = srcs.map { s ->
        pool.submit {
            val t0 = System.currentTimeMillis()
            fun ms() = System.currentTimeMillis() - t0
            val eng = SourceEngine(s.rule) { u, m, body, h ->
                Http.fetch(u, normaliseMethod(m), body, h).body
            }
            try {
                var book: BookHit? = null
                var hitKw = ""
                for (kw in kws) {
                    val hs = eng.search(kw)
                    if (hs.isNotEmpty()) { book = hs.first(); hitKw = kw; break }
                }
                if (book == null) { emit(s.name, s.url, "搜索无结果", "", ms()); return@submit }
                val toc = eng.toc(book.bookId)
                if (toc.chapters.isEmpty()) { emit(s.name, s.url, "目录空", "", ms()); return@submit }
                tocC.incrementAndGet()
                val pick = toc.chapters.getOrNull(toc.chapters.size / 2) ?: toc.chapters.first()
                val text = eng.content(pick.itemId)
                val plain = text.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
                if (plain.length < 300) {
                    emit(s.name, s.url, "正文过短", "${plain.length} 字", ms()); return@submit
                }
                okC.incrementAndGet()
                val h = java.security.MessageDigest.getInstance("MD5")
                    .digest(plain.toByteArray()).joinToString("") { "%02x".format(it) }.take(10)
                emit(s.name, s.url, "合格", "正文 ${plain.length} 字 / 目录 ${toc.chapters.size} 章 / [$hitKw] 书 ${book.name.take(14)}", ms(), h)
            } catch (e: Exception) {
                emit(s.name, s.url, "异常", "${e.javaClass.simpleName}: ${e.message?.take(80)}", ms())
            }
        }
    }
    futures.forEach { f -> runCatching { f.get(180, TimeUnit.SECONDS) } }
    pool.shutdownNow()
    synchronized(lock) { w.flush(); w.close() }
    println("=== 本轮 ${srcs.size} 条：合格 ${okC.get()} / 目录非空 ${tocC.get()} ===")
    println("结果：${outFile.absolutePath}")
}

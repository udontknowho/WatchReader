import com.watchdl.core.*
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * 批量体检书源：把书源库逐条导入并真联网搜一遍，分出「能用 / 搜不到 / 死的」三档。
 *
 * 判定（每条源）：
 * - DEAD     : LegadoImport.importOne 拿不到 rule，或导入直接抛异常。
 * - SEARCH_OK: 导入成功，且固定关键词里**任一个**搜出非空命中。
 * - NO_HIT   : 导入成功但全部关键词都空/异常/超时。
 *
 * 多关键词是为了少误杀：某站只收某一类书很正常，单本书缺源不算源坏。
 *
 * 用法：bash build.sh filt [关键词,逗号分隔] [总分钟数] [源文件] [并发数]
 *
 * 产物：
 * - build/filtered-sources.json  全量明细
 * - build/sources-usable.json    只含可用源的原始 JSON 对象（结构同 legado.json）
 * - build/filt-cache.tsv         断点续跑缓存（每跑完一条就追加，不丢已做的活）
 */
fun main(args: Array<String>) {
    val keywords = args.getOrElse(0) { "" }.split(',', '，')
        .map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf("斗破苍穹", "三体", "全职高手", "诡秘之主") }
    val budgetMin = args.getOrElse(1) { "20" }.toInt()
    val budgetMs = budgetMin * 60_000L

    val srcFile = File(args.getOrElse(2) { "" }.ifBlank { "app/assets/sources/legado.json" })
    if (!srcFile.isFile) { System.err.println("找不到 ${srcFile.path}"); kotlin.system.exitProcess(1) }
    val concurrency = args.getOrElse(3) { "8" }.toIntOrNull()?.coerceIn(1, 32) ?: 8

    @Suppress("UNCHECKED_CAST")
    val items = (JsonParser.parse(srcFile.readText()) as? List<*>)?.mapNotNull { it as? Map<*, *> }
        ?: run { System.err.println("书源库 JSON 解析失败"); kotlin.system.exitProcess(1) }
    val total = items.size

    fun nameOf(m: Map<*, *>) = m["bookSourceName"]?.toString()?.trim().orEmpty()
    fun objOf(m: Map<*, *>) = (m as Map<String, Any?>)

    System.err.println("== 筛源：$total 条，关键词 $keywords，总时长上限 ${budgetMin} 分钟 ==")

    data class Detail(val name: String, val status: String, val hits: Int, val ms: Long, val reason: String)

    // 结果按条目下标落位，避免并发写 list
    val status = arrayOfNulls<String>(total)
    val hitsBy = IntArray(total)
    val msBy = LongArray(total)
    val reasonBy = arrayOfNulls<String>(total)
    val timedOut = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // 导入失败的在并发前就定成 DEAD：便宜、且不占线程
    val rules = arrayOfNulls<BookSourceRule>(total)
    for (i in items.indices) {
        val m = items[i]
        val n = nameOf(m)
        try {
            val r = LegadoImport.importOne(n, m).rule
            if (r == null) {
                status[i] = "DEAD"; reasonBy[i] = "导入失败：无 searchUrl 或规则不可转换"
            } else rules[i] = r
        } catch (e: Exception) {
            status[i] = "DEAD"; reasonBy[i] = "导入异常：${e::class.simpleName}: ${e.message?.take(120)}"
        }
    }

    val allWork = (0 until total).filter { rules[it] != null }
    val ok = AtomicInteger(); val nohit = AtomicInteger(); val dead = AtomicInteger()
    dead.addAndGet(total - allWork.size)

    // ---- 断点续跑 ----
    // 641 条跑十几分钟，中途断一次就全白做。每跑完一条就追加一行，
    // 重跑时先读缓存，已测过的直接沿用（键是源名 + 首个关键词，两者变了一概不认）。
    val cacheFile = File("build/filt-cache.tsv")
    fun cacheKey(n: String) = "$n\t${keywords.first()}"
    val cached = HashMap<String, Array<String>>()   // key -> [status, hits, ms, reason]
    if (cacheFile.isFile) {
        runCatching {
            cacheFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 5) cached[p[0] + "\t" + p[1]] = arrayOf(p[2], p[3], p[4], p.getOrElse(5) { "" })
            }
        }
        System.err.println("续跑缓存：已测 ${cached.size} 条")
    }
    val cacheLock = Any()
    runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.appendText("") }

    var reused = 0
    for (i in allWork) {
        val hit = cached[cacheKey(nameOf(items[i]))] ?: continue
        status[i] = hit[0]
        hitsBy[i] = hit[1].toIntOrNull() ?: 0
        msBy[i] = hit[2].toLongOrNull() ?: 0L
        reasonBy[i] = hit[3]
        if (hit[0] == "SEARCH_OK") ok.incrementAndGet() else nohit.incrementAndGet()
        reused++
    }
    if (reused > 0) System.err.println("复用 ${reused} 条已测结果，本次只需跑 ${allWork.size - reused} 条")

    val work = allWork.filter { status[it] == null }
    var done = AtomicInteger(total - allWork.size + reused)

    val t0 = System.currentTimeMillis()
    fun elapsed() = System.currentTimeMillis() - t0

    fun report() {
        System.err.println("已完成 ${done.get()}/$total，OK ${ok.get()}，空 ${nohit.get()}，死 ${dead.get()}")
    }
    if (done.get() > 0) report()

    // 单源超时：多数失效源会直接 RST/域名没了，等满久纯浪费。
    // 6 秒是实测的平衡点：活源首包一般 1-2 秒内到。
    val perSrcMs = System.getenv("FILT_TIMEOUT_MS")?.toIntOrNull() ?: 6_000

    val pool = Executors.newFixedThreadPool(concurrency)
    // 中间层：每条源自己再起一个线程跑「多关键词」并 join(单次超时)。
    // 不能在外层 pool 里再 submit 到同一个 pool —— E2e.kt 的注释记过这个坑：
    // 外层任务占满线程后，内层任务永远排不上队，全体被误判超时。
    val runner = Executors.newCachedThreadPool { r -> Thread(r, "kw").apply { isDaemon = true } }
    val tasks = work.map { i ->
        Callable {
            val m = items[i]; val rule = rules[i]!!; val n = nameOf(m)
            val st = System.currentTimeMillis()
            var maxHits = 0
            var lastErr = ""
            var any = false
            for (kw in keywords) {
                if (any) break
                val f = runner.submit(Callable {
                    runCatching {
                        SourceEngine(rule) { u, mm, b, h ->
                            Http.fetch(u, mm, b, h, null, perSrcMs).body
                        }.search(kw)
                    }.fold({ it.size to "" },
                           { (0 to "${it::class.simpleName}: ${it.message?.take(80)}") })
                })
                val res = runCatching { f.get((perSrcMs + 1_000).toLong(), TimeUnit.MILLISECONDS) }.getOrElse {
                    f.cancel(true); lastErr = "超时" ; -1 to "超时"
                }
                if (res.first > 0) { any = true; maxHits = res.first }
                else if (res.second.isNotBlank() && lastErr.isBlank()) lastErr = res.second
            }
            hitsBy[i] = maxHits
            msBy[i] = System.currentTimeMillis() - st
            if (any) { status[i] = "SEARCH_OK"; ok.incrementAndGet() }
            else {
                status[i] = "NO_HIT"; nohit.incrementAndGet()
                reasonBy[i] = lastErr.ifBlank { "全部关键词无命中" }
            }
            val d = done.incrementAndGet()
            // 跑完一条立刻落缓存：断开/被 kill 也不丢已做的活。
            runCatching {
                synchronized(cacheLock) {
                    cacheFile.appendText(
                        "$n\t${keywords.first()}\t${status[i]}\t${hitsBy[i]}\t${msBy[i]}\t${reasonBy[i].orEmpty().replace('\t', ' ').replace('\n', ' ')}\n"
                    )
                }
            }
            if (d % 20 == 0 || d == total) report()
            d
        }
    }
    pool.invokeAll(tasks, budgetMs, TimeUnit.MILLISECONDS)
    // 预算用尽：还没定档的就是没跑完的
    for (i in work) if (status[i] == null) {
        status[i] = "NO_HIT"; timedOut.add(nameOf(items[i])); reasonBy[i] = "总时长 $budgetMin 分钟用完，未跑完"
        nohit.incrementAndGet()
    }
    pool.shutdownNow(); runner.shutdownNow()
    val wall = elapsed()
    report()

    fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    fun detJson(i: Int) = "{\"name\":\"${esc(nameOf(items[i]))}\",\"status\":\"${status[i]}\"," +
        "\"hits\":${hitsBy[i]},\"ms\":${msBy[i]},\"reason\":\"${esc(reasonBy[i].orEmpty())}\"}"

    fun idxOf(s: String) = items.indices.filter { status[it] == s }
    val okIdx = idxOf("SEARCH_OK").sortedByDescending { hitsBy[it] }
    val noIdx = idxOf("NO_HIT")
    val deadIdx = idxOf("DEAD")

    val out = File("build").apply { mkdirs() }
    File(out, "filtered-sources.json").writeText(buildString {
        append("{\"generated\":${System.currentTimeMillis() / 1000},\"keyword\":[")
        append(keywords.joinToString(",") { "\"${esc(it)}\"" })
        append("],\"total\":$total,")
        append("\"ok\":[${okIdx.joinToString(",") { detJson(it) }}],")
        append("\"nohit\":[${noIdx.joinToString(",") { detJson(it) }}],")
        append("\"dead\":[${deadIdx.joinToString(",") { detJson(it) }}],")
        append("\"timedOut\":[${timedOut.toList().joinToString(",") { "\"${esc(it)}\"" }}],")
        append("\"usable\":[${okIdx.joinToString(",") { "\"${esc(nameOf(items[it]))}\"" }}]}")
    })

    // 只留可用源的原始对象，结构同 legado.json，可直接放回 app/assets/sources/。
    // 原始文本用 TinyJson 无写出器，所以从源文件按顶层对象切片，避免重造 780 条字段。
    val rawText = srcFile.readText()
    val slices = topLevelObjects(rawText)
    val keep = okIdx.mapNotNull { slices.getOrNull(it) }.filter { it.isNotBlank() }
    File(out, "sources-usable.json").writeText(keep.joinToString(",\n", "[\n", "\n]\n"))
    if (keep.size != okIdx.size)
        System.err.println("警告：原始切片数 ${keep.size} 与可用源 ${okIdx.size} 不一致")

    val top = okIdx.take(10).joinToString(" / ") { "${nameOf(items[it])}(${hitsBy[it]})" }
    println()
    println("总 $total 条，耗时 ${wall / 1000} 秒")
    println("SEARCH_OK ${okIdx.size} / NO_HIT ${noIdx.size} / DEAD ${deadIdx.size}" +
        (if (timedOut.isEmpty()) "" else "（其中 ${timedOut.size} 条因总时长用完未跑完）"))
    println("最健康：$top")
    println("→ build/filtered-sources.json, build/sources-usable.json（${keep.size} 条）")
}

/**
 * 按顶层数组元素切出原始 JSON 文本片段。
 *
 * 要的是「原样复制」，不能走 JsonParser 再序列化 —— 书源里有大量
 * 正则与脚本，往返一次容易变形。只在看到未转义 `{` 时开始计深度。
 */
private fun topLevelObjects(text: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var start = -1
    var inStr = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inStr) {
            if (c == '\\') { i += 2; continue }
            if (c == '"') inStr = false
            i++; continue
        }
        when (c) {
            '"' -> inStr = true
            '{' -> { if (depth == 0) start = i; depth++ }
            '}' -> { depth--; if (depth == 0 && start >= 0) { out.add(text.substring(start, i + 1)); start = -1 } }
        }
        i++
    }
    return out
}

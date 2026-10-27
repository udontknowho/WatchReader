import com.watchdl.core.*
import java.io.File
import java.util.concurrent.*

/**
 * 全链路活体自测：搜索 → 选书 → 目录 → 下载 → 存盘。
 *
 * 跑在纯 JVM 上，不依赖 Android。目的是让「改完代码对不对」这件事
 * 不用靠装 APK 手动点 —— 那是十分钟一轮，而且要人在场。
 *
 * 用法：
 * ```
 * ./build.sh e2e               # 默认关键词「斗破苍穹」，下前 3 章
 * ./build.sh e2e 诡秘之主 5     # 指定关键词和前 N 章
 * ```
 *
 * 输出五段，每段各自判定，最后给总判定。任一段红就整体退非零。
 */
fun main(args: Array<String>) {
    val kw = args.getOrElse(0) { "斗破苍穹" }
    val want = args.getOrElse(1) { "3" }.toInt()
    val srcFile = File("app/assets/sources/legado.json")
    val workDir = File("build/e2e").apply { deleteRecursively(); mkdirs() }

    var failed = 0
    val total = 6
    fun step(n: Int, name: String, ok: Boolean, detail: String) {
        if (ok) println("  [$n/$total] ✓ $name  $detail")
        else { failed++; println("  [$n/$total] ✗ $name  $detail") }
    }

    println("== e2e：关键词「$kw」，目标 $want 章 ==\n")

    // ---------- 1. 搜索 ----------
    if (!srcFile.isFile) {
        println("  [1/5] ✗ 搜索  找不到 ${srcFile.path}"); summarize(workDir, 1); return
    }
    val all = runCatching { JsonParser.parse(srcFile.readText()) as List<*> }.getOrNull()
    if (all == null) { println("  [1/5] ✗ 搜索  书源库 JSON 解析失败"); summarize(workDir, 1); return }

    val rules = all.mapNotNull { item ->
        @Suppress("UNCHECKED_CAST")
        val m = item as? Map<*, *> ?: return@mapNotNull null
        runCatching { LegadoImport.importOne(m["bookSourceName"]?.toString() ?: "", m).rule }.getOrNull()
    }
    if (rules.isEmpty()) { println("  [1/5] ✗ 搜索  一条源都导入不了"); summarize(workDir, 1); return }

    val pool = Executors.newFixedThreadPool(8)
    data class Hit(val rule: BookSourceRule, val hit: BookHit)
    val found = java.util.Collections.synchronizedList(mutableListOf<Hit>())
    val srcOk = java.util.concurrent.atomic.AtomicInteger()
    val srcFail = java.util.concurrent.atomic.AtomicInteger()

    val t0 = System.currentTimeMillis()
    // 注意：之前这里用「外层 pool 里再 submit 到同一 pool」，8 个线程全被外层任务
    // 占满，内层超时任务永远排不上队 —— 结果 79 条源被误判成超时（真因）。
    // 正确做法：每个任务直接跑，超时由 invokeAll 的总超时兑底。
    val tasks = rules.map { rule ->
        Callable {
            val hits = runCatching {
                SourceEngine(rule) { u, m, b, h -> Http.fetch(u, m, b, h, null, 10_000).body }
                    .search(kw)
            }.getOrNull()
            if (hits.isNullOrEmpty()) { srcFail.incrementAndGet(); 0 }
            else {
                srcOk.incrementAndGet()
                hits.forEach { found.add(Hit(rule, it)) }
                hits.size
            }
        }
    }
    pool.invokeAll(tasks, 180, TimeUnit.SECONDS)
    pool.shutdownNow()
    val ms1 = System.currentTimeMillis() - t0

    val names = found.map { it.hit.name.trim() }.filter { it.isNotEmpty() }.toSet()
    // 真 bug 拦截：`@{{$.author_nickname}}` 这类 legado 模板字段没求值时，
    // 原文会直接出现在搜索结果里。这类命中算失败，不能只统计条数。
    val rawTpl = found.filter { it.hit.author.contains("{{") || it.hit.name.contains("{{") }
    if (rawTpl.isNotEmpty()) {
        failed++
        println("  ✗ 未求值模板  ${rawTpl.size} 条，例如《${rawTpl[0].hit.name}》作者=${rawTpl[0].hit.author}")
    }
    step(1, "搜索", found.isNotEmpty(),
        "${rules.size} 源 → ${srcOk.get()} 个有结果 / ${srcFail.get()} 个失败，" +
            "${found.size} 条命中、${names.size} 本不同书，${ms1} ms")
    if (found.isEmpty()) { summarize(workDir, failed); return }

    // ---------- 2. 选书 ----------
    // 优先级：书名精确等于关键词 > 包含关键词 > 其他。
    //
    // 不能只挑「命中最多的那本」：实测会选到《剑破苍穹》（53 个聚合站
    // 都有它），而聚合站的 toc 和真站不一样，直接卡在第 3 段。
    // 精确同名的书在各源上差别最小，后续链路最稳。
    val exact = found.filter { it.hit.name.trim() == kw }
    val pool2 = exact.ifEmpty { found.filter { it.hit.name.contains(kw) } }.ifEmpty { found }
    // 同书名里优先选命中数少的（聚合镜像会命中很多源，真原站只有一个）
    val byName = pool2.groupBy { it.hit.name.trim() }.entries
        .sortedWith(compareBy({ it.key != kw }, { it.value.size }, { it.key.length }))
    val best = byName.first()
    val pick = best.value.first()
    step(2, "选书", best.value.isNotEmpty(),
        "《${pick.hit.name}》 ${pick.hit.author} @${pick.rule.name}" +
            "（候选 ${byName.size} 个书名，这本有 ${best.value.size} 个源）")

    // ---------- 3. 目录 ----------
    // 一个源取不到目录不代表全链路坏：逐个试候选项，取到非空目录就用。
    // 这样 e2e 量的是「这堆源里能不能找到一本能下的」而不是「某一条源准不准」。
    var used: Hit = pick
    var info: BookInfo? = null
    var tocTried = 0
    for (cand in best.value.take(20)) {
        tocTried++
        val e = SourceEngine(cand.rule) { u, m, b, h -> Http.fetch(u, m, b, h, null, 15_000).body }
        val got = runCatching { e.toc(cand.hit.bookId) }.getOrNull()
        if (got == null || got.chapters.isEmpty()) continue
        // 目录能取≠能读：规则失效的书源照样给全目录，但每章只回几十字。
        // 界面侧同一道关在 MainActivity.loadToc，这里不卡的话 e2e 测不出那个问题。
        val probe = got.chapters.filter { !it.locked }.take(3).maxOfOrNull { ch ->
            runCatching { Downloader.bodyChars(e.content(ch.itemId)) }.getOrDefault(0)
        } ?: Int.MAX_VALUE
        if (probe < Downloader.MIN_BODY_CHARS) {
            println("  ⋯ 跳过「${cand.rule.name}」：正文只有 $probe 字")
            continue
        }
        used = cand; info = got; break
    }
    val eng = SourceEngine(used.rule) { u, m, b, h -> Http.fetch(u, m, b, h, null, 15_000).body }
    if (info == null) {
        step(3, "目录", false, "试了 $tocTried 个源，全部取不到章节")
        summarize(workDir, failed); return
    }
    val ok3 = info.chapters.size >= want
    step(3, "目录", ok3,
        "${info.chapters.size} 章 @${used.rule.name}（第 $tocTried 个候选），前 3 章：" +
            info.chapters.take(3).joinToString(" / ") { it.title.take(12) })

    // ---------- 4. 下载 ----------
    val store = JobStore(File(workDir, "books"))
    val job = BookJob(info.bookId, info.name, info.author, info.chapters)
    val t2 = System.currentTimeMillis()
    val res = runCatching {
        Downloader(store, fetchChapter = { ch -> eng.content(ch.itemId) })
            .run(job.withStatuses(emptyMap()), indexes = (0 until minOf(want, info.chapters.size)).toList())
    }.getOrElse { Downloader.Result(0, want, want) }
    val ms2 = System.currentTimeMillis() - t2
    step(4, "下载", res.success >= want,
        "${res.success} 成功 / ${res.failed} 失败 / 共 ${res.total}，${ms2} ms" +
            if (res.success > 0) "（${ms2 / res.success} ms/章）" else "")

    // ---------- 4b. 间隔与并发对速度的影响 ----------
    // 同一本书各跑 2 章，量的是相对开销，不是绝对吞吐（样本短，会被网络抖动带偏）。
    run {
        val idx = 4
        if (info.chapters.size > idx + 2) {
            val probe = (idx until idx + 2).toList()
            val slow = measure(workDir, eng, job, info, probe, 350L, 1)
            val fast = measure(workDir, eng, job, info, probe, 80L, 1)
            println("  [4b] 间隔 350ms → ${slow}ms / 80ms → ${fast}ms" +
                if (fast > 0) "（快 ${"%.2f".format(slow.toDouble() / fast)} 倍）" else "")
            // 并发要拿 4 章才看得出：2 章 + 并发 3 等于没并发。
            if (info.chapters.size > idx + 4) {
                val four = (idx until idx + 4).toList()
                val seq = measure(workDir, eng, job, info, four, 80L, 1)
                val par = measure(workDir, eng, job, info, four, 80L, 3)
                println("  [4c] 4 章串行 → ${seq}ms / 并发 3 → ${par}ms" +
                    if (par > 0) "（快 ${"%.2f".format(seq.toDouble() / par)} 倍）" else "")
            }
        }
    }

    // ---------- 5. 存盘 ----------
    val chapters = runCatching { store.readChapters(info.bookId) }.getOrDefault(emptyMap())
    val txt = File(workDir, "out.txt")
    var chars = 0
    if (chapters.isNotEmpty()) {
        val sb = StringBuilder()
        sb.append(info.name).append('\n').append(info.author).append("\n\n")
        info.chapters.forEach { ch ->
            // readChapters 给的是整行 JSONL，正文在 content 字段里。
            // 以前直接拿整行去洗标签，导出的「正文」其实是 JSON，字数统计也算进了 JSON 的长度。
            val line = chapters[ch.itemId] ?: return@forEach
            val body = TinyJson.path(line, "content") ?: line
            sb.append(ch.title).append("\n\n").append(Downloader.htmlToText(body)).append("\n\n")
        }
        txt.writeText(sb.toString())
        chars = sb.length
    }
    val ok = chapters.size >= minOf(want, info.chapters.size) && chars > 200
    step(5, "存盘", ok,
        "${chapters.size} 章正文，${txt.length() / 1024} KB，${chars} 字 → ${txt.path}")

    // ---------- 6. 正文完整性 ----------
    // 真 bug 拦截：神话之后那个源的正文规则 `id.chaptercontent@p@html` 命中 54 个 `<p>`，
    // 只取第一项时每章只剩 16 字，但旧阈值 `chars > 200` 三章加起来 465 字照样绿。
    // 现在按“最少一章”卡：正常章节至少 300 字，否则当截断。
    val perChapter = info.chapters.mapNotNull { ch ->
        chapters[ch.itemId]?.let { ch.title to Downloader.htmlToText(TinyJson.path(it, "content") ?: it).length }
    }.take(minOf(want, info.chapters.size))
    val thin = perChapter.filter { it.second < 300 }
    step(6, "正文完整", thin.isEmpty(),
        if (thin.isEmpty()) "每章最少 ${perChapter.minOfOrNull { it.second } ?: 0} 字"
        else "${thin.size} 章不足 300 字：" + thin.take(3).joinToString(" / ") { "${it.first}=${it.second}字" })

    summarize(workDir, failed)
}

/** 去掉正文里的 HTML 标签，只留文字。存盘和字数统计都要用。 */
private fun stripTags(s: String): String = s
    .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
    .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
    .replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("&nbsp;"), " ")
    .replace(Regex("&amp;"), "&")
    .replace(Regex("&lt;"), "<")
    .replace(Regex("&gt;"), ">")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

private fun summarize(workDir: File, failed: Int) {
    println()
    if (failed == 0) {
        println(">>> e2e 全部通过（产物 ${workDir.path}）")
    } else {
        println(">>> e2e 失败 $failed 段")
        kotlin.system.exitProcess(1)
    }
}

/**
 * 用指定章间隔跑一遍，返回耗时毫秒。
 *
 * 每次建独立 JobStore：共用的话第二次会认为章「已下载」直接跳过，耗时就成 0。
 */
private fun measure(
    workDir: File,
    eng: SourceEngine,
    job: BookJob,
    info: BookInfo,
    indexes: List<Int>,
    gapMs: Long,
    concurrency: Int = 1,
): Long {
    val dir = File(workDir, "gap-$gapMs-c$concurrency").apply { deleteRecursively(); mkdirs() }
    val store = JobStore(dir)
    val fresh = BookJob(info.bookId, info.name, info.author, info.chapters)
    val t = System.currentTimeMillis()
    runCatching {
        Downloader(
            store, fetchChapter = { ch -> eng.content(ch.itemId) },
            gapMs = gapMs, concurrency = concurrency,
        ).run(fresh.withStatuses(emptyMap()), indexes = indexes)
    }
    return System.currentTimeMillis() - t
}

package com.watchdl.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 多源聚合搜索。
 *
 * 并发跑一组 [BookSource]，合并结果，单个源失败/超时静默跳过。
 *
 * 参数取值理由（手表端）：
 * - 并发 5：同时打 54 个源会把手表拖垮，5 个能在 15 秒内覆盖大部分
 * - 总超时 15 秒：用户等不起更久，先拿到快的
 * - 失败静默但计数：某个源挂掉不该影响整体，但要让用户知道「有几个没响应」
 */
class MultiSource(
    /** 参与搜索的源。 */
    private val sources: List<NamedSource>,
    /**
     * 全部可用源（含不参与搜索的）。[pickFor] 在这里找。
     *
     * 搜索受并发与超时限制只能跑一部分，但**目录与正文得能从任何一个源取**：
     * 搜索结果里的源名一定在搜索集内，而换源重试需要更大的池。
     */
    private val pool: List<NamedSource> = sources,
    private val concurrency: Int = 5,
    private val timeoutMs: Long = 15_000,
) : BookSource {

    /** 带名字的源。[name] 用于结果标注与失败统计。 */
    /**
     * [host] 只在重名时用来消歧。源库实测 88 组重名：
     * `漫客栈` 同时有 `comic.mkzhan.com` 与 `www.mkzhan.com`，`酷我小说` 三条不同接口。
     * 去掉装饰符后还会再撞（`" 绾书文学"` 与 `"绾书文学"`）。
     */
    data class NamedSource(val name: String, val source: BookSource, val host: String = "")

    /** 一次聚合搜索的结果。 */
    data class Result(
        val hits: List<BookHit>,
        /** 参与搜索的源数量。 */
        val total: Int,
        /** 有响应的源数量（返回了非空结果）。 */
        val responded: Int,
        /** 失败或超时的源名。 */
        val failed: List<String>,
    )

    /** 可搜索的源总数（含尚未轮到的那批）。换一批源时用来自增起点。 */
    val searchable: Int get() = pool.size

    /** 全部源（含不参与搜索的）。换源屏要靠它列出「还能从哪些源取这本书」。 */
    fun allNamed(): List<NamedSource> = pool

    /** 兼容 [BookSource] 接口：只要命中列表。 */
    override fun search(keyword: String, page: Int): List<BookHit> = searchDetailed(keyword, page).hits

    /**
     * 本轮要搜的源：从 [offset] 开始按 [sources] 的顺序取 [sources].size 个，绕回开头。
     *
     * 默认配置下 [sources] 是取前 MAX_SOURCES 个，所以 offset 为 0 时就是原来的行为。
     */
    private fun rotation(offset: Int): List<NamedSource> {
        if (pool.isEmpty()) return sources
        val start = ((offset % pool.size) + pool.size) % pool.size
        if (start == 0) return sources
        val shifted = pool.drop(start) + pool.take(start)
        return shifted.take(sources.size)
    }

    /**
     * 带回统计的搜索。失败源不抛异常，只记名。
     *
     * [onProgress] 每有一个源跑完就回调一次（已完成数, 总数）。
     * 多源搜索实测要 15~20 秒，界面不吭声用户会以为卡死（P1-2）。
     * 回调可能在任意线程上发生，UI 层自己切主线程。
     */
    fun searchDetailed(
        keyword: String,
        page: Int = 1,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        /** 从第几个源开始取本轮集合（换一批源）。 */
        offset: Int = 0,
        /**
         * 边跑边报：每有一个源返回命中就回调一次，带**本次新到的**已清洗、已去重条目。
         *
         * 以前用 `invokeAll` 等全部完成才返回，首屏要等最慢的源（实测 15 秒）。
         * 现在慢源不再挡住快的。回调在任意线程上，UI 层自己切主线程。
         */
        onPartial: ((hits: List<BookHit>, done: Int, total: Int) -> Unit)? = null,
    ): Result {
        // 先存一个局部变量：rotation 每次调用都可能给不同结果，两处各调一次就错位了。
        val sources = rotation(offset)
        return runOn(sources, keyword, page, onProgress, onPartial,
            minOf(concurrency, sources.size), timeoutMs)
    }

    /**
     * 跨**全部**源并发搜一遍（含不参与常规搜索的那批）。
     *
     * 换源宁可等十几秒也要把每个源都问一遍，所以道数提到 [SWAP_LANES]、预算放到 [SWAP_BUDGET_MS]。
     * 去重、清洗、边跑边报全部沿用 [runOn]，与常规搜索同一套规则。
     */
    fun searchAll(
        keyword: String,
        page: Int = 1,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        onPartial: ((hits: List<BookHit>, done: Int, total: Int) -> Unit)? = null,
    ): Result = runOn(pool, keyword, page, onProgress, onPartial,
        minOf(SWAP_LANES, pool.size), SWAP_BUDGET_MS)

    /**
     * 搜索主体：给定源集合、并发道数与预算，跑一遍并汇总。
     *
     * 从 [searchDetailed] 原样抽出，只把「源集合、道数、预算」变成参数，
     * 去重 / 清洗 / 进度 / 边跑边报的行为一字未改。
     */
    private fun runOn(
        sources: List<NamedSource>, keyword: String, page: Int,
        onProgress: ((done: Int, total: Int) -> Unit)?,
        onPartial: ((hits: List<BookHit>, done: Int, total: Int) -> Unit)?,
        lanes: Int, budgetMs: Long,
    ): Result {
        if (sources.isEmpty()) return Result(emptyList(), 0, 0, emptyList())
        DevLog.log("SRC", "搜索「$keyword」第 $page 页开始：${sources.size} 源，并发 $lanes，预算 ${budgetMs}ms")

        val runner = Executors.newFixedThreadPool(lanes)
        val finished = java.util.concurrent.atomic.AtomicInteger(0)
        // 进度回调只在主循环里发，避免任务线程与本线程各发一次、进度条超过 100%。
        // N6：以前是 List，同一源会被记两次（onFailure 一次、invokeAll 取消后又一次），
        // 于是 responded = size - failed.size 可能变成负数。
        // 用 Set 去重，responded 单独计数。
        val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        // 有响应的源数（成功返回，不管有没有结果）。
        val respondedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val collected = java.util.Collections.synchronizedList(mutableListOf<BookHit>())
        // 已经报给界面的条目：跨源去重键。边跑边报不能让同一本书报两次。
        val sent = HashMap<String, Boolean>()
        // 跑完过的源名。完成顺序与列表顺序无关，超时后不能按位置推算剩下了谁。
        val doneNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /** 一个源的结局。任务内不抛异常 —— 抛了 CompletionService 就分不清是哪个源。 */
        class Lane(val ns: NamedSource, val hits: List<BookHit>?, val error: Throwable?, val ms: Long)

        try {
            val service = java.util.concurrent.ExecutorCompletionService<Lane>(runner)
            val futures = sources.map { ns ->
                service.submit(Callable {
                    val t0 = System.currentTimeMillis()
                    runCatching { ns.source.search(keyword, page) }
                        .fold(
                            onSuccess = { hits ->
                                DevLog.log("SRC", "「${ns.name}」命中 ${hits.size} 本 ${System.currentTimeMillis() - t0}ms")
                                // 源自己没填来源名时，用我们在 MultiSource 里登记的名字补上。
                                Lane(ns, hits.map { if (it.sourceName.isBlank()) it.copy(sourceName = ns.name) else it },
                                     null, System.currentTimeMillis() - t0)
                            },
                            onFailure = { Lane(ns, null, it, System.currentTimeMillis() - t0) },
                        )
                })
            }
            // 用单调时钟算预算：手表上 wall clock 会被网络校时前后跳，
            // 往回跳一下 poll 就可能多等几秒（实测一次 8 秒）。
            val startNs = System.nanoTime()
            val budgetNs = budgetMs * 1_000_000L
            var remaining = sources.size
            while (remaining > 0) {
                val leftNs = budgetNs - (System.nanoTime() - startNs)
                if (leftNs <= 0) break
                val f = service.poll(leftNs / 1_000_000L, TimeUnit.MILLISECONDS) ?: break
                remaining--
                val lane = runCatching { f.get() }.getOrNull()
                if (lane == null) { finished.incrementAndGet(); onProgress?.invoke(finished.get(), sources.size); continue }
                doneNames.add(lane.ns.name)
                val err = lane.error
                if (err != null) {
                    failed.add(lane.ns.name)
                    // 被上层取消的源这里只记一行：以前 onFailure 记「失败」、
                    // 下面 isCancelled 分支又记「超时被取消」，同一个源两行噪音。
                    val why = if (isCancelLike(err)) "本轮超时被取消"
                              else "${err.javaClass.simpleName}: ${err.message}"
                    DevLog.log("SRC", "「${lane.ns.name}」失败 ${lane.ms}ms $why")
                } else {
                    respondedCount.incrementAndGet()
                    val rows = lane.hits ?: emptyList()
                    collected.addAll(rows)
                    // 只把「界面还没见过」的发出去。清洗规则与 [dedupe] 一致，否则
                    // 报出去的是 `<em>斗破</em>`、最终列表里是 `斗破`，对不上就会重复一行。
                    val fresh = dedupe(rows).filter { sent.put(hitKey(it), true) == null }
                    if (fresh.isNotEmpty()) onPartial?.invoke(fresh, finished.get() + 1, sources.size)
                }
                val doneNow = finished.incrementAndGet()
                onProgress?.invoke(doneNow, sources.size)
            }
            // 没轮到的源：取消并把名字记进失败，否则进度条永远不到 100%。
            if (remaining > 0) {
                futures.forEach { runCatching { it.cancel(true) } }
                sources.filter { it.name !in doneNames }.forEach { failed.add(it.name) }
                // 不能写成 `onProgress?.invoke(finished.incrementAndGet(), …)`：
                // 回调为 null 时 Kotlin 不求解参数，计数器原地不动 → 死循环。
                while (finished.get() < sources.size) {
                    val n = finished.incrementAndGet()
                    onProgress?.invoke(n, sources.size)
                }
            }
        } finally {
            runner.shutdownNow()
        }

        val hits = dedupe(collected)
        DevLog.log("SRC", "搜索「$keyword」第 $page 页：${sources.size} 源响应 ${respondedCount.get()}，失败 ${failed.size}，命中 ${hits.size} 本")
        return Result(
            hits = hits,
            total = sources.size,
            responded = respondedCount.get(),
            failed = failed.toList(),
        )
    }

    /**
     * 取消类异常。
     *
     * `invokeAll` 超时 → `cancel(true)` → 内层 `Http.fetch` 的 `task.get()` 被中断，
     * 抛出的是 InterruptedException，或 `Http` 包装后的 IOException("請求被取消")。
     * 这些不是「源坏了」，日志里必须写成上层取消。
     */
    private fun isCancelLike(e: Throwable): Boolean =
        e is InterruptedException || e is java.util.concurrent.CancellationException ||
            e.message?.contains("取消") == true

    /**
     * 去重 + 混排。
     *
     * 平权：不按源排序，只按「书名完全一致」合并。同一本书在多站命中时保留
     * 第一条，并把其它来源追加到它的 `sourceName` 里（`甲/乙`），
     * 让人知道这书在哪几个站有。
     *
     * 只按书名+作者去重，**不按 URL**：不同站的 URL 天然不同，
     * 按 URL 去重等于没去重。
     */
    /**
     * 去重 + 保留每个源的独立行。
     *
     * **不再按书名合并**（原先是把多源命中并成一条、源名拼成 `甲/乙`）。
     * 用户要从列表里直接看到「这本书哪几个源有」，并在列表上就能换源 ——
     * 合并成一行后，界面上根本看不出有几个源可选。
     *
     * 去重键改成「书名 + 作者 + 源名」：同一个源重复返回同一本书仍然要去掉，
     * 但不同源的同名书**各占一行**。
     */
    /** 去重键：书名 + 作者 + 源名。边跑边报和最终列表必须用同一个键。 */
    private fun hitKey(h: BookHit): String =
        h.name.trim() + "\u0001" + h.author.trim() + "\u0001" + h.sourceName.trim()

    private fun dedupe(hits: List<BookHit>): List<BookHit> {
        val byKey = LinkedHashMap<String, BookHit>()
        for (raw in hits) {
            // 先洗再比：各源书名常带 `<em>` 高亮，不剥的话界面直接刺出来。
            val h = clean(raw)
            val src = tidySourceName(h.sourceName)
            // 书名空的结果没有辨识度，单独留一条
            if (h.name.isBlank()) {
                byKey["\u0000" + h.bookId] = h.copy(sourceName = src)
                continue
            }
            val key = hitKey(h.copy(sourceName = src))
            if (!byKey.containsKey(key)) byKey[key] = h.copy(sourceName = src)
        }
        return byKey.values.toList()
    }

    /**
     * 源名清洗。
     *
     * 书源自带的 `bookSourceName` 经常夹前后空格和装饰符号，例如
     * `"  ●QB  qq女榜"`。直接透传到界面会多出奇怪空白，而源名是用户
     * 选择「用哪个源下载」的唯一依据，必须能一眼读清。
     */
    private fun tidySourceName(raw: String): String = tidyName(raw)

    /**
     * 洗掉搜索结果的 HTML 痕迹。
     *
     * 各站搜索页会把关键词用 `<em>`/`<font color=red>` 高亮，规则若直接取
     * `text()` 就会连标签一起拿到。界面显示 `重生<em>1980</em>` 就这么来的。
     * 这里统一剥掉标签并还原实体，**不能指望每条书源规则自己处理**。
     */
    private fun clean(h: BookHit): BookHit {
        val n = stripTags(h.name)
        val a = stripTags(h.author)
        return if (n == h.name && a == h.author) h else h.copy(name = n, author = a)
    }

    /** 单行文本去标签。不能用 HtmlText.plain —— 它会把 `<br>` 变换行，书名不需要。 */
    private fun stripTags(s: String): String = Downloader.stripTags(s)

    override fun toc(bookId: String, name: String, author: String): BookInfo =
        throw UnsupportedOperationException("多源模式下需先确定来源源（见 pickFor）")

    override fun open(hit: BookHit): BookInfo = toc(hit.bookId, hit.name, hit.author)

    override fun content(itemId: String, bookId: String): String =
        throw UnsupportedOperationException("多源模式下需先确定来源源（见 pickFor）")

    /**
     * 按来源名找出能处理这本书的具体源。找不到返回 null。
     *
     * 重名是常态（源库 88 组）。`sourceName` 里可以带 host 后缀（`漫客栈 @comic.mkzhan.com`），
     * 带后缀时按 host 精确匹配，不带时退回按名字找第一条。
     *
     * 以前只按显示名 `firstOrNull`，于是选 B 站的书交给 A 站引擎取目录 →
     * 空目录 → 报「取不到目录」。去掉装饰符后还会再撞。
     */
    fun pickFor(hit: BookHit): BookSource? {
        // 一条结果只对应一个源（不再拼 `甲/乙`），仍保留按 `/` 拆分的兼容：
        // 旧作业或外部传入的拼接名还能用。
        val names = hit.sourceName.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        for (n in names) {
            // 带 host 后缀：按 host 精确挑
            val at = n.lastIndexOf(" @")
            if (at > 0) {
                val bare = n.substring(0, at).trim()
                val h = n.substring(at + 2).trim()
                fun bareHost(u: String) =
                    u.removePrefix("https://").removePrefix("http://").trimEnd('/')
                // 只看 host：显示名带了 `@host` 后缀，拿它和裸名比必然不等。
                pool.firstOrNull { bareHost(it.host) == h }?.let { return it.source }
                // 后缀对不上（源库更新过 host）时按裸名字再找一次
                pool.firstOrNull { matchesName(it, bare) }?.let { return it.source }
                continue
            }
            pool.firstOrNull { matchesName(it, n) }?.let { return it.source }
        }
        return null
    }

    /**
     * 名字匹配。注册时重名源带了 `@host` 后缀，所以除了整名比较，
     * 还要拿「后缀前那一段」比：否则旧作业里的裸名（`漫客栈`）找不到任何源，
     * 换源重试直接退回番茄。
     */
    private fun matchesName(ns: NamedSource, n: String): Boolean {
        if (tidySourceName(ns.name) == n || ns.name == n) return true
        val at = ns.name.lastIndexOf(" @")
        return at > 0 && tidySourceName(ns.name.substring(0, at)) == n
    }


    companion object {
        /**
         * 换源全量搜索的并发道数。12 道是手表能扛住的上限：再高会把网络栈与线程调度压死。
         */
        private const val SWAP_LANES = 12

        /**
         * 换源全量搜索的预算。
         *
         * 25 s 是用户可接受的换源等待上限；超时源仍按既有逻辑记失败名，
         * 界面照实报「失败 f 条」，不假装全部都问到了。
         */
        private const val SWAP_BUDGET_MS = 25_000L

        /**
         * 源名清洗。
         *
         * 书源自带的 `bookSourceName` 经常夹前后空格和装饰符号，例如
         * `"  ●QB  qq女榜"`。直接透传到界面会多出奇怪空白，而源名是用户
         * 选择「用哪个源下载」的唯一依据，必须能一眼读清。
         *
         * 同时用于注册时判重名：清洗后撞名的源要加 host 后缀（N7）。
         */
        fun tidyName(raw: String): String {
            val s = raw.replace(Regex("\\s+"), " ").trim()
            // 去掉开头的装饰符号（● ○ ◆ ◇ ■ ▲ ★ 等），保留字母数字中文
            return s.trimStart { it in "●○◆◇■▲★☆◎※·•-—" }.trim()
        }
    }
}

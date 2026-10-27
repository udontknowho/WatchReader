package com.watchdl.core

import java.io.File
import java.io.RandomAccessFile

/**
 * 下载引擎。串行跑：手表上并发没有收益，反而更容易触发风控。
 *
 * 落盘照抄 TND 的双写：正文按章追加进 chapters.jsonl，进度写 job.json。
 * 崩溃最多丢一章，恢复时以 jsonl 为准。
 */
class Downloader(
    private val store: JobStore,
    private val fetchChapter: (ChapterRef) -> String,
    private val log: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /**
     * 章与章之间的间隔。
     *
     * 默认值 350ms 是为通用书源定的（怕把对方打急）。番茄是自家公开接口，
     * 不需要这么宽，由调用方传小值。实测 350ms 占单章耗时约 40%。
     */
    private val gapMs: Long = Backoff.GAP_MS,

    /**
     * 一批内并发跑几章。
     *
     * 默认 1（串行）—— 通用书源并发会显著提高风控概率，不替用户做主。
     * 番茄是自家公开接口，由 [com.watchdl.DownloadService] 传 3。
     */
    private val concurrency: Int = 1,

    /**
     * 取消判定。为真时尽快收尾：不再开新章，已完成的落盘，最后状态写 CANCELLED。
     *
     * 以前的取消只是 interrupt + stopSelf：请求已在飞时线程照旧跑完，
     * finally 又把状态写成 PAUSED，界面分不清「用户取消」和「被系统杀了」。
     */
    private val isCancelled: () -> Boolean = { false },

    /**
     * 单章取数的硬上限（毫秒）。0 表示不限制。
     *
     * 存在的理由：一个卡住的源会把整本书拖死。Http 单次请求虽有时限，
     * 但「重试 4 次 + 逐次退避」叠起来能到两分钟，这期间进度条完全不动，
     * 用户只能以为卡死了。到点就当作本章失败，让下载继续往下走。
     */
    private val chapterTimeoutMs: Long = 60_000,
) {

    private val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun cancelled(): Boolean = interrupted.get() || isCancelled()

    /** 下载回调：用于更新界面。 */
    var onChapter: ((index: Int, status: ChapterStatus, title: String) -> Unit)? = null

    /**
     * 开始取某一章时回调，**在发请求之前**。
     *
     * 用途只有一个：让界面知道「现在在弄第几章」。没有它的话，遇到慢源时
     * 从上一章结束到下一章结束之间界面毫无变化，看上去就是卡死了。
     */
    var onChapterStart: ((index: Int, title: String) -> Unit)? = null

    /**
     * 跑一本书。
     *
     * @param indexes 要下载的章节下标；不传则下全书（跳过锁定章）
     * @param retryFailed 续传时是否重跑上次失败的章，默认重跑
     * @return 本次运行的结果统计
     */
    fun run(job: BookJob, indexes: List<Int>? = null, retryFailed: Boolean = true): Result {
        interrupted.set(false)
        val targets = (indexes ?: job.chapters.indices.toList())
            .filter { it in job.chapters.indices }

        val statuses = job.statuses.toMutableMap()
        // 锁定章直接标记，不浪费请求
        for (i in targets) {
            if (job.chapters[i].locked && statuses[i] == null) statuses[i] = ChapterStatus.LOCKED
        }
        // 续传默认重跑失败章：不重跑的话，用户没有任何办法补回上一次失败的内容
        val todo = targets.filter {
            when (statuses[it]) {
                null, ChapterStatus.PENDING -> true
                ChapterStatus.FAILED -> retryFailed
                else -> false
            }
        }
        log("待下载 ${todo.size} 章，跳过已下 ${targets.count { statuses[it] == ChapterStatus.DONE }} 章" +
            "，跳过锁定 ${targets.count { statuses[it] == ChapterStatus.LOCKED }} 章")

        var cursor = 0
        var size = BatchPlan.initialSize(todo.size)
        var ok = 0
        var fail = 0
        // 并发跑时多个线程会同时改 statuses / 计数器 / 落盘，全部走这把锁。
        val lock = Any()

        persist(job, statuses, resume = true)
        while (cursor < todo.size && !cancelled()) {
            val batch = todo.subList(cursor, minOf(cursor + size, todo.size))
            // 7.3 小项：这里原本还有个 `done` 列表，只 add 从不读，纯占内存。
            var batchFailed = false

            // 本批分给几个线程。批次小的时候（缩批后）自然退回串行。
            val lanes = minOf(concurrency, batch.size).coerceAtLeast(1)
            val chunks = if (lanes == 1) listOf(batch.toList()) else {
                (0 until lanes).map { lane ->
                    batch.filterIndexed { idx, _ -> idx % lanes == lane }
                }.filter { it.isNotEmpty() }
            }

            val runners = chunks.map { chunk ->
                Thread {
                    for ((n, i) in chunk.withIndex()) {
                        if (cancelled()) break
                        val ch = job.chapters[i]
                        onChapterStart?.invoke(i, ch.title)
                        var success = false
                        var lastError: String? = null
                        for (attempt in 0 until Backoff.MAX_ATTEMPTS) {
                            try {
                                val html = withChapterTimeout(chapterTimeoutMs) { fetchChapter(ch) }
                                checkBody(html, ch.title)
                                synchronized(lock) { writeChapter(job, ch, html) }
                                synchronized(lock) {
                                    statuses[i] = ChapterStatus.DONE
                                    ok++
                                }
                                onChapter?.invoke(i, ChapterStatus.DONE, ch.title)
                                success = true
                                break
                            } catch (e: ChapterTimeoutException) {
                                // 超时不再重试：卡死的源重试 4 次也只是再卡 4 遍，
                                // 整本书都被它拖死。直接记本章失败，往下走。
                                lastError = e.message
                                break
                            } catch (e: Exception) {
                                lastError = e.message ?: e.javaClass.simpleName
                                log("第 ${i + 1} 章第 ${attempt + 1} 次失败：$lastError")
                                // 取消时不要再重试，也不要记成失败：这一章只是没轮到。
                                if (cancelled()) break
                                if (attempt < Backoff.MAX_ATTEMPTS - 1) pause(Backoff.delayMs(attempt))
                            }
                        }
                        if (!success && !cancelled()) {
                            synchronized(lock) {
                                statuses[i] = ChapterStatus.FAILED
                                fail++
                                batchFailed = true
                            }
                            onChapter?.invoke(i, ChapterStatus.FAILED, "${ch.title}（$lastError）")
                        }
                        // 每章都写一次进度：手表随时可能被杀
                        synchronized(lock) { persist(job, statuses) }
                        // 同一线程内串行，线程间并行 —— 节流作用保留，但不再叠加。
                        if (n < chunk.size - 1 && gapMs > 0) pause(gapMs)
                    }
                }
            }
            runners.forEach { it.start() }
            try {
                runners.forEach { it.join() }
            } catch (e: InterruptedException) {
                interrupted.set(true)
                runners.forEach { it.interrupt() }
                runners.forEach { runner ->
                    while (runner.isAlive) try { runner.join() } catch (_: InterruptedException) { }
                }
                Thread.currentThread().interrupt()
            }

            if (batchFailed && batch.size > 1 && !cancelled()) {
                // 缩批重跑这批里失败的
                val retry = batch.filter { statuses[it] == ChapterStatus.FAILED }
                val nextSize = BatchPlan.shrink(size, retry.size)
                if (nextSize != null) {
                    size = nextSize
                    log("批内有失败，批大小收到 $size，重试 ${retry.size} 章")
                    for (i in retry) {
                        if (cancelled()) break
                        try {
                            val html = withChapterTimeout(chapterTimeoutMs) { fetchChapter(job.chapters[i]) }
                            checkBody(html, job.chapters[i].title)
                            writeChapter(job, job.chapters[i], html)
                            statuses[i] = ChapterStatus.DONE
                            ok++; fail--
                            onChapter?.invoke(i, ChapterStatus.DONE, job.chapters[i].title)
                        } catch (e: Exception) {
                            if (e is InterruptedException) interrupted.set(true)
                            log("重试仍失败：第 ${i + 1} 章 ${e.message}")
                        }
                        persist(job, statuses)
                    }
                }
            }
            cursor += batch.size
        }

        val finished = job.chapters.indices.all {
            val s = statuses[it]
            s == ChapterStatus.DONE || s == ChapterStatus.LOCKED || s == ChapterStatus.SKIPPED
        }
        persist(job, statuses, when {
            cancelled() -> JobState.CANCELLED
            finished -> JobState.DONE
            else -> JobState.FAILED
        })
        // N3：ok/fail 只是**本次运行**的增量。续传时只下 5 章会报
        // 「成功 5 失败 0」，而书里已有 1200 章 —— 用户以为只下了 5 章。
        // 全书口径单独给：doneTotal / lockedTotal 从 statuses 数。
        val all = statuses.values
        val doneTotal = all.count { it == ChapterStatus.DONE }
        val lockedTotal = all.count { it == ChapterStatus.LOCKED || it == ChapterStatus.SKIPPED }
        val failTotal = all.count { it == ChapterStatus.FAILED }
        return Result(
            success = ok,
            failed = fail,
            total = job.chapters.size,
            doneTotal = doneTotal,
            lockedTotal = lockedTotal,
            failTotal = failTotal,
        )
    }

    /** interrupt 只用于取消：别让它把 run() 打穿，取消要走到落盘那一步。 */
    private fun pause(ms: Long) {
        try {
            sleep(ms)
        } catch (e: InterruptedException) {
            interrupted.set(true)
            Thread.currentThread().interrupt()
        }
    }

    /**
     * [success]/[failed] 是**本次运行**的增量；[doneTotal]/[failTotal] 是全书口径。
     * 续传后两者差别很大，界面文案要分清（N3）。
     */
    data class Result(
        val success: Int,
        val failed: Int,
        val total: Int,
        val doneTotal: Int = success,
        val lockedTotal: Int = 0,
        val failTotal: Int = failed,
    )

    /** 单章取数超时。单独列一个类型，好跟网络错误区分开 —— 超时不重试。 */
    private class ChapterTimeoutException(msg: String) : Exception(msg)


    /**
     * 单章取数的线程池。
     *
     * 以前是 cached pool：来一个新任务就建一条线程。超时走 `f.cancel(true)`，
     * 而 socket 阻塞中的线程中断不掉，于是每条超时的章都留下一条仍在跑的线程，
     * 同时存活的线程数只增不减。一本书 3000 章碰 200 个慢章，200 条线程 + 各自的
     * 响应缓冲，手表上直接 OutOfMemoryError: unable to create native thread。
     *
     * 改固定上限 + 有界队列：满了就由调用方线程自己跑（降级为串行）。
     * 宁可慢，也不把线程数堆到爆炸。
     */
    private val fetchPool = java.util.concurrent.ThreadPoolExecutor(
        MAX_FETCH_THREADS, MAX_FETCH_THREADS, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(MAX_FETCH_THREADS * 2),
        { r -> Thread(r, "chapter-fetch").apply { isDaemon = true } },
        // 满队列时不抛异常，交给提交它的线程执行 —— 即降级为串行。
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy(),
    )

    /**
     * 带硬上限跑一次取数。
     *
     * 超时就放弃等待并抛 [ChapterTimeoutException]。**被放弃的线程还在飞**：
     * Java 无法可靠地中断阻塞中的 socket 读，`cancel(true)` 多半无效。
     * 这比整本书卡死好：那些线程终会被 Http 自己的总时限收掉。
     */
    private fun withChapterTimeout(ms: Long, block: () -> String): String {
        if (ms <= 0) return block()
        val f = fetchPool.submit(java.util.concurrent.Callable { block() })
        return try {
            f.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            f.cancel(true)
            throw ChapterTimeoutException("单章超过 ${ms / 1000} 秒，放弃")
        } catch (e: InterruptedException) {
            f.cancel(true)
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            // 取数本身抛的错，原样往外传，让上层决定要不要重试。
            throw (e.cause as? Exception) ?: e
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 池已关（作业收尾中），退化为直接跑。
            block()
        }
    }

    /**
     * 正文合格判定。不合格就抛错，让这一章记 FAILED 而不是 DONE。
     *
     * 存在的理由：以前只判 `isBlank()`。规则坏掉的书源（如 m.rulianshi.la）
     * 每章只回 21 字，照样算成功写进 jsonl，进度显示「已下载 320/320」，
     * 用户打开全书都是 21 字 —— 这就是「取不到书」。
     *
     * 正常章节至少几百字；300 这个阈值是 e2e 第 6 段用的同一口径。
     */
    private fun checkBody(html: String, title: String) {
        if (html.isBlank()) error("正文为空")
        val chars = bodyChars(html)
        if (chars < MIN_BODY_CHARS) error("正文过短（${title.take(12)} 仅 ${chars} 字）")
    }

    private fun writeChapter(job: BookJob, ch: ChapterRef, html: String) {
        // itemId 也是书源给的值，和 title/content 一样不可信。裸拼的话，
        // 链接里带引号就写出断裂行，读回时对不上 → 每轮续传重下这一章，导出时这章消失。
        val json = "{\"itemId\":${quote(ch.itemId)},\"title\":${quote(ch.title)},\"content\":${quote(html)}}"
        store.appendChapter(job.bookId, json)
    }

    /**
     * 进度落盘。正文不重复写入，只在 jsonl 里。
     *
     * 默认状态跟着取消标志走：取消后服务会马上 stopSelf，进程可能来不及走到 run() 末尾的
     * 终态落盘；如果每章的 persist 还写 RUNNING，那次 CANCELLED 会被盖掉。
     */
    private fun persist(
        job: BookJob,
        statuses: Map<Int, ChapterStatus>,
        state: JobState = if (cancelled()) JobState.CANCELLED else JobState.RUNNING,
        resume: Boolean = false,
    ) {
        job.withStatuses(statuses)
        val sb = StringBuilder()
        sb.append("{\"bookId\":").append(quote(job.bookId))
        sb.append(",\"name\":").append(quote(job.name))
        sb.append(",\"author\":").append(quote(job.author))
        sb.append(",\"state\":\"").append(state.name).append('"')
        sb.append(",\"total\":").append(job.total)
        sb.append(",\"updatedAt\":").append(System.currentTimeMillis())
        sb.append(",\"chapters\":[")
        job.chapters.forEachIndexed { i, ch ->
            if (i > 0) sb.append(',')
            sb.append("{\"itemId\":").append(quote(ch.itemId))
            sb.append(",\"title\":").append(quote(ch.title))
            sb.append(",\"locked\":").append(ch.locked)
            sb.append(",\"status\":\"").append(statuses[i]?.name ?: "PENDING").append("\"}")
        }
        sb.append("]}")
        store.writeJob(job.bookId, sb.toString(), isCancelled = { cancelled() }) { resume }
    }

    companion object {
        /**
         * 单章正文的最少字数（去空白后）。低于此值判本章失败，可重试/可重下。
         * 阈值与 e2e 第 6 段「正文完整」同一口径。
         */
        const val MIN_BODY_CHARS = 300

        /**
         * 正文有效字数（去标签、去空白）。
         *
         * 特意不走 [htmlToText]：restore() 要对整本 jsonl 逐章判，
         * 那边做四五次正则 + 逐行缩进，3000 章的书在手表上要卡住界面。
         * 这里只去标签，阈值只要粗致。
         */
        fun bodyChars(html: String): Int =
            html.replace(Regex("<[^>]+>"), "").count { !it.isWhitespace() && it != '\u3000' }

        /**
         * 取数线程上限。
         *
         * 用户可选并发 1–8 路（DownloadService.DEFAULT_LANES = 3）。池给 16 条：
         * 8 路正常取数 + 8 条留在飞上的超时线程。再多就会在手表上撞
         * `OutOfMemoryError: unable to create native thread`。
         */
        const val MAX_FETCH_THREADS = 16

        /**
         * 把目录落进 job.json，供下载服务自己读。
         *
         * N9：整份目录以前塞在 Intent extra 里走 Binder（1 MB 硬上限），
         * 书源 itemId 是完整 URL，6000 章就超限，startForegroundService
         * 在主线程抛 TransactionTooLargeException —— 点下载直接闪退。
         *
         * 已有作业时**不覆盖章节**：目录可能已经变过，而 chapters.jsonl
         * 里的正文是按旧 itemId 存的，换掉章节列表会让已下的正文对不上号。
         * 只补元数据。
         */
        fun saveCatalog(store: JobStore, info: BookInfo) {
            if (store.readJob(info.bookId) != null) return
            val sb = StringBuilder()
            sb.append("{\"bookId\":").append(quote(info.bookId))
            sb.append(",\"name\":").append(quote(info.name))
            sb.append(",\"author\":").append(quote(info.author))
            sb.append(",\"state\":\"PENDING\"")
            sb.append(",\"total\":").append(info.chapters.size)
            sb.append(",\"chapters\":[")
            info.chapters.forEachIndexed { i, ch ->
                if (i > 0) sb.append(',')
                sb.append("{\"itemId\":").append(quote(ch.itemId))
                sb.append(",\"title\":").append(quote(ch.title))
                sb.append(",\"locked\":").append(ch.locked)
                sb.append(",\"status\":\"PENDING\"}")
            }
            sb.append("]}")
            store.writeJob(info.bookId, sb.toString())
        }

        /** JSON 字符串转义。正文里引号、换行、反斜杠都很常见，必须转。 */
        fun quote(s: String): String {
            val sb = StringBuilder(s.length + 16)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        /**
         * 读回一本书的章节状态。
         *
         * 以 chapters.jsonl 为准覆盖 job.json：jsonl 每章都写，快照按批写，前者更新。
         */
        fun restore(store: JobStore, bookId: String): BookJob? {
            val raw = store.readJob(bookId) ?: return null
            val name = TinyJson.path(raw, "name") ?: bookId
            val author = TinyJson.path(raw, "author") ?: ""
            val state = runCatching { JobState.valueOf(TinyJson.path(raw, "state") ?: "PENDING") }
                .getOrDefault(JobState.PENDING)
            val chapters = TinyJson.objects(raw, "chapters").mapNotNull { o ->
                val id = TinyJson.path(o, "itemId") ?: return@mapNotNull null
                ChapterRef(
                    itemId = id,
                    title = TinyJson.path(o, "title") ?: "",
                    locked = TinyJson.path(o, "locked") == "true",
                )
            }
            val statuses = HashMap<Int, ChapterStatus>()
            TinyJson.objects(raw, "chapters").forEachIndexed { i, o ->
                TinyJson.path(o, "status")?.let { s ->
                    runCatching { statuses[i] = ChapterStatus.valueOf(s) }
                }
            }
            // jsonl 里有的章，状态一定是 DONE
            // 但旧版本没校验正文字数：规则坏掉的源会写进 21 字一章还标 DONE，
            // 用户打开全书空白。这类必须当未下载重跑，否则永远修不好。
            val downloaded = store.readChapters(bookId)
            chapters.forEachIndexed { i, ch ->
                val line = downloaded[ch.itemId] ?: return@forEachIndexed
                val body = TinyJson.path(line, "content") ?: ""
                val chars = bodyChars(body)
                statuses[i] = if (chars >= MIN_BODY_CHARS) ChapterStatus.DONE
                              else ChapterStatus.FAILED
            }
            // 进程被杀时没有机会写 DONE。RUNNING 只能表示上次异常中断，不能继续冒充运行中。
            //
            // 改判 PAUSED 而不是 FAILED：两者在界面上是两回事。FAILED 读起来像
            // 「下载出错了」，而实际情况是 ROM 把前台服务杀了、作业本身没毛病。
            // PAUSED 的语义就是「停着，可以接着跑」，用户点「继续下载」即可。
            val recoveredState = if (state == JobState.RUNNING) JobState.PAUSED else state
            return BookJob(bookId, name, author, chapters, recoveredState).withStatuses(statuses)
        }

        /**
         * 首页列表用的轻量读法（M8）：**只读 job.json，不碰 chapters.jsonl**。
         *
         * [restore] 会把整个 chapters.jsonl 读一遍并逐章比对，一本 3000 章的书
         * 就是数千行。首页一次要列 8 本，8 次全量读盘直接白屏数秒。
         * 列表只需要总数和已下载数，`job.json` 里的 status 字段就够了。
         *
         * 代价：进程被杀时没来得及写 DONE 的章会被算成未完成。
         * 首页显示的是粗略进度，点进目录时会走 [restore] 拿到准确值。
         */
        fun restoreLight(store: JobStore, bookId: String): BookJob? {
            val raw = store.readJob(bookId) ?: return null
            val name = TinyJson.path(raw, "name") ?: bookId
            val author = TinyJson.path(raw, "author") ?: ""
            val state = runCatching { JobState.valueOf(TinyJson.path(raw, "state") ?: "PENDING") }
                .getOrDefault(JobState.PENDING)
            val objs = TinyJson.objects(raw, "chapters")
            val chapters = objs.mapNotNull { o ->
                val id = TinyJson.path(o, "itemId") ?: return@mapNotNull null
                ChapterRef(
                    itemId = id,
                    title = TinyJson.path(o, "title") ?: "",
                    locked = TinyJson.path(o, "locked") == "true",
                )
            }
            val statuses = HashMap<Int, ChapterStatus>()
            objs.forEachIndexed { i, o ->
                TinyJson.path(o, "status")?.let { s ->
                    runCatching { statuses[i] = ChapterStatus.valueOf(s) }
                }
            }
            val recovered = if (state == JobState.RUNNING) JobState.PAUSED else state
            return BookJob(bookId, name, author, chapters, recovered).withStatuses(statuses)
        }

        /** 导出纯文本。正文存的是 XHTML，导出时才转文本。 */
        /**
         * 把一本已下载的书导出为纯文本。
         *
         * [order] 是从目录来的**正确章节顺序**（itemId 列表）。不传就按
         * chapters.jsonl 的**行序**——那是落盘顺序，不是阅读顺序。
         * 下载是并发的（默认 3 路），第 7 章完全可能比第 6 章先写完，
         * 于是导出文件里就会出现「最后几章跳到前面」。
         *
         * 落盘顺序只保证「不丢章」，不能拿来当章节顺序。
         */
        fun exportText(
            store: JobStore,
            bookId: String,
            out: File,
            header: String? = null,
            order: List<String>? = null,
        ): Int {
            val raw = store.readChapters(bookId)
            if (raw.isEmpty()) return 0

            // 按目录顺序重排。目录里没有的 itemId（旧作业/目录变更残留）
            // 追加到末尾，不能默默丢掉。
            val chapters: Map<String, String> = if (order.isNullOrEmpty()) raw else {
                val seen = LinkedHashMap<String, String>(raw.size)
                for (id in order) raw[id]?.let { seen[id] = it }
                for ((id, line) in raw) if (!seen.containsKey(id)) seen[id] = line
                seen
            }

            // htmlToText 是纯 CPU（多次正则替换），整本导出时是瓶颈。
            // 并行转换、按原顺序落盘 —— 分隔与书名都要保持章节顺序，不能边并行边写。
            val items = chapters.entries.toList()
            val workers = minOf(Runtime.getRuntime().availableProcessors(), 4).coerceAtLeast(1)
            val blocks = if (items.size < 32 || workers == 1) {
                items.mapIndexed { i, (_, line) -> convert(line, i + 1) }
            } else {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
                try {
                    val futures = items.mapIndexed { i, (_, line) ->
                        pool.submit(java.util.concurrent.Callable { convert(line, i + 1) })
                    }
                    futures.map { it.get() }
                } finally {
                    pool.shutdown()
                }
            }

            var n = 0
            // 写 .tmp 再改名：以前直接在目标文件上 setLength(0) 后逐段写，
            // 写到一半进程被杀就留下半截文件，而原来的完整导出已经被清空。
            val tmp = File(out.absolutePath + ".tmp")
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                // UTF-8 BOM：不加的话 Windows 记事本按 GBK 猜，全篇乱码。
                raf.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                if (!header.isNullOrBlank()) raf.write((header + "\n\n").toByteArray(Charsets.UTF_8))
                for (b in blocks) {
                    if (b == null) continue
                    raf.write(b.toByteArray(Charsets.UTF_8))
                    n++
                }
            }
            // 原子替换：rename 在 Linux 上是原子的，用户要么拿到旧文件要么拿到新的。
            if (!tmp.renameTo(out)) {
                // 跨文件系统等情况下 rename 失败，退化为直接写（总比不写强）
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            return n
        }

        /**
         * 一章 → 一段可直接落盘的文本。取不到正文返回 null（保持原行为：跳过该章）。
         *
         * [seq] 是章节序号（从 1 起）。标题里已写「第N章」的**不重复加**，
         * 否则会出现「第3章 第3章 xxx」。
         */
        private fun convert(line: String, seq: Int): String? {
            val title = TinyJson.path(line, "title") ?: ""
            val content = TinyJson.path(line, "content") ?: return null
            val head = if (title.trimStart().matches(Regex("^第\\s*[\\d零〇一二三四五六七八九十百千万两]+\\s*[章节回卷篇].*")))
                title else "第${seq}章 ${title.ifBlank { "" }}".trimEnd()
            return "$head\n\n${htmlToText(content)}\n\n"
        }

        /**
         * XHTML 转纯文本。照 TND 的做法：块级标签转换行，去标签，压空行，
         * 每段加全角缩进，首行与标题重复则删掉。
         */
        fun htmlToText(html: String): String {
            var s = html
            s = s.replace(Regex("(?i)<(script|style|header)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), "")
            s = s.replace(Regex("(?i)<(br|/p|/div|/section|/h[1-6])[^>]*>"), "\n")
            s = s.replace(Regex("<[^>]+>"), "")
            s = decodeEntities(s)
            val lines = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val body = if (lines.size > 1 && lines[0].length < 40 && lines[0].contains(lines.getOrNull(1)?.take(6) ?: "\u0000")) {
                lines.drop(1)
            } else lines
            return body.joinToString("\n") { "　　$it" }
        }

        /** 常见实体还原。TND 用有界多轮，这里做一轮够用，避免 `&amp;lt;` 之类被反复解。 */
        /**
         * 去 HTML 标签 + 还原实体。搜索结果里书名常带 `<em>` 高亮，直接用会显示成乱码。
         *
         * 番茄搜索接口实测返回 `<em>斗破苍穹</em>：萧炎归来` 这种片段。
         */
        fun stripTags(s: String): String {
            if (s.indexOf('<') < 0 && s.indexOf('&') < 0) return s.trim()
            val noTag = s.replace(Regex("(?is)<[^>]+>"), "")
            return decodeEntities(noTag).trim()
        }

        fun decodeEntities(s: String): String {
            return s
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
                .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value }
                .replace("&amp;", "&")
        }
    }
}

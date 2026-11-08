package com.watchdl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.watchdl.core.*
import com.watchdl.ui.theme.Md3
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 下载前台服务。
 *
 * 手表系统对后台进程很凶，Activity 一被切走就可能在几秒内被杀。下载这种要跑几分钟的活
 * 必须挂前台通知，否则用户锁个屏回来就发现断了。
 *
 * 一次只跑一个作业：手表上没有并发的收益，反而更容易触发风控。
 */
class DownloadService : Service() {

    companion object {
        /**
         * 番茄逐章间隔。实测单章约 500ms 网络耗时 + 350ms 间隔，
         * 降到 80ms 后固定开销几乎消失；再小就没意义了（网络本身占大头）。
         */
        private const val FANQIE_GAP_MS = 80L

        /**
         * 默认并发线程数。用户可在书源页改（存在 reader 偏好 lanes 里）。
         *
         * 3 是原来的番茄固定值，通用书源沿用它作为默认：单章请求延迟占大头，
         * 并发直接乘上去。各站限流强度不同，所以做成用户可选。
         */
        const val DEFAULT_LANES = 3

        /**
         * 正在下载的 bookId，没有则为 null。
         *
         * 界面靠它区分「后台正在跑」和「上次停了」——磁盘里的 state 读不出这个：
         * 作业跑着的时候 job.json 一直是 RUNNING，restore 会把 RUNNING 改判成 PAUSED，
         * 于是正在下载的书页会误报「上次下载已中断」。
         */
        @Volatile
        var runningBookId: String? = null

        /**
         * 正在取的那一章在目录里的下标，-1 表示没在取。
         *
         * 缓存管理页要靠它把章行的「下载中」和「待下载」区分开：只有真的在飞的那一行
         * 才画进度条与停止键。服务每次发请求前写一次，作业收尾与取消时清回 -1。
         */
        @Volatile
        var currentChapterIndex: Int = -1

        const val CHANNEL_ID = "watchdl_download"
        const val NOTIFY_ID = 1001
        const val ACTION_START = "com.watchdl.START"
        /**
         * 跑队列：不传书名，从 queue.json 取队头开跑（「全部开始」用）。
         *
         * 不复用 ACTION_START —— 单独开一本书时要清掉队列（用户点明只要这本），
         * 两个意图必需分开，否则「全部开始」排好的队会被自己的第一步清空。
         */
        const val ACTION_QUEUE = "com.watchdl.QUEUE"
        const val ACTION_CANCEL = "com.watchdl.CANCEL"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_NAME = "name"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_CHAPTERS = "chapters"     // 旧协议："itemId\u0001title\u0001locked" 用 \n 连接
        const val EXTRA_FREE_ONLY = "freeOnly"

        /**
         * 只下指定的几章（逗号连接的目录下标）。
         *
         * 缓存管理页的「下载本章」用它 —— 单章重建走同一条服务，不另写一条下载链。
         */
        const val EXTRA_INDEXES = "indexes"

        /** 进度广播，Activity 在线时直接刷新界面。 */
        const val ACTION_PROGRESS = "com.watchdl.PROGRESS"
        const val EXTRA_DONE = "done"
        const val EXTRA_FAILED = "failed"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_TITLE = "title"
        /** 非空表示本次是被熔断停下的，界面要把原因说出来（P0-3-a）。 */
        const val EXTRA_ABORT = "abort"
        const val EXTRA_FINISHED = "finished"

        fun start(
            ctx: Context,
            info: BookInfo,
            freeOnly: Boolean = true,
            indexes: List<Int>? = null,
        ) {
            // 章节不再走 Intent。整份目录 join 成字符串走 Binder，硬上限 1 MB：
            // 通用书源 itemId 是完整 URL（60–90 字符），6000 章就超限，
            // startForegroundService 在主线程抛 TransactionTooLargeException，
            // 表现为「点下载就闪退」。改成把目录落进 job.json，服务自己读。
            runCatching {
                Downloader.saveCatalog(JobStore(File(ctx.filesDir, "books")), info)
            }
            val i = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BOOK_ID, info.bookId)
                putExtra(EXTRA_NAME, info.name)
                putExtra(EXTRA_AUTHOR, info.author)
                putExtra(EXTRA_FREE_ONLY, freeOnly)
                if (!indexes.isNullOrEmpty()) putExtra(EXTRA_INDEXES, indexes.joinToString(","))
            }
            ctx.startForegroundService(i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).apply { action = ACTION_CANCEL })
        }

        /** 队列文件。服务与缓存页两边都用这一个名字。 */
        fun queueOf(filesDir: File): DownloadQueue = DownloadQueue(File(filesDir, "queue.json"))

        /** 叫醒服务去跑队列头。队列为空时服务会自己收尾退出。 */
        fun startQueue(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, DownloadService::class.java).apply { action = ACTION_QUEUE }
            )
        }
    }

    private val cancelFlag = AtomicBoolean(false)
    private val worker = AtomicReference<Thread?>(null)
    /** 当前作业的 bookId。取消时要在 stopSelf 之前就把状态落盘成 CANCELLED。 */
    private val currentBookId = AtomicReference<String?>(null)

    /** 用户选的并发线程数。作业开始时读一次，改设置不影响正在跑的作业。 */
    private fun lanes(): Int =
        getSharedPreferences("reader", MODE_PRIVATE).getInt("lanes", DEFAULT_LANES)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelFlag.set(true)
                // 先把状态落盘再中断：请求已在飞时，线程要过几秒才走到落盘那一步，
                // 这段时间里界面读到 RUNNING，会把用户取消当成崩溃中断。
                currentBookId.get()?.let { id ->
                    runCatching {
                        JobStore(File(filesDir, "books")).writeState(id, JobState.CANCELLED)
                    }
                }
                worker.get()?.interrupt()
                // 取消连带把队列作废：用户点「全部停止」是要停整队，
                // 不是停一本然后自己一本一本冒出来。
                runCatching { queueOf(filesDir).clear() }
                // S14：没有工作线程时直接退出，但两个「正在跑哪本」的状态要一起清，
                // 否则界面读 runningBookId 会一直以为后台还在跑，
                // 「继续下载」按钮点了永远走不到真正启动。
                if (worker.get() == null) {
                    runningBookId = null
                    currentBookId.set(null)
                    currentChapterIndex = -1
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_QUEUE -> {
                createChannel()
                // startForegroundService 必须在 5 秒内见到 startForeground，
                // 所以先挂个通知再判有没有活。
                startForeground(NOTIFY_ID, buildNotification("准备下载", 0, 0, 0, true))
                if (worker.get() != null) {
                    val running = currentBookId.get()
                    val existing = running?.let {
                        runCatching { Downloader.restore(JobStore(File(filesDir, "books")), it) }.getOrNull()
                    }
                    startForeground(NOTIFY_ID, buildNotification(
                        "下载任务尚未结束", existing?.count(ChapterStatus.DONE) ?: 0,
                        existing?.count(ChapterStatus.FAILED) ?: 0, existing?.total ?: 0, true))
                    return START_NOT_STICKY
                }
                val next = queueOf(filesDir).pop()
                if (next == null) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val chapters = runCatching {
                    Downloader.restore(JobStore(File(filesDir, "books")), next.bookId)?.chapters
                }.getOrNull() ?: emptyList()
                if (chapters.isEmpty()) {
                    // 目录丢了的作业排在队头会把整队卡住，直接跳过，剩下的留给下一次收尾继续。
                    notify(buildNotification("跳过《${next.name}》：目录不在了", 0, 0, 0, false))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFY_ID, buildNotification("准备下载：${next.name}", 0, 0, 0, true))
                launch(BookInfo(next.bookId, next.name, next.author, chapters), true, null)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { stopSelf(); return START_NOT_STICKY }
                // 工作线程收尾前不释放槽位，主线程不等待 join。
                if (worker.get() != null) {
                    createChannel()
                    // N10：以前这里固定打 0/0，正在下的书进度条被抹成零，
                    // 看着像卡住或重新开始了。改成读当前作业的真实进度。
                    // 如果是**另一本书**，就明说，不让用户以为点了没反应。
                    val running = currentBookId.get()
                    val existing = running?.let {
                        runCatching {
                            // 作业在 books/ 下，不是 jobs/。以前这里写成 jobs，
                            // 于是「正在下载另一本」时那条通知恒报 0/0/0。
                            Downloader.restore(JobStore(File(filesDir, "books")), it)
                        }.getOrNull()
                    }
                    val d = existing?.count(ChapterStatus.DONE) ?: 0
                    val f = existing?.count(ChapterStatus.FAILED) ?: 0
                    val t = existing?.total ?: 0
                    val title = when {
                        running == null -> "下载任务尚未结束"
                        running == bookId -> "下载任务尚未结束"
                        else -> "正在下载另一本，请稍候"
                    }
                    startForeground(NOTIFY_ID, buildNotification(title, d, f, t, true))
                    return START_NOT_STICKY
                }
                createChannel()
                startForeground(NOTIFY_ID, buildNotification("准备下载", 0, 0, 0, true))
                // 单本启动是用户点明「只要这本」：把上一轮的队列作废，
                // 否则这本下完会接着跑一堆用户已经不记得排过的书。
                runCatching { queueOf(filesDir).clear() }
                val info = parseInfo(intent)
                val freeOnly = intent.getBooleanExtra(EXTRA_FREE_ONLY, true)
                // 指定章节（单章重建）。解析失败就当没传，退回整本/免费章的旧行为。
                val explicit = intent.getStringExtra(EXTRA_INDEXES)
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.filter { it >= 0 }
                    ?.takeIf { it.isNotEmpty() }
                launch(info, freeOnly, explicit)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 按书链接找出能从哪个源下载。
     *
     * 多源后一本书可能来自任意一条 legado 书源。书链接（[BookInfo.bookId]）
     * 就是那本书在该站的 URL，逐个源比对 `rule.host` 与链接前缀即可定位。
     * 对不上（或本来就是番茄）就退回番茄 —— 与以前行为一致。
     */
    private fun resolveSource(bookId: String): BookSource {
        if (bookId.isBlank()) return fanqieSource()
        val dir = File(filesDir, "sources").apply { mkdirs() }
        val files = dir.listFiles()?.filter { it.extension == "json" } ?: return fanqieSource()
        for (f in files) {
            val rules = runCatching {
                val text = f.readText()
                if (text.contains("bookSourceName")) LegadoImport.importAll(text).mapNotNull { it.rule }
                else emptyList()
            }.getOrElse { emptyList() }
            for (rule in rules) {
                val host = rule.host.trimEnd('/')
                // 链接以该源的主机开头，就认为这本书属于这个源。
                if (host.isNotBlank() && bookId.startsWith(host, ignoreCase = true)) {
                    return LegadoSource.of(rule)
                }
            }
        }
        return fanqieSource()
    }

    private fun fanqieSource(): BookSource {
        DeviceRegister.init(File(filesDir, "fanqie"))
        return FanqieApp(DeviceRegister.get())
    }

    private fun parseInfo(intent: Intent): BookInfo {
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: bookId
        val author = intent.getStringExtra(EXTRA_AUTHOR) ?: ""
        val fromIntent = (intent.getStringExtra(EXTRA_CHAPTERS) ?: "")
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split('\u0001')
                if (p.size < 3) null
                else ChapterRef(p[0], p[1], p[2] == "true")
            }
            .toList()
        // 章节不在这里读。runJob 一开始就 Downloader.restore 一次，那才是权威版本；
        // 主线程再读一遍等于把整本 jsonl 加逐章正则在 UI 线程做两次（3000 章秒级卡顿）。
        // 旧协议传来的 extra 仍然优先（单章重建这类调用就靠它）。
        return BookInfo(bookId, name, author, fromIntent)
    }

    /**
     * 起一条下载线程。onStartCommand 与队列收尾都用它，两边行为必需一致：
     * 三个「正在跑哪本」的状态一起设，收尾统一走 [finishJob]。
     */
    private fun launch(info: BookInfo, freeOnly: Boolean, explicit: List<Int>?) {
        cancelFlag.set(false)
        val t = Thread {
            val owner = Thread.currentThread()
            try {
                runJob(info, freeOnly, explicit)
            } catch (e: Exception) {
                runCatching { notify(buildNotification("下载中断：${e.message}", 0, 0, 0, false)) }
            } finally {
                android.os.Handler(mainLooper).post { finishJob(owner) }
            }
        }.apply { isDaemon = false }
        worker.set(t)
        currentBookId.set(info.bookId)
        runningBookId = info.bookId
        t.start()
    }

    /**
     * 作业收尾：清状态，队列还有下一本就接着跑，否则停服。
     *
     * 主线程上跑（线程自己的 finally 里 post 过来），所以这里可以建界面要用的通知。
     * 队列里的书目录直接从 job.json 读：入队时挑的都是下载过的书，目录早在盘上。
     */
    private fun finishJob(owner: Thread) {
        if (!worker.compareAndSet(owner, null)) return
        runningBookId = null
        currentBookId.set(null)
        currentChapterIndex = -1
        val store = JobStore(File(filesDir, "books"))
        while (!cancelFlag.get()) {
            val next = queueOf(filesDir).pop() ?: break
            val chapters = runCatching { Downloader.restore(store, next.bookId)?.chapters }
                .getOrNull() ?: emptyList()
            if (chapters.isEmpty()) continue
            createChannel()
            startForeground(NOTIFY_ID, buildNotification("准备下载：${next.name}", 0, 0, 0, true))
            launch(BookInfo(next.bookId, next.name, next.author, chapters), true, null)
            return
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /**
     * 跑一本次下载。
     *
     * [explicit] 非空时只下这几章（缓存页的「下载本章」）。它优先于 [freeOnly]：
     * 用户点明要哪一章，就不该再拿「免费章」这个规则把它筛掉。
     */
    private fun runJob(info: BookInfo, freeOnly: Boolean, explicit: List<Int>? = null) {
        val store = JobStore(File(filesDir, "books"))
        val startedAt = System.currentTimeMillis()
        // APP 接口给完整正文（网页端只给 185 字试读片段）。
        // 第三方书源不初始化番茄设备，避免无用注册。
        val source: BookSource = resolveSource(info.bookId)

        val existing = Downloader.restore(store, info.bookId)
        val job = if (existing == null || existing.chapters.isEmpty()) {
            // 旧库章节为空时用新目录：否则会拿到一个 0 章的作业，
            // 下载直接「完成」，用户看不到任何反应。
            val base = BookJob(info.bookId, info.name, info.author, info.chapters)
            if (existing == null) base else base.withStatuses(existing.statuses)
        } else if (existing.name.isBlank() || existing.author.isBlank()) {
            // 旧版本可能已经落了空元数据，新的目录信息应补回作业快照。
            BookJob(info.bookId, info.name.ifBlank { existing.name }, info.author.ifBlank { existing.author }, existing.chapters)
                .withStatuses(existing.statuses)
        } else existing

        // N10：onChapter 是从多个下载线程调进来的（Downloader 起 lanes 条线程），
        // 以前是普通 Int 自增，计数会丢。用原子变量。
        val done = java.util.concurrent.atomic.AtomicInteger(job.count(ChapterStatus.DONE))
        val failed = java.util.concurrent.atomic.AtomicInteger(job.count(ChapterStatus.FAILED))
        val total = job.total
        fun publish(title: String, finished: Boolean = false, current: Int = -1, abort: String? = null) {
            val d = done.get()
            val f = failed.get()
            notify(buildNotification(title, d, f, total, !finished, current))
            sendBroadcast(Intent(ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_DONE, d)
                putExtra(EXTRA_FAILED, f)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FINISHED, finished)
                abort?.let { putExtra(EXTRA_ABORT, it) }
            })
        }

        try {
            val lanesNow = lanes()
            DevLog.log("DL", "开始：${info.name.ifBlank { "未命名" }} ${job.chapters.size} 章，并发 $lanesNow，间隔 ${if (source is FanqieApp) FANQIE_GAP_MS else Backoff.GAP_MS}ms")
            val dl = Downloader(
                store = store,
                fetchChapter = { ch -> source.content(ch.itemId, info.bookId) },
                log = { com.watchdl.core.DevLog.log("DL", it) },
                sleep = { Thread.sleep(it) },
                // 番茄是自家公开接口，350ms 的通用间隔对它保守过头。
                // 第三方书源保持默认值，免得把人打急。
                gapMs = if (source is FanqieApp) FANQIE_GAP_MS else Backoff.GAP_MS,
                // 并发同理：以前只对番茄开 3 个，通用书源串行（0.17 章/秒，一本两小时）。
                // 现在统一读用户选项，默认 3。
                concurrency = lanesNow,
                // 取消要走到落盘那一步：Downloader 自己把最终状态写成 CANCELLED。
                isCancelled = { cancelFlag.get() },
            )
            dl.onChapter = { _, status, title ->
                when (status) {
                    ChapterStatus.DONE -> done.incrementAndGet()
                    ChapterStatus.FAILED -> failed.incrementAndGet()
                    else -> {}
                }
                if (!cancelFlag.get()) publish(title)
            }
            // 发请求之前就报一声：遇到慢源时界面才有东西可变，
            // 否则从上一章结束到下一章结束之间通知完全静止，看着就是卡死。
            dl.onChapterStart = { i, title ->
                currentChapterIndex = i
                if (!cancelFlag.get()) publish("正在下载：${title.take(24)}", current = i)
            }
            val targets = explicit
                ?: if (freeOnly) job.chapters.indices.filter { !job.chapters[it].locked }
                   else job.chapters.indices.toList()
            val r = dl.run(job, targets)
            DevLog.log("DL", "结束：${info.name.ifBlank { "未命名" }} 成功 ${r.success} 失败 ${r.failed}" +
                "，全书 ${r.doneTotal}/${r.total}，用时 ${(System.currentTimeMillis() - startedAt) / 1000}s")
            // N3：本次只下 5 章时，说「成功 5」会让人以为全书只有 5 章。
            // 分成两句：全书进度 + 本次增量。
            val head = "已下载 ${r.doneTotal}/${r.total}" +
                if (r.lockedTotal > 0) "（${r.lockedTotal} 章需登录）" else ""
            val msg = when {
                r.failTotal > 0 -> "$head，失败 ${r.failTotal} 章"
                r.success == 0 -> "$head，无新增"
                else -> "$head，本次 +${r.success}"
            }
            // N10：取消时 dl.run() 是正常返回，以前无条件报「下载完成」，
            // finally 再补一句「已取消」，界面先看到完成再看到取消。
            // 熔断（P0-3-a）不是完成：把原因原样带给界面，否则用户只看到「结束」
            // 却不知道为什么停在 40 章。
            if (!cancelFlag.get()) publish(r.abortReason ?: msg, finished = true, abort = r.abortReason)
        } catch (e: Exception) {
            publish("下载中断：${e.message}", finished = true)
        } finally {
            if (cancelFlag.get()) publish("已取消", finished = true)
        }
    }

    override fun onDestroy() {
        cancelFlag.set(true)
        worker.get()?.interrupt()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "小说下载进度"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(
        title: String,
        done: Int,
        failed: Int,
        total: Int,
        ongoing: Boolean,
        current: Int = -1,
    ): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
                else @Suppress("DEPRECATION") Notification.Builder(this)
        // 正在下载时进度条取「已完成 + 正在处理的这一章」，
        // 否则一章跑两分钟时进度条一直不动，跟卡死没区别。
        val pos = if (current >= 0) minOf(done + 1, total) else done
        val text = if (ongoing) "$done/$total" + (if (failed > 0) "，失败 $failed" else "") else ""
        b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
            // S13：以前没设点击意图，有通知权限时点了也没反应，
            // 而界面明确叫用户「进度看通知」。
            .setContentIntent(openAppIntent())
            .setAutoCancel(!ongoing)
        if (ongoing && total > 0) {
            b.setProgress(total, pos.coerceIn(0, total), false)
        }
        // 报告 P0-1：通知上没有取消入口，用户想停只能进 App 找进度屏。
        if (ongoing) {
            b.addAction(
                Notification.Action.Builder(null, "取消", cancelIntent()).build()
            )
        }
        return b.build()
    }

    /** 通知上的「取消」直连服务，不必先开界面。 */
    private fun cancelIntent(): android.app.PendingIntent {
        val i = android.content.Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        return android.app.PendingIntent.getService(this, 1, i, flag)
    }

    /** 点通知回到 App 的进度屏。 */
    private fun openAppIntent(): android.app.PendingIntent {
        val i = android.content.Intent(this, com.watchdl.MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_MAIN
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            // 服务可能在下另一本书时被点到，带上 bookId 让界面知道往哪跳。
            currentBookId.get()?.let { putExtra(EXTRA_BOOK_ID, it) }
        }
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        return android.app.PendingIntent.getActivity(this, 0, i, flag)
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n)
    }
}

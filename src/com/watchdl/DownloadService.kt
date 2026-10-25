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
import com.watchdl.ui.Md3
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

        const val CHANNEL_ID = "watchdl_download"
        const val NOTIFY_ID = 1001
        const val ACTION_START = "com.watchdl.START"
        const val ACTION_CANCEL = "com.watchdl.CANCEL"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_NAME = "name"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_CHAPTERS = "chapters"     // 旧协议："itemId\u0001title\u0001locked" 用 \n 连接
        const val EXTRA_FREE_ONLY = "freeOnly"

        /** 进度广播，Activity 在线时直接刷新界面。 */
        const val ACTION_PROGRESS = "com.watchdl.PROGRESS"
        const val EXTRA_DONE = "done"
        const val EXTRA_FAILED = "failed"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FINISHED = "finished"

        fun start(ctx: Context, info: BookInfo, freeOnly: Boolean = true) {
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
            }
            ctx.startForegroundService(i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).apply { action = ACTION_CANCEL })
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
        Md3.configure(this)
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
                if (worker.get() == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { stopSelf(); return START_NOT_STICKY }
                // 工作线程收尾前不释放槽位，主线程不等待 join。
                if (worker.get() != null) {
                    createChannel()
                    startForeground(NOTIFY_ID, buildNotification("下载任务尚未结束", 0, 0, 0, true))
                    return START_NOT_STICKY
                }
                createChannel()
                cancelFlag.set(false)
                startForeground(NOTIFY_ID, buildNotification("准备下载", 0, 0, 0, true))
                val info = parseInfo(intent)
                val freeOnly = intent.getBooleanExtra(EXTRA_FREE_ONLY, true)
                val t = Thread {
                    val owner = Thread.currentThread()
                    try {
                        runJob(info, freeOnly)
                    } catch (e: Exception) {
                        runCatching { notify(buildNotification("下载中断：${e.message}", 0, 0, 0, false)) }
                    } finally {
                        android.os.Handler(mainLooper).post {
                            if (worker.compareAndSet(owner, null)) {
                                runningBookId = null
                                currentBookId.set(null)
                                stopForeground(STOP_FOREGROUND_DETACH)
                                stopSelf()
                            }
                        }
                    }
                }.apply { isDaemon = false }
                worker.set(t)
                currentBookId.set(bookId)
                runningBookId = bookId
                t.start()
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
        // 章节优先从 job.json 读（start() 已落盘）。旧协议传来的 extra 仍兼容。
        val chapters = fromIntent.ifEmpty {
            Downloader.restore(JobStore(File(filesDir, "books")), bookId)?.chapters ?: emptyList()
        }
        return BookInfo(bookId, name, author, chapters)
    }

    private fun runJob(info: BookInfo, freeOnly: Boolean) {
        val store = JobStore(File(filesDir, "books"))
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

        var done = job.count(ChapterStatus.DONE)
        var failed = job.count(ChapterStatus.FAILED)
        val total = job.total
        fun publish(title: String, finished: Boolean = false, current: Int = -1) {
            notify(buildNotification(title, done, failed, total, !finished, current))
            sendBroadcast(Intent(ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_DONE, done)
                putExtra(EXTRA_FAILED, failed)
                putExtra(EXTRA_TOTAL, total)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FINISHED, finished)
            })
        }

        try {
            val dl = Downloader(
                store = store,
                fetchChapter = { ch -> source.content(ch.itemId, info.bookId) },
                log = { },
                sleep = { Thread.sleep(it) },
                // 番茄是自家公开接口，350ms 的通用间隔对它保守过头。
                // 第三方书源保持默认值，免得把人打急。
                gapMs = if (source is FanqieApp) FANQIE_GAP_MS else Backoff.GAP_MS,
                // 并发同理：以前只对番茄开 3 个，通用书源串行（0.17 章/秒，一本两小时）。
                // 现在统一读用户选项，默认 3。
                concurrency = lanes(),
                // 取消要走到落盘那一步：Downloader 自己把最终状态写成 CANCELLED。
                isCancelled = { cancelFlag.get() },
            )
            dl.onChapter = { _, status, title ->
                when (status) {
                    ChapterStatus.DONE -> done++
                    ChapterStatus.FAILED -> failed++
                    else -> {}
                }
                if (!cancelFlag.get()) publish(title)
            }
            // 发请求之前就报一声：遇到慢源时界面才有东西可变，
            // 否则从上一章结束到下一章结束之间通知完全静止，看着就是卡死。
            dl.onChapterStart = { i, title ->
                if (!cancelFlag.get()) publish("正在下载：${title.take(24)}", current = i)
            }
            val targets = if (freeOnly) job.chapters.indices.filter { !job.chapters[it].locked }
                          else job.chapters.indices.toList()
            val r = dl.run(job, targets)
            publish("下载完成：成功 ${r.success}，失败 ${r.failed}", finished = true)
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
        if (ongoing && total > 0) {
            b.setProgress(total, pos.coerceIn(0, total), false)
        }
        return b.build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n)
    }
}

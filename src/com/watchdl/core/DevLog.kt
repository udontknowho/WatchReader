package com.watchdl.core

import java.io.File
import java.util.ArrayDeque

/**
 * 开发者模式日志。
 *
 * 开关关闭时 [log] 只打 logcat（[Log.d]，和原来各处直接调 Log.d 效果相同）。
 * 打开后额外留两份：内存环形缓冲（供「立即导出」时连刚才的上下文一起带走）
 * 与 [file] 追加写（进程被杀后仍能看到）。
 *
 * 只记本 App 自己的日志，不读系统 logcat。手表上读 logcat 要权限且极慢，
 * 而真正需要定位的问题都发生在自己的代码里。
 *
 * 只依赖 java.io / java.util，纯 JVM 测试里也能跑（[Log] 反射兜住）。
 */
object DevLog {
    // 一次多源搜索就是几百行，500 条装不下前后文。3000 条 × 约 200 字节 ≈ 600KB 内存。
    private const val CAP = 3000
    private const val MAX_BYTES = 2L * 1024 * 1024   // 超过就轮转一次，别把关表用户的存储吃光
    private const val TAG = "WatchDL"

    // 落盘节奏：攒够 50 条，或距上次落盘超过 2 秒。
    // 原来是每 8 条一次：密日志下（一次多源搜索几百行）IO 开销把主线程拖住；
    // 只按时间又会让进程被杀时丢掉最后两秒的日志。
    private const val FLUSH_EVERY = 50
    private const val FLUSH_EVERY_MS = 2_000L

    @Volatile var enabled: Boolean = false
        private set

    /** 日志文件；[MainActivity] 启动时用 filesDir 设置。为空则不落盘。 */
    @Volatile var file: File? = null

    private val ring = ArrayDeque<String>()
    private val lock = Any()
    private var sinceFlush = 0
    private var lastFlushAt = 0L

    /** 累计进过 ring 的条数。ring 封顶后前面会被丢掉，靠它算「还有多少条没落盘」。 */
    private var total = 0L

    /** 已写进文件的条数（相对上一次轮转）。 */
    private var written = 0L

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) {
            val f = file ?: return
            runCatching {
                f.parentFile?.mkdirs()
                if (!f.exists()) f.writeText("")
            }
            log(TAG, "开发者日志开启")
            synchronized(lock) {
                lastFlushAt = System.currentTimeMillis()
                appendLocked()   // 开启那一刻先落一条：用户点到开关就该能导到东西
            }
        }
    }

    fun log(tag: String, msg: String) = append(tag, msg.take(400))

    private fun append(tag: String, msg: String) {
        val line = "${nowText()} [${Thread.currentThread().name}] $tag $msg"
        debugLog(tag, msg)                     // 始终进 logcat
        if (!enabled) return
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > CAP) ring.removeFirst()
            total++
            sinceFlush++
            if (sinceFlush >= FLUSH_EVERY || System.currentTimeMillis() - lastFlushAt >= FLUSH_EVERY_MS) {
                appendLocked()
            }
        }
    }

    /** 缓冲里现有的全部行。 */
    fun dump(): List<String> = synchronized(lock) { ring.toList() }

    /** 立即落盘。[exportText] 与崩溃处理器用。 */
    fun forceFlush() {
        synchronized(lock) { appendLocked() }
    }

    /**
     * 导出用的完整文本：先 flush，再读**文件**（最多 2MB）。
     *
     * 以前导出只读 ring（3000 条），前面的上下文早就被挤掉了；
     * 而文件才是完整的。文件为空（没落盘过）时退回 ring。
     */
    fun exportText(): String {
        forceFlush()
        val f = file
        val text = runCatching { if (f != null && f.exists()) f.readText() else "" }.getOrDefault("")
        if (text.isNotBlank()) {
            return if (text.length > MAX_BYTES) text.takeLast(MAX_BYTES.toInt()) else text
        }
        return dump().joinToString("") { it + "\n" }
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            total = 0
            written = 0
            sinceFlush = 0
            runCatching { file?.writeText("") }
        }
    }

    @Volatile private var crashHooked = false

    /**
     * 进程级崩溃捕获：记下异常类、消息与 20 帧栈，flush 后交回原来的处理器。
     *
     * 幂等：Activity 重建会再调一次，重复挂会把上一个（就是我们自己）串成链。
     */
    fun installCrashHandler() {
        if (crashHooked) return
        crashHooked = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                // 崩溃栈不能被 log() 的 400 字截断，所以直接走 append。
                append("CRASH", "${e.javaClass.name}: ${e.message} @${t.name}")
                append("CRASH", e.stackTrace.take(20)
                    .joinToString(" | ") { "${it.methodName}(${it.fileName}:${it.lineNumber})" })
                forceFlush()
            }
            if (prev != null) prev.uncaughtException(t, e) else e.printStackTrace()
        }
    }

    // 只追加还没写过的尾段。
    private fun appendLocked() {
        val f = file ?: return
        sinceFlush = 0
        lastFlushAt = System.currentTimeMillis()
        runCatching {
            if (f.length() > MAX_BYTES) {
                f.writeText("")
                written = 0
            }
            // 待写条数只能是 total - written。以前拿 flushed 和 ring.size 比大小：
            // ring 一封顶（size == CAP == flushed）就恒判「没新行」，
            // 文件从此不再增长，只剩最早那 3000 行。
            val all = ring.toList()
            // 差值比 ring 里现存的还多，说明中间有行已被挤掉 —— 直接跳过，不能当写过。
            val pending = (total - written).coerceIn(0, all.size.toLong()).toInt()
            if (pending <= 0) return@runCatching
            val start = all.size - pending
            f.appendText(all.subList(start, all.size).joinToString("") { it + "\n" })
            written = total
        }
    }

    // SimpleDateFormat 不是线程安全的，而落盘日志来自多个下载线程。
    // 每次 new 一个在密日志下开销明显，用 ThreadLocal 各线程留一份。
    private val fmt = object : ThreadLocal<java.text.SimpleDateFormat>() {
        override fun initialValue() =
            java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    }

    private fun nowText(): String =
        (fmt.get() ?: java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US))
            .format(java.util.Date())

    // 反射调 android.util.Log：纯 JVM 测试里没有这个类，硬引用会在测试路径上抛
    // NoClassDefFoundError（LegadoImport 踩过同一个坑）。
    private fun debugLog(tag: String, msg: String) {
        try {
            val c = Class.forName("android.util.Log")
            c.getMethod("d", String::class.java, String::class.java).invoke(null, tag, msg)
        } catch (_: Throwable) {
        }
    }
}

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
    private const val CAP = 500
    private const val MAX_BYTES = 256L * 1024   // 超过就轮转一次，别把关表用户的存储吃光
    private const val TAG = "WatchDL"

    @Volatile var enabled: Boolean = false
        private set

    /** 日志文件；[MainActivity] 启动时用 filesDir 设置。为空则不落盘。 */
    @Volatile var file: File? = null

    private val ring = ArrayDeque<String>()
    private val lock = Any()
    private var tick = 0

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) {
            val f = file ?: return
            runCatching {
                f.parentFile?.mkdirs()
                if (!f.exists()) f.writeText("")
            }
            log(TAG, "开发者日志开启 ${nowText()}")
            synchronized(lock) { appendLocked() }   // 开启那一刻先落一条：用户点到开关就该能导到东西
        }
    }

    fun log(tag: String, msg: String) {
        val line = "${nowText()} $tag ${msg.take(400)}"
        debugLog(tag, msg)                     // 始终进 logcat
        if (!enabled) return
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > CAP) ring.removeFirst()
            // 每 8 条落一次盘：逐条 open/close 在手表上太贵，全丢又不扛进程被杀。
            if (++tick % 8 == 0) appendLocked()
        }
    }

    /** 缓冲里现有的全部行，供导出。 */
    fun dump(): List<String> = synchronized(lock) { ring.toList() }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            flushed = 0
            runCatching { file?.writeText("") }
        }
    }

    private var flushed = 0        // 已写进文件的行数（相对上一次轮转）

    // 只追加还没写过的尾段。以前每次把整个 ring 抄一遍：文件按 500 行的倍数膨胀，
    // 随后轮转又把刚写的清掉，结果是「最新的几行没了」。
    private fun appendLocked() {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                f.writeText("")
                flushed = 0
            }
            val all = ring.toList()
            // ring 可能已经丢弃前面若干条（封顶 500），按长度差补齐起点。
            if (all.size < flushed) flushed = 0
            val tail = if (flushed >= all.size) emptyList() else all.subList(flushed, all.size)
            if (tail.isNotEmpty()) f.appendText(tail.joinToString("") { it + "\n" })
            flushed = all.size
        }
    }

    private fun nowText(): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())

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

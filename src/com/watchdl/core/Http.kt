package com.watchdl.core

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * HTTP 取数。整个 App 只走这里，便于统一处理重定向、编码、超时。
 *
 * **必须自己跟重定向**：Java 的 `instanceFollowRedirects` 出于安全考虑不跟随
 * HTTP→HTTPS 的跨协议跳转，会直接把 301 返回给你。实测 780 条书源里有 11 条
 * 正是这种情况（站点从 http 跳到 https），不处理就全部失败。
 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private const val MAX_REDIRECTS = 5

    // ───────────────────────── 明文源（M7）─────────────────────────
    //
    // 书源库里 bookSourceUrl 大多是 `http://`，而站点其实两边都开。以前一路走明文：
    // 在公共网络上正文可被旁路读改，运营商也可能注入。现在同一 host 先探一次 https：
    // 通就用 https，不通就回落原 http（并在界面标「明文」）。
    //
    // 探测**必须缓存**，否则每个章节都多一次请求，下载慢一倍。
    // 缓存按 host 记，不按完整 URL：一个站点几十个章节页共用一条结论。

    /** host（含端口）→ https 是否可用。 */
    private val httpsOk = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 探测结果快照，界面据此给仍走明文的源标注。key 是 host（含端口）。 */
    fun cleartextHosts(): Set<String> = httpsOk.filterValues { !it }.keys.toSet()

    /**
     * 这个 host 是否已探测过、且只能走明文。
     *
     * 没探过返回 false：**不预标**。否则所有声明 http 的源一进列表
     * 就全挂上「明文」，用户看不出哪些是真降不下来的。
     */
    fun isCleartextHost(hostOrUrl: String): Boolean {
        val u = hostOrUrl.substringBefore('#')
        val key = runCatching {
            val x = URL(if (u.startsWith("http")) u else "http://$u")
            if (x.port > 0 && x.port != 80) "${x.host}:${x.port}" else x.host
        }.getOrNull() ?: return false
        return httpsOk[key] == false
    }

    /** 清空探测缓存。自检用；线上不需要。 */
    fun resetProbeCache() { httpsOk.clear() }

    /** 探测超时。手表上不能等，3 秒足够一次 TLS 握手。 */
    private const val PROBE_TIMEOUT_MS = 3_000

    /**
     * 给 `http://` 地址挑一个实际使用的地址。非 http 直接原样返回。
     *
     * 探测失败不抛异常 —— 回落到原 http，让真正的请求去报错，
     * 这样错误信息仍然是「站点怎么了」而不是「探测怎么了」。
     */
    private fun upscale(url: String): String {
        if (!url.startsWith("http://", true)) return url
        val host = runCatching {
            val u = URL(url)
            if (u.port > 0 && u.port != 80) "${u.host}:${u.port}" else u.host
        }.getOrNull() ?: return url
        val ok = httpsOk[host] ?: probeHttps(host).also { httpsOk[host] = it }
        return if (ok) "https://" + url.substring(7) else url
    }

    /**
     * 探一次 https。只关心「能不能拿到 HTTP 状态码」，不看内容、不跟重定向。
     *
     * 证书错误的站点只能回落到明文：让用户装自己的 CA 不在本工程范围内。
     */
    private fun probeHttps(host: String): Boolean {
        val task = java.util.concurrent.FutureTask {
            val conn = URL("https://$host/").openConnection() as javax.net.ssl.HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = PROBE_TIMEOUT_MS
                conn.readTimeout = PROBE_TIMEOUT_MS
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", UA)
                val code = conn.responseCode
                // 403/405 也算通：服务器在拒绝我们的方法，但 TLS 与站点都在。
                code in 200..499
            } finally {
                runCatching { conn.disconnect() }
            }
        }
        POOL.execute(task)
        return try {
            task.get((PROBE_TIMEOUT_MS + 1_000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // 探不通就断掉那条连接，别让它挂在那儿占线程
            task.cancel(true)
            false
        }
    }

    /** 抓取结果。charset 是探测到的编码，排查乱码时用得上。 */
    data class Response(val body: String, val finalUrl: String, val charset: String?, val code: Int)

    /**
     * 原始字节版：番茄的设备注册要发二进制体，正文要读 `y`/`c` 响应头。
     * 只支持单跳，不跟重定向（这两个接口都不跳）。
     */
    data class Raw(val bytes: ByteArray, val headers: Map<String, String>, val code: Int)

    /**
     * 带硬超时的原始字节抓取。
     *
     * S12：以前 `fetchRaw` 只有 connect/read 超时，没有 [fetch] 那套 FutureTask 外壳。
     * 慢速吐字的服务器单次 read 永不超时，于是设备注册这一步卡住，
     * 整本书停在「准备下载」；`ACTION_CANCEL` 的 interrupt 也打不断阻塞的 socket 读。
     * 这一步在 [com.watchdl.core.Downloader.run] 之前，章超时管不到它。
     */
    fun fetchRaw(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
        timeoutMs: Int = 15_000,
        probe: Boolean = true,
        totalTimeoutMs: Int? = null,
    ): Raw {
        require(totalTimeoutMs == null || totalTimeoutMs > 0)
        val budget = totalTimeoutMs?.toLong() ?: (timeoutMs.toLong() * TOTAL_FACTOR + 2_000)
        val probeOk = probe && budget > PROBE_TIMEOUT_MS * 2L
        val target = if (probeOk) upscale(url) else url
        val task = java.util.concurrent.FutureTask {
            fetchRawNow(target, method, body, contentType, headers, userAgent, timeoutMs)
        }
        POOL.execute(task)
        return try {
            task.get(budget, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            throw java.io.IOException("请求超时（${budget}ms，含 DNS）")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw java.io.IOException("请求被取消")
        }
    }

    /** [fetchRaw] 的实体，跑在池线程里。 */
    private fun fetchRawNow(
        url: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        userAgent: String?,
        timeoutMs: Int,
    ): Raw {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = method
        conn.setRequestProperty("User-Agent", userAgent ?: UA)
        conn.setRequestProperty("Accept-Encoding", "gzip")
        contentType?.let { conn.setRequestProperty("Content-Type", it) }
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val hs = HashMap<String, String>()
        for ((k, v) in conn.headerFields) {
            // 多值头必须合并：JDK 把多条 Set-Cookie 折到一个键上，
            // 只取 first 会丢掉 _csrfToken（起点靠它换 cookie）。
            if (k != null && v != null) hs[k.lowercase()] = v.joinToString(", ")
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        // 发了 Accept-Encoding: gzip，就得自己拆包。以前这里直接 readAll，
        // 调用方拿到的是压缩字节，转成字符串后满篇乱码 —— 肉搜和榜单页都踩过。
        val bytes = if (stream == null) ByteArray(0) else stream.use {
            val real = if (conn.contentEncoding?.contains("gzip") == true) GZIPInputStream(it) else it
            if (real === it) readAll(it) else real.use { gz -> readAll(gz) }
        }
        conn.disconnect()
        return Raw(bytes, hs, code)
    }

    /**
     * 硬超时外壳：整个请求（DNS、连接、读头、读体）交给独立线程，到点直接放弃结果。
     *
     * 卡死的真实形态是 DNS 解析阻塞：`connectTimeout` 不覆盖 getaddrinfo，
     * `disconnect()` 也打断不了它。实测线程栈停在 `conn.responseCode` 三十多分钟不动，
     * 而 30 秒那个看护线程确实跑过了 disconnect。只能放弃线程，让上层退避重试接住。
     */
    fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeoutMs: Int = 15_000,
        // 目录剩余预算不应再乘 TOTAL_FACTOR 或追加宽限时间。
        totalTimeoutMs: Int? = null,
        // 明文源探测（见 upgrade）：探测与回落都要额外花钱，后台批量请求可以关掉。
        probe: Boolean = true,
    ): Response {
        require(totalTimeoutMs == null || totalTimeoutMs > 0)
        val budget = totalTimeoutMs?.toLong() ?: (timeoutMs.toLong() * TOTAL_FACTOR + 2_000)
        // 探测本身最长 PROBE_TIMEOUT_MS，且是在**调用方线程上同步跑**的。
        // 预算比它宽不了多少时（目录分页那种 150ms 硬限），探测一跑就把预算吃光，
        // 真正的请求连发都没发出去 —— 表现为「目录超时」而不是「站点慢」。
        // 所以只在预算够宽时才探，宁可用明文，也不能让功能失效。
        val probeOk = probe && budget > PROBE_TIMEOUT_MS * 2L
        val target = if (probeOk) upscale(url) else url
        val task = java.util.concurrent.FutureTask {
            fetchNow(target, method, body, headers, referer, timeoutMs, totalTimeoutMs)
        }
        POOL.execute(task)
        return try {
            task.get(budget, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            throw java.io.IOException("请求超时（${budget}ms，含 DNS）")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw java.io.IOException("请求被取消")
        }
    }

    /**
     * 卡死的请求线程不会自己退出（阻塞在 getaddrinfo 里），所以池必须能长大：
     * 固定大小会在几次卡死后把整个下载冻住，正是要避免的那个毛病。
     */
    private val POOL = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "http").apply { isDaemon = true }
    }

    /**
     * GET/POST。
     *
     * @param referer 有些站要求带来源页
     * @param timeoutMs 连接与读取超时（手表上要短，别让用户干等）
     */
    private fun fetchNow(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeoutMs: Int = 15_000,
        totalTimeoutMs: Int? = null,
    ): Response {
        var current = url
        var m = if (method.isBlank()) "GET" else method.uppercase()
        var bodyToSend = body
        var redirects = 0

        val totalBudget = minOf(timeoutMs.toLong() * TOTAL_FACTOR, totalTimeoutMs?.toLong() ?: Long.MAX_VALUE)
        // 整次请求（含建连、重定向、读响应体）共用一份预算。
        // 以前 deadline 是在拿到 inputStream **之后**才算的，建连与等响应头的时间
        // 一点都不计入：自检里 timeoutMs=300 的慢速吐字用例实测跑到了 8.6 秒。
        val deadlineAt = System.currentTimeMillis() + totalBudget
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false          // 自己跟，理由见类注释
            val leftNow = (deadlineAt - System.currentTimeMillis()).coerceAtMost(timeoutMs.toLong())
            if (leftNow <= 0) error("请求超时（${totalBudget}ms）")
            conn.connectTimeout = leftNow.toInt()
            conn.readTimeout = leftNow.toInt()
            conn.requestMethod = m
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            referer?.let { conn.setRequestProperty("Referer", it) }
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (m == "POST" && bodyToSend != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(bodyToSend.toByteArray(Charsets.UTF_8)) }
            }

            // 总时限。readTimeout 只约束单次读：服务器每次吐几个字节时它永远不触发，
            // 实测下载线程在 getResponseCode 里挂了 8 分钟没动静、界面一直「下载中」。
            // 到点就从外部 disconnect，把读打断，让上层记一次失败并重试。
            val expired = java.util.concurrent.atomic.AtomicBoolean(false)
            val cut = Thread {
                try { Thread.sleep((deadlineAt - System.currentTimeMillis()).coerceAtLeast(0)) } catch (e: InterruptedException) { return@Thread }
                expired.set(true)
                runCatching { conn.disconnect() }
            }.apply { isDaemon = true; start() }

            val code: Int
            try {
                code = conn.responseCode
            } catch (e: Exception) {
                cut.interrupt()
                if (expired.get() || System.currentTimeMillis() >= deadlineAt) error("请求超时（${totalBudget}ms）")
                throw e
            }

            if (code in 300..399) {
                cut.interrupt()
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                    error("HTTP $code 重定向失败（Location=$loc）")
                }
                current = resolve(current, loc)
                redirects++
                // 303 与 301/302 之后的 POST 按浏览器惯例降级为 GET
                if (code == 303 || m == "POST") { m = "GET"; bodyToSend = null }
                continue
            }
            if (code !in 200..299) {
                cut.interrupt()
                conn.disconnect()
                error("HTTP $code")
            }

            val bytes: ByteArray
            val contentType: String?
            try {
                val stream = conn.inputStream
                bytes = if (conn.contentEncoding?.contains("gzip") == true) {
                    GZIPInputStream(stream).use { readAll(it, conn, deadlineAt) }
                } else {
                    stream.use { readAll(it, conn, deadlineAt) }
                }
                contentType = conn.contentType
            } catch (e: Exception) {
                cut.interrupt()
                if (expired.get() || System.currentTimeMillis() >= deadlineAt) error("请求超时（${totalBudget}ms）")
                throw e
            }
            cut.interrupt()
            val charset = sniffCharset(bytes, contentType)
            conn.disconnect()
            return Response(decode(bytes, charset), current, charset, code)
        }
    }

    /** 总时限 = 单次读超时 × 这个倍数。 */
    private const val TOTAL_FACTOR = 2

    /** 相对 Location 补成绝对。 */
    private fun resolve(base: String, location: String): String {
        if (location.startsWith("http")) return location
        val b = URL(base)
        return if (location.startsWith("/")) {
            "${b.protocol}://${b.authority}$location"
        } else {
            val path = b.path.substringBeforeLast('/', "")
            "${b.protocol}://${b.authority}$path/$location"
        }
    }

    /**
     * 读完整个流。
     *
     * `deadlineAt` 是绝对毫秒时间戳（0 表示不限）。**必须传**：慢速吐字的服务器每次
     * 吐几个字节时，单次 readTimeout 永远不触发，旧实现靠另一个线程 `disconnect()`
     * 打断，而 JDK 的 disconnect 并不能中断阻塞中的 read —— 实测要等到服务器下一字节。
     * 这里改成每次读之前把 readTimeout 压到「还剩多少时间」，到点自然抛超时。
     */
    /** 响应体字节上限。超限抛 IOException，走正常的单章失败路径，不让整进程 OOM。 */
    private const val MAX_BYTES = 32 * 1024 * 1024

    private fun readAll(s: java.io.InputStream, conn: java.net.URLConnection? = null, deadlineAt: Long = 0): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(16 * 1024)
        while (true) {
            if (deadlineAt > 0) {
                val left = deadlineAt - System.currentTimeMillis()
                if (left <= 0) error("读超时")
                (conn as? java.net.HttpURLConnection)?.readTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            val n = s.read(buf)
            if (n <= 0) break
            // 硬超时只管时间不管字节：站点吐几百 MB，或「全文阅读」页把整本塞一页，
            // 直接 OutOfMemoryError 把下载进程杀掉，job.json 停在 RUNNING 白等一轮。
            if (out.size() + n > MAX_BYTES) {
                throw java.io.IOException("响应超过上限 ${MAX_BYTES / 1024 / 1024}MB（已读 ${out.size()}）")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 从 HTTP 头或页面 meta 里嗅编码。中文小站大量用 GBK。 */
    fun sniffCharset(bytes: ByteArray, contentType: String?): String? {
        Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType ?: "")
            ?.groupValues?.get(1)?.let { return it }
        // 只看前 2 KB，避免把正文里的汉字当 meta
        val head = String(bytes, 0, minOf(2048, bytes.size), Charsets.ISO_8859_1)
        Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)
            ?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun decode(bytes: ByteArray, charset: String?): String {
        val cs = charset?.lowercase()
        return when {
            cs == null -> String(bytes, Charsets.UTF_8)
            cs == "gbk" || cs == "gb2312" || cs == "gb18030" ->
                runCatching { String(bytes, charset("GBK")) }.getOrElse { String(bytes, Charsets.UTF_8) }
            cs == "big5" ->
                runCatching { String(bytes, charset("Big5")) }.getOrElse { String(bytes, Charsets.UTF_8) }
            else -> runCatching { String(bytes, charset(cs)) }.getOrElse { String(bytes, Charsets.UTF_8) }
        }
    }
}

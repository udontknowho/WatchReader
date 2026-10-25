import com.watchdl.core.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore

private val count = java.util.concurrent.atomic.AtomicInteger(0)

/**
 * Http 重定向自检。起一个本地小服务器，不依赖外网。
 *
 * 重点验证：Java 的 instanceFollowRedirects 不跟 HTTP→HTTPS 跨协议跳转，
 * 我们的实现必须自己跟（否则 780 条书源里那 11 条 http→https 的源全废）。
 */

fun main() {
    var failures = 0
    fun check(n: String, ok: Boolean, d: String = "") {
        if (ok) println("  ✓ $n") else { failures++; println("  ✗ $n  $d") }
    }

    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    val port = server.address.port
    val base = "http://127.0.0.1:$port"

    fun handler(path: String, body: (com.sun.net.httpserver.HttpExchange) -> Unit) {
        server.createContext(path) { ex ->
            try { body(ex) } finally { ex.close() }
        }
    }

    handler("/ok") { ex ->
        val b = "<html><body>你好 Hello</body></html>".toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b)
    }
    handler("/gbk") { ex ->
        val b = "<html><body>中文编码测试</body></html>".toByteArray(charset("GBK"))
        ex.responseHeaders.add("Content-Type", "text/html; charset=gbk")
        ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b)
    }
    handler("/gbk-meta") { ex ->
        val b = "<html><head><meta charset=\"gbk\"></head><body>元标签编码</body></html>".toByteArray(charset("GBK"))
        ex.responseHeaders.add("Content-Type", "text/html")
        ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b)
    }
    handler("/r301") { ex ->
        ex.responseHeaders.add("Location", "$base/ok")
        ex.sendResponseHeaders(301, -1)
    }
    handler("/r302") { ex ->
        ex.responseHeaders.add("Location", "$base/ok")
        ex.sendResponseHeaders(302, -1)
    }
    handler("/rel") { ex ->
        ex.responseHeaders.add("Location", "/ok")
        ex.sendResponseHeaders(302, -1)
    }
    handler("/relpath") { ex ->
        ex.responseHeaders.add("Location", "ok")
        ex.sendResponseHeaders(302, -1)
    }
    handler("/loop") { ex ->
        ex.responseHeaders.add("Location", "$base/loop")
        ex.sendResponseHeaders(302, -1)
    }
    // 慢速吐字：先回头，再每 300ms 吐一个字节，永远读不完。
    // 这是实测里下载线程卡死的形态——服务器一直不断，单次读超时永远不触发。
    handler("/trickle") { ex ->
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        out.write('a'.code); out.flush()
        repeat(40) { Thread.sleep(300); out.write('a'.code); out.flush() }
    }
    handler("/404") { ex -> ex.sendResponseHeaders(404, -1) }
    handler("/post") { ex ->
        val req = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val b = "收到:$req".toByteArray(Charsets.UTF_8)
        ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b)
    }
    handler("/count") { ex ->
        val n = (count.get() + 1)
        count.set(n)
        val b = "$n".toByteArray()
        ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b)
    }
    server.start()

    // ══════ 明文源（M7）：同 host 先探 https ══════
    //
    // 这里要**在一切 https 请求之前**换掉信任库：探测代码不加信任例外，
    // 只能让 JVM 真的信任 test/ssl 里那张自签证书。
    val oldTrust = System.getProperty("javax.net.ssl.trustStore")
    val oldPass = System.getProperty("javax.net.ssl.trustStorePassword")
    val trustFile = makeTempTrustStore()
    System.setProperty("javax.net.ssl.trustStore", trustFile.absolutePath)
    System.setProperty("javax.net.ssl.trustStorePassword", "changeit")

    // 在 127.0.0.1 上同时开 http 与 https 两个端口，两者的响应正文不同：
    // 正文能直接说明**实际走了哪个方案**，不用猜。
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(File("test/ssl/probe.p12").inputStream(), "changeit".toCharArray())
    val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply {
        init(javax.net.ssl.KeyManagerFactory
                .getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(ks, "changeit".toCharArray()) }.keyManagers,
            null, null)
    }
    val https = com.sun.net.httpserver.HttpsServer.create(
        java.net.InetSocketAddress("127.0.0.1", 0), 0)
    https.httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(tls)
    val httpsHits = java.util.concurrent.atomic.AtomicInteger(0)
    https.createContext("/ok") { ex ->
        httpsHits.incrementAndGet()
        val b = "HTTPS-SIDE".toByteArray(Charsets.UTF_8)
        ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b); ex.close()
    }
    https.createContext("/r301") { ex ->
        ex.responseHeaders.add("Location", "https://127.0.0.1:${https.address.port}/ok")
        ex.sendResponseHeaders(301, -1); ex.close()
    }
    https.start()
    httpsHits.set(0)

    try {
        println("[Http] 明文源：https 可用时选 https")
        // 端口不同 → host 键不同（含端口），所以每个场景都要自己一份缓存。
        Http.resetProbeCache()
        val okHttps = "http://127.0.0.1:${https.address.port}/ok"
        val r1 = Http.fetch(okHttps)
        check("正文来自 https 端口", r1.body == "HTTPS-SIDE", r1.body)
        check("最终 URL 改成 https", r1.finalUrl.startsWith("https://"), r1.finalUrl)

        println("[Http] 明文源：缓存生效，不重复探测")
        // 探测打的是根路径 `/`，不计入 `/ok` 的计数器；所以这里只有 1 次取数。
        // 探测次数靠下一行「后续不再探测」间接验：若每轮都探，新增会 >2。
        val hitsAfterFirst = httpsHits.get()
        check("首轮只取数 1 次（探测走根路径不计数）", hitsAfterFirst == 1, "实际 $hitsAfterFirst")
        Http.fetch(okHttps); Http.fetch(okHttps)
        check("后续不再探测（每次只多 1 次取数）", httpsHits.get() == hitsAfterFirst + 2,
            "实际 ${httpsHits.get() - hitsAfterFirst} 次新增")
        check("host 未被记为明文", !Http.isCleartextHost("127.0.0.1:${https.address.port}"))

        println("[Http] 明文源：https 不可用时回落 http")
        Http.resetProbeCache()
        // 原来的 http 服务器**没有** TLS 监听，https 探过去必失败。
        val plain = "$base/ok"
        val r2 = Http.fetch(plain)
        check("正文仍来自原 http", r2.body.contains("你好 Hello"), r2.body)
        check("host 记为明文", Http.isCleartextHost("127.0.0.1:$port"))
        check("明文快照含该 host", Http.cleartextHosts().contains("127.0.0.1:$port"),
            Http.cleartextHosts().toString())
        // 回落路径也要缓存：否则每个章节都白等一次探测超时。
        val t0 = System.currentTimeMillis()
        repeat(2) { Http.fetch(plain) }
        check("回落结论被缓存（两次取数 <1s）", System.currentTimeMillis() - t0 < 1_000,
            "耗时 ${System.currentTimeMillis() - t0}ms")

        println("[Http] 明文源：重定向到 https 仍正常")
        Http.resetProbeCache()
        val r3 = Http.fetch("https://127.0.0.1:${https.address.port}/r301")
        check("跨协议重定向回归未被破坏", r3.body == "HTTPS-SIDE", r3.body)

        println("[Http] 明文源：probe=false 完全不碰 https")
        Http.resetProbeCache()
        val before = httpsHits.get()
        // 该端口只有 TLS，纯 http 过去拿不到正常响应 —— 抛异常也是预期结果，
        // 关键只是**没有**再打 https。以前这里断言 r4.code，异常没接住，
        // 整个自检进程被 SocketException 打断，后面的测试全部不跑了。
        val r4 = runCatching { Http.fetch("http://127.0.0.1:${https.address.port}/ok", probe = false) }
        check("没触发 https 请求", httpsHits.get() == before, "新增 ${httpsHits.get() - before}")
        check("probe=false 时不写缓存", !Http.isCleartextHost("127.0.0.1:${https.address.port}"))
        check("纯 http 打 TLS 端口会失败或返回非 200",
            r4.getOrNull()?.let { it.code != 200 } ?: true, r4.exceptionOrNull()?.toString() ?: "")
    } finally {
        https.stop(0)
        if (oldTrust == null) System.clearProperty("javax.net.ssl.trustStore")
        else System.setProperty("javax.net.ssl.trustStore", oldTrust)
        if (oldPass == null) System.clearProperty("javax.net.ssl.trustStorePassword")
        else System.setProperty("javax.net.ssl.trustStorePassword", oldPass)
        trustFile.delete()
    }

    try {
        println("[Http] 基本取数")
        val r = Http.fetch("$base/ok")
        check("状态 200", r.code == 200)
        check("正文正确", r.body.contains("你好 Hello"), r.body)

        println("[Http] 编码探测")
        check("头里 GBK", Http.fetch("$base/gbk").body.contains("中文编码测试"))
        check("meta 里 GBK", Http.fetch("$base/gbk-meta").body.contains("元标签编码"))
        check("UTF-8 无误判", Http.fetch("$base/ok").body.contains("你好"))

        println("[Http] 重定向（关键：跨协议不自动跟，必须自己跟）")
        check("跟随 301", Http.fetch("$base/r301").body.contains("你好 Hello"))
        check("跟随 302", Http.fetch("$base/r302").body.contains("你好 Hello"))
        check("跟随相对 /ok", Http.fetch("$base/rel").body.contains("你好 Hello"))
        check("跟随相对 ok", Http.fetch("$base/relpath").body.contains("你好 Hello"))
        check("最终 URL 已更新", Http.fetch("$base/r301").finalUrl == "$base/ok",
            Http.fetch("$base/r301").finalUrl)
        check("重定向环不无限循环",
            runCatching { Http.fetch("$base/loop") }.isFailure)

        println("[Http] 错误与 POST")
        check("404 抛异常", runCatching { Http.fetch("$base/404") }.isFailure)
        check("POST 带 body", Http.fetch("$base/post", "POST", "a=1&b=2").body == "收到:a=1&b=2")
        check("POST 空 body", Http.fetch("$base/post", "POST", null).body == "收到:")
        check("空 method 归为 GET",
            runCatching { Http.fetch("$base/ok", "  ") }.getOrNull()?.code == 200)

        println("[Http] 总时限（单次读超时挡不住慢速吐字）")
        val t0 = System.currentTimeMillis()
        val err = runCatching { Http.fetch("$base/trickle", timeoutMs = 300) }.exceptionOrNull()
        val ms = System.currentTimeMillis() - t0
        check("慢速吐字会在总时限内失败", err != null && ms < 3000, "耗时 ${ms}ms，${err?.message}")

        println("[Http] 卡死连接（连上不回包，旧实现的 disconnect 解不开）")
        // 真实形态是 DNS/握手后不回包：栈停在 responseCode，看护线程跑过 disconnect 也没用。
        val silent = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val t1 = System.currentTimeMillis()
        val hangErr = runCatching {
            Http.fetch("http://127.0.0.1:${silent.localPort}/hang", timeoutMs = 800)
        }.exceptionOrNull()
        val hangMs = System.currentTimeMillis() - t1
        silent.close()
        check("卡死请求在预算内放弃", hangErr != null && hangMs < 6000,
            "耗时 ${hangMs}ms，${hangErr?.message}")

        println("[Http] charset 探测函数")
        check("从头探测", Http.sniffCharset(ByteArray(0), "text/html; charset=GBK") == "GBK")
        check("从 meta 探测",
            Http.sniffCharset("<meta charset=\"gbk\">xx".toByteArray(Charsets.ISO_8859_1), null) == "gbk")
        check("无编码信息返回 null", Http.sniffCharset(ByteArray(0), "text/html") == null)
    } finally {
        server.stop(0)
    }

    if (failures == 0) println("\n全部通过") else { println("\n失败 $failures 项"); kotlin.system.exitProcess(1) }
}

/**
 * 自签证书的 TLS 上下文，只给自检用。私钥与证书就在 `test/ssl/`，不是秘密。
 *
 * 探测代码（[Http.probeHttps]）**没有任何信任例外**：它和线上一样走系统信任链，
 * 而这条链在 JVM 里就是 <java.home>/lib/security/cacerts。所以要在运行测试的
 * JVM 里临时把一个 trustStore 指向“出厂 CA + 这张自签证书”的副本。
 * 不改探测代码来让测试变绿，也不引入 BouncyCastle —— 这是 JDK 自带的机制。
 */
private fun makeTempTrustStore(): File {
    val pass = "changeit".toCharArray()
    val ks = java.security.KeyStore.getInstance("PKCS12")
    ks.load(File("test/ssl/probe.p12").inputStream(), pass)
    val cf = java.security.cert.CertificateFactory.getInstance("X.509")
    val cert = File("test/ssl/probe.crt").inputStream().use { cf.generateCertificate(it) }

    // 出厂 CA 从这里抄过来：探测的失败用例（TLS 端口不回包）不依赖它，
    // 但不抄的话任何真实 https 都会验证失败，以后加联网用例会集体假红。
    val out = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
    out.load(null, null)
    val bundled = File(System.getProperty("java.home"), "lib/security/cacerts")
    if (bundled.canRead()) {
        val src = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        bundled.inputStream().use { src.load(it, "changeit".toCharArray()) }
        for (a in src.aliases()) {
            runCatching { out.setCertificateEntry(a, src.getCertificate(a)) }
        }
    }
    out.setCertificateEntry("probe", cert)
    val f = File(System.getProperty("java.io.tmpdir"), "wr-probe-ts-${ProcessHandle.current().pid()}.jks")
    f.deleteOnExit()
    f.outputStream().use { out.store(it, pass) }
    return f
}

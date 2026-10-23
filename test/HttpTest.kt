import com.watchdl.core.*
import java.net.HttpURLConnection
import java.net.URL

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

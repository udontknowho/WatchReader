import com.watchdl.core.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors

private fun budgetRule() = BookSourceRule(
    name = "budget", host = "https://budget.example",
    search = SearchRule("/search", name = FieldRule("$.name"), link = FieldRule("$.url")),
    toc = TocRule("", name = FieldRule("$.names[*]"), link = FieldRule("$.links[*]"),
        nextTocUrl = FieldRule("$.next[*]")),
    content = ContentRule("", content = FieldRule()),
)

fun main() {
    val rule = budgetRule()
    val first = """{"names":["第一章"],"links":["/1"],"next":["/page2"]}"""
    val budgets = mutableListOf<Int>()
    val urls = mutableListOf<String>()
    val engine = SourceEngine(rule.copy(toc = rule.toc.copy(tocUrlRule = FieldRule("$.toc"))),
        fetchToc = { url, _, _, _, left ->
            budgets += left
            urls += url
            when {
                url.endsWith("/book") -> {
                    Thread.sleep(50)
                    """{"toc":"/toc"}"""
                }
                url.endsWith("/toc") -> {
                    Thread.sleep(50)
                    first
                }
                else -> throw java.io.IOException("page timeout")
            }
        }) { _, _, _, _ -> error("unbudgeted TOC request") }
    val info = engine.toc("https://budget.example/book", 800)
    check(urls.map { it.substringAfterLast('/') } == listOf("book", "toc", "page2"))
    check(budgets[0] in 1..800)
    check(budgets[1] <= budgets[0] - 40 && budgets[2] <= budgets[1] - 40)
    check(info.chapters.map { it.title } == listOf("第一章"))

    var calls = 0
    val exhausted = SourceEngine(rule, fetchToc = { _, _, _, _, _ ->
        calls++
        Thread.sleep(80)
        first
    }) { _, _, _, _ -> error("unbudgeted TOC request") }
    check(exhausted.toc("https://budget.example/book", 40).chapters.size == 1)
    check(calls == 1)
    check(runCatching { exhausted.toc("https://budget.example/book", 0) }.isFailure)
    check(calls == 1)

    val legacyFetch: (String, String, String?, Map<String, String>) -> String = { _, _, _, _ -> first }
    check(SourceEngine(rule, legacyFetch).toc("/book").chapters.size == 1)
    check(SourceEngine(rule) { _, _, _, _ -> first }.toc("/book").chapters.size == 1)

    val workers = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = workers
    server.createContext("/slow") { ex ->
        try { Thread.sleep(2_000) } finally { ex.close() }
    }
    server.createContext("/fast") { ex ->
        try {
            val bytes = first.replace("/page2", "/slow").toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.write(bytes)
        } finally { ex.close() }
    }
    server.start()
    try {
        val base = "http://127.0.0.1:${server.address.port}"
        val began = System.nanoTime()
        check(runCatching {
            Http.fetch("$base/slow", timeoutMs = 5_000, totalTimeoutMs = 150)
        }.isFailure)
        val elapsed = (System.nanoTime() - began) / 1_000_000
        check(elapsed in 100..1_200) { "hard timeout took ${elapsed}ms" }
        check(runCatching { Http.fetch("$base/fast", totalTimeoutMs = 0) }.isFailure)
        val httpEngine = SourceEngine(rule.copy(toc = rule.toc.copy(nextTocUrl = null)),
            fetchToc = { url, method, body, headers, left ->
                Http.fetch(url, method, body, headers, totalTimeoutMs = left).body
            }) { _, _, _, _ -> error("unbudgeted TOC request") }
        val initialBegan = System.nanoTime()
        check(runCatching { httpEngine.toc("$base/slow", 150) }.isFailure)
        check((System.nanoTime() - initialBegan) / 1_000_000 < 1_200)
        val pagedHttp = SourceEngine(rule,
            fetchToc = { url, method, body, headers, left ->
                Http.fetch(url, method, body, headers, totalTimeoutMs = left).body
            }) { _, _, _, _ -> error("unbudgeted TOC request") }
        val pagingBegan = System.nanoTime()
        check(pagedHttp.toc("$base/fast", 150).chapters.size == 1)
        check((System.nanoTime() - pagingBegan) / 1_000_000 < 1_200)
        check(LegadoSource.of(rule.copy(host = base, toc = rule.toc.copy(nextTocUrl = null)))
            .toc("$base/fast").chapters.size == 1)
        println("TocBudgetTest: PASS (shared initial/paging budget, partial TOC, legacy calls, HTTP cap ${elapsed}ms)")
    } finally {
        server.stop(0)
        workers.shutdownNow()
    }
}

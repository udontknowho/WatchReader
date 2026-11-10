import com.watchdl.core.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors

private fun tocPaginationOfflineChecks() {
    val base = budgetRule()
    fun page(n: Int, next: String = "[]") =
        """{"names":["第${n}章"],"links":["chapter$n"],"next":$next}"""
    val seen = mutableListOf<String>()
    val nestedPages = mapOf(
        "https://budget.example/books/start" to page(1, "[\"part/toc2\"]"),
        "https://budget.example/books/part/toc2" to page(2, "[\"../end/toc3\"]"),
        "https://budget.example/books/end/toc3" to page(3),
    )
    val nested = SourceEngine(base) { url, _, _, _ -> seen += url; nestedPages.getValue(url) }
        .toc("https://budget.example/books/start")
    check(!nested.truncated && nested.chapters.size == 3)
    check(nested.chapters.map { it.itemId } == listOf(
        "https://budget.example/books/chapter1", "https://budget.example/books/part/chapter2",
        "https://budget.example/books/end/chapter3"))
    check(seen == nestedPages.keys.toList())

    val objectRule = base.copy(jsLib = "function unpack(s){return JSON.parse(s).items;} function value(s,k){return JSON.parse(s)[k];}",
        toc = base.toc.copy(sourceList = FieldRule("@js:JSON.stringify(unpack(result))"),
            name = FieldRule("@js:value(result,'n')"), link = FieldRule("@js:value(result,'u')"),
            isVolume = FieldRule("$.volume")))
    val objectInfo = SourceEngine(objectRule) { url, _, _, _ ->
        if (url.endsWith("start")) """{"items":[{"n":"卷一","u":"","volume":true},{"n":"第一章","u":"one"}],"next":["part/second"]}"""
        else """{"items":[{"n":"第二章","u":"two"}],"next":[]}"""
    }.toc("https://budget.example/books/start")
    check(objectInfo.chapters.size == 3 && !objectInfo.truncated)
    check(objectInfo.chapters.any { it.itemId == "https://budget.example/books/part/two" })
    check(objectInfo.chapters.any { it.title == "卷一" && it.itemId.contains("#volume-") })

    val many = (1..31).joinToString(",", "[", "]") { "\"/p$it\"" }
    var calls = 0
    val cap = SourceEngine(base) { _, _, _, _ -> calls++; if (calls == 1) page(0, many) else page(calls) }.toc("/start")
    check(calls == 31 && cap.truncated) { "多值页数上限漏报：$calls ${cap.truncated}" }
    calls = 0
    val chain = SourceEngine(base) { _, _, _, _ -> calls++; page(calls, "[\"/p${calls + 1}\"]") }.toc("/start")
    check(calls == 31 && chain.truncated) { "单链页数上限漏报" }
    calls = 0
    val exact = SourceEngine(base) { _, _, _, _ ->
        calls++; page(calls, if (calls == 31) "[]" else "[\"/p${calls + 1}\"]")
    }.toc("/start")
    check(calls == 31 && !exact.truncated) { "恰好到末页不应误报截断" }
    val emptyPage = SourceEngine(base) { url, _, _, _ -> if (url.endsWith("start")) page(1, "[\"/bad\"]") else "<html>error</html>" }.toc("/start")
    check(emptyPage.truncated && emptyPage.chapters.size == 1)
    val jsFailure = SourceEngine(objectRule) { url, _, _, _ ->
        if (url.endsWith("start")) """{"items":[{"n":"第一章","u":"one"}],"next":["/bad"]}""" else "invalid JSON"
    }.toc("/start")
    check(jsFailure.truncated && jsFailure.chapters.size == 1)
    val querySeen = mutableListOf<String>()
    val queries = SourceEngine(base) { url, _, _, _ ->
        querySeen += url
        if (querySeen.size == 1) page(1, "[\"?p=2\",\"?p=2\"]") else page(2)
    }.toc("https://budget.example/books/toc?p=1")
    check(!queries.truncated && querySeen == listOf("https://budget.example/books/toc?p=1", "https://budget.example/books/toc?p=2"))
    val contentSeen = mutableListOf<String>()
    val contentPages = mapOf(
        "https://budget.example/books/first" to """{"body":"第一页","next":"part/second"}""",
        "https://budget.example/books/part/second" to """{"body":"第二页","next":"third"}""",
        "https://budget.example/books/part/third" to """{"body":"第三页","next":""}""",
    )
    val contentRule = base.copy(jsLib = "function body(s){return JSON.parse(s).body;}",
        content = ContentRule("", content = FieldRule("@js:body(result)"), nextContentUrl = FieldRule("$.next")))
    val content = SourceEngine(contentRule) { url, _, _, _ -> contentSeen += url; contentPages.getValue(url) }
        .content("https://budget.example/books/first")
    check(contentSeen == contentPages.keys.toList())
    check(listOf("第一页", "第二页", "第三页").all { content.contains(it) })
    println("TOC offline: object scopes/jsLib, page bases, cap reporting, parse failure, query links")
}

private fun acceptanceOfflineChecks() {
    val chapters = listOf("公告", "第一章", "请假条", "第二章 公告", "第三章", "完本感言")
        .mapIndexed { i, title -> ChapterRef("https://fixture/$i", title) }
    check(acceptancePositions(chapters) == listOf(1, 3, 4))
    check(chapters.size == 6) { "抽样不能修改目录" }
    check(!acceptanceAnnouncement("第十二章 公告"))
    check(!acceptanceAnnouncement("公告中的秘密"))
    check(acceptancePositions(listOf(ChapterRef("/1", "公告"))).isEmpty())
    check(acceptanceTimeout(java.io.IOException("wrapped", java.net.SocketTimeoutException("read"))))
    check(!acceptanceTimeout(java.io.IOException("404")))
}

private fun budgetRule() = BookSourceRule(
    name = "budget", host = "https://budget.example",
    search = SearchRule("/search", name = FieldRule("$.name"), link = FieldRule("$.url")),
    toc = TocRule("", name = FieldRule("$.names[*]"), link = FieldRule("$.links[*]"),
        nextTocUrl = FieldRule("$.next[*]")),
    content = ContentRule("", content = FieldRule()),
)

fun main() {
    tocPaginationOfflineChecks()
    acceptanceOfflineChecks()
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
    check(info.truncated) { "请求失败必须报告目录截断" }

    var calls = 0
    val exhausted = SourceEngine(rule, fetchToc = { _, _, _, _, _ ->
        calls++
        Thread.sleep(80)
        first
    }) { _, _, _, _ -> error("unbudgeted TOC request") }
    val exhaustedInfo = exhausted.toc("https://budget.example/book", 40)
    check(exhaustedInfo.chapters.size == 1 && exhaustedInfo.truncated)
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

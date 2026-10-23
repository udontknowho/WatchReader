package com.watchdl.core

/** Boundary regressions; no network requests. */
fun main() {
    fun links(html: String, query: String) =
        HtmlSelect.select(html, query, Selector.Pick.ATTR, "href")

    check(links("<body><a href='/1'>first</a>", "body a") == listOf("/1"))
    check(links("<html><body><a href='/1'>first</a></html>", "body a") == listOf("/1"))
    val broken = "<body><div class='wrap'><div><a href='/in'>in</a></div>" +
        "<a href='/outside'>outside</a></body>"
    check(links(broken, ".wrap a") == listOf("/in"))
    val ancestor = "<section><div class='wrap'><a href='/in'>in</a></section>" +
        "<a href='/outside'>outside</a>"
    check(links(ancestor, ".wrap a") == listOf("/in"))
    check(links("<div class='wrap'><a href='/in'>in</a></div><a href='/out'>out</a>",
        ".wrap a") == listOf("/in"))
    check(links("<body><a href='/1'>first</a><a href='/2'>second</a>", "body a[0]") == listOf("/1"))

    check(LegadoImport.parseHeader("@js: JSON.stringify({'X-Test':'static'})") == mapOf("X-Test" to "static"))
    check(LegadoImport.parseHeader("@js: ({'X-Test':java.md5Encode('abc')})") ==
        mapOf("X-Test" to "900150983cd24fb0d6963f7d28e17f72"))
    val source = """[{"bookSourceName":"boundary","bookSourceUrl":"https://example.com",
        "searchUrl":"/search?q={{key}}","header":"HEADER",
        "ruleSearch":{"bookList":".r","name":"a@text","bookUrl":"a@href"},
        "ruleToc":{"chapterList":".c","chapterName":"a@text","chapterUrl":"a@href"},
        "ruleContent":{"content":".content"}}]"""
    check(LegadoImport.importAll(source.replace("HEADER", "@js: ({'X-Test':'static'})")).single().rule != null)
    for (api in listOf("ajax", "get", "post")) {
        val js = "@js: var value = java.$api('http://127.0.0.1:1', 'body'); " +
            "JSON.stringify({'X-Test':value})"
        check(LegadoImport.parseHeader(js) == null) { "$api must reject a network-dependent header" }
        val imported = LegadoImport.importAll(source.replace("HEADER", js)).single()
        check(imported.rule == null && imported.issue != null) { "$api source must be marked unsupported" }
    }
    check(LegadoImport.parseHeader(null) == emptyMap<String, String>())

    // 点号位置简写：`span.2:0` 是区间，不是类名 `0`
    check(LegadoSelector.toCss("span.2:0@text") == "span[2:0]@text")
    check(LegadoSelector.toCss("td.-1:-2@text") == "td[-1:-2]@text")
    check(LegadoSelector.toCss("span.0@text") == "span[0]@text")
    check(LegadoSelector.toCss("class.lst@text") == ".lst@text")

    // `##正则##` 与 `@js:` 之后是自由文本，不能当选择器解析
    check(LegadoSelector.toCss("##.*第[0-9]/[0-9]页.*") == "##.*第[0-9]/[0-9]页.*")
    check(LegadoSelector.toCss(".a@text##小说|[0-9]") == ".a@text##小说|[0-9]")
    check(LegadoSelector.toCss(".a@href@js:result+[0-9]") == ".a@href@js:result+[0-9]")

    // 渲染必须幂等：909 条真实书源规则实测的回归防线
    for (r in listOf("span.2:0@text", "tag.a@href", "-class.lst@tag.li@text",
                     ".field@span.1:2:5@text", "a.0@text&&td.6:4@text##\\[|\\]")) {
        val once = LegadoSelector.toCss(r)
        check(LegadoSelector.toCss(once) == once) { "idempotency broke: $r -> $once" }
    }
    println("RuleBoundaryTest passed")
}

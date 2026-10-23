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
    println("RuleBoundaryTest passed")
}

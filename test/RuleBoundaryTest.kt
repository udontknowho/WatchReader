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

    // 属性选择器算符（旧实现只认裸 `=`，`$=` `~=` 整只被吞，直接选错元素）
    check(LegadoSelector.toCss("[property$=book_name]@content") == "[property$=book_name]@content")
    check(LegadoSelector.toCss("[property~=a|b]@content") == "[property~=\"a|b\"]@content")
    check(HtmlSelect.select("<div class=\"a b\"></div>", "[class~=b]", Selector.Pick.TEXT, null).size == 1)
    check(HtmlSelect.select("<div data-x=\"foo-bar\"></div>", "[data-x^=foo]", Selector.Pick.TEXT, null).size == 1)
    check(HtmlSelect.select("<div data-x=\"foo-bar\"></div>", "[data-x$=bar]", Selector.Pick.TEXT, null).size == 1)

    // XPath 的 `@` 是谓词语法，不能当取值后缀或被切层
    check(LegadoSelector.toCss("//meta[@name=\"description\"]/@content") == "//meta[@name=\"description\"]/@content")

    // 伪类不是类名（`div.c_tag:nth-of-type(2)` 以前渲染成 `div:nth-of-type(2).c_tag`）
    check(LegadoSelector.toCss("div.c_tag:nth-of-type(2)@text") == "div.c_tag:nth-of-type(2)@text")

    // 逗号多选一交给 Jsoup，不能拆成「类 s1 且类 s5」
    check(LegadoSelector.toCss(".s1,.s5@text") == ".s1,.s5@text")
    // 逗号是「或」：`.s1,.s5` 要能取到 class=s5 的元素。
    // 以前解析层把整段当 raw 保留、执行层不认，被当成「无条件匹配」。
    check(HtmlSelect.select("<i class=\"s5\">a</i>", ".s1,.s5", Selector.Pick.TEXT, null) == listOf("a"))
    check(HtmlSelect.select("<i class=\"s1\">b</i>", ".s1,.s5", Selector.Pick.TEXT, null) == listOf("b"))
    // 两个都不匹配时不能「全命中」——那会把全页每个元素都当条目
    check(HtmlSelect.select("<i class=\"zzz\">c</i>", ".s1,.s5", Selector.Pick.TEXT, null).isEmpty())
    // `:text(a,b)` 里的逗号不是备选分隔符
    check(LegadoSelector.toCss("div:text(a,b)@text") == "div:text(a,b)@text")

    // 内建常量大小写敏感（`imageStyle: "TEXT"`）
    check(LegadoSelector.toCss("TEXT") == "TEXT")

    // `{{...}}` 是取值模板，不是选择器
    check(LegadoSelector.toCss("{{$.id}}") == "{{$.id}}")
    check(LegadoSelector.toCss("https://x/y?bid={{$.id}}") == "https://x/y?bid={{$.id}}")

    // `@@` 转义：宣告「后面是选择器，不是 JS」。源库里 149 处这么写。
    val card = "<div class='search-card-category'><a href='/k'>玄幻</a></div>"
    check(FieldRule.parse("{{@@class.search-card-category@tag.a@text}}").eval(card) == listOf("玄幻"))
    check(FieldRule.parse("{{@css:div a@text}}").eval(card) == listOf("玄幻"))
    // 内层是 JS 表达式时按 JS 求值（以前整串落进 CSS 解析，恒空）
    check(FieldRule.parse("{{'玄幻'}}").eval(card) == listOf("玄幻"))
    // 模板里的 `##` 属于内层规则，不是外层字段的替换
    check(FieldRule.parse("{{$.a##x##y}}").selector == "{{$.a##x##y}}")
    check(FieldRule.parse("{{$.a}}##x##y").replace == "##x##y")

    // URL 选项的 charset：58 条源写 gbk/gb2312，搜索词必须按那个编码出字节。
    val (optUrl, opt) = LegadoImport.parseUrlOptions(
        "/search,{'method':'POST','body':'kw={{key}}','charset':'gbk'}")
    check(optUrl == "/search")
    check(opt.method == "POST")
    check(opt.charset == "gbk")
    check(SourceEngine.urlEncode("中", "gbk") == "%D6%D0")
    check(SourceEngine.urlEncode("中", "GB2312") == "%D6%D0")
    check(SourceEngine.urlEncode("中", "") == "%E4%B8%AD")
    check(SourceEngine.urlEncode("中") == "%E4%B8%AD")

    // 渲染必须幂等：909 条真实书源规则实测的回归防线
    for (r in listOf("span.2:0@text", "tag.a@href", "-class.lst@tag.li@text",
                     ".field@span.1:2:5@text", "a.0@text&&td.6:4@text##\\[|\\]")) {
        val once = LegadoSelector.toCss(r)
        check(LegadoSelector.toCss(once) == once) { "idempotency broke: $r -> $once" }
    }
    println("RuleBoundaryTest passed")
}

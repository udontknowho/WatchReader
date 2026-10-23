import com.watchdl.core.*

/** 引擎自检：选择器、JSONPath、规则求值、书源执行。纯逻辑，不碰网络。 */
fun main() {
    var failures = 0
    fun check(n: String, ok: Boolean, d: String = "") {
        if (ok) println("  ✓ $n") else { failures++; println("  ✗ $n  $d") }
    }

    val html = """
<html><body>
<div class="result-list">
  <div class="result-item"><a href="/book/1" class="title">斗破苍穹</a><span class="author">天蚕土豆</span></div>
  <div class="result-item"><a href="/book/2" class="title">武动乾坤</a><span class="author">天蚕土豆</span></div>
</div>
<div id="toc">
  <dl><dd><a href="/read/11">第一章 觉醒</a></dd><dd><a href="/read/12">第二章 突破</a></dd></dl>
</div>
<div class="content"><p>正文第一段</p><p>正文第二段</p></div>
</body></html>
    """.trimIndent()

    println("[HtmlSelect] 节点扫描")
    val nodes = HtmlSelect.nodes(html)
    check("扫出多个节点", nodes.size >= 8, "实际 ${nodes.size}")
    check("含 div", nodes.any { it.tag == "div" })
    check("含 a", nodes.any { it.tag == "a" })
    check("class 属性被解析", nodes.any { it.attrs["class"] == "result-item" })

    println("[HtmlSelect] 选择器")
    check("标签", HtmlSelect.select(html, "a", Selector.Pick.TEXT, null).size >= 4)
    check("类", HtmlSelect.select(html, ".author", Selector.Pick.TEXT, null) == listOf("天蚕土豆", "天蚕土豆"))
    check("id", HtmlSelect.select(html, "#toc", Selector.Pick.TEXT, null).first().contains("第一章"))
    check("后代组合", HtmlSelect.select(html, ".result-item a.title", Selector.Pick.TEXT, null) ==
        listOf("斗破苍穹", "武动乾坤"))
    check("取属性", HtmlSelect.select(html, ".title", Selector.Pick.ATTR, "href") == listOf("/book/1", "/book/2"))
    check("序号", HtmlSelect.select(html, ".title[1]", Selector.Pick.TEXT, null) == listOf("武动乾坤"))
    check("属性选择器", HtmlSelect.select(html, "[class=title]", Selector.Pick.TEXT, null).size == 2)
    check("未命中返回空", HtmlSelect.select(html, ".nope", Selector.Pick.TEXT, null).isEmpty())

    // ── 回归：未闭合标签不得吞掉整页（2026-10-22）──
    // 页面少写一个 </div> 时，那个 <div> 的区间就成了全文，
    // `.wr a` 这种选择器会把整页所有链接都算成它的子孙：目录爆出几百条，
    // 正文拼进导航与广告。以前 nodes() 用 html.length 当 end 且没有标记。
    println("[回归] 未闭合标签")
    run {
        val broken = """
            <html><body>
            <div class="wrap"><div class="box"><a href="/in1">里一</a>
            <a href="/in2">里二</a>
            </div>
            <a href="/outside">外面</a>
            </body></html>
        """.trimIndent()
        val got = HtmlSelect.select(broken, ".wrap a", Selector.Pick.ATTR, "href")
        // wrap 未闭合，它的区间是文末；不去展开后代，就一个都不该取到？
        // 不能取到外侧那个才是关键 —— 取不到任何也优于把整页当正文。
        check("未闭合的 .wrap 不会把外面那个 a 也吃进来",
            "/outside" !in got, "实际 $got")

        val ns = HtmlSelect.nodes(broken)
        check("未闭合节点被标记出来", ns.any { it.tag == "div" && it.unclosed })
        check("正常闭合的节点不被误标",
            HtmlSelect.nodes("<div><p>x</p></div>").none { it.unclosed })
    }

    println("[XPathLite]")
    check("后代 //a", XPathLite.select(html, "//a", Selector.Pick.TEXT, null).size >= 4)
    check("路径 //dl/dd/a", XPathLite.select(html, "//dl/dd/a", Selector.Pick.TEXT, null) ==
        listOf("第一章 觉醒", "第二章 突破"))
    check("属性谓词", XPathLite.select(html, "//div[@id='toc']//a", Selector.Pick.TEXT, null).size == 2)
    check("取属性", XPathLite.select(html, "//dd/a/@href", Selector.Pick.TEXT, null) == listOf("/read/11", "/read/12"))
    check("复杂谓词安全返回空", XPathLite.select(html, "//a[contains(text(),'x')]", Selector.Pick.TEXT, null).isEmpty())

    println("[Selector] 解析")
    check("css 前缀", Selector.parse("@css:.a") is Selector.Css)
    check("xpath 前缀", Selector.parse("@xpath://a") is Selector.XPath)
    check("json 前缀", Selector.parse("@json:$.a") is Selector.Json)
    check("$ 开头当 JSONPath", Selector.parse("$.a") is Selector.Json)
    check("// 开头当 XPath", Selector.parse("//div/p") is Selector.XPath)
    check("其余当 CSS", Selector.parse(".cls") is Selector.Css)
    check("取值后缀 text", (Selector.parse("@css:.a@text") as Selector.Css).pick == Selector.Pick.TEXT)
    check("取值后缀 href", (Selector.parse("@css:.a@href") as Selector.Css).attr == "href")
    check("attr= 形式", (Selector.parse("@css:.a@attr=src") as Selector.Css).attr == "src")
    // XPath 里的 /@href 不能被当成后缀吃掉
    check("XPath 保留 /@href", (Selector.parse("@xpath://a/@href") as Selector.XPath).expr == "//a/@href")

    println("[JsonPathLite]")
    val json = """{"code":0,"data":{"total":2,"list":[{"id":"a1","n":{"k":"v1"}},{"id":"a2","n":{"k":"v2"}}]}}"""
    check("取数字", JsonPathLite.select(json, "$.data.total") == listOf("2"))
    check("取字符串", JsonPathLite.select(json, "$.data.list[0].id") == listOf("a1"))
    check("通配", JsonPathLite.select(json, "$.data.list[*].id") == listOf("a1", "a2"))
    check("负索引", JsonPathLite.select(json, "$.data.list[-1].id") == listOf("a2"))
    check("深层通配", JsonPathLite.select(json, "$.data.list[*].n.k") == listOf("v1", "v2"))
    check("无 $ 前缀也行", JsonPathLite.select(json, "data.total") == listOf("2"))
    check("不存在返回空", JsonPathLite.select(json, "$.nope").isEmpty())
    check("越界返回空", JsonPathLite.select(json, "$.data.list[9].id").isEmpty())

    println("[FieldRule]")
    check("列表求值", FieldRule.parse("@css:.title").eval(html, list = true) == listOf("斗破苍穹", "武动乾坤"))
    check("单值只取第一个", FieldRule.parse("@css:.title").eval(html) == listOf("斗破苍穹"))
    check("正则替换", FieldRule.parse("@css:.title##苍穹##").one(html) == "斗破")
    check("坏正则不抛", FieldRule.parse("@css:.title##[##").one(html) == "斗破苍穹")
    check("空选择器返回整段", FieldRule("").one("raw") == "raw")

    println("[SourceEngine] 端到端（假网络）")
    val rule = BookSourceRule(
        name = "测试源",
        host = "https://site.example",
        search = SearchRule(
            url = "https://site.example/search?q={{key}}&p={{page}}",
            name = FieldRule.parse("@css:.result-item a@text"),
            link = FieldRule.parse("@css:.result-item a@href"),
            author = FieldRule.parse("@css:.author"),
        ),
        toc = TocRule(
            url = "/toc/{{id}}",
            name = FieldRule.parse("@css:#toc dd a@text"),
            link = FieldRule.parse("@css:#toc dd a@href"),
        ),
        content = ContentRule(
            url = "/read/{{id}}",
            content = FieldRule.parse("@css:.content"),
        ),
    )
    var lastUrl = ""
    val engine = SourceEngine(rule) { url, _, _, _ ->
        lastUrl = url
        when {
            url.contains("/search") -> html
            url.contains("/toc/") -> html
            else -> html
        }
    }
    val hits = engine.search("斗破")
    check("搜到 2 本", hits.size == 2, "实际 ${hits.size}")
    check("书名正确", hits[0].name == "斗破苍穹", hits[0].name)
    check("链接补全为绝对", hits[0].bookId == "https://site.example/book/1", hits[0].bookId)
    check("作者取到", hits[0].author == "天蚕土豆")
    check("URL 模板展开（关键词已编码）",
        lastUrl == "https://site.example/search?q=%E6%96%97%E7%A0%B4&p=1", lastUrl)

    val info = engine.toc("111")
    check("目录 2 章", info.chapters.size == 2, "实际 ${info.chapters.size}")
    check("章节标题", info.chapters[0].title == "第一章 觉醒", info.chapters[0].title)
    check("章节链接绝对", info.chapters[0].itemId == "https://site.example/read/11", info.chapters[0].itemId)

    val text = engine.content("https://site.example/read/11")
    check("正文取到", text.contains("正文第一段"), text.take(60))

    println("[SourceEngine] 相对 host + 分页")
    check("相对 URL 补全", rule.expand("/toc/{{id}}", mapOf("id" to "9")) == "https://site.example/toc/9")
    check("分页算术", rule.expand("?p={{(page-1)*10}}", mapOf("page" to "3")) == "https://site.example?p=20",
        rule.expand("?p={{(page-1)*10}}", mapOf("page" to "3")))
    check("分页算术（不经 expand）", Template.render("?p={{(page-1)*10}}", mapOf("page" to "3")) == "?p=20")

    // 导入自检（JSON5 解析、脚本检测、真实规则文件）
    importMain()
    // URL 编码自检
    encodeCheck()
    // 链接编码自检
    linkCheck()

    if (failures == 0) println("\n全部通过") else { println("\n失败 $failures 项"); kotlin.system.exitProcess(1) }
}

/** so-novel 导入自检。用真实 main.json（有则跑）。 */
fun importMain() {
    var failures = 0
    fun check(n: String, ok: Boolean, d: String = "") {
        if (ok) println("  ✓ $n") else { failures++; println("  ✗ $n  $d") }
    }

    println("[JsonParser] JSON5 风味")
    val json5 = """
    {
      // 行注释
      "a": 1, /* 块注释 */
      "b": [1, 2, 3,],
      unquoted: "ok",
    }
    """.trimIndent()
    val p = JsonParser.parse(json5) as Map<*, *>
    check("解析数字", p["a"] == 1L, "实际 ${p["a"]}")
    check("尾逗号容忍", (p["b"] as List<*>).size == 3)
    check("无引号键", p["unquoted"] == "ok")
    check("注释被剥离", !JsonParser.clean(json5).contains("块注释") &&
        !JsonParser.clean(json5).contains("行注释"))

    println("[SonovelImport] 脚本检测")
    check("检出 @js:", SonovelImport.usesScript("div@js:r=r+1"))
    check("检出 java.", SonovelImport.usesScript("java.ajax(x)"))
    check("普通选择器不算脚本", !SonovelImport.usesScript(".result-item a"))

    println("[SonovelImport] 单条转换")
    val one = """
    [{"url":"http://s.example/","name":"测试站",
      "search":{"url":"http://s.example/s","method":"post","data":"{searchkey: %s}",
                "result":"#form tr","bookName":"td.even > a","author":"td:nth-of-type(3)"},
      "book":{},
      "toc":{"item":"#list dl dd a"},
      "chapter":{"title":"h1","content":"#content","filterTxt":"广告一|广告二"}}]
    """.trimIndent()
    val rules = SonovelImport.importAll(one)
    check("导入 1 条", rules.size == 1, "实际 ${rules.size}")
    if (rules.isNotEmpty()) {
        val r = rules[0].second
        check("POST 方法", r.search.method == "POST", r.search.method)
        check("body 占位转换", r.search.body == "{searchkey: {{key}}}", r.search.body ?: "null")
        check("书名拼成绝对选择器", r.search.name.selector.contains("#form tr") &&
            r.search.name.selector.endsWith("@text"), r.search.name.selector)
        check("链接带 @href", r.search.link.selector.endsWith("@href"), r.search.link.selector)
        check("目录标题", r.toc.name.selector == "#list dl dd a", r.toc.name.selector)
        check("目录链接带 @href", r.toc.link.selector == "#list dl dd a@href", r.toc.link.selector)
        check("正文选择器", r.content.content.selector == "@css:#content", r.content.content.selector)
        check("过滤词取第一条", r.content.content.replace == "##广告一##", r.content.content.replace ?: "null")
        check("目录 URL 留空（用书链接）", r.toc.url.isEmpty(), r.toc.url)
    }

    println("[SonovelImport] 带脚本的源被明确拒绝")
    val withJs = """
    [{"url":"http://j.example/","name":"脚本站",
      "search":{"url":"http://j.example/s","method":"post","data":"{q:%s}",
                "result":"#r tr","bookName":"a@js:r=r.trim()"},
      "book":{},"toc":{"item":"#list a"},
      "chapter":{"content":"#c@js:var x=1"}}]
    """.trimIndent()
    val jr = SonovelImport.importAll(withJs).firstOrNull()?.second
    check("脚本字段清空而非误用", jr?.search?.name?.selector?.isEmpty() == true,
        jr?.search?.name?.selector ?: "null")
    check("脚本正文清空", jr?.content?.content?.selector?.isEmpty() == true)

    val real = System.getenv("SONOVEL_RULES")
    if (!real.isNullOrEmpty()) {
        println("[SonovelImport] 真实规则文件 $real")
        val all = SonovelImport.importAll(java.io.File(real).readText())
        check("导入 12 条", all.size == 12, "实际 ${all.size}")
        var usable = 0
        var scripted = 0
        for ((_, r) in all) {
            if (r.search.name.selector.isNotBlank() && r.search.link.selector.isNotBlank() &&
                r.toc.name.selector.isNotBlank() && r.toc.link.selector.isNotBlank()) usable++
            if (r.content.content.selector.isBlank() && r.content.content.replace == null) scripted++
        }
        println("    可用 $usable/${all.size}，正文需脚本 $scripted")
        check("至少 7 条可用", usable >= 7, "可用 $usable")
    } else {
        println("[SonovelImport] 未设 SONOVEL_RULES，跳过真实文件")
    }

    if (failures > 0) { println("导入自检失败 $failures 项"); kotlin.system.exitProcess(1) }
    println("导入自检通过")
}

/** URL 编码回归：中文关键词必须编码后才能请求。 */
fun encodeCheck() {
    var failures = 0
    fun check(n: String, ok: Boolean, d: String = "") {
        if (ok) println("  ✓ $n") else { failures++; println("  ✗ $n  $d") }
    }
    println("[SourceEngine] URL 编码")
    check("中文被编成 %XX", SourceEngine.urlEncode("斗破") == "%E6%96%97%E7%A0%B4",
        SourceEngine.urlEncode("斗破"))
    check("空格编成 %20 而非 +", SourceEngine.urlEncode("a b") == "a%20b", SourceEngine.urlEncode("a b"))
    check("ASCII 原样", SourceEngine.urlEncode("abc123") == "abc123")
    check("特殊符号被编", SourceEngine.urlEncode("a&b=c") == "a%26b%3Dc", SourceEngine.urlEncode("a&b=c"))

    // 端到端：中文关键词经 search() 后 URL 里不能有非 ASCII
    var seen = ""
    val rule = BookSourceRule(
        name = "t", host = "https://s.example",
        search = SearchRule(
            url = "https://s.example/find?q={{key}}&p={{page}}",
            name = FieldRule.parse("@css:.t@text"),
            link = FieldRule.parse("@css:.t@href"),
        ),
        toc = TocRule(url = "", name = FieldRule.parse(".l a@text"), link = FieldRule.parse(".l a@href")),
        content = ContentRule(url = "", content = FieldRule.parse("@css:#c")),
    )
    SourceEngine(rule) { u, _, _, _ -> seen = u; "<div class=\"t\"><a href=\"/b/1\">书名</a></div>" }
        .search("斗破苍穹")
    check("搜索 URL 无非 ASCII", seen.none { it.code > 127 }, seen)

    if (failures > 0) { println("编码自检失败 $failures 项"); kotlin.system.exitProcess(1) }
    println("编码自检通过")
}

/** 链接编码回归：中文路径必须编码后才能请求。 */
fun linkCheck() {
    var failures = 0
    fun check(n: String, ok: Boolean, d: String = "") {
        if (ok) println("  ✓ $n") else { failures++; println("  ✗ $n  $d") }
    }
    println("[SourceEngine] 链接编码")
    check("中文路径被编码",
        SourceEngine.encodeUrlPath("https://a.com/斗破") == "https://a.com/%E6%96%97%E7%A0%B4",
        SourceEngine.encodeUrlPath("https://a.com/斗破"))
    check("纯 ASCII 原样", SourceEngine.encodeUrlPath("https://a.com/x?y=1") == "https://a.com/x?y=1")
    check("保留已有 %XX", SourceEngine.encodeUrlPath("https://a.com/%E6%96%97") == "https://a.com/%E6%96%97")
    check("结构字符不动",
        SourceEngine.encodeUrlPath("https://a.com/p?q=1&r=2") == "https://a.com/p?q=1&r=2",
        SourceEngine.encodeUrlPath("https://a.com/p?q=1&r=2"))

    // 端到端：搜索结果链接带中文时，目录请求的 URL 必须已编码
    var tocUrl = ""
    val rule = BookSourceRule(
        name = "t", host = "https://www.hongxiu.com",
        search = SearchRule(
            url = "https://www.hongxiu.com/so/{{key}}",
            name = FieldRule.parse("@css:.n a@text"),
            link = FieldRule.parse("@css:.n a@href"),
        ),
        toc = TocRule(url = "", name = FieldRule.parse(".v li a@text"), link = FieldRule.parse(".v li a@href")),
        content = ContentRule(url = "", content = FieldRule.parse("")),
    )
    val eng = SourceEngine(rule) { u, _, _, _ ->
        if (u.contains("/so/")) "<div class=\"n\"><a href=\"https://www.hongxiu.com/斗破之我即正道\">书</a></div>"
        else { tocUrl = u; "<div class=\"v\"><ul><li><a href=\"/c/1\">第一章</a></li></ul></div>" }
    }
    val hits = eng.search("斗破")
    check("搜索链接补全并编码", hits.firstOrNull()?.bookId?.none { it.code > 127 } == true,
        hits.firstOrNull()?.bookId ?: "无结果")
    eng.toc(hits[0].bookId)
    check("目录请求 URL 无非 ASCII", tocUrl.none { it.code > 127 }, tocUrl)

    // ── 回归：列表选择器的 `||` 备选链（2026-10-16 修）──
    //
    // 以前 evalList 把整条链当一个 CSS 选择器（`||` 不是 CSS 语法），命中恒为 0。
    // 实测 52书库 这类源全部「搜索无结果」。
    println("[回归] 列表选择器的 || 备选链")
    val chainRule = BookSourceRule(
        name = "chain", host = "https://x.com",
        search = SearchRule(
            url = "https://x.com/s?q={{key}}",
            list = FieldRule.parse("class.excerpt||class.post||article"),
            name = FieldRule.parse("h2 a@text"),
            link = FieldRule.parse("h2 a@href"),
        ),
        toc = TocRule(url = "", name = FieldRule.parse("a@text"), link = FieldRule.parse("a@href")),
        content = ContentRule(url = "", content = FieldRule.parse("@text")),
    )
    val chainEng = SourceEngine(chainRule) { _, _, _, _ ->
        "<article class=\"excerpt\"><h2><a href=\"/1.html\">书一</a></h2></article>" +
            "<article class=\"excerpt\"><h2><a href=\"/2.html\">书二</a></h2></article>"
    }
    val chainHits = chainEng.search("斗破")
    check("备选链能命中（首条备选）", chainHits.size == 2, "实际 ${chainHits.size}")
    check("备选链里书名带出来", chainHits.firstOrNull()?.name == "书一",
        chainHits.firstOrNull()?.name ?: "空")

    // 首条备选不命中时，要落到后面的备选
    val chainEng2 = SourceEngine(chainRule) { _, _, _, _ ->
        "<article class=\"post\"><h2><a href=\"/3.html\">书三</a></h2></article>"
    }
    val chainHits2 = chainEng2.search("斗破")
    check("首条不中落到后续备选", chainHits2.size == 1 && chainHits2[0].name == "书三",
        "实际 ${chainHits2.size}")

    // ── 回归：字段选择器的 `||` 链不能丢取值后缀（2026-10-16 修）──
    //
    // toCss 靠 lastIndexOf('@') 判后缀，对整条链调用时只有最后一段的 @text
    // 被认出来，前面的被当成 legado 路径分隔符换成空格。
    println("[回归] 字段选择器链保留 @text")
    val normed = LegadoSelector.normaliseChain("h2 a@text||h3 a@text||h4 a@text")
    check("链上每段都保留 @text",
        LegadoSelector.alternatives(normed).all { LegadoSelector.valueSuffix(it) == "text" }, normed)
    val frChain = FieldRule.parse(normed)
    check("链式字段能取到值",
        frChain.eval("<h2><a>甲</a></h2>").firstOrNull() == "甲",
        frChain.eval("<h2><a>甲</a></h2>").toString())

    // ── 回归：脚本字段不能退化成「整段就是值」（2026-10-16 修）──
    //
    // @js: 字段被跳过时若返回空选择器，语义变成「整段就是值」，
    // 会把整块 HTML 当作者名返回。
    println("[回归] 不支持的字段返回空而非整段")
    check("UNSUPPORTED 求值为空",
        FieldRule(FieldRule.UNSUPPORTED).eval("<p>整段</p>").isEmpty())
    check("空选择器仍是整段就是值",
        FieldRule("").eval("abc") == listOf("abc"))

    // ── 回归：三种规则链连接方式（官方 splitRule("&&","||","%%")）──
    println("[回归] 规则链 && / || / %%")
    val orRule = FieldRule.parse("h2 a@text||h3 a@text")
    check("|| 取首个命中", orRule.eval("<h3><a>乙</a></h3>").firstOrNull() == "乙",
        orRule.eval("<h3><a>乙</a></h3>").toString())
    check("|| 前面有就不看后面",
        FieldRule.parse("h2 a@text||h3 a@text").eval("<h2><a>甲</a></h2><h3><a>乙</a></h3>") == listOf("甲"))
    check("chain 识别 ||", LegadoSelector.chain("a||b").join == LegadoSelector.Join.Or)
    check("chain 识别 &&", LegadoSelector.chain("a&&b").join == LegadoSelector.Join.And)
    check("chain 识别 %%", LegadoSelector.chain("a%%b").join == LegadoSelector.Join.Merge)
    check("&& 优先级高于 ||",
        LegadoSelector.chain("a&&b||c").join == LegadoSelector.Join.And,
        LegadoSelector.chain("a&&b||c").toString())
    check("链上的 @ 不被吃掉",
        LegadoSelector.chain("h2 a@text||h3 a@href").segments.all { it.contains("@") },
        LegadoSelector.chain("h2 a@text||h3 a@href").segments.toString())

    // ── 回归：取值后缀 textNodes / ownText / all ──
    println("[回归] 取值后缀 textNodes / ownText / all")
    val sample = "<div>直接<span>子</span>尾巴</div>"
    check("ownText 不含子元素文本",
        FieldRule.parse("div@ownText").eval(sample).firstOrNull()?.let { !it.contains("子") } == true,
        FieldRule.parse("div@ownText").eval(sample).toString())
    check("all 保留原文",
        FieldRule.parse("div@all").eval(sample).firstOrNull()?.contains("<span>") == true)
    check("html 剔 script",
        FieldRule.parse("div@html").eval("<div><script>x</script>正文</div>")
            .firstOrNull()?.contains("script") == false)

    // ── 回归：序号 [N] [-N] [!N] [a:b] ──
    println("[回归] 序号索引")
    val list3 = "<ul><li>一</li><li>二</li><li>三</li></ul>"
    check("[0] 取第一个", FieldRule.parse("li[0]@text").eval(list3) == listOf("一"),
        FieldRule.parse("li[0]@text").eval(list3).toString())
    check("[-1] 取倒数第一", FieldRule.parse("li[-1]@text").eval(list3) == listOf("三"),
        FieldRule.parse("li[-1]@text").eval(list3).toString())

    // ── JS 引擎（@js: / <js>）──
    println("[回归] JS 引擎与沙箱")
    check("基础脚本求值",
        FieldRule.parse("@js: String(result).replace(/a/g,'X')").eval("aaa").firstOrNull() == "XXX",
        FieldRule.parse("@js: String(result).replace(/a/g,'X')").eval("aaa").toString())
    check("<js> 包裹写法",
        FieldRule.jsCodeOf("<js>return 1+1</js>") == "return 1+1")
    check("@js: 前缀取出正文",
        FieldRule.jsCodeOf("@js:abc") == "abc")
    check("非 JS 规则返回 null", FieldRule.jsCodeOf(".item a@text") == null)
    check("JSON.parse 可用",
        FieldRule.parse("@js: JSON.parse(result).n").eval("""{"n":"书名"}""").firstOrNull() == "书名")
    check("中文处理正确",
        FieldRule.parse("@js: String(result).length + ''").eval("斗破苍穹").firstOrNull() == "4",
        FieldRule.parse("@js: String(result).length + ''").eval("斗破苍穹").toString())

    // 沙箱：这些必须被拦（手表只有 1 GB 内存，不放任第三方脚本碰 Java）
    check("Packages 被拦",
        FieldRule.parse("@js: String(Packages.java.lang.System.getProperty('user.dir'))").eval("").isEmpty())
    check("java.lang.Runtime 被拦",
        runCatching { JsEngine.eval("java.lang.Runtime.getRuntime()") }.isFailure)
    check("Java.type 逃逸被拦",
        runCatching { JsEngine.eval("Java.type('java.lang.Runtime')") }.isFailure)

    // 白名单可以走
    check("java.base64Encode 白名单",
        FieldRule.parse("@js: java.base64Encode('abc')").eval("").firstOrNull() == "YWJj")
    check("java.md5Encode 白名单",
        FieldRule.parse("@js: java.md5Encode('abc')").eval("").firstOrNull()
            == "900150983cd24fb0d6963f7d28e17f72")
    check("java.encodeURI 白名单",
        FieldRule.parse("@js: java.encodeURI('斗破')").eval("").firstOrNull()
            ?.startsWith("%") == true)

    // 超时必须掐断死循环（否则脚本能卡死手表）
    val t0 = System.currentTimeMillis()
    val looped = runCatching { JsEngine.eval("while(true){}") }.isFailure
    val took = System.currentTimeMillis() - t0
    check("死循环被超时掐断", looped && took < 4000, "耗时 ${took}ms")

    // 死线必须是 ThreadLocal。以前是全局变量，并发 eval 互相覆盖：
    // A 立好死线，B 跑完进 finally 就清零，A 的死循环再也不被中断。
    run {
        val results = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
        val ts = (0 until 4).map { i ->
            Thread {
                val r = runCatching { JsEngine.eval("var s=0; for(var k=0;k<1000;k++){s+=k}; s") }
                results[i] = r.isSuccess
            }.also { it.start() }
        }
        ts.forEach { it.join(10_000) }
        check("并发 4 路脚本都正常返回", results.size == 4 && results.values.all { it },
            "成功 ${results.count { it.value }}/4")
    }
    run {
        val results = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
        val ts = (0 until 2).map { i ->
            Thread {
                // 一个死循环 + 一个正常脚本同时跑：死线互不干扰，前者该被杀，后者该活。
                results[i] = runCatching { JsEngine.eval(if (i == 0) "while(true){}" else "1+1") }.isSuccess
            }.also { it.start() }
        }
        ts.forEach { it.join(15_000) }
        check("死循环与正常脚本并行时各自结算",
            results[0] == false && results[1] == true, "结果 $results")
    }

    // ── 回归：相对路径 searchUrl 要补全（2026-10-16 修）──
    // 以前只处理前导 / 的形式，`search/getX` 这种被判"不是绝对地址"而拒掉，
    // 780 条源里 22 条因此不可用。
    println("[回归] searchUrl 补全")
    val relSrc = """[{"bookSourceName":"rel","bookSourceUrl":"https://a.com","enabled":true,
      "searchUrl":"search/getSearchResult",
      "ruleSearch":{"bookList":".x","name":"a@text","bookUrl":"a@href"},
      "ruleToc":{"chapterList":".y","chapterName":"a@text","chapterUrl":"a@href"},
      "ruleContent":{"content":".z@text"}}]"""
    val relRule = LegadoImport.importAll(relSrc).first().rule
    check("无前导斜杠的相对路径能补全",
        relRule?.search?.url == "https://a.com/search/getSearchResult",
        relRule?.search?.url ?: "null(被拒)")
    check("前导斜杠的相对路径照旧",
        LegadoImport.importAll(relSrc.replace("search/getSearchResult", "/s?q=1")).first()
            .rule?.search?.url == "https://a.com/s?q=1")

    // ── 回归：<js> 多行写法（尾标签可能缺失）──
    println("[回归] <js> 多行")
    check("<js> 多行能取正文",
        FieldRule.jsCodeOf("<js>\nvar a=1;\n</js>")?.contains("var a=1") == true,
        FieldRule.jsCodeOf("<js>\nvar a=1;\n</js>") ?: "null")
    check("<js> 无尾标签也能取",
        FieldRule.jsCodeOf("<js>\nvar a=1;")?.contains("var a=1") == true)
    check("<js> 能被识别为脚本字段",
        FieldRule.jsCodeOf("<js>return 1</js>") != null)
    check("占位符 # 仍被拒",
        LegadoImport.importAll(relSrc.replace("search/getSearchResult", "#")).first().rule == null)

    // ── 回归：第三方正则必须能在 Android 上编译（2026-10-17）──
    // 设备实测 45/54 条挂在 PatternSyntaxException，本机 JVM 却全绿 ——
    // Android 的 java.util.regex 是 ICU 后端，比 JVM 严格。
    println("[回归] Android 正则兼容")
    check("花括号被转义（Illegal repetition）",
        SafeRegex.compileLenient(".*{{sourceUrl}}.*\\.html") != null)
    check("无效转义 \\《 被修掉",
        SafeRegex.compileLenient("\\《|\\》") != null)
    check("无效转义 \\作者 被修掉",
        SafeRegex.compileLenient("\\作者：.*") != null)
    check("不认的 \\p{Emoji} 被丢弃",
        SafeRegex.compileLenient("[\\p{Emoji}\\p{So}]") != null)
    check("正常中文正则不受影响",
        SafeRegex.compileLenient("第\\d+章.*|.*本章未完.*") != null)
    check("环视语法保留",
        SafeRegex.compileLenient("(?<=[^\\s])(?=第)") != null)
    check("空模式返回 null（不是匹配一切）",
        SafeRegex.compileLenient("") == null)
    // `{{...}}` 是 legado 的模板占位，不是正则语法。
    // 旧实现把它转义成字面量 `\{\{...\}\}`，等于要求正文里真的出现 "{{sourceUrl}}"
    // —— 那是错的。现在整个占位丢掉（模板在正则里不是要匹配的文本）。
    check("模板占位被丢掉，不当字面量",
        SafeRegex.compileLenient(".*{{sourceUrl}}.*")!!.containsMatchIn("x{{sourceUrl}}y"))
    // 模板丢掉后剩下的正则照常工作。`.replaceAll` 会把空匹配也算上，
    // 所以用「去掉模板后还能正常匹配」这种不依赖空匹配的断言。
    check("## 后处理：模板占位丢掉后正则仍生效",
        FieldRule("", "##{{a}}广告##").applyReplace("正文广告尾巴") == "正文尾巴")
    check("## 后处理正常替换",
        FieldRule("", "##\\d+##N").applyReplace("ab 123 cd") == "ab N cd")

    // ── 回归：单段选择器上的 ## 过滤（r7 第七节，2026-10-18）──
    // evalOne 以前直接 return selectByCss(...)，只包在 And/Or/Merge 分支里的
    // clean() 才调 applyReplace，所以 `chapterName: text##正则` 的过滤被静默丢弃，
    // 「正文卷」「最新章节.」这类非章节条目留在目录里。
    run {
        val html = """
          <div id="list"><ul>
            <li><a href="/r/1.html">第一章 陨落的天才</a></li>
            <li><a href="/r/2.html">正文卷</a></li>
            <li><a href="/r/3.html">最新章节.</a></li>
            <li><a href="/r/4.html">第二章 斗气大陆</a></li>
          </ul></div>"""
        val want = listOf("第一章 陨落的天才", "第二章 斗气大陆")
        val got = FieldRule.parse("#list li a@text##正文卷|最新章节\\.").eval(html, list = true)
        check("单段选择器的 ## 过滤生效", got == want, got.toString())
        // 过滤成空串的条目要丢掉，不留空标题。
        val noBlank = FieldRule.parse("#list li a@text##正文卷|最新章节\\.|第一章.*")
            .eval(html, list = true)
        check("过滤成空串的条目被丢弃", noBlank.none { it.isBlank() }, noBlank.toString())
        // 没写 ## 的字段行为不能变。
        val plain = FieldRule.parse("#list li a@text").eval(html, list = true)
        check("无 ## 时行为不变", plain.size == 4, plain.toString())
    }

    // ── 回归：用户要求「JS 引擎全挪进来」补的 API（2026-10-17）──
    println("[回归] 补的 JS API")
    check("t2s 繁体转简体", T2s.toSimplified("萬歲書庫") == "万岁书库")
    check("t2s 简体原样", T2s.toSimplified("万岁书库") == "万岁书库")
    check("t2s 表外字保留", T2s.toSimplified("萬X歲").contains("X"))
    check("t2s 空串", T2s.toSimplified("") == "")
    check("hexDecode 可用", JsEngine.EXTRA_HEX_DECODE("e4b8ad") == "中")
    check("hexDecode 带 0x 前缀", JsEngine.EXTRA_HEX_DECODE("0xe4b8ad") == "中")
    check("hexDecode 奇数长度返回空", JsEngine.EXTRA_HEX_DECODE("abc") == "")
    check("UA 非空", JsEngine.EXTRA_UA.isNotBlank())


    // ── 回归：ruleContent.replaceRegex 之前被整段丢弃（2026-10-17）──
    // 54 条源里 25 条用这个字段。不实现，正文会夹带站点广告与末尾公告。
    println("[回归] ruleContent.replaceRegex")
    run {
        val html = "<html><body><div class='c'>正文内容广告尾巴</div></body></html>"
        fun eng(rr: String?, first: Boolean = false) = SourceEngine(BookSourceRule(
            name = "t", host = "https://t.example",
            search = SearchRule(url = "/s", name = FieldRule.parse(""), link = FieldRule.parse("")),
            toc = TocRule(url = "/t", name = FieldRule.parse(""), link = FieldRule.parse("")),
            content = ContentRule(url = "/c", content = FieldRule.parse(".c"),
                replaceRegex = rr, replaceFirst = first),
        )) { _, _, _, _ -> html }
        check("replaceRegex 去掉广告尾巴",
            eng("##广告##").content("https://t.example/c") == "正文内容尾巴")
        check("replaceRegex 未命中时原样",
            eng("##不存在的串##").content("https://t.example/c") == "正文内容广告尾巴")
        check("replaceRegex 带替换文本",
            eng("##广告##【删】").content("https://t.example/c") == "正文内容【删】尾巴")
        check("replaceRegex 为 null 时不处理",
            eng(null).content("https://t.example/c") == "正文内容广告尾巴")
        // 实测 6 条源的正则体里有 `{{...}}`，设备上必抛 PatternSyntaxException。
        check("replaceRegex 含 {{}} 时模板被丢掉、其余部分照常替换",
            eng("##{{a}}广告##").content("https://t.example/c") == "正文内容尾巴")
        check("replaceRegex 整条只有 {{}} 时不替换",
            eng("##{{a}}##").content("https://t.example/c") == "正文内容广告尾巴")
        check("replaceRegex 含 \\p{Emoji} 不抛",
            eng("##[\\p{Emoji}]##").content("https://t.example/c") == "正文内容广告尾巴")
        check("replaceFirst 只替第一处",
            eng("##尾巴##X", first = true).content("https://t.example/c") == "正文内容广告X")
    }
    run {
        // 真实书源里的三条正则：必须都能编译且不抛
        val real = listOf(
            "##———— 网站公告 ————[\\s\\S]*$",
            "##\\s*[（(]?本章完[）)]?\\s*$|新.{0,2}书吧|吧书.{0,2}新|loadAdv\\([\\d, ]*\\);?",
            "##[\\p{Emoji}\\p{So}]|[^\\p{L}\\p{N}\\s.,!?;':\\-()...（）【】、；：“”‘’《》…·〈〉￥，。？！〔〕［］｀〖〗『』「」——]|.*(第[^，。]+章).*",
        )
        val html = "<html><body><div class='c'>正文———— 网站公告 ————垃圾</div></body></html>"
        val eng = SourceEngine(BookSourceRule(
            name = "t", host = "https://t.example",
            search = SearchRule(url = "/s", name = FieldRule.parse(""), link = FieldRule.parse("")),
            toc = TocRule(url = "/t", name = FieldRule.parse(""), link = FieldRule.parse("")),
            content = ContentRule(url = "/c", content = FieldRule.parse(".c"), replaceRegex = real[0]),
        )) { _, _, _, _ -> html }
        check("真实 replaceRegex 去掉公告尾巴",
            !eng.content("https://t.example/c").contains("垃圾"))
    }


    // ── 回归：正文分页 nextContentUrl（27/54 条源在用，2026-10-17）──
    println("[回归] nextContentUrl 正文分页")
    run {
        // 三页正文，每页末尾给下一页链接，最后一页没有
        val pages = mapOf(
            "https://p.example/1.html" to "<div class='c'>第一页<a id='nx' href='/2.html'>下一页</a></div>",
            "https://p.example/2.html" to "<div class='c'>第二页<a id='nx' href='/3.html'>下一页</a></div>",
            "https://p.example/3.html" to "<div class='c'>第三页</div>",
        )
        var hits = 0
        val eng = SourceEngine(BookSourceRule(
            name = "p", host = "https://p.example",
            search = SearchRule(url = "/s", name = FieldRule.parse(""), link = FieldRule.parse("")),
            toc = TocRule(url = "/t", name = FieldRule.parse(""), link = FieldRule.parse("")),
            content = ContentRule(url = "", content = FieldRule.parse(".c@html"),
                nextContentUrl = FieldRule.parse("#nx@href")),
        )) { url, _, _, _ -> hits++; pages[url] ?: "" }
        val body = eng.content("https://p.example/1.html")
        check("分页跟到底（3 页）", hits == 3, "实际请求 $hits 次")
        check("三页内容都在", body.contains("第一页") && body.contains("第二页") && body.contains("第三页"))
    }
    run {
        // 死循环防护：下一页指回自身
        var hits = 0
        val eng = SourceEngine(BookSourceRule(
            name = "loop", host = "https://l.example",
            search = SearchRule(url = "/s", name = FieldRule.parse(""), link = FieldRule.parse("")),
            toc = TocRule(url = "/t", name = FieldRule.parse(""), link = FieldRule.parse("")),
            content = ContentRule(url = "", content = FieldRule.parse(".c"),
                nextContentUrl = FieldRule.parse("a@href")),
        )) { url, _, _, _ ->
            hits++
            "<div class='c'>正文<a href='https://l.example/same'>下一页</a></div>"
        }
        eng.content("https://l.example/1.html")
        check("下一页指回自身时不无限循环", hits <= 2, "实际请求 $hits 次")
    }

    // ── 回归：目录分页（nextTocUrl）总页数封顶（2026-10-22）──
    // 以前只封了内层链式循环，外层 `pending` 有多少就请求多少次。
    // `.pagination a@href` 返回全部页码时，一本书能联网几百次，界面卡在「正在取目录…」。
    println("[回归] 目录分页封顶")
    run {
        var hits = 0
        // 第一页给 200 个后续页链接，故意远超上限。
        val firstPage = "<ul class='ch'><li><a href='/c1'>一</a></li></ul>" +
            "<div class='pg'>" + (1..200).joinToString("") { "<a href='/p$it'>$it</a>" } + "</div>"
        val eng = SourceEngine(BookSourceRule(
            name = "many", host = "https://m.example",
            search = SearchRule(url = "/s", name = FieldRule.parse(""), link = FieldRule.parse("")),
            toc = TocRule(
                url = "/t",
                name = FieldRule.parse(".ch a@text"),
                link = FieldRule.parse(".ch a@href"),
                nextTocUrl = FieldRule.parse(".pg a@href"),
            ),
            content = ContentRule(url = "", content = FieldRule.parse(".ch")),
        )) { url, _, _, _ ->
            hits++
            if (url.endsWith("/t")) firstPage
            else "<ul class='ch'><li><a href='/x'>章</a></li></ul>"
        }
        runCatching { eng.toc("https://m.example/t") }
        check("200 个后续页不会全请求", hits <= 32, "实际请求 $hits 次")
    }
    run {
        // 没有 nextContentUrl 时只请求一次
        var hits = 0
        val eng = SourceEngine(BookSourceRule(
            name = "nopage", host = "https://n.example",
            search = SearchRule(url = "/s", name = FieldRule.parse(""), link = FieldRule.parse("")),
            toc = TocRule(url = "/t", name = FieldRule.parse(""), link = FieldRule.parse("")),
            content = ContentRule(url = "", content = FieldRule.parse(".c")),
        )) { _, _, _, _ -> hits++; "<div class='c'>正文<a href='/2'>下一页</a></div>" }
        eng.content("https://n.example/1.html")
        check("无分页规则时只请求 1 次", hits == 1, "实际请求 $hits 次")
    }


    // ── 回归：2026-10-17 为「54 条源全可用」补的四类修复 ──
    println("[回归] 字段接线与 ICU 兼容")
    run {
        // 1. `@css:` 前缀不能被 toCss 吃掉（阅读书库的 chapterList 曾因此丢空）
        check("@css: 前缀被剥离",
            LegadoSelector.toCss("@css:.a > li").trim() == ".a > li",
            LegadoSelector.toCss("@css:.a > li"))
        check("@css: 折叠换行后可用",
            !LegadoSelector.toCss("@css:\n.a > li,\n.b > li").contains("css:"),
            LegadoSelector.toCss("@css:\n.a > li,\n.b > li"))
        check("普通选择器不受影响", LegadoSelector.toCss(".a@text") == ".a@text")

        // 2. 「CSS 在前、脚本在后」只取 CSS 前缀
        check("CSS + <js> 取 CSS",
            FieldRule.cssBeforeScript(".chapters li a ||.list_xm@li@a\n<js>\nx()\n</js>")
                ?.startsWith(".chapters li a") == true)
        check("CSS + @js: 取 CSS",
            FieldRule.cssBeforeScript("ul > li\n@js:\nresult.sort();") == "ul > li")
        check("纯脚本开头返回 null（没有可用 CSS）",
            FieldRule.cssBeforeScript("@js:\nresult;") == null)
        check("无脚本返回 null", FieldRule.cssBeforeScript(".a@text") == null)

        // 3. 模板 URL 不再一律拒绝
        val tplSrc = """[{"bookSourceName":"tpl","bookSourceUrl":"https://t.com",
          "searchUrl":"{{source.getKey()}}/search?q={{key}}","header":"{}",
          "ruleSearch":{"bookList":".r","name":".n@text","bookUrl":".n@href"},
          "ruleToc":{"chapterList":".c","chapterName":"a@text","chapterUrl":"a@href"},
          "ruleContent":{"content":".z"}}]"""
        check("模板 URL 在导入期放行",
            LegadoImport.importAll(tplSrc).first().rule != null,
            LegadoImport.importAll(tplSrc).first().issue ?: "")
        // `<js>` searchUrl 现在是**支持**的（存成 urlJs，请求期求值），不再拒绝。
        val bareJs = tplSrc.replace("{{source.getKey()}}/search?q={{key}}", "<js>f()</js>")
        val bj = LegadoImport.importAll(bareJs).first()
        check("JS searchUrl 存为 urlJs 而不是被拒",
            bj.rule?.search?.urlJs == "f()", bj.rule?.search?.urlJs ?: "null")
        check("JS searchUrl 的 url 留空（请求期才算）",
            bj.rule?.search?.url == "", bj.rule?.search?.url ?: "null")

        // 4. `@js:` 请求头在导入期求值
        val jsHeader = """[{"bookSourceName":"jh","bookSourceUrl":"https://j.com",
          "searchUrl":"/s?q={{key}}","header":"@js:JSON.stringify({'X-T':'ab'})",
          "ruleSearch":{"bookList":".r","name":".n@text","bookUrl":".n@href"},
          "ruleToc":{"chapterList":".c","chapterName":"a@text","chapterUrl":"a@href"},
          "ruleContent":{"content":".z"}}]"""
        val jh = LegadoImport.importAll(jsHeader).first()
        check("@js: 头能导入", jh.rule != null, jh.issue ?: "")
        check("@js: 头解出正确键值", jh.rule?.search?.headers?.get("X-T") == "ab",
            jh.rule?.search?.headers?.toString() ?: "null")
        check("坏 @js: 头才判不可用",
            LegadoImport.importAll(jsHeader.replace("JSON.stringify({'X-T':'ab'})", "throw 1"))
                .first().rule == null)
    }


    // ── 回归：「选择器 + 脚本尾巴」不再是「不支持的脚本写法」（2026-10-17）──
    // 实测四五十个字段这么写（`.`book-title@text@js:java.t2s(result)` 等）。
    // 以前 jsCodeOf 只在开头找 @js:，这些字段全被判成不支持而丢弃。
    println("[回归] 选择器 + 脚本尾巴")
    run {
        val html = "<div class='book-title'>萬歲書庫</div><a class='n' href='/b/1.html'>鏈接</a>"

        check("@js: 尾巴被识别为脚本",
            FieldRule.jsCodeOf(".book-title@text@js:java.t2s(result)") == "java.t2s(result)")
        check("<js> 尾巴被识别为脚本",
            FieldRule.jsCodeOf("a@href<js>result</js>") == "result")
        check("纯选择器不误判为脚本",
            FieldRule.jsCodeOf(".book-title@text") == null)
        check("开头 @js: 仍识别",
            FieldRule.jsCodeOf("@js:result") == "result")

        check("选择器部分被正确切出",
            FieldRule.selectorPartOf(".book-title@text@js:java.t2s(result)") == ".book-title@text")

        // 端到端：选择器先取值，脚本拿到的是选择器的结果（不是整段 HTML）
        check("选择器+@js: 选择器结果进脚本",
            FieldRule.parse(".book-title@text@js:java.t2s(result)").eval(html).firstOrNull() == "万岁书库",
            FieldRule.parse(".book-title@text@js:java.t2s(result)").eval(html).toString())
        check("选择器+<js> 同样生效",
            FieldRule.parse("a@href<js>result.replace('.html','')</js>").eval(html).firstOrNull() == "/b/1",
            FieldRule.parse("a@href<js>result.replace('.html','')</js>").eval(html).toString())
        check("纯选择器不受影响",
            FieldRule.parse(".book-title@text").eval(html).firstOrNull() == "萬歲書庫")
        check("纯脚本不受影响",
            FieldRule.parse("@js:'X'+result").eval("abc").firstOrNull() == "Xabc")
    }

    // ── 回归：支持的脚本不再报「部分字段用脚本」（假警报）──
    println("[回归] searchUrl 脚本不报警")
    run {
        val src = """[{"bookSourceName":"jsurl","bookSourceUrl":"https://js.example",
          "searchUrl":"@js:base + '/s?q=' + key","header":"{}",
          "ruleSearch":{"bookList":".r","name":".n@text","bookUrl":".n@href"},
          "ruleToc":{"chapterList":".c","chapterName":"a@text","chapterUrl":"a@href"},
          "ruleContent":{"content":".z"}}]"""
        val i = LegadoImport.importAll(src).first()
        check("JS searchUrl 导入且不报警", i.rule != null && i.issue == null,
            "issue=${i.issue}")
        check("JS searchUrl 存进 urlJs", i.rule?.search?.urlJs == "base + '/s?q=' + key",
            i.rule?.search?.urlJs ?: "null")
    }


    // ── 回归：搜索结果里冒出 <em>（2026-10-17 用户报）──
    // 根因有两个：REGEX_MARK 只写不读、位置索引整体错一位。
    println("[回归] 搜索结果显示干净")
    run {
        val html = """<div class="list"><ul><li>
          <a href="/b/0">第0个</a><a href="/b/1">斗破<em>苍穹</em></a>
          <span class="author">作者：天蚕<span>土豆</span></span>
          <p class="desc">这是<em>斗破</em>的简介</p></li></ul></div>"""

        // 1. 纯正则提取（REGEX_MARK）以前只写不读，落进选择器解析后返回整段 HTML
        val reRule = FieldRule(LegadoImport.REGEX_MARK + "##作者：([^<]+)<##$1###")
        check("纯正则字段提取到作者（不返回整段 HTML）",
            reRule.eval(html).firstOrNull() == "天蚕", reRule.eval(html).toString())
        // `###` = replaceFirst：只取匹配片段再替换，不是整段替换
        val reRule2 = FieldRule(LegadoImport.REGEX_MARK + "##作者：([^<]+)<##[$1]###")
        check("### 只对匹配片段替换", reRule2.eval(html).firstOrNull() == "[天蚕]",
            reRule2.eval(html).toString())
        // 不带 ### 时是整段替换（其余文本保留）
        val reRule3 = FieldRule(LegadoImport.REGEX_MARK + "##作者：[^<]+<##")
        check("无 ### 时整段替换、其余保留",
            reRule3.eval(html).firstOrNull()?.startsWith("<div") == true,
            reRule3.eval(html).toString().take(60))

        // 2. 位置索引按 legado 的 0-based（以前减了 1，整体错一位）
        check("a.0 取第 0 个（0-based）",
            FieldRule.parse("a.0@text").eval(html).firstOrNull() == "第0个",
            FieldRule.parse("a.0@text").eval(html).toString())
        check("a.1 取第 1 个",
            FieldRule.parse("a.1@text").eval(html).firstOrNull() == "斗破苍穹",
            FieldRule.parse("a.1@text").eval(html).toString())
        check("越界返回空",
            FieldRule.parse("a.9@text").eval(html).isEmpty())

        // 3. @html 取值后给用户看之前要剥标签
        check("@text 天然干净（<em> 不出现在文本里）",
            FieldRule.parse("a.1@text").eval(html).firstOrNull() == "斗破苍穹",
            FieldRule.parse("a.1@text").eval(html).toString())
        check("@html 带标签（由 search() 出口统一剥）",
            FieldRule.parse("p.desc@html").eval(html).firstOrNull()?.contains("<em>") == true,
            FieldRule.parse("p.desc@html").eval(html).toString())
    }

    // 端到端：搜索结果三个文本字段都不含标签
    run {
        val html = """<div class="list"><ul><li>
          <a href="/b/1">斗破<em>苍穹</em></a>
          <span class="author">天蚕<em>土豆</em></span>
          <p class="desc">这是<em>斗破</em>的简介</p></li></ul></div>"""
        val rule = BookSourceRule(
            name = "t", host = "https://t.example",
            search = SearchRule(url = "/s", name = FieldRule.parse("a@html"),
                link = FieldRule.parse("a@href"), author = FieldRule.parse("span.author@html"),
                intro = FieldRule.parse("p.desc@html"), list = FieldRule.parse("li")),
            toc = TocRule(url = "/t", name = FieldRule.parse("a@text"), link = FieldRule.parse("a@href")),
            content = ContentRule(url = "", content = FieldRule.parse(".z")),
        )
        val hits = SourceEngine(rule) { _, _, _, _ -> html }.search("斗破")
        val all = hits.flatMap { listOf(it.name, it.author, it.abstractText) }
        check("搜索结果里没有尖括号", all.none { it.contains('<') || it.contains('>') }, all.toString())
        check("搜索结果里没有 <em>", all.none { it.contains("<em>") }, all.toString())
        check("内容没被误删", all.any { it.contains("斗破") }, all.toString())
    }

    // ── 回归：chapterList 脚本返回对象数组（5 条源）──
    // 形如 `@js: let v=[]; JSON.parse(result).list.map($=>{…}); list`，
    // 元素是 {n:…,u:…}，chapterName / chapterUrl 写 `$.n` / `$.u`，在该对象内取值。
    println("[回归] 对象数组 chapterList")
    run {
        val json = """{"list":[{"name":"第一章 觉醒","url":"/c/1.html"},
            {"name":"第二章 突破","url":"/c/2.html"}]}"""
        // 名字和链接是两个字段，所以各自的脚本押自己的键：$.n / $.u。
        val nameScript = "let list = []; JSON.parse(result).list.map($ => { list.push({n: $.name, u: $.url}) }); list"
        val rule = BookSourceRule(
            name = "objs", host = "https://o.example",
            search = SearchRule(url = "/s", name = FieldRule.parse("a@text"), link = FieldRule.parse("a@href")),
            toc = TocRule(
                url = "/t",
                // chapterList 返回对象数组；名字和链接各在对象内取 $.n / $.u。
                sourceList = FieldRule.parse("@js:$nameScript"),
                name = FieldRule.parse("$.n"),
                link = FieldRule.parse("$.u"),
            ),
            content = ContentRule(url = "", content = FieldRule.parse(".z")),
        )
        val eng = SourceEngine(rule) { _, _, _, _ -> json }
        val info = eng.toc("https://o.example/b/1")
        check("对象数组取到两章", info.chapters.size == 2, info.chapters.toString())
        check("章节名从 $.n 取", info.chapters.map { it.title } == listOf("第一章 觉醒", "第二章 突破"),
            info.chapters.map { it.title }.toString())
        check("章节链接从 $.u 取并绝对化",
            info.chapters.map { it.itemId } == listOf("https://o.example/c/1.html", "https://o.example/c/2.html"),
            info.chapters.map { it.itemId }.toString())
        check("对象数组不影响旧路径", info.chapters.size == 2)

        // 裸键名写法（全本小说）：chapterName / chapterUrl 写 `name` / `url`，不带 `$.`。
        val eng3 = SourceEngine(rule.copy(
            toc = TocRule(url = "/t",
                sourceList = FieldRule.parse("@js:$nameScript"),
                name = FieldRule.parse("n"),
                link = FieldRule.parse("u")))) { _, _, _, _ -> json }
        check("裸键名取到两章",
            eng3.toc("https://o.example/b/1").chapters.map { it.title } == listOf("第一章 觉醒", "第二章 突破"),
            eng3.toc("https://o.example/b/1").chapters.toString())
        // 普通 CSS 规则不能被裸键名分支截走：`a@href` 不是标识符，走旧路径。
        check("CSS 规则不被当成键名",
            eng3.toc("https://o.example/b/1").chapters.map { it.itemId }
                .all { it.startsWith("https://o.example/") },
            eng3.toc("https://o.example/b/1").chapters.map { it.itemId }.toString())

        // 字符串列表必须原样：同样的 CSS 目录不能被对象数组分支接走。
        val htmlToc = """<div id="toc"><a href="/c/9.html">第九章</a></div>"""
        val eng2 = SourceEngine(rule.copy(
            toc = TocRule(url = "/t", name = FieldRule.parse("#toc a@text"),
                link = FieldRule.parse("#toc a@href")))) { _, _, _, _ -> htmlToc }
        check("字符串目录不受影响",
            eng2.toc("https://o.example/b/1").chapters.map { it.title } == listOf("第九章"),
            eng2.toc("https://o.example/b/1").chapters.toString())

        // legado 的 ruleBookInfo.init＝先把书页响应收窄到子对象，再求值后续字段。
        // 实测 5 条 0ms 失败的源正是这种写法：详情页套娃 {code,data:{bookVo:{bookId}}}，
        // 规则写 init="$.data.bookVo" + tocUrl="…?bookId={{$.bookId}}"。
        // 不实现 init，`$.bookId` 在顶层找→取空→URL 变成 `?bookId=`。
        val detail = """{"code":"SUCCESS","data":{"bookVo":{"bookId":"803500510"}}}"""
        val tocJson ="""{"list":[{"name":"卷一","bookChapters":[
            {"name":"第一章","id":"c1","bookid":"803500510"}]}]}"""
        var asked = ""
        val engInit = SourceEngine(rule.copy(
            toc = TocRule(url = "",
                // tocUrl 是整串 URL 模板，靠 TEMPLATE_MARK 标记原样传出。
                tocUrlRule = FieldRule(LegadoImport.TEMPLATE_MARK +
                    "https://api.example/getvolume?bookId={{$.bookId}}"),
                initRule = FieldRule.parse("@json:$.data.bookVo"),
                name = FieldRule.parse("@json:$.list[0].bookChapters[0].name"),
                link = FieldRule.parse("@json:$.list[0].bookChapters[0].id")))) { u, _, _, _ ->
            asked = u
            if (u.contains("bookDetail")) detail else tocJson
        }
        engInit.toc("https://api.example/bookDetail?cnid=1")
        check("init 收窄后 tocUrl 取到 bookId",
            asked == "https://api.example/getvolume?bookId=803500510", asked)

        // init 取不到时不能把书页正文弄没。
        var asked2 = ""
        val engInitMiss = SourceEngine(rule.copy(
            toc = TocRule(url = "",
                initRule = FieldRule.parse("@json:$.no.such"),
                name = FieldRule.parse("@json:$.list[0].bookChapters[0].name"),
                link = FieldRule.parse("@json:$.list[0].bookChapters[0].id")))) { u, _, _, _ ->
            asked2 = u; tocJson
        }
        check("init 未命中时保留原响应",
            engInitMiss.toc("https://api.example/bookDetail").chapters.map { it.title } == listOf("第一章"),
            asked2)

        // searchFlat（无 bookList）时，bookUrl 里的 {{$.x}} 绑整篇搜索响应。
        // 实测「全本小说」写 `findChapterList?book_id={{$.bookId}}`，bookList 缺失，
        // 以前 searchFlat 只 absolute(link)，模板原样进 URL → book_id= 空。
        // 用一个返回模板字面量的 link 选择器把这条路径针住。
        val flatJson = """{"bookId":"9549","items":[{"n":"斗破","a":"xx"}]}"""
        val engFlat = SourceEngine(rule.copy(
            search = SearchRule(
                url = "https://api.example/find?q={{key}}",
                name = FieldRule.parse("@json:$.items[0].n"),
                link = FieldRule("http://api.example/findChapterList?book_id={{\$.bookId}}"),
                author = FieldRule.parse("@json:$.items[0].a")))) { _, _, _, _ -> flatJson }
        check("searchFlat 的 {{$.x}} 绑整篇响应",
            engFlat.search("斗破").firstOrNull()?.bookId == "http://api.example/findChapterList?book_id=9549",
            engFlat.search("斗破").firstOrNull()?.bookId ?: "null")
    }

    if (failures > 0) { println("链接自检失败 $failures 项"); kotlin.system.exitProcess(1) }
    println("链接自检通过")
}

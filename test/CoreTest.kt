import com.watchdl.core.Template
import com.watchdl.core.TinyJson
import com.watchdl.core.CharsetFontDecoder
import com.watchdl.core.LegadoImport

/** 纯 JVM 自检：规则模板、极简 JSON 取值、字符表还原。跑法见 build.sh 的 test 目标。 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("  ✓ $name") else { failures++; println("  ✗ $name  $detail") }
    }

    println("[Template] 搜狗式分页与变量")
    check("变量直取", Template.render("q={{kw}}&p={{page}}", mapOf("kw" to "斗破苍穹", "page" to "2"))
        == "q=斗破苍穹&p=2")
    check("算术表达式", Template.render("offset={{(page-1)*10}}", mapOf("page" to "3")) == "offset=20")
    check("除法与括号", Template.render("{{(10+6)/4}}", emptyMap()) == "4")
    check("缺失变量按 0 参与运算", Template.render("{{(page-1)*10}}", emptyMap()) == "-10")
    check("未知变量取值得到空串", Template.render("[{{name}}]", mapOf("other" to "x")) == "[]")
    check("无占位符原样返回", Template.render("https://a.b/c", emptyMap()) == "https://a.b/c")
    check("未闭合括号不丢尾", Template.render("a{{b", mapOf("b" to "1")) == "a{{b")

    println("[TinyJson] 路径取值")
    val search = """
    {"code":0,"data":{"has_more":true,"ret_data":[
      {"book_id":"7556555977751792702","book_name":"斗破苍穹","author":"天蚕土豆","abstract":"简介A"},
      {"book_id":"111","book_name":"第二本","author":"某人","abstract":"简介\n换行"}
    ]}}
    """.trimIndent()
    check("取嵌套字符串", TinyJson.path(search, "data.ret_data[0].book_id") == "7556555977751792702")
    check("取中文值", TinyJson.path(search, "data.ret_data[0].book_name") == "斗破苍穹")
    check("取数字", TinyJson.path(search, "code") == "0")
    check("取布尔", TinyJson.path(search, "data.has_more") == "true")
    check("取数组第二项", TinyJson.path(search, "data.ret_data[1].book_id") == "111")
    check("路径不存在返回 null", TinyJson.path(search, "data.ret_data[9].book_id") == null)
    check("键不存在返回 null", TinyJson.path(search, "data.nope") == null)

    println("[TinyJson] 列表提取与转义")
    val ids = TinyJson.arrayField(search, "data.ret_data", "book_id")
    check("提取全部 book_id", ids == listOf("7556555977751792702", "111"), "实际 $ids")
    check("转义 \\u 还原", TinyJson.unescape("\\u003Cp\\u003E正文") == "<p>正文")
    check("转义引号与反斜杠", TinyJson.unescape("a\\\"b\\\\c") == "a\"b\\c")
    check("无转义原样返回", TinyJson.unescape("普通文本") == "普通文本")

    println("[TinyJson] 纯字符串数组")
    val sarr = """{"data":{"allItemIds":["7672698507827891224","7542921625763597336"],"other":1}}"""
    check("取字符串数组", TinyJson.stringArray(sarr, "allItemIds") ==
        listOf("7672698507827891224", "7542921625763597336"))
    check("键不存在返回空", TinyJson.stringArray(sarr, "nope").isEmpty())

    println("[TinyJson] 正文抽取（回归：大正文不得爆栈）")
    val big = "x".repeat(30_000)
    val page = """{"a":1,"reader":{"chapterData":{"content":"$big","id":"9"}},"junk":"content"}"""
    val got = TinyJson.extractContent(page)
    check("抽出最长 content", got == big, "实际长度 ${got?.length}")
    check("小 content 被过滤", TinyJson.extractContent("""{"content":"短"}""") == null)
    check("无 content 返回 null", TinyJson.extractContent("{\"other\":1}") == null)
    check("未闭合不抛异常", TinyJson.extractContent("{\"content\":\"never closed") == null)
    val escBody = "a".repeat(300)
    val escJson = "{\"content\":\"" + escBody + "\\\"引号\\u003Cp\\u003E\"}"
    val esc = TinyJson.extractContent(escJson, minLength = 10)
    check("转义后还原", esc != null && esc.contains("\"引号<p>") && esc.startsWith(escBody),
        "得到 ${esc?.takeLast(24)}")

    println("[CharsetFontDecoder] 边界行为")
    val table = CharsetFontDecoder(
        rows = listOf(listOf("甲", "乙", "?"), listOf("丙", "丁")),
        code = listOf(0xE000..0xE0FF, 0xE100..0xE1FF),
    )
    check("第一行命中", table.decode("\uE000\uE001") == "甲乙")
    check("问号项保留原字符", table.decode("\uE002") == "\uE002")
    check("第二行命中", table.decode("\uE100\uE101") == "丙丁")
    check("区间外原样保留", table.decode("abc") == "abc")
    check("越界原样保留", table.decode("\uE0FF") == "\uE0FF")
    check("空表恒等变换", CharsetFontDecoder(emptyList()).decode("\uE000x") == "\uE000x")

    val real = System.getenv("CHARSET_JSON")
    if (!real.isNullOrEmpty()) {
        println("[CharsetFontDecoder] 真实字符表 $real")
        val d = CharsetFontDecoder.fromFile(real)
        check("载入两行表", d.rows.size == 2, "实际 ${d.rows.size} 行")
        check("首行长度合理", d.rows[0].size > 300, "实际 ${d.rows[0].size}")
        val sample = "\uE3F0\uE4F3\uE4F4\uE42C"
        val out = d.decode(sample)
        check("私用区字符全还原", out.none { it.code in 0xE000..0xF8FF }, "得到 $out")
        println("    样本 \"演武场…\" 首四字 → $out")
    } else {
        println("[CharsetFontDecoder] 未设 CHARSET_JSON，跳过真实字符表")
    }

    // 书源库回归门：assets 里那包书源一旦退化成「全都搜不出」，
    // App 的搜索会静默变空。这里只查结构可用性（导入层），不发网络请求。
    val lib = java.io.File("app/assets/sources/legado.json")
    if (lib.exists()) {
        val all = LegadoImport.importAll(lib.readText())
        var usable = 0
        for (item in all) {
            val r = item.rule ?: continue
            if (r.search.name.selector.isNotBlank() && r.search.link.selector.isNotBlank() &&
                r.toc.name.selector.isNotBlank() && r.toc.link.selector.isNotBlank()) usable++
        }
        println("[书源库] ${lib.length() / 1024} KB，${all.size} 条，结构可用 $usable 条")
        check("书源库至少 30 条结构可用", usable >= 30, "实际 $usable")
    } else {
        println("[书源库] 未找到 ${lib.path}，跳过")
    }

    println("[Rule] legado {{$.x}} 模板")
    // `@js:` 开头的 searchUrl 不能被「URL,选项」切分逻辑截断。
    // XIU2 那批 22 条里有 6 条把 `,{'method':...}` 写在 JS 字符串中间，
    // 旧代码在第一个 `,{` 处切开，Rhino 报 unterminated string literal。
    println("[LegadoImport] @js 开头的 searchUrl 完整保留")
    run {
        // 用普通字符串：原始字符串里 \" 不是转义，会把非法 JSON 喂给解析器。
        val jsUrl = "@js:url=baseUrl+\"/so/{{key}}.html,{'method':'GET'}\";result=url;"
        val src = "[" + "{\"bookSourceName\":\"js源\",\"bookSourceUrl\":\"https://a.example\"," +
            "\"searchUrl\":" + "\"" + jsUrl.replace("\"", "\\\"") + "\"," +
            "\"ruleSearch\":{\"bookList\":\"class.x\",\"name\":\"a@text\",\"bookUrl\":\"a@href\"}," +
            "\"ruleToc\":{\"chapterList\":\"class.c\",\"chapterName\":\"a@text\",\"chapterUrl\":\"a@href\"}}" + "]"
        val im = LegadoImport.importAll(src).firstOrNull()
        val js = im?.rule?.search?.urlJs
        check("searchUrl 脚本被保留", js != null, "解析不出 urlJs；issue=${im?.issue}")
        // 关键断言：整段 JS 都在，没有被 `,{` 切断。
        check("脚本未被 ,{ 截断", js?.contains("{'method':'GET'}") == true, js ?: "null")
        check("脚本结尾完整", js?.trimEnd()?.endsWith("result=url;") == true, js ?: "null")
    }

    val authorJson = """{"author_nickname":"天蚕土豆","book_id":123}"""
    check("作者模板求值",
        com.watchdl.core.FieldRule("@{{$.author_nickname}}").eval(authorJson) == listOf("天蚕土豆"),
        com.watchdl.core.FieldRule("@{{$.author_nickname}}").eval(authorJson).toString())
    check("模板里混字面量",
        com.watchdl.core.FieldRule("{{$.author_nickname}}·著").eval(authorJson) == listOf("天蚕土豆·著"),
        com.watchdl.core.FieldRule("{{$.author_nickname}}·著").eval(authorJson).toString())
    check("取不到给空串，不露规则原文",
        com.watchdl.core.FieldRule("@{{$.nope}}").eval(authorJson).isEmpty(),
        com.watchdl.core.FieldRule("@{{$.nope}}").eval(authorJson).toString())

    println("[TinyJson] 嵌套路径定位数组（回归：不能命中同名键）")
    // 页面 SSR 真实的形态：前面还有一段无关的 {"total":0}，然后是 "rank":{...}。
    // 以前 arrayField 拿值段文本再 indexOf 回位置，会认错数组。
    val ssr = """{"rank":{"serverRendered":true,"book_list":[""" +
        """{"bookId":"1","bookName":"甲"},{"bookId":"2","bookName":"乙"}]},"tail":{"total":0}}"""
    check("嵌套路径取到两个", TinyJson.arrayField(ssr, "rank.book_list", "bookId") == listOf("1", "2"),
        "实际 " + TinyJson.arrayField(ssr, "rank.book_list", "bookId"))
    check("同名键不串位", TinyJson.arrayField(ssr, "rank.book_list", "bookName") == listOf("甲", "乙"),
        "实际 " + TinyJson.arrayField(ssr, "rank.book_list", "bookName"))

    println("[JobStore] 取消落盘")
    val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "jobstore-cancel-test")
    tmp.deleteRecursively(); tmp.mkdirs()
    val st = com.watchdl.core.JobStore(java.io.File(tmp, "books"))
    st.writeJob("bid1", """{"bookId":"bid1","name":"X","author":"","state":"RUNNING","chapters":[]}""")
    st.writeState("bid1", com.watchdl.core.JobState.PAUSED)
    val after = st.readJob("bid1") ?: ""
    check("取消把 RUNNING 改成 PAUSED", after.contains("\"state\":\"PAUSED\""), after)
    check("其余字段保留", after.contains("\"bookId\":\"bid1\""), after)
    tmp.deleteRecursively()

    if (failures == 0) println("\n全部通过") else { println("\n失败 $failures 项"); kotlin.system.exitProcess(1) }
}

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
        println("[书源库] ${lib.length() / 1024} KB，${all.size} 条")
        check("精选书源库非空", all.isNotEmpty())
        for (item in all) {
            val r = item.rule
            check("${item.name} 导入无错误", r != null && item.issue == null,
                item.issue ?: "规则为空")
            if (r == null) continue
            check("${item.name} 名称完整", item.name.isNotBlank() && r.name.isNotBlank())
            val host = runCatching { java.net.URI(r.host) }.getOrNull()
            check("${item.name} 站点 URL 完整",
                host?.scheme in listOf("http", "https") && !host?.host.isNullOrBlank(), r.host)
            check("${item.name} 搜索入口完整", r.search.url.isNotBlank() || !r.search.urlJs.isNullOrBlank())
            val fields = listOf(r.search.name, r.search.link, r.toc.name, r.toc.link, r.content.content)
            check("${item.name} 搜索目录正文规则完整",
                fields.all { it.selector.isNotBlank() && it.selector != com.watchdl.core.FieldRule.UNSUPPORTED },
                fields.map { it.selector }.toString())
        }
        val urls = all.mapNotNull { it.rule?.host }
        check("书源 URL 唯一", urls.distinct().size == urls.size, urls.toString())
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

    println("[本地导入] txt 分章")
    run {
        val txt = """
            书名页免责声明

            序章 引子

            引子正文。

            第一章 少年

            少年正文第一段。
            少年正文第二段。

            第二章 起步

            起步正文。
        """.trimIndent()
        val chs = com.watchdl.core.LocalImport.splitTxt(txt)
        check("中文标题切成 3 章", chs.size == 3, "实际 ${chs.size}")
        check("首章标题保留", chs[0].title == "序章 引子", chs[0].title)
        check("标题行不进正文", chs[1].body.startsWith("少年正文第一段"), chs[1].body)
        check("正文两段都在", chs[1].body.contains("少年正文第二段"), chs[1].body)
        check("短章前导丢成开篇", chs.none { it.title == "开篇" }, chs.map { it.title }.toString())
    }
    run {
        // 第一个标题之前的正文够长时要单独成章，不能默默吞掉。
        val long = "前" + "言".repeat(300) + "\n\n第一章 起\n\n正文。"
        val chs = com.watchdl.core.LocalImport.splitTxt(long)
        check("长前导单独成章", chs.size == 2 && chs[0].title == "开篇", chs.map { it.title }.toString())
    }
    run {
        val en = "Chapter 1 The Start\n\nHello there.\n\nChapter 2 Next\n\nAgain."
        val chs = com.watchdl.core.LocalImport.splitTxt(en)
        check("英文标题切两章", chs.size == 2, "实际 ${chs.size}: " + chs.map { it.title })
        check("英文首章正文对", chs[0].body == "Hello there.", chs[0].body)
    }
    run {
        val plain = "就是一段没有章节标题的文字。\n第二段也是。"
        val chs = com.watchdl.core.LocalImport.splitTxt(plain)
        check("无标题退化成一章", chs.size == 1 && chs[0].title == "全书", chs.map { it.title }.toString())
        check("无标题正文完整", chs[0].body.contains("第二段也是"), chs[0].body)
    }
    run {
        // 正文里出现「第三章」的句子不能被当成标题切碎。
        val mid = "第一章 甲\n\n他翻到第三章就不再看了，那是后话。\n\n第二章 乙\n\n乙正文。"
        val chs = com.watchdl.core.LocalImport.splitTxt(mid)
        check("行内提到章号不误切", chs.size == 2, "实际 ${chs.size}")
    }

    println("[本地导入] 编码")
    run {
        val s = "第一章 开始\n\n这是一段简体中文正文，用来验证 GBK 识别。"
        val gbk = s.toByteArray(charset("GB18030"))
        check("GBK 字节能还原", com.watchdl.core.LocalImport.decodeText(gbk) == s,
            com.watchdl.core.LocalImport.decodeText(gbk).take(20))
        check("UTF-8 字节能还原", com.watchdl.core.LocalImport.decodeText(s.toByteArray()) == s)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray()
        check("带 BOM 的 UTF-8 不残留 BOM", com.watchdl.core.LocalImport.decodeText(bom) == s,
            com.watchdl.core.LocalImport.decodeText(bom).take(3))
    }

    println("[本地导入] epub spine")
    run {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
            put("META-INF/container.xml",
                "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>")
            // manifest 故意把 c2 写在 c1 前面：顺序必须听 spine，不听 manifest。
            put("OEBPS/content.opf",
                "<package><manifest>" +
                    "<item id=\"c2\" href=\"text/ch2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"c1\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                    "<item id=\"css\" href=\"s.css\" media-type=\"text/css\"/>" +
                    "</manifest><spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>")
            put("OEBPS/text/ch1.xhtml",
                "<html><body><h1>第一章 开始</h1><p>第一段正文。</p></body></html>")
            put("OEBPS/text/ch2.xhtml",
                "<html><body><h1>第二章 继续</h1><p>第二段正文。</p></body></html>")
            put("OEBPS/s.css", "p{}")
        }
        val chs = com.watchdl.core.LocalImport.epubChapters(bos.toByteArray())
        check("spine 顺序取到两章", chs.size == 2, "实际 ${chs.size}")
        check("第一章在前", chs[0].title == "第一章 开始", chs[0].title)
        check("第二章在后", chs.getOrNull(1)?.title == "第二章 继续", chs.getOrNull(1)?.title ?: "null")
        check("XHTML 转成纯文本", chs[0].body.contains("第一段正文"), chs[0].body)
    }

    println("[本地导入] epub 路径与退化包")
    run {
        fun zip(build: (java.util.zip.ZipOutputStream) -> Unit): ByteArray {
            val bos = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bos).use { build(it) }
            return bos.toByteArray()
        }
        fun put(z: java.util.zip.ZipOutputStream, name: String, body: String) {
            z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
        }

        // container 写成 ./OEBPS/content.opf：不归一化就查不到 OPF。
        val dotted = zip { z ->
            put(z, "META-INF/container.xml",
                "<container><rootfiles><rootfile full-path=\"./OEBPS/content.opf\"/></rootfiles></container>")
            put(z, "OEBPS/content.opf",
                "<package><manifest><item id=\"c1\" href=\"text/ch1.xhtml\"/></manifest>" +
                    "<spine><itemref idref=\"c1\"/></spine></package>")
            put(z, "OEBPS/text/ch1.xhtml", "<html><body><h1>第一章 点</h1><p>点号路径正文。</p></body></html>")
        }
        val d = com.watchdl.core.LocalImport.epubChapters(dotted)
        check("full-path 带 ./ 也读得到", d.size == 1, "实际 ${d.size}")

        // href 是百分号编码的中文文件名。
        val encoded = zip { z ->
            put(z, "META-INF/container.xml",
                "<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>")
            put(z, "content.opf",
                "<package><manifest><item id=\"c1\" href=\"%E7%AC%AC%E4%B8%80%E7%AB%A0.xhtml\"/></manifest>" +
                    "<spine><itemref idref=\"c1\"/></spine></package>")
            put(z, "第一章.xhtml", "<html><body><h1>第一章 码</h1><p>百分号路径正文。</p></body></html>")
        }
        val e = com.watchdl.core.LocalImport.epubChapters(encoded)
        check("href 百分号编码能解", e.size == 1 && e[0].body.contains("百分号路径正文"), "实际 ${e.size}")

        // 没有 OPF 的自制包：按扩展名扫 XHTML，不能直接抛。
        val noOpf = zip { z ->
            put(z, "a.xhtml", "<html><body><h1>第一章 无包</h1><p>没有 OPF 的正文。</p></body></html>")
        }
        val n = com.watchdl.core.LocalImport.epubChapters(noOpf)
        check("缺 OPF 也扫得出正文", n.size == 1, "实际 ${n.size}")

        // 章标题不能同时留在正文首行。
        val dup = zip { z ->
            put(z, "META-INF/container.xml",
                "<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>")
            put(z, "content.opf",
                "<package><manifest><item id=\"c1\" href=\"ch1.xhtml\"/></manifest>" +
                    "<spine><itemref idref=\"c1\"/></spine></package>")
            put(z, "ch1.xhtml",
                "<html><body><h1>第一章 重</h1><p>第一章 重</p>" +
                    "<p>真正的正文在这里，字数要够过十字线。</p></body></html>")
        }
        val dupCh = com.watchdl.core.LocalImport.epubChapters(dup)
        check("标题不在正文里重复", !dupCh[0].body.startsWith("第一章 重"), dupCh[0].body.take(20))
        check("正文还在", dupCh[0].body.contains("真正的正文"), dupCh[0].body.take(20))
    }

    println("[本地导入] 边界")
    run {
        // 无 BOM 的 UTF-16LE：只看替换符会判成 UTF-8，整本变带洞的乱码。
        // 「一」= U+4E00 低字节是 0x00、落在偶位；换行极少时靠数零字节会把 LE 判成 BE。
        // 样本取「每行 156 个一」，是 229664d 那版零字节判据唯一会翻车（给出 NNNNNNNN）
        // 的形态 —— 现在用来防那种判据回潮。
        val dash = "一".repeat(156) + "\n"
        val dec = com.watchdl.core.LocalImport.decodeText(dash.repeat(40).toByteArray(Charsets.UTF_16LE))
        check("全是「一」的 UTF-16LE 能认", dec.count { it == '一' } == 156 * 40,
            "${dec.take(8)} / 一=${dec.count { it == '一' }}")
        val decBe = com.watchdl.core.LocalImport.decodeText(dash.repeat(40).toByteArray(Charsets.UTF_16BE))
        check("全是「一」的 UTF-16BE 能认", decBe.count { it == '一' } == 156 * 40,
            "${decBe.take(8)} / 一=${decBe.count { it == '一' }}")

        // 纯 ASCII 前后夹一段 GBK 中文：只取开头会判成 UTF-8，整本中文变替换符。
        val ascii = ("Preface (c) 2024, all rights reserved.\n".repeat(300))
        val gbkMix = ascii.toByteArray() +
            "第一章 开始\n\n这是中文正文，用来验证取样覆盖中段与尾部。\n".repeat(200)
                .toByteArray(charset("GB18030"))
        val mixDec = com.watchdl.core.LocalImport.decodeText(gbkMix)
        check("GBK 前面一大段英文也能认", mixDec.contains("这是中文正文") && !mixDec.contains('\uFFFD'),
            mixDec.take(12))

        // UTF-16 编码的纯英文：两个可打印 ASCII 字节不能拼成「汉字」把判定带偏。
        val en = "The quick brown fox jumps over the lazy dog.\n".repeat(40)
        val enDec = com.watchdl.core.LocalImport.decodeText(en.toByteArray(Charsets.UTF_8))
        check("纯 ASCII 不被判成 UTF-16", enDec.startsWith("The quick brown fox"),
            enDec.take(12))
        // 不带空格的连续 ASCII 编码成 UTF-16BE：端序只能靠零字节奇偶位定。
        // 只用小写字母：含数字时旧判据反而能判对，这条就测不出缺陷。
        val noSpace = "abcdefghijklmnopqrstuvwxyz".repeat(40)
        val beDec = com.watchdl.core.LocalImport.decodeText(noSpace.toByteArray(Charsets.UTF_16BE))
        check("UTF-16BE 连续 ASCII 不判成 LE", beDec == noSpace, beDec.take(12))
        val leDec = com.watchdl.core.LocalImport.decodeText(noSpace.toByteArray(Charsets.UTF_16LE))
        check("UTF-16LE 连续 ASCII 不判成 BE", leDec == noSpace, leDec.take(12))

        // 语料里 30% 常用汉字的两个字节都落在可打印 ASCII 区间（来=eg、是=/f）。
        // 若按「双 ASCII 字节就当乱码」扣分，对话体密集的 UTF-16 中文会整本翻去 GB18030。
        val dense = "来人是谁？就是那个在大学里认识的女孩子。\n".repeat(60)
        val denseDec = com.watchdl.core.LocalImport.decodeText(dense.toByteArray(Charsets.UTF_16LE))
        check("双 ASCII 字节汉字不误判", denseDec.trimEnd('\n') == dense.trimEnd('\n'), denseDec.take(12))

        // 英文为主、只夹少量中文的 UTF-8：取样窗切在多字节字符中间也不能判成 GBK。
        val enBody = "The quick brown fox jumps over the lazy dog.\n".repeat(400)
        val sparse = enBody + "这里只有一个中文字。\n" + enBody
        val sparseDec = com.watchdl.core.LocalImport.decodeText(sparse.toByteArray(Charsets.UTF_8))
        check("英文夹少量中文仍是 UTF-8", sparseDec.contains("这里只有一个中文字"), sparseDec.take(12))

        // 分章：全角数字、各种分隔符、番外、Chapter 无空格。
        val heads = listOf(
            "第１２章 全角", "第一章【方括号】", "第一章（一）",
            "番外篇 婚礼", "番外一", "Chapter One", "Chapter12",
        )
        for (h in heads) {
            val txt = "$h\n\n正文内容。\n\n第二章 后\n\n后文。"
            val chs = com.watchdl.core.LocalImport.splitTxt(txt)
            check("认得出「$h」", chs.size == 2, "切成 ${chs.size} 章：${chs.map { it.title }}")
        }

        // 逗号不能当标题分隔符：这条是正文，不是章节标题。
        val comma = "第一章，他终于明白自己错了，可是已经晚了。\n\n后面还有正文。"
        val commaCh = com.watchdl.core.LocalImport.splitTxt(comma)
        // 只断言 size==1 不够：把整句当标题也还是 1 章，测不出缺陷。
        check("逗号开头的正文不当标题", commaCh.size == 1 && commaCh[0].title == "全书",
            commaCh.map { it.title }.toString())
        check("逗号那句仍在正文里", commaCh[0].body.contains("他终于明白"), commaCh[0].body.take(20))

        // 没有 spine 的包按文件名自然序，不能 1,10,11,2。
        val bosN = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bosN).use { z ->
            for (i in listOf(1, 2, 10, 11)) {
                z.putNextEntry(java.util.zip.ZipEntry("ch$i.xhtml"))
                z.write(("<html><body><h1>第${i}章</h1><p>这是第${i}章的正文内容，够十个字。</p></body></html>")
                    .toByteArray())
                z.closeEntry()
            }
        }
        val nat = com.watchdl.core.LocalImport.epubChapters(bosN.toByteArray())
        check("无 spine 按自然序", nat.map { it.title } == listOf("第1章", "第2章", "第10章", "第11章"),
            nat.map { it.title }.toString())

        // 20 位流水号不能溢出成 0；前导零与非数字混排也得稳住。
        val bigZip = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bigZip).use { z ->
            for (f in listOf("a2", "a99999999999999999999", "ch01", "ch1", "x0", "x00")) {
                z.putNextEntry(java.util.zip.ZipEntry("$f.xhtml"))
                z.write("<html><body><h1>$f 章</h1><p>正文内容够十个字才行吧。</p></body></html>".toByteArray())
                z.closeEntry()
            }
        }
        val bt = com.watchdl.core.LocalImport.epubChapters(bigZip.toByteArray()).map { it.title }
        check("20 位数字不溢出", bt.indexOf("a2 章") < bt.indexOf("a99999999999999999999 章"), bt.toString())
        check("前导零不并列丢章", bt.size == 6, bt.toString())

        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "local-edge-test")
        dir.deleteRecursively(); dir.mkdirs()
        val store = com.watchdl.core.JobStore(java.io.File(dir, "books"))
        val body = "第一章 甲\n\n甲正文。"
        val t = com.watchdl.core.LocalImport.importInto(store, "同名.txt", body.toByteArray())
        val p = com.watchdl.core.LocalImport.importInto(store, "同名.epub",
            epudBytes("第一章 甲\n\n这是一段足够长的甲正文内容，用来凑够十字。"))
        check("同名不同格式不撞库", t.bookId != p.bookId, "${t.bookId} / ${p.bookId}")
        check("空文件报错", runCatching {
            com.watchdl.core.LocalImport.importInto(store, "空.txt", ByteArray(0))
        }.isFailure)
        dir.deleteRecursively()
    }

    println("[本地导入] 落成作业")
    run {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "local-import-test")
        dir.deleteRecursively(); dir.mkdirs()
        val store = com.watchdl.core.JobStore(java.io.File(dir, "books"))
        val txt = "第一章 甲\n\n甲正文。\n\n第二章 乙\n\n乙正文。\n\n第三章 丙\n\n丙正文。"
        val res = com.watchdl.core.LocalImport.importInto(store, "夜读测试.txt", txt.toByteArray())
        check("bookId 带 local 前缀", com.watchdl.core.LocalImport.isLocal(res.bookId), res.bookId)
        check("bookId 不是链接", !res.bookId.startsWith("http"), res.bookId)
        check("书名取文件名", res.name == "夜读测试", res.name)
        check("章数对得上", res.chapters == 3, "${res.chapters}")
        val raw = store.readJob(res.bookId).orEmpty()
        check("job 有书名", TinyJson.path(raw, "name") == "夜读测试", raw.take(80))
        check("job 状态 DONE", TinyJson.path(raw, "state") == "DONE", TinyJson.path(raw, "state") ?: "null")
        check("书源标签写成本地", TinyJson.path(raw, "originName") == "本地")
        check("每章都是 DONE", raw.contains("\"status\":\"DONE\"") && !raw.contains("\"status\":\"PENDING\""))
        val job = com.watchdl.core.Downloader.restoreLight(store, res.bookId)
        check("书架能读出这本书", job != null && job.chapters.size == 3, "${job?.chapters?.size}")
        check("已下数等于总章数", job?.count(com.watchdl.core.ChapterStatus.DONE) == 3,
            "${job?.count(com.watchdl.core.ChapterStatus.DONE)}")
        check("正文按章落盘", store.readChapters(res.bookId).size == 3,
            "${store.readChapters(res.bookId).size}")
        // 换章走单章读法：扫到命中行就停，不再把整本扫成 Map。
        val oneId = job!!.chapters.first().itemId
        check("按章读法与整本读法一致",
            store.readChapter(res.bookId, oneId) == store.readChapters(res.bookId)[oneId])
        check("找不到的章给 null", store.readChapter(res.bookId, "没有这个 id") == null &&
            store.readChapter("不存在这本书", oneId) == null)
        check("章 id 不重复", job!!.chapters.map { it.itemId }.toSet().size == 3)
        // 睡眠不适用：restore 会按 300 字线判 DONE，本地书必须豁免，否则短章全变 FAILED。
        val full = com.watchdl.core.Downloader.restore(store, res.bookId)
        check("短章不被判失败", full?.count(com.watchdl.core.ChapterStatus.DONE) == 3,
            "${full?.count(com.watchdl.core.ChapterStatus.DONE)}")

        // 同名再导一次 = 覆盖同一本，不能多出一本、也不能留下旧正文。
        val again = com.watchdl.core.LocalImport.importInto(store, "夜读测试.txt",
            "第一章 甲\n\n新的甲正文。".toByteArray())
        check("重导入仍是同一本", again.bookId == res.bookId)
        check("重导入只剩一章", again.chapters == 1, "${again.chapters}")
        check("旧正文不残留", store.readChapters(res.bookId).size == 1,
            "${store.readChapters(res.bookId).size}")

        // 真机走的是「文件 → ZipFile 按需读 → 边解析边落盘」，这条路不能没人测。
        val epubFile = java.io.File(dir, "流式.epub")
        epubFile.writeBytes(epudBytes("第一章 甲\n\n这是一段足够长的甲正文内容，用来凑够十字。"))
        val streamed = com.watchdl.core.LocalImport.importInto(store, "流式.epub", epubFile)
        check("按文件导入 epub", streamed.chapters == 1 && streamed.format == "EPUB", "${streamed.chapters}")
        check("流式正文落盘", store.readChapters(streamed.bookId).size == 1,
            "${store.readChapters(streamed.bookId).size}")

        // 失败要回滚：半截 tmp 不能留下，旧正文也不能被换掉。
        val before = store.readChapters(streamed.bookId).size
        val badFile = java.io.File(dir, "坏包.epub")
        badFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3))
        val bad = runCatching { com.watchdl.core.LocalImport.importInto(store, "坏包.epub", badFile) }
        check("坏 epub 报错", bad.isFailure, "${bad.exceptionOrNull()}")
        check("坏 epub 不覆盖旧书", store.readChapters(streamed.bookId).size == before, "$before")

        // 本地书在 restore 里短路：状态只看 job.json，不再把 chapters.jsonl 拉进来逐行解析。
        // 把正文删掉仍应全是 DONE —— 否则「目录屏打开一本 50MB 的书」又要回到秒级卡顿。
        val done1 = com.watchdl.core.ChapterStatus.DONE
        store.chaptersFile(streamed.bookId).delete()
        val after = com.watchdl.core.Downloader.restore(store, streamed.bookId)
        check("本地书状态不看正文", after?.count(done1) == 1,
            "${after?.count(done1)}")
        check("目录屏读法（restoreLight）同样只认 job.json",
            com.watchdl.core.Downloader.restoreLight(store, streamed.bookId)?.count(done1) == 1)
        dir.deleteRecursively()
    }

    if (failures == 0) println("\n全部通过") else { println("\n失败 $failures 项"); kotlin.system.exitProcess(1) }
}

/** 造一个最小 epub：一章 XHTML。给边界测试用。 */
private fun epudBytes(text: String): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(bos).use { z ->
        fun put(name: String, body: String) {
            z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
        }
        put("META-INF/container.xml",
            "<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>")
        put("content.opf",
            "<package><manifest><item id=\"c1\" href=\"ch1.xhtml\"/></manifest>" +
                "<spine><itemref idref=\"c1\"/></spine></package>")
        put("ch1.xhtml", "<html><body>" + text.replace("\n", "<br/>") + "</body></html>")
    }
    return bos.toByteArray()
}

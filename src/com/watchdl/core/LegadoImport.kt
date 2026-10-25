package com.watchdl.core

/**
 * legado 书源导入（`shareBookSource.json` 那种格式）。
 *
 * 覆盖范围见笔记本页 `booksource-engine-specs`：只导入 Tier 0 能跑的部分，
 * 用到脚本的段**明确留空**而不是硬当选择器用，导入方应据此提示用户该源不可用。
 *
 * 与 legado 的差异（对着真实 shareBookSource.json 核对）：
 * - `searchUrl` 形如 `url,{"method":"POST","body":"…","headers":{…}}`，逗号后是 URL 选项。
 * - `ruleSearch` 的 `bookList` 是结果项选择器，`name`/`bookUrl` 相对于它。
 * - `ruleToc` 的 `chapterList` 同理，`chapterName`/`chapterUrl` 相对于它。
 * - `nextContentUrl` 是正文分页，已实现（27/54 条源在用）。
 * - `nextTocUrl` 是目录分页，已实现（16/54 条源在用）。
 * - `@css:` / `@XPath:` / `@json:` 前缀与 `##正则##`、`||`、`@put/@get` 都保留原样。
 */
object LegadoImport {

    /** 导入一份 shareBookSource.json，返回「源名 → 规则」与每条的问题说明。 */
    data class Imported(
        val name: String,
        val rule: BookSourceRule?,
        /** 无法转换的原因，null 表示干净导入。 */
        val issue: String?,
        /**
         * 源地址是 http://（M7）。
         *
         * 这里只知道**声明**是明文；实际能不能升级到 https 要等真发过请求
         * （见 [Http.cleartextHosts]）。界面把两者合起来才标「明文」。
         */
        val declaredHttp: Boolean = false,
    ) {
        val usable: Boolean get() = rule != null
    }

    fun importAll(json: String): List<Imported> {
        // S16：解析失败以前直接抛，被调用方 `getOrElse` 吞成空列表，
        // 界面只显示「共 0 条」。现在转成一条可展示的问题。
        val root = runCatching { JsonParser.parse(json) }.getOrElse {
            return listOf(Imported("（整个文件）", null,
                "不是合法 JSON：${it.message?.take(60) ?: it::class.simpleName}"))
        } as? List<*> ?: return listOf(Imported("（整个文件）", null, "顶层不是数组"))
        return root.mapIndexedNotNull { idx, item ->
            val m = item as? Map<*, *>
                ?: return@mapIndexedNotNull Imported("第 ${idx + 1} 条", null, "不是对象，已跳过")
            // 缺 bookSourceName 以前直接丢弃，一整个文件里丢几条用户无从得知。
            val name = m.getStr("bookSourceName")?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull Imported("第 ${idx + 1} 条", null, "缺 bookSourceName 字段")
            val e = m.getBool("enabled") ?: true
            if (!e) return@mapIndexedNotNull Imported(name, null, "源已禁用")
            runCatching { importOne(name, m) }
                // 带上异常类型：只有 message 时，NullPointerException 之类会显示成空串，
                // 排查时看不出是哪一步炸的。
                .getOrElse {
                    val kind = it::class.simpleName ?: "异常"
                    val msg = it.message?.take(60)?.takeIf { s -> s.isNotBlank() } ?: "无详细信息"
                    // 打完整堆栈：只看 message 时，正则错误只报
                    // "Syntax error"，看不出是哪条规则、哪个正则炸的。
                    // 走反射：纯 JVM 测试环境没有 android.util.Log，直接引用会 NoClassDefFoundError。
                    debugLog("导入书源失败：$name", it)
                    // 分清「我们的正则炸了」和「书源内容不支持」——
                    // PatternSyntaxException 只可能来自编译正则的代码，那一定是我们的 bug，
                    // 不该混进「源不可用」的统计里（之前 45 条全被误记成源的问题）。
                    val bucket = if (it is java.util.regex.PatternSyntaxException ||
                                     it.cause is java.util.regex.PatternSyntaxException)
                        "引擎正则错误（请反馈）"
                    else "解析异常"
                    Imported(name, null, "$bucket：$msg")
                }
        }
    }

    fun importOne(name: String, m: Map<*, *>): Imported {
        // 记录当前处理到哪个字段。异常时把字段名与原始规则一并报出 ——
        // 只看到 "PatternSyntaxException Syntax error" 时无从下手。
        val at = java.lang.ThreadLocal<String>()
        try {
            return importOneInner(name, m, at)
        } catch (e: Exception) {
            ImportTrace.record(e)
            // 字段名必须排在最前：界面上会截断，截掉后面也不能截掉它。
            throw IllegalStateException(
                "${at.get() ?: "?"} | ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private fun importOneInner(name: String, m: Map<*, *>, at: java.lang.ThreadLocal<String>): Imported {
        at.set("bookSourceUrl")
        // bookSourceUrl 可能带 `#♤分组名` 尾巴（legado 用它标分组，实测 780 条里有 23 条），
        // 拼相对 URL 时必须剥掉，否则会拼出带井号的非法地址。
        val host = (m.getStr("bookSourceUrl") ?: "").substringBefore('#')
        val searchUrlRaw = m.getStr("searchUrl") ?: ""
        if (searchUrlRaw.isBlank()) return Imported(name, null, "无 searchUrl，只能探索发现")

        val issues = mutableListOf<String>()
        at.set("searchUrl")
        // `@js:` 开头的 searchUrl 整段都是脚本，**不能走 parseUrlOptions**：
        // 它按 `,\s*[{[]` 找「URL,选项」的分界，而脚本里到处是 `,{`
        // （`baseUrl+\"/so/x.html,{'method':'GET'...}`），一切就把字符串截断在引号中间，
        // Rhino 报 `unterminated string literal`。XIU2 那批 22 条里有 6 条栽在这。
        val searchUrlIsPureJs = FieldRule.jsCodeOf(searchUrlRaw.trim()) != null
        val (searchUrlRaw2, searchOpts) = if (searchUrlIsPureJs) searchUrlRaw.trim() to UrlOptions()
                                           else parseUrlOptions(searchUrlRaw)
        // searchUrl 可能是 JS（实测 60 条里 12 条），在请求时求值（见 SearchRule.urlJs）。
        // 在导入期就拦掉等于放弃这些源。
        val searchUrlJs = FieldRule.jsCodeOf(searchUrlRaw2) != null
        // searchUrl 用脚本是**支持的**（存成 SearchRule.urlJs，请求期求值），
        // 不算问题、不报警。以前这里 issues += "searchUrl(脚本)"，
        // 让 10 条完全正常的源在界面上显得「部分字段用脚本」，是假警报。
        // 相对路径是合法的（如 `/modules/article/search.php`、`search/getX`），补成绝对地址。
        val searchUrl = if (searchUrlJs) ""
                        else if (searchUrlRaw2.startsWith("http")) searchUrlRaw2
                        else if (host.isNotBlank()) {
                            // 前导 `/` 直接接主机；否则当相对路径，中间补一个斜杠
                            host.trimEnd('/') + if (searchUrlRaw2.startsWith("/")) searchUrlRaw2
                                               else "/" + searchUrlRaw2
                        }
                        else searchUrlRaw2
        if (!searchUrlJs) {
            // 内嵌 `{{java.xxx}}` / `{{source.getKey()}}` 现在支持了
            // （Template.evalExpr 会交给 JsEngine），所以**不能一看到 `{{` 就拦**。
            // 实测书迷楼 / TW看書網 / 三叉小说 三源都是这种写法，
            // 展开后是合法 URL。真正要拦的只有「解码后仍然不是 URL」的。
            val raw = searchUrlRaw2.trim()
            if (raw.startsWith("<js>") || raw.startsWith("@js:") ||
                raw.startsWith("function") || raw.startsWith("("))
                return Imported(name, null, "搜索 URL 是代码而非地址")
            // 占位符（# / 空锚）：作者用来表示「本源不能搜索」。
            // 不拦的话会被拼成 `https://host/#`，看着像可用，实际永远搜不出东西。
            if (raw.startsWith("#") || raw == "/")
                return Imported(name, null, "无 searchUrl，只能探索发现")
            // 模板 URL 在导入期无法展开（要请求、要 cookie），所以只查形状：
            // 去掉 `{{...}}` 占位后，剩下的骨架里必须有个能当 URL 的东西。
            if (!searchUrlJs && !urlShaped(raw)) 
                return Imported(name, null, "搜索 URL 不是绝对地址")
        }

        val searchMap = m.getMap("ruleSearch")
        val tocMap = m.getMap("ruleToc")
        // 目录页地址（tocUrl）写在 ruleBookInfo 里，不在 ruleToc。
        val bookInfoMap = m.getMap("ruleBookInfo")
        val contentMap = m.getMap("ruleContent")

        at.set("ruleSearch.bookList")
        val listSel = searchMap.getStr("bookList")
        if (listSel.isNullOrBlank()) return Imported(name, null, "搜索列表选择器缺失")
        // `bookList` 也可以是 @js:（从结果里抠数组）。以前一律拒，
        // 等于放弃番茄系列这些用脚本解析 JSON 的源。
        if (FieldRule.jsCodeOf(listSel) == null && LegadoSelector.usesScript(listSel))
            return Imported(name, null, "搜索列表选择器用了非 @js 脚本")

        // 判断这个源是不是 JSON 接口：看 bookList 与字段选择器长什么样。
        // JSON 源里字段常写成裸键名（`data`、`title`），不带 `$.`，
        // 若按 CSS 解析就会去 HTML 里找一个叫 data 的标签，永远取不到。
        val jsonSourced = looksLikeJson(listSel, searchMap)

        // 字段相对于结果项求值，不再与 bookList 拼成绝对链 ——
        // legado 的语义就是「先取列表，再在每项里取字段」。
        fun field(key: String): FieldRule {
            at.set("ruleSearch.$key")
            val raw = searchMap.getStr(key) ?: return FieldRule()
            // 脚本字段直接交给 JsEngine 跑（保守沙箱 + 2 秒超时）。
            // 以前这里降级成 UNSUPPORTED，等于放弃所有用脚本的源。
            if (FieldRule.jsCodeOf(raw) != null) return FieldRule(raw)
            // 「选择器 + 脚本尾巴」也算能跑：选择器取值，脚本当后处理（见 FieldRule.evalOne）。
            val rawCss = FieldRule.selectorPartOf(raw)
            if (LegadoSelector.usesScript(rawCss)) { issues += "$key(非@js脚本)"; return FieldRule(FieldRule.UNSUPPORTED) }
            val cleaned = stripLegadoNoise(rawCss)
            // `bookUrl` 可能是「整串 URL 模板」而不是选择器，例如
            // `https://host/cx/bookDetailYS?bookid={{$.id}}`。
            // 这种要当模板用，不能加 $. 前缀（踩过：中文书城因此全查不到）。
            if (cleaned.contains("{{")) return FieldRule(TEMPLATE_MARK + cleaned)
            // 也可能是「纯正则提取」：`##<a\s*href="([^"]+)"##$1###`
            // 没有 CSS 选择器，直接在整段 HTML 上跑正则抠链接（未来天王等）。
            if (cleaned.startsWith("##")) return FieldRule(REGEX_MARK + cleaned)
            return if (jsonSourced) FieldRule("@json:" + normaliseJsonPath(cleaned))
                   // 必须先拆 `||` 再逐条 toCss。
                   //
                   // toCss 用 lastIndexOf('@') 判后缀，作用在整条链上时只有最后一段
                   // 的 @ 被认作取值后缀，前面的全被当成 legado 路径分隔符变成空格：
                   // `h2 a@text||h3 a@text||h4 a@text` → `h2 a text||h3 a text||h4 a@text`
                   // 实测这会让 52书库 这类源的书名全部丢空。
                   else FieldRule.parse(LegadoSelector.normaliseChain(cleaned))
        }

        at.set("ruleToc.chapterList")
        val tocList = tocMap.getStr("chapterList")
        // 目录列表允许脚本：很多源用 @js: 从页面里抠章节数组
        if (tocList.isNullOrBlank()) return Imported(name, null, "目录列表选择器缺失")
        // 「CSS 在前、脚本在后」的写法（黑粉小说等）只取 CSS 部分，别整条丢掉。
        // `-` / `+` 前缀（legado 的 chapterList 语法）：`-` 表示源目录是倒序（新章在前），
        // 需要反转；`+` 只剥离不改顺序。两个可连续出现（`-+`）。
        // 我们的 ChapterOrder 按章节号重排，效果已等价，但仍要剥掉前缀——
        // 否则 `-.chapters p` 会被当成选择器 `.chapters p` 前面的 `-` 而解析失败。
        val tocListStripped = tocList.trimStart().removePrefix("-").removePrefix("+").trimStart()
        val tocListCss = FieldRule.cssBeforeScript(tocListStripped) ?: tocListStripped
        if (FieldRule.jsCodeOf(tocListCss) == null && LegadoSelector.usesScript(tocListCss))
            return Imported(name, null, "目录列表选择器用了非 @js 脚本")
        // 整条就是脚本的 chapterList（5 条源）：可能是「返回对象数组」的写法。
        // 只看是否纯脚本，不做语义判断 —— 是否真的是对象数组，到运行期由脚本结果决定。
        val tocSourceList = tocListStripped
            .takeIf { FieldRule.jsCodeOf(it) != null && FieldRule.cssBeforeScript(it) == null }
            ?.let { FieldRule(it) }

        fun tocField(key: String): FieldRule {
            at.set("ruleToc.$key")
            val raw = tocMap.getStr(key) ?: return FieldRule()
            if (FieldRule.jsCodeOf(raw) != null) return FieldRule(raw)
            val rawCss = FieldRule.selectorPartOf(raw)
            if (LegadoSelector.usesScript(rawCss)) { issues += key; return FieldRule(FieldRule.UNSUPPORTED) }
            // chapterList 本身是**纯脚本**时，它返回的是对象数组（见 SourceEngine.tocItemScope），
            // 字段规则（`name` / `url` / `$.n`）是在**每个对象内**取值，不能和脚本拼成
            // 一条选择器。以前无条件 joinTocField，把整段脚本 + `; name` 粘成
            // chapterName，脚本再求值一次得空串 —— 实测「全本小说」469 章只剩 1 章。
            if (tocSourceList != null) return FieldRule(raw)
            return joinTocField(tocListCss, rawCss, key)
        }

        at.set("ruleContent.content")
        val contentRaw = contentMap.getStr("content")
        val contentRule = when {
            contentRaw.isNullOrBlank() -> { issues += "content"; FieldRule() }
            // 正文脚本也允许（实测 60 条里 26 条用脚本）
            FieldRule.jsCodeOf(contentRaw) != null -> FieldRule(contentRaw)
            LegadoSelector.usesScript(contentRaw) -> { issues += "content(非@js脚本)"; FieldRule() }
            else -> FieldRule.parse(stripLegadoNoise(contentRaw))
        }

        // header 字段是必需的：不带 App 专用头，很多接口直接返回空数据。
        // 请求头在导入期固定，只支持不依赖网络的脚本。
        at.set("header")
        val siteHeader = parseHeader(m.getStr("header"))
        if (siteHeader == null) return Imported(name, null, "请求头用脚本（HMac/random 等）")
        // URL 选项里的 headers 优先（更具体），站点 header 兜底
        val mergedHeaders = siteHeader + searchOpts.headers

        val rule = BookSourceRule(
            name = name,
            host = host,
            search = SearchRule(
                url = searchUrl,
                urlJs = if (searchUrlJs) FieldRule.jsCodeOf(searchUrlRaw2) else null,
                method = searchOpts.method,
                body = searchOpts.body,
                headers = mergedHeaders,
                list = if (FieldRule.jsCodeOf(listSel) != null) FieldRule(listSel)
                       else if (jsonSourced) FieldRule("@json:" + normaliseJsonPath(stripLegadoNoise(listSel)))
                       else FieldRule.parse(LegadoSelector.toCss(stripLegadoNoise(listSel))),
                isJson = jsonSourced,
                name = field("name"),
                link = field("bookUrl"),
                author = field("author"),
                intro = field("intro"),
            ),
            toc = TocRule(
                url = "",   // 目录就在书籍页上，由 tocUrl 或书链接决定
                name = tocField("chapterName"),
                link = tocField("chapterUrl"),
                nextTocUrl = tocMap.getStr("nextTocUrl")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { nc ->
                        when {
                            FieldRule.jsCodeOf(nc) != null -> FieldRule(nc)
                            LegadoSelector.usesScript(nc) -> null
                            nc.contains("{{") -> FieldRule(TEMPLATE_MARK + nc)
                            else -> FieldRule.parse(LegadoSelector.toCss(stripLegadoNoise(nc)))
                        }
                    },
                isVolume = tocMap.getStr("isVolume")?.takeIf { it.isNotBlank() }?.let { tocField("isVolume") },
                // 目录页地址写在 ruleBookInfo 里（87/175 条源在用），不在 ruleToc。
                sourceList = tocSourceList,
                // 书页收窄（legado 的 ruleBookInfo.init）。与 tocUrl 同源，一起导入。
                initRule = bookInfoMap.getStr("init")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ini ->
                        when {
                            FieldRule.jsCodeOf(ini) != null -> FieldRule(ini)
                            LegadoSelector.usesScript(ini) -> null
                            else -> FieldRule.parse(LegadoSelector.toCss(stripLegadoNoise(ini)))
                        }
                    },
                tocUrlRule = bookInfoMap.getStr("tocUrl")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { tu ->
                        when {
                            FieldRule.jsCodeOf(tu) != null -> FieldRule(tu)
                            LegadoSelector.usesScript(tu) -> null
                            tu.contains("{{") -> FieldRule(TEMPLATE_MARK + tu)
                            else -> FieldRule.parse(LegadoSelector.toCss(stripLegadoNoise(tu)))
                        }
                    },
            ),
            content = ContentRule(
                url = "",
                content = contentRule,
                replaceRegex = contentMap.getStr("replaceRegex")?.takeIf { it.isNotBlank() },
                replaceFirst = contentMap["replaceFirst"] == true,
                // 分页链接：脚本、模板、选择器三种写法都有，用 field 同一套逻辑。
                nextContentUrl = contentMap.getStr("nextContentUrl")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { nc ->
                        when {
                            FieldRule.jsCodeOf(nc) != null -> FieldRule(nc)
                            LegadoSelector.usesScript(nc) -> null   // 非 @js 脚本，不做
                            nc.contains("{{") -> FieldRule(TEMPLATE_MARK + nc)
                            nc.startsWith("##") -> FieldRule(REGEX_MARK + nc)
                            else -> FieldRule.parse(LegadoSelector.toCss(stripLegadoNoise(nc)))
                        }
                    },
            ),
            jsLib = m.getStr("jsLib"),
        )
        // 只有「真的有字段被降级/丢弃」才报警。
        // 文案要说清是哪个字段怎么降级的 —— 统一写「部分字段用脚本」会误导：
        // 支持的脚本（如 searchUrl）与不支持的脚本是两回事。
        val note = issues.takeIf { it.isNotEmpty() }?.joinToString("、")?.let { "以下字段未导入：$it" }
        return Imported(name, rule, note, declaredHttp = host.startsWith("http://"))
    }

    /**
     * 模板 URL 的形状检查（导入期无法真展开）。
     *
     * 去掉 `{{...}}` 占位后，剩下的骨架里只要有 `http`、或一个像域名的片段、
     * 或一个绝对路径（`/xxx`），就当它展成 URL 后能成立。
     * 检查的是「不可能成为 URL」而不是「一定是 URL」—— 宁可放过，不可错杀。
     */
    private fun urlShaped(raw: String): Boolean {
        if (raw.startsWith("http")) return true
        // 剥掉所有 {{...}} 占位，看骨架
        val skeleton = raw.replace(Regex("\\{\\{[^}]*\\}\\}"), "").trim()
        // 整条都是 JS 算出来的（三叉小说）：导入期没法判。
        // 宿主已知，放行交给请求期 —— 错了只是搜索失败，不影响其它源。
        if (skeleton.isEmpty()) return true
        if (skeleton.contains("http")) return true
        // 骨架里还有域名样式的片段（含点、不含空格）
        if (skeleton.split('/', '?', '&').any { it.contains('.') && !it.contains(' ') }) return true
        // 相对路径（带或不带前导斜杠）＋主机已知，也算成立
        return skeleton.startsWith("/") || skeleton.isNotEmpty() && !skeleton.contains(' ')
    }

    /** legado 的 URL 选项。用具体类型，避免调用处到处强转。 */
    data class UrlOptions(
        val method: String = "GET",
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    /**
     * 解析 legado 的 URL 选项：`url,{"method":"POST","body":"…","headers":{…}}`。
     * 逗号在第一个 `{` 之前才是分隔符（URL 里也可能有逗号）。
     */
    fun parseUrlOptions(raw: String): Pair<String, UrlOptions> {
        val braceMatch = Regex(",\\s*[{\\[]").find(raw)
        if (braceMatch == null) return raw.trim() to UrlOptions()
        val brace = braceMatch.range.first
        val url = raw.substring(0, brace).trim()
        val jsonPart = raw.substring(raw.indexOf(if (raw[braceMatch.range.last] == '[') '[' else '{', brace))
        // legado 允许 Python 风格的单引号字典（实测趣悦小说/ vivo 电子书等源这么写），
        // 标准 JSON 解析器会直接失败，导致 method 被当成空字符串。
        val normalised = singleToDoubleQuotes(jsonPart)
        val opts = runCatching { JsonParser.parse(normalised) as? Map<*, *> }.getOrNull()
            ?: return url to UrlOptions()
        val headers = (opts["headers"] as? Map<*, *>)
            ?.entries?.associate { (k, v) -> unquote(k.toString()) to unquote(v.toString()) }
            ?: emptyMap()
        return url to UrlOptions(
            method = unquote(opts["method"]?.toString() ?: "GET").uppercase().ifBlank { "GET" },
            body = opts["body"]?.toString()?.let { unquote(it) },
            headers = headers,
        )
    }

    /** 字段值是 URL 模板而不是选择器时的标记。 */
    const val TEMPLATE_MARK = "\u0000tpl:"

    /** 字段值是纯正则提取（整段文本上跑 `##正则##替换`）时的标记。 */
    const val REGEX_MARK = "\u0000re:"

    /**
     * 判断是不是 JSON 接口书源。
     *
     * 判据：`bookList` 用了 JSONPath（`$` 开头、或本身就是裸键名而非 CSS 结构），
     * 且响应不是 HTML。这类源的字段选择器普遍写成裸键名，必须按 JSONPath 处理。
     */
    private fun looksLikeJson(listSel: String, searchMap: Map<*, *>): Boolean {
        val l = listSel.trim()
        // JS 列表：脚本自己解析响应，字段也多半是脚本或裸键名，当 JSON 源对待。
        if (FieldRule.jsCodeOf(l) != null) return true
        if (l.startsWith("$")) return true
        if (l.contains("[*]") || l.contains("..")) return true
        // 裸键名（不含 CSS 结构字符）当 JSONPath
        val cssish = l.contains('.') || l.contains('#') || l.contains(' ') || l.contains('>')
        if (!cssish && l.matches(Regex("[A-Za-z_][\\w-]*"))) return true
        // 字段里出现 $. 也说明是 JSON 源
        return searchMap.values.any { it is String && (it.startsWith("$.") || it.startsWith("$..")) }
    }

    /** 裸键名补上 `$.` 前缀，让 JSONPath 解析器认得。 */
    private fun normaliseJsonPath(s: String): String {
        val t = s.trim().removePrefix("json:")
        return when {
            t.startsWith("$") -> t
            t.startsWith(".") -> "$" + t
            t.isEmpty() -> t
            else -> "$." + t
        }
    }

    /**
     * 解析 legado 的 `header` 字段。它是一个 JSON 字符串（或 JS）。
     *
     * 这是**必需的**：实测 780 条里 189 条带 header，我们那批干净源里有 148 条。
     * 很多站的接口只认安卓客户端 UA 或 App 专用头，不带就返回空数据。
     *
     * @return 头映射；脚本求值失败时返回 null，由调用方标记为不支持
     */
    fun parseHeader(raw: String?): Map<String, String>? {
        if (raw.isNullOrBlank()) return emptyMap()
        val t = raw.trim()
        if (t == "{}" || t == "null") return emptyMap()
        // `@js:` 头：实测 5 条源这么写（UA 轮换、时间戳签名）。
        // 这些脚本只产生静态请求头，不依赖请求上下文，所以**导入期就能跑**。
        // 以前一律拒，等于丢掉这 5 条源。
        val js = FieldRule.jsCodeOf(t)
        if (js != null) {
            // 请求期不会重跑头脚本，联网调用必须报错，不能静默生成空头。
            val offlineJs = """
                java.ajax = java.get = java.post = function() {
                    throw new Error("Network-dependent headers are unsupported");
                };
            """.trimIndent() + "\n" + js
            val out = runCatching { JsEngine.eval(offlineJs, JsEngine.Env(offline = true)) }.getOrNull()
                ?: return null          // 脚本炸了才真的当「本引擎不做」
            return parseHeaderJson(out)
        }
        if (LegadoSelector.usesScript(t)) return null      // 非 @js 脚本，本引擎不做
        return parseHeaderJson(t)
    }

    /** 把 JSON 文本解成请求头表。`{}` / 空 / 非对象一律当空表。 */
    private fun parseHeaderJson(text: String): Map<String, String> {
        val t = text.trim()
        if (t.isEmpty() || t == "{}" || t == "null") return emptyMap()
        val normalised = if (t.startsWith("{")) singleToDoubleQuotes(t) else return emptyMap()
        val parsed = runCatching { JsonParser.parse(normalised) as? Map<*, *> }.getOrNull()
            ?: return emptyMap()
        return parsed.entries
            .filter { it.key.toString().isNotBlank() }
            .associate { unquote(it.key.toString()) to unquote(it.value?.toString() ?: "") }
            .filterValues { it.isNotBlank() }
    }

    /** 去掉包裹的引号（单引号或双引号）。 */
    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t[0] == '\'' && t.last() == '\'') || (t[0] == '"' && t.last() == '"'))) {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    /**
     * 把 Python 风格单引号字典转成合法 JSON：`{'a': 'b'}` → `{"a": "b"}`。
     *
     * 只把「作为键或值的定界单引号」换成双引号，值里面原有的单引号与双引号保持不动
     * （body 里常见转义过的 JSON 字符串）。
     */
    private fun singleToDoubleQuotes(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> {
                    // 双引号串整体拷贝
                    sb.append(c); i++
                    while (i < s.length) {
                        val d = s[i]
                        if (d == '\\' && i + 1 < s.length) { sb.append(d).append(s[i + 1]); i += 2; continue }
                        sb.append(d); i++
                        if (d == '"') break
                    }
                }
                c == '\'' -> {
                    // 单引号串 → 双引号串，内部的双引号需要转义
                    sb.append('"'); i++
                    while (i < s.length) {
                        val d = s[i]
                        if (d == '\\' && i + 1 < s.length) { sb.append(d).append(s[i + 1]); i += 2; continue }
                        if (d == '\'') { sb.append('"'); i++; break }
                        if (d == '"') sb.append("\\\"") else sb.append(d)
                        i++
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * 把目录列表选择器与目录字段选择器拼起来。
     *
     * legado 的 `chapterName` / `chapterUrl` 有一批**内置关键字**，不是 CSS：
     * `text`（取节点文本）、`href` / `title` / `id`（取同名属性）。
     * 实测 54 条源里 `text` 出现 23 次、`href` 24 次，是主流写法。
     */
    private fun joinTocField(list: String, field: String, key: String): FieldRule {
        // 先拆出 `##正则##替换` 尾巴：`chapterName: text##正文卷.|正文.` 里
        // `text` 是取值关键字，后半段是过滤正则。
        // 以前整串拼成选择器，`text` 被当成标签名，结果取不到（玄幻阁吧就是这样）。
        val raw = field.trim()
        val hash = raw.indexOf("##")
        val head = if (hash >= 0) raw.substring(0, hash).trim() else raw
        val tail = if (hash >= 0) raw.substring(hash) else ""

        val f = head
        // 内置关键字：直接当成对列表项的取值后缀
        val suffix = when {
            f.isEmpty() && tail.isEmpty() -> return FieldRule()
            f.isEmpty() && tail.isNotEmpty() -> "@html"      // 只给正则时，对整项跑
            f == "text" -> "@text"
            f == "href" || f == "title" || f == "id" || f == "alt" || f == "src" -> "@$f"
            f == "html" -> "@html"
            else -> null
        }
        val listCss = LegadoSelector.toCss(list).trim()
        if (suffix != null) {
            if (listCss.isBlank()) return FieldRule()
            return FieldRule("$listCss$suffix", tail.ifEmpty { null })
        }
        // 其余按普通字段处理（可能自带 @text / @href）
        val joined = joinField(list, head)
        return if (tail.isNotEmpty()) joined.copy(replace = tail) else joined
    }

    /**
     * 把结果项选择器与字段选择器拼起来。
     *
     * legado 的字段选择器是相对于结果项的。本引擎的选择器是绝对的，所以拼成后代链。
     * `@text` 之类的后缀要保留。取链接的字段本身是 `a@href`，直接拼。
     */
    private fun joinField(list: String, field: String): FieldRule {
        val clean = stripLegadoNoise(field)
        if (clean.isBlank()) return FieldRule()
        // 列表选择器和字段选择器都可能有 `||` 备选链。两边要逐项组合，
        // 不能直接拼：`.a||.b` 拼 `.c` 会变成一条怪选择器，反而匹配上整篇文档。
        val lists = LegadoSelector.alternatives(list).map { LegadoSelector.toCss(it).trim() }
        // 字段是纯取值后缀（`@text` / `@href`）时不能过 toCss：
        // toCss 只认 `at > 0` 的后缀，单独一个 `@text` 会被当成路径段，
        // `@` 在 split('@') 里被丢掉，变成裸 `text`（就成了标签名）。
        val fieldIsSuffix = clean.trim().startsWith("@")
        val fields = LegadoSelector.alternatives(clean).map {
            if (fieldIsSuffix) it.trim() else LegadoSelector.toCss(it)
        }
        val combos = ArrayList<String>(lists.size * fields.size)
        for (l in lists) for (f in fields) {
            // 后缀要**直接接**在列表项后面，中间不留空格：
            // `#allchapter dd a[title] @text` 留了空格就成了「后代替 `text` 元素」，
            // 匹配恒为 0。实测错层小说网这类源全部 0 章。
            combos.add(
                when {
                    l.isBlank() -> f
                    fieldIsSuffix -> "$l$f"
                    f.isBlank() -> l
                    else -> "$l $f"
                }.trim()
            )
        }
        return FieldRule.parse(combos.joinToString("||"))
    }

    /** 去掉 legado 里本引擎不支持的位置标记等噪声。 */
    internal fun stripNoiseForTest(s: String): String = stripLegadoNoise(s)

    private fun stripLegadoNoise(s: String): String =
        s.replace(Regex("@-\\d+(?=@|$)"), "")          // 倒序标记
            // 尾部 URL 选项（webView 等）。
            // `}` 必须跟 `{` 一样转义：Android 的 ICU 后端要求成对，
            // 只转义 `{` 会在设备上抛 PatternSyntaxException（JVM 不抛，踩过）。
            .replace(Regex("##[^#]*,\\s*\\{[^}]*\\}\\s*$"), "##")
            .replace(Regex("^\\.?-?\\d+$"), "")          // 纯位置
            .trim()
}

/** 惰性判断布尔值。 */
fun Map<*, *>.getBool(key: String): Boolean? = when (val v = get(key)) {
    is Boolean -> v
    is String -> v.equals("true", true)
    else -> null
}
/**
 * 打一条 error 日志，Android 上没有就静默跳过。
 *
 * 直接用 `android.util.Log` 会让纯 JVM 测试在类加载时抛 NoClassDefFoundError ——
 * 而导入失败本来就是「测试里最容易走到」的路径。
 */
private fun debugLog(msg: String, t: Throwable) {
    runCatching {
        val log = Class.forName("android.util.Log")
        log.getMethod("e", String::class.java, String::class.java, Throwable::class.java)
            .invoke(null, "WatchDL", msg, t)
    }
}

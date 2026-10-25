package com.watchdl.core

/**
 * so-novel 规则导入。把 `~/novel/sonovel/rules/main.json` 那种 JSON 转成本工程的
 * [BookSourceRule]。
 *
 * 两边的差异（2026-10-15 对着真实 main.json 核对）：
 * - so-novel 用 `result` 给出结果项选择器，书名/作者是**相对于结果项**的；
 *   我们的 [FieldRule] 不支持相对定位，导入时拼成 `.result-bookName` 的绝对链。
 * - 搜索/目录都是 POST + `%s` 占位，转成 `{{key}}` / `{{page}}`。
 * - `filterTxt` 是「正则1|正则2」的过滤器，转成 `##正则##`；多条件时只保留第一条。
 * - `paragraphTag` / `filterTag` / `nextPage` / `pagination` 这一轮不导入，见文末说明。
 *
 * JSON 解析用手头的 [JsonParser]，不引第三方。
 */
object SonovelImport {

    /**
     * 导入一份规则文件里的所有书源。
     *
     * @param json 规则文件全文
     * @return 每项是「书源名 → 规则」，解析失败的源被跳过
     */
    fun importAll(json: String): List<Pair<String, BookSourceRule>> =
        importAllChecked(json).mapNotNull { (name, rule, _) -> rule?.let { name to it } }

    /**
     * 带问题说明的导入。
     *
     * 以前 `importAll` 静默 `mapNotNull` 掉三种情况：不是 map、没名字、`importOne` 抛异常。
     * 用户看到一个「共 12 条 / 可用 9 条」的计数，却不知道那 3 条去哪了。
     * 现在每条都给一句原因，界面按 issue 标 ⚠ 并展示。
     */
    fun importAllChecked(json: String): List<Triple<String, BookSourceRule?, String?>> {
        val root = runCatching { JsonParser.parse(json) }.getOrNull() as? List<*> ?: return emptyList()
        return root.mapIndexedNotNull { idx, item ->
            val m = item as? Map<*, *>
                ?: return@mapIndexedNotNull Triple("第 ${idx + 1} 条", null, "不是对象，已跳过")
            val name = m.getStr("name")?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull Triple("第 ${idx + 1} 条", null, "缺 name 字段")
            val r = runCatching { importOne(m) }
                .getOrElse { return@mapIndexedNotNull Triple(name, null, "转换失败：${it.message ?: it::class.simpleName}") }
            // 转换不抛异常，但关键字段空着，导入进来也是个搜不出东西的空壳。
            val missing = buildList {
                if (r.host.isBlank()) add("url")
                if (r.search.url.isEmpty() && r.search.name.selector.isEmpty()) add("search")
                if (r.toc.link.selector.isEmpty()) add("toc.item")
            }
            Triple(name, r, if (missing.isEmpty()) null else "缺字段：${missing.joinToString("、")}")
        }
    }

    fun importOne(m: Map<*, *>): BookSourceRule {
        val host = m.getStr("url") ?: ""
        val searchMap = m.getMap("search")
        val tocMap = m.getMap("toc")
        val chapterMap = m.getMap("chapter")

        val searchResult = searchMap.getStr("result")?.takeIf { it.isNotBlank() }
        val tocItem = tocMap.getStr("item")?.takeIf { it.isNotBlank() }

        val search = SearchRule(
            url = toTemplate(searchMap.getStr("url") ?: "", searchMap.getStr("method")),
            method = (searchMap.getStr("method") ?: "get").uppercase(),
            body = searchMap.getStr("data")?.let { toBodyTemplate(it) },
            headers = cookieHeaders(searchMap.getStr("cookies")),
            // 书名等字段是相对 result 的，拼成绝对后代选择器
            name = join(searchResult, searchMap.getStr("bookName")),
            link = join(searchResult, searchMap.getStr("bookName"), attr = true),
            author = join(searchResult, searchMap.getStr("author")),
            intro = join(searchResult, searchMap.getStr("intro")),
        )

        val toc = TocRule(
            // so-novel 的 `book.url` 不是要请求的地址，而是从搜索链接里提取书 id 的
            // 正则模板（如 `http://site/book/(.*?)/`）。目录页就是搜索得到的书链接本身，
            // 所以这里留空，由 [SourceEngine.toc] 直接用传入的书链接。
            url = "",
            method = (tocMap.getStr("method") ?: "get").uppercase(),
            name = FieldRule.parse(tocItem ?: ""),
            link = FieldRule.parse(tocLinkRule(tocItem)),
        )

        val content = ContentRule(
            url = "",
            content = if (usesScript(chapterMap.getStr("content"))) FieldRule()
                      else FieldRule(
                          selector = chapterMap.getStr("content")?.let { "@css:$it" } ?: "",
                          replace = filterToReplace(chapterMap.getStr("filterTxt")),
                      ),
        )

        return BookSourceRule(name = m.getStr("name") ?: "", host = host, search = search, toc = toc, content = content)
    }

    /** `{searchkey: %s}` → body 模板，`%s` 换成 `{{key}}`。 */
    private fun toBodyTemplate(data: String): String =
        data.replace("%s", "{{key}}")
            .replace("q={{page}}", "q={{key}}")   // 少数源把页码写成 %s 的重复，保守处理

    /** so-novel 的 URL 模板是纯 URL，`{{}}` 由调用方补；这里只规范 `%s`。 */
    private fun toTemplate(url: String, method: String?): String =
        url.replace("%s", "{{key}}")

    private fun cookieHeaders(cookies: String?): Map<String, String> =
        if (cookies.isNullOrBlank()) emptyMap() else mapOf("Cookie" to cookies)

    /** 目录链接规则：章节标题元素本身就是 `a`，链接在同一元素上。 */
    private fun tocLinkRule(item: String?): String {
        if (item.isNullOrBlank() || usesScript(item)) return ""
        return "$item@href"
    }

    /**
     * 规则里用了 JavaScript 时返回 true。Tier 0 不执行脚本，遇到就明确跳过，
     * 不能当成选择器静默出错（实测 12 条 so-novel 源里有 5 条带 `@js:`）。
     */
    fun usesScript(raw: String?): Boolean {
        val s = raw ?: return false
        return s.contains("@js:") || s.contains("<js>") || s.contains("java.") || s.contains("JSON.parse")
    }

    /**
     * 把结果项选择器与字段选择器拼成一条绝对选择器。
     *
     * 取链接时用属性方式，取文本时用文本方式。so-novel 的 `bookName` 本身就是 `a` 元素，
     * 链接在同一元素上，所以直接标 `@href`。
     */
    private fun join(result: String?, field: String?, attr: Boolean = false): FieldRule {
        if (field.isNullOrBlank()) return FieldRule()
        // 带脚本的字段不导入：Tier 0 不跑 JS，硬当成选择器只会默默取错内容。
        // 导入方通过 [SonovelImport.usesScript] 检测并提示用户。
        if (usesScript(field)) return FieldRule()
        val f = field.trim()
        val suffix = if (attr) "@href" else "@text"
        return if (result.isNullOrBlank()) FieldRule("@css:$f$suffix")
               else FieldRule("@css:${result.trim()} ${f.trim()}$suffix")
    }

    /**
     * `filterTxt` 是「正则1|正则2|…」的广告过滤表。我们的规则只支持一条替换，
     * 取第一条，其余交给导出时的清洗。
     */
    private fun filterToReplace(filterTxt: String?): String? {
        if (filterTxt.isNullOrBlank()) return null
        val first = filterTxt.split('|').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "##$first##"
    }
}

/**
 * 极简 JSON 解析器。so-novel 的规则文件带注释与尾逗号（JSON5 风味），
 * 所以解析前先做清洗。
 *
 * 只支持对象、数组、字符串、数字、布尔、null —— 规则文件够用。
 */
object JsonParser {

    /**
     * 输入上限 8 MB。
     *
     * S15：几 MB 输入会同时存在原文、[clean] 输出、去尾逗号输出三份字符串，
     * 手表上再大就真要 OOM 了。真实书源文件是几百 KB 量级，8 MB 足够宽松。
     */
    const val MAX_INPUT = 8 * 1024 * 1024

    fun parse(text: String): Any? {
        if (text.length > MAX_INPUT) {
            throw IllegalArgumentException("文件过大（${text.length / 1024 / 1024} MB，上限 8 MB）")
        }
        return Parser(clean(text)).parseValue()
    }

    /**
     * 去行注释、块注释、尾逗号。
     *
     * 不能用正则做：书源里到处是 `http://`，简单的 `//` 正则会把 URL 后面的内容
     * 当注释砍掉，破坏 JSON 结构（实测把 414 KB 的源文件砍得括号不配平）。
     * 必须扫描时跟踪「是否在字符串里」。
     */
    fun clean(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                out.append(c)
                when {
                    c == '\\' && i + 1 < s.length -> { out.append(s[i + 1]); i += 2; continue }
                    c == '"' -> inStr = false
                }
                i++
                continue
            }
            when {
                c == '"' -> { inStr = true; out.append(c); i++ }
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                    while (i < s.length && s[i] != '\n') i++
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        // 尾逗号：需要再扫一遍（上一步已无注释）
        return removeTrailingCommas(out.toString())
    }

    /** 去掉 `,]` `,}` 里的逗号，同样要避开字符串。 */
    private fun removeTrailingCommas(s: String): String {
        val out = StringBuilder(s.length)
        var inStr = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                out.append(c)
                when {
                    c == '\\' && i + 1 < s.length -> { out.append(s[i + 1]); i += 2; continue }
                    c == '"' -> inStr = false
                }
                i++
                continue
            }
            if (c == '"') { inStr = true; out.append(c); i++; continue }
            if (c == ',') {
                var j = i + 1
                while (j < s.length && s[j].isWhitespace()) j++
                if (j < s.length && (s[j] == ']' || s[j] == '}')) { i++; continue }   // 丢掉这个逗号
            }
            out.append(c); i++
        }
        return out.toString()
    }

    /** 嵌套深度上限。JSON 本身很少超过 20 层，200 足够宽松又能挡住恶意的深嵌套。 */
    private const val MAX_DEPTH = 200

    private class Parser(private val s: String) {
        private var i = 0
        private var depth = 0

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) return null
            // S15：深度不设限时，5 万层嵌套抛 StackOverflowError，
            // 而 catch (e: Exception) 接不住 Error，用户看到的是「0 条」。
            if (depth > MAX_DEPTH) throw IllegalArgumentException("嵌套过深（上限 $MAX_DEPTH 层）")
            depth++
            try {
                return when (s[i]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't' -> { i += 4; true }
                    'f' -> { i += 5; false }
                    'n' -> { i += 4; null }
                    else -> parseNumber()
                }
            } finally {
                depth--
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++   // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (i < s.length) {
                skipWs()
                if (i >= s.length) break
                if (s[i] == '}') { i++; break }
                // 键可能有引号，也可能没有（JSON5）
                val key = if (s[i] == '"') parseString() else readBareKey()
                if (key.isEmpty()) { i++; continue }   // 强制前进，防死循环
                skipWs()
                if (i < s.length && s[i] == ':') i++
                val before = i
                m[key] = parseValue()
                if (i == before) i++                   // 同上，进度保证
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
            }
            return m
        }

        private fun readBareKey(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-' || s[i] == '$')) i++
            return s.substring(start, i)
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++   // [
            while (i < s.length) {
                skipWs()
                if (i >= s.length) break
                if (s[i] == ']') { i++; break }
                val before = i
                list.add(parseValue())
                // 进度保证：解析器没前进说明遇到不认识的字符，必须强制前进，
                // 否则死循环把内存吃光（手表上直接卡死）。
                if (i == before) { i++; continue }
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
            }
            return list
        }

        private fun parseString(): String {
            val sb = StringBuilder()
            i++   // 开引号
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'u' -> {
                            val hex = s.substring(i + 2, minOf(i + 6, s.length))
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            i += 4
                        }
                        else -> sb.append(n)
                    }
                    i += 2
                } else if (c == '"') { i++; break } else { sb.append(c); i++ }
            }
            return sb.toString()
        }

        private fun parseNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: t
        }

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    }

}

// 取值便利方法。必须在顶层声明：写在 object 里就不是扩展函数，外部看不见。
fun Any?.getMap(key: String): Map<*, *> =
    (this as? Map<*, *>)?.get(key) as? Map<*, *> ?: emptyMap<String, Any?>()

fun Any?.getStr(key: String): String? =
    (this as? Map<*, *>)?.get(key)?.let { if (it is String) it else it.toString() }

fun Map<*, *>.getStr(key: String): String? =
    get(key)?.let { if (it is String) it else it.toString() }

fun Map<*, *>.getMap(key: String): Map<*, *> =
    get(key) as? Map<*, *> ?: emptyMap<String, Any?>()

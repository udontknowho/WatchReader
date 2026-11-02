package com.watchdl.core

/** 书源规则模型与执行器。取 so-novel 那套声明式规则的子集，见笔记本页
 * `booksource-engine-specs`。刻意不支持脚本：规则里能跑的东西越多，手表上要背的
 * 安全包袱越重。
 */

/** 规范化 HTTP 方法。空值或异体一律归到 GET，避免空方法名传到网络层报 ProtocolException。 */
fun normaliseMethod(raw: String?): String {
    val m = raw?.trim()?.uppercase().orEmpty()
    return if (m.isEmpty()) "GET" else m
}
data class FieldRule(
    /** 选择器原文：`@css:…`、`@xpath:…`、`@json:…`，留空表示用整段响应。 */
    val selector: String = "",
    /** 后处理：`##正则##替换`，只做一次替换。 */
    val replace: String? = null,
) {
    /**
     * 求值。返回所有命中值；[list] 为真时用于列表字段（书名、链接）。
     *
     * 三种连接方式对应 legado `RuleAnalyzer.splitRule("&&", "||", "%%")`：
     * - `||` 备选，**首个出结果的胜出**
     * - `&&` 串接，按序全部取
     * - `%%` 按 token 并行合并（各段的第 i 项配成一行）
     */
    fun eval(base: String, list: Boolean = false, jsLib: String? = null): List<String> {
        // 空选择器表示「整段就是值」，不经过任何选择器。
        //
        // 但 `@js:` 这类本引擎不执行的字段会被降级成空选择器，那就变成把整块
        // HTML 当值返回（实测 52书库的作者字段把整段 <article> 当作者名）。
        // 所以不支持的字段用 [UNSUPPORTED] 标记，在这里直接返回空。
        if (selector == UNSUPPORTED) return emptyList()

        // 整串 URL 模板（导入期用 TEMPLATE_MARK 标记）：`https://h/cx?bookid={{$.id}}`。
        // 原样返回，展开交给调用方（那里才有完整的 ctx）。
        //
        // **必须排在 `{{$` 分支之前**：模板里恰恰带 `{{$.bookId}}`，
        // 先走下面会被 expandJsonTemplate 当「字段模板」在书页作用域里求值，
        // `@` 前缀 / 空串残进 URL，整串就废了。
        if (selector.startsWith(LegadoImport.TEMPLATE_MARK))
            return listOf(selector.removePrefix(LegadoImport.TEMPLATE_MARK))

        // legado 的 `{{…}}` 模板字段。内层分三类：
        // - `{{@@选择器}}` / `{{@css:…}}` / `{{//xpath}}`：规则，递归求值
        // - `{{$.a.b}}`：JSONPath
        // - 其余：JS 表达式（`{{'完结'}}`、`{{source.bookSourceUrl}}`）
        // 以前只认 `{{$.…}}`，另外两类整串落进 CSS 解析 → 恒空：
        // 搜索结果里于是出现 `@{{$.author_nickname}}` 这种原文，分类格也空着。
        // 取不到值就给空串，不把规则语法露给用户。
        if (BRACE.containsMatchIn(selector)) {
            if (TPL_DEPTH.get() > 4) return emptyList()
            TPL_DEPTH.set(TPL_DEPTH.get() + 1)
            val out = try { expandTemplate(selector, base, jsLib) } finally {
                TPL_DEPTH.set(TPL_DEPTH.get() - 1)
            }
            return if (out.isBlank()) emptyList() else listOf(applyReplace(out))
        }

        // 纯正则提取（导入期用 REGEX_MARK 标记）：`##<a\s*href="([^"]+)"##$1###`。
        // 直接对整段文本跑正则，没有 CSS 选择器参与。
        // 以前这个标记只写不读，于是这类字段落进 Selector.parse 解析不出，
        // 最后把**整段 HTML 原样返回** —— 搜索结果里看到 `<em>` 就是这么来的。
        if (selector.startsWith(LegadoImport.REGEX_MARK)) {
            val spec = selector.removePrefix(LegadoImport.REGEX_MARK)
            val parts = spec.split("##")
            val re = SafeRegex.compileLenient(parts.getOrNull(1)) ?: return emptyList()
            // 尾部 `###` 是 legado 的 replaceFirst 标记：
            // 只取**匹配到的那一段**再做替换，其余文本丢掉（不是整段替换）。
            // 依据官方 AnalyzeRule.replaceRegex：replaceFirst 分支用
            // `matcher.group(0).replaceFirst(...)`。
            //
            // 注意判断位置：`##re##to###` 按 `##` 切出来是
            // `["", re, to, "#"]` —— `#` 落在 parts[3]，不在 parts[2]。
            // 所以直接看原串结尾，比数下标稳。
            val firstOnly = spec.endsWith("###")
            val to = parts.getOrElse(2) { "" }
            val out = runCatching {
                if (firstOnly) re.find(base)?.value?.let { re.replace(it, to) }
                else re.replace(base, to)
            }.getOrNull() ?: return emptyList()
            return listOf(out.trim()).filter { it.isNotEmpty() }
        }

        // `@put:{k:规则}`：求值后存进源级变量，不参与本字段的返回。
        // `k:"内嵌规则"` 对当前页求值；`k:变量名` 取已有变量。
        // 51 条源在用（11 条 @put、31 条 @get），以前完全没处理：
        // 原串当选择器 → 恒空，后续 `@get:{k}` 也取不到。
        //
        // 副作用在前：先存，再把剩余规则当本字段的选择器。
        var sel2 = selector
        Regex("@put:\\{").find(sel2)?.let { m ->
            val start = m.range.first
            var depth = 0
            var i = m.range.last
            var end = -1
            while (i < sel2.length) {
                if (sel2[i] == '{') depth++
                else if (sel2[i] == '}') { depth--; if (depth == 0) { end = i; break } }
                i++
            }
            if (end > 0) {
                val body = sel2.substring(m.range.last + 1, end)
                storePutVars(body, base, jsLib)
                sel2 = sel2.removeRange(start, end + 1)
            }
        }
        // `@get:{k}`：取变量值。整段只有它就直接返回；
        // 后面还有规则则把变量值当作新的 base 继续求值。
        Regex("^@get:\\{([^{}]*)\\}").find(sel2.trim())?.let { m ->
            val v = varsOf()[m.groupValues[1].trim()]
            val rest = sel2.trim().substring(m.value.length).trim()
            if (rest.isEmpty()) return v?.let { listOf(it) } ?: emptyList()
            if (v != null) sel2 = rest
        }

        // 整串 URL 模板（导入期用 TEMPLATE_MARK 标记）：`https://h/cx?bookid={{$.id}}`。
        // 原样返回，展开交给调用方（那里才有完整的 ctx）。
        if (sel2.startsWith(LegadoImport.TEMPLATE_MARK))
            return listOf(sel2.removePrefix(LegadoImport.TEMPLATE_MARK))

        if (sel2.isBlank()) return listOf(applyReplace(base))
        val chain = LegadoSelector.chain(sel2)
        fun clean(v: List<String>) = v.map { applyReplace(it) }.filter { it.isNotBlank() }
        // 必须显式写返回类型：不写会被推断成 String?，命中下面那个同名的公开 `one(base)`。
        fun one(seg: String): List<String> = evalOne(base, seg, jsLib)
        // 整条链只有一个段时就是纯 CSS 取值，`##正则` 必须在这里落地。
        // 以前这里不包 clean：只有 `||` / `&&` / `%%` 三条分支净化，
        // 单段直接返回原值，`replace` 被静默丢弃 —— 目录里于是留着
        // `正文卷` / `最新章节.` 这类条目（r7 第七节）。
        //
        // 只包在 selectByCss 这一处、由 eval 统一过一次 clean：
        // `@js` / REGEX_MARK / TEMPLATE_MARK 三个分支自己已经处理过替换，
        // 放进 evalOne 会二次替换。
        val result = if (chain.segments.size == 1 && chain.join != LegadoSelector.Join.Or) {
            clean(one(chain.segments[0]))
        } else when (chain.join) {
            // 备选：首个有结果的胜出
            LegadoSelector.Join.Or -> {
                var hit: List<String> = emptyList()
                for (seg in chain.segments) {
                    val c = clean(one(seg))
                    if (c.isNotEmpty()) { hit = c; break }
                }
                hit
            }

            // 串接：按序全部取
            LegadoSelector.Join.And -> chain.segments.flatMap { clean(one(it)) }

            // 按 token 并行合并：第 0 段的第 i 项接上第 1 段的第 i 项
            LegadoSelector.Join.Merge -> {
                val cols = chain.segments.map { clean(one(it)) }
                val n = cols.firstOrNull()?.size ?: 0
                (0 until n).map { i ->
                    cols.mapNotNull { it.getOrNull(i) }.joinToString("")
                }.filter { it.isNotBlank() }
            }
        }
        return if (list) result else result.take(1)
    }

    /**
     * 展开字段串里的 `{{…}}`。内层是规则就递归求值，是表达式就跑 JS。
     *
     * 对应 legado `SourceRule` 的 `evalPattern` + `isRule`：`@`（含 `@@` 转义）/`/`
     * 开头当规则，`$.`、`$[` 当 JSONPath，其余当 JS。
     */
    private fun expandTemplate(tpl: String, base: String, jsLib: String?): String {
        var resolved = false
        val out = BRACE.replace(tpl) { m ->
            val v = evalBraced(m.groupValues[1].trim(), base, jsLib)
            if (v != null) resolved = true
            v ?: ""
        }
        if (!resolved) return ""
        return out.replace(BRACE, "").trim().removePrefix("@").trim()
    }

    /** 求值一个 `{{…}}` 的内层。null 表示没取到。 */
    private fun evalBraced(inner: String, base: String, jsLib: String?): String? {
        if (inner.isEmpty()) return null
        // `@@` 是 legado 的转义：宣告「后面是规则，不是 JS」。剥掉再当规则求值。
        if (inner.startsWith("@") || inner.startsWith("/")) {
            val rule = if (inner.startsWith("@@")) inner.substring(2) else inner
            return FieldRule.parse(rule).eval(base, jsLib = jsLib).joinToString("\n").ifEmpty { null }
        }
        if (inner.startsWith("$")) {
            // `{{$.summary##正则##替换}}`：带 `##` 的先当规则走一遍，替换才落得下来。
            if (inner.contains("##")) {
                return FieldRule.parse(inner).eval(base, jsLib = jsLib)
                    .joinToString("\n").ifEmpty { null }
            }
            val head = base.trimStart()
            val path = inner.removePrefix("$").removePrefix(".").trim()
            if (path.isEmpty() || !(head.startsWith("{") || head.startsWith("["))) return null
            return TinyJson.path(base, path)
        }
        // 其余当 JS 表达式：`{{'完结'}}`、`{{source.bookSourceUrl}}`。
        // 不包 `return`：Rhino 的 evaluateString 取的就是补全值，顶层 `return` 是语法错。
        return runCatching {
            JsEngine.eval((jsLib ?: "") + "\n" + inner, JsEngine.Env(result = base))
        }.getOrNull()?.ifEmpty { null }
    }

    private fun evalOne(base: String, selectorText: String, jsLib: String? = null): List<String> {
        // JS 分支：`@js:代码` 或 `<js>代码</js>`。对整个 base 求值。
        // jsLib 是源的公共函数库，不拼上会报 ReferenceError。
        jsCodeOf(selectorText)?.let { code ->
            val full = if (jsLib.isNullOrBlank()) code else jsLib + "\n" + code
            val css = selectorPartOf(selectorText)
            val hasCss = css != selectorText.trim()
            // 「选择器在前、脚本在后」：先把选择器取到，再当 result 交给脚本。
            // 否则 result 会是整段 HTML，脚本拿到的不是它要的东西。
            val seed = if (hasCss && !selectorText.trim().startsWith("<js>", true) &&
                          !selectorText.trim().startsWith("@js:", true))
                runCatching { selectByCss(base, css).joinToString("\n") }.getOrNull() ?: base
            else base
            val jsOut = runCatching { JsEngine.eval(full, JsEngine.Env(result = seed)) }
                .getOrElse { return emptyList() }
            // 「脚本在前、选择器在后」：`<js>…</js>#book_list@ul@li`。
            // 实测笔趣阁『聚合书库』的 bookList 就是这么写的 —— 脚本解出 HTML
            // 片段，再用 CSS 取列表项。以前整段当脚本，尾部选择器被吞掉，结果 0 条。
            if (hasCss && (selectorText.trim().startsWith("<js>", true) ||
                           selectorText.trim().startsWith("@js:", true))) {
                val rest = css
                if (rest.isNotEmpty()) {
                    return runCatching { selectByCss(jsOut, rest) }.getOrElse { emptyList() }
                }
            }
            // 「选择器 + 脚本 + 尾部模板」：`$.bid\n<js>b+parseInt(result)</js>\nhttps://…?bookid={{result}}`。
            // 这是 legado 的三段式，实测松鹤阅读这类 QQ 系源都用它。
            // 脚本算出的值要回填进尾部模板，不能直接当结果返回。
            // 以前只取脚本结果（`2200468914`），拼出的书链接是
            // `DQuestQBall/2200468914` —— 不是 URL，点进去必然失败。
            val tail = scriptTailTemplate(selectorText)
            if (tail.isNotEmpty()) {
                // 整串模板替换：`...?bookid={{result}}` → `...?bookid=2200468914`
                return listOf(Template.render(tail, mapOf("result" to jsOut)))
            }
            return listOf(jsOut)
        }
        // 纯 CSS 也要过 applyReplace。
        //
        // 以前这里直接 return selectByCss(...)，`##正则` 尾部过滤被静默丢弃：
        // `chapterName: text##正文卷.` 拆出来是 selector=`...@text` + replace=`##正文卷.`，
        // 只有 And/Or/Merge 分支内的 clean() 会调用 applyReplace，单段路径完全不净化。
        // 结果「正文卷」「最新章节.」这类条目留在目录里 —— r7 报告第七节的根因。
        //
        // 过滤后变空串要丢掉：`##要删的字` 把整条标题吃得只剩空串，就是「这条不是
        // 章节，删掉」，而不是留一个空标题。与 clean() 的 filter 保持一致。
        return selectByCss(base, selectorText)
            .map { applyReplace(it) }
            .filter { it.isNotBlank() }
    }

    /** 按 CSS / JSONPath / XPath 取值。不包含任何脚本处理。 */
    private fun selectByCss(base: String, selectorText: String): List<String> {
        val sel = Selector.parse(selectorText)
        return when (sel) {
            null -> listOf(base)
            is Selector.Json -> JsonPathLite.select(base, sel.path)
            is Selector.Css -> HtmlSelect.select(base, sel, sel.pick, sel.attr)
            is Selector.XPath -> XPathLite.select(base, sel.expr, sel.pick, sel.attr)
        }
    }

    fun one(base: String): String? = eval(base).firstOrNull()

    /** 应用 `##正则##替换`。正则无效时原样返回，不抛。 */
    fun applyReplace(s: String): String {
        val r = replace ?: return s.trim()
        val parts = r.split("##")
        if (parts.size < 2) return s.trim()
        // 先直接编译；失败再修掉 ICU 不认的写法（{{}}、无效转义）重试。
        val re = SafeRegex.compileLenient(parts[1]) ?: return s.trim()
        val to = parts.getOrElse(2) { "" }
        return runCatching { re.replace(s, to).trim() }.getOrDefault(s.trim())
    }

    companion object {
        /**
         * 本引擎不执行的字段（`@js:` 等）的占位选择器。
         *
         * 不能用空串代替：空选择器的语义是「整段就是值」，会把整块 HTML 当结果返回。
         */
        const val UNSUPPORTED = "\u0000unsupported"

        /** 一个 `{{…}}`。不要贪婪：同一串里可能连着好几个。 */
        private val BRACE = Regex("\\{\\{([\\w\\W]*?)\\}\\}")

        /** 模板内嵌规则的递归层数。规则自引用（`{{@@{{…}}}}`）不封顶会栈溢出。 */
        private val TPL_DEPTH = ThreadLocal.withInitial { 0 }

        /**
         * 若这段规则是 JS，取出脚本正文；否则返回 null。
         *
         * 三种写法（legado 都支持）：
         * 1. `@js:代码` 开头
         * 2. `<js>代码</js>` 开头
         * 3. **选择器在前、脚本在后**：`.book-title@text@js:java.t2s(result)`、
         *    `.chapters li a\n<js>…</js>`。脚本拿到的 result 就是选择器取到的值。
         *
         * 第 3 种以前不认，被当成「非 @js 脚本」整条丢掉（实测四五十个字段）。
         */
        fun jsCodeOf(raw: String): String? {
            val s = raw.trim()
            if (s.startsWith("@js:", ignoreCase = true)) return s.substring(4)
            // `<js>…</js>`。尾标签可能被截掉（有些源写半个），所以只要开头是 <js> 就认。
            if (s.startsWith("<js>", ignoreCase = true)) {
                val body = s.substring(4)
                val end = body.lowercase().lastIndexOf("</js>")
                return if (end >= 0) body.substring(0, end) else body
            }
            // 选择器 + 脚本尾巴。只有「前面确实有选择器」才算，避免把纯选择器误判。
            if (s.isEmpty()) return null
            val atJs = s.lastIndexOf("@js:", ignoreCase = true)
            if (atJs > 0) return s.substring(atJs + 4)
            val tag = s.indexOf("<js>", ignoreCase = true)
            if (tag > 0) {
                val body = s.substring(tag + 4)
                val end = body.lowercase().lastIndexOf("</js>")
                return if (end >= 0) body.substring(0, end) else body
            }
            return null
        }

        /** 解析 `选择器##正则##替换` 这种复合写法。`##` 的反斜杠转义要退回为 `\`。 */
        fun parse(raw: String): FieldRule {
            val i = indexOfReplace(raw)
            if (i < 0) return FieldRule(raw.trim())
            return FieldRule(raw.substring(0, i).trim(), raw.substring(i))
        }

        /**
         * 第一个不在 `{{…}}` 里的 `##`，-1 表示没有。
         *
         * `{{$.summary##(^|[。])##$1<br>}}` 的 `##` 属于内层规则，不是本字段的替换；
         * 按 `indexOf` 切会把模板切成两半，头和尾都不再是合法规则。
         */
        private fun indexOfReplace(s: String): Int {
            var depth = 0
            var i = 0
            while (i < s.length - 1) {
                if (s.startsWith("{{", i)) { depth++; i += 2; continue }
                if (s.startsWith("}}", i)) { if (depth > 0) depth--; i += 2; continue }
                if (depth == 0 && s.startsWith("##", i)) return i
                i++
            }
            return -1
        }

        /**
         * 取「选择器 + 脚本」里的选择器部分（脚本尾巴去掉）。
         *
         * 让 `[selector]@js:code` 能先把选择器取出来，再把脚本当成后处理。
         * 没脚本时原样返回。
         */
        fun selectorPartOf(raw: String): String {
            val s = raw.trim()
            // `<js>…</js>` / `@js:…` 在开头时，脚本之后可能还跟着选择器
            // （`<js>…</js>#book_list@ul@li`）。取脚本闭合之后的那段。
            if (s.startsWith("<js>", true)) {
                val end = s.lowercase().indexOf("</js>")
                // 脚本后接普通选择器要取出来；后接 URL 模板则交给 scriptTailTemplate
                if (end >= 0) {
                    val rest = s.substring(end + 5).trim()
                    return if (rest.startsWith("http") || rest.startsWith("/")) "" else rest
                }
                return s
            }
            if (s.startsWith("@js:", true)) return s
            val atJs = s.lastIndexOf("@js:", ignoreCase = true)
            val tag = s.indexOf("<js>", ignoreCase = true)
            val cut = listOf(atJs, tag).filter { it > 0 }.minOrNull() ?: return s
            return s.substring(0, cut).trim()
        }

        /**
         * 取「脚本之后的那段 URL 模板」。
         *
         * 例：`$.bid\n<js>1100000000+parseInt(result)</js>\nhttps://x.com/a?b={{result}}`
         * → 返回 `https://x.com/a?b={{result}}`；没有则返回空串。
         */
        fun scriptTailTemplate(raw: String): String {
            val s = raw.trim()
            val end = s.lowercase().lastIndexOf("</js>")
            if (end < 0) return ""
            val rest = s.substring(end + 5).trim()
            if (rest.isEmpty()) return ""
            // 以 http / / 开头的才算 URL 模板；其余是选择器，由 selectorPartOf 处理
            return if (rest.startsWith("http") || rest.startsWith("/")) rest else ""
        }

        /**
         * 取出「选择器在前、脚本在后」这种写法里的选择器部分。
         *
         * 实测黑粉小说的 chapterList 写成：
         * ```
         * .chapters li a ||.list_xm@li@a
         * <js>
         * …校验处理…
         * </js>
         * ```
         * 前半段是完整可用的 CSS，脚本只是额外的验证码处理。
         * 整条丢掉等于白扔一个能用选择器的源，所以只取 CSS 前缀。
         *
         * 返回 null 表示没有「CSS 前缀 + 脚本」这种结构。
         */
        /**
         * `@put:{...}` 的变量表。
         *
         * 放伴生对象：变量要跨字段共享（一个字段 `@put` 存，另一个字段 `@get` 取），
         * 而同一个源的各个字段是**不同的 FieldRule 实例**。ThreadLocal 隔离并发源。
         */
        private val VARS = ThreadLocal.withInitial { mutableMapOf<String, String>() }

        internal fun varsOf(): MutableMap<String, String> = VARS.get()

        /** 清空变量表。一次搜索/目录解析开始时调，避免串到上一本书。 */
        fun clearVars() = VARS.get().clear()

        fun cssBeforeScript(raw: String): String? {
            val s = raw.trim()
            if (s.startsWith("<js>", true) || s.startsWith("@js:", true)) return null
            // 两种脚本分隔写法：`<js>` 和 `@js:`（后者前面往往还有换行，实测三个源都是这种）
            val i = listOf(
                s.indexOf("<js>", ignoreCase = true),
                s.indexOf("@js:", ignoreCase = true),
            ).filter { it > 0 }.minOrNull() ?: return null
            return s.substring(0, i).trim().takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 执行 `@put:{k:"内嵌规则", k2:变量名}`。
     *
     * 值双引号包住时是内嵌规则（对当前页求值），否则当变量名取已有值。
     */
    private fun storePutVars(body: String, base: String, jsLib: String?) {
        val vars = varsOf()
        splitTopLevel(body).forEach { pair ->
            val colon = pair.indexOf(':')
            if (colon <= 0) return@forEach
            val key = pair.substring(0, colon).trim().trim('"', '\'')
            val v = pair.substring(colon + 1).trim()
            if (key.isEmpty()) return@forEach
            val value = if (v.length >= 2 && (v.first() == '"' || v.first() == '\'') && v.last() == v.first()) {
                // 内嵌规则：引号里的内容当选择器再求一次
                FieldRule(v.substring(1, v.length - 1)).eval(base, jsLib = jsLib).firstOrNull() ?: ""
            } else {
                // 变量名：取已有值，没有就原样
                vars[v] ?: v
            }
            if (value.isNotBlank()) vars[key] = value
        }
    }

    /** 按顶层逗号切 `k:v, k2:v2`，不切引号/括号内的。 */
    private fun splitTopLevel(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        s.forEachIndexed { i, c ->
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '{' || c == '(' || c == '[' -> depth++
                c == '}' || c == ')' || c == ']' -> depth--
                c == ',' && depth == 0 -> { out.add(s.substring(start, i)); start = i + 1 }
            }
        }
        out.add(s.substring(start))
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** 搜索段规则。 */
data class SearchRule(
    val url: String,
    /**
     * `searchUrl` 是 JS 时的脚本正文（`@js:…`）。非空时 [url] 为空，
     * URL 在请求时由 [SourceEngine] 用 JsEngine 算出。
     */
    val urlJs: String? = null,
    val method: String = "GET",
    val body: String? = null,
    val charset: String = "UTF-8",
    val headers: Map<String, String> = emptyMap(),
    /**
     * 结果项选择器（legado 的 `bookList`）。取到每一项后，下面的字段选择器
     * 都**相对于该项**求值。
     */
    val list: FieldRule? = null,
    /** 响应是 JSON（决定字段选择器的解析方式）。 */
    val isJson: Boolean = false,
    /** 列表字段。 */
    val name: FieldRule,
    val link: FieldRule,
    val author: FieldRule = FieldRule(),
    val intro: FieldRule = FieldRule(),
)

/** 目录段规则。 */
data class TocRule(
    val url: String,
    val method: String = "GET",
    val charset: String = "UTF-8",
    val headers: Map<String, String> = emptyMap(),
    val name: FieldRule,
    val link: FieldRule,
    val isJson: Boolean = false,
    /** 目录分页（legado 的 `ruleToc.nextTocUrl`）。16/54 条源在用。 */
    val nextTocUrl: FieldRule? = null,
    /** 卷名标记（legado 的 `ruleToc.isVolume`）。求值后为真即该条是卷名，不是章节。 */
    val isVolume: FieldRule? = null,
    /**
     * 目录页地址（legado 的 `ruleBookInfo.tocUrl`）。
     *
     * 175 条源里有 **87 条**用它。以前完全没读，于是直接拿书籍页当目录页，
     * 而书籍页只有简介、没有章节列表 —— 整本书就取不到目录。
     * 求值作用域是**书籍页**，结果相对书籍页 URL 绝对化。
     */
    val tocUrlRule: FieldRule? = null,
    /**
     * 简介（legado 的 `ruleBookInfo.intro`）。
     *
     * 以前只导了 `ruleBookInfo` 的 `init` / `tocUrl`，简介字段直接丢掉，
     * 目录屏因此没有简介可显。作用域与 [tocUrlRule] 一样是**书籍页**。
     */
    val intro: FieldRule = FieldRule(),
    /**
     * 书页正文的收窄规则（legado 的 `ruleBookInfo.init`）。19/175 条源在用。
     *
     * legado 的语义：先拿 `init` 从书页里取出子对象，**该子串成为后续所有
     * `ruleBookInfo` 字段的新 `result`**（`tocUrl` 里的 `{{$.x}}` 也在其中）。
     *
     * 典型：详情页是 `{code,msg,data:{bookVo:{bookId,…}}}`，规则写
     * `init: "$.data.bookVo"` + `tocUrl: "…?bookId={{$.bookId}}"`。
     * 不实现 init，`$.bookId` 就在顶层找 → 取空 → URL 变成 `?bookId=` → 报错。
     * 实测评测里那 5 条 0ms 失败的源正是这种写法。
     */
    val initRule: FieldRule? = null,
    /**
     * `chapterList` 的原始规则，仅在它是脚本且返回**对象数组**时有值。
     *
     * 5 条源这么写：脚本列出 `{n:…,u:…}`，chapterName / chapterUrl 再在对象内取 `$.n` / `$.u`。
     * 为 null 时走字符串列表的旧路径。
     */
    val sourceList: FieldRule? = null,
)

/** 正文段规则。 */
data class ContentRule(
    val url: String,
    val method: String = "GET",
    val charset: String = "UTF-8",
    val headers: Map<String, String> = emptyMap(),
    val content: FieldRule,
    val isJson: Boolean = false,
    /**
     * 取到正文后要做的正则清理（legado 的 `ruleContent.replaceRegex`）。
     *
     * 值是 `##正则##替换` 或 `##正则`（`##` 后无内容表示替换为空串）。
     * 实测 54 条源里 25 条用这个字段，丢掉它正文会夹带站点广告与提示语。
     */
    val replaceRegex: String? = null,
    /** 只替换第一处（legado 的 `replaceFirst`）。 */
    val replaceFirst: Boolean = false,
    /**
     * 正文分页：从当前页 HTML 里取「下一页」链接（legado 的 `ruleContent.nextContentUrl`）。
     *
     * 实测 54 条源里 27 条用这个字段。不实现的话，多页正文只能拿到第一页 ——
     * 看起来像「正文被截断」，实际是分页没跟。
     */
    val nextContentUrl: FieldRule? = null,
)

/** 一本书源。字段名对齐 so-novel 的 JSON，方便直接导入它的规则文件。 */
data class BookSourceRule(
    val name: String,
    val host: String = "",
    val search: SearchRule,
    val toc: TocRule,
    val content: ContentRule,
    /**
     * 源的公共函数库（legado 的 `jsLib`）。
     *
     * 实测大量源把 `getServerTime` / `zzPost` 这类函数写在这里，
     * 然后规则里直接调。不先执行它，规则脚本会报 ReferenceError。
     */
    val jsLib: String? = null,
) {
    /** 把 `{{key}}` 里的变量替换掉，并补全相对 URL。
     *
     * [result] 是当前阶段的响应正文。legado 的 `{{$.x}}` 在 URL 里跑的是 JS 表达式，
     * 而 JS 侧 `$` 绑的就是这个响应（`AnalyzeUrl.replaceKeyPageJs` → `evalJS`）。
     * 以前这里不传，`{{$.bookId}}` 被 `Template` 当成未知变量换成空串，
     * URL 就变成 `?bookId=` —— 实测 5 条源 0ms 死在这里。
     */
    /**
     * [base] 是拼相对路径的基准，默认站点根。
     *
     * 正文分页要传**当前页**地址：章节页 `/book/123/456.html` 的下一页若是
     * `456_2.html`，用站点根会拼成 `/456_2.html`（404，或站点根返回 200 时
     * 把首页文字拼进正文）。以前这里只会用站点根，调用方再传 base 也无效 ——
     * 因为 expand 已经把相对链接绝对化了。
     */
    fun expand(
        template: String,
        ctx: Map<String, String>,
        result: String? = null,
        base: String? = null,
    ): String {
        val withJson = result?.let { putJsonVars(template, it) } ?: template
        val rendered = Template.render(withJson, ctx) { code, env ->
            JsEngine.eval(jsLib?.let { "$it\n$code" } ?: code, env.copy(vars = env.vars.toMutableMap()))
        }
        if (rendered.isBlank()) return rendered
        if (rendered.startsWith("http://", true) || rendered.startsWith("https://", true)) return rendered
        val origin = base?.substringBefore(',')?.trim().orEmpty()
        if (origin.isEmpty() && host.isEmpty()) return rendered
        // 与 SourceEngine.absolute 保持一致：走 RFC 3986。
        // 默认 base 不补尾斜杠：`?p=20` 拼在 host 后应为 `https://x.com?p=20`（历史行为，
        // EngineTest「分页算术」依赖它）。传了当前页 base 时就走标准 URL 解析。
        val baseUrl = if (origin.isNotEmpty()) origin else host.trimEnd('/')
        // `?` / `#` 开头：直接接在 base 后。走 URL 会补一个 `/`（变成 https://x/?p=20），
        // 与历史行为不符，「分页算术」用例依赖 https://x?p=20。
        if (rendered.startsWith("?") || rendered.startsWith("#")) return baseUrl + rendered
        return runCatching { java.net.URL(java.net.URL(baseUrl), rendered).toString() }
            .getOrDefault(if (rendered.startsWith("/")) baseUrl + rendered else "$baseUrl/$rendered")
    }

    /** 把模板里的 `{{$.a.b}}` 就地换成响应里的值。取不到就留空串（与 legado 一致）。 */
    private fun putJsonVars(template: String, result: String): String {
        if (!template.contains("{{\$")) return template
        val json = result.trimStart()
        if (!json.startsWith("{") && !json.startsWith("[")) return template
        return Regex("\\{\\{\\s*\\$(\\.[A-Za-z0-9_.\\[\\]]*)?\\s*\\}\\}").replace(template) { m ->
            val path = m.groupValues[1].removePrefix(".")
            if (path.isEmpty()) json else TinyJson.path(json, path) ?: ""
        }
    }

}

// ── `AnalyzeUrl.analyzeJs()` 的等价物（legado 里处理「脚本块 + 尾部文本」）──
//
// 约定：`<js>…</js>` 块只做副作用；块**之后**的文本才是 URL，
// 文本里的 `@result` 用块的结果替换。uu书库、最终小说网都这么写。
private val JS_BLOCK = Regex("<js>([\\w\\W]*?)</js>", RegexOption.IGNORE_CASE)

/** 按出现顺序取出所有 `<js>…</js>` 的块体。 */
fun jsBlockBodies(raw: String): List<String> =
    JS_BLOCK.findAll(raw).map { it.groupValues[1].trim() }.toList()

/** 最后一个 `</js>` 之后的文本；没有块或块后为空则返回空串。 */
fun textAfterLastJsBlock(raw: String): String =
    JS_BLOCK.findAll(raw).lastOrNull()?.let { raw.substring(it.range.last + 1).trim() } ?: ""

/** `@result` 回填。脚本结果为空时也把占位符换成空串，不留脏字符。 */
fun applyResult(template: String, jsOut: String?): String =
    if (jsOut == null) template else template.replace("@result", jsOut)

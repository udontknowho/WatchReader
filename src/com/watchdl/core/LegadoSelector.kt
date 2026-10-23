package com.watchdl.core

/**
 * legado 选择器的归一化。legado 在 CSS 基础上多了一套自己的简写，
 * 以及用 `||` 串备选。这里把简写展开成标准 CSS，并把 `||` 拆开。
 * 见笔记本页 `booksource-engine-specs`。
 */
object LegadoSelector {

    /**
     * 拆出备选链。`#content@html||.content@html` → 两条规则，按序尝试直到命中。
     *
     * 只处理 `||`。要完整语义（含 `&&` / `%%`）用 [chain]。
     */
    fun alternatives(raw: String): List<String> =
        raw.split("||").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 规则链的连接方式。对应 legado `RuleAnalyzer.splitRule("&&", "||", "%%")`。
     *
     * - [Or]（`||`）：备选，**首个出结果的胜出**
     * - [And]（`&&`）：串接，按序取到的东西全部要
     * - [Merge]（`%%`）：**按 token 并行合并** —— 第 0 段的第 i 项与第 1 段的第 i 项配对
     */
    enum class Join { Or, And, Merge }

    /** 切好的规则链：若干段 + 它们的连接方式。 */
    data class Chain(val segments: List<String>, val join: Join) {
        /** 兼容旧调用：只有一段时直接拿第一段。 */
        fun first(): String = segments.first()
    }

    /**
     * 按 legado 语义切分规则链。
     *
     * 分隔符的优先级与官方一致：先 `&&`、再 `||`、最后 `%%`，但**一条规则里只用一种**
     * （官方 `splitRule` 会在首个命中的分隔符上定型）。括号/引号内的分隔符不算。
     */
    fun chain(raw: String): Chain {
        val s = raw.trim()
        if (s.isEmpty()) return Chain(emptyList(), Join.Or)
        // 按优先级依次尝试，首个出现的分隔符定型
        for ((sep, join) in listOf("&&" to Join.And, "||" to Join.Or, "%%" to Join.Merge)) {
            val parts = splitTop(s, sep)
            if (parts.size > 1) return Chain(parts, join)
        }
        return Chain(listOf(s), Join.Or)
    }

    /**
     * 只在外层（不在 `[]` `()` 内）切分。
     *
     * 官方用平衡组扫描，为的是不踩 JSONPath 自带的 `||`/`&&`（如 `$.a||b`）
     * 与选择器里的引号。这里同样跳过括号与引号内的分隔符。
     */
    private fun splitTop(s: String, sep: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inSingle = false
        var inDouble = false
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                !inSingle && !inDouble && (c == '[' || c == '(') -> depth++
                !inSingle && !inDouble && (c == ']' || c == ')') -> depth--
            }
            if (depth == 0 && !inSingle && !inDouble && s.startsWith(sep, i)) {
                out.add(s.substring(start, i).trim())
                i += sep.length
                start = i
                continue
            }
            i++
        }
        out.add(s.substring(start).trim())
        return out.filter { it.isNotEmpty() }
    }

    /**
     * 把 legado 简写展开成选择器字符串。
     *
     * - `id.foo` → `#foo`、`class.foo` → `.foo`、`tag.a` → `a`
     * - `id.newlist@tag.a` → `#newlist a`
     *
     * 实际工作由 [RuleNode] 做：先解析成语法树，再渲染回字符串。
     * 保留这个函数是因为 [LegadoImport] 与若干测试按字符串比较结果。
     * 新代码应直接用 [RuleNode.parse]，不要经过字符串再解析一遍。
     */
    fun toCss(raw: String): String {
        // `##正则##替换` 与 `@js:`/`<js>` 之后是自由文本，不参与选择器语法。
        // 以前整串喂进解析器，正则里的 `[0-9]` 被当成序号、JS 里的
        // `result.match(...)` 被当成属性名，渲染结果面目全非。
        // `{{$.id}}` / `{{java.getString(…)}}` 是 JSONPath/JS 取值模板，
        // 不是 CSS 选择器，原样返回（解析会把 `.introinfo` 当类名拆坏）。
        if (raw.contains("{{")) return raw

        val jsAt = listOf(raw.indexOf("@js:"), raw.indexOf("<js>"))
            .filter { it >= 0 }.minOrNull() ?: raw.length
        val head = raw.substring(0, jsAt)
        val tail = raw.substring(jsAt)
        val reAt = head.indexOf("##")
        val body = if (reAt >= 0) head.substring(0, reAt) else head
        val rest = if (reAt >= 0) head.substring(reAt) else ""
        val css = RuleNode.parse(body)?.let { RuleNode.render(it).trim() } ?: body.trim()
        return css + rest + tail
    }

    /**
     * 对整条 `||` 备选链逐条做 [toCss]，再用 `||` 拼回去。
     *
     * 不能直接对整条链调 [toCss]：它靠 `lastIndexOf('@')` 判断取值后缀，
     * 链上只有最后一段的 `@` 会被认出来，其余的会被当成 legado 路径分隔符
     * 换成空格，把 `@text` / `@href` 吃掉。
     */
    fun normaliseChain(raw: String): String =
        alternatives(raw).joinToString("||") { toCss(it) }

    /**
     * 判断 `@` 后面是取值后缀还是路径分隔。
     *
     * legado 里 `id.foo@tag.a` 的 `@tag.` 是「接着找子节点」，而 `div@text` 的
     * `@text` 是取值。混在一起时按后缀名判断。
     */
    fun isValueSuffix(suffix: String): Boolean {
        val s = suffix.trim()
        if (s.startsWith("tag.") || s.startsWith("id.") || s.startsWith("class.")) return false
        return s.isNotEmpty()
    }

    /** 取一条 legado 规则里最后那个取值后缀（`@text` / `@html` / `@href` / `@attr=val`）。 */
    fun valueSuffix(raw: String): String? {
        val at = raw.lastIndexOf('@')
        if (at <= 0) return null
        val suffix = raw.substring(at + 1)
        return if (isValueSuffix(suffix)) suffix else null
    }

    /** 判断规则是否用了 JS。 */
    fun usesScript(raw: String?): Boolean {
        val s = raw ?: return false
        return s.contains("@js:") || s.contains("<js>") || s.contains("java.") ||
            s.contains("JSON.parse") || s.contains("@js\n")
    }
}

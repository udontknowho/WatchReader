package com.watchdl.core

/**
 * legado 选择器的语法树。
 *
 * 以前 [LegadoSelector.toCss] 把简写展开成一条 CSS 字符串，再用 `[rev]`、
 * `[!0:2]`、`:text(…)` 这类**非 CSS 标记**把位置与谓词塞回去，[HtmlSelect]
 * 接着反向解析这些标记。于是每加一条语法就要在「展开」与「反向解析」两处
 * 同时改，两边靠字符串协议对齐。
 *
 * 现在改成：规则串 → [RuleNode]（解析一次）→ 执行。
 * [render] 把 AST 渲染回字符串，只为兼容仍要吃字符串的 [HtmlSelect]。
 *
 * 语义与 legado 官方 `RuleAnalyzer.splitRule` + `AnalyzeByJSoup` 一致：
 * - 段按空格或 `>` 切，**两者不分**（官方也是按后代处理）
 * - 序号 0 起；`A[N]` 是「每个父节点的第 N 个」，不是全局下标
 * - `text.串` 是「内文包含该串」的谓词，不是类名
 */
sealed interface RuleNode {

    /** 一段：选择器 + 谓词 + 0~1 个序号选择器。`sel` 为空表示任意标签。 */
    data class Step(
        val sel: StepSel = StepSel(),
        val picker: Picker? = null,
    ) : RuleNode

    /** 整条链的倒置（legado 段首 `-`）。 */
    data class Reverse(val inner: RuleNode) : RuleNode

    /**
     * 取值后缀：`@text` / `@html` / `@href` / `@attr=val`。
     *
     * [pick] 为 null 表示没写后缀，由调用方的默认值决定。
     */
    data class Pick(val pick: Selector.Pick?, val attr: String?) : RuleNode

    companion object {
        /** 渲染时用来标记「本段要倒置」的非 CSS 标记。 */
        const val REV_MARK = "[rev]"

        /**
         * 解析一条 legado 选择器。
         *
         * @return null 表示空规则。
         */
        fun parse(raw: String): RuleNode? {
            var s = raw.trim()
            if (s.isEmpty()) return null

            // `@css:` / `@xpath:` / `@json:` 只是标明语法类型，这里摘掉再判断。
            // 不摘会在按 `@` 切路径时把 `css:` 留进选择器，选不到任何东西。
            val prefix = listOf("@css:", "@xpath:", "@json:").firstOrNull { s.startsWith(it, true) }
            if (prefix != null) s = s.substring(prefix.length).trim()

            val first = when {
                s.startsWith("json:") || prefix.equals("@json:", true) -> s.substringAfter(':').trim()
                s.startsWith("$") || s.startsWith("data.") -> s
                s.startsWith("xpath:") -> "@xpath:" + s.substringAfter(':').trim()
                prefix.equals("@xpath:", true) || s.startsWith("/") -> s
                else -> s
            }

            var pick: RuleNode.Pick? = null
            var body = first
            val at = valueSuffixAt(first)
            if (at > 0) {
                body = first.substring(0, at).trim()
                pick = pickOf(first.substring(at + 1).trim())
            }

            val isPath = body.startsWith("@xpath:") || body.startsWith("/")
            val inner: RuleNode = if (body.isEmpty()) {
                Step()
            } else if (isPath) {
                // XPath 不做 legado 简写展开：`//div/@href` 的 `@` 是 XPath 语法。
                Step(StepSel.Path(body.removePrefix("@xpath:").trim()))
            } else {
                chainOf(body)
            }
            return if (pick == null) inner else nested(inner, pick)
        }

        /**
         * 按 `@` 切成层，每层再切成段。
         *
         * `id.newlist@tag.a` → 两层；`div@text` 的 `@text` 是后缀不是层。
         */
        internal fun chainOf(body: String): RuleNode {
            val levels = body.split('@').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { stepsOf(it) }
            // 段首 `-` 是 legado 的列表倒置，作用于整条链。
            val rev = body.trimStart().startsWith("-")
            val node: RuleNode = if (levels.isEmpty()) Step() else Group(levels)
            return if (rev) Reverse(node) else node
        }

        /**
         * 一层里按空格与 `>` 切成段，全部拍平。
         *
         * `>` 与空格执行语义相同（都是后代），切成段后不再保留分隔符，
         * 但渲染时用空格接回去；`@css:` 规则里 `A > B` 与原样保留的差别
         * 只影响可读性，不影响选中结果。
         */
        internal fun stepsOf(part: String): List<RuleNode.Step> {
            val out = ArrayList<RuleNode.Step>()
            val cur = StringBuilder()
            fun flush() {
                val t = cur.toString().trim()
                cur.setLength(0)
                if (t.isNotEmpty()) out.add(stepOf(t))
            }
            for (c in part.trim()) {
                when {
                    // `>` 独立成段，渲染时原样还回去。
                    c == '>' -> { flush(); out.add(stepOf(">")) }
                    c.isWhitespace() -> flush()
                    else -> cur.append(c)
                }
            }
            flush()
            return out
        }

        /** 单个段：`tag.p!0:2`、`text.下一页.0`、`.a.b`、`p:eq(1)`。 */
        internal fun stepOf(raw0: String): Step {
            // `>` 与空格同义（都是后代），但渲染时要原样留下，所以只在本段首尾剥掉。
            val raw = raw0.trim()
            // `children` 是 legado 的「所有直接子标签」。本执行器只有后代关系，
            // 渲染成 `>*`（历史上就是这样，且父节点本身不会被自己的后代匹配到）。
            if (raw == "children") return Step(StepSel(wildcard = true, raw = ">*"))
            // 单独一个 `>`（`@css:.a > li` 里的）原样保留，执行层当空段跳过。
            if (raw == ">*" || raw == "*") return Step(StepSel(wildcard = true))
            if (raw == ">") return Step(StepSel(raw = ">"))

            var sel = StepSel()
            var picker: Picker? = null

            // `:eq(N)` / `:contains(T)`：`@css:` 前缀的规则会原样带进来。
            Regex("^(.*?):eq\\(\\s*(-?\\d+)\\s*\\)$").find(raw)?.let { m ->
                return Step(StepSel.parse(m.groupValues[1]), Picker.One(m.groupValues[2].toInt()))
            }
            Regex("^(.*?):contains\\(\\s*(.+?)\\s*\\)$").find(raw)?.let { m ->
                return Step(StepSel.parse(m.groupValues[1]), Picker.Contains(m.groupValues[2].trim('\'', '"')))
            }

            var s = expandDotted(raw.trimStart().removePrefix("-").trim())
            // 方括号位置：`[0]`、`[-1]`、`[!0]`、`[!0:2]`、`[0:3:2]`、`[rev]`。
            // 从右往左剥，允许 `a[0][rev]` 这种连写。
            while (true) {
                val m = Regex("\\[([!\\-\\d:]+|rev)]$").find(s) ?: break
                s = s.substring(0, m.range.first)
                val tok = m.groupValues[1]
                val p = if (tok == "rev") Picker.Reverse else pickerOf(tok) ?: continue
                picker = combine(picker, p)
            }
            // 点号位置：`p!0`、`.chapter!0:2`、`p.-1`。
            Regex("^(.*?)(?:!([\\-\\d:]+)|\\.(-?\\d+))$").find(s)?.let { m ->
                val excl = m.groupValues[2]
                val neg = m.groupValues[3]
                // 正则已经把 `!` 吃掉，补回去再交给 pickerOf。
                val p = if (excl.isNotEmpty()) pickerOf("!" + excl) else neg.toIntOrNull()?.let { Picker.One(it) }
                if (p != null) {
                    s = m.groupValues[1]
                    picker = combine(picker, p)
                }
            }
            sel = StepSel.parse(s)
            return Step(sel, picker)
        }

        /**
         * 展开 legado 的点号简写：`tag.div` → `div`、`class.foo` → `.foo`、
         * `id.foo` → `#foo`、`text.下一页` → `:text(下一页)`。
         *
         * 逐 token 走，允许连写：`tag.div.cls.1` → `div.cls[1]`。
         */
        internal fun expandDotted(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return t
            val tokens = t.split('.').filter { it.isNotEmpty() }
            if (tokens.size < 2) return t
            // 简写展开只对以 id./class./tag./text. 开头的段生效；
            // 其余情况还要处理 `span.2:0`：点号后是位置记号，不是类名。
            if (tokens[0] !in setOf("id", "class", "tag", "text")) return expandDotPosition(t)
            val sb = StringBuilder()
            var i = 0
            while (i < tokens.size) {
                when (tokens[i]) {
                    "id" -> { i++; if (i < tokens.size) { sb.append('#').append(tokens[i]); i++ } }
                    "class" -> { i++; if (i < tokens.size) { sb.append('.').append(tokens[i]); i++ } }
                    "tag" -> {
                        i++
                        if (i >= tokens.size) continue
                        sb.append(tokens[i]); i++
                        // 标签后紧跟的位置段
                        if (i < tokens.size) {
                            val p = tokens[i]
                            if (p.startsWith("!") || Regex("^-?\\d+$").matches(p)) {
                                sb.append('[').append(p).append(']'); i++
                            }
                        }
                    }
                    "text" -> {
                        i++
                        if (i >= tokens.size) { sb.append('*'); continue }
                        sb.append('*').append(":text(").append(tokens[i]).append(')'); i++
                        if (i < tokens.size) {
                            val p = tokens[i]
                            if (p.toIntOrNull() != null) { sb.append('[').append(p).append(']'); i++ }
                        }
                    }
                    else -> { sb.append('.').append(tokens[i]); i++ }
                }
            }
            return sb.toString()
        }


        /**
         * `span.2:0` → `span[2:0]`、`td.-1:-2` → `td[-1:-2]`。
         *
         * legado 的位置简写可以是单个数字（`.0`）或区间（`.2:0`）。
         * 以前只认单个数字，区间被当成类名，渲染成了 `span:0.2`。
         */
        private fun expandDotPosition(t: String): String {
            val pos = Regex("^([^.]+)\\.([\\-\\d:!]+)$").find(t) ?: return t
            val (head, idx) = pos.destructured
            if (head.isEmpty()) return t
            return "$head[$idx]"
        }

        /**
         * 一个段里只挂一个序号选择器。连写时取**最左**的那个。
         *
         * `a[0][rev]` 的 `[rev]` 是外层 [Reverse] 的部分，不该被 `[rev]` 标记吃掉。
         * 历史上这个位置的选择器是 `[rev]` 之外的第一个，保持一致。
         */
        private fun combine(first: Picker?, next: Picker): Picker = first ?: next

        /** `!X` 排除、`a:b:c` 区间、纯数字取第 N 个。 */
        internal fun pickerOf(body: String): Picker? =
            if (body.startsWith("!")) {
                val ns = body.substring(1).split(':').mapNotNull { it.trim().toIntOrNull() }
                when (ns.size) {
                    0 -> null
                    1 -> Picker.Exclude(ns[0])
                    else -> Picker.ExcludeRange(ns)
                }
            } else if (body.contains(":")) {
                val p = body.split(':')
                Picker.Range(
                    p.getOrNull(0)?.takeIf { it.isNotEmpty() }?.toIntOrNull(),
                    p.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull(),
                    p.getOrNull(2)?.toIntOrNull() ?: 1,
                )
            } else {
                body.toIntOrNull()?.let { Picker.One(it) }
            }

        /** 取值后缀 → [Pick]。与 `Selector.parse` 的后缀分支同义。 */
        private fun pickOf(suffix: String): RuleNode.Pick {
            val eq = suffix.indexOf('=')
            return when {
                suffix.equals("text", true) -> RuleNode.Pick(Selector.Pick.TEXT, null)
                suffix.equals("textNodes", true) -> RuleNode.Pick(Selector.Pick.TEXT_NODES, null)
                suffix.equals("ownText", true) -> RuleNode.Pick(Selector.Pick.OWN_TEXT, null)
                suffix.equals("html", true) -> RuleNode.Pick(Selector.Pick.HTML, null)
                suffix.equals("all", true) -> RuleNode.Pick(Selector.Pick.ALL, null)
                eq > 0 -> RuleNode.Pick(Selector.Pick.ATTR, suffix.substring(eq + 1).trim())
                else -> RuleNode.Pick(Selector.Pick.ATTR, suffix)
            }
        }

        /**
         * 把 [pick] 挂到 [inner] 的最深处。多层链上只有最后一层带取值后缀。
         *
         * 纯取值后缀（`@text`）解析出来是空 [Step]，直接用 [pick] 顶替。
         */
        internal fun nested(inner: RuleNode, pick: RuleNode.Pick): RuleNode = when (inner) {
            is Step -> if (inner.sel.isEmpty && inner.picker == null) pick else Group(listOf(inner, pick))
            is Reverse -> Reverse(nested(inner.inner, pick))
            // 取值后缀挂在链尾：`levels` 是平铺的，直接接一段 [Pick] 即可。
            is Group -> Group(inner.levels + pick)
            is RuleNode.Pick -> inner
        }

        /**
         * 拆出 `(选择器主体, 取值方式, 属性名)`，供 [Selector.parse] 分派。
         *
         * 主体带回 `css:` / `xpath:` 前缀，让调用方只看前缀决定用哪个选择器。
         * 空规则返回 (null, …)，表示「整段就是值」。
         */
        fun head(n: RuleNode): Triple<String?, Selector.Pick?, String?> {
            var pick: Selector.Pick? = null
            var attr: String? = null
            fun take(p: RuleNode.Pick) { pick = p.pick; attr = p.attr }
            var inner = n
            while (true) {
                when (inner) {
                    is RuleNode.Pick -> { take(inner); break }
                    is Reverse -> inner = inner.inner
                    is Group -> {
                        val last = inner.levels.lastOrNull() ?: break
                        if (last is RuleNode.Pick) { take(last); inner = Group(inner.levels.dropLast(1)) }
                        break
                    }
                    is Step -> break
                }
            }
            val body = renderBody(inner)
            return Triple(body.ifEmpty { null }, pick, attr)
        }

        private fun startsWithXPath(n: RuleNode): Boolean = when (n) {
            is Step -> n.sel.xpath
            is Reverse -> startsWithXPath(n.inner)
            is Group -> n.levels.firstOrNull()?.let { startsWithXPath(it) } ?: false
            is RuleNode.Pick -> false
        }

        /** 只渲染结构部分，不带取值后缀。 */
        /**
         * 渲染一串节点：同层 [Step] 之间用空格，取值后缀 [Pick] 前用 `@`。
         *
         * 这套分隔符是 [HtmlSelect] 与 [Selector] 共同约定的字符串形态：
         * 空格=后代关系，`@`=取值后缀。`Pick` 之后不再接线。
         */
        private fun joinNodes(nodes: List<RuleNode>): String {
            val sb = StringBuilder()
            for (nd in nodes) {
                when (nd) {
                    is RuleNode.Pick -> sb.append(render(nd))
                    else -> {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(render(nd))
                    }
                }
            }
            return sb.toString()
        }

        private fun renderBody(n: RuleNode): String = when (n) {
            is Group -> joinNodes(n.levels.filter { it !is RuleNode.Pick })
            is Reverse -> renderBody(n.inner)
            is Step -> render(n)
            is RuleNode.Pick -> ""
        }

        /**
         * 渲染回字符串，供 [HtmlSelect] 消费。
         *
         * 输出形态与旧 [LegadoSelector.toCss] 逐字一致：位置写成 `[N]`，
         * 谓词写成 `:text(…)`，倒置写成 `[rev]`。
         */
        fun render(n: RuleNode): String = when (n) {
            is Step -> renderSel(n.sel) + (n.picker?.let { renderPicker(it) } ?: "")
            is RuleNode.Pick -> "@" + when (n.pick) {
                null -> ""
                Selector.Pick.TEXT -> "text"
                Selector.Pick.TEXT_NODES -> "textNodes"
                Selector.Pick.OWN_TEXT -> "ownText"
                Selector.Pick.HTML -> "html"
                Selector.Pick.ALL -> "all"
                Selector.Pick.ATTR -> n.attr ?: ""
            }
            is Group -> joinNodes(n.levels)
            // `[rev]` 要挂在**首段**的序号位上（`.lst[rev] li`）。
            // 放在串首会被当成整串前缀，重新解析时丢掉。
            is Reverse -> revRender(n.inner)
        }


        /** 把 [REV_MARK] 塞进链里第一个 [Step]，没有段时退化成裸标记。 */
        private fun revRender(n: RuleNode): String = when (n) {
            is Step -> {
                val base = renderSel(n.sel) + (n.picker?.let { renderPicker(it) } ?: "")
                base + REV_MARK
            }
            is Group -> {
                val i = n.levels.indexOfFirst { it is Step }
                if (i < 0) REV_MARK + joinNodes(n.levels)
                else joinNodes(n.levels.toMutableList().also {
                    it[i] = Reverse(it[i])
                })
            }
            is Reverse -> revRender(n.inner)
            is RuleNode.Pick -> REV_MARK + render(n)
        }

        /** 一个段的匹配条件渲染回 CSS 形态。 */
        private fun renderSel(sel: StepSel): String {
            if (sel.xpath || sel.raw == ">") return sel.raw
            if (sel.raw == ">" || sel.raw == ">*") return sel.raw
            if (sel.wildcard && sel.id.isEmpty() && sel.classes.isEmpty() &&
                sel.attrs.isEmpty() && sel.text == null) return "*"
            val sb = StringBuilder()
            // 只有 `:text(…)` 谓词、没有标签/类/id 时，legado 写作 `*:text(…)`。
            if (sel.tag.isEmpty() && sel.text != null) sb.append('*') else if (sel.tag.isNotEmpty()) sb.append(sel.tag)
            if (sel.id.isNotEmpty()) sb.append('#').append(sel.id)
            for (c in sel.classes) sb.append('.').append(c)
            for ((k, v) in sel.attrs) {
                sb.append('[').append(k)
                if (v != null) sb.append('=').append(v)
                sb.append(']')
            }
            if (sel.text != null) sb.append(":text(").append(sel.text).append(')')
            return sb.toString()
        }

        private fun renderPicker(p: Picker): String = when (p) {
            is Picker.One -> "[${p.n}]"
            is Picker.Exclude -> "[!${p.n}]"
            is Picker.ExcludeRange -> "[!" + p.list.joinToString(":") + "]"
            is Picker.Range -> "[" + (p.a?.toString() ?: "") + ":" + (p.b?.toString() ?: "") +
                (if (p.step != 1) ":${p.step}" else "") + "]"
            is Picker.Reverse -> REV_MARK
            is Picker.Contains -> ":contains(${p.text})"
        }

        /** [first] 里取值后缀的 `@` 位置；-1 表示没有。 */
        private fun valueSuffixAt(s: String): Int {
            var at = s.lastIndexOf('@')
            while (at > 0 && (s[at - 1] == '/' || !LegadoSelector.isValueSuffix(s.substring(at + 1)))) {
                at = s.lastIndexOf('@', at - 1)
            }
            return at
        }
    }
}

/** 一层（`@` 分隔）里的若干段（空格分隔）。嵌套表示祖先关系。 */
data class Group(val levels: List<RuleNode>) : RuleNode {
    /** 本层所有段的叶子。渲染与执行都按顺序走。 */
    val steps: List<RuleNode.Step>
        get() = levels.filterIsInstance<RuleNode.Step>()
}

/** 一个段的匹配条件。 */
data class StepSel(
    /** 是否 XPath。XPath 段原样交给 [XPathLite]，不做 CSS 解析。 */
    val xpath: Boolean = false,
    val tag: String = "",
    val id: String = "",
    val classes: List<String> = emptyList(),
    val attrs: List<Pair<String, String?>> = emptyList(),
    /** `text.串`：内文包含该串。 */
    val text: String? = null,
    /** 纯通配（`*`、`children`）。 */
    val wildcard: Boolean = false,
    /** XPath 表达式原文。 */
    val raw: String = "",
) {
    val isEmpty: Boolean
        get() = !xpath && tag.isEmpty() && id.isEmpty() && classes.isEmpty() &&
            attrs.isEmpty() && text == null && !wildcard && raw != ">"

    companion object {
        fun Path(expr: String) = StepSel(xpath = true, raw = expr)

        /**
         * 解析一个段。按顺序剥离，与本执行器历史行为一致：
         * `:text(…)` → `#id` → `.class` → `[attr]` / `[attr=val]` → 剩下的当标签名。
         */
        fun parse(raw: String): StepSel {
            var rest = raw.trim()
            if (rest.isEmpty()) return StepSel()

            var text: String? = null
            Regex(":text\\(([^)]*)\\)").find(rest)?.let { m ->
                text = m.groupValues[1]
                rest = rest.replace(m.value, "")
            }

            var id = ""
            Regex("#([\\w-]+)").find(rest)?.let { m ->
                id = m.groupValues[1]
                rest = rest.replace(m.value, "")
            }

            val classes = Regex("\\.([\\w-]+)").findAll(rest).map { it.groupValues[1] }.toList()
            rest = Regex("\\.[\\w-]+").replace(rest, "")

            val attrs = Regex("\\[([\\w-]+)(?:=(['\"]?)([^\\]]*?)\\2)?]").findAll(rest).map { m ->
                m.groupValues[1] to m.groupValues[3].takeIf { it.isNotEmpty() }
            }.toList()
            rest = Regex("\\[[^\\]]*]").replace(rest, "")

            val tag = rest.trim().trimEnd('*').lowercase()
            if (text == null && id.isEmpty() && classes.isEmpty() && attrs.isEmpty() && tag.isEmpty())
                return StepSel(wildcard = true)
            return StepSel(
                tag = tag, id = id, classes = classes, attrs = attrs, text = text,
                wildcard = tag.isEmpty(),
            )
        }
    }
}

/** 序号选择器。对应 legado 的 `ElementsSingle` 索引语法。 */
sealed interface Picker {
    /** `[N]`：只取第 N 个（支持负数，-1 为倒数第一）。 */
    data class One(val n: Int) : Picker
    /** `[!N]`：去掉第 N 个。 */
    data class Exclude(val n: Int) : Picker
    /** `[!a:b]`：去掉列出的序号（与 `[!N]` 同义，只是多个连写）。 */
    data class ExcludeRange(val list: List<Int>) : Picker
    /** `[a:b:c]`：区间；两端与步长都可省略，都可为负。 */
    data class Range(val a: Int?, val b: Int?, val step: Int) : Picker
    /** `[rev]`：把本层结果倒序。 */
    data object Reverse : Picker
    /** `:contains(字串)`：只留内文包含该串的元素。 */
    data class Contains(val text: String) : Picker
}

package com.watchdl.core

/**
 * 选择器。Tier 0 只支持三种取值方式，见笔记本页 `booksource-engine-specs`。
 *
 * 语法照 so-novel：`@css:.foo > .bar@text`、`@xpath://div/@href`、`@json:$.data.list[*].id`。
 * 省略前缀时按上下文猜：以 `$` 或 `.` 开头当 JSONPath，以 `/` 或 `//` 开头当 XPath，其余当 CSS。
 */
sealed class Selector {

    /**
     * 取值方式。前四个对应 legado `getResultLast` 的枚举：
     * `text` / `textNodes` / `ownText` / `html` / `all`，剩下的当属性名。
     */
    enum class Pick { TEXT, TEXT_NODES, OWN_TEXT, HTML, ALL, ATTR }

    /**
     * CSS 分支。
     *
     * [node] 是解析好的语法树；[query] 只是它的字符串渲染，留给
     * 需要看规则原文的调用方（导入界面、日志）。执行一律走 [node]，
     * 不再把串重新解析一遍。
     */
    data class Css(
        val node: RuleNode?,
        val pick: Pick,
        val attr: String? = null,
        /** 只在 [node] 为 null 时才用得到：语法树解析失败的原文。 */
        val raw: String = "",
    ) : Selector() {
        val query: String get() = node?.let { RuleNode.render(it).trim() } ?: raw

        constructor(query: String, pick: Pick, attr: String? = null) :
            this(RuleNode.parse(query), pick, attr, query)
    }
    data class XPath(val expr: String, val pick: Pick, val attr: String? = null) : Selector()
    data class Json(val path: String) : Selector()

    companion object {
        /**
         * 解析一条规则。
         *
         * @param defaultPick 没写取值后缀时用哪种，搜索标题用 TEXT，简介用 TEXT，链接用 ATTR
         */
        fun parse(raw: String, defaultPick: Pick = Pick.TEXT): Selector? {
            var s = raw.trim()
            if (s.isEmpty()) return null

            // 语法分派由 [RuleNode] 决定；这里只把值取出来填进三个 Selector 分支。
            val node = RuleNode.parse(s) ?: return null
            val (head, p0, attr) = RuleNode.head(node)
            val body = head ?: return null
            val pick = p0 ?: defaultPick

            return when {
                s.startsWith("json:") || s.startsWith("@json:") ->
                    Json(s.substringAfter(':').trim())
                s.startsWith("$") || (s.startsWith("data.") ) -> Json(s)
                s.startsWith("css:") || s.startsWith("@css:") ->
                    Css(node, pick, attr, body.removePrefix("css:").trim())
                s.startsWith("xpath:") || s.startsWith("@xpath:") -> XPath(body.trim(), pick, attr)
                s.startsWith("/") -> XPath(body, pick, attr)
                else -> Css(node, pick, attr, body)
            }
        }
    }
}

/**
 * 极简 HTML 选择器。手表端不引 jsoup，只实现书源真正用到的子集：
 *
 * - `tag`、`.class`、`#id`、`[attr]`、`[attr=value]`、`tag.class#id`
 * - 后代 `A B`、直接子代 `A > B`
 * - 序号 `[0]`（等价 idx=N）、`[!0]`（排除）
 *
 * 不支持伪类、属性前缀匹配、兄弟选择器。规则用到不支持的特性时返回空，
 * 由调用方按「取不到」处理，不猜。
 */
object HtmlSelect {

    /** 当前正在解析的 HTML。`matches` 只拿到 Node，取文本谓词要靠它。
     *  用 ThreadLocal：多源搜索是并发的，共享一个字段会串页。 */
    private val htmlCache = ThreadLocal<String?>()

    /**
     * 取所有匹配元素，返回它们的「起止区间 + 标签名」。
     *
     * 不构造 DOM 树：手表内存有限，按需扫描更省。每次选择器求值都重新扫一遍，
     * 但书源一次只解析一个小页面，代价可接受。
     */
    data class Node(
        val tag: String,
        val start: Int,
        val end: Int,
        val attrs: Map<String, String>,
        /**
         * 没有找到闭合标签。
         *
         * 区间采用保守边界，允许选择边界内的后代。
         */
        val unclosed: Boolean = false,
    )

    /**
     * 找出整篇 HTML 里所有标签的区间（成对标签取到闭合标签结尾）。
     *
     * 关键：找到成对标签后不能直接跳到它的末尾，否则整个文档只会看到最外层一个节点。
     * 正确做法是把节点记下，然后继续往里扫（节点区间已包含内部，不需要重复扫子节点区间）。
     */
    fun nodes(html: String): List<Node> {
        val out = ArrayList<Node>(256)
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) break
            if (lt + 1 < html.length && (html[lt + 1] == '!' || html[lt + 1] == '?')) {
                i = lt + 1; continue
            }
            val gt = html.indexOf('>', lt)
            if (gt < 0) break
            val inner = html.substring(lt + 1, gt).trim()
            if (inner.isEmpty() || inner[0] == '/') { i = gt + 1; continue }

            val selfClose = inner.endsWith("/")
            val tag = inner.takeWhile { it.isLetterOrDigit() || it == '-' }.lowercase()
            if (tag.isEmpty()) { i = gt + 1; continue }
            val attrs = parseAttrs(inner.substring(tag.length))

            val end = if (selfClose || tag in VOID_TAGS) gt + 1 else matchEnd(html, gt + 1, tag)
            val unclosed = end < 0
            // 未闭合节点止于首个同名闭合或祖先闭合，避免吞入外侧节点。
            val realEnd = if (unclosed) {
                unclosedEnd(html, gt + 1, tag)
            } else end
            out.add(Node(tag, lt, realEnd, attrs, unclosed))
            // 继续从开标签之后扫，才能看到子孙节点
            i = gt + 1
        }
        return out
    }

    private val VOID_TAGS = setOf("br", "hr", "img", "input", "meta", "link", "area", "base", "col")

    private fun parseAttrs(s: String): Map<String, String> {
        val m = HashMap<String, String>(4)
        Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*(?:=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+))?")
            .findAll(s).forEach { g ->
                val k = g.groupValues[1].lowercase()
                var v = g.groupValues[2]
                if (v.length >= 2 && (v[0] == '"' || v[0] == '\'')) v = v.substring(1, v.length - 1)
                m[k] = v
            }
        return m
    }

    // shortcut: 嵌套同名闭合有歧义时取保守边界，完整容错需 DOM 解析器。
    private fun unclosedEnd(html: String, from: Int, tag: String): Int {
        val nested = mutableListOf<String>()
        for (token in Regex("<(/?)([a-zA-Z][a-zA-Z0-9-]*)\\b[^>]*>").findAll(html, from)) {
            val name = token.groupValues[2].lowercase()
            if (token.groupValues[1] == "/") {
                if (name == tag) return token.range.last + 1
                val open = nested.lastIndexOf(name)
                if (open < 0) return token.range.first
                while (nested.size > open) nested.removeAt(nested.lastIndex)
            } else if (name !in VOID_TAGS && !token.value.endsWith("/>")) {
                nested.add(name)
            }
        }
        return html.length
    }

    /** 找闭合标签，容忍嵌套同名标签。 */
    private fun matchEnd(html: String, from: Int, tag: String): Int {
        var depth = 1
        var i = from
        val open = "<$tag"
        val close = "</$tag"
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) return -1
            val gt = html.indexOf('>', lt)
            if (gt < 0) return -1
            val head = html.substring(lt, minOf(lt + tag.length + 2, html.length)).lowercase()
            when {
                head.startsWith(close) -> { depth--; if (depth == 0) return gt + 1 }
                head.startsWith(open) && (gt == lt + tag.length + 1 || !html[lt + tag.length + 1].isLetterOrDigit()) -> depth++
            }
            i = gt + 1
        }
        return -1
    }

    /**
     * 求值一个选择器。
     *
     * 查询串先经 [RuleNode.parse] 解析成语法树，再逐段执行。
     * 每段都从上一段的命中里向下找，不是对着整篇扫再取交集：
     * `.result-item a` 里的 `a` 必须真的在那个 `.result-item` 里面。
     *
     * 序号后缀 `[N]` 是**每个父节点各取一个**，不是全局第 N 个：
     * `.item a[0]` 在两个 `.item` 下应该各取第一个 `a`（实测 legado 源靠这个
     * 区分同一行里的书名链接与作者链接）。
     */
    fun select(html: String, query: String, pick: Selector.Pick, attr: String?): List<String> =
        select(html, RuleNode.parse(query), pick, attr)

    /**
     * 语法树版求值。字符串入口 [select] 解析后转调这里。
     *
     * 序号、谓词、倒置全部由 AST 节点直接作用于 DOM 结果，
     * 不再经过 `[rev]` / `[!0:2]` 这类字符串标记。
     */
    /** [Selector.Css] 版：直接用它的语法树，只有解析失败时才回落到原文字符串。 */
    fun select(html: String, sel: Selector.Css, pick: Selector.Pick = sel.pick, attr: String? = sel.attr): List<String> =
        sel.node?.let { select(html, it, pick, attr) }
            ?: select(html, sel.raw, pick, attr)

    fun select(html: String, node: RuleNode?, pick: Selector.Pick, attr: String?): List<String> {
        val hits = nodesOf(html, node) ?: return emptyList()
        // nodesOf 已把 `<body` 之前截掉，取值必须对同一段文本做。
        return hits.mapNotNull { extract(htmlCache.get() ?: html, it, pick, attr) }
    }

    /**
     * 求值语法树，返回命中的 DOM 节点（未取值）。
     *
     * 每段都从上一段的命中里向下找，不是对着整篇扫再取交集：
     * `.result-item a` 里的 `a` 必须真的在那个 `.result-item` 里面。
     *
     * 序号后缀 `[N]` 是**每个父节点各取一个**，不是全局第 N 个：
     * `.item a[0]` 在两个 `.item` 下应该各取第一个 `a`。
     *
     * @return null 表示这条规则本执行器不处理（XPath / 含取值后缀）。
     */
    fun nodesOf(html: String, node: RuleNode?): List<Node>? {
        if (node == null) return null
        val bodyStart = html.indexOf("<body")
        val raw = if (bodyStart < 0) html else html.substring(bodyStart)
        htmlCache.set(raw)
        val steps = flatten(node) ?: return null

        val all = nodes(raw)
        // 倒置是整条链的属性：AST 上的 Reverse 节点，或任一段的 Picker.Reverse。
        val rev = node is RuleNode.Reverse || steps.any { it.picker === Picker.Reverse }

        var current: List<Node> = emptyList()
        for ((idx, step) in steps.withIndex()) {
            if (idx == 0) {
                current = applyPicker(raw, all.filter { matches(it, step.sel) }, step.picker)
            } else {
                // 逐个父节点向下找，序号在每个父节点的子集里单独生效
                val next = ArrayList<Node>()
                for (parent in current) {
                    val inside = all.filter { n -> n.start > parent.start && n.end <= parent.end }
                    next.addAll(applyPicker(raw, inside.filter { matches(it, step.sel) }, step.picker))
                }
                current = next
            }
            if (current.isEmpty()) return emptyList()
        }
        return if (rev) current.reversed() else current
    }

    /**
     * 把语法树压成线性段序列。
     *
     * 层与层之间是祖先关系（`@tag.a@text` → 两层），与段之间一样按「向内找」处理，
     * 所以拍平即可。遇到 XPath 或取值后缀返回 null：本函数不执行它们。
     */
    private fun flatten(n: RuleNode): List<RuleNode.Step>? {
        // 取值后缀由调用方的 pick/attr 决定，结构上直接跳过。
        // 纯取值后缀（`@text`）因此压成空链，等同于「不选，整段就是结果」。
        val out = ArrayList<RuleNode.Step>()
        fun walk(x: RuleNode): Boolean = when (x) {
            is RuleNode.Step -> { if (x.sel.raw != ">") out.add(x); !x.sel.xpath }
            is RuleNode.Reverse -> walk(x.inner)
            is Group -> x.levels.all { walk(it) }
            is RuleNode.Pick -> true
        }
        return if (walk(n)) out else null
    }

    /** 应用序号选择器。越界就返回空，不猜。 */
    private fun applyPicker(html: String, items: List<Node>, p: Picker?): List<Node> {
        if (p == null) return items
        val len = items.size
        fun norm(i: Int) = if (i < 0) i + len else i
        return when (p) {
            is Picker.One -> listOfNotNull(items.getOrNull(norm(p.n)))
            is Picker.Exclude -> items.filterIndexed { i, _ -> i != norm(p.n) }
            is Picker.Reverse -> items.reversed()
            // `:contains()` 按内文筛，不按下标，所以不受 `len` 影响。
            is Picker.Contains -> items.filter { HtmlText.plain(innerHtml(html, it)).contains(p.text) }
            is Picker.ExcludeRange -> {
                val drop = p.list.map { norm(it) }.toSet()
                items.filterIndexed { i, _ -> i !in drop }
            }
            is Picker.Range -> {
                // legado 区间左闭右开：`[0:3]` 取第 0、1、2 个，不含第 3 个。
                // 终点省略（`[2:]`）表示到末尾。逆序区间（`[-1:0]`）靠负 step。
                val step = if (p.step == 0) 1 else p.step
                val from = p.a?.let { norm(it) } ?: if (step > 0) 0 else len - 1
                val to = p.b?.let { norm(it) } ?: if (step > 0) len else -1
                val out = mutableListOf<Node>()
                var i = from
                if (step > 0) { while (i < to) { items.getOrNull(i)?.let { out.add(it) }; i += step } }
                else { while (i > to) { items.getOrNull(i)?.let { out.add(it) }; i += step } }
                out
            }
        }
    }

    /**
     * 取一段 HTML 里的纯文本节点（`<p>a<span>c</span>b</p>` → `[a, c, b]`）。
     *
     * 不能拿 `[^<>]+` 扫全文：那样会把**标签名本身**也当文本，
     * `span`、`/span` 混进正文。只取 `>` 与 `<` 之间的内容。
     */
    internal fun textNodesOf(inner: String): List<String> =
        Regex("(?s)>([^<>]+)<").findAll(">$inner<")
            .map { HtmlText.plain(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /** 节点的纯文本是否包含给定字串（legado `text.` 的判定）。 */
    private fun containsText(node: Node, want: String): Boolean {
        val body = htmlCache.get() ?: return false
        val inner = body.substring(minOf(node.start, body.length), minOf(node.end, body.length))
        return HtmlText.plain(inner).contains(want)
    }

    private fun matches(node: Node, sel: StepSel): Boolean {
        if (sel.isEmpty) return true
        // `text.串`：内文**包含**该串即命中。
        if (sel.text != null && !containsText(node, sel.text)) return false
        // #id
        if (sel.id.isNotEmpty() && node.attrs["id"] != sel.id) return false
        // .class（可多个）
        if (sel.classes.isNotEmpty()) {
            val cls = node.attrs["class"] ?: return false
            val have = cls.split(Regex("\\s+"))
            if (sel.classes.any { it !in have }) return false
        }
        // [attr] / [attr=value]
        for ((k, v) in sel.attrs) {
            val actual = node.attrs[k] ?: return false
            if (v != null && actual != v) return false
        }
        return sel.tag.isEmpty() || node.tag == sel.tag
    }

    private fun extract(html: String, node: Node, pick: Selector.Pick, attr: String?): String? {
        val outer = html.substring(node.start, minOf(node.end, html.length))
        return when (pick) {
            Selector.Pick.ALL -> outer
            Selector.Pick.HTML -> {
                // 官方 `html`：先剔掉 script/style，再给 outerHtml
                outer.replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
                    .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
            }
            Selector.Pick.ATTR -> attr?.let { node.attrs[it] }
            // ownText：只要本节点的直接文本，不含子元素里的（官方 element.ownText()）
            Selector.Pick.OWN_TEXT -> {
                val t = HtmlText.plain(innerHtml(html, node).replace(Regex("(?s)<[^>]*>.*"), ""))
                t.ifBlank { HtmlText.plain(directTextOnly(html, node)) }
            }
            // textNodes：逐个子文本节点，用换行拼（官方 joinToString("\n")）
            Selector.Pick.TEXT_NODES -> {
                val parts = textNodesOf(innerHtml(html, node))
                if (parts.isEmpty()) fallbackText(html, node) else parts.joinToString("\n")
            }
            Selector.Pick.TEXT -> {
                val inner = innerHtml(html, node)
                val text = HtmlText.plain(inner)
                // 取文本为空时回退：这个元素里通常只有一张图，书名写在 img 的 alt 上。
                // legado 就是这么做的（实测玄幻阁吧的书名在 <img alt="斗破苍穹">）。
                if (text.isNotBlank()) text else fallbackText(html, node)
            }
        }
    }

    /** 取本节点的直接文本（不递归子元素）。 */
    internal fun ownTextOf(html: String, node: Node): String = directTextOnly(html, node)

    private fun directTextOnly(html: String, node: Node): String {
        val inner = innerHtml(html, node)
        var depth = 0
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '<' -> { val e = inner.indexOf('>', i); i = if (e < 0) inner.length else e + 1; depth++ }
                else -> {
                    if (depth == 0) sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** 文本为空时的回退：取内部第一个 img 的 alt 或 title 属性。 */
    private fun fallbackText(html: String, node: Node): String {
        val inner = innerHtml(html, node)
        Regex("<img[^>]*>", RegexOption.IGNORE_CASE).find(inner)?.let { m ->
            Regex("(?:alt|title)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                .find(m.value)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?.let { return Downloader.decodeEntities(it) }
        }
        // 再退一步：元素自身的 title 属性
        node.attrs["title"]?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    /** 元素内部的 HTML（去掉自己的开闭标签）。 */
    fun innerHtml(html: String, node: Node): String {
        val open = html.indexOf('>', node.start)
        if (open < 0) return ""
        val body = html.substring(open + 1, minOf(node.end, html.length))
        // 去掉结尾的 </tag>
        val closeAt = body.lastIndexOf("</")
        return if (closeAt >= 0 && body.substring(closeAt).startsWith("</")) body.substring(0, closeAt) else body
    }
}

/** HTML 转纯文本。规则取值时用，比结构化的 [Downloader.htmlToText] 简单。 */
object HtmlText {
    fun plain(html: String): String {
        var s = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|tr|h[1-6])>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = Downloader.decodeEntities(s)
        return s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}

/**
 * 极简 XPath。只支持书源常用的绝对路径与 `//` 后代查找：
 * `//div[@class='x']/a/@href`、`//ul/li`。
 *
 * 属性谓词只认等值。复杂谓词（`contains()`、位置函数）直接返回空。
 */
object XPathLite {

    fun select(html: String, expr: String, pick: Selector.Pick, attr: String?): List<String> {
        val path = expr.trim()
        if (path.contains("contains(") || path.contains("position()") || path.contains("text()[")) return emptyList()

        // 末段是属性取值：/@href
        var wantAttr: String? = attr
        var body = path
        Regex("/@([\\w-]+)$").find(body)?.let {
            wantAttr = it.groupValues[1]
            body = body.substring(0, it.range.first)
        }
        val steps = body.split('/').filter { it.isNotBlank() && it != "." }
        if (steps.isEmpty()) return emptyList()

        val all = HtmlSelect.nodes(html)
        var current: List<HtmlSelect.Node> = emptyList()
        for ((i, stepRaw) in steps.withIndex()) {
            val step = stepRaw.replace(Regex("\\[(\\d+)]"), "[idx=$1]")
            val (tag, preds) = parseStep(step)
            val next = if (i == 0) all else descendants(all, current)
            current = next.filter { n ->
                (tag.isEmpty() || tag == "*" || n.tag == tag) && preds.all { ok(html, n, it) }
            }
            // 位置谓词 `[N]`：在**本步的结果集**里取第 N 个（0 起，负数倒数）。
            // 以前 ok() 对 Index 恒 true，`//div[2]` 返回全部 div。
            preds.filterIsInstance<Pred.Index>().lastOrNull()?.let { ix ->
                val len = current.size
                val at = if (ix.i < 0) ix.i + len else ix.i
                current = listOfNotNull(current.getOrNull(at))
            }
        }
        return current.mapNotNull { n ->
            when {
                wantAttr != null -> n.attrs[wantAttr]
                // 与 extract 保持一致：ALL 保留原文，HTML 剔 script/style
                pick == Selector.Pick.ALL -> html.substring(n.start, minOf(n.end, html.length))
                pick == Selector.Pick.HTML -> html.substring(n.start, minOf(n.end, html.length))
                    .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
                    .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
                pick == Selector.Pick.ATTR -> n.attrs[attr ?: "href"]
                pick == Selector.Pick.OWN_TEXT -> HtmlText.plain(HtmlSelect.ownTextOf(html, n))
                pick == Selector.Pick.TEXT_NODES -> HtmlSelect.textNodesOf(HtmlSelect.innerHtml(html, n))
                    .joinToString("\n")
                    .ifBlank { HtmlText.plain(HtmlSelect.innerHtml(html, n)) }
                else -> HtmlText.plain(HtmlSelect.innerHtml(html, n))
            }
        }
    }

    private fun parseStep(step: String): Pair<String, List<Pred>> {
        val preds = mutableListOf<Pred>()
        val rest = Regex("\\[([^\\]]*)]").replace(step) { m ->
            val p = m.groupValues[1]
            when {
                p.startsWith("@") -> {
                    val eq = p.indexOf('=')
                    if (eq > 0) {
                        val k = p.substring(1, eq).trim()
                        val v = p.substring(eq + 1).trim().trim('\'', '"')
                        preds.add(Pred.Attr(k, v))
                    }
                    ""
                }
                p.startsWith("idx=") -> { preds.add(Pred.Index(p.removePrefix("idx=").toIntOrNull() ?: 0)); "" }
                p.all { it.isDigit() } -> { preds.add(Pred.Index(p.toInt())); "" }
                else -> ""
            }
        }
        return rest.trim().lowercase() to preds
    }

    private sealed interface Pred {
        data class Attr(val key: String, val value: String) : Pred
        data class Index(val i: Int) : Pred
    }

    private fun ok(html: String, n: HtmlSelect.Node, p: Pred): Boolean = when (p) {
        is Pred.Attr -> {
            val cls = n.attrs[p.key] ?: return false
            cls.split(Regex("\\s+")).contains(p.value) || cls == p.value
        }
        is Pred.Index -> true   // 位置在下面单独处理
    }

    /** 后代节点：所有 start 落在父节点区间内的节点。 */
    private fun descendants(all: List<HtmlSelect.Node>, parents: List<HtmlSelect.Node>): List<HtmlSelect.Node> =
        all.filter { n -> parents.any { p -> n.start in (p.start + 1) until p.end } }
}

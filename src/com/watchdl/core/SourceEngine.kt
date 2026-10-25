package com.watchdl.core

/**
 * 书源执行器。把一条 [BookSourceRule] 跑成实际的搜索、目录、正文。
 *
 * 网络由调用方注入（[fetch]），这样纯逻辑可以脱离 Android 自检。
 */
class SourceEngine(
    val rule: BookSourceRule,
    /** 目录专用请求，最后一参是剩余总预算（毫秒）。 */
    private val fetchToc: ((String, String, String?, Map<String, String>, Int) -> String)? = null,
    /**
     * 请求。由调用方注入，便于纯逻辑自检。
     *
     * 实现方应当处理重定向。注意 Java 的 `instanceFollowRedirects` **不会**
     * 跟随 HTTP→HTTPS 的跨协议跳转（会直接返回 301），很多小说站正好是这种，
     * 所以必须自己跟。见 [SourceEngine.normaliseMethod] 与手机端的 `Http`。
     */
    private val fetch: (url: String, method: String, body: String?, headers: Map<String, String>) -> String,
) {
    constructor(
        rule: BookSourceRule,
        fetch: (String, String, String?, Map<String, String>) -> String,
    ) : this(rule, null, fetch)

    /** 搜索。返回命中列表。 */
    fun search(keyword: String, page: Int = 1): List<BookHit> {
        // 字段里的 @js: 也调 source.getKey()，这里统一设好线程本地。
        JsEngine.setSourceKey(rule.host)
        // 关键词进 URL 必须编码：中文直接塞进去，Java 构造 URL 时看着能过，
        // 但 openConnection 会报 `Illegal character in URL`。实测 780 条源里
        // 有多条因此失败。
        val ctx = mapOf(
            "key" to urlEncode(keyword),
            "keyword" to urlEncode(keyword),
            "page" to page.toString(),
        )
        // searchUrl 是脚本时，在请求时算 URL。脚本里常用 key/keyword/page/baseUrl。
        val url = rule.search.urlJs?.let { js ->
            // 脚本里也会写 `baseUrl+"/so/{{key}}.html"`。`{{key}}` 直接交给 Rhino
            // 是非法语法，必须先把模板变量渲染掉（XIU2 起点中文这样写）。
            val code = Template.render(js, ctx)
            JsEngine.eval(rule.jsLib?.let { "$it\n$code" } ?: code, JsEngine.Env(
                baseUrl = rule.host,
                result = "",
                vars = mutableMapOf("key" to keyword, "keyword" to keyword, "page" to page.toString()),
                // 书源 searchUrl 常用 {{source.getKey()}} 拼本站地址。
                sourceKey = rule.host,
            )).trim()
        } ?: rule.expand(rule.search.url, ctx)
        // 脚本算出的 URL 可能是相对路径（实测七猫小说返回 `/api/v5/search/...`），
        // 直接交给 Http 会报 `MalformedURLException: no protocol`。用源主机补全。
        val absUrl = if (url.startsWith("/") && rule.host.isNotBlank())
            rule.host.trimEnd('/') + url else url
        val body = rule.search.body?.let { Template.render(it, ctx) }
        val raw = fetch(absUrl, normaliseMethod(rule.search.method), body, rule.search.headers)

        // 结果列表：先取出每一项的原文，字段再**相对于该项**求值。
        //
        // 这是 legado 的语义：`bookList: $.data[*]` 取数组，`name: $.novelName`
        // 是相对数组元素再取字段。以前我对整篇文档求值，42 条 JSON 源全废。
        val listSel = rule.search.list
            ?: return searchFlat(raw)
        val items = evalList(raw, listSel.selector, url, rule.jsLib)
        if (items.isEmpty()) return emptyList()

        // 字段逐项求值。取不到就是空串，不报错 —— 单个字段缺失在书源里很常见。
        fun column(fr: FieldRule): List<String> = items.map { fr.eval(it, jsLib = rule.jsLib).firstOrNull() ?: "" }

        val names = column(rule.search.name)
        val links = column(rule.search.link)
        val authors = column(rule.search.author)
        val intros = column(rule.search.intro)

        return items.indices.mapNotNull { i ->
            val link = absolute(resolveUrlTemplate(links[i], items[i]))
            if (link.isBlank()) return@mapNotNull null
            val name = plainText(names[i])
            // 书名为空的项直接丢。
            //
            // 不是「防御性代码」而是必须：很多源在搜不到时会返回结构合法但
            // 内容为空壳的响应（实测南极小说返回 `data.state[0].moduleName
            // =NotSupport` 的提示对象、疯读小说返回 `books: []`）。
            // 那些壳子 `list` 能匹配上，但 name 取出来是空串。
            // 留空会让 98 个源的结果被归成同一本《》，去重和选书全部失效。
            if (name.isBlank()) return@mapNotNull null
            BookHit(
                bookId = link,
                name = name,
                // 作者/简介取不到留空就行：界面有处空位可放，不影响归并。
                author = plainText(authors[i]),
                abstractText = plainText(intros[i]),
            )
        }
    }

    /** 把可能含 HTML 标签 / 实体的文本转成纯文本。纯文本原样返回。 */
    private fun plainText(s: String): String {
        if (s.isEmpty()) return s
        // 没有尖括号也不是实体就不用碰，省掉大部分字段的解析开销。
        if (!s.contains('<') && !s.contains('&')) return s
        return HtmlText.plain(s).trim()
    }

    /**
     * legado 的真值判定（`String?.isTrue()`），isVolume / isVip / isPay 共用。
     *
     * 空 / `null` / `false` / `no` / `not` / `0` / `0.0`（不区分大小写）为假，其余全为真。
     * 不能写 `== "true"`：isVolume 存的是选择器，求值结果可能是任意文本。
     */
    private fun isTrue(s: String?): Boolean {
        if (s.isNullOrBlank() || s == "null") return false
        return !s.trim().matches(Regex("(?i)^(?:false|no|not|0|0\\.0)$"))
    }

    /**
     * 把字段值里的 `{{$.xxx}}` 换成该结果项的实际值。
     * 例：`/novel/{{$.novelId}}?isSearch=1`。
     */
    private fun resolveUrlTemplate(tpl: String, item: String): String {
        if (!tpl.contains("{{")) return tpl.trim()
        // 用 `\}\}` 而不是 `[^}]*` 收尾：后者会把 `{{$.book_id}}` 的非贪婪匹配
        // 停在第一个 `}`，只吃掉 `{{$.book_id}`，**留下一根 `}`**。
        // 实测这个 bug 让拼出的书链接全部带上尾巴：
        // `...?book_id=742}` —— 不是合法 URL，点进去必然失败。
        return Regex("\\{\\{(.*?)\\}\\}").replace(tpl) { m ->
            val expr = m.groupValues[1].trim()
            if (expr.startsWith("$")) JsonPathLite.one(item, expr) ?: ""
            else Template.evalExpr(expr, emptyMap())
        }.trim()
    }

    /** 没有列表选择器的源：退回整篇求值（少数简单源）。 */
    private fun searchFlat(raw: String): List<BookHit> {
        val names = rule.search.name.eval(raw, list = true)
        val links = rule.search.link.eval(raw, list = true)
        val authors = rule.search.author.eval(raw, list = true)
        val intros = rule.search.intro.eval(raw, list = true)
        return names.indices.mapNotNull { i ->
            val link = links.getOrNull(i) ?: return@mapNotNull null
            val name = plainText(names[i])
            // 同 searchStructured：空书名项会归成同一本《》，必须丢。
            if (name.isBlank()) return@mapNotNull null
            BookHit(absolute(resolveUrlTemplate(link, raw)), name,
                plainText(authors.getOrNull(i) ?: ""), plainText(intros.getOrNull(i) ?: ""))
        }
    }

    /**
     * 按列表选择器取出每一项的原文。
     *
     * JSON 源用 [JsonPathLite]；HTML 源用 [HtmlSelect] 取节点的 HTML 原文
     * （外层要保留，因为字段选择器还要在它里面找）。
     *
     * 关键：选择器可能直接指向**数组本身**（如 `$.data`）而不是元素（`$.data[*]`）。
     * 实测酷我小说就是这么写的，返回整个数组时必须展开成多项，
     * 否则后续字段选择器会把整个数组当成一项去找，永远找不到。
     */
    private fun evalList(raw: String, sel: FieldRule, baseUrl: String = "", jsLib: String? = null): List<String> =
        evalList(raw, sel.selector, baseUrl, jsLib)

    /** [sel] 的正文版：目录复用同一套「列表选择器」语义。 */
    private fun evalList(raw: String, sel: String, baseUrl: String = "", jsLib: String? = null): List<String> {
        // 列表选择器也要展开 `||` 备选链。
        //
        // 以前直接 `Selector.parse(整串)`，`class.excerpt||class.post||...`
        // 会被当成一个 CSS 选择器（`||` 不是 CSS 语法），命中恒为 0。
        // 实测这条 bug 让 52书库这类多头备选的源全部“搜索无结果”。
        val chain = LegadoSelector.chain(sel.trim())
        fun one(seg: String): List<String> {
            // 列表也可以是脚本：番茄系列用它把搜索响应转成条目数组。
            // 脚本靠 `baseUrl` 里的 q/offset 拿关键词，所以必须传进来。
            FieldRule.jsCodeOf(seg)?.let { code ->
                val full = jsLib?.let { "$it\n$code" } ?: code
                return runCatching {
                    listOf(JsEngine.eval(full, JsEngine.Env(
                        result = raw, baseUrl = baseUrl, sourceKey = rule.host)))
                }.getOrElse { emptyList() }
            }
            val parsed = Selector.parse(seg)
            return when (parsed) {
                is Selector.Json -> JsonPathLite.select(raw, parsed.path)
                is Selector.Css -> HtmlSelect.select(raw, parsed, Selector.Pick.HTML, null)
                is Selector.XPath -> XPathLite.select(raw, parsed.expr, Selector.Pick.HTML, null)
                null -> emptyList()
            }
        }
        // 连接方式与 FieldRule.eval 一致，见 LegadoSelector.chain
        val got = when (chain.join) {
            LegadoSelector.Join.Or ->
                chain.segments.firstNotNullOfOrNull { one(it).takeIf { r -> r.isNotEmpty() } } ?: emptyList()
            LegadoSelector.Join.And -> chain.segments.flatMap { one(it) }
            LegadoSelector.Join.Merge -> chain.segments.firstOrNull()?.let { one(it) } ?: emptyList()
        }
        // 展开被当成单个值的数组
        return got.flatMap { v ->
            val t = v.trim()
            if (t.startsWith("[")) TinyJson.allValues(t) else listOf(v)
        }
    }

    /**
     * legado 的 `ruleBookInfo.init`：把书页响应收窄到子对象，作为后续字段的 result。
     *
     * 取不到就原样返回——宁可维持旧行为，也不要把正文弄没。
     */
    private fun narrow(body: String): String {
        val ini = rule.toc.initRule ?: return body
        return runCatching { ini.eval(body, jsLib = rule.jsLib).firstOrNull() }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: body
    }

    /** 目录。
     *
     * [book] 是搜索得到的书链接（绝对）。规则没给目录 URL 时直接请求它；
     * 给了则用模板展开（`{{id}}` 由调用方在 ctx 里提供）。
     */
    fun toc(book: String): BookInfo = toc(book, TOC_BUDGET_MS)

    internal fun toc(book: String, budgetMs: Long): BookInfo {
        val tocDeadline = System.nanoTime() + budgetMs * 1_000_000
        fun remainingMs(): Int = ((tocDeadline - System.nanoTime()) / 1_000_000)
            .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        fun request(url: String): String {
            val left = remainingMs()
            if (left <= 0) throw java.io.IOException("目录请求超时")
            return fetchToc?.invoke(url, normaliseMethod(rule.toc.method), null, rule.toc.headers, left)
                ?: fetch(url, normaliseMethod(rule.toc.method), null, rule.toc.headers)
        }
        JsEngine.setSourceKey(rule.host)
        val ctx = mapOf("id" to book, "bookId" to book, "url" to book, "key" to book)
        // 第一步：拿书页。
        // 书页不只是目录页的跳板，tocUrl 规则就要在它上面求值。
        val bookUrl = absolute(book)
        val tocUrlRule = rule.toc.tocUrlRule
        var url = if (rule.toc.url.isNotBlank()) {
            absolute(rule.expand(rule.toc.url, ctx), bookUrl)
        } else bookUrl
        var raw: String
        if (tocUrlRule != null && rule.toc.url.isBlank()) {
            // 书籍页可能与当前 url 相同，不重复请求。
            val bookRaw0 = request(bookUrl)
            // legado 的 ruleBookInfo.init：把书页响应收窄到子对象，作为后续字段的 result。
            // 必须在求值 tocUrl **之前**做，否则 `{{$.bookId}}` 只能在顶层找，取到空。
            val bookRaw = narrow(bookRaw0)
            val tocPath = runCatching { tocUrlRule.eval(bookRaw, jsLib = rule.jsLib).firstOrNull() }
                .getOrNull()?.trim().orEmpty()
            if (tocPath.isNotEmpty()) {
                val resolved = absolute(rule.expand(tocPath, ctx, bookRaw), bookUrl)
                // 只当求得不同地址时，才去请求真正的目录页；否则直接用书页。
                if (resolved != bookUrl && resolved.isNotEmpty()) {
                    url = resolved
                    raw = request(url)
                } else raw = bookRaw
            } else raw = bookRaw
        } else {
            raw = narrow(request(url))
        }


        // chapterList 自己返回**对象数组**时（实测 5 条源），每个对象就是一个项作用域：
        // chapterList 的脚本列出 `{n:…,u:…}`，chapterName / chapterUrl 再在该对象内
        // 取 `$.n` / `$.u` —— 这是 legado 的语义。
        // 以前名字和链接都拿整段响应求值，`$.n` 取不到，目录为空。
        // chapterList 是字符串列表（CSS / 带 @text 的脚本）时走旧路径，一个字符不改。
        val items = tocItemScope(raw)
        val names: MutableList<String>
        val links: MutableList<String>
        val vols: MutableList<String>
        if (items != null) {
            // 对象里那一格名由字段规则自己指定（`$.n` → 取 n）。取不到就是空串。
            fun column(fr: FieldRule) = items.map { obj ->
                val rule0 = fr.selector.trim()
                objKey(rule0)?.let { k ->
                    TinyJson.path(obj, k) ?: if (rule0.startsWith("$")) "" else fr.eval(obj, jsLib = rule.jsLib).firstOrNull() ?: ""
                } ?: (fr.eval(obj, jsLib = rule.jsLib).firstOrNull() ?: "")
            }
            names = column(rule.toc.name).toMutableList()
            links = column(rule.toc.link).toMutableList()
            vols = (rule.toc.isVolume?.let { runCatching { it.eval(raw, list = true) }.getOrNull() }
                ?: emptyList()).toMutableList()
        } else {
            names = rule.toc.name.eval(raw, list = true).toMutableList()
            links = rule.toc.link.eval(raw, list = true).toMutableList()
            // isVolume 是本轮新增的：卷名节点没有章节链接，不能当章节丢弃。
            vols = (rule.toc.isVolume?.let { runCatching { it.eval(raw, list = true) }.getOrNull() }
                ?: emptyList()).toMutableList()
        }
        // 章节链接一律以**目录页自身**为基准拼绝对地址。
        // 用 rule.host（站点根）会把 /book/123/1.html 拼成 /1.html，整本书全 404。
        val baseOf = { u: String -> absolute(u, url) }

        // 目录分页（legado 的 nextTocUrl）。
        //
        // 语义：规则返回的是**后续目录页的 URL 列表**，不是章节链接。
        // legado 的分支：0 个停；恰 1 个串行链式翻；≥2 个并发一次全取且不再往下递归。
        // 以前只取 firstOrNull()，多值源（如 `.pagination a@href` 返回全部页码）
        // 只能拿到第 2 页，后面全丢 —— 这是「整本目录取不到」的主要成因。
        val nextRule = rule.toc.nextTocUrl
        if (nextRule != null) {
            val visited = HashSet<String>().apply { add(url) }
            // 第一轮：拿当前页给出的后续页列表。
            val firstNext = runCatching { nextRule.eval(raw, jsLib = rule.jsLib) }
                .getOrDefault(emptyList())
            val pending = firstNext.map { it.trim() }.filter { it.isNotEmpty() }
                .map { absolute(rule.expand(it, ctx), url) }
                .filter { it !in visited }
            val isMulti = pending.size >= 2

            // 总页数封顶。以前只封了内层循环，外层 `pending` 有多少就发多少次请求：
            // `.pagination a@href` 这种规则会返回全部页码，一千章的书写上百个 URL，
            // 于是联网几百次，用户卡在「正在取目录…」直到杀掉进程。
            // 现在内外层共用一份预算，并且多取几页也够用 —— 正常站点的目录就几页。
            var budget = MAX_PAGES
            pending.take(MAX_PAGES).forEach { start ->
                if (budget <= 0) return@forEach
                var nextUrl = start
                var chain = 0
                // 单值分支：继续链式翻页；多值分支：每页只取其自身列表，不再递归。
                while (nextUrl.isNotEmpty() && nextUrl !in visited && chain < MAX_PAGES) {
                    if (budget-- <= 0 || remainingMs() <= 0) break
                    visited += nextUrl
                    val pageRaw = runCatching {
                        request(nextUrl)
                    }.getOrNull() ?: break
                    val n2 = rule.toc.name.eval(pageRaw, list = true)
                    if (n2.isEmpty()) break
                    names += n2
                    links += rule.toc.link.eval(pageRaw, list = true)
                    rule.toc.isVolume?.let { v ->
                        runCatching { vols += v.eval(pageRaw, list = true) }
                    }
                    chain++
                    if (isMulti || chain >= MAX_PAGES) break
                    // 单值：从刚打开的页里继续找下一页。
                    nextUrl = runCatching { nextRule.eval(pageRaw, jsLib = rule.jsLib) }
                        .getOrDefault(emptyList()).firstOrNull()?.trim()?.let { absolute(rule.expand(it, ctx), nextUrl) }
                        .orEmpty()
                }
            }
        }

        val chapters = names.indices.mapNotNull { i ->
            val rawLink = links.getOrNull(i)?.trim().orEmpty()
            val title = plainText(names[i])
            // 空链接的两种兑底，与 legado 一致：
            //   isVolume 为真 → 造一个唯一假 URL 当卷名节点；
            //   isVolume 为假 → 指向目录页（数据脏，但不能丢，丢了章节就少一章）。
            val isVol = vols.getOrNull(i)?.let { isTrue(it) } ?: false
            val link = when {
                rawLink.isNotEmpty() -> baseOf(rawLink)
                isVol -> "$url#$i"
                else -> url
            }
            if (link.isEmpty()) return@mapNotNull null
            ChapterRef(itemId = link, title = title)
        }
        // 按章节号排序 + 去重，见 ChapterOrder 的注释。
        return BookInfo(bookId = book, name = "", author = "", chapters = ChapterOrder.normalise(chapters))
    }

    /**
     * 取「chapterList 直接返回对象数组」的项作用域；不适用时返回 null。
     *
     * 判据：chapterList 规则是脚本，且它的结果解析出成 JSON 对象数组。
     * 字符串列表的源在这里拿到空表（TinyJson.objectItems 不认字符串数组）。
     */
    private fun tocItemScope(raw: String): List<String>? {
        val lst = rule.toc.sourceList ?: return null
        // evalList 已经把脚本返回的数组摊平成元素（末尾的 TinyJson.allValues），
        // 所以这里拿到的是「每项一个对象的 JSON 原文」。再确认一遍都是对象 ——
        // 字符串数组（普通脚本目录）不是对象，照样返回 null 走旧路径。
        val ev = evalList(raw, lst, "", rule.jsLib)
        if (ev.isEmpty() || ev.any { !it.trim().startsWith("{") }) return null
        return ev
    }

    /**
     * 取出字段规则指向的对象键，两种写法都要认：
     *
     * - `$.n`（中文万维、中文书城这类）、`$.a.b` 取嵌套键；
     * - 裸键名 `name` / `url`（全本小说）。这条源的 chapterList 脚本
     *   直接 `$.chapters.map(...)` 造出 `{name, url}`，字段规则就写裸键名。
     *
     * 裸键名必须限定成「不含选择器语法的单段标识符」，否则普通 CSS 规则
     * （`a@href`、`.x li`）会被误当成键名，把正常源的目录吃掉。
     */
    private fun objKey(sel: String): String? {
        val t = sel.trim()
        if (t.isEmpty()) return null
        // 兜底链 `||` / `&&` 只取第一段（与 FieldRule 的求值顺序一致）。
        val head = t.substringBefore("||").substringBefore("&&").trim()
        if (head.startsWith("$.")) {
            return head.substring(2).substringBefore('|').substringBefore('&')
                .takeIf { it.isNotEmpty() && !it.contains('@') && !it.contains(' ') }
        }
        // 裸键名：只认 [A-Za-z_][A-Za-z0-9_]*，单个标识符。
        return head.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
    }

    /** 正文。返回 HTML 或文本原文。
     *
     * [chapterUrl] 是目录里取到的章节链接（绝对）。规则没给正文 URL 时直接请求它。
     */
    fun content(chapterUrl: String): String {
        JsEngine.setSourceKey(rule.host)
        val ctx = mapOf("id" to chapterUrl, "url" to chapterUrl, "chapterUrl" to chapterUrl)
        val url = if (rule.content.url.isBlank()) absolute(chapterUrl) else absolute(rule.expand(rule.content.url, ctx))
        val raw = fetch(url, normaliseMethod(rule.content.method), null, rule.content.headers)
        // 选择器为空说明规则没提供（例如用了脚本），直接返回原文，交给上层处理
        val body = if (rule.content.content.selector.isBlank()) raw else contentBody(raw)
        return applyReplaceRegex(paged(url, raw, body))
    }

    /**
     * 跟正文分页（legado 的 `ruleContent.nextContentUrl`）。
     *
     * 很多站的正文分好几页，第一页末尾给个「下一页」链接。不跟的话正文看着被截断。
     * 每页都从**该页的完整 HTML** 里取下一页链接，取不到或已在 visited 里就停。
     *
     * [firstUrl] / [firstRaw] 是第一页，已经在 [content] 里请求过了，不重复请求。
     * 上限 [MAX_PAGES] 页 —— 防站点的「下一页」绕回自身造成死循环。
     */
    /**
     * 正文取值。
     *
     * 正文是多段的：`id.chaptercontent@p@html` 会命中 54 个 `<p>`，
     * 只取第一项就只剩开头一句（实测神话之后这个源每章只剩 16 字）。
     * 命中多项时换行拼接 —— 但仅限 HTML/文本取值；`@text` 这类单值后缀还是取一个。
     */
    private fun contentBody(raw: String): String {
        val rule = rule.content.content
        val all = runCatching { rule.eval(raw, list = true) }.getOrNull().orEmpty()
        // 规则给了选择器却一条没命中，返回空串，让上层记 FAILED。
        // 以前返回 raw，于是站点改版/选择器失效时每章都把整页 HTML
        //（导航、广告、版权）存下，并且因为 html.isBlank() 不成立而记 DONE。
        // 结果是全书「成功 N 失败 0」，导出文本全是菜单，用户要打开文件才发现。
        if (all.isEmpty()) return ""
        if (all.size == 1) return all[0]
        // 选的是链接/图片地址就只取第一个，拼起来没有意义。
        // 不能只看规则原文（可能是 `href` 后的脚本），看结果长相更准。
        if (all.all { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("//") || it.startsWith("/") }) {
            return all[0]
        }
        return all.joinToString("\n")
    }

    private fun paged(firstUrl: String, firstRaw: String, firstBody: String): String {
        val nextRule = rule.content.nextContentUrl ?: return firstBody
        val sb = StringBuilder(firstBody)
        val visited = HashSet<String>().apply { add(firstUrl) }
        var curRaw = firstRaw
        var pages = 1
        while (pages < MAX_PAGES) {
            // 链接相对当前页求值（legado 语义）。取不到就结束。
            val next = runCatching { nextRule.eval(curRaw, jsLib = rule.jsLib).firstOrNull() }
                .getOrNull()?.trim().orEmpty()
            if (next.isEmpty()) break
            val nextUrl = absolute(rule.expand(next, mapOf("url" to firstUrl, "chapterUrl" to firstUrl)))
            if (nextUrl in visited) break          // 绕回自身，停
            visited += nextUrl
            val rawPage = runCatching {
                fetch(nextUrl, normaliseMethod(rule.content.method), null, rule.content.headers)
            }.getOrNull() ?: break
            val bodyPage = if (rule.content.content.selector.isBlank()) rawPage
                           else contentBody(rawPage)
            sb.append('\n').append(bodyPage)
            curRaw = rawPage
            pages++
        }
        return sb.toString()
    }

    /**
     * 应用 `ruleContent.replaceRegex`：清掉站点广告、提示语、末尾公告。
     *
     * 值是 `##正则##替换`（`##` 后无内容 = 替换为空串），也可能带 `@js:` 前缀。
     * 正则非法时原样返回 —— 清理失败不比正文脏更糟。
     */
    private fun applyReplaceRegex(body: String): String {
        val spec = rule.content.replaceRegex ?: return body
        val code = spec.substringAfter("##", "")
        if (code.isEmpty()) return body
        val parts = spec.split("##")
        if (parts.size < 2) return body
        val re = SafeRegex.compileLenient(parts[1]) ?: return body
        val to = parts.getOrElse(2) { "" }
        return runCatching {
            if (rule.content.replaceFirst) re.replaceFirst(body, to) else re.replace(body, to)
        }.getOrDefault(body)
    }

    /**
     * 把相对链接补成绝对链接，并编码其中的非 ASCII 字符。
     *
     * 很多站的链接带中文（如红袖添香 `https://www.hongxiu.com/斗破之我即正道`），
     * 直接拿去 openConnection 会报 `Illegal character in URL`。
     * 导出与断点续传都要求 itemId 稳定，所以补全与编码都在这里一次做完。
     *
     * [base] 是**拼相对路径的基准**，默认站点根。传目录页/正文页的绝对 URL 才能
     * 正确处理 `1.html`、`../a.html`、`?p=2` 这类相对链接 —— 用站点根当基准会把
     * `/book/123/1.html` 拼成 `/1.html`，整本书的章节链接全部 404。
     */
    private fun absolute(link: String, base: String = rule.host): String {
        val raw0 = link.trim()
        if (raw0.isEmpty()) return raw0
        // `javascript:` 伪链接：legado 直接丢弃（返回空串）。留下会让上层拿它去请求。
        if (raw0.startsWith("javascript", ignoreCase = true)) return ""
        // `data:` 内联资源原样保留。
        if (raw0.startsWith("data:", ignoreCase = true)) return raw0
        // 已经是绝对地址（legado 的 isAbsUrl 只认 http/https）。
        if (raw0.startsWith("http://", ignoreCase = true) ||
            raw0.startsWith("https://", ignoreCase = true)
        ) return encodeUrlPath(raw0)
        // base 可能带 `,{"webView":true}` 这类选项后缀，截掉再解析（legado 同样只截 base）。
        val baseUrl = base.substringBefore(',').trim().ifEmpty { rule.host.substringBefore(',').trim() }
        if (baseUrl.isEmpty()) return raw0
        // 用 java.net.URL 拼：它按 RFC 3986 处理 `/x`、`//host/x`、`../x`、`?q`、`#f`。
        // 手写 startsWith 分支覆盖不全，`../` 和 `?q` 都会拼错。
        return runCatching { java.net.URL(java.net.URL(baseUrl), raw0).toString() }
            .map { encodeUrlPath(it) }
            .getOrElse { encodeUrlPath(raw0) }
    }

    companion object {
        /** 正文分页上限。防站点「下一页」指回自身造成死循环。 */
        private const val MAX_PAGES = 30

        /** 目录请求总预算，包含书籍页与分页。分页超时保留已取得的章节。 */
        private const val TOC_BUDGET_MS = 25_000L

        /**
         * URL 关键词编码。
         *
         * 空格要转成 `%20` 而不是 `+`：不少站点按 `+` 当字面量处理。
         */
        fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /**
         * 只编码 URL 里非 ASCII 的部分，保留已有的 `%XX`、`/`、`?`、`&`、`=` 等结构字符。
         * 不能整串 `URLEncoder.encode` —— 那把 `://` 与 `?` 也编了，URL 就废了。
         */
        fun encodeUrlPath(url: String): String {
            if (url.all { it.code < 128 }) return url
            val sb = StringBuilder(url.length + 16)
            for (b in url.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (c < 128) sb.append(c.toChar())
                else sb.append('%').append("%02X".format(c))
            }
            return sb.toString()
        }
    }
}

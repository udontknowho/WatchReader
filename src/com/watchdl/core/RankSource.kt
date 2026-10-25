package com.watchdl.core

import java.net.URLEncoder

/**
 * 榜单取数：番茄推荐榜、起点月票榜/畅销榜。
 *
 * 两个站都**不需要签名**，走网页端公开接口（不是 APP 签名链路）。
 * 详见笔记本页 `watchreader-discover-api`（2026-10-18 实测）。
 *
 * 榜单条目复用 [BookHit]：点进去走的是既有的「按书名多源搜索」，
 * 榜单本身不负责下载 —— 起点没有对应的 [BookSource]，番茄榜单的书也未必能直接取目录。
 */
object RankSource {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private const val FQ_HOST = "https://fanqienovel.com"

    /** 起点榜单代号。2026-10-24 逐个实测，其余代号一律 404。 */
    val QIDIAN_BOARDS = RankConfig.QIDIAN_BOARDS

    /**
     * 按云端配置里的 [RankConfig.Rank] 取一页。
     *
     * 发现页的推荐流全部走这个入口，不直接调具体的站。
     */
    fun fetch(
        rank: RankConfig.Rank,
        gender: String,
        page: Int,
        /** 本批的绝对死线。三处必须共用同一个起点，否则 3 次重试各自计时。 */
        deadline: Long = System.currentTimeMillis() + PAGE_BUDGET_MS,
        /** 字体还原器，只有番茄分类榜用得上；其它榜忽略。 */
        decoder: CharsetFontDecoder? = null,
    ): List<BookHit> = when (rank.kind) {
        RankConfig.Kind.FANQIE -> fanqie(deadline)
        RankConfig.Kind.FANQIE_UPDATE -> fanqieUpdate(page, deadline)
        // 番茄分类 ID 男女频不通用，以榜单自身绑定的频道为准，不信调用方传的值。
        RankConfig.Kind.FANQIE_RANK -> fanqieRank(rank, deadline, decoder)
        RankConfig.Kind.QIDIAN -> qidian(rank.board, gender, page, deadline)
    }

    /**
     * 单个榜的取数预算。
     *
     * 原来是每个榜 3 次重试 × 20 秒超时 —— 一个站挂住能吃掉 45 秒（含退避），
     * 首发四条榜串行跑完要一分钟以上，界面只能一直空着。
     */
    const val PAGE_BUDGET_MS = 12_000L

    /** 番茄更新榜每页 20 条，服务端 `total` 固定 100，即 5 页。 */
    const val FQ_UPDATE_PAGE = 20

    // ── 番茄分类榜 ─────────────────────────────────────
    /**
     * 番茄分类榜：男频/女频 × 阅读榜/新书榜 × 分类，各 10 本，不可翻页。
     *
     * 10 条是服务端上限：SSR 里只有 `book_list` 一个数组，`?page=`、`?pageNum=`
     * 均返回同一批书。想要更多只能换榜（同分类的阅读/新书两份加起来 20 本）。
     *
     * **必须走页面 SSR，不要碰 `/api/rank/category/list`** —— 那个接口要登录态，
     * 任何参数组合都返空数组。而同一个页面渲染出来的 `__INITIAL_STATE__.rank.book_list`
     * 是完整的，条件是 UA 为桌面版：移动 UA 拿到的是 `serverRendered:false` 空壳。
     *
     * 书名/作者含私用区字符，用 [CharsetFontDecoder] 还原。
     */
    fun fanqieRank(
        rank: RankConfig.Rank,
        deadline: Long = System.currentTimeMillis() + PAGE_BUDGET_MS,
        decoder: CharsetFontDecoder? = null,
    ): List<BookHit> = retry(deadline) {
        val g = if (rank.gender == RankConfig.Gender.FEMALE) 0 else 1
        val url = "$FQ_HOST/rank/${g}_${rank.rankType}_${rank.category}"
        val html = Http.fetchRaw(
            url, "GET", null, null, emptyMap(), UA, timeoutMs = left(deadline),
        ).let { String(it.bytes, Charsets.UTF_8) }

        // 页面里是 `...,"loading":true},"rank":{...}`，rank 前一字符是逗号，
        // 不是合法 JSON 起点。补个 `{` 把它包成根对象，就能用嵌套路径了。
        val at = html.indexOf("\"rank\":{")
        require(at >= 0) { "榜单页没吐 SSR 数据（UA 不是桌面版？）" }
        val body = "{" + html.substring(at)
        // 空表时 decode 是恒等变换，所以拿不到字符表也不会把书名抹掉，只是带私用区字符。
        val dec = decoder ?: EMPTY_DECODER

        fun field(k: String) = TinyJson.arrayField(body, "rank.book_list", k)
        val ids = field("bookId")
        val names = field("bookName")
        val authors = field("author")
        val abstracts = field("abstract")
        val covers = field("thumbUri")
        val words = field("wordNumber")
        val pos = field("currentPos")
        val lastCh = field("lastChapterTitle")

        ids.mapIndexed { i, id ->
            val word = words.getOrNull(i).orEmpty().toIntOrNull()
            val p = pos.getOrNull(i).orEmpty()
            BookHit(
                bookId = id,
                name = dec.decode(names.getOrNull(i) ?: ""),
                author = dec.decode(authors.getOrNull(i) ?: ""),
                abstractText = dec.decode(abstracts.getOrNull(i) ?: ""),
                sourceName = "番茄",
                cover = covers.getOrNull(i).orEmpty(),
                extra = listOf(
                    p.takeIf { it.isNotBlank() }?.let { "第${it}名" },
                    word?.takeIf { it > 0 }?.let { "${it / 10_000}万字" },
                    lastCh.getOrNull(i)?.takeIf { it.isNotBlank() }
                        ?.let { dec.decode(it) },
                ).filterNotNull().joinToString(" · "),
            )
        }.filter { it.name.isNotBlank() }
    }

    /** 拿不到字符表时的空解码器（恒等变换）。 */
    private val EMPTY_DECODER = CharsetFontDecoder(emptyList())

    // ── 番茄推荐/更新 ──────────────────────────────────
    /**
     * 番茄推荐榜，固定 9 条、不可翻页。
     *
     * 按分类的 `/api/rank/category/list` 恒返回空（参数与官方 JS 一致也不出数据），
     * 所以只剩这一条流水可用。
     */
    fun fanqie(deadline: Long = System.currentTimeMillis() + PAGE_BUDGET_MS): List<BookHit> = retry(deadline) {
        val body = Http.fetch(
            "$FQ_HOST/api/rank/recommend/list", "GET", null,
            mapOf("Accept" to "application/json"), referer = "$FQ_HOST/",
            timeoutMs = left(deadline),
        ).body
        // S18：ids 是主字段。路径对不上说明接口改了，抛错让 retry 重试，
        // 别把它当成「今天没数据」。
        val ids = TinyJson.arrayFieldOrThrow(body, "data.list", "bookId")
            ?: error("番茄推荐接口字段变了（缺 data.list）")
        val names = TinyJson.arrayField(body, "data.list", "bookName")
        val authors = TinyJson.arrayField(body, "data.list", "author")
        val abstracts = TinyJson.arrayField(body, "data.list", "abstract")
        ids.mapIndexed { i, id ->
            BookHit(
                bookId = id,
                name = names.getOrNull(i) ?: "",
                author = authors.getOrNull(i) ?: "",
                abstractText = abstracts.getOrNull(i) ?: "",
                sourceName = "番茄",
            )
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 番茄更新榜，实时变动，[page] 从 1 起，共 5 页。
     *
     * 与推荐榜的区别：这条**真会变化**，每次进来都是新书，所以它兼顾了「刷新」。
     * `gender` 与 `category_id` 服务端都不认，别白传。
     * 后几页会和前页有重叠（实时排序会漂），调用方自己去重。
     */
    fun fanqieUpdate(
        page: Int,
        deadline: Long = System.currentTimeMillis() + PAGE_BUDGET_MS,
    ): List<BookHit> = retry(deadline) {
        val offset = (page - 1).coerceAtLeast(0) * FQ_UPDATE_PAGE
        val body = Http.fetch(
            "$FQ_HOST/api/rank/recent/update/list?app_id=2503" +
                "&offset=$offset&limit=$FQ_UPDATE_PAGE",
            "GET", null,
            mapOf("Accept" to "application/json"), referer = "$FQ_HOST/",
            timeoutMs = left(deadline),
        ).body
        // S18：同上，主字段缺失视为接口变更。
        val ids = TinyJson.arrayFieldOrThrow(body, "data.data", "bookId")
            ?: error("番茄分类榜接口字段变了（缺 data.data）")
        val names = TinyJson.arrayField(body, "data.data", "bookName")
        val authors = TinyJson.arrayField(body, "data.data", "author")
        val cats = TinyJson.arrayField(body, "data.data", "category")
        ids.mapIndexed { i, id ->
            val cat = cats.getOrNull(i).orEmpty()
            val auth = authors.getOrNull(i).orEmpty()
            BookHit(
                bookId = id,
                name = names.getOrNull(i) ?: "",
                // 列表副标题拼的是作者，分类只能借道这里带出去（没有单独的分类字段）。
                author = listOf(auth, cat).filter { it.isNotBlank() }.joinToString(" · "),
                abstractText = cat,
                sourceName = "番茄",
            )
        }.filter { it.name.isNotBlank() }
    }

    // ── 起点 ──────────────────────────────────────────────
    /**
     * 起点榜单。[board] 取 [QIDIAN_BOARDS] 的代号，[gender] 是 `male`/`female`，[page] 从 1 起。
     *
     * 必须走 `m.qidian.com`：`www.qidian.com` 有 WAF，回 HTTP 202 + 挑战脚本，取不到数据。
     * `majax` 接口要 `_csrfToken`，值从榜单页的 Set-Cookie 拿，且要和 cookie 一起发。
     */
    fun qidian(
        board: String,
        gender: String,
        page: Int,
        deadline: Long = System.currentTimeMillis() + PAGE_BUDGET_MS,
    ): List<BookHit> = retry(deadline) {
        val url = "https://m.qidian.com/majax/rank/$board" +
            "?gender=$gender&pageNum=$page&_csrfToken=" + enc(token())
        val body = Http.fetch(
            url, "GET", null,
            mapOf(
                "Cookie" to "_csrfToken=${token()}",
                "Accept" to "application/json, text/plain, */*",
            ),
            referer = "https://m.qidian.com/rank/yuepiao", timeoutMs = left(deadline),
        ).body
        // S18：同上。起点用 bid。
        val ids = TinyJson.arrayFieldOrThrow(body, "data.records", "bid")
            ?: error("起点榜接口字段变了（缺 data.records）")
        val names = TinyJson.arrayField(body, "data.records", "bName")
        val authors = TinyJson.arrayField(body, "data.records", "bAuth")
        val abstracts = TinyJson.arrayField(body, "data.records", "desc")
        ids.mapIndexed { i, id ->
            BookHit(
                bookId = id,
                name = names.getOrNull(i) ?: "",
                author = authors.getOrNull(i) ?: "",
                abstractText = abstracts.getOrNull(i) ?: "",
                sourceName = "起点",
            )
        }.filter { it.name.isNotBlank() }
    }

    /** 起点的 csrf token。一年有效，进程内缓存一次就够。 */
    @Volatile
    private var cachedToken: String? = null

    private fun token(): String {
        cachedToken?.let { return it }
        val raw = Http.fetchRaw(
            "https://m.qidian.com/rank/yuepiao", "GET", null, null,
            emptyMap(), UA, timeoutMs = 20_000,
        )
        // HttpURLConnection 把多个 Set-Cookie 折叠成一个逗号分隔的值，
        // 而 cookie 的 Expires 属性本身带逗号（"Mon, 18-Oct-2027"），
        // 所以不能按逗号切 —— 用正则直接找 _csrfToken 的值。
        val t = Regex("_csrfToken=([^;,\\s]+)")
            .find(raw.headers["set-cookie"].orEmpty())
            ?.groupValues?.get(1)
        require(!t.isNullOrEmpty()) { "起点没下发 _csrfToken" }
        cachedToken = t
        return t
    }

    // ── 小工具 ────────────────────────────────────────────
    /**
     * 两个站都会偶发软失败：HTTP 200 但正文不是 JSON（或字段缺失）。
     * 番茄的目录接口也有同样的毛病，重试就过了。token 过期时清掉重取。
     */
    /**
     * 带死线重试。**预算用完就不再试** —— 否则退避和重试会把整批拖成分钟级。
     */
    private fun retry(
        deadline: Long,
        times: Int = 3,
        block: () -> List<BookHit>,
    ): List<BookHit> {
        var last: Exception? = null
        for (i in 0 until times) {
            if (left(deadline) < 500) break
            try {
                val r = block()
                if (r.isNotEmpty()) return r
            } catch (e: Exception) {
                last = e
                cachedToken = null
            }
            val wait = minOf(600L * (i + 1), (left(deadline) - 500).coerceAtLeast(0).toLong())
            if (i < times - 1 && wait > 0) Thread.sleep(wait)
        }
        if (last != null) throw last
        return emptyList()
    }

    /** 死线剩下的毫秒数，给 [Http.fetch] 当超时。至少给 1 秒，别传 0 下去。 */
    private fun left(deadline: Long): Int =
        (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L).toInt()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

package com.watchdl.core

/**
 * 把一条 legado 书源规则（[BookSourceRule]）适配成 [BookSource]。
 *
 * 以前 [SourceEngine] 写好了却没有任何调用点 —— 界面固定用番茄，
 * 本地导入的 54 条书源只被统计数量、从不参与搜索。
 * 这个类就是补上那一环：一条规则 = 一个可搜索的源。
 */
class LegadoSource(
    val rule: BookSourceRule,
    private val engine: SourceEngine,
) : BookSource {

    /** 显示用的源名（结果列表里标注来源靠它）。 */
    val displayName: String get() = rule.name

    override fun search(keyword: String, page: Int): List<BookHit> =
        engine.search(keyword, page).map { it.copy(sourceName = displayName) }

    override fun toc(bookId: String, name: String, author: String): BookInfo =
        engine.toc(bookId)

    override fun open(hit: BookHit): BookInfo = toc(hit.bookId, hit.name, hit.author)

    override fun content(itemId: String, bookId: String): String = engine.content(itemId)

    companion object {
        /**
         * 用默认 HTTP 实现造一个源。
         *
         * [Http.fetch] 已经处理重定向（Java 的 `instanceFollowRedirects` 不跨协议），
         * 所以这里直接转发即可。
         */
        fun of(rule: BookSourceRule): LegadoSource = LegadoSource(rule,
            SourceEngine(rule, fetchToc = { url, method, body, headers, left ->
                Http.fetch(url, method, body, headers, timeoutMs = minOf(15_000, left),
                    totalTimeoutMs = left).body
            }) { url, method, body, headers ->
                Http.fetch(url, method, body, headers).body
            })
    }
}

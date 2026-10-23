package com.watchdl

import com.watchdl.core.*
import java.net.URLEncoder

/**
 * 番茄小说取数适配器。三条链路全部无签名、无 Cookie、无服务端，
 * 详见笔记本页 `fanqie-source-protocol`（2026-10-15 本机端到端验证通过）。
 */
class FanqieSource(
    private val charsetPath: String,
    private val refererPage: (String) -> String = { "https://fanqienovel.com/page/$it" },
) : BookSource {
    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val SEARCH_HOST = "https://novel.snssdk.com"
        private const val WEB_HOST = "https://fanqienovel.com"
    }

    private val decoder: CharsetFontDecoder by lazy { CharsetFontDecoder.fromFile(charsetPath) }

    /** 搜索。接口无需签名，直连可用。 */
    override fun search(keyword: String, page: Int): List<BookHit> {
        val url = "$SEARCH_HOST/api/novel/channel/homepage/search/search/v1/" +
            "?device_platform=android&parent_enterfrom=novel_channel_search.tab." +
            "&offset=${(page - 1) * 10}&aid=1967&q=" + enc(keyword)
        val body = get(url, referer = null)
        // 这个接口的书名字段是 title，不是 book_name（实测 2026-10-15）
        val ids = TinyJson.arrayField(body, "data.ret_data", "book_id")
        val names = TinyJson.arrayField(body, "data.ret_data", "title")
        val authors = TinyJson.arrayField(body, "data.ret_data", "author")
        val abstracts = TinyJson.arrayField(body, "data.ret_data", "abstract")
        return ids.mapIndexed { i, id ->
            BookHit(
                bookId = id,
                // 接口返回的书名带 <em> 高亮标签，不清洗会直接显示成 "<em>斗破苍穹</em>"。
                name = Downloader.stripTags(names.getOrNull(i) ?: "（无题）"),
                author = Downloader.stripTags(authors.getOrNull(i) ?: ""),
                abstractText = abstracts.getOrNull(i) ?: "",
            )
        }
    }

    /**
     * 目录。`allItemIds` 给出章节顺序，`chapterListWithVolume` 是分卷嵌套。
     * 两者按 itemId 连接，不能靠下标对齐：分卷会让拍平后的顺序与 allItemIds 不一致。
     *
     * 这个接口不返回书名与作者（实测只有 allItemIds 和 chapterListWithVolume 两个键），
     * 书名从搜索结果传入。
     */
    override fun toc(bookId: String, name: String, author: String): BookInfo {
        val body = get("$WEB_HOST/api/reader/directory/detail?bookId=$bookId", referer = refererPage(bookId))
        val ids = TinyJson.stringArray(body, "allItemIds")

        // 按 itemId 建索引：分卷嵌套会破坏下标对齐，只能按 id 查
        val meta = HashMap<String, Pair<String, Boolean>>(ids.size * 2)
        for (obj in TinyJson.objects(body, "chapterListWithVolume")) {
            val id = TinyJson.path(obj, "itemId") ?: continue
            meta[id] = (TinyJson.path(obj, "title") ?: "") to
                (TinyJson.path(obj, "isChapterLock")?.equals("true", ignoreCase = true) == true)
        }

        val chapters = ids.mapIndexed { i, id ->
            val m = meta[id]
            ChapterRef(
                itemId = id,
                title = m?.first?.takeIf { it.isNotEmpty() } ?: "第 ${i + 1} 章",
                locked = m?.second ?: false,
            )
        }
        return BookInfo(bookId = bookId, name = name.ifEmpty { bookId }, author = author, chapters = chapters)
    }

    /** 完整取书：搜索定位后直接拉目录。 */
    override fun open(hit: BookHit): BookInfo = toc(hit.bookId, hit.name, hit.author)

    /**
     * 正文。取 HTML 里 JSON 的 content 字段，去标签后用字符表还原字体混淆。
     * 返回 XHTML 片段，落盘存原文，导出时再转纯文本。
     */
    override fun content(itemId: String, bookId: String): String {
        val html = get("$WEB_HOST/reader/$itemId", referer = refererPage(bookId))
        val raw = TinyJson.extractContent(html)
            ?: error("正文页没找到 content 字段（itemId=$itemId，该章可能被锁）")
        return decoder.decode(raw)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, referer: String?): String =
        Http.fetch(url, "GET", null, emptyMap(), referer, timeoutMs = 20_000).body
}

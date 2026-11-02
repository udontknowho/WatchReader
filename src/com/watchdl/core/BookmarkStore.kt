package com.watchdl.core

import java.io.File

/**
 * 书签。
 *
 * 一条书签 = 一本书里的一章 + 章内像素位置 + 一句摘录。
 * 手表上没有文字选中，「书签」只能是「这一章我停在这儿」，摘录用来在列表里认出它。
 *
 * 存储是一个 json 文件，不做数据库：全书签量级是几十条，
 * 读一次全量解析比引入 Room 便宜得多。文件路径由调用方给（[file]），
 * 和 [DevLog] 一样是为了让纯 JVM 自检能指向临时目录。
 */
class Bookmark(
    val bookId: String,
    val bookName: String,
    /** 章下标，与 `BookInfo.chapters` 对齐。 */
    val index: Int,
    val title: String,
    /** 章内像素位置，-1 表示没记。 */
    val offset: Int,
    /** 摘录，列表里显示用。 */
    val excerpt: String,
    val at: Long,
) {
    /** 同书同章算同一条。位置改了按覆盖处理，不留两条。 */
    fun sameAs(o: Bookmark): Boolean = bookId == o.bookId && index == o.index
}

object BookmarkStore {

    /** 落盘位置。Activity 启动时指到 `filesDir/bookmarks.json`。 */
    @Volatile
    var file: File? = null

    /**
     * 总条数上限。
     *
     * 手表磁盘只有几十兆可用，书签没人清就会一直长。超了丢最旧的 ——
     * 丢最新的是错的，用户刚加的那条最有用。
     */
    const val MAX = 200

    fun list(): List<Bookmark> {
        val f = file ?: return emptyList()
        if (!f.isFile) return emptyList()
        // 存档坏了不能让阅读页起不来：解析失败就当没有。
        return runCatching { parse(f.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    fun forBook(bookId: String): List<Bookmark> =
        list().filter { it.bookId == bookId }.sortedBy { it.index }

    fun has(bookId: String, index: Int): Boolean =
        list().any { it.bookId == bookId && it.index == index }

    /** 加一条。同书同章覆盖旧的。返回更新后的全量。 */
    fun add(b: Bookmark): List<Bookmark> {
        val kept = list().filterNot { it.sameAs(b) } + b
        // 新的在后，超限从头部删（头部最旧）。
        val trimmed = kept.sortedBy { it.at }.takeLast(MAX)
        save(trimmed)
        return trimmed
    }

    fun remove(bookId: String, index: Int): List<Bookmark> {
        val kept = list().filterNot { it.bookId == bookId && it.index == index }
        save(kept)
        return kept
    }

    /** 同步落盘。进程随时可能被杀，异步写会丢。 */
    fun save(items: List<Bookmark>) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(dump(items), Charsets.UTF_8)
        }
    }

    // ── json ──────────────────────────────────────────
    // 格式：{"v":1,"list":[{...},...]}。字段全平，没有嵌套，所以手写最省。

    private fun dump(items: List<Bookmark>): String = buildString {
        append("{\"v\":1,\"list\":[")
        items.forEachIndexed { i, b ->
            if (i > 0) append(',')
            append('{')
            append("\"bookId\":\"").append(esc(b.bookId)).append("\",")
            append("\"bookName\":\"").append(esc(b.bookName)).append("\",")
            append("\"index\":").append(b.index).append(',')
            append("\"title\":\"").append(esc(b.title)).append("\",")
            append("\"offset\":").append(b.offset).append(',')
            append("\"excerpt\":\"").append(esc(b.excerpt)).append("\",")
            append("\"at\":").append(b.at)
            append('}')
        }
        append("]}")
    }

    private fun parse(text: String): List<Bookmark> =
        TinyJson.objects(text, "list").mapNotNull { obj ->
            val bookId = str(obj, "bookId")
            // bookId 是书签的唯一归属，缺了这条就没有意义。
            if (bookId.isNullOrBlank()) return@mapNotNull null
            Bookmark(
                bookId = bookId,
                bookName = str(obj, "bookName") ?: "",
                index = num(obj, "index"),
                title = str(obj, "title") ?: "",
                offset = num(obj, "offset", -1),
                excerpt = str(obj, "excerpt") ?: "",
                at = num(obj, "at").toLong(),
            )
        }

    private fun str(obj: String, key: String): String? =
        TinyJson.field(obj, key)?.let {
            if (it.startsWith("\"")) TinyJson.unescape(it.trim('"')) else it
        }

    private fun num(obj: String, key: String, fallback: Int = 0): Int =
        str(obj, key)?.trim()?.toIntOrNull() ?: fallback

    /** 只转义必须的几个字符：引号、反斜杠、换行、回车、制表。 */
    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(' ') else append(c)
        }
    }
}

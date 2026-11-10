package com.watchdl.core

import java.io.File

/** 收藏身份不随当前书源改变；旧调用只传 info/sourceName/addedAt 仍有效。 */
data class ShelfBook(
    val info: BookInfo, val sourceName: String = "", val addedAt: Long = 0L,
    val shelfId: String = info.bookId, val candidates: List<BookHit> = emptyList(),
)

class ShelfStore(private val file: File) {
    companion object { private val lock = Any() }

    /** 读取失败不阻断启动；写入口仍严格读取，绝不覆盖损坏原档。 */
    fun books(): List<ShelfBook> = synchronized(lock) { runCatching { read().second }.getOrDefault(emptyList()) }
    fun readError(): String? = synchronized(lock) { runCatching { read() }.exceptionOrNull()?.message }
    fun find(id: String): ShelfBook? = books().firstOrNull { matches(it, id) }
    fun contains(id: String): Boolean = find(id) != null
    fun find(info: BookInfo): ShelfBook? = find(info.bookId) ?: books().firstOrNull {
        sameBook(info.name, info.author, it.info.name, it.info.author)
    }

    fun add(info: BookInfo, sourceName: String = "", candidates: List<BookHit> = emptyList()) = synchronized(lock) {
        require(info.bookId.isNotBlank()) { "书籍地址为空，不能收藏" }
        val (migrated, list) = read()
        val old = list.firstOrNull { matches(it, info.bookId) } ?: list.firstOrNull {
            sameBook(info.name, info.author, it.info.name, it.info.author)
        }
        // 另一条源的搜索命中只补候选，不能用空目录替换当前可读来源。
        val keepCurrent = old != null && old.info.bookId != info.bookId
        val keep = if (keepCurrent) old!!.info else info.copy(
            chapters = info.chapters.ifEmpty { old?.info?.chapters.orEmpty() },
            intro = info.intro.ifBlank { old?.info?.intro.orEmpty() },
        )
        val origins = mergeCandidates(keep, (old?.candidates.orEmpty() + candidates +
            BookHit(info.bookId, info.name, info.author, info.intro, sourceName = sourceName)))
        val book = ShelfBook(keep, if (keepCurrent) old!!.sourceName else sourceName.ifBlank { old?.sourceName.orEmpty() },
            old?.addedAt ?: System.currentTimeMillis(), old?.shelfId ?: info.bookId, origins)
        save(migrated, list.filterNot { it.shelfId == book.shelfId } + book)
    }

    /** 仅验证成功后调用。保留收藏身份、加入时间和旧来源。 */
    fun replaceSource(oldId: String, info: BookInfo, sourceName: String, candidates: List<BookHit> = emptyList()) = synchronized(lock) {
        require(info.bookId.isNotBlank() && info.chapters.isNotEmpty()) { "新源目录为空" }
        val (migrated, list) = read()
        val old = list.firstOrNull { matches(it, oldId) } ?: return@synchronized
        require(sameBook(old.info.name, old.info.author, info.name, info.author)) { "新源书名或作者不匹配" }
        val origins = mergeCandidates(info, old.candidates + candidates +
            BookHit(info.bookId, info.name, info.author, info.intro, sourceName = sourceName))
        val book = old.copy(info = info, sourceName = sourceName, candidates = origins)
        save(migrated, list.filterNot { it.shelfId == old.shelfId || matches(it, info.bookId) } + book)
    }

    fun remove(id: String) = synchronized(lock) {
        val (migrated, list) = read()
        save(migrated, list.filterNot { matches(it, id) })
    }

    fun migrateOnce(legacy: () -> List<ShelfBook>) = synchronized(lock) {
        val (migrated, list) = read()
        if (!migrated) {
            val merged = LinkedHashMap<String, ShelfBook>()
            legacy().forEach { if (it.info.bookId.isNotBlank()) merged[it.shelfId] = it }
            list.forEach { merged[it.shelfId] = it }
            save(true, merged.values.toList())
        }
    }

    private fun matches(b: ShelfBook, id: String): Boolean = b.shelfId == id || b.info.bookId == id || b.candidates.any { it.bookId == id }

    private fun mergeCandidates(info: BookInfo, hits: List<BookHit>): List<BookHit> = hits.filter {
        it.bookId.isNotBlank() && sameBook(info.name, info.author, it.name, it.author)
    }.distinctBy { it.sourceName to it.bookId }

    private fun read(): Pair<Boolean, List<ShelfBook>> {
        if (!file.exists()) return false to emptyList()
        val raw = file.readText().trim()
        require(raw.startsWith("{") && raw.endsWith("}") && TinyJson.field(raw, "books")?.let {
            it.startsWith("[") && it.endsWith("]")
        } == true) { "收藏存档损坏，已保留原文件" }
        val books = TinyJson.objects(raw, "books").mapNotNull { o ->
            val id = TinyJson.path(o, "bookId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chapters = TinyJson.objects(o, "chapters").mapNotNull chapter@ { c ->
                val itemId = TinyJson.path(c, "itemId") ?: return@chapter null
                ChapterRef(itemId, TinyJson.path(c, "title").orEmpty(), TinyJson.path(c, "locked") == "true")
            }
            val info = BookInfo(id, TinyJson.path(o, "name").orEmpty(), TinyJson.path(o, "author").orEmpty(),
                chapters, TinyJson.path(o, "truncated") == "true", TinyJson.path(o, "intro").orEmpty())
            val sn = TinyJson.path(o, "sourceName").orEmpty()
            val candidates = TinyJson.objects(o, "candidates").mapNotNull candidate@ { c ->
                val cid = TinyJson.path(c, "bookId")?.takeIf { it.isNotBlank() } ?: return@candidate null
                BookHit(cid, TinyJson.path(c, "name").orEmpty(), TinyJson.path(c, "author").orEmpty(),
                    TinyJson.path(c, "abstractText").orEmpty(), sourceName = TinyJson.path(c, "sourceName").orEmpty())
            }
            ShelfBook(info, sn, TinyJson.path(o, "addedAt")?.toLongOrNull() ?: 0L,
                TinyJson.path(o, "shelfId")?.takeIf { it.isNotBlank() } ?: id,
                mergeCandidates(info, candidates + BookHit(id, info.name, info.author, info.intro, sourceName = sn)))
        }
        return (TinyJson.path(raw, "migrated") == "true") to books.distinctBy { it.shelfId }
    }

    private fun save(migrated: Boolean, list: List<ShelfBook>) {
        val q: (String) -> String = { Downloader.quote(it) }
        val json = "{\"version\":2,\"migrated\":$migrated,\"books\":[" + list.joinToString(",") { b ->
            val i = b.info
            "{\"shelfId\":${q(b.shelfId)},\"bookId\":${q(i.bookId)},\"name\":${q(i.name)},\"author\":${q(i.author)}," +
                "\"intro\":${q(i.intro)},\"sourceName\":${q(b.sourceName)},\"addedAt\":${b.addedAt}," +
                "\"candidates\":[" + b.candidates.joinToString(",") { h ->
                    "{\"bookId\":${q(h.bookId)},\"name\":${q(h.name)},\"author\":${q(h.author)},\"abstractText\":${q(h.abstractText)},\"sourceName\":${q(h.sourceName)}}"
                } + "],\"truncated\":${i.truncated},\"chapters\":[" + i.chapters.joinToString(",") { c ->
                    "{\"itemId\":${q(c.itemId)},\"title\":${q(c.title)},\"locked\":${c.locked}}"
                } + "]}"
        } + "]}"
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { out -> out.write(json.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            check(tmp.renameTo(file)) { "无法保存收藏" }
        } finally { tmp.delete() }
    }
}

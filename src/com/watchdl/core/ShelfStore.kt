package com.watchdl.core

import java.io.File

/** 收藏独立于下载作业。删除下载文件不会移除收藏，取消收藏不会删除正文。 */
data class ShelfBook(val info: BookInfo, val sourceName: String = "", val addedAt: Long = 0L)

class ShelfStore(private val file: File) {
    companion object { private val lock = Any() }

    fun books(): List<ShelfBook> = synchronized(lock) { read().second }
    fun find(id: String): ShelfBook? = books().firstOrNull { it.info.bookId == id }
    fun contains(id: String): Boolean = find(id) != null

    fun add(info: BookInfo, sourceName: String = "") = synchronized(lock) {
        require(info.bookId.isNotBlank()) { "书籍地址为空，不能收藏" }
        val (migrated, list) = read()
        val old = list.firstOrNull { it.info.bookId == info.bookId }
        val keep = info.copy(
            chapters = info.chapters.ifEmpty { old?.info?.chapters.orEmpty() },
            intro = info.intro.ifBlank { old?.info?.intro.orEmpty() },
        )
        val book = ShelfBook(keep, sourceName.ifBlank { old?.sourceName.orEmpty() },
            old?.addedAt ?: System.currentTimeMillis())
        save(migrated, list.filterNot { it.info.bookId == info.bookId } + book)
    }

    fun remove(id: String) = synchronized(lock) {
        val (migrated, list) = read()
        save(migrated, list.filterNot { it.info.bookId == id })
    }

    /** 标记与收藏一次提交；移出收藏的旧作业以后不会再次被导入。 */
    fun migrateOnce(legacy: () -> List<ShelfBook>) = synchronized(lock) {
        val (migrated, list) = read()
        if (!migrated) {
            val merged = LinkedHashMap<String, ShelfBook>()
            legacy().forEach { if (it.info.bookId.isNotBlank()) merged[it.info.bookId] = it }
            list.forEach { merged[it.info.bookId] = it }
            save(true, merged.values.toList())
        }
    }

    private fun read(): Pair<Boolean, List<ShelfBook>> {
        if (!file.exists()) return false to emptyList()
        val raw = file.readText()
        require(raw.trim().startsWith("{") && raw.trim().endsWith("}")) { "收藏存档损坏，已保留原文件" }
        val books = TinyJson.objects(raw, "books").mapNotNull { o ->
            val id = TinyJson.path(o, "bookId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chapters = TinyJson.objects(o, "chapters").mapNotNull chapter@ { c ->
                val itemId = TinyJson.path(c, "itemId") ?: return@chapter null
                ChapterRef(itemId, TinyJson.path(c, "title").orEmpty(), TinyJson.path(c, "locked") == "true")
            }
            ShelfBook(BookInfo(id, TinyJson.path(o, "name").orEmpty(), TinyJson.path(o, "author").orEmpty(),
                chapters, TinyJson.path(o, "truncated") == "true", TinyJson.path(o, "intro").orEmpty()),
                TinyJson.path(o, "sourceName").orEmpty(), TinyJson.path(o, "addedAt")?.toLongOrNull() ?: 0L)
        }
        return (TinyJson.path(raw, "migrated") == "true") to books.distinctBy { it.info.bookId }
    }

    private fun save(migrated: Boolean, list: List<ShelfBook>) {
        val q: (String) -> String = { Downloader.quote(it) }
        val json = "{\"version\":1,\"migrated\":$migrated,\"books\":[" + list.joinToString(",") { b ->
            val i = b.info
            "{\"bookId\":${q(i.bookId)},\"name\":${q(i.name)},\"author\":${q(i.author)}," +
                "\"intro\":${q(i.intro)},\"sourceName\":${q(b.sourceName)},\"addedAt\":${b.addedAt}," +
                "\"truncated\":${i.truncated},\"chapters\":[" + i.chapters.joinToString(",") { c ->
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

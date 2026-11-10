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
    /** 裸字符串优先解释为稳定收藏标识；来源地址仅在不歧义时可用。 */
    fun find(id: String): ShelfBook? = resolveId(books(), id)
    fun contains(id: String): Boolean = find(id) != null
    fun find(info: BookInfo): ShelfBook? = logicalBook(books(), info)
    fun contains(info: BookInfo): Boolean = find(info) != null

    fun add(info: BookInfo, sourceName: String = "", candidates: List<BookHit> = emptyList()) = synchronized(lock) {
        require(info.bookId.isNotBlank()) { "书籍地址为空，不能收藏" }
        val (migrated, list) = read()
        val old = logicalBook(list, info) ?: list.filter { it.info.bookId == info.bookId }
            .singleOrNull()?.takeIf {
                // 兼容旧调用：空目录、未指定来源的同作者元数据刷新。
                // 此例外只适用于 add；UI 查询和删除不能据此推断逻辑身份。
                info.chapters.isEmpty() && sourceName.isBlank() && candidates.isEmpty() &&
                    it.info.chapters.isNotEmpty() && it.info.author.isNotBlank() && it.info.author == info.author
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
            old?.addedAt ?: System.currentTimeMillis(), old?.shelfId ?: newShelfId(info.bookId, list), origins)
        save(migrated, list.filterNot { it.shelfId == book.shelfId } + book)
    }

    /** 仅验证成功后调用。保留收藏身份、加入时间和旧来源。 */
    fun replaceSource(oldId: String, info: BookInfo, sourceName: String, candidates: List<BookHit> = emptyList()) = synchronized(lock) {
        require(info.bookId.isNotBlank() && info.chapters.isNotEmpty()) { "新源目录为空" }
        val (migrated, list) = read()
        val old = resolveId(list, oldId) ?: return@synchronized
        require(sameBook(old.info.name, old.info.author, info.name, info.author)) { "新源书名或作者不匹配" }
        val origins = mergeCandidates(info, old.candidates + candidates +
            BookHit(info.bookId, info.name, info.author, info.intro, sourceName = sourceName))
        val book = old.copy(info = info, sourceName = sourceName, candidates = origins)
        save(migrated, list.filterNot { it.shelfId == old.shelfId } + book)
    }

    fun remove(id: String) = synchronized(lock) {
        val (migrated, list) = read()
        val old = resolveId(list, id) ?: return@synchronized
        save(migrated, list.filterNot { it.shelfId == old.shelfId })
    }

    fun remove(info: BookInfo) = synchronized(lock) {
        val (migrated, list) = read()
        val old = logicalBook(list, info) ?: return@synchronized
        save(migrated, list.filterNot { it.shelfId == old.shelfId })
    }

    fun migrateOnce(legacy: () -> List<ShelfBook>) = synchronized(lock) {
        val (migrated, list) = read()
        if (!migrated) {
            val merged = mutableListOf<ShelfBook>()
            (legacy().filter { it.info.bookId.isNotBlank() } + list).forEach { book ->
                val old = logicalBook(merged, book.info)
                if (old != null) merged.remove(old)
                merged.add(book)
            }
            save(true, uniqueShelfIds(merged))
        }
    }

    private fun logicalBook(list: List<ShelfBook>, info: BookInfo): ShelfBook? = list.firstOrNull {
        sameBook(info.name, info.author, it.info.name, it.info.author)
    }

    private fun resolveId(list: List<ShelfBook>, id: String): ShelfBook? {
        if (id.isBlank()) return null
        return list.singleOrNull { it.shelfId == id } ?: list.filter {
            it.info.bookId == id || it.candidates.any { hit -> hit.bookId == id }
        }.singleOrNull()
    }

    private fun newShelfId(preferred: String, list: List<ShelfBook>): String {
        val used = list.flatMap { b -> listOf(b.shelfId, b.info.bookId) + b.candidates.map { it.bookId } }.toSet()
        if (preferred !in used) return preferred
        return generateSequence(2) { it + 1 }.map { "$preferred#shelf-$it" }.first { it !in used }
    }

    /** 旧存档可以包含同地址不同书；不丢记录，确定性修复重复的收藏标识。 */
    private fun uniqueShelfIds(list: List<ShelfBook>): List<ShelfBook> {
        val used = list.flatMap { b -> listOf(b.shelfId, b.info.bookId) + b.candidates.map { it.bookId } }.toMutableSet()
        val seen = HashSet<String>()
        return list.map { book ->
            if (book.shelfId.isNotBlank() && seen.add(book.shelfId)) book else {
                val base = book.shelfId.ifBlank { book.info.bookId }
                val id = generateSequence(2) { it + 1 }.map { "$base#shelf-$it" }.first { it !in used }
                used.add(id)
                seen.add(id)
                book.copy(shelfId = id)
            }
        }
    }

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
        return (TinyJson.path(raw, "migrated") == "true") to uniqueShelfIds(books)
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

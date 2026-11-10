package com.watchdl.core

import java.io.File
import java.security.MessageDigest

/** 在线阅读的目录与来源快照。地址精确匹配，不合并同名书。 */
class ReadingBookStore(private val file: File) {
    companion object { private val lock = Any() }

    fun books(): List<ShelfBook> = synchronized(lock) {
        runCatching { read() }.getOrDefault(emptyList())
    }

    /** 裸地址仅在无歧义时解析；身份键精确匹配。 */
    fun find(bookId: String): ShelfBook? = books().filter { readingId(it.info) == bookId }.singleOrNull()
        ?: books().filter { it.info.bookId == bookId }.singleOrNull()

    fun find(info: BookInfo): ShelfBook? = books().firstOrNull { readingId(it.info) == readingId(info) }

    fun save(info: BookInfo, sourceName: String = "") = synchronized(lock) {
        require(info.bookId.isNotBlank()) { "书籍地址为空" }
        val books = read()
        val old = books.firstOrNull { readingId(it.info) == readingId(info) }
        val book = ShelfBook(info.copy(
            chapters = info.chapters.ifEmpty { old?.info?.chapters.orEmpty() },
            intro = info.intro.ifBlank { old?.info?.intro.orEmpty() },
        ), sourceName.ifBlank { old?.sourceName.orEmpty() }, old?.addedAt ?: System.currentTimeMillis())
        if (book == old) return@synchronized
        val q: (String) -> String = { Downloader.quote(it) }
        val json = "{\"version\":2,\"books\":[" + (books.filterNot { readingId(it.info) == readingId(info) } + book)
            .joinToString(",") { b ->
                val i = b.info
                "{\"readingId\":${q(readingId(i))},\"bookId\":${q(i.bookId)},\"name\":${q(i.name)},\"author\":${q(i.author)}," +
                    "\"intro\":${q(i.intro)},\"sourceName\":${q(b.sourceName)},\"addedAt\":${b.addedAt}," +
                    "\"truncated\":${i.truncated},\"chapters\":[" + i.chapters.joinToString(",") { c ->
                        "{\"itemId\":${q(c.itemId)},\"title\":${q(c.title)},\"locked\":${c.locked}}"
                    } + "]}"
            } + "]}"
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { out -> out.write(json.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            check(tmp.renameTo(file)) { "阅读目录快照保存失败" }
        } finally { tmp.delete() }
    }

    private fun read(): List<ShelfBook> {
        if (!file.exists()) return emptyList()
        val raw = file.readText().trim()
        require(raw.startsWith("{") && raw.endsWith("}") && TinyJson.field(raw, "books")?.let {
            it.startsWith("[") && it.endsWith("]")
        } == true) { "阅读目录快照损坏，已保留原文件" }
        return TinyJson.objects(raw, "books").mapNotNull { o ->
            val id = TinyJson.path(o, "bookId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chapters = TinyJson.objects(o, "chapters").mapNotNull chapter@ { c ->
                val item = TinyJson.path(c, "itemId") ?: return@chapter null
                ChapterRef(item, TinyJson.path(c, "title").orEmpty(), TinyJson.path(c, "locked") == "true")
            }
            ShelfBook(BookInfo(id, TinyJson.path(o, "name").orEmpty(), TinyJson.path(o, "author").orEmpty(),
                chapters, TinyJson.path(o, "truncated") == "true", TinyJson.path(o, "intro").orEmpty()),
                TinyJson.path(o, "sourceName").orEmpty(), TinyJson.path(o, "addedAt")?.toLongOrNull() ?: 0L)
        }
    }
}

/** 旧候选地址不能套用收藏当前源的目录。空目录只在没有完整目录时返回。 */
fun resolveReadingBook(bookId: String, job: BookInfo?, shelf: ShelfBook?, snapshot: ShelfBook?): BookInfo? {
    return resolveReadingIdentity(bookId, null, listOfNotNull(job, shelf?.info, snapshot?.info))
}

/** 来源阅读身份版本独立于网络地址。规范化与 sameBook 保持一致。 */
fun readingId(info: BookInfo): String {
    fun normalized(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
    val name = normalized(info.name)
    val author = normalized(info.author)
    val identity = "${name.length}:$name${author.length}:$author"
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    return "${info.bookId}#reading-v1:$digest"
}

/** 新事件严格按身份，旧事件只有地址对应唯一逻辑身份时才解析。 */
fun resolveReadingIdentity(bookId: String, id: String?, choices: Collection<BookInfo>): BookInfo? {
    val candidates = choices.filter { it.bookId == bookId }
    val target = if (!id.isNullOrBlank()) id else candidates.map(::readingId).distinct().singleOrNull() ?: return null
    return candidates.filter { readingId(it) == target }.let { matches ->
        matches.firstOrNull { it.chapters.isNotEmpty() } ?: matches.firstOrNull()
    }
}

fun resolveReadingBook(info: BookInfo, job: BookInfo?, shelf: ShelfBook?, snapshot: ShelfBook?): BookInfo? =
    resolveReadingIdentity(info.bookId, readingId(info), listOfNotNull(job, shelf?.info, snapshot?.info))

fun progressKey(info: BookInfo): String = progressKey(readingId(info))

/** 同地址多身份时，地址进度不能猜归属。 */
fun canMigrateReadingProgress(info: BookInfo, known: Collection<BookInfo>): Boolean =
    known.filter { it.bookId == info.bookId }.map(::readingId).distinct().let {
        it.isEmpty() || it == listOf(readingId(info))
    }

/** 完整地址或阅读身份散列，修复长网址前 64 字符相同造成的进度串书。 */
fun progressKey(bookId: String): String = "v2_" + MessageDigest.getInstance("SHA-256")
    .digest(bookId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

/** 旧键没有完整身份，仅无已知冲突时允许迁移。 */
fun canMigrateLegacyProgress(bookId: String, knownBookIds: Collection<String>): Boolean =
    knownBookIds.none { it != bookId && it.take(64) == bookId.take(64) }

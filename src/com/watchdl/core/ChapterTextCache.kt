package com.watchdl.core

/** 来源实例、逻辑书籍和章节共同标识正文，不用可能碰撞的哈希字符串。 */
class ChapterTextKey(
    private val source: Any?,
    private val bookId: String,
    private val name: String,
    private val author: String,
    private val itemId: String,
    private val title: String,
) {
    override fun equals(other: Any?): Boolean = other is ChapterTextKey &&
        source === other.source && bookId == other.bookId && name == other.name &&
        author == other.author && itemId == other.itemId && title == other.title

    override fun hashCode(): Int {
        var result = System.identityHashCode(source)
        for (part in listOf(bookId, name, author, itemId, title)) result = 31 * result + part.hashCode()
        return result
    }
}

/** 缓存未应用替换规则的正文，条数与字符数双限，避免单章异常膨胀。 */
class ChapterTextCache(private val maxEntries: Int = 24, private val maxChars: Int = 512 * 1024) {
    init { require(maxEntries > 0 && maxChars > 0) }
    private val entries = LinkedHashMap<ChapterTextKey, String>(16, 0.75f, true)
    private var chars = 0

    @Synchronized fun get(key: ChapterTextKey): String? = entries[key]

    @Synchronized fun put(key: ChapterTextKey, text: String) {
        entries.remove(key)?.let { chars -= it.length }
        if (text.isEmpty() || text.length > maxChars) return
        entries[key] = text
        chars += text.length
        val it = entries.entries.iterator()
        while (entries.size > maxEntries || chars > maxChars) {
            chars -= it.next().value.length
            it.remove()
        }
    }
}

package com.watchdl.core

import java.text.Normalizer
import java.util.Locale

/** 只规整兼容字符、大小写和空白，不剥书名后缀、不把未知作者当通配符。 */
private fun identityText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

fun sameBook(name: String, author: String, otherName: String, otherAuthor: String): Boolean =
    identityText(name).isNotEmpty() && identityText(name) == identityText(otherName) &&
        identityText(author) == identityText(otherAuthor)

fun sameBookCandidates(info: BookInfo, hits: List<BookHit>): List<BookHit> = hits.filter {
    it.bookId.isNotBlank() && sameBook(info.name, info.author, it.name, it.author)
}.distinctBy { it.sourceName to it.bookId }

/** 优先唯一同标题；找不到时保留旧章号并夹到新目录范围，不伪造标题命中。 */
fun swappedChapterIndex(old: BookInfo, next: BookInfo, index: Int): Pair<Int, Boolean> {
    if (next.chapters.isEmpty()) return 0 to false
    val title = old.chapters.getOrNull(index)?.title?.let(::identityText).orEmpty()
    val matches = if (title.isEmpty()) emptyList() else next.chapters.indices.filter {
        identityText(next.chapters[it].title) == title
    }
    return if (matches.size == 1) matches.single() to true
        else index.coerceIn(0, next.chapters.lastIndex) to false
}

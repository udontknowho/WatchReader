package com.watchdl.core

/**
 * 全文搜索的「词怎么匹配」与「命中段怎么切」（#23 收尾）。
 *
 * 两件事都必须能在 JVM 上单测，也都要在后台线程里跑，所以留在 core：
 * Android 的 `SpannableString` 只能在屏里拼，这里只回答「哪几段命中」。
 */

/** 摘录里的一段。[hit] 为真表示这段是命中词，屏里给它上主色。 */
data class FindSeg(val text: String, val hit: Boolean)

/** 单段文字最多标几处。整章命中几百次时光上色就能把绘制拖慢。 */
const val FIND_SPAN_MAX = 200

/**
 * 关键词 → 匹配器。返回原文里所有非重叠命中区间。
 *
 * [useRegex] 打开时走 [SafeRegex]：正则写不成立就**退回字面匹配**而不是报错，
 * 让屏先给一屏结果更划算；要不要提示由屏拿 [findRegexUsable] 决定。
 * 字面匹配不分大小写，中文书名里夹英文单词很常见。
 */
fun findMatcher(query: String, useRegex: Boolean): (String) -> List<IntRange> {
    val q = query.trim()
    if (q.isEmpty()) return { _ -> emptyList() }
    val re = if (useRegex) SafeRegex.compile(q) else null
    if (re == null) return { text -> literalRanges(text, q) }
    return { text -> regexRanges(re, text) }
}

/** 这条正则能不能编译。屏据此决定「已按字面匹配」的提示要不要弹。 */
fun findRegexUsable(query: String): Boolean =
    query.isNotBlank() && SafeRegex.compile(query.trim()) != null

private fun literalRanges(text: String, q: String): List<IntRange> {
    if (text.isEmpty() || q.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var from = 0
    while (from <= text.length - q.length) {
        val at = text.indexOf(q, from, ignoreCase = true)
        if (at < 0) break
        out.add(at until at + q.length)
        if (out.size >= FIND_SPAN_MAX) break
        from = at + q.length
    }
    return out
}

private fun regexRanges(re: Regex, text: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var from = 0
    while (from <= text.length) {
        val m = re.find(text, from) ?: break
        // 零宽命中（`^`、`\b`、`(?=)`）不前进就是死循环，跳一格再看。
        val next = if (m.range.isEmpty()) m.range.first + 1 else m.range.last + 1
        if (!m.range.isEmpty()) out.add(m.range)
        if (out.size >= FIND_SPAN_MAX) break
        from = next
    }
    return out
}

/**
 * 把文字按命中区间切成分段，屏里逐段上色。
 *
 * 区间可能重叠、乱序、越界（正则的捕获组、反向引用会给到同一段），
 * 先筛掉非法区间再排序合并，否则上色时会出现互相覆盖的花色块。
 */
fun findHighlight(text: String, ranges: List<IntRange>): List<FindSeg> {
    if (text.isEmpty()) return emptyList()
    val clean = ranges
        .filter { it.first >= 0 && it.last < text.length && it.first <= it.last }
        .sortedBy { it.first }
    if (clean.isEmpty()) return listOf(FindSeg(text, false))
    // 相接的区间并成一段：正则的捕获组常给出首尾相抵的两段，
    // 不并用一个 span 就够的地方要挂两个，绘制时还要防颜色叠加。
    val merged = ArrayList<IntRange>(clean.size)
    for (r in clean) {
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last + 1) {
            merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
        } else {
            merged.add(r)
        }
    }
    val out = ArrayList<FindSeg>(merged.size * 2 + 1)
    var cursor = 0
    for (r in merged) {
        val from = maxOf(r.first, cursor)
        if (from > cursor) out.add(FindSeg(text.substring(cursor, from), false))
        val to = r.last + 1
        if (to > from) out.add(FindSeg(text.substring(from, to), true))
        cursor = maxOf(cursor, to)
    }
    if (cursor < text.length) out.add(FindSeg(text.substring(cursor), false))
    return out
}

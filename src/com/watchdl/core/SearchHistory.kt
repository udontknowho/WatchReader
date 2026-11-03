package com.watchdl.core

import java.io.File

/**
 * 搜索屏的两块纯逻辑：关键词历史与精准过滤。界面只负责摆放。
 *
 * 依据本地参考仓 `~/ref/legado-novel`（Compose/MD3，3.26.21）的
 * `ui/book/search/SearchScreen.kt` 与 `SearchResultOrdering.kt`：
 * legado 把历史存进 Room 的 `searchkeywords` 表，命中项在 `MatchMode.EXACT`
 * 下只留「书名或作者含关键词」的书，统计牌写「结果 N · 进度 done/total」。
 * 我们的历史落成一行一词的小文件（`filesDir/search/history.txt`），
 * 过滤与文案做成纯函数，JVM 自检能直接断言真实现。
 */

/** 历史总条数上限。手表磁盘几十兆，历史没人清就一直长，超过丢最旧的。 */
const val SEARCH_HISTORY_MAX = 10

/** 菜单里一屏放得下的条数。189 × 248 dp 上 8 行正好占满，再多要滑。 */
const val SEARCH_HISTORY_SHOWN = 8

/**
 * 去重键：去首尾空白 + 忽略大小写。
 *
 * 用户敲过「ABC」再敲「abc」，不该在历史里占两行 —— 搜出来是同一批书。
 */
fun searchHistoryKey(word: String): String = word.trim().lowercase()

/**
 * 往历史里加一条，返回新列表（最新在前，已含上限裁剪）。
 *
 * 空词不入库：输入框确认空串时调用方会拦，这里再兜一次，
 * 否则历史菜单第一行会是一条点不出东西的空白。
 */
fun searchHistoryAdd(
    existing: List<String>,
    keyword: String,
    limit: Int = SEARCH_HISTORY_MAX,
): List<String> {
    val k = searchHistoryKey(keyword)
    if (k.isEmpty()) return existing.toList()
    val word = keyword.trim()
    return (listOf(word) + existing.filter { searchHistoryKey(it) != k }).take(limit)
}

/** 读盘：按行拆，去空、去重、裁剪。坏行直接跳过，一条都不留也不报错。 */
fun searchHistoryParse(text: String, limit: Int = SEARCH_HISTORY_MAX): List<String> {
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    for (raw in text.lineSequence()) {
        val word = raw.trim()
        if (word.isEmpty()) continue
        if (!seen.add(searchHistoryKey(word))) continue
        out.add(word)
        if (out.size >= limit) break
    }
    return out
}

/** 落盘文本：一行一词，最新在前。 */
fun searchHistoryFormat(words: List<String>): String = words.joinToString("\n")

/**
 * 这一条命中是否算「精准」。
 *
 * 照 legado `SearchResultOrdering`：`EXACT` 档只认书名或作者含关键词，
 * 且不分大小写 —— 英文书名各源大小写写法不一致。
 */
fun searchPreciseKeep(hit: BookHit, keyword: String): Boolean {
    val k = keyword.trim()
    if (k.isEmpty()) return true
    return hit.name.contains(k, ignoreCase = true) || hit.author.contains(k, ignoreCase = true)
}

/** 列表版，给非流式调用方用。 */
fun searchPreciseHits(hits: List<BookHit>, keyword: String): List<BookHit> =
    hits.filter { searchPreciseKeep(it, keyword) }

/**
 * 结果排序档：0 等值、1 包含、2 其它。
 *
 * 照 legado `SearchResultOrdering.sortedWithSearchPriority`：
 * `name` 或 `author` 等于关键词进 0 档，包含关键词进 1 档，剩下的垫底。
 * legado 中间还有一档「类型/标签含关键词」，我们的 [BookHit] 没有 kind 字段，
 * 那一档不存在 —— 少一档只影响「类型命中」的书，其余相对顺序与 legado 一致。
 */
fun searchRankTier(hit: BookHit, keyword: String): Int {
    val k = keyword.trim()
    if (k.isEmpty()) return 0
    val author = hit.author.trim()
    // 先看作者等值：MatchScore 只认书名，作者命中会被压到最后一档。
    if (author.isNotEmpty() && author.equals(k, ignoreCase = true)) return 0
    // MatchScore 分大小写，英文书名各源写法不一（Douluo Dalu / douluo），
    // 先两边都转小写再比 —— legado 那边也是 `ignoreCase = true`。
    return when (MatchScore.of(hit.name.trim().lowercase(), k.lowercase())) {
        0 -> 0                    // 书名就是关键词
        1, 2 -> 1                 // 前缀 / 包含
        else -> if (author.contains(k, ignoreCase = true)) 1 else 2
    }
}

/**
 * 按 legado 的显示顺序排：档位升序，档内**源数降序**（多源都有的书更可信）。
 *
 * 末尾用书名兜底，与 legado 的「稳定排序 = 先到先得」有意不同：
 * 边跑边上屏时每个源回来的顺序不一样，不留兜底键的话列表会在几秒里反复重排。
 */
fun searchRanked(
    hits: List<BookHit>,
    keyword: String,
    sourceCount: (BookHit) -> Int,
): List<BookHit> = hits.sortedWith(
    compareBy<BookHit> { searchRankTier(it, keyword) }
        .thenByDescending { sourceCount(it) }
        .thenBy { it.name.trim() },
)

/** 删掉一条词，返回新列表。键按 [searchHistoryKey] 比，大小写与空白不同也算同一条。 */
fun searchHistoryRemove(existing: List<String>, word: String): List<String> {
    val k = searchHistoryKey(word)
    return existing.filter { searchHistoryKey(it) != k }
}

/** 统计牌第一行。`total` 为 0（重放时没记过）只留条数，别写「进度 0/0」。 */
fun searchSummaryText(count: Int, responded: Int, total: Int): String =
    if (total <= 0) "结果 $count" else "结果 $count · 进度 $responded/$total"

/** 统计牌第二行：有源没回话时提醒结果可能不全。都回来了就不提。 */
fun searchPartialHint(responded: Int, total: Int): String? =
    if (total > 0 && responded < total) "部分书源未返回，结果可能不完整" else null

/**
 * 列表项第二行：`作者 · 最新: 章名`。
 *
 * legado 的 `SearchBookListItem` 把 `latestChapterTitle` 接在作者后面，
 * 没有就只留作者。我们没有独立的最新章字段，`extra` 有就用它，
 * 否则退回源名 —— 那正是用户挑源时要看的那一条。
 */
fun searchHitLine(author: String, extra: String, sourceName: String): String {
    val parts = ArrayList<String>(3)
    if (author.isNotBlank()) parts.add(author.trim())
    val latest = extra.trim()
    // 源模板没展开时会带 `{{`/`@{`，那种片段不能上屏。
    val cleanSource = sourceName.trim().takeIf { it.isNotEmpty() && !it.contains("{{") && !it.contains("@{") }
    when {
        latest.isNotEmpty() -> parts.add("最新: $latest")
        cleanSource != null -> parts.add(cleanSource)
    }
    return parts.joinToString(" · ")
}

/** 源数小牌上的字：只有一本时不出牌，返回 null。 */
fun searchSourceBadge(n: Int): String? = if (n > 1) "$n" else null

/**
 * 历史文件读写。`filesDir/search/history.txt`，一行一词。
 *
 * 用目录作参数（不用全局 `file` 字段）是为了 JVM 自检能指到临时目录，
 * 也不必去改 Activity 的启动装配。
 */
object SearchHistoryStore {

    const val NAME = "history.txt"

    /** 文件位置。 */
    fun file(dir: File?): File? = dir?.let { File(it, NAME) }

    /** 读历史。没目录、没文件、内容坏了，一律当空表。 */
    fun load(dir: File?, limit: Int = SEARCH_HISTORY_MAX): List<String> {
        val f = file(dir) ?: return emptyList()
        if (!f.isFile) return emptyList()
        return runCatching { searchHistoryParse(f.readText(Charsets.UTF_8), limit) }
            .getOrDefault(emptyList())
    }

    /** 同步落盘。进程随时可能被杀，异步写会丢最后一次搜索。 */
    fun save(dir: File?, words: List<String>) {
        val f = file(dir) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(searchHistoryFormat(words), Charsets.UTF_8)
        }
    }

    /** 记一条并落盘，返回更新后的列表。 */
    fun add(dir: File?, keyword: String, limit: Int = SEARCH_HISTORY_MAX): List<String> {
        val next = searchHistoryAdd(load(dir, limit), keyword, limit)
        save(dir, next)
        return next
    }

    /** 删一条并落盘。legado 的历史行右侧有 ×，我们走二级菜单（[searchHistoryRemove]）。 */
    fun remove(dir: File?, word: String, limit: Int = SEARCH_HISTORY_MAX): List<String> {
        val next = searchHistoryRemove(load(dir, limit), word)
        save(dir, next)
        return next
    }

    /** 清空：文件内容写成空串，不删文件 —— 删了下次读还是空表，留白更省事。 */
    fun clear(dir: File?): List<String> {
        save(dir, emptyList())
        return emptyList()
    }
}

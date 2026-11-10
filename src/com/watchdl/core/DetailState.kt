package com.watchdl.core

/**
 * 书详情屏的四处推断：统计块三行、书架动作卡的文案与图标、简介折叠、移出书架文案。
 *
 * 依据本地参考仓 `~/ref/legado-novel`（Compose/MD3，3.26.21）的
 * `ui/book/info/BookInfoScreen.kt`：`BookInfoSummary` 1479-1580、
 * `BookInfoActions` 1336-1479、`BookInfoIntro` 1580-1696。
 * 那里全是 Composable 里的即时表达式；我们抽成纯函数，JVM 自检能直接覆盖，
 * 界面只负责摆放与配色。
 */

/** 统计行第一格，照 legado `read_chapter_total`「共 %d 章」。 */
fun detailTotalText(total: Int): String = "共 ${total.coerceAtLeast(0)} 章"

/**
 * 阅读状态词，照 legado `BookInfoSummary` 的三态：
 * `durChapterIndex == 0 && durChapterPos == 0` → `is_unread`「未读」；
 * `index + 1 == total` → 「已读完」；其余 `read_chapter_index`「已读 %d 章」。
 *
 * 我们没有章内位置（legado 的 `durChapterPos`），下标 0 一律算「还没读过」——
 * 本应用的 `readProgress` 本来就把 0 与「没记录过」当同一件事。
 */
fun detailReadState(total: Int, readIndex: Int): String {
    val n = total.coerceAtLeast(0)
    val i = readIndex.coerceAtLeast(0)
    if (n <= 0) return "未读"
    if (i == 0) return "未读"
    if (i + 1 >= n) return "已读完"
    return "已读 ${i + 1} 章"
}

/**
 * 统计行三段：`共 N 章` / `|` / 状态词。
 *
 * 拆成一段一段是因为 legado 那里给三段三种颜色（primary 加粗 / secondary / secondary）。
 * 拼装留在纯函数里，自检能直接断言顺序与措辞。
 */
fun detailStatParts(total: Int, readIndex: Int): List<String> =
    listOf(detailTotalText(total), "|", detailReadState(total, readIndex))

/**
 * 目录加载失败的提示语，照 legado `error_load_toc`「加载目录失败」。
 *
 * legado 把它接在统计行第三格后面（同一行，` · ` 分隔）。189 dp 一行已经装着
 * 「共 N 章 | 已读 N 章」，再接一句就溢出，所以本版另起一行，配色仍用 error。
 */
fun detailTocFailText(): String = "加载目录失败"

/**
 * 目录为空时那句提示，分两种：取失败过，和还没取。
 *
 * 以前两种都写「目录还没取，点「查看目录」开始」—— 刷新失败之后屏上还是这句，
 * 用户会以为按钮没生效、反复点。失败要如实说是失败。
 */
fun detailEmptyTocHint(failed: Boolean): String =
    if (failed) "目录加载失败，点「查看目录」重试" else "目录还没取，点「查看目录」开始"

/** 统计块第一行：「在读 · 章名」。还没有章节时 legado 用 `loading`「加载中…」占位。 */
fun detailCurrentLine(title: String?): String =
    "在读 · " + (title?.takeIf { it.isNotBlank() } ?: "加载中…")

/** 统计块第二行：「最新 · 章名」。没有章节时返回 null —— 整行不画，不留空行。 */
fun detailLatestLine(title: String?): String? =
    title?.takeIf { it.isNotBlank() }?.let { "最新 · $it" }

/**
 * 头部那一枚小牌：下载进度。
 *
 * legado 那个位置是 `kindLabels`（类型标签，`TextCard` 小牌）。我们没有类型系统，
 * 用「这本书下到哪了」顶上。**只一枚** —— 189 dp 一行放不下第二枚。
 * 「下载进度」是 legado 没有的概念：它只按章缓存正文，不在详情页报进度。
 */
fun detailDownloadTag(done: Int, total: Int): String {
    val n = total.coerceAtLeast(0)
    if (n <= 0) return "未下载"
    val d = done.coerceIn(0, n)
    return if (d <= 0) "未下载" else "已下载 $d/$n"
}

/**
 * 书架动作卡的文案与图标，照 legado `BookInfoActions` 的 `inBookshelf` 分叉：
 * 未入架 `BookmarkAdd` + `add_to_bookshelf`「放入书架」，已入架 `Book` +
 * `already_in_bookshelf`「已在书架」（再点给 `click_to_remove`「点击删除」）。
 *
 * 入架只更新收藏元数据，不创建下载作业。已收藏时可移出，历史与缓存继续保留。
 *
 * [glyph] 是 [com.watchdl.ui.theme.Icons.DATA] 的键名；core 不认识视图层，
 * 只把键当字符串带出来，取不到字形时调用点自己退回文字。
 */
data class DetailShelfAction(val label: String, val glyph: String, val desc: String)

fun detailShelfAction(inShelf: Boolean): DetailShelfAction = if (inShelf) {
    DetailShelfAction("已在书架", "bookmark", "已在书架，移出后保留历史与缓存")
} else {
    DetailShelfAction("放入书架", "plus", "仅收藏，不会自动下载")
}

/**
 * 移出书架的确认正文。
 *
 * 收藏与正文缓存独立，移出时不清理阅读历史、进度或已下载正文。
 */
fun detailRemoveMessage(name: String): String =
    "移出《${name.ifBlank { "未命名" }}》：仅移除收藏，保留阅读历史、进度与缓存"

/** 简介折叠按钮的文案，照 legado `expand`「展开」/ `collapse`「折叠」。 */
fun detailIntroToggleLabel(expanded: Boolean): String = if (expanded) "折叠" else "展开"

/**
 * 简介要不要给折叠按钮。
 *
 * 189 dp 宽、12 sp 下三行约 20 字/行（实测中文在 12 sp + 157 dp 内容宽里每行 19–21 字），
 * 正文短于这个量根本不会截断 —— 摆一个按了没反应的「展开」比不摆更糟。
 */
fun detailIntroToggleVisible(intro: String, lines: Int = 3): Boolean =
    intro.trim().length > lines * 20

/**
 * 简介正文；没有简介返回 null。
 *
 * legado 的 `parseBookInfoIntro` 先判 `isNullOrBlank`，无内容时画
 * `intro_show_null`「简介：暂无简介」。这里多一道「有效字符不足 10 个」的过滤：
 * 兜底摘要常是「...」这类占位，只判非空会在屏上留一句假简介。
 */
fun detailIntroText(intro: String): String? {
    val t = intro.trim()
    return if (t.isNotBlank() && t.count { it.isLetterOrDigit() } >= 10) t else null
}

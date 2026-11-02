package com.watchdl.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 书签页与阅读记录页的纯逻辑：搜索、排序、多选、文案。
 *
 * 依据本地参考仓 `~/ref/legado-novel`（Compose/MD3，3.26.21）的
 * `ui/book/bookmark/AllBookmarkScreen.kt`、`ui/book/readRecord/ReadRecordScreen.kt`：
 * legado 的书签是「按书总览 → 本书详情（书签 / 笔记）」两层，记录页有三种显示模式
 * （聚合 / 时间线 / 最新），两页都有顶栏搜索与多选删除。
 *
 * 我们的数据只有 [Bookmark]（书 + 章号 + 摘录 + 时间）与每本书的最后位置/时刻，
 * 没有笔记表、没有逐章历史，所以总览层与记录三模式都造不出来。
 * 这里留下两页共用的那部分：命中判定、排序、选择集、文案。
 * 放纯函数是为了 JVM 自检能直接断言 —— 界面只负责摆放。
 */

/** 选择集里用的是「书 + 章号」，不是列表下标：重画之后下标会变。 */
private const val KEY_SEP = "\u0000"

/** 一条书签的身份键。同书同章就是同一条（[Bookmark.sameAs] 同口径）。 */
fun markKey(b: Bookmark): String = "${b.bookId}$KEY_SEP${b.index}"

/**
 * 章名。空章名退回「第 N 章」，照 legado 的 `feature_bookmarks_chapter_fallback`。
 *
 * 章名会空是因为换源之后旧书签只剩章号：标题字段没跟着写。
 */
fun markChapterLabel(b: Bookmark): String =
    b.title.ifBlank { "第 ${b.index + 1} 章" }

/** 摘录压成一行。存档里可能带换行，两行摘录里夹一个空行会把行高顶高。 */
fun markExcerpt(b: Bookmark): String = b.excerpt.replace(Regex("\\s+"), " ").trim()

/**
 * 书签第三行：《书名》· 时间。单书页的书名已经在顶栏，所以只用时间。
 *
 * 相对时间只认到「N 天前」：手表上没人拿它当时间线看，
 * 更细的分钟与更老的日期都不改变「这条是什么时候加的」。
 */
fun markMeta(b: Bookmark, now: Long = System.currentTimeMillis()): String =
    if (b.bookName.isBlank()) markTimeLabel(b.at, now)
    else "《${b.bookName}》· ${markTimeLabel(b.at, now)}"

/** 时刻 → 今天 / 昨天 / N 天前 / 年月日。 */
fun markTimeLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0L) return "时间未知"
    val days = dayIndex(now) - dayIndex(at)
    return when {
        days <= 0 -> "今天"
        days == 1 -> "昨天"
        days < 7 -> "$days 天前"
        // 老于一周就给绝对日期：再写「40 天前」还得自己换算。
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))
    }
}

/**
 * 时刻 → 单调的天序号。
 *
 * 用 Calendar 的「年 × 366 + 年内第几天」而不是除法：夏令时与闰年都不会跳号。
 * 一年按 366 算，多出来的一天不影响「相差几天」。
 */
private fun dayIndex(ms: Long): Int {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return c.get(Calendar.YEAR) * 366 + c.get(Calendar.DAY_OF_YEAR)
}

/** 命中：书名、章名、摘录三处都算。用户记得住的多半是摘录里那半句。 */
fun markMatch(b: Bookmark, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return b.bookName.contains(q, ignoreCase = true) ||
        b.title.contains(q, ignoreCase = true) ||
        b.excerpt.contains(q, ignoreCase = true)
}

/** 排序：按书名分组，组内按章号。等于 legado 总览的「按书分组 + 组内顺序」。 */
fun markSorted(items: List<Bookmark>): List<Bookmark> =
    items.sortedWith(compareBy({ it.bookName }, { it.index }))

/** 屏幕真正要画的那一份：先筛后排，两件事都不在 View 里做。 */
fun markShown(items: List<Bookmark>, query: String): List<Bookmark> =
    markSorted(items.filter { markMatch(it, query) })

/** 列表分组标题。搜索态优先，与书源 / 替换两页同形。 */
fun markGroupLabel(query: String, count: Int): String {
    val q = query.trim()
    val n = count.coerceAtLeast(0)
    return if (q.isEmpty()) "全部书签（$n）" else "「$q」$n 条"
}

/**
 * 顶栏副标题：不搜索时给「共 N 条 · M 本」，搜索时给命中数。
 *
 * 统计从列表上方的分组标题挪到顶栏：248 dp 高的屏上，多一行统计就少一条书签。
 */
fun markSubtitle(all: List<Bookmark>, shown: List<Bookmark>, query: String): String {
    val q = query.trim()
    if (q.isNotEmpty()) return "「$q」命中 ${shown.size} 条"
    return "共 ${all.size} 条 · ${all.map { it.bookId }.distinct().size} 本"
}

/** 多选：点一条切选中。返回值表示「现在选中了」。 */
fun markSelectionToggle(selected: MutableSet<String>, key: String): Boolean =
    if (selected.remove(key)) false else {
        selected.add(key)
        true
    }

/** 全选 / 全不选。与书源、替换两页同语义：已全选时再点清空。 */
fun markSelectionSelectAll(selected: MutableSet<String>, keys: List<String>): Boolean {
    val all = keys.isNotEmpty() && selected.size == keys.size && selected.containsAll(keys)
    selected.clear()
    if (!all) selected += keys
    return !all
}

/** 丢掉已经不存在的选中项。返回 false 表示一条不剩，界面该退出选择态。 */
fun markSelectionPrune(selected: MutableSet<String>, keys: List<String>): Boolean {
    selected.retainAll(keys.toSet())
    return selected.isNotEmpty()
}

/** 删除确认文案。legado 的 `delete_bookmark_message` 只说「确定删除」，这里写明后果。 */
fun markDeleteMessage(count: Int): String =
    "删掉这 ${count.coerceAtLeast(1)} 条书签就找不回来。"

// ── 阅读记录 ──────────────────────────────────────────

/**
 * 记录命中：书名或作者。
 *
 * legado 记录页的搜索按书名与章节名匹配（它有逐章历史）；我们只有书，
 * 所以作者成了第二个可搜字段 —— 用户经常记得「谁写的」记不住书名。
 */
fun recordMatch(name: String, author: String, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return name.contains(q, ignoreCase = true) || author.contains(q, ignoreCase = true)
}

/**
 * 副标题：「读至 12/345 章 · 3%」。
 *
 * 总量为 0 时（job.json 里没有章节表）不报百分比：分母是零，
 * 写成「0%」会让人以为刚开头，而实际是不知道全书多长。
 */
fun recordSubtitle(index: Int, total: Int): String {
    if (total <= 0) return "读至 ${index + 1} 章"
    return "读至 ${index + 1}/$total 章 · ${index * 100 / total}%"
}

/**
 * 顶栏副标题。
 *
 * 写成「按最近阅读排列」是因为只有这一档：legado 的聚合与时间线要逐章记录，
 * 每本书只剩最后一次位置与时刻，造不出那两档，就不该在菜单里给假选项。
 */
fun recordSubtitleText(count: Int): String =
    "共 ${count.coerceAtLeast(0)} 本 · 按最近阅读排列"

/** 删记录确认文案。记录只影响列表，正文与书都在本地。 */
fun recordDeleteMessage(count: Int): String =
    "删掉这 ${count.coerceAtLeast(1)} 条记录只影响列表，书和正文都还在。"

/** 清空记录的确认文案。 */
fun recordClearMessage(): String =
    "清空后这里会变空，书和正文都还在，下次翻开就又有了。"

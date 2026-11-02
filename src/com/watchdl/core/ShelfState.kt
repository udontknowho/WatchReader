package com.watchdl.core

/**
 * 书架卡上的三处推断：未读角标、来源标签、副标题、选择集合。
 *
 * 依据本地参考仓 `~/ref/legado-novel`（Compose/MD3，3.26.21）的
 * `ui/main/bookshelf/BookItem.kt`：`badgeText = unreadText`、
 * `leftBottomText = matchedSourceLabel ?: bookTypeLabel`、`isSelected` 两处。
 * 那里是 Composable 里的即时表达式；我们抽成纯函数，JVM 自检能直接覆盖，
 * 界面只负责摆放。
 */

/**
 * 未读章数，照 legado `Book.getUnreadChapterNum()`：`total - durChapterIndex - 1`。
 *
 * [readIndex] 是当前在读章的下标（0 基），0 与「没记录过」等价 ——
 * 本应用的 `readProgress` 也把 `-1` 收成 0，所以这里只看下界，不看 -1。
 */
fun shelfUnreadCount(total: Int, readIndex: Int): Int {
    if (total <= 0) return 0
    val cur = readIndex.coerceAtLeast(0)
    return (total - cur - 1).coerceIn(0, total)
}

/**
 * 角标文案。0 返回 null（不画），超过 99 折成 `99+`。
 *
 * 为什么不用真数：卡宽三列下 47 dp，角标牌上限是封面的 1/3（约 15 dp），
 * 四位数在 8 sp 下也放不进那块地方，折掉反而能读。
 */
fun shelfBadgeText(unread: Int): String? = when {
    unread <= 0 -> null
    unread > 99 -> "99+"
    else -> unread.toString()
}

/** 书架副标题（legado `uiState.subtitle`）：`N 本 · 在读 M`。 */
fun shelfSubtitle(bookCount: Int, readingCount: Int): String {
    val n = bookCount.coerceAtLeast(0)
    val m = readingCount.coerceIn(0, n)
    return "$n 本 · 在读 $m"
}

/** 选择模式标题，照 legado `bookshelf_selected_count`「已选%1$d本」的措辞。 */
fun shelfSelectTitle(count: Int): String = "已选 ${count.coerceAtLeast(0)}"

/** 选择模式副标题，照 legado `bookshelf_total_count`「共%1$d本」。 */
fun shelfSelectSubtitle(total: Int): String = "共 ${total.coerceAtLeast(0)} 本"

/**
 * 删除确认的正文。
 *
 * legado 的 DeleteBooksConfirmDialog 标题是「删除选中」、正文是「已选 N 本」，
 * 具体删什么交给下面那排勾选。我们没有「删源文件」的分叉（正文就是源），
 * 所以把后果直接写进正文 —— 手表上一句话讲不清后果，用户就真会误删。
 */
fun shelfDeleteMessage(names: List<String>): String = when (names.size) {
    0 -> "没有选中任何书"
    1 -> "删除《${names[0]}》的缓存与目录，不可恢复"
    else -> "删除 ${names.size} 本书的缓存与目录，不可恢复"
}

/**
 * 封面左下角标签：书源名截到 4 字，没有书源给「本地」。
 *
 * legado 那里是 `matchedSourceLabel ?: bookTypeLabel`，其中 bookTypeLabel
 * 在没有类型标记时就是 `R.string.local`「本地」。我们每本都是下到本机的，
 * 「本地」正好是这个兜底。
 */
fun shelfSourceTag(sourceName: String?): String {
    val s = sourceName?.trim().orEmpty()
    return if (s.isEmpty()) "本地" else s.take(4)
}

/**
 * 点一下卡片：已在选中集合里就移出，否则加入。返回操作后是否仍选中。
 *
 * 集合用 LinkedHashSet：腿上的顺序按点击先后，删除确认里列书名时顺序稳定。
 */
fun shelfToggle(selected: MutableSet<String>, bookId: String): Boolean =
    if (selected.remove(bookId)) false else { selected.add(bookId); true }

/**
 * 全选 / 全不选。传进来的 [all] 是当前架上的全部 bookId（不含搜索过滤）。
 * 返回操作后是否变成「全选」状态 —— 图标据此在「全选 / 全不选」之间切。
 */
fun shelfSelectAll(selected: MutableSet<String>, all: Collection<String>): Boolean {
    val wantAll = !(all.isNotEmpty() && selected.containsAll(all))
    selected.clear()
    if (wantAll) selected.addAll(all)
    return wantAll
}

/** 清掉已经不在架上的选中项（删完一本书后集合要跟着收）。返回是否还有选中。 */
fun shelfPruneSelection(selected: MutableSet<String>, alive: Collection<String>): Boolean {
    val keep = alive.toSet()
    selected.retainAll(keep)
    return selected.isNotEmpty()
}

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

// ══════════════════ 排序（legado BookshelfConfigSheet 的「排序 / 顺序」） ══════════════════

/**
 * 排序键。数值照 legado `BookshelfRepository.sortBooks`：
 * 0 = 最近阅读、1 = 更新时间、2 = 书名、5 = 作者。
 *
 * 3（手动拖）与 4（综合）不做：前者要拖拽排序并落盘 order 字段，
 * 我们连 order 都没存过；后者与 1 在只有下载作业的书上结果一样。
 */
object ShelfSort {
    const val RECENT = 0
    const val UPDATED = 1
    const val NAME = 2
    const val AUTHOR = 5

    /** 菜单里能选的键，顺序与 legado 的 bookshelf_px_array 一致。 */
    val keys = listOf(RECENT, UPDATED, NAME, AUTHOR)

    fun label(sort: Int): String = when (sort) {
        UPDATED -> "更新时间"
        NAME -> "书名"
        AUTHOR -> "作者"
        else -> "最近阅读"
    }

    /** 换键时给的默认方向：时间类倒序（新的在前），名字类正序。 */
    fun defaultDesc(sort: Int): Boolean = sort == RECENT || sort == UPDATED
}

/** 排序比对用的四个字段。书架读盘时顺手取出，排序不再回读 job.json。 */
data class ShelfSortKey(
    val name: String,
    val author: String,
    val updatedAt: Long,
    val lastRead: Long,
)

/**
 * 中文按拼音比，照 legado 的 `cnCompare`（那边用 Collator）。
 * 直接 `String.compareTo` 是码点序，「阿」会排到「张」后面。
 */
private val shelfCollator: java.text.Collator by lazy {
    java.text.Collator.getInstance(java.util.Locale.CHINA)
        .apply { strength = java.text.Collator.PRIMARY }
}

/** 升序比较。倒序由调用处取反，与 legado 的 `isDescending` 一个路子。 */
fun shelfCompare(a: ShelfSortKey, b: ShelfSortKey, sort: Int): Int = when (sort) {
    ShelfSort.UPDATED -> a.updatedAt.compareTo(b.updatedAt)
    ShelfSort.NAME -> shelfCollator.compare(a.name, b.name)
    ShelfSort.AUTHOR -> shelfCollator.compare(a.author, b.author)
    else -> a.lastRead.compareTo(b.lastRead)
}

// ══════════════════ 更新目录（legado RefreshTocUseCase 的 AppendOnly 版） ══════════════════

/** 合并结果：新的 job.json 正文、追加了几章、追加后总章数。 */
data class ShelfTocMerge(val json: String, val added: Int, val total: Int)

/**
 * 把新取到的目录**追加**到 job.json 的章节表尾部。
 *
 * 与 legado `RefreshTocUseCase`（删全表再插新表）的差别是有意的：
 * 我们已下的正文按 itemId 存在 chapters.jsonl 里，整表换掉会让正文对不上号。
 * 只追加「旧的没有的章」，旧章的 itemId / 状态原样保留 —— 连载书新增章
 * 本来就在末尾，这条路既补得到新章，又不动已下的书。
 *
 * 没有新章、或原文读不出章节表时返回 null（调用处据此判定「已是最新」）。
 */
fun mergeShelfCatalog(raw: String, fresh: List<ChapterRef>, now: Long = 0L): ShelfTocMerge? {
    if (raw.isBlank() || fresh.isEmpty()) return null
    val oldObjs = TinyJson.objects(raw, "chapters")
    if (oldObjs.isEmpty()) return null
    val seen = HashSet<String>(oldObjs.size * 2)
    val kept = StringBuilder()
    oldObjs.forEachIndexed { i, o ->
        val id = TinyJson.path(o, "itemId") ?: return@forEachIndexed
        if (!seen.add(id)) return@forEachIndexed
        if (kept.isNotEmpty()) kept.append(',')
        val title = TinyJson.path(o, "title").orEmpty()
        kept.append("{\"itemId\":").append(Downloader.quote(id))
            .append(",\"title\":").append(Downloader.quote(title))
            .append(",\"locked\":").append(if (TinyJson.path(o, "locked") == "true") "true" else "false")
            .append(",\"status\":").append(Downloader.quote(TinyJson.path(o, "status") ?: "PENDING"))
            .append('}')
    }
    if (kept.isEmpty()) return null

    val added = StringBuilder()
    var n = 0
    for (ch in fresh) {
        if (ch.itemId.isBlank() || !seen.add(ch.itemId)) continue
        if (added.isNotEmpty()) added.append(',')
        added.append("{\"itemId\":").append(Downloader.quote(ch.itemId))
            .append(",\"title\":").append(Downloader.quote(ch.title))
            .append(",\"locked\":").append(if (ch.locked) "true" else "false")
            .append(",\"status\":\"PENDING\"}")
        n++
    }
    if (n == 0) return null

    val total = seen.size
    val sb = StringBuilder(kept.length + added.length + 160)
    sb.append("{\"bookId\":").append(Downloader.quote(TinyJson.path(raw, "bookId").orEmpty()))
        .append(",\"name\":").append(Downloader.quote(TinyJson.path(raw, "name").orEmpty()))
        .append(",\"author\":").append(Downloader.quote(TinyJson.path(raw, "author").orEmpty()))
        .append(",\"state\":").append(Downloader.quote(TinyJson.path(raw, "state") ?: "PENDING"))
        .append(",\"total\":").append(total)
        // 更新时间就是「最近一次查出新章」的时刻，排序「按更新时间」直接用它。
        .append(",\"updatedAt\":").append(if (now > 0) now else System.currentTimeMillis())
        .append(",\"chapters\":[").append(kept).append(',').append(added).append("]}")
    return ShelfTocMerge(sb.toString(), n, total)
}

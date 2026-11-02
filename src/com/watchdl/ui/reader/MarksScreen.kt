package com.watchdl.ui.reader

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 书签列表。
 *
 * 手表上没有文字选中，「书签」只能是「这一章我停在这儿」。
 * 所以列表按章排序，每条显示章名 + 摘录，点一条直接跳回去。
 *
 * 存盘走 [BookmarkStore]，本页每次进来现读 —— 加删之后回来不会看到旧列表。
 */

/** 本页最多显示多少条。200 条书签全画出来，手表上滚半天也找不到。 */
private const val MARKS_SHOWN = 60

/** 摘录长度。屏宽 189 dp，一行正文能放 12 个汉字左右。 */
private const val EXCERPT_MAX = 24

internal fun MainActivity.showMarks(info: BookInfo) {
    setScreen(Screen.Marks(info))
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "书签 · ${info.name}", onBack = { showToc(info) }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val all = BookmarkStore.forBook(info.bookId)

    if (all.isEmpty()) {
        col.addView(Widgets.emptyState(this, "bookmark", "还没有书签。\n阅读页点「设置」可以加一条。"))
        root.addView(body, Md3.lpWeight(1f))
        return
    }

    col.addView(Widgets.sectionLabel(this, "共 ${all.size} 条"))
    for (b in all.take(MARKS_SHOWN)) {
        // 章号可能因为换源而失效：书源换了目录长度不同，旧的 index 会指到别的章。
        // 越界就显示但跳不了，比悄悄少一条好 —— 用户至少知道它还在。
        val valid = b.index in info.chapters.indices
        val row = Widgets.listItem(
            ctx = this,
            title = if (valid) info.chapters[b.index].title else "${b.title}（已失效）",
            subtitle = b.excerpt.ifBlank { null },
            twoLine = b.excerpt.isNotBlank(),
            onClick = if (valid) ({ jumpToMark(info, b) }) else null,
        )
        col.addView(row)
    }

    if (all.size > MARKS_SHOWN) {
        col.addView(Md3.text(this, "另有 ${all.size - MARKS_SHOWN} 条未显示", Md3.FS_SM, Md3.onSurfaceVariant))
    }

    col.addView(Widgets.button(this, "清空本书书签", "outline") {
        BookmarkStore.save(BookmarkStore.list().filterNot { it.bookId == info.bookId })
        toast("已清空")
        showMarks(info)
    })
    root.addView(body, Md3.lpWeight(1f))
}

/** 跳到一条书签：恢复章 + 章内位置。 */
private fun MainActivity.jumpToMark(info: BookInfo, b: Bookmark) {
    // 位置要在 preview 之前写：preview 建完视图才读 readOffset，
    // 那时候再写就和「上次读到哪」混在一起了。
    if (b.offset > 0) saveOffset(info.bookId, b.index, b.offset, sync = true)
    preview(info, b.index)
}

/**
 * 在当前位置加一条书签。
 *
 * 摘录取当前章正文的前 [EXCERPT_MAX] 字 —— 阅读页并没有「光标」，
 * 拿不到用户正在看的那一句，只能给章首。这比空摘录强，至少能认出哪一章。
 * shortcut: 摘录是章首不是所见位置，等阅读页有滚动位置反查段落再改。
 */
internal fun MainActivity.addMark(info: BookInfo, index: Int, text: String) {
    val title = info.chapters.getOrNull(index)?.title ?: ""
    if (BookmarkStore.has(info.bookId, index)) {
        BookmarkStore.remove(info.bookId, index)
        toast("已移除本章书签")
        return
    }
    val excerpt = text.replace(Regex("\\s+"), " ").trim().take(EXCERPT_MAX)
    BookmarkStore.add(
        Bookmark(
            bookId = info.bookId,
            bookName = info.name,
            index = index,
            title = title,
            offset = readOffset(info.bookId),
            excerpt = excerpt,
            at = System.currentTimeMillis(),
        )
    )
    toast("已加书签")
}

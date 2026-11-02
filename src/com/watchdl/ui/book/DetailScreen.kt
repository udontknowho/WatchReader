package com.watchdl.ui.book

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.reader.preview
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 书详情，照 legado `ui/book/info/BookInfoScreen`。
 *
 * 信息层级照抄：封面 + 书名 + 作者 + 书源 → 四张操作卡 → 在读/最新/共 N 章 → 简介。
 * 裁掉的是背景大图与相关书籍横排：后者是横向列表，189 dp 一排放不下；
 * 前者是材质装饰，要 Compose 的模糊。其余字段一个不留地纵向排下来，靠滚动容纳。
 */
internal fun MainActivity.showDetail(info: BookInfo, src: BookSource? = null) {
    setScreen(Screen.Detail(info, src))
    root.removeAllViews()
    root.addView(Widgets.topBar(this, info.name, onBack = { goBackToPrevious() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    // ── 头部：封面 5:7 + 书名 / 作者 / 书源 ──
    // 手表上封面给 56 dp 宽（手机是 84 dp），高度按 5:7 算出来 78 dp。
    val head = Md3.row(this)
    head.gravity = Gravity.TOP
    val coverW = 56
    val cover = Md3.text(this, coverGlyph(info), 26f, Md3.onPrimaryContainer, bold = true).apply {
        gravity = Gravity.CENTER
        background = Md3.bg(coverColor(info), Md3.R_ITEM.toFloat())
    }
    head.addView(cover, LinearLayout.LayoutParams(
        Md3.dp(this, coverW), Md3.dp(this, coverW * 7 / 5)))
    val meta = Md3.column(this)
    meta.setPadding(Md3.dp(this, Md3.PAD), 0, 0, 0)
    val nameView = Md3.text(this, info.name, Md3.FS_LG, Md3.onSurface, bold = true)
    nameView.maxLines = 2
    nameView.ellipsize = android.text.TextUtils.TruncateAt.END
    meta.addView(nameView)
    meta.addView(Md3.ellipsize(Md3.text(this, "作者：${info.author.ifBlank { "未知" }}",
        Md3.FS_SM, Md3.onSurfaceVariant)))
    srcName(src).takeIf { it.isNotBlank() }?.let { n ->
        meta.addView(Md3.ellipsize(Md3.text(this, "书源：$n", Md3.FS_SM, Md3.onSurfaceVariant)))
    }
    head.addView(meta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    col.addView(head, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = Md3.dp(this@showDetail, Md3.GAP) })

    // ── 四张操作卡，2×2 ──
    // 手机是一行四张；189 dp 一行四张每张只剩 42 dp，改两行两列。
    fun actionCard(glyph: String, label: String, act: () -> Unit): LinearLayout {
        val cell = Md3.column(this)
        cell.gravity = Gravity.CENTER
        cell.background = Md3.ripple(this, Md3.surface3, Md3.R_ITEM.toFloat())
        cell.isClickable = true
        cell.contentDescription = label
        cell.setOnClickListener { act() }
        val icon = Icons.name(glyph)
        cell.addView(
            if (icon != null) Icons.view(this, icon, Md3.primary, 18)
            else Md3.text(this, glyph, Md3.FS_LG, Md3.primary).apply { gravity = Gravity.CENTER },
            Md3.lpDp(this, null, 18))
        cell.addView(Md3.ellipsize(Md3.text(this, label, Md3.FS_SM, Md3.onSurface)).apply {
            gravity = Gravity.CENTER
        })
        return cell
    }
    val acts = listOf(
        Triple("plus", "放入书架") { startDownload(info) },
        Triple("menu", "查看目录") { detailOpenToc(info, src) },
        Triple("swap", "换源") { rememberReturn(); showSwap(info, src) },
        Triple("history", "阅读记录") { showProgress(info) },
    )
    for (pair in acts.chunked(2)) {
        val row = Md3.row(this)
        for ((glyph, label, act) in pair) {
            row.addView(actionCard(glyph, label, act), LinearLayout.LayoutParams(
                0, Md3.dp(this, 46), 1f).apply {
                if (row.childCount > 0) leftMargin = Md3.dp(this@showDetail, Md3.GAP)
            })
        }
        col.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 46))
            .apply { bottomMargin = Md3.dp(this@showDetail, Md3.GAP) })
    }

    // ── 在读 / 最新 / 共 N 章 ──
    // 章节还没取时只有「放进书架」能下载，所以这一段整块省掉，不留空行。
    val readAt = readProgress(info.bookId)
    val done = Downloader.restoreLight(jobsStore(), info.bookId)?.count(ChapterStatus.DONE) ?: 0
    if (info.chapters.isNotEmpty()) {
        val box = Widgets.card(this)
        // legado 两行：「在读 · 章名」「最新 · 章名」。
        info.chapters.getOrNull(readAt)?.let {
            box.addView(Md3.ellipsize(Md3.text(this, "在读 · ${it.title}",
                Md3.FS_SM, Md3.primary)))
        }
        info.chapters.lastOrNull()?.let {
            box.addView(Md3.ellipsize(Md3.text(this, "最新 · ${it.title}",
                Md3.FS_SM, Md3.onSurfaceVariant)))
        }
        box.addView(Md3.text(this, "共 ${info.chapters.size} 章 · 已读 $readAt 章 · 已下载 $done 章",
            Md3.FS_SM, Md3.onSurfaceVariant))
        col.addView(box)
    } else {
        col.addView(Md3.text(this, "目录还没取，点「查看目录」开始", Md3.FS_SM, Md3.onSurfaceVariant)
            .apply { setPadding(0, 0, 0, Md3.dp(this@showDetail, Md3.GAP)) })
    }

    // ── 简介，可展开 ──
    if (info.intro.trim().let { it.isNotBlank() && it.count { c -> c.isLetterOrDigit() } >= 10 }) {
        col.addView(Widgets.sectionLabel(this, "简介"))
        val ic = Widgets.card(this)
        val tv = Md3.text(this, info.intro, Md3.FS_SM, Md3.onSurfaceVariant)
        tv.maxLines = 3
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.contentDescription = "简介，点击展开"
        tv.setOnClickListener {
            val expanded = tv.maxLines != Int.MAX_VALUE
            tv.maxLines = if (expanded) Int.MAX_VALUE else 3
            tv.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
            tv.contentDescription = if (expanded) "简介，点击收起" else "简介，点击展开"
        }
        ic.addView(tv)
        col.addView(ic)
    }
    root.addView(body, Md3.lpWeight(1f))

    // ── 底部「阅读」：照 legado 的 FAB，点一下直接读上次那章 ──
    val go = Widgets.button(this, if (info.chapters.isEmpty()) "取目录并阅读" else "阅读", "filled") {
        if (info.chapters.isEmpty()) detailOpenToc(info, src)
        else preview(info, if (readAt < info.chapters.size) readAt else 0)
    }
    go.layoutParams = Md3.lpDp(this, null, Md3.BTN).apply {
        leftMargin = Md3.dp(this@showDetail, Md3.PAD)
        rightMargin = Md3.dp(this@showDetail, Md3.PAD)
        bottomMargin = Md3.dp(this@showDetail, Md3.GAP)
    }
    root.addView(go)
}

/**
 * 详情页里要章节的那两处动作（查看目录 / 阅读）。
 *
 * 从搜索结果进来时手上只有一个命中项，没有章节表 —— 走 [loadToc] 那条老路
 * （逐源重试 + 先验一章正文），而不是直接开一张空目录。
 */
private fun MainActivity.detailOpenToc(info: BookInfo, src: BookSource?) {
    if (info.chapters.isNotEmpty()) { showToc(info, src); return }
    val candidates = detailCandidates
    if (candidates.isEmpty()) {
        // 兜底：重进这一屏（返回再进来）时候选源没跟着恢复，直接开出空目录
        // 比停在详情页更糟，所以给一句明确的失败提示。
        toast("没有可用的源，请重新搜索这本书")
        return
    }
    rememberReturn()
    loadToc(info.name, info.author, candidates)
}

/** 书源名。legado 源有显示名，番茄源没有，单独给一个。 */
internal fun srcName(src: BookSource?): String = when (src) {
    null -> ""
    is LegadoSource -> src.displayName
    else -> "番茄"
}

/** 详情页大封面的占位字与配色：与书架同一套，同一本书颜色稳定。 */
internal fun MainActivity.coverGlyph(info: BookInfo): String {
    val src = info.name.ifBlank { "未" }
    src.firstOrNull { it.isLetterOrDigit() }?.let { return it.toString() }
    return String(Character.toChars(src.codePointAt(0)))
}

internal fun MainActivity.coverColor(info: BookInfo): Int {
    val palette = intArrayOf(
        Md3.primaryContainer, Md3.secondaryContainer, Md3.surface4, Md3.outlineVariant,
    )
    return palette[Math.floorMod(info.name.hashCode(), palette.size)]
}

package com.watchdl.ui.reader

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.concurrent.thread
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File

/**
 * 阅读器与阅读设置（从 MainActivity 原样搬出，行为未改）。
 *
 * 正文排版状态（readerSize/readerSpacing/readerBackground/readerBody/滚动位置）
 * 仍留在 Activity：它们是 onPause / onSaveInstanceState 要存的进程状态，
 * 且扩展属性带初始化器在 Kotlin 里不成立。
 */

/** ⑥ 阅读器。 */
internal fun MainActivity.showReader(info: BookInfo, index: Int, text: String, src: BookSource? = null) {
    setScreen(Screen.Reader(info, index, text, src))
    saveProgress(info.bookId, index)
    // 浅色底时整套前景色都要换：正文、顶栏、底栏按钮、进度。
    // 以前只换了正文，白底上顶栏还是黑块，上下章还是浅紫，几乎看不见。
    val light = Color.red(readerBackground) > 100
    val readerInk = if (light) Color.rgb(28, 27, 31) else Md3.onSurface
    val readerAccent = if (light) Color.rgb(56, 30, 114) else Md3.primary
    val readerMuted = if (light) Color.rgb(73, 69, 79) else Md3.onSurfaceVariant
    val hairline = if (light) 0x24000000 else 0x24FFFFFF
    root.removeAllViews()

    // legado 阅读页顶栏：书名在上，副行是「章名 · 书源名」。
    // 原来只有章名，看完一章不知道读的是哪本书、正文从哪个源来的。
    // BookSource 接口不带名字（规则源才有 displayName），没名字就只显章名。
    val srcName = (src as? LegadoSource)?.displayName
    val subLine = listOfNotNull(
        info.chapters.getOrNull(index)?.title?.takeIf { it.isNotBlank() },
        srcName?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val bar = Widgets.topBar(this, info.name, onBack = { showToc(info) },
        trailing = listOf("settings" to { showReaderSettings(info, index, text, src) }),
        subtitle = subLine)
    bar.setBackgroundColor(readerBackground)
    if (light) Widgets.tintSubtree(bar, readerInk)
    root.addView(bar)
    root.addView(android.view.View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    body.setBackgroundColor(readerBackground)
    val col = Widgets.contentColumn(body)

    // 正文用专用字号（比正文 UI 大），行距 1.8 在手表上偏松，手机上刚好。
    val tv = Md3.text(this, if (text.isEmpty()) "本章无正文" else text, readerSize, readerInk)
    tv.setLineSpacing(Md3.dp(this, 2).toFloat(), readerSpacing)
    tv.justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
    tv.setPadding(Md3.dp(this, 3), Md3.dp(this, 2), Md3.dp(this, 3), Md3.dp(this, 24))
    col.addView(tv)

    root.addView(body, Md3.lpWeight(1f))

    // 工具格：照 legado 底栅的图标网格（每行 5 个 × N 行）。
    // 189 dp 一行放 5 个只剩 30 dp，改成 3×2 六格。
    root.addView(android.view.View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))
    val grid = Md3.column(this)
    grid.setBackgroundColor(readerBackground)
    fun toolCell(glyph: String, label: String, act: () -> Unit): LinearLayout {
        val cell = Md3.column(this)
        cell.gravity = Gravity.CENTER
        cell.isClickable = true
        cell.contentDescription = label
        cell.background = Md3.ripple(this, Color.TRANSPARENT, Md3.dp(this, 6).toFloat())
        cell.setOnClickListener { act() }
        // 格子只有 30 dp 高，图标 16 dp + 一行 10 sp 标签刚好放下。
        val icon = Icons.name(glyph)
        cell.addView(
            if (icon != null) Icons.view(this, icon, readerAccent, 16)
            else Md3.text(this, glyph, Md3.FS_MD, readerAccent).apply { gravity = Gravity.CENTER },
            Md3.lpDp(this, null, 16))
        cell.addView(Md3.text(this, label, 10f, readerMuted).apply { gravity = Gravity.CENTER })
        return cell
    }
    val tools = listOf(
        Triple("menu", "目录") { showToc(info, src) },
        Triple("bookmark", "书签") { showMarks(info) },
        Triple("swap", "换源") { toast("换源还没做") },
        Triple("settings", "设置") { showReaderSettings(info, index, text, src) },
        Triple("download", "缓存") { startDownload(info) },
        Triple("more", "更多") {
            Widgets.menuDialog(this, null, listOf<Pair<CharSequence, () -> Unit>>(
                "导出 TXT" to { exportTxt(info) },
                "阅读记录" to { showProgress(info) },
            )).show()
        },
    )
    for (chunk in tools.chunked(3)) {
        val row = Md3.row(this)
        for ((glyph, label, act) in chunk) {
            row.addView(toolCell(glyph, label, act), LinearLayout.LayoutParams(0,
                Md3.dp(this, 30), 1f))
        }
        grid.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 30)))
    }
    root.addView(grid)
    root.addView(android.view.View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))

    // 读到哪了：滚动停下再记，滚动过程中每帧写盘没有意义。
    // onPause / onDestroy 会再存一次，覆盖突然退出、切后台的情况。
    readerBody = body
    readerBookId = info.bookId
    readerIndex = index
    body.setOnScrollChangeListener { _, _, y, _, _ ->
        readerOffset = y
        scrollSave?.let { body.removeCallbacks(it) }
        val task = Runnable { saveOffset(info.bookId, index, y) }
        scrollSave = task
        body.postDelayed(task, 400)
    }
    // 恢复上次读到的地方。等布局完成再滚，否则拿不到 maxScroll。
    val restore = readOffset(info.bookId).takeIf { it > 0 }
    if (restore != null) {
        body.post {
            val child = body.getChildAt(0)
            val limit = ((child?.height ?: 0) - body.height +
                body.paddingTop + body.paddingBottom).coerceAtLeast(0)
            body.scrollTo(0, restore.coerceAtMost(limit))
        }
    }

    // 底部：进度 + 上/下一章。两边的点击目标都要够大，手机上单手也能摸到。
    // 底栏必须背实底色 + 一根分隔线：否则正文从按钮下面露出来，看着像排版坏了。
    val foot = Md3.row(this)
    foot.gravity = Gravity.CENTER_VERTICAL
    foot.setPadding(Md3.dp(this, 12), 0, Md3.dp(this, 12), 0)
    foot.setBackgroundColor(readerBackground)
    root.addView(android.view.View(this).apply { setBackgroundColor(hairline) }, Md3.lpDp(this, null, 1))

    val prev = Md3.text(this, "‹ 上一章", Md3.FS_MD, readerAccent, bold = true).apply {
        gravity = Gravity.CENTER
    }
    prev.isClickable = true
    prev.minHeight = Md3.dp(this, Md3.HIT)
    prev.setPadding(Md3.dp(this, 4), 0, Md3.dp(this, 4), 0)
    // 与「下一节」对称：本章还有没读完的节时，回头必须先回上一节。
    prev.setOnClickListener {
        prevChapter(info, index)
    }
    // 第一章时置灰并真的禁用：以前只调 alpha，点了还能按，弹一句「已是第一章」。
    if (index <= 0) { prev.alpha = 0.35f; prev.isClickable = false }
    foot.addView(prev)

    val m = Md3.text(this, "${index + 1} / ${info.chapters.size}", Md3.FS_SM, readerMuted).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        // 宽度不够时会折行，把底栏撑成两行（与左右按钮不共基线）。
        setSingleLine(true)
        minHeight = Md3.dp(this@showReader, Md3.HIT)
    }
    foot.addView(m, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val next = Md3.text(this, "下一章 ›", Md3.FS_MD, readerAccent, bold = true).apply {
        gravity = Gravity.CENTER
    }
    next.isClickable = true
    next.minHeight = Md3.dp(this, Md3.HIT)
    next.setPadding(Md3.dp(this, 4), 0, Md3.dp(this, 4), 0)
    // 本章还有未读的节时，这一下必须先翻节 —— 否则读者点「下一章」，
    // 本章后半截就再也不会被看到。
    next.setOnClickListener {
        nextChapter(info, index)
    }
    if (index >= info.chapters.size - 1) {
        next.alpha = 0.35f
        next.isClickable = false
    }
    foot.addView(next)
    // 高度必须容得下 HIT 高的子项，否则页码被挤到第二行（P0-3）。
    foot.layoutParams = Md3.lpDp(this, null, Md3.HIT)
    root.addView(foot)
}

/** 上一章。跳过连续的锁定章（付费章取不到正文）。 */
private fun MainActivity.prevChapter(info: BookInfo, index: Int) {
    val prev = index - 1
    if (prev < 0) { toast("已是第一章"); return }
    if (info.chapters[prev].locked) {
        val free = (prev downTo 0).firstOrNull { !info.chapters[it].locked }
        if (free == null) { toast("前面都是付费章"); return }
        preview(info, free)
    } else preview(info, prev)
}
/** 从记录的位置续读：章号 + 章内节号。 */
internal fun MainActivity.preview(info: BookInfo, index: Int) {
    val ch = info.chapters[index]
    // 先读本地正文，只有缺章时才在线程中初始化源并联网。
    val src = (curScreen as? Screen.Toc)?.src
        ?: (curScreen as? Screen.Reader)?.src
        ?: tocSrc
    root.removeAllViews()
    root.addView(Widgets.topBar(this, ch.title, onBack = { showToc(info) }))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    (Widgets.contentColumn(body)).addView(Widgets.emptyState(this, "refresh", "正在取正文…"))
    root.addView(body)

    // 读者停在 Toc / Reader 屏上点章节，回来时应该还在原来的屏。
    val owner: Screen = curScreen

    thread {
        try {
            val line = jobsStore().readChapters(info.bookId)[ch.itemId]
            val localHtml = line?.let { TinyJson.path(it, "content") }
            val actualSrc = if (localHtml == null) (src ?: source) else src
            val html = localHtml ?: actualSrc!!.content(ch.itemId, info.bookId)
            val text = Downloader.htmlToText(html).ifEmpty { html.replace(Regex("<[^>]+>"), "\n").trim() }
            postAlive(owner) { showReader(info, index, text, actualSrc) }
        } catch (e: Exception) {
            postAlive(owner) { toast("正文失败：${e.message}"); showToc(info, src) }
        }
    }
}

private fun MainActivity.nextChapter(info: BookInfo, index: Int) {
    val next = index + 1
    if (next >= info.chapters.size) { toast("已是最后一章"); return }
    if (info.chapters[next].locked) {
        // 跳过连续的锁定章
        val free = (next until info.chapters.size).firstOrNull { !info.chapters[it].locked }
        if (free == null) { toast("后面都是付费章"); return }
        preview(info, free)
    } else preview(info, next)
}
/**
 * 阅读设置。
 *
 * 一屏装得下：一行实时预览 + 三行「标签 + 三段选择」。
 * 旧版是三组上下堆叠的大按钮加一个固定高度预览块，总高超过对话框可见区，
 * 「行距」整行被裁掉半截，只能滚动；选中态还靠「● 」前缀表示。
 * 改值只重画预览与按钮配色，不重建视图树。
 */
private fun MainActivity.showReaderSettings(info: BookInfo, index: Int, text: String, src: BookSource?) {
    val sizes = listOf("小" to Md3.FS_MD, "中" to Md3.FS_READ, "大" to Md3.FS_XL)
    val spacings = listOf("紧" to 1.35f, "标准" to 1.75f, "松" to 1.9f)
    val bgs = listOf(
        "深" to Color.rgb(20, 18, 24),
        "浅" to Color.WHITE,
        "暖" to Color.rgb(255, 248, 230),
    )

    var curSize = readerSize
    var curSpacing = readerSpacing
    var curBg = readerBackground
    var refresh: () -> Unit = {}

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Md3.dp(this@showReaderSettings, 12), Md3.dp(this@showReaderSettings, 2),
                   Md3.dp(this@showReaderSettings, 12), 0)
    }

    val preview = Md3.text(this, "示例正文，看看效果。", Md3.FS_READ, Md3.onSurface).apply {
        setPadding(Md3.dp(this@showReaderSettings, 8), Md3.dp(this@showReaderSettings, 6),
                   Md3.dp(this@showReaderSettings, 8), Md3.dp(this@showReaderSettings, 6))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    box.addView(preview)

    fun paintPreview() {
        preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, curSize)
        preview.setLineSpacing(Md3.dp(this, 1).toFloat(), curSpacing)
        preview.setTextColor(if (Color.red(curBg) > 100) Color.rgb(28, 27, 31) else Md3.onSurface)
        preview.background = Md3.bg(curBg, Md3.dp(this, 8).toFloat())
    }

    fun persist() {
        saveReaderPrefs(curSize, curSpacing, curBg)
        refresh()
    }

    /** 一行「标签 + 三段」。返回刷新函数；改值后由 refresh 统一调用。 */
    fun segmentRow(
        label: String,
        options: List<Pair<String, Any>>,
        get: () -> Any,
        set: (Any) -> Unit,
    ): () -> Unit {
        val row = Md3.row(this).apply { setPadding(0, Md3.dp(this@showReaderSettings, 3), 0, 0) }
        row.addView(Md3.text(this, label, Md3.FS_SM, Md3.onSurfaceVariant),
            LinearLayout.LayoutParams(Md3.dp(this, 26), ViewGroup.LayoutParams.WRAP_CONTENT))
        val chips = mutableListOf<Pair<TextView, Any>>()
        options.forEachIndexed { i, opt ->
            val chip = Md3.text(this, opt.first, Md3.FS_SM, Md3.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { set(opt.second); persist() }
            }
            chips.add(chip to opt.second)
            row.addView(chip, LinearLayout.LayoutParams(0, Md3.dp(this, 26), 1f).apply {
                leftMargin = if (i == 0) 0 else Md3.dp(this@showReaderSettings, 4)
            })
        }
        box.addView(row)
        return {
            val cur = get()
            chips.forEach { pair ->
                val sel = pair.second == cur
                pair.first.setTypeface(null,
                    if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                // 选中态与全局一致：浅紫填充 + 深字（发现页、按钮都用这套）。
                // 原来这里用 secondaryContainer 深紫，同一应用两套选中语义。
                pair.first.setTextColor(if (sel) Md3.onPrimary else Md3.onSurfaceVariant)
                pair.first.background = Md3.ripple(this,
                    if (sel) Md3.primary else Color.TRANSPARENT,
                    Md3.dp(this, 13).toFloat(),
                    if (sel) null else Md3.outlineVariant)
            }
        }
    }

    val rowSize = segmentRow("字号", sizes.map { it.first to (it.second as Any) },
        { curSize as Any }) { curSize = it as Float }
    val rowSpacing = segmentRow("行距", spacings.map { it.first to (it.second as Any) },
        { curSpacing as Any }) { curSpacing = it as Float }
    val rowBg = segmentRow("背景", bgs.map { it.first to (it.second as Any) },
        { curBg as Any }) { curBg = it as Int }

    refresh = {
        rowSize(); rowSpacing(); rowBg(); paintPreview()
    }
    refresh()

    // 书签开关放在这里，因为阅读页顶栏只剩一个 ⚙ 的位置。
    // 按完不关窗：关窗会重建阅读页，滚动位置会跳，连续处理书签很不方便。
    val markBtn = Widgets.button(this, "", "tonal") {}
    fun paintMark() {
        markBtn.text = if (BookmarkStore.has(info.bookId, index)) "移除本章书签" else "加书签"
        markBtn.contentDescription = markBtn.text
    }
    markBtn.setOnClickListener { addMark(info, index, text); paintMark() }
    paintMark()
    box.addView(markBtn, Md3.lpDp(this, null, Md3.BTN).apply {
        topMargin = Md3.dp(this@showReaderSettings, Md3.GAP / 2)
    })

    val dialog = Widgets.styleDialog(android.app.AlertDialog.Builder(this)
        .setTitle("阅读设置")
        .setView(box)
        .setNegativeButton("完成", null)
        .create())
    dialog.setOnDismissListener { showReader(info, index, text, src) }
    dialog.show()
}

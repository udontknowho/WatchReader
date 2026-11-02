package com.watchdl.ui.search

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.book.showDetail
import com.watchdl.ui.shelf.showShelf
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 搜索：关键词输入、多源聚合结果、换源翻页（从 MainActivity 原样搬出，行为未改）。
 *
 * 窗口状态（HITS_WINDOW / hitsWinStart / seenHits）留在 Activity：
 * 扩展属性不能带初始化器。
 */

/** ② 搜索结果。 */
/**
 * 下一批要搜的起点。返回 null 表示源已经轮过一圈了。
 *
 * 只有 pool（含被 MAX_SOURCES 剔除的源）大于本轮用量时，换一批才能真正换出新源；
 * 否则转一圈回到原处，翻页就是白翻。
 */
private fun MainActivity.nextSourceBatch(h: Screen.Hits): Int? =
    SourceRotation.next(h.offset, h.searchedSources, multi.searchable)
private fun MainActivity.hitKey(h: BookHit) = "${h.name.trim()}\u0001${h.author.trim()}"

/** 搜索结果页的「换一批源」。 */
private fun MainActivity.rotateSources(h: Screen.Hits) {
    val next = nextSourceBatch(h)
    if (next == null) {
        toast("源已经轮过一圈了")
    } else {
        seenHits.clear()
        doSearch(h.keyword, next)
    }
}

internal fun MainActivity.showHits(keyword: String, allHits: List<BookHit>, stat: MultiSource.Result? = null, offset: Int = 0,
                    batch: Int = 0, replay: Boolean = false) {
    // 排序（P2）：各源拼接顺序完全随机，实测 1700+ 条里目标书可能落在
    // 第 900 位。手表小屏只看得到几条，不排等于没有。
    //
    // 新的一次搜索要把窗口拍回开头：不重置就会停在上次翻到的位置，
    // 用户看到的是「新搜索结果从第 61 条开始」。
    if (!replay) hitsWinStart = 0
    //
    // 两层排序：先按匹配度分档，档内**同名书必须相邻** —— 列表里同一本书
    // 现在按源各占一行，分散在各处就失去了「挑个能用的源」的意义。
    // 重放是「回到已经看过的那一屏」，条目必然都在 seenHits 里；
    // 再过滤一次会把整屏清空，用户看到的是「没有找到」。
    val all = allHits.asSequence()
        .filter { it.name.isNotBlank() }
        .filter { replay || hitKey(it) !in seenHits }
        .sortedWith(compareBy<BookHit> { MatchScore.of(it.name, keyword) }.thenBy { it.name.trim() })
        .toList()
    // 记下来，换一批时不再重复显示。
    if (!replay) all.forEach { seenHits += hitKey(it) }
    // 同一本书多个源只出一张卡：屏上只看得见 3.5 行，四张重复卡会把别的结果挤掉。
    // 点卡片仍是按书名重新搜索，所以合并不会丢掉「换个源」的能力。
    val hits = all.distinctBy { "${it.name.trim()}\u0001${it.author.trim()}" }
    // 同名书共几源：必须在合并前统计，合并后每个名字只剩一条。
    val sourceCount = all.groupingBy { "${it.name.trim()}\u0001${it.author.trim()}" }.eachCount()
    setScreen(Screen.Hits(keyword, hits, offset, stat?.total ?: 0, all))
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "搜索结果", onBack = { goBackToPrevious() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    // 多源统计卡：告诉用户跑了几条源、几条没响应。
    // 不加这行的话，用户不知道「搜索结果少」是源少还是源挂了。
    if (stat != null) {
        // 文案长短会决定它是 1 行还是 2 行，下面列表就上下跳 24px（P1-3）。
        // 手表屏 378px 宽，失败源那段很容易溢出—— 限定单行 + 末尾省略，
        // 完整数字本来在下方书源页里看得到。
        val line = Md3.ellipsize(Md3.text(this,
            "找到 ${hits.size} 本",
            Md3.FS_SM, Md3.onSurfaceVariant))
        col.addView(line)
    }

    // 换一批源：**固定在滚动区外**，不跟着列表一起滑走。
    // 放列表顶部的话，一往下滑就看不见了 —— 而那正是想换一批的时候。
    // 重进这一屏时 stat 为 null，改用屏上记的批次大小。
    val batch = stat?.total ?: batch.takeIf { it > 0 }
        ?: (curScreen as? Screen.Hits)?.searchedSources ?: 0
    val hitsScreen = Screen.Hits(keyword, hits, offset, batch, all)
    val sourceTotal = multi.searchable
    val canRotate = batch > 0 && offset + batch < sourceTotal
    val remaining = sourceTotal - offset - batch
    // 当前屏的批次状态，换一批后 [showHits] 靠它算下一批。
    setScreen(hitsScreen)

    if (hits.isEmpty()) {
        // N6：15 个源全挂（断网、DNS 挂了）时，说「换个关键词」是错的建议 ——
        // 用户会去改关键词，而问题在网络。responded 为 0 说明一条源都没回话。
        val msg = when {
            stat != null && stat.responded == 0 && stat.total > 0 ->
                "所有源都没有响应\n检查网络后再试"
            stat != null && stat.responded < stat.total ->
                "没有找到「$keyword」\n${stat.total - stat.responded} 个源没响应，可换一批"
            else -> "没有找到「$keyword」\n换个关键词试试"
        }
        col.addView(Widgets.emptyState(this, "∅", msg), Md3.lpWeight(1f))
    } else {
        // 510 条结果一次性建 510 个 View，手表上量到 616ms 掉帧（2026-10-27 日志）。
        // 跟目录那一屏用同一个办法：只建一窗，翻页换窗。
        val winStart = hitsWinStart.coerceIn(0, maxOf(0, hits.size - HITS_WINDOW))
        hitsWinStart = winStart
        val winEnd = (winStart + HITS_WINDOW).coerceAtMost(hits.size)
        for (h in hits.subList(winStart, winEnd)) {
            // 第二行显示作者 + 来源站名。源名放最后，因为它是用户挑源要看的。
            val cleanSource = h.sourceName.takeIf { it.isNotBlank() && !it.contains("{{") && !it.contains("@{") }
            val sub = listOf(h.author.takeIf { it.isNotBlank() }, cleanSource)
                .filterNotNull().joinToString(" · ")
            // 同一本书有多个源时标出来，否则用户以为是两本不同的书。
            val n = sourceCount[hitKey(h)] ?: 1
            val title = if (n > 1) "${h.name}（$n 源）" else h.name
            // legado 搜索结果带封面（72 dp）。189 dp 屏上取 40 × 56 dp 占位块，
            // 颜色按书名哈希，同一本书与书架、详情页同色。
            val first = h.name.trim().let { if (it.isEmpty()) "书" else it.substring(0, 1) }
            val cover = Md3.text(this, first, Md3.FS_MD, Md3.onPrimaryContainer, bold = true).apply {
                gravity = android.view.Gravity.CENTER
                background = Md3.bg(Md3.nameColor(h.name), Md3.R_GRID.toFloat())
                minWidth = Md3.dp(this@showHits, 40)
                minimumHeight = Md3.dp(this@showHits, 56)
            }
            col.addView(Widgets.listItem(this, title, sub, twoLine = true, highlight = true,
                leading = cover) {
                openHit(h)
            })
        }
        // 翻页条：只在结果超过一窗时才出，否则会让小屏多占两行。
        if (hits.size > HITS_WINDOW) {
            val nav = Widgets.button(this, "${winStart + 1}–$winEnd / ${hits.size} 条", "tonal", {
                hitsWinStart = if (winEnd >= hits.size) 0 else winEnd
                showHits(keyword, allHits, stat, offset, batch, replay = true)
            })
            nav.contentDescription = "翻到下一百条"
            col.addView(nav)
        }
    }
    root.addView(body)

    // 换一批源：放在**固定底栏**，列表怎么滑都在。
    if (canRotate) {
        val next = Widgets.button(this, "换一批（还剩 $remaining 条）", "tonal", {
            rotateSources(hitsScreen)
        })
        next.contentDescription = "换一批源，还剩 $remaining 条"
        next.layoutParams = Md3.lpDp(this, null, Md3.BTN).apply {
            topMargin = Md3.dp(this@showHits, Md3.GAP / 2)
            bottomMargin = Md3.dp(this@showHits, Md3.GAP / 2)
        }
        root.addView(next)
    }
}
/** 用手表上的输入对话框要关键词。 */
internal fun MainActivity.askKeyword() {
    askText("搜索小说", "书名或作者", "请先输入书名") { doSearch(it) }
}

/** 目录过滤：按章名找。空关键词等于清空过滤。 */
internal fun MainActivity.askTocQuery(info: BookInfo, src: BookSource?) {
    askText("找章节", "章名关键词", null) { q ->
        tocQuery = q
        showToc(info, src)
    }
}

/**
 * 要一段文字的输入对话框。
 *
 * [emptyHint] 非空时空输入会提示这句并留在窗里；为 null 时空输入也算确认
 * （目录过滤靠空值清空）。
 */
internal fun MainActivity.askText(
    title: String,
    hintText: String,
    emptyHint: String?,
    onOk: (String) -> Unit,
) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Md3.dp(this@askText, 12), Md3.dp(this@askText, 4),
                   Md3.dp(this@askText, 12), Md3.dp(this@askText, 2))
    }
    // 平台默认的输入框是绿色下划线、绿色「取消」、20sp 标题，与 MD3 紫系冲突。
    // 这里全部换成自绘：圆角底输入框 + 两个自绘按钮，对话框不再挂系统按钮。
    val dialog = Widgets.styleDialog(android.app.AlertDialog.Builder(this)
        .setTitle(title)
        .setView(box)
        .create())
    input = EditText(this).apply {
        hint = hintText
        setSingleLine()
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Md3.FS_MD)
        setHintTextColor(Md3.onSurfaceVariant)
        setTextColor(Md3.onSurface)
        background = Md3.bg(Md3.surface4, Md3.dp(this@askText, 8).toFloat())
        setPadding(Md3.dp(this@askText, 10), 0, Md3.dp(this@askText, 10), 0)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    }
    box.addView(input, Md3.lpDp(this, null, Md3.BTN + 4))

    fun submit() {
        val kw = input.text.toString().trim()
        // 空关键词不关窗（P1-1）：以前直接 dismiss，用户以为自己按错了。
        if (kw.isEmpty() && emptyHint != null) toast(emptyHint)
        else { dialog.dismiss(); onOk(kw) }
    }
    input.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
    }

    val row = Md3.row(this)
    row.setPadding(0, Md3.dp(this@askText, 8), 0, 0)
    row.addView(Widgets.button(this, "取消", "outline") { dialog.dismiss() },
        LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f))
    row.addView(Widgets.button(this, "确定", "filled") { submit() },
        LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f).apply {
            leftMargin = Md3.dp(this@askText, Md3.GAP)
        })
    box.addView(row)

    dialog.setCanceledOnTouchOutside(true)
    dialog.show()
    // 手机上自动弹键盘，省一次点击。非主屏（手表/虚拟屏）不弹：
    // 那里没有键盘，弹出来只会盖住界面 —— 实测在虚拟屏上会把整帧渲染卡住。
    if ((display?.displayId ?: android.view.Display.DEFAULT_DISPLAY) ==
        android.view.Display.DEFAULT_DISPLAY) {
        input.requestFocus()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
}
internal fun MainActivity.doSearch(keyword: String, offset: Int = 0) {
    // screen 必须同步跟上：进度回调靠它认屏，不换的话还停在上一屏，
    // 回调全被屏守卫丢掉 —— 进度条不动、结果也回不来。
    // 新的一轮搜索要清掉上一轮的「已见过」，否则同名书会被自己的旧记录过滤掉。
    if (offset == 0) seenHits.clear()
    setScreen(Screen.Hits(keyword, emptyList(), offset, 0))
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "搜索中…", onBack = { showShelf() }))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    // 进度文本每多一个源就改一次（P1-2）。以前 15~20 秒里只有一个静态的
    // 「正在搜索」，用户分不清是在跑还是卡死了。
    // 不能用 Widgets.emptyState —— 它内部自建 TextView，外面拿不到引用改字。
    val box = Md3.column(this).apply {
        gravity = android.view.Gravity.CENTER
        setPadding(Md3.dp(this@doSearch, 12), Md3.dp(this@doSearch, 24),
                   Md3.dp(this@doSearch, 12), Md3.dp(this@doSearch, 24))
    }
    box.addView(Icons.view(this, "refresh", Md3.onSurfaceVariant, 32).apply { alpha = 0.3f },
        Md3.lpDp(this, null, 32))
    val progress = Md3.text(this, "正在搜索「$keyword」\n已扫 0 个源", Md3.FS_MD, Md3.onSurfaceVariant)
        .apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, Md3.dp(this@doSearch, 10), 0, 0)
        }
    box.addView(progress)
    // 进程条 + 出口提示：原来只有一个静态数字，卡住时用户不知道能不能退。
    val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this@doSearch, 3),
        ).apply { topMargin = Md3.dp(this@doSearch, 8) }
    }
    box.addView(bar)
    box.addView(Md3.text(this, "超过 15 秒可以返回重试", Md3.FS_SM, Md3.onSurfaceVariant).apply {
        gravity = android.view.Gravity.CENTER
        setPadding(0, Md3.dp(this@doSearch, 8), 0, 0)
    })
    col.addView(box)
    root.addView(body)

    val owner: Screen = Screen.Hits(keyword, emptyList(), offset, 0)
    // 边跑边上屏：第一个带回命中的源到达就先显示，不等最慢那个源（实测 15 秒）。
    // 累加器在 UI 线程外被写，同步表包一层。
    val shown = java.util.Collections.synchronizedList(mutableListOf<BookHit>())
    bg(owner, {
        // 多源聚合：并发 5、总超时 15 秒，单源失败静默跳过。
        // offset 让「换一批源」从下一批开始，而不是重跑同一批。
        multi.searchDetailed(keyword, onProgress = { done, total ->
            postAlive(owner) {
                progress.text = "正在搜索「$keyword」\n已扫 $done / $total 个源"
                bar.isIndeterminate = false
                bar.max = total.coerceAtLeast(1)
                bar.progress = done
            }
        }, offset = offset, onPartial = { fresh, done, total ->
            shown.addAll(fresh)
            postAlive(owner) {
                // 只在「还停在同一关键词的结果屏」且「列表没被滚动」时重绘。
                // 用户已经滚下去看别的书时刷屏，比慢更不能忍。
                val cur = curScreen as? Screen.Hits
                if (cur != null && cur.keyword == keyword && hitsWinStart == 0) {
                    // replay = true：不重置窗口、不写 seenHits。
                    // 写了的话下面的最终结果会被自己过滤成空表。
                    showHits(keyword, shown.toList(),
                        MultiSource.Result(shown.toList(), total, done, emptyList()),
                        offset = offset, replay = true)
                }
            }
        })
    }) { res ->
        res.onSuccess { r ->
            DevLog.log("WatchDL",
                "搜索「$keyword」：${r.hits.size} 条 / 响应 ${r.responded}/${r.total} 源" +
                if (r.failed.isEmpty()) "" else "，失败 ${r.failed.size}: ${r.failed.take(5)}")
            showHits(keyword, r.hits, r, offset)
        }.onFailure { e ->
            toast("搜索失败：${e.message}")
            showShelf()
        }
    }
}
/**
 * 按命中项选定该用哪个源。
 *
 * 多源聚合后，一本书可能来自任意一条 legado 书源，也可能来自番茄。
 * [BookHit.sourceName] 带上了源名，用它在 [multi] 里找回具体那条源。
 * 找不到（旧数据/空名）就退回番茄 —— 那是以前的行为，不会更差。
 */
internal fun MainActivity.sourceFor(hit: BookHit): BookSource = multi.pickFor(hit) ?: source

/** 打开一条命中项（搜索结果的点击入口）：先进书详情，再往目录去。 */
internal fun MainActivity.openHit(hit: BookHit) {
    val src = sourceFor(hit)
    // 记下搜索结果这一屏，详情页返回时原样退回去。
    rememberReturn()
    val job = Downloader.restore(jobsStore(), hit.bookId)
    if (job != null) {
        // 已经下到本地的书，用户点它就是要接着读，不必再绕一层详情。
        showToc(BookInfo(hit.bookId, job.name.ifBlank { hit.name }, job.author.ifBlank { hit.author }, job.chapters), src)
        return
    }
    // 详情页的「查看目录」还要走逐源重试，候选源得跟着带过去。
    detailCandidates = siblingsOf(hit)
    showDetail(BookInfo(hit.bookId, hit.name, hit.author, emptyList(),
        intro = hit.abstractText), src)
}
/**
 * 取同一本书的其它源：优先当前搜索结果里同名的，其次就是它自己。
 *
 * 按当前顺序依次试（列表已按源名排过，同名的挨着），
 * 让被点的那一条排第一。
 */
internal fun MainActivity.siblingsOf(hit: BookHit): List<BookHit> {
    val all = (curScreen as? Screen.Hits)?.all.orEmpty()
    val same = all.filter {
        it.name.trim() == hit.name.trim() && it.author.trim() == hit.author.trim()
    }
    return (listOf(hit) + same).distinctBy { it.sourceName }
}

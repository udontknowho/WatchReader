package com.watchdl.ui.search

import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.my.showMy
import com.watchdl.ui.reader.preview
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 全文搜索（#23）：在已下载正文里找关键词。
 *
 * 与「搜索」页的分工：那边联网找书，这边离线找字。书全下到本地之后，
 * 「这句在哪一章」是手表上最常见的需求，而阅读页的查找只能搜当前章。
 *
 * 照本地参考仓 `~/ref/legado-novel`（Compose／MD3）的
 * `ui/book/searchContent/SearchContentScreen.kt` 抄的是位置与语义：
 *
 * | legado | 这里（189 × 248 dp 表盘） |
 * |---|---|
 * | 有结果时标题「共 N 条结果」，否则「搜索内容」 | 同样，另有副标题「『词』· 已扫 N 本」 |
 * | 顶栏「替换」「正则」两枚开关 | 只留正则，放进 ⋮（替换本页不改正文，不做） |
 * | 搜索中右上停止按钮（`Icons.Default.Stop`） | ⋮ 里的「停止扫描」，点了立刻铺已扫结果 |
 * | 结果卡：章名 + 分隔线 + 高亮摘录 + 右上角牌 | 同样，内边距 16→8、摘录 2 行、卡里多一行书名 |
 * | 内联搜索栏带清除键 | 顶栏 search 图标重开输入框（表盘上打字成本高，不留常驻输入框） |
 * | 搜索历史、定位当前章 | 不做，理由见 [showFindMenu] |
 *
 * 三阶段（等输入、扫、出结果）共用屏身份 `find:关键词`：
 * 切屏就中断扫描，[FindText.scan] 的 cancelled 拿得到。
 */

/**
 * 屏内状态。放文件级 object 而不是往 MainActivity 加字段：
 * 正则开关与部分结果只属于这一屏，离开就重来。
 *
 * 三个可变字段要在后台线程读（扫描线程查 [stop]、[round]），所以标 `@Volatile`。
 */
internal object FindUi {

    /** 正则开关。默认关：多数人搜的是一个词，不是一条正则。 */
    @Volatile var useRegex = false

    /** 正在扫。⋮ 里的「停止扫描」只在这时为真。 */
    @Volatile var scanning = false

    /** 用户按了停止。扫描线程在下一本书的边界看到它就收尾。 */
    @Volatile var stop = false

    /** 扫到第几本，停止后照样要写进副标题。 */
    @Volatile var scanned = 0

    /** 已扫到的部分结果。停止时不等后台回包，直接铺这一份。 */
    @Volatile var partial: List<FindText.Hit> = emptyList()

    /**
     * 轮次。换词或换匹配方式就 +1，在飞的那一轮回调看到号不一样就自己作废，
     * 否则旧结果会在新结果之后回来，把屏刷回上一个关键词。
     */
    @Volatile var round = 0
}

internal fun MainActivity.showFind(
    query: String = "",
    hits: List<FindText.Hit> = emptyList(),
    scanned: Int = 0,
    done: Boolean = false,
    useRegex: Boolean = false,
) {
    if (query.isBlank()) {
        setScreen(Screen.Find())
        FindUi.useRegex = false
        askText("全文搜索", "搜已下载正文", "请先输入关键词") { q -> showFind(q) }
        return
    }
    // 还没扫过：起后台任务，进度上屏。replay 时 done 为 true，直接重画结果。
    if (!done && hits.isEmpty() && scanned == 0) {
        startFind(query, useRegex)
        return
    }
    FindUi.useRegex = useRegex
    FindUi.scanning = false
    setScreen(Screen.Find(query, hits, scanned, done, useRegex))

    root.removeAllViews()
    // legado 有结果时标题写条数，让人一眼知道这轮搜到了多少；条数原来在分组标签里，
    // 挪到副标题后与书源、替换、记录三页的顶栏口径一致。
    root.addView(Widgets.topBar(
        this,
        if (hits.isNotEmpty()) "共 ${hits.size} 条结果" else "全文搜索",
        onBack = { showMy() },
        subtitle = "「$query」· 已扫 $scanned 本",
        trailing = listOf(
            "search" to { askFind() },
            "more" to { showFindMenu(query) },
        ),
    ))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    if (hits.isEmpty()) {
        // 照 legado `EmptyResult`：一句「没找到」，再交代搜了多少本。
        col.addView(Widgets.emptyState(this, "search",
            "没有找到「$query」\n已扫 $scanned 本已下载的书"))
    } else {
        val match = findMatcher(query, useRegex)
        for (h in hits) col.addView(findCard(h, match))
    }
    root.addView(body, Md3.lpWeight(1f))
}

/** 起一轮整库扫描。进度按书更新，切屏后 [FindText.scan] 会自然中断。 */
private fun MainActivity.startFind(query: String, useRegex: Boolean) {
    val owner = Screen.Find(query, useRegex = useRegex)
    FindUi.useRegex = useRegex
    FindUi.stop = false
    FindUi.scanning = true
    FindUi.scanned = 0
    FindUi.partial = emptyList()
    // 换词重扫时号一变，上一轮的回调就作废（切屏那条路还会再中断线程）。
    val round = ++FindUi.round

    setScreen(owner)
    root.removeAllViews()
    // 与结果页同一套顶栏：返回、⋮ 都在原位，扫描中才不会「这屏没出口」。
    root.addView(Widgets.topBar(
        this, "全文搜索", onBack = { showMy() },
        subtitle = "「$query」· 扫描中",
        trailing = listOf("more" to { showFindMenu(query) }),
    ))
    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    val progress = Md3.text(this, "搜「$query」\n已扫 0 本", Md3.FS_MD, Md3.onSurfaceVariant).apply {
        gravity = Gravity.CENTER
        setPadding(Md3.dp(this@startFind, 12), Md3.dp(this@startFind, 24),
                   Md3.dp(this@startFind, 12), Md3.dp(this@startFind, 24))
    }
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this@startFind, 3),
        )
    }
    col.addView(progress)
    col.addView(bar)
    root.addView(body, Md3.lpWeight(1f))

    bg(owner, {
        FindText.scan(
            store = jobsStore(),
            query = query,
            useRegex = useRegex,
            onBook = { n, name, partial ->
                FindUi.scanned = n
                FindUi.partial = partial
                postAlive(owner) {
                    if (round != FindUi.round) return@postAlive
                    progress.text = "搜「$query」\n已扫 $n 本 · $name"
                }
            },
            cancelled = { FindUi.stop || Thread.currentThread().isInterrupted },
        )
    }) { res ->
        if (round != FindUi.round) return@bg
        FindUi.scanning = false
        res.onSuccess { hits ->
            FindUi.partial = hits
            DevLog.log("WatchDL", "全文搜索「$query」：${hits.size} 条")
            showFind(query, hits, scanned = FindUi.scanned, done = true, useRegex = useRegex)
        }.onFailure { e ->
            toast("全文搜索失败：${e.message}")
            showMy()
        }
    }
}

/**
 * 停止扫描：立刻把已扫到的结果铺上去，不等后台线程走到下一本书的边界。
 *
 * 号 +1 让在飞的那轮结果作废 —— 否则它几秒后回来，会把用户刚看到的结果再刷一次。
 * 线程自己会在下一个边界看到 [FindUi.stop] 退出，守护线程不必回收。
 */
private fun MainActivity.stopFind(query: String) {
    FindUi.stop = true
    FindUi.round++
    FindUi.scanning = false
    showFind(query, FindUi.partial, FindUi.scanned, done = true, useRegex = FindUi.useRegex)
}

/**
 * 顶栏 ⋮ 菜单。结果页与扫描页共用。
 *
 * 不做三样：搜索历史（没有历史存储，表盘上打字成本高，每次弹输入框就够）、
 * 替换（本页只搜不改正文）、定位当前章（没有阅读位置滚动条，
 * 命中卡上的「当前」牌已经说明哪条是读到的位置）。
 */
private fun MainActivity.showFindMenu(query: String) {
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>()
    if (FindUi.scanning) items += "停止扫描" to { stopFind(query) }
    items += "正则搜索：${if (FindUi.useRegex) "开 ✓" else "关"}" to {
        FindUi.useRegex = !FindUi.useRegex
        if (FindUi.useRegex && !findRegexUsable(query)) toast("正则写法不成立，按字面匹配")
        // 换匹配方式等于换一轮搜索，直接重扫：留着旧结果会让人以为开关没生效。
        showFind(query, useRegex = FindUi.useRegex)
    }
    items += "换个关键词" to { askFind() }
    Widgets.longMenuDialog(this, "全文搜索", items).show()
}

/**
 * 一张结果卡。照 legado `SearchResultItem`：章名、分隔线、摘录三段竖排。
 *
 * 三处压缩与一处偏离：卡片内边距 16→8 dp、摘录限两行、角牌从绝对定位的
 * 右上角改成标题行右侧（189 dp 上绝对定位会压住章名）；
 * 多一行《书名》—— legado 是单书搜索，我们是整库搜索，不写书名分不清归属。
 */
private fun MainActivity.findCard(h: FindText.Hit, match: (String) -> List<IntRange>): LinearLayout {
    val card = Md3.column(this)
    card.background = Md3.ripple(this, Md3.surface3, Md3.dp(this, 12))
    card.setPadding(Md3.dp(this, 8), Md3.dp(this, 8), Md3.dp(this, 8), Md3.dp(this, 8))
    card.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = Md3.dp(this@findCard, Md3.GAP) }
    card.isClickable = true
    card.contentDescription = "${h.title}，${h.bookName}，点按翻到这一章"
    card.setOnClickListener { openFindHit(h) }

    val head = Md3.row(this)
    head.gravity = Gravity.CENTER_VERTICAL
    val title = Md3.text(this, findMarked(h.title.ifBlank { "第${h.index + 1}章" }, match, false),
        Md3.FS_MD, Md3.onSurface)
    title.maxLines = 2
    title.ellipsize = TextUtils.TruncateAt.END
    head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    // 「当前章节」照 legado；表盘上省成「当前」两个字，否则章名只剩五六个字宽。
    if (readProgress(h.bookId) == h.index) head.addView(findTag("当前"))
    val percent = if (h.total > 0) h.index * 100 / h.total else 0
    if (percent > 0) head.addView(findTag("$percent%"))
    card.addView(head)

    val line = View(this)
    line.setBackgroundColor(Md3.surface)
    card.addView(line, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 1),
    ).apply { topMargin = Md3.dp(this@findCard, 4); bottomMargin = Md3.dp(this@findCard, 4) })

    val excerpt = Md3.text(this, findMarked(h.excerpt, match, true), Md3.FS_SM, Md3.onSurface)
    excerpt.maxLines = 2
    excerpt.ellipsize = TextUtils.TruncateAt.END
    card.addView(excerpt)

    val book = Md3.text(this, "《${h.bookName}》", Md3.FS_SM, Md3.onSurfaceVariant)
    book.alpha = 0.85f
    card.addView(book)
    return card
}

/** 右上角小牌。legado 是 TextCard：8 dp 圆角、次级容器色。 */
private fun MainActivity.findTag(text: String): TextView =
    Md3.text(this, text, Md3.FS_SM, Md3.onSurfaceVariant).apply {
        background = Md3.bg(Md3.surface4, Md3.dp(this@findTag, 6))
        setPadding(Md3.dp(this@findTag, 4), Md3.dp(this@findTag, 1),
                   Md3.dp(this@findTag, 4), Md3.dp(this@findTag, 1))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = Md3.dp(this@findTag, 4)
        layoutParams = lp
    }

/**
 * 命中词上色。章名只换字色，摘录再加一层主色底 —— 照 legado 的
 * `getTitleAnnotatedString` 与 `getContentAnnotatedString`（`primaryContainer` 底）。
 *
 * 分段交给 core 的 [findHighlight]，这里只负责把段拼成 `Spannable`。
 */
private fun MainActivity.findMarked(
    text: String,
    match: (String) -> List<IntRange>,
    withBg: Boolean,
): CharSequence {
    val segs = findHighlight(text, match(text))
    if (segs.none { it.hit }) return text
    val out = SpannableString(text)
    var at = 0
    for (s in segs) {
        val end = at + s.text.length
        if (s.hit) {
            out.setSpan(ForegroundColorSpan(Md3.primary), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (withBg) {
                out.setSpan(BackgroundColorSpan(Md3.primaryContainer), at, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        at = end
    }
    return out
}

/** 点一条命中：把这本书的目录读回来，直接翻到那一章。 */
private fun MainActivity.openFindHit(h: FindText.Hit) {
    val job = Downloader.restore(jobsStore(), h.bookId)
    if (job == null || h.index !in job.chapters.indices) {
        toast("这一章已经不在本地了")
        return
    }
    val info = BookInfo(h.bookId, job.name, job.author, job.chapters)
    // 记下这一屏，从目录退回来还能回到结果列表。
    rememberReturn()
    preview(info, h.index)
}

/** 从「我的」页进来的入口。正则开关跟着上一轮走。 */
internal fun MainActivity.askFind() {
    askText("全文搜索", "搜已下载正文", "请先输入关键词") { q ->
        showFind(q, useRegex = FindUi.useRegex)
    }
}

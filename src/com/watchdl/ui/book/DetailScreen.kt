package com.watchdl.ui.book

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.reader.preview
import com.watchdl.ui.source.showSources
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets

/**
 * 书详情，照 legado `ui/book/info/BookInfoScreen`（本地参考仓 `~/ref/legado-novel` 3.26.21）。
 *
 * 段落一一对应：`BookInfoHeader` 1111-1335、`BookInfoActions` 1336-1479、
 * `BookInfoSummary` 1479-1580、`BookInfoIntro` 1580-1696、
 * `BookInfoTopBarActions` / `BookInfoOverflowMenu` 851-1007。
 *
 * 裁掉的是背景大图与相关书籍横排：后者是横向列表，189 dp 一排放不下；
 * 前者是材质装饰，要 Compose 的模糊。其余字段一个不留地纵向排下来，靠滚动容纳。
 *
 * 与 legado 的已知差异（不打算追）：
 * - `relatedBooks`（同类推荐横排）：我们没有推荐源，也没有横滑空间。
 * - `remark`（备注卡）：legado 有 `Book.remark` 可编辑，我们没有备注存储。
 * - `kindLabels` / `groupNames`（分类与分组标签行）：我们没有类型/分组系统，
 *   同一位置换成下载进度小牌。
 * - 顶栏的「编辑 / 分享」：书籍信息就是书源返回的那份，改动无处落盘；也没有分享通道。
 * - 简介的展开收起：legado 藏在书名长按菜单里，手表上没有长按提示，另给一枚文字按钮。
 * - 下拉刷新目录：手表没有下拉手势，只留 ⋮ 里的「刷新目录」。
 */

/**
 * 这一屏的瞬时状态。文件级 object，不进 MainActivity —— 它只是「简介展开了没有」，
 * 进程重建后本来就该回到折叠态。
 *
 * legado 用 `rememberSaveable` 存这一位；我们用 bookId 判断要不要复位：
 * 换一本书进来，上一本的展开状态不该跟着走。
 */
internal object DetailUi {
    /** 简介是否展开。 */
    var introExpanded = false

    /**
     * 目录取失败过没有。照 legado `BookInfoUiState.tocLoadFailed`。
     *
     * legado 拿它在统计行尾巴上补一句 `error_load_toc`。我们没有 ViewModel，
     * 状态放在这里，`showDetail` 每轮读一次；换书时复位，同一本书刷新失败后重画仍在。
     */
    var tocFailed = false

    /** 这两位状态属于哪本书。 */
    var bookId = ""

    /** 折叠态行数。189 dp 宽、12 sp 下三行约 60 字，够判断要不要往下读。 */
    const val INTRO_LINES = 3

    fun bind(id: String) {
        if (bookId != id) {
            bookId = id
            introExpanded = false
            tocFailed = false
        }
    }
}

internal fun MainActivity.showDetail(info: BookInfo, src: BookSource? = null) {
    setScreen(Screen.Detail(info, src))
    DetailUi.bind(info.bookId)
    root.removeAllViews()
    // legado 的 `BookInfoTopBarActions` 是「编辑 / 分享 / ⋮」三枚。
    // 编辑书籍信息与分享我们没有对应能力（书籍信息就是源上那份，改动无处落盘），
    // 只留 ⋮ —— 条目见 [showDetailMenu]，接不上的如实说明，不造空按钮。
    root.addView(Widgets.topBar(this, info.name, onBack = { goBackToPrevious() },
        trailing = listOf("more" to { showDetailMenu(info, src) })))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    // 作业快照：一次读 job.json（不碰 chapters.jsonl），下面三处都用它。
    // 有作业 = 「已在书架」；已下章数也在这里。
    val job = Downloader.restoreLight(jobsStore(), info.bookId)
    val done = job?.count(ChapterStatus.DONE) ?: 0
    val totalChapters = if (info.chapters.isNotEmpty()) info.chapters.size else (job?.total ?: 0)

    // ── 头部：封面 5:7 + 书名 / 作者 / 书源 ──
    // 手机封面是 112 dp（legado `BookInfoHeader` 的 `.width(112.dp)`）。
    // 表盘内容区只有 157 dp，112 dp 封面会把书名挤进 45 dp（两行标题都放不下），
    // 所以压到 56 dp / 78 dp —— 与书架三列卡同一个宽度，两块屏看着是同一套书。
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
    meta.gravity = Gravity.CENTER_VERTICAL
    val nameView = Md3.text(this, info.name, Md3.FS_LG, Md3.onSurface, bold = true)
    nameView.maxLines = 2
    nameView.ellipsize = android.text.TextUtils.TruncateAt.END
    meta.addView(nameView)
    meta.addView(Md3.ellipsize(Md3.text(this, "作者：${info.author.ifBlank { "未知" }}",
        Md3.FS_SM, Md3.onSurfaceVariant)))
    // legado 的书源行是 `labelMedium` + primary 色，不是次级灰 —— 它可点，
    // 点了进书源编辑（`onOriginClick` → `OpenBookSourceEdit`）。
    // 我们对应的是书源管理屏；没有对应物时就不加点击，不留死链接。
    srcName(src).takeIf { it.isNotBlank() }?.let { n ->
        // 能点时用 primary，不能点时退回次级灰。primary 是「这里可以按」的信号，
        // 点不动的文字染成 primary 就是在骗人 —— 之前内置源也染了 primary 却按不动。
        val clickable = src is LegadoSource
        val origin = Md3.ellipsize(Md3.text(this, "书源：$n", Md3.FS_SM,
            if (clickable) Md3.primary else Md3.onSurfaceVariant))
        if (clickable) {
            origin.background = Md3.ripple(this, android.graphics.Color.TRANSPARENT, 0f)
            origin.isClickable = true
            origin.contentDescription = "书源：$n，点按管理书源"
            origin.setOnClickListener { rememberReturn(); showSources() }
        }
        meta.addView(origin)
    }
    head.addView(meta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    col.addView(head, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = Md3.dp(this@showDetail, Md3.GAP / 2) })

    // ── 头部小牌：下载进度 ──
    // legado 这里是 `kindLabels` 的 TextCard（类型标签）。我们没有类型系统，用下载进度顶上。
    // 只一枚：189 dp 一行放三枚会各自被截成两个字的宽度。
    col.addView(Md3.text(this, detailDownloadTag(done, totalChapters),
        Md3.FS_SM, Md3.onSurfaceVariant).apply {
        setPadding(Md3.dp(this@showDetail, Md3.GAP), Md3.dp(this@showDetail, 1),
            Md3.dp(this@showDetail, Md3.GAP), Md3.dp(this@showDetail, 1))
        background = Md3.bg(Md3.surface4, Md3.R_ITEM.toFloat())
        contentDescription = "下载进度 ${detailDownloadTag(done, totalChapters)}"
    }, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = Md3.dp(this@showDetail, Md3.GAP) })

    // ── 四张操作卡，2×2 ──
    // 手机是一行四张；189 dp 一行四张每张只剩 42 dp（图标 18 dp + 两字标题），
    // 改两行两列，每张 46 dp 高。顺序照 legado：书架 / 目录 / 换源 / 阅读记录。
    fun actionCard(glyph: String, label: String, desc: String, act: () -> Unit): LinearLayout {
        val cell = Md3.column(this)
        cell.gravity = Gravity.CENTER
        cell.background = Md3.ripple(this, Md3.surface3, Md3.R_ITEM.toFloat())
        cell.isClickable = true
        cell.contentDescription = desc
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
    // 书架卡的文案与图标随状态走（legado `BookInfoActions` 的 inBookshelf 分叉)。
    val shelf = detailShelfAction(job != null)
    val acts = listOf(
        ActCard(shelf.glyph, shelf.label, shelf.desc) {
            if (job != null) confirmRemoveShelf(info, src) else startDownload(info)
        },
        // legado 的目录卡图标是 FormatListBulleted，我们对应 `list`。
        ActCard("list", "查看目录", "查看目录") { detailOpenToc(info, src) },
        ActCard("swap", "换源", "换源") { rememberReturn(); showSwap(info, src) },
        ActCard("history", "阅读记录", "阅读记录") { showProgress(info) },
    )
    for (pair in acts.chunked(2)) {
        val row = Md3.row(this)
        for (a in pair) {
            row.addView(actionCard(a.glyph, a.label, a.desc, a.act), LinearLayout.LayoutParams(
                0, Md3.dp(this, 46), 1f).apply {
                if (row.childCount > 0) leftMargin = Md3.dp(this@showDetail, Md3.GAP)
            })
        }
        col.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 46))
            .apply { bottomMargin = Md3.dp(this@showDetail, Md3.GAP) })
    }

    // ── 在读 / 最新 / 共 N 章（legado `BookInfoSummary` 的三行）──
    // legado 的层级：第一行 titleMedium 加粗（onSurface）、第二行 bodyMedium 次级色、
    // 第三行 labelMedium 三格（primary 加粗 / secondary 竖线 / secondary 状态词）。
    // 16 sp 的 titleMedium 在 189 dp 上会把书名行吃掉，所以字号压到 FS_MD / FS_SM，
    // **层级靠字重与颜色保住，不靠字号差** —— 这是这一块唯一没照抄的地方。
    val readAt = readProgress(info.bookId)
    if (info.chapters.isNotEmpty()) {
        val box = Widgets.card(this)
        box.addView(Md3.ellipsize(Md3.text(this, detailCurrentLine(
            info.chapters.getOrNull(readAt)?.title), Md3.FS_MD, Md3.onSurface, bold = true)))
        detailLatestLine(info.chapters.lastOrNull()?.title)?.let {
            box.addView(Md3.ellipsize(Md3.text(this, it, Md3.FS_SM, Md3.onSurfaceVariant)))
        }
        val stat = detailStatParts(info.chapters.size, readAt)
        val statRow = Md3.row(this)
        // 三格三种颜色：共 N 章 primary 加粗 / 竖线与状态词用 outline（MD3 里 secondary
        // 的「弱一档」角色，本主题没单列 secondary）。
        statRow.addView(Md3.text(this, stat[0], Md3.FS_SM, Md3.primary, bold = true))
        statRow.addView(Md3.text(this, stat[1], Md3.FS_SM, Md3.outline).apply {
            setPadding(Md3.dp(this@showDetail, 6), 0, Md3.dp(this@showDetail, 6), 0)
        })
        statRow.addView(Md3.text(this, stat[2], Md3.FS_SM, Md3.outline))
        box.addView(statRow)
        // 目录取失败过：legado 在同一行尾巴补 `error_load_toc`；189 dp 一行装不下，另起一行。
        if (DetailUi.tocFailed) {
            box.addView(Md3.text(this, detailTocFailText(), Md3.FS_SM, Md3.error))
        }
        col.addView(box)
    } else {
        // legado 目录没回来时第一行写 `loading`「加载中…」；我们停在「还没取」，
        // 所以明说要按哪张卡，而不是让人等一个不会来的结果。取失败过就说是失败。
        col.addView(Md3.text(this, detailEmptyTocHint(DetailUi.tocFailed),
            Md3.FS_SM, if (DetailUi.tocFailed) Md3.error else Md3.onSurfaceVariant)
            .apply { setPadding(0, 0, 0, Md3.dp(this@showDetail, Md3.GAP)) })
    }

    // ── 简介（legado `BookInfoIntro`）──
    val intro = detailIntroText(info.intro)
    if (intro == null) {
        // 无简介时 legado 画 `intro_show_null`「简介：暂无简介」，不另起分组标题。
        // 以前整块不画，用户以为是没加载出来。
        col.addView(Md3.text(this, "简介：暂无简介", Md3.FS_SM, Md3.onSurfaceVariant).apply {
            alpha = 0.7f
            setPadding(Md3.dp(this@showDetail, Md3.PAD), 0,
                Md3.dp(this@showDetail, Md3.PAD), Md3.dp(this@showDetail, Md3.GAP))
        })
    } else {
        col.addView(Widgets.sectionLabel(this, "简介"))
        val ic = Widgets.card(this)
        val tv = Md3.text(this, intro, Md3.FS_SM, Md3.onSurfaceVariant)
        // legado 的「展开 / 折叠」在书名长按菜单里。手表上没有长按提示，等于没有入口，
        // 所以另给一枚看得见的文字按钮；正文本身仍可点（老行为，留着不吃亏）。
        var toggle: TextView? = null
        fun applyExpand() {
            tv.maxLines = if (DetailUi.introExpanded) Int.MAX_VALUE else DetailUi.INTRO_LINES
            tv.ellipsize = if (DetailUi.introExpanded) null else android.text.TextUtils.TruncateAt.END
            tv.contentDescription = if (DetailUi.introExpanded) "简介，点击收起" else "简介，点击展开"
            toggle?.text = detailIntroToggleLabel(DetailUi.introExpanded)
            toggle?.contentDescription = detailIntroToggleLabel(DetailUi.introExpanded)
        }
        tv.setOnClickListener {
            DetailUi.introExpanded = !DetailUi.introExpanded
            applyExpand()
        }
        applyExpand()
        ic.addView(tv)
        if (detailIntroToggleVisible(intro, DetailUi.INTRO_LINES)) {
            // 32 dp 高：热区比文字大一圈，又不用 TAP 的 48 dp —— 248 dp 高的屏上
            // 一个纯文字入口占 48 dp 会把简介正文挤出视野。
            val btn = Md3.text(this, detailIntroToggleLabel(DetailUi.introExpanded),
                Md3.FS_SM, Md3.primary).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                background = Md3.ripple(this@showDetail, android.graphics.Color.TRANSPARENT,
                    Md3.R_ITEM.toFloat())
                isClickable = true
                setOnClickListener {
                    DetailUi.introExpanded = !DetailUi.introExpanded
                    applyExpand()
                }
            }
            toggle = btn
            btn.contentDescription = detailIntroToggleLabel(DetailUi.introExpanded)
            ic.addView(btn, Md3.lpDp(this, null, 32))
        }
        col.addView(ic)
    }
    root.addView(body, Md3.lpWeight(1f))

    // ── 底部「阅读」：照 legado 的 FAB，点一下直接读上次那章 ──
    // 还没有目录时不写「取目录并阅读」：这条路径只到目录屏，读到第一章要等用户挑章，
    // 写「并阅读」是承诺了没做的事。
    val go = Widgets.button(this, if (info.chapters.isEmpty()) "取目录" else "阅读", "filled") {
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
 * 详情右上角 ⋮ 菜单，照 legado `BookInfoOverflowMenu`（`BookInfoScreen.kt:1012-1103`）。
 *
 * legado 那份有 17 项（编辑 / 刷新 / 阅读记录 / 源变量 / 复制链接 / 置顶 /
 * 允许更新 / 清理缓存 / 日志 / 私密标记…）。我们接得上四项：换源 / 刷新目录 /
 * 移出书架（未入架时不画）/ 阅读记录。其余都没有对应能力：书籍信息就是书源返回的那份，
 * 没有可写入的本地副本；没有 WebDav、没有源变量、没有私密标记。**不造空条目** ——
 * 点一下只弹「暂不支持」的菜单比没有更糟。
 *
 * 用整屏对话框而不是浮层：浮层在 189 dp 的屏上几乎占满，既没有留白感也没有整屏的秩序感。
 */
internal fun MainActivity.showDetailMenu(info: BookInfo, src: BookSource?) {
    val inShelf = Downloader.restoreLight(jobsStore(), info.bookId) != null
    val items = mutableListOf<Pair<CharSequence, () -> Unit>>(
        // 换源排在第一位：详情页最常见的动作是「这条源取不到正文，换一条」。
        "换源" to { rememberReturn(); showSwap(info, src) },
        "刷新目录" to { refreshToc(info, src) },
    )
    if (inShelf) items += "移出书架" to { confirmRemoveShelf(info, src) }
    items += "阅读记录" to { showProgress(info) }
    Widgets.longMenuDialog(this, info.name, items).show()
}

/** 四张动作卡里的一张。legado 是 `BookInfoActionCard`，字段一样：图标 / 文案 / 无障碍描述 / 动作。 */
private data class ActCard(
    val glyph: String,
    val label: String,
    val desc: String,
    val act: () -> Unit,
)

/**
 * 移出书架，走二次确认。
 *
 * 复用书架屏那套整本删除：[MainActivity.purgeBookCache]（取消服务 → 踢队列 →
 * 等收工 → 落墓碑），所以正在下的书也不会留下半截目录。删完重画本屏，
 * 动作卡自己回到「放入书架」。
 */
private fun MainActivity.confirmRemoveShelf(info: BookInfo, src: BookSource?) {
    Widgets.md3Dialog(this,
        title = "移出书架",
        body = detailRemoveMessage(info.name),
        confirm = "移出",
        onConfirm = {
            purgeBookCache(listOf(info.bookId)) { gone ->
                toast(if (gone > 0) "已移出书架" else "没删成：目录仍被占用")
                showDetail(info, src)
            }
        },
    ).show()
}

/**
 * 刷新目录，照 legado 的 `BookInfoMenuAction.Refresh`。
 *
 * legado 原地刷新这一屏的章数字段；我们手上的 [BookInfo] 是一份快照，所以取回目录后
 * 重画同一屏（[showDetail]）—— 停在详情页，不跳去目录页。只重取目录、不重测正文规则：
 * 换源那条路（[loadToc]）才带首章正文探测。
 */
private fun MainActivity.refreshToc(info: BookInfo, src: BookSource?) {
    if (info.chapters.isEmpty()) { detailOpenToc(info, src); return }
    val useSrc = src ?: tocSrc ?: source
    toast("正在刷新目录…")
    val owner = Screen.Detail(info, src)
    bg(owner, { useSrc.toc(info.bookId, info.name, info.author) }) { res ->
        res.onSuccess { got ->
            if (got.chapters.isEmpty()) {
                DetailUi.tocFailed = true
                toast("目录是空的，这条源可能失效了")
                showDetail(info, src)
                return@onSuccess
            }
            DetailUi.tocFailed = false
            toast("目录已更新：${got.chapters.size} 章")
            showDetail(info.copy(
                chapters = got.chapters,
                truncated = got.truncated,
                intro = got.intro.ifBlank { info.intro },
            ), useSrc)
        }.onFailure {
            DetailUi.tocFailed = true
            toast("目录失败：${it.message}")
            showDetail(info, src)
        }
    }
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

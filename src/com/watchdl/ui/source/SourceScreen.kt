package com.watchdl.ui.source

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.watchdl.MainActivity
import com.watchdl.core.*
import com.watchdl.ui.attachTabs
import com.watchdl.ui.theme.Icons
import com.watchdl.ui.theme.Md3
import com.watchdl.ui.theme.Widgets
import java.io.File

/**
 * 发现榜单、书源管理、书源市场三屏（从 MainActivity 原样搬出，行为未改）。
 *
 * 与 ui/shelf 同样写成 MainActivity 的扩展函数：这些屏要用 Activity 的
 * root / setScreen / crownStep / toast 等状态。
 * 榜单缓存三个字段（rankCache/rankRefreshed/rankRefreshing）留在 Activity，
 * 因为扩展属性不能有初始化器。
 */

/** ⑤ 书源管理。 */
internal fun MainActivity.showSources() {
    setScreen(Screen.Sources)
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "书源", onBack = { goBackToPrevious() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)

    // 副标题原本是「从 yiove 在线拉取，点选即可导入」，单行省略号把它压到卡片边缘（P1-5）。
    col.addView(Widgets.listItem(
        this, "书源市场", "yiove 在线库，点选导入",
        twoLine = true, highlight = false,
        leading = Icons.view(this, "cloud", Md3.primary, 18),
    ) { showMarket(1, emptyList()) })
    // 云端整包更新：改一个 JSON 推上去就不必重新发包。
    col.addView(Widgets.listItem(
        this, "更新书源库", "从云端拉取最新书源包",
        twoLine = true, highlight = false,
        leading = Icons.view(this, "download", Md3.primary, 18),
    ) { updateFromCloud() })
    // 并发线程数：通用书源默认串行太慢（实测 0.17 章/秒），交给用户自己定。
    col.addView(Widgets.listItem(
        this, "下载线程", "当前 ${lanes()} 线程 · 越多越易被限流",
        twoLine = true, highlight = false,
        leading = Icons.view(this, "menu", Md3.primary, 18),
    ) { pickLanes() })
    col.addView(Widgets.sectionLabel(this, "本地书源"))

    val owner: Screen = Screen.Sources
    bg(owner, {
        // 整段必须包 try：这里任何一步抛异常，线程会静默死掉，
        // 界面停在空白状态 —— 看起来就是「书源 0 条」，却没有任何提示。
        runCatching {
            extractAssets()
            buildLocalSourceRows()
        }.getOrElse {
            DevLog.log("WatchDL", "读取本地书源失败：${it.message}")
            null
        }
    }) { res ->
        val data = res.getOrNull()
        if (data == null) {
            col.addView(Widgets.emptyState(this, "warning", "读取本地书源失败\n请重装或重新导入"))
            root.addView(body)
            return@bg
        }
        val (files, rows, total, usable) = data
        run {
            val card = Widgets.card(this, outlined = true)
            card.addView(Md3.text(this, "可用 $usable / 共 $total 条", Md3.FS_MD, Md3.onSurface))
            // 路径是内部信息（P1-4），只在 debug 包显示 —— 用户拿它无用处。
            if (rows.count { it.mark == "✗" } > 0 && isDebugBuild()) {
                card.addView(Md3.text(this,
                    "目录 ${files.size} 个文件 · ${srcDirPath}",
                    Md3.FS_SM, Md3.onSurfaceVariant))
            }
            // ── 诊断区：只在真的出问题时才显示 ──
            //
            // 手表屏幕小，这三行（正则自检 / 失败明细 / 导入抛点）是排障用的，
            // 源全可用时显示只会误导："失败 6 条" 看着像故障，实际是
            // "这 6 条需修补且已自动修补"。所以全好就藏，有问题才冒出来。
            // logcat 里始终写全量，需要时仍可读到。
            val badRe = SafeRegex.probe(RegexProbe.ALL) + SafeRegex.probe(RegexProbe.FROM_SOURCES)
            val lenientBad = SafeRegex.probeLenient(
                badRe.map { it.substringBefore(" | ") to it.substringAfter("正则=") })
                .filter { it.contains("仍失败") }
            val trace = ImportTrace.last
            val broken = rows.count { it.mark == "✗" }
            DevLog.log("WatchDL",
                "正则自检：字面 ${RegexProbe.ALL.size} / 书源 ${RegexProbe.FROM_SOURCES.size} / " +
                "裸编译失败 ${badRe.size}（走后兜底仍失败 ${lenientBad.size}）/ 导入抛点 ${trace ?: "无"}")
            badRe.take(6).forEach { DevLog.log("WatchDL", "裸编译失败 $it") }
            lenientBad.take(6).forEach { DevLog.log("WatchDL", "兜底仍失败 $it") }

            // 只有「有源不可用」或「修补后仍编不过 / 导入抛过异常」才展示
            if (broken > 0 || lenientBad.isNotEmpty() || trace != null) {
                // 只给结论，不贴正则原文：189dp 宽的屏上那几行只会溢出卡片。
                if (lenientBad.isNotEmpty()) {
                    card.addView(Md3.text(this,
                        "${lenientBad.size} 条书源已自动修正",
                        Md3.FS_SM, Md3.onSurfaceVariant))
                }
                if (trace != null) {
                    card.addView(Md3.text(this, "有源在导入时出错，详情见日志",
                        Md3.FS_SM, Md3.onSurfaceVariant))
                }
            } else {
                // 全好：一句结论就够了，不再列数字与明细（那是排障才需要的）
                card.addView(Md3.text(this, "全部可导入", Md3.FS_SM, Md3.onSurfaceVariant))
            }
            // 按原因归类，比逐条列 780 行有用得多。
            // 全好时上面已经写过「全部可导入」，这里不再重复。
            val byReason = rows.filter { it.mark == "✗" }
                .groupBy { shortReason(it.note) }
                .entries.sortedByDescending { it.value.size }
            if (byReason.isNotEmpty()) {
                card.addView(Md3.text(this,
                    "${rows.count { it.mark == "✗" }} 条暂不可用",
                    Md3.FS_SM, Md3.onSurfaceVariant))
                byReason.take(3).forEach { (reason, list) ->
                    card.addView(Md3.ellipsize(
                        Md3.text(this, "· ${list.size} 条：$reason", Md3.FS_SM, Md3.onSurfaceVariant)))
                }
                if (byReason.size > 3) {
                    card.addView(Md3.text(this, "· 另有 ${byReason.size - 3} 类原因", Md3.FS_SM, Md3.onSurfaceVariant))
                }
            }
            // 「来源」原来是逐条列内部文件名（2972e4a8_kk笔趣阁_http_…），
            // 对用户没有任何用处。文件数并进卡片一行，列表只留给真实书源。
            card.addView(Md3.text(this, "源文件 ${files.size} 个", Md3.FS_SM, Md3.onSurfaceVariant))
            col.addView(card)

            // 全部列出，可用的排前面。以前只列"可用"的，用户会以为源丢了 ——
            // 54 条里 11 条不可用是正常的，应当看得见、能知道原因。
            val sorted = rows.sortedBy { if (it.mark == "✓") 0 else if (it.mark == "⚠") 1 else 2 }
            col.addView(Widgets.sectionLabel(this, "全部书源（${rows.size}）"))
            sorted.take(400).forEach { r ->
                val color = when (r.mark) {
                    "✓" -> Md3.ok
                    "⚠" -> Md3.primary
                    else -> Md3.error
                }
                // M7：声明明文、且实测 https 也升级不上去的源才标「明文」。
                // 探测结论来自 [Http.cleartextHosts]：没发过请求的源还不知道，不预标。
                val plain = r.declaredHttp && r.plainConfirmed
                val sub = listOfNotNull(
                    r.note?.let { shortReason(it) },
                    if (plain) "明文传输" else null,
                ).joinToString(" · ").ifEmpty { null }
                col.addView(Widgets.listItem(
                    this, r.name, sub, twoLine = sub != null,
                    highlight = false,
                    leading = Icons.view(this, when (r.mark) {
                        "✓" -> "check"; "⚠" -> "warning"; else -> "close"
                    }, color, 16),
                ))
            }
            if (sorted.size > 400) {
                col.addView(Md3.text(this, "（还有 ${sorted.size - 400} 条未显示）",
                    Md3.FS_SM, Md3.onSurfaceVariant))
            }
        }
    }
    root.addView(body)
}

/**
 * 书源市场。列表只拿元数据（快），点某一条才拉完整规则（约 1 秒），
 * 装上后落盘缓存，下次不再拉。
 *
 * 全站 31606 条 / 2.5 GB，全量拉要 9 小时，所以不做整库导入。
 */
/**
 * 发现页：选站点 → 选频道 → 选分类 → 看那一个榜。
 *
 * 以前是一条混合流，把几个榜各取一页轮流拼起来 —— 结果「番茄更新」那种新鲜但
 * 难看的书把好榜的份额挤掉了。现在番茄有 26 个分类 × 阅读/新书两榜，起点有 5 个榜，
 * 全并行拉一次就是 52 个请求，所以改成**选中后只拉一个榜**。
 *
 * 番茄分类榜走页面 SSR，一屏 10 本、不可翻页；起点每页 20 本、可翻到第 10 页。
 * 点书名走既有的多源搜索：起点没有对应 [BookSource]，番茄榜单的书也未必能直取目录。
 */
internal fun MainActivity.showDiscover(
    site: String = "fq",
    gender: String = "male",
    rankKey: String = "",
) {
    setScreen(Screen.Discover(site, gender, rankKey))
    root.removeAllViews()

    // 这个站点+频道下有哪些榜。
    val genderEnum = if (gender == "female") RankConfig.Gender.FEMALE else RankConfig.Gender.MALE
    val siteEnum = if (site == "qd") RankConfig.Site.QIDIAN else RankConfig.Site.FANQIE
    val avail = discoverRanks().filter { it.site == siteEnum && it.gender == genderEnum }
    // 没指定就选第一个。两栏布局里右栏空着等于白占一半屏。
    val sel = avail.firstOrNull { it.key == rankKey } ?: avail.firstOrNull()

    // 根屏（底栏三栏之一），不挂返回箭头，动作图标钉在标题行右端。
    root.addView(Widgets.headerRow(this, sel?.name ?: "发现",
        trailing = listOf("refresh" to { showDiscover(site, gender, rankKey) })))

    // 站点与频道合成一行。手表屏高只有 248dp，两个各带分组标题的区块
    // 各吃掉约 69dp，两栏就没地方了。
    val head = Md3.row(this)
    head.setPadding(Md3.dp(this, Md3.PAD), 0, Md3.dp(this, Md3.PAD), 0)
    for ((on, label, act) in listOf<Triple<Boolean, String, () -> Unit>>(
        Triple(site == "fq", "番茄", { if (site != "fq") showDiscover("fq", gender, "") }),
        Triple(site == "qd", "起点", { if (site != "qd") showDiscover("qd", gender, "") }),
        Triple(gender == "male", "男频", { if (gender != "male") showDiscover(site, "male", "") }),
        Triple(gender == "female", "女频", { if (gender != "female") showDiscover(site, "female", "") }),
    )) {
        val b = Widgets.button(this, label, if (on) "filled" else "tonal", act)
        b.contentDescription = label
        head.addView(b, LinearLayout.LayoutParams(0, Md3.dp(this, Md3.BTN), 1f).apply {
            if (head.childCount > 0) leftMargin = Md3.dp(this@showDiscover, Md3.GAP / 2)
        })
    }
    head.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, Md3.BTN),
    ).apply { bottomMargin = Md3.dp(this@showDiscover, Md3.GAP) }
    root.addView(head)

    if (avail.isEmpty()) {
        root.addView(Widgets.emptyState(this, "list", "这个频道暂时没有榜单"), Md3.lpWeight(1f))
        attachTabs(1)
        return
    }
    val cur = sel ?: return

    // 两栏：左分类（自己滚），右书目（表冠滚）。
    val split = Md3.row(this)
    split.layoutParams = Md3.lpWeight(1f)
    split.setPadding(Md3.dp(this, Md3.PAD), 0, Md3.dp(this, Md3.PAD), Md3.dp(this, Md3.PAD))
    root.addView(split)

    // 侧栏宽度：手表 189dp 下给 59dp，四字分类刚好放得下。
    val railScroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        layoutParams = LinearLayout.LayoutParams(
            Md3.dp(this@showDiscover, 59), ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val rail = Md3.column(this)
    railScroll.addView(rail, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    split.addView(railScroll)

    // 右侧书目用 scrollBody 是为了白拿它的表冠滚动；它自带的四周 PAD
    // 在这里会顶出一倍的缝，所以置零，边距由 split 统一给。
    val paneBody = Widgets.scrollBody(this, crownStep, crownGlide)
    paneBody.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        .apply { leftMargin = Md3.dp(this@showDiscover, Md3.GAP) }
    paneBody.setPadding(0, 0, 0, 0)
    split.addView(paneBody)
    val pane = Widgets.contentColumn(paneBody)

    for (r in avail) {
        val on = r.key == cur.key
        // 榜单名形如「西方奇幻 · 阅读」，侧栏只放分类名。
        val label = if (site == "qd") r.name else r.name.substringBefore(" · ")
        val item = Md3.text(this, label, Md3.FS_SM,
            if (on) Md3.onPrimaryContainer else Md3.onSurfaceVariant, bold = on).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            setPadding(Md3.dp(context, 6), Md3.dp(context, 3), Md3.dp(context, 6), Md3.dp(context, 3))
            minimumHeight = Md3.dp(context, Md3.ITEM)
            background = if (on) Md3.bg(Md3.primaryContainer, Md3.R_ITEM.toFloat())
                         else Md3.ripple(context, Color.TRANSPARENT, Md3.R_ITEM.toFloat())
            contentDescription = label
            isClickable = true
            setOnClickListener { if (!on) showDiscover(site, gender, r.key) }
        }
        rail.addView(item, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Md3.dp(this@showDiscover, 2) })
    }

    val holder = Md3.column(this)
    pane.addView(holder)
    holder.addView(Md3.text(this, "正在拉取…", Md3.FS_SM, Md3.onSurfaceVariant))

    // 云端配置照旧在后台刷：拉到了且列表真变了就重画一次，没变就不动。
    refreshRanks(Screen.Discover(site, gender, rankKey)) {
        showDiscover(site, gender, rankKey)
    }

    bg(Screen.Discover(site, gender, rankKey), { loadOneRank(cur) }) { res ->
        holder.removeAllViews()
        res.onSuccess { hits ->
            if (hits.isEmpty()) {
                holder.addView(Md3.text(this, "这个榜暂时没有内容",
                    Md3.FS_SM, Md3.onSurfaceVariant))
            } else {
                for (h in hits) holder.addView(discoverItem(pane, h))
            }
        }.onFailure { e ->
            holder.addView(Md3.text(this, "取不到：${e.message?.take(60)}",
                Md3.FS_SM, Md3.error))
            holder.addView(Widgets.button(this, "重试", "tonal", {
                showDiscover(site, gender, rankKey)
            }))
        }
    }
    attachTabs(1)
}

/**
 * 发现页的一行。
 *
 * 副标题优先用 [BookHit.extra]（排名 · 字数 · 最新章节），没有就退回作者。
 * 点进去走多源搜索。
 */
private fun MainActivity.discoverItem(col: LinearLayout, h: BookHit): LinearLayout {
    val sub = listOf(
        h.author.takeIf { it.isNotBlank() },
        h.extra.takeIf { it.isNotBlank() },
    ).filterNotNull().joinToString(" · ")
    return Widgets.listItem(this, h.name, sub, twoLine = true, highlight = true) {
        rememberReturn()
        openDiscoverHit(h)
    }
}

/**
 * 拉起一个榜。
 *
 * 番茄分类榜一屏 10 本、不可翻页；起点每页 20 本，这里把前 [RankConfig.Rank.pages]
 * 页串起来（最多 10 页 = 200 本）。整批共用一个死线，免得 10 页各自计时。
 */
private fun MainActivity.loadOneRank(r: RankConfig.Rank): List<BookHit> {
    // 字符表只读一次。读不到也不致命，书名只是带私用区字符。
    val decoder = runCatching {
        CharsetFontDecoder.fromFile(File(filesDir, "charset.json").absolutePath)
    }.getOrNull()?.takeIf { it.isReady }

    val gender = if (r.gender == RankConfig.Gender.FEMALE) "female" else "male"
    val deadline = System.currentTimeMillis() + RankSource.PAGE_BUDGET_MS * 2
    // S18：以前非起点榜一律 1 页，配置里写 pages:5 也无效。
    // 现在按配置走，0 表示不可翻页。
    val pages = r.pages.coerceIn(0, 10).coerceAtLeast(1)
    val out = LinkedHashMap<String, BookHit>()
    for (p in 1..pages) {
        if (System.currentTimeMillis() > deadline) break
        val got = runCatching { RankSource.fetch(r, gender, p, deadline, decoder) }
            .getOrElse { if (p == 1) throw it else break }
        for (h in got) out.putIfAbsent(h.bookId, h)
    }
    return out.values.toList()
}


/**
 * 当前可用的榜单。**已缓存的纯内存读，首次也只读磁盘和内置表，都不联网。**
 *
 * 以前这里直接调 [RankConfig.load]，而 load 最坏串行问 4 个镜像、
 * 总预算 20 秒。它在主线程被调 —— 点「发现榜单」就冻住十几秒，看着就是卡死。
 * 现在联网的部分全交给 [refreshRanks] 在后台做。
 */
private fun MainActivity.discoverRanks(): List<RankConfig.Rank> {
    rankCache?.let { return it }
    val got = RankConfig.local(File(filesDir, "sources"))
    // 空表不缓存 —— 否则一次取数失败会让本次进程再也不重试。
    if (got.isNotEmpty()) rankCache = got
    return got
}

/**
 * 后台拉一次云端配置；拉到了就重新渲染当前发现页。
 *
 * 只在榜选择器画完之后调，所以即使慢或失败也不影响用户先看到本地榜单。
 * 重渲染前已经靠 [postAlive] 核过屏身份 —— 用户已经走了就不重画。
 */
private fun MainActivity.refreshRanks(owner: Screen, rerender: () -> Unit) {
    if (rankRefreshing || rankRefreshed) return
    rankRefreshing = true
    bg(owner, { RankConfig.load(File(filesDir, "sources")) }) { res ->
        rankRefreshing = false
        val got = res.getOrNull()?.first ?: return@bg
        if (got.isEmpty()) return@bg
        rankRefreshed = true
        val before = rankCache
        rankCache = got
        // 内容没变就不重画，避免回到发现页时闪一下。
        if (got != before) rerender()
    }
}
internal fun MainActivity.showMarket(page: Int, accumulated: List<YioveSource.Item>) {
    setScreen(Screen.Market(accumulated, 0, page))
    root.removeAllViews()
    root.addView(Widgets.topBar(this, "书源市场", onBack = { showSources() }))

    val body = Widgets.scrollBody(this, crownStep, crownGlide)
    val col = Widgets.contentColumn(body)
    root.addView(body)

    if (accumulated.isEmpty()) {
        col.addView(Widgets.emptyState(this, "cloud", "正在拉取第 $page 页…"))
    } else {
        renderMarket(col, accumulated, page)
    }

    val owner: Screen = Screen.Market(accumulated, 0, page)
    bg(owner, { YioveSource.list(page = page, pageSize = 30) }) { res ->
        res.onSuccess { p ->
            val all = accumulated + p.items
            setScreen(Screen.Market(all, p.total, page))
            col.removeAllViews()
            renderMarket(col, all, page, p.total, p.pages)
        }.onFailure { e ->
            col.removeAllViews()
            col.addView(Widgets.emptyState(this, "warning", "拉取失败：${e.message?.take(60)}"))
            col.addView(Widgets.button(this, "重试", "tonal", { showMarket(page, accumulated) }))
        }
    }
}

private fun MainActivity.renderMarket(
    col: LinearLayout,
    items: List<YioveSource.Item>,
    page: Int,
    total: Int = 0,
    pages: Int = 0,
) {
    // 已装过的标记出来（按 id 前 8 位找文件名）
    val dir = sourcesDir()
    val installed = YioveSource.installed(dir).map { it.name }.toSet()

    if (total > 0) {
        val card = Widgets.card(this, outlined = true)
        card.addView(Md3.text(this, "共 $total 条 · 第 $page/$pages 页", Md3.FS_MD, Md3.onSurface))
        card.addView(Md3.text(this,
            "点选一条即拉取并导入；全量太大，只按需拉", Md3.FS_SM, Md3.onSurfaceVariant))
        col.addView(card)
    }

    col.addView(Widgets.sectionLabel(this, "第 $page 页"))
    val startIdx = if (total > 0) items.size - 30 else 0
    items.drop(startIdx).forEach { it0 ->
        val has = installed.any { it.startsWith(it0.id.take(8)) }
        col.addView(Widgets.listItem(
            this, it0.name,
            if (has) "已导入" else "热度 ${it0.views}",
            twoLine = true, highlight = false,
            leading = Icons.view(this, if (has) "check" else "download",
                if (has) Md3.ok else Md3.onSurfaceVariant, 18),
        ) { if (!has) installFromMarket(it0.name, it0.id, page, items) })
    }

    val more = Widgets.button(this, "下一页", "tonal", { showMarket(page + 1, items) })
    more.layoutParams = Md3.lpDp(this, null, Md3.BTN).apply {
        topMargin = Md3.dp(this@renderMarket, Md3.GAP)
    }
    col.addView(more)
}

/** 拉单条规则并导入（约 1 秒），成功后回列表把该条标成已导入。 */
private fun MainActivity.installFromMarket(name: String, id: String, page: Int, shown: List<YioveSource.Item>) {
    toast("正在拉取「$name」…")
    val owner: Screen = Screen.Market(shown, 0, page)
    bg(owner, { YioveSource.install(sourcesDir(), YioveSource.Item(id, name, "", 0)) }) { res ->
        res.onSuccess { r ->
            toast(if (r.ok) "已导入：${r.name}" else "不支持：${r.issue ?: "未知原因"}")
            showMarket(page, shown.filter { it.name != name })
        }.onFailure { toast("导入失败：${it.message?.take(50)}") }
    }
}

/** 从云端整包更新书源库。成功后回书源页，能看到新的条数。 */
private fun MainActivity.updateFromCloud() {
    toast("正在检查…")
    val owner: Screen = Screen.Sources
    bg(owner, {
        CloudSource.update(sourcesDir(), onProgress = { msg -> postAlive(owner) { toast(msg) } })
    }) { res ->
        val r = res.getOrElse { e ->
            DevLog.log("WatchDL", "云端更新失败：${e.message}")
            CloudSource.Result(false, "更新异常：${e.message?.take(60)}")
        }
        toast(r.message)
        // 不管成没成都会书源页 —— 失败时用户能看到当前条数，知道没被弄坏。
        showSources()
    }
}

/** 本地书源一行。 */
private data class SourceRow(
    val file: String,
    val name: String,
    val mark: String,
    val note: String?,
    /** 声明为 http:// 的源（M7）；实际是否回落到明文由 [Http.cleartextHosts] 决定。 */
    val declaredHttp: Boolean = false,
    /** 书源根地址，用来把探测结论对到这条行上。null = 导入器没给出。 */
    val host: String? = null,
) {
    /** 声明明文、且探测结果明确说这个 host 升不上 https。 */
    val plainConfirmed: Boolean
        get() = declaredHttp && host != null && Http.isCleartextHost(host)
}

/**
 * 读 [filesDir]/sources 下所有 json，导入并统计。
 *
 * 抽成独立函数是为了让调用方能用 try 包住 —— 以前这段直接写在 thread 里，
 * 一抛异常整屏就空白，表现成「书源 0 条」。
 */
/** 诊断用：本地书源目录路径。 */
private val MainActivity.srcDirPath: String get() = File(filesDir, "sources").absolutePath

private fun MainActivity.buildLocalSourceRows(): Quad<List<File>, List<SourceRow>, Int, Int> {
    val dir = File(filesDir, "sources").apply { mkdirs() }
    val files = sourceFiles(dir)
    val rows = mutableListOf<SourceRow>()
    var total = 0
    var usable = 0
    for (f in files) {
        val text = runCatching { f.readText() }.getOrNull() ?: continue
        val imported = runCatching {
            if (text.contains("bookSourceName")) LegadoImport.importAll(text)
            else SonovelImport.importAllChecked(text).map { (n, r, issue) ->
                // S16：以前 `.map { (n, r) -> Imported(n, r, null) }`，issue 永远 null，
                // 缺字段被丢掉的条目在界面上完全不出声。
                LegadoImport.Imported(n, r, issue)
            }
        }.getOrElse {
            DevLog.log("WatchDL", "导入 ${f.name} 失败：${it.message}")
            emptyList()
        }
        if (imported.isEmpty()) continue
        total += imported.size
        usable += imported.count { it.rule != null }
        imported.forEach { i ->
            rows += SourceRow(f.name, i.name,
                if (i.rule == null) "✗" else if (i.issue != null) "⚠" else "✓", i.issue,
                i.declaredHttp, i.rule?.host)
        }
    }
    // 探测是按 host 记的，回填到行上；没探过的 host 保留 null。
    return Quad(files, rows, total, usable)
}

/** 四元组。Kotlin 标准库只有 Pair/Triple。 */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/**
 * 书源目录里**真正参与搜索**的 json。
 *
 * 书源目录原本是「所有 .json 都要」—— 于是任何时候往这里放过第二个文件
 * （旧版留下的 `legado.qualified.json`、手工备份、云端整包的另一份），
 * 它们会跟正式源库叠在一起，用户看到两百多个源却说不清哪来的。
 * 2026-10-21 实测设备上 `legado.json`(175) + `legado.qualified.json`(42) = 217 条。
 *
 * 现在只认两类：
 * - `legado.json`：云端 / 内置的整包源库（唯一会被同步覆盖的文件）
 * - 不含 175 条整包特征的散装文件：书源市场单条导入、手动导入的
 *
 * 备份一律带 `.bak` 后缀，天然被 extension 过滤掉。
 */
internal fun MainActivity.sourceFiles(dir: File): List<File> =
    dir.listFiles()
        ?.filter { it.isFile && it.extension == "json" }
        ?.filter { !it.name.startsWith(".") }
        ?.sortedBy { it.name }
        ?: emptyList()

/** 书源目录。本地书源与市场导入的都放这里。 */
internal fun MainActivity.sourcesDir(): File = File(filesDir, "sources").apply { mkdirs() }

/**
 * 把导入器的长原因压缩成一句能看的短句。
 *
 * `部分字段用脚本：searchUrl(脚本)、content(非@js脚本)` 这种在列表里
 * 只占一行、又看不出重点，所以归类到几个大类。
 */
private fun MainActivity.shortReason(note: String?): String {
    val n = note ?: return ""
    return when {
        // 以前直接把「引擎正则错误：<字段> | <异常>」摊给用户看（P1-4）。
        // 完整信息 logcat 里有，界面只说人话。
        n.startsWith("引擎正则错误") -> "这条源的解析规则无法执行"
        n.startsWith("解析异常") -> "解析这条源时出错"
        n.contains("非@js脚本") || n.contains("非 @js 脚本") -> "用了不支持的脚本写法"
        n.contains("脚本") && n.contains("部分字段") -> "部分字段靠脚本"
        n.contains("无 searchUrl") -> "没有搜索地址（只能探索）"
        n.contains("不是绝对地址") || n.contains("代码而非地址") -> "搜索地址不合法"
        n.contains("源已禁用") -> "源已禁用"
        n.contains("请求头用脚本") -> "请求头需要脚本"
        n.length > 26 -> n.take(26) + "…"
        else -> n
    }
}

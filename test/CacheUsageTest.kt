import com.watchdl.core.BookHit
import com.watchdl.core.FindSeg
import com.watchdl.core.findHighlight
import com.watchdl.core.findMatcher
import com.watchdl.core.findRegexUsable
import com.watchdl.core.Bookmark
import com.watchdl.core.markChapterLabel
import com.watchdl.core.markDeleteMessage
import com.watchdl.core.markExcerpt
import com.watchdl.core.markGroupLabel
import com.watchdl.core.markKey
import com.watchdl.core.markMatch
import com.watchdl.core.markMeta
import com.watchdl.core.markSelectionPrune
import com.watchdl.core.markSelectionSelectAll
import com.watchdl.core.markSelectionToggle
import com.watchdl.core.markShown
import com.watchdl.core.markSorted
import com.watchdl.core.markSubtitle
import com.watchdl.core.markTimeLabel
import com.watchdl.core.recordClearMessage
import com.watchdl.core.recordDeleteMessage
import com.watchdl.core.recordMatch
import com.watchdl.core.recordSubtitle
import com.watchdl.core.recordSubtitleText
import com.watchdl.core.ChapterCacheStatus
import com.watchdl.core.DownloadQueue
import com.watchdl.core.JobStore
import com.watchdl.core.QueueItem
import com.watchdl.core.chapterCacheStatusText
import com.watchdl.core.detailCurrentLine
import com.watchdl.core.detailDownloadTag
import com.watchdl.core.detailEmptyTocHint
import com.watchdl.core.detailIntroText
import com.watchdl.core.detailIntroToggleLabel
import com.watchdl.core.detailIntroToggleVisible
import com.watchdl.core.detailLatestLine
import com.watchdl.core.detailReadState
import com.watchdl.core.detailRemoveMessage
import com.watchdl.core.detailShelfAction
import com.watchdl.core.detailStatParts
import com.watchdl.core.detailTocFailText
import com.watchdl.core.detailTotalText
import com.watchdl.core.ReaderFind
import com.watchdl.core.ReaderStyle
import com.watchdl.core.ReplacePrefs
import com.watchdl.core.ReplaceRule
import com.watchdl.core.ReplaceSort
import com.watchdl.core.replaceApplyOrder
import com.watchdl.core.replaceDeleteMessage
import com.watchdl.core.replaceFlipEnabled
import com.watchdl.core.replaceKey
import com.watchdl.core.replaceListLabel
import com.watchdl.core.replaceMatch
import com.watchdl.core.replaceMoveToEdge
import com.watchdl.core.replacePruneSelection
import com.watchdl.core.replaceRemove
import com.watchdl.core.replaceRowSubtitle
import com.watchdl.core.replaceSelectAll
import com.watchdl.core.replaceSelectTitle
import com.watchdl.core.replaceSetEnabled
import com.watchdl.core.replaceSorted
import com.watchdl.core.replaceStateJson
import com.watchdl.core.replaceStateParse
import com.watchdl.core.replaceSubtitle
import com.watchdl.core.replaceToggleSelect
import com.watchdl.core.replaceUpsert
import com.watchdl.core.replaceValidate
import com.watchdl.core.resolveChapterCacheStatus
import com.watchdl.core.SEARCH_HISTORY_MAX
import com.watchdl.core.SEARCH_HISTORY_SHOWN
import com.watchdl.core.SearchHistoryStore
import com.watchdl.core.searchHitLine
import com.watchdl.core.searchHistoryAdd
import com.watchdl.core.searchHistoryFormat
import com.watchdl.core.searchHistoryKey
import com.watchdl.core.searchHistoryParse
import com.watchdl.core.searchHistoryRemove
import com.watchdl.core.searchPartialHint
import com.watchdl.core.searchPreciseHits
import com.watchdl.core.searchPreciseKeep
import com.watchdl.core.searchRankTier
import com.watchdl.core.searchRanked
import com.watchdl.core.searchSourceBadge
import com.watchdl.core.searchSummaryText
import com.watchdl.core.shelfDeleteMessage
import com.watchdl.core.shelfPruneSelection
import com.watchdl.core.shelfSelectAll
import com.watchdl.core.shelfSelectSubtitle
import com.watchdl.core.shelfSelectTitle
import com.watchdl.core.SourceFilter
import com.watchdl.core.SourcePrefs
import com.watchdl.core.SourcePrefsStore
import com.watchdl.core.SourceSort
import com.watchdl.core.sourceApplyOrder
import com.watchdl.core.sourceDeleteMessage
import com.watchdl.core.sourceEnabled
import com.watchdl.core.sourceFilterLabel
import com.watchdl.core.sourceListLabel
import com.watchdl.core.sourceMatchFilter
import com.watchdl.core.sourceMoveToEdge
import com.watchdl.core.sourcePruneSelection
import com.watchdl.core.sourceSelectAll
import com.watchdl.core.sourceSelectTitle
import com.watchdl.core.sourceSetEnabled
import com.watchdl.core.sourceSortLabel
import com.watchdl.core.sourceSortNames
import com.watchdl.core.sourceStateJson
import com.watchdl.core.sourceStateParse
import com.watchdl.core.sourceSubtitle
import com.watchdl.core.sourceToggle
import com.watchdl.core.sourceToggleSelect
import com.watchdl.core.shelfSourceTag
import com.watchdl.core.shelfSubtitle
import com.watchdl.core.shelfToggle
import com.watchdl.core.shelfUnreadCount
import com.watchdl.core.ChapterRef
import com.watchdl.core.ShelfSort
import com.watchdl.core.ShelfSortKey
import com.watchdl.core.mergeShelfCatalog
import com.watchdl.core.shelfCompare
import com.watchdl.core.TinyJson
import java.io.File

/**
 * 缓存管理的统计口径（#26 返工）。
 *
 * 「已缓存」必须按 `chapters.jsonl` 的实际行数算，不能信 job.json 的 status：
 * 快照按批写，断电时最后一批 DONE 没落盘，状态说已下、磁盘上没有。
 * 这一页是拿来决定删谁的，虚报一次就是让用户删错书。
 */
private var failures = 0

private fun check(desc: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ✓ $desc")
    else { println("  ✗ $desc  $detail"); failures++ }
}

private fun job(bookId: String, name: String, chapters: Int, status: String) =
    jobMixed(bookId, name, List(chapters) { status })

/** 逐章给状态的作业快照。 */
private fun jobMixed(bookId: String, name: String, statuses: List<String>) =
    """{"bookId":"$bookId","name":"$name","author":"人","state":"PAUSED","total":${statuses.size},""" +
        """"chapters":[${statuses.mapIndexed { i, s ->
            """{"itemId":"c$i","title":"第$i 章","locked":false,"status":"$s"}"""
        }.joinToString(",")}]}"""

fun main() {
    val root = File(System.getProperty("java.io.tmpdir"), "cacheusage-" + System.nanoTime())
    root.mkdirs()
    val store = JobStore(root)
    println("[缓存统计] 根目录 $root")

    // 状态说三章全下完，磁盘上只有一行 —— 统计要报 1/3，不能报 3/3。
    store.writeJob("a", job("a", "只落了一章", 3, "DONE"))
    store.appendChapter("a", """{"itemId":"c0","content":"<p>正文</p>"}""")

    // 一行都没有：清过正文的书写成 0/3，界面才好提示「缺 3 章」。
    store.writeJob("b", job("b", "清空了", 3, "PENDING"))

    // 目录缩水后 jsonl 可能比目录还多：cached 不能超过 total。
    store.writeJob("c", job("c", "目录缩过水", 2, "DONE"))
    repeat(5) { store.appendChapter("c", """{"itemId":"old$it","content":"x"}""") }

    // 只有目录没有 job.json 的不算一本书。
    File(root, "shell").mkdirs()

    val all = store.cacheUsages()
    val byId = all.associateBy { it.bookId }
    check("三本书都列出来", all.size == 3, all.map { it.bookId }.toString())
    check("不信 job.json 的 status", byId["a"]?.cached == 1, byId["a"]?.cached.toString())
    check("总数取目录章数", byId["a"]?.total == 3, byId["a"]?.total.toString())
    check("清过的书报 0 章", byId["b"]?.cached == 0 && byId["b"]?.bytes == 0L,
        "${byId["b"]?.cached}/${byId["b"]?.bytes}")
    check("cached 不超过 total", byId["c"]?.cached == 2, byId["c"]?.cached.toString())
    check("没有 job.json 的目录不算书", all.none { it.bookId == "shell" })
    check("书名读得到", byId["a"]?.name == "只落了一章", byId["a"]?.name.orEmpty())
    check("正文字节是 jsonl 的大小",
        byId["a"]?.bytes == store.chaptersFile("a").length(), byId["a"]?.bytes.toString())
    check("占用大的在前", all.first().bookId == "c", all.map { it.bookId }.toString())

    // 缓存页顶栏要报「失败 N 章」，所以失败章数与作业状态得一并从 job.json 读出来。
    store.writeJob("d", jobMixed("d", "两败一好", listOf("FAILED", "FAILED", "PENDING")))
    val d = store.cacheUsages().first { it.bookId == "d" }
    check("失败章数从 job.json 数出来", d.failed == 2, d.failed.toString())
    check("作业状态读得到", d.state == com.watchdl.core.JobState.PAUSED, d.state.toString())

    // 队列：『全部开始』排进去的书要能落盘、能按顺序取，重复排不得重复下。
    val qf = File(root, "queue.json")
    val q = DownloadQueue(qf)
    check("空队列没有队头", q.pop() == null)
    val n1 = q.enqueue(listOf(
        QueueItem("https://a/1", "甲", "A"),
        QueueItem("https://b/2", "乙", "B"),
    ))
    check("两本入队", n1 == 2, n1.toString())
    check("重复排不重复下", q.enqueue(listOf(QueueItem("https://a/1", "甲", "A"))) == 2,
        q.items().size.toString())
    check("落盘后能读回", DownloadQueue(qf).items().map { it.name } == listOf("甲", "乙"),
        DownloadQueue(qf).items().map { it.name }.toString())
    check("带引号的 id 不坏 json",
        q.enqueue(listOf(QueueItem("a\"b", "丙", "C"))) == 3 &&
            DownloadQueue(qf).items().last().bookId == "a\"b",
        DownloadQueue(qf).items().joinToString { it.bookId })
    check("队头先出", q.pop()?.bookId == "https://a/1", q.items().firstOrNull()?.bookId.orEmpty())
    check("出队后剩两本", q.items().size == 2, q.items().size.toString())
    q.clear()
    check("清队后为空", q.items().isEmpty())
    check("文件也删了", !qf.exists())

    // 八态优先级：照 legado `resolveChapterCacheStatusKey` 的判定顺序。
    println("[缓存章行八态]")
    fun st(
        downloading: Boolean = false, waiting: Boolean = false, paused: Boolean = false,
        error: Boolean = false, cached: Boolean = false, label: Boolean = false,
    ) = resolveChapterCacheStatus(downloading, waiting, paused, error, cached, label)
    check("正在取且带进度标签 → ProgressLabel", st(downloading = true, label = true) ==
        ChapterCacheStatus.ProgressLabel)
    check("正在取 → Downloading", st(downloading = true, cached = true) ==
        ChapterCacheStatus.Downloading)
    check("排队且失败过 → 等重试", st(waiting = true, error = true) ==
        ChapterCacheStatus.ErrorWaitingRetry)
    check("排队 → Waiting", st(waiting = true) == ChapterCacheStatus.Waiting)
    check("暂停 → Paused", st(paused = true) == ChapterCacheStatus.Paused)
    check("失败 → Error", st(error = true) == ChapterCacheStatus.Error)
    check("已缓存 → Cached", st(cached = true) == ChapterCacheStatus.Cached)
    check("什么都没有 → NotCached", st() == ChapterCacheStatus.NotCached)
    check("失败且磁盘有正文，仍报失败", st(error = true, cached = true) == ChapterCacheStatus.Error)
    check("暂停优先于失败", st(paused = true, error = true) == ChapterCacheStatus.Paused)
    check("文案对齐 legado 中文包", chapterCacheStatusText(ChapterCacheStatus.Waiting) == "待下载" &&
        chapterCacheStatusText(ChapterCacheStatus.Cached) == "下载完成" &&
        chapterCacheStatusText(ChapterCacheStatus.NotCached) == "未缓存")

    // 书架卡与选择模式（照 legado-novel `BookItem.kt` 的 leftBottomText、
    // `BookshelfTopBar` 的编辑态）。
    println("[书架卡与选择]")
    check("未读 = 总章 - 在读 - 1", shelfUnreadCount(10, 3) == 6)
    check("没记录过（下标 0）→ 总章减 1", shelfUnreadCount(10, 0) == 9)
    check("读到最后一章 → 0", shelfUnreadCount(10, 9) == 0)
    check("下标超界不出现负数", shelfUnreadCount(10, 50) == 0)
    check("没有章 → 0", shelfUnreadCount(0, 0) == 0)
    check("副标题", shelfSubtitle(12, 3) == "12 本 · 在读 3")
    check("在读数不超总数", shelfSubtitle(3, 9) == "3 本 · 在读 3")
    check("选择标题照 legado 措辞", shelfSelectTitle(2) == "已选 2")
    check("选择副标题照 legado 措辞", shelfSelectSubtitle(9) == "共 9 本")
    check("单本删除文案点名书名",
        shelfDeleteMessage(listOf("剑来")) == "删除《剑来》的缓存与目录，不可恢复")
    check("多本删除文案给本数",
        shelfDeleteMessage(listOf("a", "b", "c")) == "删除 3 本书的缓存与目录，不可恢复")
    check("没有书源给「本地」", shelfSourceTag(null) == "本地" && shelfSourceTag("  ") == "本地")
    check("书源名截到 4 字", shelfSourceTag("番茄小说app") == "番茄小说")
    val sel = linkedSetOf<String>()
    check("点一下选中", shelfToggle(sel, "a") && sel.contains("a"))
    check("再点一下取消", !shelfToggle(sel, "a") && sel.isEmpty())
    check("全选", shelfSelectAll(sel, listOf("a", "b")) && sel.size == 2)
    check("再点全选变全不选", !shelfSelectAll(sel, listOf("a", "b")) && sel.isEmpty())
    sel.addAll(listOf("a", "gone"))
    check("剔除已不在架上的选中项", shelfPruneSelection(sel, listOf("a")) && sel == setOf("a"))
    check("选中的都删了 → 该退出选择模式", !shelfPruneSelection(sel, emptyList()))

    // 书详情屏（照 legado-novel `ui/book/info/BookInfoScreen.kt` 的
    // `BookInfoSummary` / `BookInfoActions` / `BookInfoIntro`）。
    println("[书详情]")
    check("第一行照 toc_s 措辞", detailCurrentLine("第一章") == "在读 · 第一章")
    check("没有章名时用「加载中…」", detailCurrentLine(null) == "在读 · 加载中…")
    check("第二行照 lasted_show 措辞", detailLatestLine("末章") == "最新 · 末章")
    check("没有最新章时整行不画", detailLatestLine("  ") == null && detailLatestLine(null) == null)
    check("总章照 read_chapter_total 措辞", detailTotalText(320) == "共 320 章")
    check("章数负数不出现负号", detailTotalText(-3) == "共 0 章")
    check("没读过 → 未读", detailReadState(10, 0) == "未读")
    check("读到末章 → 已读完", detailReadState(10, 9) == "已读完")
    check("中间 → 已读 M 章（下标 0 基、显示 1 基）", detailReadState(10, 4) == "已读 5 章")
    check("单章书没读过 → 未读（未读判定在前）", detailReadState(1, 0) == "未读")
    check("章数超界 → 已读完不留负数", detailReadState(3, 50) == "已读完")
    check("空目录 → 未读", detailReadState(0, 0) == "未读")
    check("统计行三段顺序", detailStatParts(10, 4) == listOf("共 10 章", "|", "已读 5 章"))
    check("加载目录失败照 error_load_toc 措辞", detailTocFailText() == "加载目录失败")
    check("还没取目录 → 明说要按哪张卡",
        detailEmptyTocHint(false) == "目录还没取，点「查看目录」开始")
    check("取失败过 → 如实报失败，不冒充未取",
        detailEmptyTocHint(true) == "目录加载失败，点「查看目录」重试" &&
            !detailEmptyTocHint(true).contains("还没取"))
    check("未入架 → 放入书架 + plus",
        detailShelfAction(false).let { it.label == "放入书架" && it.glyph == "plus" })
    check("已入架 → 已在书架 + bookmark",
        detailShelfAction(true).let { it.label == "已在书架" && it.glyph == "bookmark" })
    check("移出确认写清不可恢复",
        detailRemoveMessage("剑来") == "移出《剑来》：删掉已下载的缓存与目录，不可恢复")
    check("没有书名时给「未命名」", detailRemoveMessage(" ").contains("未命名"))
    check("下载小牌：没下过", detailDownloadTag(0, 300) == "未下载")
    check("下载小牌：下了一部分", detailDownloadTag(40, 300) == "已下载 40/300")
    check("下载小牌：下完", detailDownloadTag(300, 300) == "已下载 300/300")
    check("下载小牌：done 超总数夹回", detailDownloadTag(999, 300) == "已下载 300/300")
    check("下载小牌：没目录算未下载", detailDownloadTag(5, 0) == "未下载")
    check("折叠按钮照 expand/collapse",
        detailIntroToggleLabel(false) == "展开" && detailIntroToggleLabel(true) == "折叠")
    check("短简介不给折叠按钮", !detailIntroToggleVisible("很短的一句简介。"))
    check("长简介给折叠按钮", detailIntroToggleVisible("啊".repeat(80)))
    check("占位摘要按没有简介处理", detailIntroText("...") == null && detailIntroText("") == null)
    check("正常简介原样返回", detailIntroText("  一本讲剑与江湖的长篇故事。  ") == "一本讲剑与江湖的长篇故事。")

    // ══════ 书源管理（core/SourceState.kt，对照 legado BookSourceViewModel / BookSourceSort）══════
    check("默认启用", sourceEnabled(emptySet(), "源甲"))
    check("在禁用集里就禁用", !sourceEnabled(setOf("源甲"), "源甲"))
    val dis = mutableSetOf<String>()
    check("点一下变禁用", !sourceToggle(dis, "源甲") && dis == setOf("源甲"))
    check("再点一下变回启用", sourceToggle(dis, "源甲") && dis.isEmpty())
    sourceSetEnabled(dis, listOf("甲", "乙"), false)
    check("批量禁用", dis == setOf("甲", "乙"))
    sourceSetEnabled(dis, listOf("甲"), true)
    check("批量启用只放开选中那条", dis == setOf("乙"))
    check("筛选：全部放过两边", sourceMatchFilter(true, SourceFilter.ALL) &&
        sourceMatchFilter(false, SourceFilter.ALL))
    check("筛选：已启用只要启用", sourceMatchFilter(true, SourceFilter.ENABLED) &&
        !sourceMatchFilter(false, SourceFilter.ENABLED))
    check("筛选：已禁用只要禁用", !sourceMatchFilter(true, SourceFilter.DISABLED) &&
        sourceMatchFilter(false, SourceFilter.DISABLED))
    check("筛选文案照 legado", sourceFilterLabel(SourceFilter.ALL) == "全部" &&
        sourceFilterLabel(SourceFilter.ENABLED) == "已启用" &&
        sourceFilterLabel(SourceFilter.DISABLED) == "已禁用")
    check("顶栏副标题共 N 启用 M", sourceSubtitle(175, 12) == "共 175 · 启用 12")
    check("副标题启用数超界夹回", sourceSubtitle(3, 9) == "共 3 · 启用 3")
    check("分组标题：全部", sourceListLabel(SourceFilter.ALL, "", 175) == "全部书源（175）")
    check("分组标题：已启用", sourceListLabel(SourceFilter.ENABLED, "", 7) == "已启用（7）")
    check("分组标题：搜索优先", sourceListLabel(SourceFilter.ENABLED, "笔趣阁", 3) == "「笔趣阁」3 条")
    check("排序文案照 legado", sourceSortLabel(SourceSort.Order) == "手动排序" &&
        sourceSortLabel(SourceSort.Name) == "名称排序" &&
        sourceSortLabel(SourceSort.Usable) == "可用性排序")
    val names = listOf("甲", "乙", "丙", "丁")
    check("没手动顺序就原样", sourceApplyOrder(names, emptyList()) == names)
    check("手动顺序排前、其余按原序补后",
        sourceApplyOrder(names, listOf("丙", "甲")) == listOf("丙", "甲", "乙", "丁"))
    check("顺序里的生名字被忽略",
        sourceApplyOrder(names, listOf("野", "丁")) == listOf("丁", "甲", "乙", "丙"))
    check("置顶", sourceMoveToEdge(names, emptyList(), listOf("丁"), true) ==
        listOf("丁", "甲", "乙", "丙"))
    check("置底", sourceMoveToEdge(names, emptyList(), listOf("甲"), false) ==
        listOf("乙", "丙", "丁", "甲"))
    check("多条一起置顶，内部顺序保持",
        sourceMoveToEdge(names, emptyList(), listOf("丙", "乙"), true) ==
            listOf("乙", "丙", "甲", "丁"))
    check("置顶没命中时不动", sourceMoveToEdge(names, emptyList(), listOf("野"), true) == names)
    // 名称排序按拼音（legado 用 Collator 比中文）：丙 bing < 甲 jia < 乙 yi。
    check("名称排序按拼音", sourceSortNames(listOf("乙", "甲", "丙"), SourceSort.Name, emptyList(),
        emptySet()) == listOf("丙", "甲", "乙"))
    check("名称排序按字母", sourceSortNames(listOf("c", "a", "B"), SourceSort.Name, emptyList(),
        emptySet()) == listOf("a", "B", "c"))
    check("顺序排序", sourceSortNames(listOf("丙", "甲", "乙"), SourceSort.Order,
        listOf("乙"), emptySet()) == listOf("乙", "丙", "甲"))
    check("可用性排序：可用的排前",
        sourceSortNames(names, SourceSort.Usable, emptyList(), setOf("丁", "乙")) ==
            listOf("乙", "丁", "甲", "丙"))
    check("多选标题照书架屏", sourceSelectTitle(3) == "已选 3")
    val picked = mutableSetOf("甲")
    check("再点取消选中", !sourceToggleSelect(picked, "甲") && picked.isEmpty())
    check("点一下加入选中", sourceToggleSelect(picked, "乙") && picked == setOf("乙"))
    check("全选", sourceSelectAll(picked, names) && picked.size == 4 && "丁" in picked)
    check("再按一次全不选", !sourceSelectAll(picked, names) && picked.isEmpty())
    check("剪完还有选中 → true", run {
        val s2 = mutableSetOf("甲", "野")
        sourcePruneSelection(s2, names) && s2 == setOf("甲")
    })
    check("删光 → false", !sourcePruneSelection(mutableSetOf("野"), names))
    check("删除正文说清源文件不动", sourceDeleteMessage(listOf("源甲")) ==
        "移除《源甲》\n源文件不动，只是不再显示")
    check("批量删除正文带条数", sourceDeleteMessage(listOf("甲", "乙")).startsWith("移除 2 条书源"))
    check("没选中时不提删除", sourceDeleteMessage(emptyList()) == "没有选中任何书源")
    val prefs = SourcePrefs(setOf("甲"), setOf("乙"), listOf("丙", "甲"))
    val round = sourceStateParse(sourceStateJson(prefs))
    check("状态落盘再读回一致", round == prefs)
    check("空状态也一致", sourceStateParse(sourceStateJson(SourcePrefs())) == SourcePrefs())
    check("名字里有引号也读得回",
        sourceStateParse(sourceStateJson(SourcePrefs(disabled = setOf("带\"引号\"的源"))))
            .disabled == setOf("带\"引号\"的源"))
    check("坏 JSON 当空状态", sourceStateParse("{坏").disabled.isEmpty())
    check("缺字段当空状态", sourceStateParse("{}") == SourcePrefs())
    val stateDir = File(root, "state").apply { mkdirs() }
    SourcePrefsStore.save(stateDir, prefs)
    check("落盘文件叫 .state.json", SourcePrefsStore.NAME == ".state.json" &&
        SourcePrefsStore.file(stateDir).isFile)
    check("从盘里读回同一份", SourcePrefsStore.load(stateDir) == prefs)
    check("目录没文件时给空状态", SourcePrefsStore.load(File(root, "nope")) == SourcePrefs())

    // ── 搜索历史与精准过滤（对齐 legado 搜索屏）──────────────
    check("历史最新在前", searchHistoryAdd(listOf("旧"), "新") == listOf("新", "旧"))
    check("重复词顶到最前不重复", searchHistoryAdd(listOf("甲", "乙"), "乙") == listOf("乙", "甲"))
    check("同一词忽略大小写只留一条", searchHistoryAdd(listOf("abc"), "ABC") == listOf("ABC"))
    check("空词不入库", searchHistoryAdd(listOf("甲"), "   ") == listOf("甲"))
    check("两侧空白被切掉", searchHistoryAdd(emptyList(), "  斗破  ") == listOf("斗破"))
    check("上限 10 条，最旧的被挤掉", run {
        var h = emptyList<String>()
        for (i in 1..12) h = searchHistoryAdd(h, "词$i")
        h.size == SEARCH_HISTORY_MAX && h.first() == "词12" && "词1" !in h
    })
    check("菜单一屏 8 条，少于总上限", SEARCH_HISTORY_SHOWN == 8 && SEARCH_HISTORY_SHOWN < SEARCH_HISTORY_MAX)
    check("读盘跳过空行", searchHistoryParse("甲\n\n  \n乙\n") == listOf("甲", "乙"))
    check("读盘按上限裁剪", searchHistoryParse((1..15).joinToString("\n") { "w$it" }).size == SEARCH_HISTORY_MAX)
    check("落盘一行一词", searchHistoryFormat(listOf("甲", "乙")) == "甲\n乙")
    check("去重键忽略大小写与空白", searchHistoryKey("  AbC ") == "abc")
    val hitA = BookHit("1", "斗破苍穹", "天蚕土豆", "简介")
    val hitB = BookHit("2", "武动乾坤", "天蚕土豆", "简介")
    val hitC = BookHit("3", "Douluo Dalu", "Tang Jia San Shao", "简介")
    check("精准：书名命中只留一本",
        searchPreciseHits(listOf(hitA, hitB), "斗破").map { it.name } == listOf("斗破苍穹"))
    check("精准：作者命中两本", searchPreciseHits(listOf(hitA, hitB), "土豆").size == 2)
    check("精准：忽略大小写", searchPreciseKeep(hitC, "douluo") &&
        searchPreciseHits(listOf(hitC), "DOULUO").size == 1)
    check("精准：空关键词全留", searchPreciseHits(listOf(hitA, hitB, hitC), "").size == 3)
    check("精准：都不沾就空", searchPreciseHits(listOf(hitA, hitB), "诡秘").isEmpty())
    check("统计牌带进度", searchSummaryText(12, 9, 15) == "结果 12 · 进度 9/15")
    check("没进度数据只写条数", searchSummaryText(0, 0, 0) == "结果 0")
    check("有源没回话才提醒", searchPartialHint(9, 15) == "部分书源未返回，结果可能不完整")
    check("都回话就不提醒", searchPartialHint(15, 15) == null && searchPartialHint(0, 0) == null)
    check("第二行作者加最新章",
        searchHitLine("天蚕土豆", "第 12 章 出发", "源甲") == "天蚕土豆 · 最新: 第 12 章 出发")
    check("没最新章退回源名", searchHitLine("天蚕土豆", "", "源甲") == "天蚕土豆 · 源甲")
    check("没作者没源名只剩最新章", searchHitLine("", "第 3 章", "") == "最新: 第 3 章")
    check("三者都空得空串", searchHitLine("", "", "") == "")
    check("模板没展开的源名不上屏", searchHitLine("甲", "", "@{site}") == "甲")
    check("源数只有一本不出牌", searchSourceBadge(1) == null && searchSourceBadge(3) == "3")
    // 排序照 legado `sortedWithSearchPriority`：档位 + 档内源数降序。
    val hitD = BookHit("4", "斗破苍穹后传", "天蚕土豆", "简介")
    val hitE = BookHit("5", "苍穹榜", "斗破苍穹", "简介")
    check("档位：书名就是关键词", searchRankTier(hitA, "斗破苍穹") == 0)
    check("档位：作者就是关键词也进第一档", searchRankTier(hitE, "斗破苍穹") == 0)
    check("档位：包含关键词进第二档", searchRankTier(hitA, "斗破") == 1 &&
        searchRankTier(hitC, "douluo") == 1)
    check("档位：不沾关键词垫底", searchRankTier(hitA, "诡秘") == 2)
    check("排序：等值档在前，源数少也压得住包含档",
        searchRanked(listOf(hitA, hitD), "斗破苍穹") { 1 }.map { it.bookId } == listOf("1", "4"))
    check("排序：同档内源数多的在前",
        searchRanked(listOf(hitD, hitA), "斗破") { if (it.bookId == "4") 3 else 1 }
            .map { it.bookId } == listOf("4", "1"))
    check("排序：同档同源数按书名定序，不随到达顺序飘",
        searchRanked(listOf(hitD, hitA), "斗破") { 1 }.map { it.bookId } ==
            searchRanked(listOf(hitA, hitD), "斗破") { 1 }.map { it.bookId })
    check("排序：空关键词按书名定序",
        searchRanked(listOf(hitB, hitA), "") { 1 }.map { it.bookId } == listOf("1", "2"))
    check("删词按去重键比大小写", searchHistoryRemove(listOf("Abc", "乙"), "  aBC ") == listOf("乙"))
    val hisDir = File(root, "search")
    check("历史没文件时为空", SearchHistoryStore.load(hisDir).isEmpty())
    check("落盘再读回一致", run {
        SearchHistoryStore.save(hisDir, listOf("甲", "乙"))
        SearchHistoryStore.load(hisDir) == listOf("甲", "乙") &&
            SearchHistoryStore.file(hisDir)?.name == "history.txt"
    })
    check("加一条自动落盘", SearchHistoryStore.add(hisDir, "丙").first() == "丙" &&
        SearchHistoryStore.load(hisDir).first() == "丙")
    check("重复搜同一词不涨条数", SearchHistoryStore.add(hisDir, "丙").size == 3)
    check("清空后读回为空",
        SearchHistoryStore.clear(hisDir).isEmpty() && SearchHistoryStore.load(hisDir).isEmpty())
    check("单删一条并落盘", run {
        SearchHistoryStore.save(hisDir, listOf("甲", "乙"))
        SearchHistoryStore.remove(hisDir, "甲") == listOf("乙") &&
            SearchHistoryStore.load(hisDir) == listOf("乙")
    })

    // ── 阅读页排版、亮度与章内查找（ReaderStyle） ─────────
    check("亮度三档取值", ReaderStyle.brightValue(0) == 0.35f &&
        ReaderStyle.brightValue(1) == 0.6f && ReaderStyle.brightValue(2) == 1.0f)
    check("亮度越界档按最近一档", ReaderStyle.brightValue(-3) == 0.35f &&
        ReaderStyle.brightValue(9) == 1.0f)
    check("亮度值落回档位", ReaderStyle.brightTier(0.35f) == 0 &&
        ReaderStyle.brightTier(0.6f) == 1 && ReaderStyle.brightTier(1.0f) == 2)
    check("跟随系统亮度落回中档", ReaderStyle.brightTier(-1f) == 1 &&
        ReaderStyle.brightTier(0f) == 1)
    check("档位存盘再读回不变",
        (0..2).all { ReaderStyle.brightTier(ReaderStyle.brightValue(it)) == it })
    check("段距三档比例", ReaderStyle.paraRatio(0) == 0f &&
        ReaderStyle.paraRatio(1) == 0.5f && ReaderStyle.paraRatio(2) == 1f)
    check("段距越界档夹到两端", ReaderStyle.paraRatio(-1) == 0f &&
        ReaderStyle.paraRatio(7) == 1f)
    check("段距像素按字号行距密度换算",
        ReaderStyle.paraExtraPx(2, 16f, 1.75f, 2f) == 56)
    check("无段距不给像素", ReaderStyle.paraExtraPx(0, 16f, 1.75f, 2f) == 0)
    check("密度为零不出负数像素", ReaderStyle.paraExtraPx(2, 16f, 1.75f, 0f) == 0)
    check("段尾只取换行符", ReaderStyle.paraEnds("甲乙\n丙丁\n戊").toList() == listOf(2, 5))
    check("段尾条数受限", ReaderStyle.paraEnds("a\nb\nc\nd", 2).size == 2)
    check("首行缩进每段两个全角空格",
        ReaderStyle.indent("甲\n\n乙") == "　　甲\n\n　　乙")
    check("空正文不缩进", ReaderStyle.indent("").isEmpty())
    check("滚动定位加正文顶边与内边距", ReaderStyle.scrollTarget(120, 4, 300, 900) == 424)
    check("滚动定位夹在内容范围", ReaderStyle.scrollTarget(0, 0, 9999, 700) == 700 &&
        ReaderStyle.scrollTarget(0, 0, -50, 700) == 0)
    check("内容比屏短就滚到顶", ReaderStyle.scrollTarget(0, 0, 30, -20) == 0)
    check("翻一屏保留一行重叠", ReaderStyle.pageFirstLine(0, 10, 100, true) == 9 &&
        ReaderStyle.pageFirstLine(20, 10, 100, false) == 11)
    check("回滚到章首停在第一行", ReaderStyle.pageFirstLine(3, 10, 100, false) == 0)
    check("不足一屏时就到末尾行", ReaderStyle.pageFirstLine(0, 10, 5, true) == 4)
    check("一屏只有一行也要走一行", ReaderStyle.pageFirstLine(0, 1, 100, true) == 1)
    check("没有行就不动", ReaderStyle.pageFirstLine(0, 10, 0, true) == 0)
    check("翻屏时长按距离比例", ReaderStyle.pageDuration(1000, 1000) == ReaderStyle.PAGE_BASE_MS &&
        ReaderStyle.pageDuration(500, 1000) == ReaderStyle.PAGE_BASE_MS / 2)
    check("翻屏基准与表屏相符", ReaderStyle.PAGE_BASE_MS == 240L)
    check("零距离与零视口都给 1 ms", ReaderStyle.pageDuration(0, 1000) == 1L &&
        ReaderStyle.pageDuration(300, 0) == 1L)
    check("下一处首尾循环", ReaderStyle.step(2, 1, 3) == 0)
    check("上一处从末尾回绕", ReaderStyle.step(0, -1, 3) == 2)
    check("还没有位置时前后都对", ReaderStyle.step(-1, 1, 3) == 0 &&
        ReaderStyle.step(-1, -1, 3) == 2)
    check("没有命中就没有下一处", ReaderStyle.step(-1, 1, 0) == -1)
    check("命中计数带单位", ReaderStyle.counterText(0, 7) == "第 1 / 共 7 处" &&
        ReaderStyle.counterText(-1, 0) == "共 0 处")
    check("缩进后命中偏移按屏上正文算", run {
        val shown = ReaderStyle.indent("第一段\n第二段有目标")
        val hit = ReaderFind.inText(shown, "目标").firstOrNull()
        hit != null && shown.substring(hit.start, hit.start + 2) == "目标"
    })
    check("护眼遮罩是暖橙低透明", ReaderStyle.eyeColor() ==
        ((28 shl 24) or (255 shl 16) or (180 shl 8) or 80))
    check("护眼透明度夹到 0", ReaderStyle.eyeColor(-5) == ReaderStyle.eyeColor(0))

    // ── 替换净化：搜索 / 排序 / 置顶置底 / 多选 / 编辑合并（core/ReplaceState.kt）──
    run {
        val rules = listOf(
            ReplaceRule("甲规则", "请记住本站", "", isRegex = true, scope = "text", enabled = true),
            ReplaceRule("乙规则", "求月票", "[投票]", isRegex = false, scope = "both", enabled = false),
            ReplaceRule("丙规则", "本章未完", "", isRegex = true, scope = "title", enabled = true),
        )
        val ka = replaceKey("甲规则", "请记住本站")
        val kb = replaceKey("乙规则", "求月票")
        val kc = replaceKey("丙规则", "本章未完")
        check("替换键用名称加模式", ka == "甲规则\u0000请记住本站" && ka != kb)
        check("搜索按名称命中", replaceMatch(rules, "甲规").size == 1)
        check("搜索按模式命中", replaceMatch(rules, "月票").map { it.name } == listOf("乙规则"))
        check("空搜索等于不过滤", replaceMatch(rules, "  ").size == 3)
        check("搜索忽略大小写", replaceMatch(
            listOf(ReplaceRule("r", "AdBlock", "", false, "text", true)), "adblock").size == 1)
        check("原文顺序听手动表",
            replaceSorted(rules, ReplaceSort.Order, listOf(kc)).map { it.name } ==
                listOf("丙规则", "甲规则", "乙规则"))
        check("名称排序按拼音不按码位",
            replaceSorted(rules, ReplaceSort.Name, emptyList()).map { it.name } ==
                listOf("丙规则", "甲规则", "乙规则"))
        check("启用优先把关着的沉底且保住相对顺序",
            replaceSorted(rules, ReplaceSort.Enabled, emptyList()).map { it.name } ==
                listOf("甲规则", "丙规则", "乙规则"))
        check("手动顺序表为空就原样返回", replaceApplyOrder(rules, emptyList()) == rules)
        check("置顶挪到最前", replaceMoveToEdge(rules, emptyList(), listOf(kc), true) ==
            listOf(kc, ka, kb))
        check("置底挪到最后", replaceMoveToEdge(rules, emptyList(), listOf(kc), false) ==
            listOf(ka, kb, kc))
        check("置顶结果覆盖全部规则",
            replaceMoveToEdge(rules, emptyList(), listOf(kc), true).size == 3)
        check("批量启用只动所选", run {
            val out = replaceSetEnabled(rules, listOf(kb), true)
            out[1].enabled && out[0].enabled && !replaceSetEnabled(rules, listOf(ka), false)[0].enabled
        })
        check("翻启用只翻一条", run {
            val out = replaceFlipEnabled(rules, ka)
            !out[0].enabled && out[2].enabled
        })
        check("删除移掉所选", replaceRemove(rules, listOf(kb)).map { it.name } ==
            listOf("甲规则", "丙规则"))
        check("编辑按旧键替换含改名", run {
            val out = replaceUpsert(rules, ka,
                ReplaceRule("甲改名", "请记住本站", "", true, "text", true))
            out.size == 3 && out[0].name == "甲改名"
        })
        check("新建追加到末尾", replaceUpsert(rules, null,
            ReplaceRule("新规则", "广告", "", false, "text", true)).size == 4)
        check("旧键找不到也追加不覆盖",
            replaceUpsert(rules, "不存在的键",
                ReplaceRule("新规则", "广告", "", false, "text", true)).size == 4)
        check("校验拦住空模式",
            replaceValidate("有名字", "  ") == "查找内容不能空" &&
                replaceValidate(" ", "有模式") == "名称不能空")
        check("校验放过正常规则", replaceValidate("名", "模式") == null)
        check("行的副标题写清正则与否和替换成什么",
            replaceRowSubtitle(rules[1]) == "纯文本 · 正文+标题 · 替换为「[投票]」")
        check("空替换写成一个空字", replaceRowSubtitle(rules[0]).endsWith("替换为空"))
        check("顶栏统计夹住越界", replaceSubtitle(3, 5) == "3 条 · 启用 3 条" &&
            replaceSubtitle(-1, 0) == "0 条 · 启用 0 条")
        check("列表标题搜索态优先", replaceListLabel("月票", 1) == "「月票」1 条" &&
            replaceListLabel("", 3) == "全部规则（3）")
        check("多选选中再点取消", run {
            val s = linkedSetOf<String>()
            replaceToggleSelect(s, ka) && s.size == 1 && !replaceToggleSelect(s, ka)
        })
        check("全选再点变全不选", run {
            val s = linkedSetOf<String>()
            replaceSelectAll(s, listOf(ka, kb)) && s.size == 2 &&
                !replaceSelectAll(s, listOf(ka, kb)) && s.isEmpty()
        })
        check("消失的选中被清掉", run {
            val s = linkedSetOf(ka, kb)
            replacePruneSelection(s, listOf(kb)) && s == setOf(kb)
        })
        check("全没了就是没有选中", run {
            val s = linkedSetOf(ka)
            !replacePruneSelection(s, emptyList())
        })
        check("多选标题与书架屏同形", replaceSelectTitle(3) == "已选 3" &&
            replaceSelectTitle(-1) == "已选 0")
        check("状态往返保住排序与顺序", run {
            val p = ReplacePrefs(ReplaceSort.Name, listOf(ka, kc))
            replaceStateParse(replaceStateJson(p)) == p
        })
        check("状态文件是合法 JSON 片段", replaceStateJson(ReplacePrefs()).startsWith("{\"sort\":"))
        check("坏状态文件退回默认", replaceStateParse("{坏") == ReplacePrefs())
        check("删除文案写明找不回来", replaceDeleteMessage(2).contains("找不回来"))
    }

    // ── 书签与阅读记录（core/MarksState.kt）──────────────
    // 书签页与记录页的搜索、排序、多选、文案：两页共用，所以一起断言。
    run {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val bmA = Bookmark("b1", "A书", 3, "第三章 风起", 120, "他抬头看天", now)
        val bmB = Bookmark("b1", "A书", 9, "", -1, "  第二段\n摘录  ", now - day)
        val bmC = Bookmark("b2", "B书", 0, "第一章", 0, "无关内容", now - 3 * day)
        val all = listOf(bmC, bmB, bmA)

        check("章名空退回第 N 章", markChapterLabel(bmB) == "第 10 章")
        check("章名有就原样", markChapterLabel(bmA) == "第三章 风起")
        check("摘录压成一行", markExcerpt(bmB) == "第二段 摘录")
        check("相对时间认到天", markTimeLabel(now, now) == "今天" &&
            markTimeLabel(now - day, now) == "昨天" &&
            markTimeLabel(now - 3 * day, now) == "3 天前")
        check("老于一周给年月日", markTimeLabel(now - 40 * day, now).length == 10)
        check("没时刻不说今天", markTimeLabel(0L, now) == "时间未知")
        check("第三行是书名加时间", markMeta(bmA, now) == "《A书》· 今天")
        check("书名空只给时间",
            markMeta(Bookmark("x", "", 0, "", 0, "", now), now) == "今天")
        check("书名章名摘录都算命中", markMatch(bmA, "A书") && markMatch(bmA, "风起") &&
            markMatch(bmA, "抬头") && !markMatch(bmA, "月票"))
        check("空搜索全放行", markMatch(bmA, "   "))
        check("排序按书名再章号",
            markSorted(all).map { markChapterLabel(it) } == listOf("第三章 风起", "第 10 章", "第一章"))
        check("筛完仍有顺序", markShown(all, "A书").size == 2 &&
            markShown(all, "A书")[0].index == 3)
        check("分组标题搜索态优先", markGroupLabel("", 3) == "全部书签（3）" &&
            markGroupLabel("月票", 2) == "「月票」2 条")
        check("顶栏副标题给条数与本数", markSubtitle(all, all, "") == "共 3 条 · 2 本")
        check("副标题搜索态给命中数", markSubtitle(all, listOf(bmA), "月票") == "「月票」命中 1 条")
        check("书签键含书与章号", markKey(bmA) != markKey(bmB) && markKey(bmA).endsWith("\u00003"))
        check("多选选中再点取消", run {
            val s = linkedSetOf<String>()
            markSelectionToggle(s, markKey(bmA)) && s.size == 1 &&
                !markSelectionToggle(s, markKey(bmA))
        })
        check("全选再点变全不选", run {
            val s = linkedSetOf<String>()
            val keys = all.map { markKey(it) }
            markSelectionSelectAll(s, keys) && s.size == 3 &&
                !markSelectionSelectAll(s, keys) && s.isEmpty()
        })
        check("消失的选中被清掉", run {
            val s = linkedSetOf(markKey(bmA), markKey(bmC))
            markSelectionPrune(s, listOf(markKey(bmC))) && s == setOf(markKey(bmC))
        })
        check("一条不剩就没有选中", run {
            val s = linkedSetOf(markKey(bmA))
            !markSelectionPrune(s, emptyList())
        })
        check("删书签写明找不回来", markDeleteMessage(2).contains("找不回来") &&
            markDeleteMessage(0).contains("1"))
        check("记录按书名作者命中", recordMatch("甲书", "老王", "老王") &&
            recordMatch("甲书", "老王", "甲") && !recordMatch("甲书", "老王", "乙"))
        check("空搜索放行全部记录", recordMatch("甲书", "", "  "))
        check("记录副标题带百分比", recordSubtitle(11, 344) == "读至 12/344 章 · 3%")
        check("总量为零不报百分比", recordSubtitle(0, 0) == "读至 1 章")
        check("记录副标题写明按最近阅读", recordSubtitleText(3) == "共 3 本 · 按最近阅读排列")
        check("删记录写明书还在", recordDeleteMessage(2).contains("书和正文都还在"))
        check("清空记录写明书还在", recordClearMessage().contains("书和正文都还在"))
        check("字面匹配不分大小写", findMatcher("Abc", false)("xABCy") == listOf(1..3))
        check("字面匹配找全每一处", findMatcher("a", false)("a|a|a").size == 3)
        check("空关键词不命中", findMatcher("  ", false)("任何文字").isEmpty())
        check("正则能命中多段", findMatcher("第\\d+章", true)("第1章与第22章") ==
            listOf(0..2, 4..7))
        check("正则不成立退回字面", findMatcher("([", true)("字面([") == listOf(2..3))
        check("正则可用性判得准", findRegexUsable("第\\d+章") && !findRegexUsable("(["))
        check("零宽正则不卡死", runCatching { findMatcher("^|\\b", true)("abc") }.isSuccess)
        check("高亮切出命中段", run {
            val segs = findHighlight("前言关键词后语", listOf(2..4))
            segs.size == 3 && segs[1].text == "关键词" && segs[1].hit &&
                !segs[0].hit && segs[0].text == "前言"
        })
        check("高亮合并重叠区间", run {
            val segs = findHighlight("abcdef", listOf(1..3, 2..4))
            segs.joinToString("") { it.text } == "abcdef" &&
                segs.count { it.hit } == 1 && segs.first { it.hit }.text == "bcde"
        })
        check("高亮丢掉越界区间", findHighlight("abc", listOf(2..9)).none { it.hit })
        check("没有命中就是整段", findHighlight("abc", emptyList()) == listOf(FindSeg("abc", false)))
        check("空文字没有分段", findHighlight("", listOf(0..1)).isEmpty())
    }

    // ── 书架排序（legado BookshelfRepository.sortBooks 的四个键）────────────
    // ── 排序键与默认方向 ──────────────────────────────
    check("四个排序键，顺序照 legado", ShelfSort.keys == listOf(0, 1, 2, 5))
    check("最近阅读的标签", ShelfSort.label(ShelfSort.RECENT) == "最近阅读")
    check("更新时间的标签", ShelfSort.label(ShelfSort.UPDATED) == "更新时间")
    check("未知键落回最近阅读", ShelfSort.label(99) == "最近阅读")
    check("时间类默认倒序", ShelfSort.defaultDesc(ShelfSort.RECENT) &&
        ShelfSort.defaultDesc(ShelfSort.UPDATED))
    check("名字类默认正序", !ShelfSort.defaultDesc(ShelfSort.NAME) &&
        !ShelfSort.defaultDesc(ShelfSort.AUTHOR))

    // ── 四个键的比较 ──────────────────────────────────
    val a = ShelfSortKey(name = "Alpha", author = "Zhang", updatedAt = 100L, lastRead = 200L)
    val b = ShelfSortKey(name = "Beta", author = "An", updatedAt = 300L, lastRead = 50L)

    check("按最近阅读：a 在后", shelfCompare(a, b, ShelfSort.RECENT) > 0)
    check("按更新时间：a 在前", shelfCompare(a, b, ShelfSort.UPDATED) < 0)
    check("按书名：a 在前", shelfCompare(a, b, ShelfSort.NAME) < 0)
    check("按作者：a 在后", shelfCompare(a, b, ShelfSort.AUTHOR) > 0)
    check("自己跟自己相等", shelfCompare(a, a, ShelfSort.UPDATED) == 0)
    check("缺省键等同最近阅读", shelfCompare(a, b, 99) == shelfCompare(a, b, ShelfSort.RECENT))

    // 倒序就是取反，与 legado isDescending 一个路子。
    val rows = listOf(a, b)
    check("正序", rows.sortedWith { x, y -> shelfCompare(x, y, ShelfSort.UPDATED) }
        .map { it.name } == listOf("Alpha", "Beta"))
    check("倒序", rows.sortedWith { x, y -> -shelfCompare(x, y, ShelfSort.UPDATED) }
        .map { it.name } == listOf("Beta", "Alpha"))

    // ── 更新目录：只追加 ─────────────────────────────
    val raw = """
        {"bookId":"u1","name":"测试书","author":"作者","state":"DONE","total":2,"updatedAt":7,
         "chapters":[
           {"itemId":"c1","title":"第一章","locked":false,"status":"DONE"},
           {"itemId":"c2","title":"第二章","locked":true,"status":"FAILED"}]}
    """.trimIndent()
    val fresh = listOf(
        ChapterRef("c1", "第一章", false),
        ChapterRef("c2", "第二章", true),
        ChapterRef("c3", "第三章", false),
        ChapterRef("c4", "第四章", false),
    )
    val m = mergeShelfCatalog(raw, fresh, now = 1234L)
    check("查出两章新增", m?.added == 2, "${m?.added}")
    check("总章数按合并后算", m?.total == 4, "${m?.total}")

    val json = m!!.json
    check("书名没丢", TinyJson.path(json, "name") == "测试书")
    check("作者没丢", TinyJson.path(json, "author") == "作者")
    check("状态没被改", TinyJson.path(json, "state") == "DONE")
    check("total 字段同步", TinyJson.path(json, "total") == "4")
    check("更新时间换成这次检查的时刻", TinyJson.path(json, "updatedAt") == "1234")

    val chapters = TinyJson.objects(json, "chapters")
    check("章节表四条", chapters.size == 4, "${chapters.size}")
    check("第一条仍是 c1", TinyJson.path(chapters[0], "itemId") == "c1")
    check("已下的状态保住 DONE", TinyJson.path(chapters[0], "status") == "DONE")
    check("失败的状态保住 FAILED", TinyJson.path(chapters[1], "status") == "FAILED")
    check("付费标记保住", TinyJson.path(chapters[1], "locked") == "true")
    check("新章追加在末尾", TinyJson.path(chapters[3], "itemId") == "c4")
    check("新章状态是 PENDING", TinyJson.path(chapters[2], "status") == "PENDING")
    check("新章标题跟着来", TinyJson.path(chapters[2], "title") == "第三章")
    check("新章免费标记", TinyJson.path(chapters[3], "locked") == "false")

    // 目录没变化时不动盘。
    check("没有新章返回 null",
        mergeShelfCatalog(raw, listOf(ChapterRef("c1", "第一章", false))) == null)
    check("空目录返回 null", mergeShelfCatalog(raw, emptyList()) == null)
    check("坏作业不猜", mergeShelfCatalog("{}", fresh) == null)
    check("空原文不猜", mergeShelfCatalog("", fresh) == null)

    // 新目录里自己重复的 itemId 只收一次，否则章号会错位。
    val dup = mergeShelfCatalog(raw, listOf(
        ChapterRef("c3", "第三章", false),
        ChapterRef("c3", "第三章", false),
    ))
    check("重复 itemId 只追加一次", dup?.added == 1, "${dup?.added}")

    // 正文里的引号与换行必须转义，否则整份 job.json 会坏。
    val tricky = mergeShelfCatalog(raw, listOf(ChapterRef("c9", "他说\"走\"\n好", false)))
    check("标题里的引号换行被转义",
        TinyJson.path(TinyJson.objects(tricky!!.json, "chapters").last(), "title")
            == "他说\"走\"\n好")

    root.deleteRecursively()
    println()
    if (failures == 0) println("全部通过") else {
        println("失败 $failures 项"); kotlin.system.exitProcess(1)
    }
}

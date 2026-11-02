import com.watchdl.core.BookHit
import com.watchdl.core.ChapterCacheStatus
import com.watchdl.core.DownloadQueue
import com.watchdl.core.JobStore
import com.watchdl.core.QueueItem
import com.watchdl.core.chapterCacheStatusText
import com.watchdl.core.detailCurrentLine
import com.watchdl.core.detailDownloadTag
import com.watchdl.core.detailIntroText
import com.watchdl.core.detailIntroToggleLabel
import com.watchdl.core.detailIntroToggleVisible
import com.watchdl.core.detailLatestLine
import com.watchdl.core.detailReadState
import com.watchdl.core.detailRemoveMessage
import com.watchdl.core.detailShelfAction
import com.watchdl.core.detailStatParts
import com.watchdl.core.detailTotalText
import com.watchdl.core.ReaderFind
import com.watchdl.core.ReaderStyle
import com.watchdl.core.resolveChapterCacheStatus
import com.watchdl.core.SEARCH_HISTORY_MAX
import com.watchdl.core.SEARCH_HISTORY_SHOWN
import com.watchdl.core.SearchHistoryStore
import com.watchdl.core.searchHitLine
import com.watchdl.core.searchHistoryAdd
import com.watchdl.core.searchHistoryFormat
import com.watchdl.core.searchHistoryKey
import com.watchdl.core.searchHistoryParse
import com.watchdl.core.searchPartialHint
import com.watchdl.core.searchPreciseHits
import com.watchdl.core.searchPreciseKeep
import com.watchdl.core.searchSourceBadge
import com.watchdl.core.searchSummaryText
import com.watchdl.core.shelfBadgeText
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

    // 书架卡与选择模式（照 legado-novel `BookItem.kt` 的 badgeText / leftBottomText、
    // `BookshelfTopBar` 的编辑态）。
    println("[书架卡与选择]")
    check("未读 = 总章 - 在读 - 1", shelfUnreadCount(10, 3) == 6)
    check("没记录过（下标 0）→ 总章减 1", shelfUnreadCount(10, 0) == 9)
    check("读到最后一章 → 0", shelfUnreadCount(10, 9) == 0)
    check("下标超界不出现负数", shelfUnreadCount(10, 50) == 0)
    check("没有章 → 0", shelfUnreadCount(0, 0) == 0)
    check("角标 0 不画", shelfBadgeText(0) == null)
    check("角标 7", shelfBadgeText(7) == "7")
    check("角标超 99 折成 99+", shelfBadgeText(1200) == "99+")
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

    root.deleteRecursively()
    println()
    if (failures == 0) println("全部通过") else {
        println("失败 $failures 项"); kotlin.system.exitProcess(1)
    }
}

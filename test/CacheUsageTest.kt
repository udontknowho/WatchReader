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
import com.watchdl.core.resolveChapterCacheStatus
import com.watchdl.core.shelfBadgeText
import com.watchdl.core.shelfDeleteMessage
import com.watchdl.core.shelfPruneSelection
import com.watchdl.core.shelfSelectAll
import com.watchdl.core.shelfSelectSubtitle
import com.watchdl.core.shelfSelectTitle
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

    root.deleteRecursively()
    println()
    if (failures == 0) println("全部通过") else {
        println("失败 $failures 项"); kotlin.system.exitProcess(1)
    }
}

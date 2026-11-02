import com.watchdl.core.ChapterCacheStatus
import com.watchdl.core.DownloadQueue
import com.watchdl.core.JobStore
import com.watchdl.core.QueueItem
import com.watchdl.core.chapterCacheStatusText
import com.watchdl.core.resolveChapterCacheStatus
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

    root.deleteRecursively()
    println()
    if (failures == 0) println("全部通过") else {
        println("失败 $failures 项"); kotlin.system.exitProcess(1)
    }
}

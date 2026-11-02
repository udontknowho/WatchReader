import com.watchdl.core.JobStore
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

    root.deleteRecursively()
    println()
    if (failures == 0) println("全部通过") else {
        println("失败 $failures 项"); kotlin.system.exitProcess(1)
    }
}

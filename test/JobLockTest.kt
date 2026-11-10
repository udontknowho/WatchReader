import com.watchdl.core.JobState
import com.watchdl.core.JobStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 作业状态落盘的并发安全。
 *
 * 真故障：下载线程 persist 整份重写 job.json 的同时，取消走 writeState 读-改-写，
 * 后写的把前者盖掉 —— 用户点了取消，作业却还是 RUNNING，
 * 界面报「系统中断」，下次只能从头重下。
 */
private var failures = 0

private fun check(desc: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ✓ $desc")
    else { println("  ✗ $desc  $detail"); failures++ }
}

fun main() {
    val root = File(System.getProperty("java.io.tmpdir"), "joblock-" + System.nanoTime())
    root.mkdirs()
    val store = JobStore(root)
    println("[作业锁] 根目录 $root")

    run {
        println("[作业锁] 并发写同一本书不会丢状态")
        val id = "b-concurrent"
        store.writeJob(id, """{"bookId":"$id","name":"书","author":"人","state":"RUNNING","total":3,"chapters":[]}""")
        val start = CountDownLatch(1)
        val rounds = 40
        val bad = AtomicInteger()
        val ts = (0 until 8).map { t ->
            Thread {
                start.await()
                repeat(rounds) { r ->
                    val writer = if (t % 2 == 0) store else JobStore(File(root, "."))
                    if (t % 2 == 0) {
                        // 一路 persist 整份重写
                        store.writeJob(id,
                            """{"bookId":"$id","name":"书","author":"人","state":"RUNNING","total":3,"chapters":[]}""")
                    } else {
                        // 一路读-改-写
                        writer.writeState(id, JobState.CANCELLED)
                        writer.writeState(id, JobState.RUNNING)
                    }
                    // 任何时刻读回来的都必须是完整 JSON，不能是半截
                    val raw = store.readJob(id)
                    if (raw == null || !raw.contains("\"bookId\"") || !raw.trimEnd().endsWith("}")) bad.incrementAndGet()
                }
            }.also { it.start() }
        }
        start.countDown()
        ts.forEach { it.join(20_000) }
        check("8 线程 × $rounds 轮后每次读到的都是完整 JSON", bad.get() == 0, "坏读 ${bad.get()} 次")
        check("文件仍在且可解析", store.readJob(id) != null)
    }

    run {
        println("[作业锁] 不同书互不干扰")
        val ids = listOf("x1", "x2", "x3", "x4")
        ids.forEach {
            store.writeJob(it, """{"bookId":"$it","name":"书","author":"人","state":"RUNNING","total":1,"chapters":[]}""")
        }
        val ts = ids.map { id ->
            Thread {
                repeat(20) {
                    store.writeState(id, JobState.CANCELLED)
                    store.writeState(id, JobState.RUNNING)
                }
            }.also { it.start() }
        }
        ts.forEach { it.join(10_000) }
        val allOk = ids.all { store.readJob(it)?.contains("\"state\":\"RUNNING\"") == true }
        check("四本书状态各自正确", allOk)
    }

    run {
        println("[作业锁] 取消状态不会被后续 persist 盖回 RUNNING")
        val id = "b-cancel"
        store.writeJob(id, """{"bookId":"$id","name":"书","author":"人","state":"RUNNING","total":1,"chapters":[]}""")
        store.writeState(id, JobState.CANCELLED)
        val stale = """{"bookId":"$id","state":"DONE","total":2,"chapters":[]}"""
        JobStore(File(root, ".")).writeJob(id, stale)
        check("旧快照不能覆盖取消状态", store.readJob(id)?.contains("\"state\":\"CANCELLED\"") == true)
        check("取消后仍保留最新进度", store.readJob(id)?.contains("\"total\":2") == true)
        store.writeJob(id, stale) { false }
        check("恢复请求已取消时不覆盖取消状态", store.readJob(id)?.contains("\"state\":\"CANCELLED\"") == true)
        store.writeJob(id, stale.replace("DONE", "RUNNING")) { true }
        check("显式恢复可写入运行状态", store.readJob(id)?.contains("\"state\":\"RUNNING\"") == true)
        store.writeState(id, JobState.CANCELLED)
        check("取消后立刻读回 CANCELLED",
            store.readJob(id)?.contains("\"state\":\"CANCELLED\"") == true,
            "实际 ${store.readJob(id)?.take(80)}")
    }

    run {
        store.writeJob("cancel-before-create", """{"state":"RUNNING"}""", isCancelled = { true }) { true }
        check("首次快照在锁内检查取消", store.readJob("cancel-before-create")?.contains("\"state\":\"CANCELLED\"") == true)
    }

    run {
        val id = "b/collision"
        val alias = "b:collision"
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val ts = (0 until 4).map { lane ->
            Thread {
                try {
                    val writer = JobStore(File(root, "."))
                    repeat(20) { n ->
                        writer.appendChapter(if (lane % 2 == 0) id else alias,
                            """{"itemId":"$lane-$n","content":"${"x".repeat(1024)}"}""")
                    }
                } catch (e: Throwable) { errors.add(e) }
            }.also { it.start() }
        }
        ts.forEach { it.join(10_000) }
        check("旧碰撞键跨实例追加各自不丢章",
            errors.isEmpty() && ts.none { it.isAlive } &&
                store.readChapters(id).size == 40 && store.readChapters(alias).size == 40)
    }

    root.deleteRecursively()
    println()
    if (failures == 0) println("全部通过") else {
        println("失败 $failures 项"); kotlin.system.exitProcess(1)
    }
}

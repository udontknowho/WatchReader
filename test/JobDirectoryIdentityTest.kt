import com.watchdl.core.Downloader
import com.watchdl.core.JobStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue

/** 目录键必须区分完整身份，旧目录只能由快照中的真实身份认领。 */
fun main() {
    val root = File(System.getProperty("java.io.tmpdir"), "job-identity-${System.nanoTime()}").apply { mkdirs() }
    fun snapshot(id: String) = """{"bookId":${Downloader.quote(id)},"name":"书","state":"DONE","chapters":[{"itemId":"c","status":"DONE"}]}"""
    fun chapter(text: String) = """{"itemId":"c","content":${Downloader.quote(text)}}"""
    fun expect(label: String, result: Boolean) { check(result) { label }; println("  ✓ $label") }
    fun legacy(id: String, dirName: String): File = File(root, dirName).apply {
        mkdirs()
        File(this, "job.json").writeText(snapshot(id))
        File(this, "chapters.jsonl").writeText(chapter("旧正文") + "\n")
    }
    try {
        val store = JobStore(root)
        val first = "a:b"
        val second = "a/b"
        expect("替换碰撞分离", JobStore.safeDirName(first) != JobStore.safeDirName(second))
        val longA = "x".repeat(100) + "a"
        val longB = "x".repeat(100) + "b"
        expect("长前缀碰撞分离", JobStore.safeDirName(longA) != JobStore.safeDirName(longB))
        expect("数字旧路径保留", JobStore.safeDirName("7490785501058712088") == "7490785501058712088")
        expect("摘要保留完整256位", JobStore.safeDirName(first).substringAfterLast('~').length == 64)
        expect("摘要键不能被标识冒充", JobStore.safeDirName(JobStore.safeDirName(first)) != JobStore.safeDirName(first))
        expect("暂存命名空间独立", !JobStore.isTransientDirName(JobStore.safeDirName("x~building")))
        for (id in listOf(first, second, longA, longB)) {
            store.writeJob(id, snapshot(id))
            store.appendChapter(id, chapter(id))
        }
        expect("四本快照正文各自独立", listOf(first, second, longA, longB).all {
            store.readJob(it)?.contains(Downloader.quote(it)) == true && store.readChapter(it, "c") == chapter(it)
        })

        val old = legacy("legacy:a", "legacy_a")
        expect("旧目录迁移后快照可读", store.readJob("legacy:a") == snapshot("legacy:a"))
        expect("旧正文完整保留", store.readChapter("legacy:a", "c") == chapter("旧正文"))
        expect("目录整体迁移", !old.exists() && File(root, JobStore.safeDirName("legacy:a")).isDirectory)
        legacy("other:a", "other_a")
        expect("碰撞身份不读取旧快照", store.readJob("other/a") == null)
        expect("碰撞身份不读取旧正文", store.readChapters("other/a").isEmpty())
        store.clearChapters("other/a")
        store.deleteChapter("other/a", "c")
        store.markDeleted("other/a")
        expect("碰撞身份不删另一书", store.readChapter("other:a", "c") == chapter("旧正文"))

        val poisoned = File(root, JobStore.safeDirName("poison:a")).apply { mkdirs() }
        File(poisoned, "job.json").writeText(snapshot("innocent"))
        File(poisoned, "chapters.jsonl").writeText(chapter("不可删除") + "\n")
        expect("新路径身份不符也不读取", store.readJob("poison:a") == null && store.readChapter("poison:a", "c") == null)
        store.clearChapters("poison:a")
        expect("新路径身份不符不删章", !store.deleteChapter("poison:a", "c"))
        expect("新路径身份不符不删目录", !store.markDeleted("poison:a") && File(poisoned, "chapters.jsonl").isFile)

        val fallback = legacy("fallback:a", "fallback_a")
        File(root, JobStore.safeDirName("fallback:a")).writeText("目标被占用")
        expect("迁移受阻安全回退", store.readJob("fallback:a") == snapshot("fallback:a"))
        store.appendChapter("fallback:a", """{"itemId":"d","content":"新增"}""")
        expect("回退路径可追加", fallback.isDirectory && store.readChapters("fallback:a").size == 2)

        legacy("concurrent:a", "concurrent_a")
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until 4).map { lane -> Thread {
            try {
                start.await()
                val peer = JobStore(File(root, "."))
                repeat(20) { n ->
                    val id = if (lane % 2 == 0) "concurrent:a" else "concurrent/a"
                    peer.appendChapter(id, """{"itemId":"$lane-$n","content":"正文"}""")
                }
            } catch (e: Throwable) { errors.add(e) }
        }.also { it.start() } }
        start.countDown()
        threads.forEach { it.join(15_000) }
        expect("迁移与跨实例追加共用稳定锁", errors.isEmpty() && threads.none { it.isAlive } &&
            store.readChapters("concurrent:a").size == 41 && store.readChapters("concurrent/a").size == 40)
        expect("列表返回真实身份", store.listBooks().containsAll(listOf(first, second, "legacy:a", "fallback:a")))
        expect("缓存列表不重复身份", store.cacheUsages().let { usages -> usages.size == usages.map { it.bookId }.toSet().size })
        println("目录身份测试通过")
    } finally {
        root.deleteRecursively()
    }
}

import com.watchdl.core.*
import java.nio.file.Files

/** 纯 JVM 回归；不联网，不依赖 Android。 */
fun main() {
    val dir = Files.createTempDirectory("reading-books-").toFile()
    try {
        val file = dir.resolve("reading-books.json")
        val store = ReadingBookStore(file)
        val info = BookInfo("https://example.test/books/1", "书\"名\n", "作者",
            listOf(ChapterRef("c1", "第一章"), ChapterRef("c2", "第二章", true)),
            truncated = true, intro = "简介\n第二行")
        check(store.find(info.bookId) == null)
        store.save(info, "测试源")
        val loaded = ReadingBookStore(file).find(info.bookId)!!
        check(loaded.info == info && loaded.sourceName == "测试源")
        check(!dir.resolve("shelf.json").exists() && !dir.resolve("books").exists())
        val firstBytes = file.readBytes()
        store.save(info, "测试源")
        check(file.readBytes().contentEquals(firstBytes))
        // 无变化保存不写盘；跨实例直接复用成功保存后的完整快照。
        check(file.setLastModified(1234000L))
        val stableTime = file.lastModified()
        repeat(5) { ReadingBookStore(file).save(info, "测试源") }
        check(file.lastModified() == stableTime)
        check(ReadingBookStore(file).find(info) == loaded)
        store.save(info.copy(chapters = emptyList(), intro = ""))
        check(store.find(info.bookId)?.info == info)
        check(store.find(info.bookId)?.sourceName == "测试源")
        // 同名不同来源分别保存，不能覆盖原书的历史目录。
        val other = info.copy(bookId = "https://second.test/books/1")
        store.save(other, "另一个源")
        check(ReadingBookStore(file).books().size == 2)
        check(store.find(info.bookId)?.sourceName == "测试源")
        // 无下载、无收藏，仍能恢复在线目录。
        check(resolveReadingBook(info.bookId, null, null, loaded) == info)
        val job = info.copy(name = "作业书名")
        check(resolveReadingBook(info.bookId, job, loaded, loaded) == null) // 裸地址不能猜身份。
        check(resolveReadingBook(info, job, loaded, loaded) == info) // 同地址的其他作业不能优先。
        check(resolveReadingBook(info, job.copy(chapters = emptyList()), loaded, null) == info)
        check(resolveReadingBook(info.bookId, null, ShelfBook(other), loaded) == info)
        check(resolveReadingBook("unknown", job, loaded, loaded) == null)
        // 旧记录格式不需伪造下载任务，仍可关联快照。
        ReadingHistory.record(dir, info.bookId, 1, 1234L)
        val event = ReadingHistory.list(dir).single()
        check(event.bookId == info.bookId && event.index == 1 && event.at == 1234L)
        check(resolveReadingBook(event.bookId, null, null, store.find(event.bookId)) == info)
        ReadingHistory.remove(dir, setOf(event.id))
        check(ReadingHistory.list(dir).isEmpty())
        check(store.find(info.bookId)?.info == info) // 删除事件不删目录快照。
        // 同地址两书先后阅读，重建存储后各自目录、来源、历史和进度键仍隔离。
        val sharedA = info.copy(bookId = "https://shared.test/book", name = "甲书", author = "甲作者")
        val sharedB = sharedA.copy(name = "乙书", author = "乙作者",
            chapters = listOf(ChapterRef("b1", "乙第一章")))
        store.save(sharedA, "甲源")
        ReadingHistory.record(dir, sharedA, 1, 2000L)
        store.save(sharedB, "乙源")
        ReadingHistory.record(dir, sharedB, 0, 2100L)
        val reopened = ReadingBookStore(file)
        check(reopened.find(sharedA.bookId) == null)
        check(reopened.find(sharedA)?.info == sharedA && reopened.find(sharedA)?.sourceName == "甲源")
        check(reopened.find(sharedB)?.info == sharedB && reopened.find(sharedB)?.sourceName == "乙源")
        check(reopened.find(readingId(sharedA))?.info == sharedA)
        check(readingId(sharedA) != readingId(sharedB) && progressKey(sharedA) != progressKey(sharedB))
        check(readingId(sharedA) == readingId(sharedA.copy(name = "  甲书  ", chapters = emptyList())))
        val positions = java.util.Properties().apply {
            setProperty("pos_" + progressKey(sharedA), "1")
            setProperty("off_" + progressKey(sharedA), "123")
            setProperty("pos_" + progressKey(sharedB), "0")
            setProperty("off_" + progressKey(sharedB), "456")
        }
        val progressFile = dir.resolve("positions.properties")
        progressFile.outputStream().use { positions.store(it, null) }
        val restoredPositions = java.util.Properties().apply { progressFile.inputStream().use { load(it) } }
        check(restoredPositions.getProperty("pos_" + progressKey(sharedA)) == "1")
        check(restoredPositions.getProperty("off_" + progressKey(sharedA)) == "123")
        check(restoredPositions.getProperty("pos_" + progressKey(sharedB)) == "0")
        check(restoredPositions.getProperty("off_" + progressKey(sharedB)) == "456")
        val choices = reopened.books().map { it.info }
        val bothEvents = ReadingHistory.list(dir)
        check(bothEvents.size == 2)
        check(bothEvents.map { resolveReadingIdentity(it.bookId, it.readingId, choices) } == listOf(sharedA, sharedB))
        check(resolveReadingBook(sharedA, sharedB, null, reopened.find(sharedA)) == sharedA)
        check(resolveReadingIdentity(sharedA.bookId, null, choices) == null) // 旧4列事件歧义。
        check(resolveReadingIdentity(sharedA.bookId, "missing", choices) == null)
        check(!canMigrateReadingProgress(sharedA, choices))
        check(canMigrateReadingProgress(sharedA, listOf(sharedA, sharedA.copy(chapters = emptyList()))))
        check(resolveReadingIdentity(sharedA.bookId, null, listOf(sharedA)) == sharedA)
        // 删甲的事件不会删乙的历史。
        ReadingHistory.remove(dir, setOf(bothEvents.first().id))
        check(ReadingHistory.list(dir).single().readingId == readingId(sharedB))
        ReadingHistory.removeBooks(dir, setOf(sharedA.bookId))
        // 同文件多个实例并发保存不丢记录，输入目录被调用方修改不污染快照。
        val concurrentFile = dir.resolve("concurrent-reading.json")
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until 8).map { n -> Thread {
            runCatching {
                val entry = info.copy(bookId = "parallel-$n")
                ReadingBookStore(concurrentFile).save(entry, "源$n")
                check(ReadingBookStore(concurrentFile).find(entry)?.info == entry)
            }.exceptionOrNull()?.let { failures.add(it) }
        }.apply { start() } }
        workers.forEach { it.join() }
        check(failures.isEmpty()) { failures.toString() }
        check(ReadingBookStore(concurrentFile).books().size == 8)
        val mutableChapters = info.chapters.toMutableList()
        val isolated = info.copy(bookId = "isolated", chapters = mutableChapters)
        ReadingBookStore(concurrentFile).save(isolated)
        mutableChapters.clear()
        check(ReadingBookStore(concurrentFile).find(isolated)?.info?.chapters == info.chapters)
        // v1 缺失来源、简介、锁定标志时仍能读取。
        file.writeText("""{"books":[{"bookId":"legacy","name":"旧书","chapters":[{"itemId":"old-c1","title":"旧章"}]}]}""")
        val legacy = ReadingBookStore(file).find("legacy")!!
        check(legacy.sourceName.isEmpty() && legacy.info.chapters.single() == ChapterRef("old-c1", "旧章"))
        check(legacy.info.intro.isEmpty() && !legacy.info.truncated)
        check(!dir.resolve("reading-books.json.tmp").exists())
        // 损坏文件读时不崩溃，写时拒绝覆盖。
        file.writeText("{broken")
        check(store.books().isEmpty())
        check(runCatching { store.save(info, "源") }.isFailure)
        check(file.readText() == "{broken")
        // 长 URL 的旧键碰撞，完整散列必须区分。
        val prefix = "https://example.test/" + "a".repeat(70)
        val a = prefix + "/a"
        val b = prefix + "/b"
        check(a.take(64) == b.take(64) && progressKey(a) != progressKey(b))
        check(progressKey(a) == progressKey(a) && progressKey(a).length == 67)
        check(canMigrateLegacyProgress(a, listOf(a)))
        check(!canMigrateLegacyProgress(a, listOf(a, b)))
        check(canMigrateLegacyProgress("old", listOf("old", "other")))
        println("ReadingBookStore：在线快照、旧记录恢复、来源隔离、损坏保护和进度键碰撞检查通过")
    } finally { dir.deleteRecursively() }
}

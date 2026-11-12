import com.watchdl.core.*
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

/** 定位索引回归，不联网，不依赖界面。 */
fun main() {
    val root = Files.createTempDirectory("reader-index-").toFile()
    try {
        val store = JobStore(root.resolve("jobs"))
        val other = JobStore(root.resolve("jobs/."))
        val id = "index-book"
        fun line(item: String, text: String) = "{\"itemId\":\"$item\",\"content\":\"$text\"}"
        val lines = (0 until 300).map { line("c$it", "正文$it") }
        store.writeChapters(id, lines)
        check(store.readChapter(id, "c299") == lines.last())
        check(other.readChapter(id, "c0") == lines.first())
        check(store.readChapter(id, "missing") == null)
        other.appendChapter(id, line("new", "追加"))
        check(store.readChapter(id, "new") == line("new", "追加"))
        other.writeChapters(id, listOf(line("c299", "等长")))
        check(store.readChapter(id, "c299") == line("c299", "等长"))
        check(store.readChapter(id, "c0") == null)
        other.openChapters(id).also { it.add(line("sink", "流式")); it.commit() }
        check(store.readChapter(id, "sink") == line("sink", "流式"))
        check(other.deleteChapter(id, "sink"))
        check(store.readChapter(id, "sink") == null)
        store.writeChapters(id, lines)
        check(store.readChapter(id, "c299") != null)
        other.clearChapters(id)
        check(store.readChapter(id, "c299") == null)
        store.writeChapters(id, lines)
        check(store.readChapter(id, "c299") != null)
        check(other.markDeleted(id))
        check(store.readChapter(id, "c299") == null)
        store.clearDeleted(id)
        store.writeChapters(id, listOf(line("fresh", "新正文")))
        check(store.readChapter(id, "fresh") == line("fresh", "新正文"))
        // 写入可以保留相同时间戳；显式失效不能仅依赖文件元数据。
        val modified = store.chaptersFile(id).lastModified()
        other.writeChapters(id, listOf(line("fresh", "旧正文")))
        store.chaptersFile(id).setLastModified(modified)
        check(store.readChapter(id, "fresh") == line("fresh", "旧正文"))
        other.openStaging(id).also { it.add(line("staged", "重导正文")); it.commit() }
        other.commitStaging(id, "{\"bookId\":\"$id\",\"state\":\"DONE\"}")
        check(store.readChapter(id, "fresh") == null)
        check(store.readChapter(id, "staged") == line("staged", "重导正文"))
        val file = root.resolve("reading.json")
        val a = ReadingBookStore(file)
        val b = ReadingBookStore(root.resolve("./reading.json"))
        val info = BookInfo("address", "书", "作者", listOf(ChapterRef("a", "章")))
        a.save(info)
        check(a.find(info)?.info == info)
        b.save(info.copy(chapters = listOf(ChapterRef("b", "新章"))))
        check(a.find(info)?.info?.chapters?.single()?.itemId == "b")
        check(file.delete())
        check(a.find(info) == null)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until 4).map { n -> Thread {
            try {
                repeat(10) { round ->
                    val writer = if (n % 2 == 0) a else b
                    val book = info.copy(bookId = "book-$n", intro = "$round")
                    writer.save(book)
                    check(writer.find(book)?.info == book)
                    other.appendChapter(id, line("$n-$round", "正文"))
                    check(store.readChapter(id, "$n-$round") != null)
                }
            } catch (e: Throwable) { errors.add(e) }
        }.also { it.start() } }
        threads.forEach { it.join(15000) }
        check(threads.none { it.isAlive } && errors.isEmpty()) { errors.toString() }
        check(a.books().size == 4)
        println("ReaderStoreIndex：字节定位、UTF-8、跨实例写入删除失效及并发检查通过")
    } finally { root.deleteRecursively() }
}

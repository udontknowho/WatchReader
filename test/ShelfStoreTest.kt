import com.watchdl.core.*
import java.nio.file.Files

/** 纯 JVM 收藏回归；交给主构建串行执行，不访问网络。 */
fun main() {
    val dir = Files.createTempDirectory("shelf-store-").toFile()
    try {
        val file = dir.resolve("shelf.json")
        val store = ShelfStore(file)
        val info = BookInfo("https://example.test/book/1", "书\"名\n", "作者", listOf(
            ChapterRef("chapter/1", "第一章"), ChapterRef("chapter/2", "第二章", true)), intro = "简介\n正文")
        store.migrateOnce { listOf(ShelfBook(info, "测试源", 42)) }
        check(store.books().size == 1)
        check(ShelfStore(file).find(info.bookId)?.info == info)
        check(store.find(info.bookId)?.addedAt == 42L)
        store.add(info.copy(name = "更新书名", chapters = emptyList()), "")
        check(store.books().size == 1)
        check(store.find(info.bookId)?.info?.chapters == info.chapters)
        check(store.find(info.bookId)?.sourceName == "测试源")
        store.remove(info.bookId)
        check(store.books().isEmpty())
        store.migrateOnce { error("旧作业不能重新导入") }
        check(store.books().isEmpty())
        store.add(info)
        check(store.contains(info.bookId))
        check(!dir.resolve("books").exists())
        check(!dir.resolve("shelf.json.tmp").exists())
        file.writeText("{invalid")
        check(runCatching { store.add(info) }.isFailure)
        check(file.readText() == "{invalid")
        println("ShelfStore：迁移、去重、转义、重启恢复、独立收藏与损坏保护通过")
    } finally { dir.deleteRecursively() }
}

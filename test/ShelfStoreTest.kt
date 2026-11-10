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
        // v1 存档没有 shelfId/candidates，读取后补当前来源；不修改原字节。
        file.writeText("""{"version":1,"migrated":true,"books":[{"bookId":"old","name":"同书","author":"作者","sourceName":"甲","addedAt":7,"chapters":[{"itemId":"c1","title":"第一章"}]}]}""")
        val legacyBytes = file.readText()
        val old = store.find("old")!!
        check(old.shelfId == "old" && old.candidates.single().sourceName == "甲")
        check(file.readText() == legacyBytes)
        val second = BookHit("new", "同书", "作者", "摘要", "乙")
        store.add(old.info, "甲", listOf(second, second, second.copy(author = "其他作者")))
        check(store.books().single().candidates.size == 2)
        check(ShelfStore(file).find("new")?.shelfId == "old")
        store.add(BookInfo("new", "同书", "作者", emptyList()), "乙")
        check(store.books().single().info.bookId == "old") // 收藏另一个命中不抢当前源。
        val next = BookInfo("new", "同书", "作者", listOf(ChapterRef("n1", "第一章")))
        check(runCatching { store.replaceSource("old", next.copy(author = "别人"), "乙") }.isFailure)
        check(runCatching { store.replaceSource("old", next.copy(chapters = emptyList()), "乙") }.isFailure)
        store.replaceSource("old", next, "乙")
        val swapped = ShelfStore(file).books().single()
        check(swapped.shelfId == "old" && swapped.info.bookId == "new" && swapped.addedAt == 7L)
        check(swapped.candidates.map { it.bookId }.toSet() == setOf("old", "new"))
        store.remove("new")
        store.migrateOnce { error("移除后不得重新导入") }
        check(store.books().isEmpty())
        check(sameBook(" Ａ书 ", "作者", "A书", "作者"))
        check(!sameBook("同书", "作者", "同书外传", "作者"))
        check(!sameBook("同书", "作者", "同书", ""))
        check(!sameBook("同书", "", "同书", "作者"))
        val progressOld = next.copy(chapters = listOf(ChapterRef("a", "序"), ChapterRef("b", "重逢")))
        val progressNew = next.copy(chapters = listOf(ChapterRef("x", "重逢"), ChapterRef("y", "新章")))
        check(swappedChapterIndex(progressOld, progressNew, 1) == (0 to true))
        check(swappedChapterIndex(progressOld, progressNew.copy(chapters = listOf(ChapterRef("x", "新章"))), 1) == (0 to false))
        file.writeText("{invalid")
        check(runCatching { store.add(info) }.isFailure)
        check(file.readText() == "{invalid")
        check(store.books().isEmpty())
        check(store.readError() != null)
        check(runCatching { store.migrateOnce { emptyList() } }.isFailure)
        check(file.readText() == "{invalid")
        file.writeText("{}")
        check(runCatching { store.add(info) }.isFailure)
        check(file.readText() == "{}")
        println("ShelfStore：迁移、去重、转义、重启恢复、独立收藏与损坏保护通过")
    } finally { dir.deleteRecursively() }
}

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
        // 相同收藏更新不重写整个目录，跨实例读取保持一致。
        check(file.setLastModified(1234000L))
        val stableTime = file.lastModified()
        repeat(5) { ShelfStore(file).add(info, "测试源") }
        check(file.lastModified() == stableTime)
        check(ShelfStore(file).find(info)?.addedAt == 42L)
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
        // 同作者不同书共用地址，三本均须落盘，并且按逻辑身份而非地址查询。
        val collisionFile = dir.resolve("collisions.json")
        val collisionStore = ShelfStore(collisionFile)
        val first = BookInfo("shared", "第一本", "同一作者", listOf(ChapterRef("a1", "甲章")))
        val secondBook = first.copy(name = "第二本", chapters = listOf(ChapterRef("b1", "乙章")))
        val third = first.copy(bookId = "candidate", name = "第三本", chapters = listOf(ChapterRef("c1", "丙章")))
        val candidate = BookHit("candidate", first.name, first.author, "", "备用")
        collisionStore.add(first, "甲源", listOf(candidate))
        collisionStore.add(secondBook, "乙源")
        collisionStore.add(third, "丙源") // 地址与第一本候选地址相同，不能合并。
        val persisted = ShelfStore(collisionFile)
        check(persisted.books().size == 3)
        check(persisted.books().map { it.shelfId }.toSet().size == 3)
        listOf(first, secondBook, third).forEach { book ->
            check(persisted.find(book)?.info == book)
            check(persisted.contains(book))
        }
        val firstId = persisted.find(first)!!.shelfId
        val secondId = persisted.find(secondBook)!!.shelfId
        val thirdId = persisted.find(third)!!.shelfId
        check(persisted.find(third.copy(bookId = "shared"))?.shelfId == thirdId)
        val absent = first.copy(name = "未收藏的第四本")
        check(persisted.find(absent) == null && !persisted.contains(absent))
        persisted.remove(absent) // 同地址的未知书不能删除已有书。
        check(persisted.books().size == 3)
        check(persisted.find("candidate") == null) // 歧义候选地址不是稳定标识。
        persisted.remove("candidate")
        check(persisted.books().size == 3)
        check(persisted.find(firstId)?.info == first)
        check(persisted.find(secondId)?.info == secondBook)
        check(persisted.find(thirdId)?.info == third)
        // 逻辑书名作者命中优先：传入其他书的稳定标识，也只补本书候选。
        persisted.add(third.copy(bookId = firstId, chapters = emptyList()), "第四源")
        check(persisted.books().size == 3 && persisted.find(first)?.info == first)
        check(persisted.find(third)?.shelfId == thirdId && persisted.find(third)?.info == third)
        // 换源到其他书的现用地址不能删掉对方，身份和加入时间不变。
        val addedAt = persisted.find(third)!!.addedAt
        persisted.replaceSource(thirdId, third.copy(bookId = "shared"), "第四源")
        val afterSwap = ShelfStore(collisionFile)
        check(afterSwap.books().size == 3)
        check(afterSwap.find(third)?.shelfId == thirdId && afterSwap.find(third)?.addedAt == addedAt)
        check(afterSwap.find(first)?.info == first && afterSwap.find(secondBook)?.info == secondBook)
        // 使用旧 BookInfo 删除仍按逻辑身份，另外两本保持原样。
        afterSwap.remove(third)
        check(ShelfStore(collisionFile).books().map { it.info }.toSet() == setOf(first, secondBook))
        afterSwap.remove(secondBook)
        check(ShelfStore(collisionFile).books().single().shelfId == firstId)
        // 裸稳定标识只移除一个目标，不会连带删除共用地址的记录。
        afterSwap.add(secondBook, "乙源")
        afterSwap.remove(firstId)
        check(ShelfStore(collisionFile).books().single().info == secondBook)
        // v1 缺失 shelfId 的重复地址不得在读取时 distinctBy 丢掉。
        val legacyCollisionFile = dir.resolve("legacy-collisions.json")
        legacyCollisionFile.writeText("""{"version":1,"migrated":true,"books":[{"bookId":"same","name":"甲书","author":"作者"},{"bookId":"same","name":"乙书","author":"作者"},{"bookId":"same","name":"丙书","author":"作者"}]}""")
        val duplicateBytes = legacyCollisionFile.readText()
        val recovered = ShelfStore(legacyCollisionFile)
        val recoveredIds = recovered.books().map { it.shelfId }
        check(recovered.books().size == 3 && recoveredIds.toSet().size == 3)
        check(ShelfStore(legacyCollisionFile).books().map { it.shelfId } == recoveredIds)
        check(legacyCollisionFile.readText() == duplicateBytes) // 只读修复不写盘。
        recovered.remove(recovered.books()[1].info)
        check(ShelfStore(legacyCollisionFile).books().map { it.info.name }.toSet() == setOf("甲书", "丙书"))
        // 从旧作业迁移也不能按重复默认 shelfId 覆盖其他书。
        val migrationFile = dir.resolve("migration-collisions.json")
        val migratedStore = ShelfStore(migrationFile)
        migratedStore.migrateOnce { listOf(first, secondBook, third).map { ShelfBook(it) } }
        check(ShelfStore(migrationFile).books().map { it.info }.toSet() == setOf(first, secondBook, third))
        check(migratedStore.books().map { it.shelfId }.toSet().size == 3)
        // 同文件跨实例串行化读改写，避免并发收藏丢失。
        val concurrentFile = dir.resolve("concurrent-shelf.json")
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = (0 until 8).map { n -> Thread {
            runCatching {
                val entry = info.copy(bookId = "parallel-$n", name = "并发书$n")
                ShelfStore(concurrentFile).add(entry, "源$n")
                check(ShelfStore(concurrentFile).find(entry)?.info == entry)
            }.exceptionOrNull()?.let { failures.add(it) }
        }.apply { start() } }
        workers.forEach { it.join() }
        check(failures.isEmpty()) { failures.toString() }
        check(ShelfStore(concurrentFile).books().size == 8)
        val mutableChapters = info.chapters.toMutableList()
        val isolated = info.copy(bookId = "isolated", chapters = mutableChapters)
        ShelfStore(concurrentFile).add(isolated)
        mutableChapters.clear()
        check(ShelfStore(concurrentFile).find(isolated)?.info?.chapters == info.chapters)
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

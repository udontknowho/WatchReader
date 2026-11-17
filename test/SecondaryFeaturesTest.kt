import com.watchdl.core.*
import java.io.File

/** 单独编译运行；本文件不包含 Android 依赖。 */
fun main() {
    val dir = File(System.getProperty("java.io.tmpdir"), "secondary-${System.nanoTime()}")
    dir.mkdirs()
    try {
        ReadingHistory.record(dir, "book\t中文", 2, 1000)
        ReadingHistory.record(dir, "book\t中文", 2, 2000)
        check(ReadingHistory.list(dir).size == 1)
        // 按书聚合：同一本换章只更新那一条，不再新增行。
        ReadingHistory.record(dir, "book\t中文", 3, 3000)
        check(ReadingHistory.list(dir).map { it.index } == listOf(3))
        check(ReadingHistory.list(dir).first().bookId == "book\t中文")
        check(ReadingHistory.list(dir).all { it.readingId == null }) // 旧四列仍可读。
        val one = BookInfo("shared", "甲书", "作者", emptyList())
        val two = one.copy(name = "乙书")
        ReadingHistory.record(dir, one, 0, 2100)
        ReadingHistory.record(dir, two, 0, 2200)
        ReadingHistory.record(dir, two, 0, 2300)
        val identities = ReadingHistory.list(dir).filter { it.bookId == "shared" }
        check(identities.size == 2) // 同章同地址不同书不合并；同身份重复保存合并。
        check(identities.map { it.readingId } == listOf(readingId(one), readingId(two)))
        ReadingHistory.removeBooks(dir, setOf("shared"))
        ReadingHistory.remove(dir, setOf(1000L))
        check(ReadingHistory.list(dir).single().index == 3)
        repeat(510) { ReadingHistory.record(dir, "book-$it", it, 4000L + it) }
        check(ReadingHistory.list(dir).size == 500)
        // 上限裁剪按时间：最后 500 条里已经不含那条 index=3 的书。
        ReadingHistory.removeBooks(dir, setOf("book\t中文"))
        check(ReadingHistory.list(dir).none { it.bookId == "book\t中文" })

        val normal = "11-09 12:01:02.123 [main] UI 页面打开"
        val error = "11-09 12:01:02.123 [worker 1] WatchDL 目录失败"
        check(LogFilter("UI").matches(normal))
        check(!LogFilter("UI").matches(error))
        check(LogFilter(errorsOnly = true).matches(error))
        check(!LogFilter(errorsOnly = true).matches(normal))
        check(!LogFilter("UI").matches("无结构 UI 失败"))

        ReplaceRuleStore.file = File(dir, "replace.json")
        val global = ReplaceRule("全局", "甲", "乙", false, "text", true)
        val local = ReplaceRule("本书", "乙", "丙", false, "text", true, "a")
        ReplaceRuleStore.save(listOf(global, local))
        check(ReplaceRuleStore.apply("甲", false, "a") == "丙")
        check(ReplaceRuleStore.apply("甲", false, "b") == "乙")
        check(ReplaceRuleStore.apply("甲", true, "a") == "甲")
        check(replaceKeyOf(global) == replaceKey(global.name, global.pattern))
        check(replaceSetEnabled(listOf(local), listOf(replaceKeyOf(local)), false).single().bookId == "a")
        println("SecondaryFeaturesTest: passed")
    } finally {
        ReplaceRuleStore.file = null
        dir.deleteRecursively()
    }
}

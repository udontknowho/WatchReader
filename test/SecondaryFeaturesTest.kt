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
        ReadingHistory.record(dir, "book\t中文", 3, 3000)
        check(ReadingHistory.list(dir).map { it.index } == listOf(2, 3))
        check(ReadingHistory.list(dir).first().bookId == "book\t中文")
        ReadingHistory.remove(dir, setOf(1000L))
        check(ReadingHistory.list(dir).single().index == 3)
        repeat(510) { ReadingHistory.record(dir, "book", it, 4000L + it) }
        check(ReadingHistory.list(dir).size == 500)
        ReadingHistory.removeBooks(dir, setOf("book"))
        check(ReadingHistory.list(dir).isEmpty())

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

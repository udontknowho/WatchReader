import com.watchdl.core.ReplaceRule
import com.watchdl.core.ReplaceRuleStore
import java.io.File

/** 独立入口；本轮只提供用例，未执行。 */
fun main() {
    val dir = kotlin.io.path.createTempDirectory("replace-b1-").toFile()
    try {
        ReplaceRuleStore.file = File(dir, "replace.json")
        val user = ReplaceRule("保留停用", "甲", "乙", false, "text", false)
        ReplaceRuleStore.save(listOf(user))
        val incoming = """[
          {"name":"保留停用","pattern":"甲","replacement":"覆盖","isEnabled":true},
          {"name":"上游停用","pattern":"丙","replacement":"丁","isEnabled":false,"scopeTitle":true,"scopeContent":false},
          {"name":"两处","pattern":"戊","replacement":"己","isEnabled":true,"scopeTitle":true,"scopeContent":true},
          {"name":"脚本","pattern":"庚","replacement":"@js:result","isEnabled":true},
          {"name":"坏正则","pattern":"[","isRegex":true}
        ]"""
        check(ReplaceRuleStore.importJson(incoming) == 2)
        check(ReplaceRuleStore.list().first().replacement == "乙")
        check(!ReplaceRuleStore.list().first().enabled)
        check(ReplaceRuleStore.list()[1].scope == "title")
        check(!ReplaceRuleStore.list()[1].enabled)
        check(ReplaceRuleStore.apply("戊", false) == "己")
        check(ReplaceRuleStore.apply("戊", true) == "己")
        check(ReplaceRuleStore.importJson(incoming) == 0)
        ReplaceRuleStore.save(emptyList())
        check(ReplaceRuleStore.list().isEmpty())
        // 切换文件路径绕过进程缓存，验证重读空数组不恢复默认规则。
        val empty = File(dir, "empty.json").apply { writeText("[]") }
        ReplaceRuleStore.file = empty
        check(ReplaceRuleStore.list().isEmpty())
    } finally {
        ReplaceRuleStore.file = null
        dir.deleteRecursively()
    }
}

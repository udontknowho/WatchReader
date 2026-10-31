import com.watchdl.core.LegadoImport
import com.watchdl.core.applyResult
import com.watchdl.core.jsBlockBodies
import com.watchdl.core.textAfterLastJsBlock

/**
 * 「脚本块 + 尾部文本」自检（legado 的 `AnalyzeUrl.analyzeJs()`）。
 *
 * 两条真实源被这条规则挡住过：
 * - 最终小说网：`<js>…</js>` 换行后才是 URL，以前整条当脚本 → 空 URL，`no protocol: `
 * - uu书库：脚本算查询串，尾部给网址带 `@result`，以前把带引号的查询串当 URL
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("  ✓ $name") else { failures++; println("  ✗ $name  $detail") }
    }

    println("[UrlJs] 块与尾部文本切分")
    val zz = "<js>cookie.removeCookie(source.key);</js>\n" +
             "https://www.zuizhong.net/search.html,{\"method\":\"POST\",\"body\":\"s={{key}}\"}"
    check("块体取出", jsBlockBodies(zz) == listOf("cookie.removeCookie(source.key);"), "${jsBlockBodies(zz)}")
    check("尾部文本", textAfterLastJsBlock(zz).startsWith("https://www.zuizhong.net/search.html,"),
        textAfterLastJsBlock(zz))

    val uu = "<js>var a=1;return JSON.stringify('keyword='+a);</js>" +
             "https://www.rrssk.com/?action=search,{\"method\":\"POST\",\"body\":@result}"
    check("uu书库 尾部文本", textAfterLastJsBlock(uu).startsWith("https://www.rrssk.com/?action=search,"),
        textAfterLastJsBlock(uu))

    // 纯脚本源：没有尾部文本，不能被误判成「块 + 文本」
    check("纯脚本无尾部", textAfterLastJsBlock("<js>return 'https://x/so?q='+key;</js>").isEmpty())
    check("纯 @js: 无尾部", textAfterLastJsBlock("@js:'https://x/?q='+key").isEmpty())
    check("无脚本无尾部", textAfterLastJsBlock("https://x/so?q={{key}}").isEmpty())

    println("[UrlJs] @result 回填")
    check("替换一次", applyResult("https://x/?@result", "a=1") == "https://x/?a=1")
    check("结果为 null 不动", applyResult("https://x/?@result", null) == "https://x/?@result")
    check("无占位符不动", applyResult("https://x/", "a=1") == "https://x/")

    println("[UrlJs] 导入期接线")
    val src = mapOf(
        "bookSourceName" to "自检源",
        "bookSourceUrl" to "https://self.test",
        "searchUrl" to zz,
        "ruleSearch" to mapOf("bookList" to ".item", "name" to ".name"),
        "ruleToc" to mapOf("chapterList" to ".ch"),
        "ruleContent" to mapOf("content" to "#content"),
    )
    val imported = runCatching { LegadoImport.importOne("自检源", src) }.getOrNull()
    val r = imported?.rule
    check("导入成功", r != null, imported?.issue ?: "")
    check("尾部文本成了 URL", r?.search?.url == "https://www.zuizhong.net/search.html", "${r?.search?.url}")
    check("POST 选项来自尾部", r?.search?.method == "POST" && r?.search?.body == "s={{key}}",
        "${r?.search?.method} ${r?.search?.body}")
    check("块体进了 urlJs", r?.search?.urlJs?.contains("removeCookie") == true, "${r?.search?.urlJs}")

    if (failures == 0) println("[UrlJs] 全部通过") else
        throw AssertionError("UrlJs 自检失败 $failures 项")
}

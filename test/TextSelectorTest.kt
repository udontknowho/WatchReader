import com.watchdl.core.LegadoSelector
import com.watchdl.core.HtmlSelect
import com.watchdl.core.Selector

/**
 * legado `text.串@href` 的选择器自检。
 *
 * 这条规则以前被当成 CSS 类名 `.下一页` 处理，永远选不到，
 * 于是 `text.下一页@href` 的分页源只取到目录第一页（实测 20/40/50 章）。
 */
fun main() {
    check(LegadoSelector.toCss("text.下一页@href") == "*:text(下一页)@href") {
        "展开错误：" + LegadoSelector.toCss("text.下一页@href")
    }
    check(LegadoSelector.toCss("text.下一章") == "*:text(下一章)") {
        "展开错误：" + LegadoSelector.toCss("text.下一章")
    }
    // 带位置：text.下一页.0 → *:text(下一页)[0]
    check(LegadoSelector.toCss("text.下一页.0@href") == "*:text(下一页)[0]@href") {
        "带位置展开错误：" + LegadoSelector.toCss("text.下一页.0@href")
    }

    val html = """
        <html><body>
        <div class="pager">
          <a href="/toc/2.html">下一页</a>
          <a href="/toc/9.html">尾页</a>
        </div>
        <div class="x"><a href="/other.html">别的</a></div>
        </body></html>
    """.trimIndent()

    val got = HtmlSelect.select(
        html, LegadoSelector.toCss("text.下一页").substringBefore("@"),
        Selector.Pick.ATTR, "href"
    )
    check(got == listOf("/toc/2.html")) { "应只选中「下一页」，实际：$got" }

    // 不含目标文字时必须为空
    val none = HtmlSelect.select(
        html, LegadoSelector.toCss("text.下一章").substringBefore("@"),
        Selector.Pick.ATTR, "href"
    )
    check(none.isEmpty()) { "不该选中任何节点，实际：$none" }

    // 文本取值：含「尾页」的元素都会被选中（legado 也是这个语义，
    // 祖先节点的内文同样含这三个字）。规则最后用位置或 @href 收窄。
    val txt = HtmlSelect.select(html, LegadoSelector.toCss("text.尾页"), Selector.Pick.TEXT, null)
    check(txt.contains("尾页")) { "文本取值应含「尾页」，实际：$txt" }

    println("TextSelectorTest passed")
}

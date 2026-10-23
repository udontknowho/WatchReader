import com.watchdl.core.XPathLite
import com.watchdl.core.LegadoSelector
import com.watchdl.core.HtmlSelect
import com.watchdl.core.Selector
import com.watchdl.core.FieldRule

/**
 * legado Default 语法自检：位置、排除、倒置、children、区间。
 *
 * 这些写法以前要么恒空，要么静默取错值（`tag.div.-1` 被当成取第一个）。
 * 基准：https://hapelee.github.io/legado-with-MD3/dev/syntax.html
 */
fun main() {
    val html = """
        <html><body>
        <div class="box">
          <p>x0</p><p>x1</p><p>x2</p><p>x3</p>
        </div>
        <ul class="lst">
          <li>a</li><li>b</li><li>c</li>
        </ul>
        </body></html>
    """.trimIndent()

    fun pick(rule: String, attr: String? = null): List<String> {
        val css = LegadoSelector.toCss(rule)
        val sel = Selector.parse(css) as Selector.Css
        return HtmlSelect.select(html, sel.query, sel.pick, sel.attr ?: attr)
    }

    // 位置：tag.p.0 取第一个
    check(pick("tag.p.0@text") == listOf("x0")) { "tag.p.0: ${pick("tag.p.0@text")}" }
    // 负数位置：tag.p.-1 取最后一个（以前被静默删掉，返回 x0）
    check(pick("tag.p.-1@text") == listOf("x3")) { "tag.p.-1: ${pick("tag.p.-1@text")}" }
    // 点号排除：tag.p!0 去掉第一个
    check(pick("tag.p!0@text") == listOf("x1", "x2", "x3")) { "tag.p!0: ${pick("tag.p!0@text")}" }
    // 点号排除列表：`!0:2` 按文档是「排除第 1 个和第 3 个」，即去掉索引 0 和 2
    check(pick("tag.p!0:2@text") == listOf("x1", "x3")) { "tag.p!0:2: ${pick("tag.p!0:2@text")}" }
    // 方括号区间左闭右开：[0:2] 取前两个
    check(pick("tag.p[0:2]@text") == listOf("x0", "x1")) { "tag.p[0:2]: ${pick("tag.p[0:2]@text")}" }
    // 列表倒置
    check(pick("-class.lst@tag.li@text") == listOf("c", "b", "a")) {
        "-class.lst: ${pick("-class.lst@tag.li@text")}"
    }

    // children：直接子标签
    val kids = pick("class.lst@children@text")
    check(kids == listOf("a", "b", "c")) { "children: $kids" }

    // textNodes：不能把标签名当文本
    val tn = HtmlSelect.select(
        "<html><body><p>a<span>c</span>b</p></body></html>",
        "p", Selector.Pick.TEXT_NODES, null
    )
    check(tn == listOf("a\nc\nb")) { "textNodes: $tn" }

    // XPath 位置谓词：//p[2] 取第 2 个（0 起）
    val xsel = Selector.parse("//p[2]") as Selector.XPath
    val xp = XPathLite.select(html, xsel.expr, xsel.pick, xsel.attr)
    check(xp == listOf("x2")) { "//p[2]: $xp" }

    // `:eq(N)` / `:contains()`：`@css:` 前缀的规则会把它们原样带进来
    check(pick("@css:class.box>p:eq(1)@text") == listOf("x1")) { ":eq(1): ${pick("@css:class.box>p:eq(1)@text")}" }
    val tc = HtmlSelect.select(
        "<html><body><span>作者：老舍</span><span>分类：小说</span></body></html>",
        "span:contains(分类)", Selector.Pick.TEXT, null
    )
    check(tc == listOf("分类：小说")) { ":contains: $tc" }

    // `@put` 存变量、`@get` 取变量。以前两者都不处理：原串当选择器 → 恒空。
    FieldRule.clearVars()
    FieldRule("class.box@text@put:{first:\"tag.p.0@text\"}").eval(html)
    check(FieldRule("@get:{first}").eval(html) == listOf("x0")) {
        "@get after @put: ${FieldRule("@get:{first}").eval(html)}"
    }
    // 多个 key、内嵌规则里的逗号不能切错
    FieldRule.clearVars()
    FieldRule("p@put:{a:\"tag.p.1@text\",b:\"tag.p.3@text\"}").eval(html)
    check(FieldRule("@get:{a}").eval(html) == listOf("x1")) { "@get a" }
    check(FieldRule("@get:{b}").eval(html) == listOf("x3")) { "@get b" }
    // 取不存在的变量：返回空，不是字面量
    check(FieldRule("@get:{nope}").eval(html).isEmpty()) { "@get nope" }

    println("LegadoDefaultTest passed")
}

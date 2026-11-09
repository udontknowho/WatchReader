import com.watchdl.core.Es5Compat
import com.watchdl.core.JsEngine

/**
 * default 参数降级的自检：改完要还能跑，且语义不变。
 *
 * 跑法见 build.sh 的 test 目标。
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("  ✓ $name") else { failures++; println("  ✗ $name  $detail") }
    }

    println("[Es5Compat] 改写")
    check("函数声明摘默认值",
        Es5Compat.rewrite("function f(a, b = 1) { return a + b }")
            == "function f(a, b) { if (typeof b === \"undefined\") b = 1;  return a + b }")
    check("名字带空格的函数",
        Es5Compat.rewrite("function getApiUrl(path, data, host = api) { x() }")
            .let { it.contains("host)") && it.contains("host = api;") })
    check("多个默认值按序插",
        Es5Compat.rewrite("function f(a = 1, b = 2) { return a }")
            .let { it.indexOf("a = 1;") < it.indexOf("b = 2;") })
    check("默认值是调用/对象不误切",
        Es5Compat.rewrite("function f(a, b = g(1, 2), c = { x: 1, y: 2 }) { return 1 }")
            .let { it.contains("b = g(1, 2);") && it.contains("c = { x: 1, y: 2 };") })
    check("箭头函数块体",
        Es5Compat.rewrite("var f = (x = 2) => { return x }")
            .let { it.contains("=>") && it.contains("x = 2;") })
    check("表达式体箭头原样返回",
        Es5Compat.rewrite("var f = (x = 2) => x").let { it == "var f = (x = 2) => x" })
    check("普通括号不动",
        Es5Compat.rewrite("if (a == 1) { b(2, 3) }") == "if (a == 1) { b(2, 3) }")
    check("字符串里的括号与等号不动",
        Es5Compat.rewrite("var s = \"function f(a = 1)\"") == "var s = \"function f(a = 1)\"")
    check("注释不动",
        Es5Compat.rewrite("// function f(a = 1)\nvar x = 1") == "// function f(a = 1)\nvar x = 1")
    check("rest 参数保留",
        Es5Compat.rewrite("function f(a, b = 1, ...r) { return 1 }")
            .let { it.contains("...r)") && it.contains("b = 1;") })
    check("比较运算不当默认值",
        Es5Compat.rewrite("function f(a) { if (a >= 1 && a != 2) { return 1 } }")
            .let { !it.contains("a = ") })

    println("[Es5Compat] 真跑一遍 Rhino")
    check("default 参数能执行",
        JsEngine.eval("function f(a, b = 1) { return a + b } String(f(1))") == "2")
    check("默认值生效",
        JsEngine.eval("function f(a, b = 7) { return b } String(f(1))") == "7")
    check("传了实参就不覆盖",
        JsEngine.eval("function f(a, b = 7) { return b } String(f(1, 3))") == "3")
    check("默认值是函数调用",
        JsEngine.eval("function g() { return 5 } function f(b = g()) { return b } String(f())") == "5")
    check("箭头函数默认值",
        JsEngine.eval("var f = (x = 4) => { return x }; String(f())") == "4")
    check("老写法不受影响",
        JsEngine.eval("function f(a, b) { return a + b } String(f(1, 2))") == "3")

    println("[Es5Compat] 7.5 真实规则回归")
    fun evaluates(name: String, code: String, expected: String) {
        val result = runCatching { JsEngine.eval(code) }
        check(name, result.getOrNull() == expected,
            result.exceptionOrNull()?.message ?: "得到 ${result.getOrNull()}，预期 $expected")
    }
    evaluates("微博正则含引号与不平衡括号", "var result = '{\"content1\": \"正文\",'; result.match(/\"content1\": \"([^\"]+)\",/)?.[1] ?? ''", "正文")
    evaluates("微博正则未匹配", "var result = ''; result.match(/\"content1\": \"([^\"]+)\",/)?.[1] ?? ''", "")
    evaluates("多级链后接空值合并", "var src = '{}'; JSON.parse(src || '[]')?.data?.media_list ?? []", "[]")
    evaluates("空值合并右侧可选链", "var data = {live: 7}; data?.common ?? data?.live", "7")
    evaluates("空值合并保留零与空串", "[0 ?? 7, '' ?? 'x', false ?? true]", "[0,\"\",false]")
    evaluates("链左侧只求值一次", "var calls=0; function f(){calls++;return {x:3}}; var x=f()?.x ?? 8; [x,calls]", "[3,1]")
    evaluates("正则中的现代运算符不改写", "String(/a?.b/.test('ab'))", "true")
    evaluates("匿名函数默认值", "var f = function(x = 4) { return x }; f()", "4")
    evaluates("豆瓣正文模板内可选链", "var item={data:{size:{orig:{src:'cover'}}}}; `<img src=\"\${item.data?.size.orig.src}\">`", "<img src=\"cover\">")
    evaluates("模板空值合并", "var t=null; `v=\${t??'none'}`", "v=none")
    evaluates("嵌套模板表达式", "var t=null; `a=\${`b=\${t??'none'}`}`", "a=b=none")

    if (failures > 0) {
        println("Es5CompatTest: FAIL ($failures)")
        kotlin.system.exitProcess(1)
    }
    println("Es5CompatTest passed")
}

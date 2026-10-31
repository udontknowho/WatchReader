import com.watchdl.core.JsEngine

/**
 * 探针：本机 Rhino 到底认哪些 ES6 语法。
 *
 * 只打印矩阵，不断言 —— 结论用来决定 `Es5Compat` 要降级哪些写法。
 */
fun main() {
    val cases = linkedMapOf(
        "default 参数" to "function f(a, b = 1) { return a + b } String(f(1))",
        "default 参数(箭头)" to "var f = (x = 2) => x; String(f())",
        "rest 参数" to "function f(...a) { return a.length } String(f(1, 2))",
        "箭头函数" to "var f = (x) => x + 1; String(f(1))",
        "模板字符串" to "var a = 1; `v=\${a}`",
        "对象解构声明" to "var { a } = { a: 5 }; String(a)",
        "对象解构带默认" to "var o = {}; var { a = 7 } = o; String(a)",
        "数组解构" to "[a, b] = [1, 2]; String(a + b)".let { "var a, b; " + it },
        "解构参数" to "function f({ a }) { return a } String(f({ a: 3 }))",
        "属性简写" to "var a = 1; var o = { a }; String(o.a)",
        "展开调用" to "function f(a, b) { return a + b } var x = [1, 2]; String(f(...x))",
        "展开数组字面量" to "var x = [1, 2]; var y = [...x, 3]; String(y.length)",
        "可选链" to "var o = { a: 1 }; String(o?.a)",
        "空值合并" to "String(null ?? 3)",
        "let/const" to "const a = 1; let b = 2; String(a + b)",
        "class" to "class A { constructor() { this.x = 1 } } String(new A().x)",
        "幂运算" to "String(2 ** 3)",
        "for-of" to "var s = 0; for (var v of [1, 2]) { s += v } String(s)",
        "generator" to "function* g() { yield 1 } String(g().next().value)",
        "includes" to "String([1, 2].includes(2))",
        "Object.entries" to "String(Object.entries({ a: 1 }).length)",
        "async 函数" to "async function f() { return 1 } 'x'",
    )
    var ok = 0
    for ((name, code) in cases) {
        val r = runCatching { JsEngine.eval(code) }
        val v = r.getOrNull()
        if (v != null) {
            ok++
            println("  ✓ %-16s → %s".format(name, v.take(24)))
        } else {
            println("  ✗ %-16s → %s".format(name, r.exceptionOrNull()?.message?.take(70)))
        }
    }
    println("JsSyntaxProbeTest: 认 $ok / ${cases.size}")
}

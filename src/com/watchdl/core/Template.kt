package com.watchdl.core

/**
 * URL 模板求值。支持两种占位：
 * - `{{key}}`：从上下文取值
 * - `{{(page-1)*10}}`：只允许「数字变量 与 数字」的 + - * / 四则，禁止函数调用
 *
 * 刻意不支持任意表达式：规则里能跑的东西越多，手表上要背的安全包袱越重。
 */
object Template {

    /**
     * @param jsEval 处理 JS 表达式（`{{java.xxx}}` 这类）的回调；
     *   传 null 时这类表达式返回空串。传回调是为了让调用方能把 jsLib 拼进脚本。
     */
    fun render(
        template: String,
        ctx: Map<String, String>,
        jsEval: ((String, JsEngine.Env) -> String)? = null,
    ): String {
        val sb = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val open = template.indexOf("{{", i)
            if (open < 0) { sb.append(template, i, template.length); break }
            sb.append(template, i, open)
            val close = template.indexOf("}}", open + 2)
            if (close < 0) { sb.append(template, open, template.length); break }
            sb.append(evalExpr(template.substring(open + 2, close), ctx, jsEval))
            i = close + 2
        }
        return resolveGet(sb.toString(), ctx)
    }

    /**
     * legado 的 `@get:{var}` 语法：取 JS 变量的值。
     *
     * 9 条源在 URL 里这么写，如 `http://api.17k.com/book/@get:{book}/volumes`。
     * 以前原样拼进 URL，得到字面量 `@get:{book}` → URL 非法 → 直接报错。
     *
     * 变量优先取 [ctx]（调用方已经放进去的书名/书号/URL），找不到就原样保留——
     * 留字面量比默默换成空串好查。
     */
    private fun resolveGet(s: String, ctx: Map<String, String>): String {
        if (!s.contains("@get:")) return s
        return GET_RE.replace(s) { m ->
            val name = m.groupValues[1].trim()
            ctx[name] ?: m.value
        }
    }

    private val GET_RE = Regex("@get:\\{([^{}]*)\\}")

    /** 旧签名，保留给不需要 JS 的调用方。 */
    fun render(template: String, ctx: Map<String, String>): String = render(template, ctx, null)

    /**
     * 求值一个 `{{...}}` 内部表达式。
     *
     * 变量取值失败分两种情形：整个表达式就是一个变量名时返回空串（表示「没取到」）；
     * 变量参与四则运算时按 0 算，保证 URL 仍能拼出来。
     */
    fun evalExpr(
        expr: String,
        ctx: Map<String, String>,
        jsEval: ((String, JsEngine.Env) -> String)? = null,
    ): String {
        val e = expr.trim()
        ctx[e]?.let { return it }
        // 裸变量名且取不到：返回空串，不要伪装成 0
        if (Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(e)) return ""
        // JS 表达式（`{{java.encodeURI(key)}}` 这类）交给回调。
        // 实测 30 条 yiove 源里 7 条用这种写法拼搜索地址。
        if (looksLikeJs(e)) {
            val cb = jsEval ?: return ""
            return runCatching { cb("($e)", JsEngine.Env(vars = ctx.toMutableMap())) }.getOrElse { "" }
        }
        val body = e.removeSurrounding("(", ")")
        // 只用数字与四则运算符，避免引入表达式解析器
        if (!Regex("^[0-9+\\-*/().\\sA-Za-z_]+$").matches(body)) return ""
        val substituted = Regex("[A-Za-z_][A-Za-z0-9_]*").replace(body) { m ->
            (ctx[m.value]?.toDoubleOrNull() ?: return@replace "0").let {
                if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
            }
        }
        return Arith.eval(substituted.trim())
    }
    /** 含 `java.` / 引号 / 函数调用 → 当 JS 表达式处理。 */
    private fun looksLikeJs(e: String): Boolean =
        e.contains("java.") || e.contains("'") || e.contains("\"") ||
            Regex("[A-Za-z_][A-Za-z0-9_]*\\s*\\(").containsMatchIn(e)
}

/** 四则 + 括号，整数优先。 */
internal object Arith {
    fun eval(expr: String): String {
        val v = try { Parser(expr).parseExpr() } catch (e: Exception) { return expr }
        return if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    }

    private class Parser(private val s: String) {
        private var i = 0
        fun parseExpr(): Double {
            var v = parseTerm()
            while (i < s.length) {
                when (s[i]) {
                    '+' -> { i++; v += parseTerm() }
                    '-' -> { i++; v -= parseTerm() }
                    else -> return v
                }
            }
            return v
        }
        private fun parseTerm(): Double {
            var v = parseFactor()
            while (i < s.length) {
                when (s[i]) {
                    '*' -> { i++; v *= parseFactor() }
                    '/' -> { i++; val d = parseFactor(); v = if (d == 0.0) 0.0 else v / d }
                    else -> return v
                }
            }
            return v
        }
        private fun parseFactor(): Double {
            while (i < s.length && s[i] == ' ') i++
            if (i < s.length && (s[i] == '-' || s[i] == '+')) {
                val neg = s[i] == '-'; i++
                val v = parseFactor(); return if (neg) -v else v
            }
            if (i < s.length && s[i] == '(') {
                i++
                val v = parseExpr()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDoubleOrNull() ?: 0.0
        }
    }
}

package com.watchdl.core

/**
 * 本机 Rhino 不认的写法 → 它认的写法。
 *
 * 本机 Rhino 认箭头函数、模板串、解构、rest、for-of、generator，
 * **不认**：
 * 1. default 参数（`function f(a, b = 1)` / `(a = 1) => …`）→ [params]
 * 2. `a?.b` 可选链、`a ?? b` 空值合并 → [modern]
 *
 * `class` / `async` / spread **不做** —— 要真语法树，而且 84 条源里 0 命中。
 * 支持矩阵见 test/JsSyntaxProbeTest.kt。
 */
object Es5Compat {

    /** 两步：先补 ES2020 的 `?.` `??`，再把 default 参数摘成守卫语句。 */
    fun rewrite(src: String): String {
        val s1 = if (src.contains("?.") || src.contains("??")) modern(src) else src
        if (!s1.contains("function") && !s1.contains("=>")) return s1
        return params(s1)
    }

    /** 快路径以外只处理参数表：`function f(a, b = 1) { … }`。 */
    private fun params(src: String): String {
        val sb = StringBuilder(src.length + 64)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\'' || c == '"' || c == '`') {
                val e = endOfString(src, i); sb.append(src, i, e); i = e; continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                val e = endOfLine(src, i); sb.append(src, i, e); i = e; continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                val e = endOfBlockComment(src, i); sb.append(src, i, e); i = e; continue
            }
            if (c == '(') {
                val r = rewriteParams(src, i, sb)
                if (r > i) { i = r; continue }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * 在 `(` 处判断它是不是参数表，是就把默认值摘成 body 开头的守卫语句。
     *
     * 判定：括号配平后紧跟 `=>`，或者括号前是 `function` / `function 名字`。
     * 返回处理到的位置（-1 表示不是参数表，交给调用方原样输出）。
     */
    private fun rewriteParams(src: String, open: Int, sb: StringBuilder): Int {
        val close = matchParen(src, open) ?: return -1
        val params = src.substring(open + 1, close)
        val after = skipSpace(src, close + 1)
        val isArrow = src.startsWith("=>", after)
        val isFunc = looksLikeFunction(src, open)
        if ((!isArrow && !isFunc) || !hasDefault(params)) return -1

        // 带默认值就必须有块体，表达式体（`(a = 1) => a + 1`）没法插守卫语句 → 原样返回。
        val braceAt = if (isArrow) skipSpace(src, after + 2) else after
        if (braceAt >= src.length || src[braceAt] != '{') return -1

        val plain = mutableListOf<String>()
        val guards = mutableListOf<String>()
        for (raw in splitTopLevel(params)) {
            val eq = topLevelAssign(raw)
            if (eq < 0) {
                plain += raw.trim()
            } else {
                val name = raw.substring(0, eq).trim()
                if (!IDENT.matches(name)) {
                    plain += raw.trim()
                    continue
                }
                plain += name
                guards += "if (typeof $name === \"undefined\") $name = ${raw.substring(eq + 1).trim()};"
            }
        }
        // `)` 到 body 开头（`=>` 或空格）原样搬过去，守卫插在 `{` 之后。
        sb.append('(').append(plain.joinToString(", ")).append(')')
        sb.append(src, close + 1, braceAt)
        sb.append("{ ")
        if (guards.isNotEmpty()) {
            sb.append(guards.joinToString(" "))
            sb.append(' ')
        }
        return braceAt + 1
    }

    // ── ES2020：可选链与空值合并 ──
    //
    // 本机 Rhino 不认 `a?.b` / `a ?? b`。两种都靠文本改写补上：
    //   a?.b      → ((_q0 = a) == null ? void 0 : _q0.b)
    //   a?.b?.c   → 再套一层，见 [chain]
    //   a ?? b    → ((_q0 = a) != null ? _q0 : (b))
    // 左操作数的文字直接从已生成的输出里切（[at] 记录源位置→输出位置），
    // 这样嵌套改写（`a?.b ?? c`）不会把已改写好的左半边切错。
    //
    // shortcut: 只按「后缀表达式 + 一个操作数」的文本边界切；
    // 遇到语句边界（`if (x) y ?? z` 写在同一行）会切错。命中面很小
    // （84 条源里 `?.` 2 条、`??` 0 条），真错了只是这条源报脚本错，不连累别的源。
    private fun modern(src: String): String {
        val out = StringBuilder(src.length + 64)
        val at = IntArray(src.length + 1)
        val tmp = intArrayOf(0)
        var i = 0
        while (i < src.length) {
            at[i] = out.length
            val c = src[i]
            if (c == '\'' || c == '"' || c == '`') {
                val e = endOfString(src, i); out.append(src, i, e); i = e; continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                val e = endOfLine(src, i); out.append(src, i, e); i = e; continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                val e = endOfBlockComment(src, i); out.append(src, i, e); i = e; continue
            }
            if (c == '?' && i + 1 < src.length && src[i + 1] == '.' &&
                (i + 2 >= src.length || !src[i + 2].isDigit())) {
                val r = chain(src, i, out, at, tmp)
                if (r > i) { i = r; continue }
            }
            if (c == '?' && i + 1 < src.length && src[i + 1] == '?') {
                val r = nullish(src, i, out, at, tmp)
                if (r > i) { i = r; continue }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** `A?.b.c(x)[i]` → 逐层 `((_qN = A) == null ? void 0 : _qN.b.c(x)[i])`。 */
    private fun chain(src: String, q: Int, out: StringBuilder, at: IntArray, tmp: IntArray): Int {
        val leftStart = exprStart(src, q)
        if (leftStart >= q) return -1
        var cur = out.substring(at[leftStart])          // 已改写的左操作数
        if (cur.isBlank()) return -1
        val head = at[leftStart]
        var acc = StringBuilder()
        var i = q + 2
        while (i < src.length) {
            val c = src[i]
            if (c == '?') {
                if (i + 1 >= src.length || src[i + 1] != '.') break
                cur = wrap(cur, acc, tmp)
                acc = StringBuilder()
                i += 2
                continue
            }
            if (c == '.' || isIdent(c)) {
                // `?.a` 的成员名紧跟 `?.`，不带点；`a.b` 的带点。两种都当一级访问。
                val from = if (c == '.') i + 1 else i
                var e = from
                while (e < src.length && isIdent(src[e])) e++
                if (e == from || !isIdent(src[from])) break
                acc.append('.').append(src, from, e)
                i = e
                continue
            }
            if (c == '[' || c == '(') {
                val e = matchPair(src, i, c, if (c == '[') ']' else ')') ?: break
                acc.append(modern(src.substring(i, e + 1)))
                i = e + 1
                continue
            }
            break
        }
        out.setLength(head)
        out.append(wrap(cur, acc, tmp))
        return i
    }

    private fun wrap(cur: String, acc: StringBuilder, tmp: IntArray): String {
        val t = "__wc" + tmp[0]++
        return "(($t = $cur) == null ? void 0 : $t$acc)"
    }

    /** `A ?? B` → `((_qN = A) != null ? _qN : (B))`。 */
    private fun nullish(src: String, q: Int, out: StringBuilder, at: IntArray, tmp: IntArray): Int {
        val leftStart = exprStart(src, q)
        if (leftStart >= q) return -1
        val left = out.substring(at[leftStart])
        if (left.isBlank()) return -1
        val rhsStart = skipSpace(src, q + 2)
        val rhsEnd = operandEnd(src, rhsStart)
        if (rhsEnd <= rhsStart) return -1
        val rhs = modern(src.substring(rhsStart, rhsEnd).trimEnd())
        if (rhs.isBlank()) return -1
        val t = "__wc" + tmp[0]++
        out.setLength(at[leftStart])
        out.append("(($t = $left) != null ? $t : ($rhs))")
        return rhsEnd
    }

    /**
     * 向后找后缀表达式的起点（`?.` 与 `??` 的左操作数）。
     *
     * 认：标识符、`.x` 链、`[i]`、`(call)`、字符串/数字字面量、`?.` 连缀，
     * 以及 `typeof` `new` 这类前缀词。跨行、语句关键词、没闭合的 `(` 一律停。
     */
    private fun exprStart(src: String, pos: Int): Int {
        var k = pos - 1
        while (k >= 0) {
            val beforeWs = k
            while (k >= 0 && (src[k] == ' ' || src[k] == '\t')) k--
            if (k < 0) return 0
            if (src[k] == '\n' || src[k] == '\r') return beforeWs + 1
            when (val c = src[k]) {
                ')' , ']' -> {
                    val open = if (c == ')') '(' else '['
                    val m = matchBack(src, k, open, c) ?: return k + 1
                    if (c == ')' && (matchPair(src, m, '(', ')') ?: -1) > pos) return k + 1
                    k = m - 1
                }
                '(' -> return k + 1          // 向后遇到的 `(` 只能是一次没闭合的调用
                '.' -> k--
                '?' -> {
                    if (k + 1 < src.length && src[k + 1] == '.') k -= 2 else return k + 1
                }
                '\'', '"', '`' -> {
                    val s = stringStartBack(src, k)
                    if (s <= 0) return 0
                    k = s - 1
                }
                else -> {
                    when {
                        isIdent(c) -> {
                            var s = k
                            while (s >= 0 && isIdent(src[s])) s--
                            if (src.substring(s + 1, k + 1) in STOP_WORDS) return k + 1
                            k = s
                        }
                        c.isDigit() -> { while (k >= 0 && (src[k].isDigit() || src[k] == '.')) k-- }
                        else -> return k + 1
                    }
                }
            }
        }
        return 0
    }

    /** `??` 右操作数到哪里结束：顶层遇到分隔符就停。 */
    private fun operandEnd(src: String, from: Int): Int {
        var i = from
        var depth = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\'' || c == '"' || c == '`') { i = endOfString(src, i); continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') { i = endOfLine(src, i); continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') { i = endOfBlockComment(src, i); continue }
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { if (depth == 0) return i; depth-- }
                ',', ';', '?', ':', '\n', '\r' -> if (depth == 0) return i
            }
            i++
        }
        return i
    }

    /** 语句/声明关键词：左操作数**不**包含它们，向后扫到就停。 */
    private val STOP_WORDS = setOf(
        "return", "var", "let", "const", "case", "throw", "else", "do", "if", "for",
        "while", "switch", "catch", "function", "with", "try", "typeof", "new",
    )

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun stringStartBack(src: String, end: Int): Int {
        val q = src[end]
        var i = end - 1
        while (i >= 0) {
            if (src[i] == '\\') { i -= 2; continue }
            if (src[i] == q) return i
            i--
        }
        return -1
    }

    /** 从收尾符号往回找开符号。 */
    private fun matchBack(src: String, close: Int, open: Char, closeCh: Char): Int? {
        var depth = 0
        var i = close
        while (i >= 0) {
            val c = src[i]
            if (c == closeCh) depth++
            else if (c == open) { depth--; if (depth == 0) return i }
            i--
        }
        return null
    }

    private fun matchPair(src: String, open: Int, o: Char, c: Char): Int? {
        var depth = 0
        var i = open
        while (i < src.length) {
            val ch = src[i]
            if (ch == '\'' || ch == '"' || ch == '`') { i = endOfString(src, i); continue }
            if (ch == o) depth++ else if (ch == c) { depth--; if (depth == 0) return i }
            i++
        }
        return null
    }

    private val IDENT = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

    private fun hasDefault(params: String) = splitTopLevel(params).any { topLevelAssign(it) >= 0 }

    /** 顶层 `=` 的位置（排除 `==` `>=` `=>` 这些）。 */
    private fun topLevelAssign(s: String): Int {
        var i = 0
        var depth = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' || c == '"' || c == '`' -> { i = endOfString(s, i) }
                c == '(' || c == '[' || c == '{' -> { depth++; i++ }
                c == ')' || c == ']' || c == '}' -> { depth--; i++ }
                c == '=' && depth == 0 -> {
                    val prev = if (i > 0) s[i - 1] else ' '
                    val next = if (i + 1 < s.length) s[i + 1] else ' '
                    val ok = next != '=' && next != '>' && prev != '=' && prev != '!' && prev != '<' && prev != '>'
                    if (ok) return i
                    i += 2
                }
                else -> i++
            }
        }
        return -1
    }

    /** 按顶层逗号切分参数表（默认值里的逗号、对象字面量里的逗号不算）。 */
    private fun splitTopLevel(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' || c == '"' || c == '`' -> i = endOfString(s, i)
                c == '(' || c == '[' || c == '{' -> { depth++; i++ }
                c == ')' || c == ']' || c == '}' -> { depth--; i++ }
                c == ',' && depth == 0 -> { out += s.substring(start, i); start = i + 1; i++ }
                else -> i++
            }
        }
        out += s.substring(start)
        return out.filter { it.isNotBlank() }
    }

    private fun looksLikeFunction(src: String, open: Int): Boolean {
        var i = open - 1
        while (i >= 0 && src[i].isWhitespace()) i--
        // 跳过函数名
        while (i >= 0 && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i--
        while (i >= 0 && src[i].isWhitespace()) i--
        val end = i + 1
        val start = maxOf(0, end - 8)
        return src.substring(start, end).endsWith("function")
    }

    private fun matchParen(src: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\'' || c == '"' || c == '`' -> { i = endOfString(src, i); continue }
                c == '(' -> depth++
                c == ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun skipSpace(src: String, from: Int): Int {
        var i = from
        while (i < src.length && src[i].isWhitespace()) i++
        return i
    }

    /** 返回字符串字面量结束后的位置（含收尾引号）。 */
    private fun endOfString(src: String, start: Int): Int {
        val q = src[start]
        var i = start + 1
        while (i < src.length) {
            when (src[i]) {
                '\\' -> i += 2
                q -> return i + 1
                else -> i++
            }
        }
        return src.length
    }

    private fun endOfLine(src: String, start: Int): Int {
        var i = start
        while (i < src.length && src[i] != '\n') i++
        return i
    }

    private fun endOfBlockComment(src: String, start: Int): Int {
        val e = src.indexOf("*/", start + 2)
        return if (e < 0) src.length else e + 2
    }
}

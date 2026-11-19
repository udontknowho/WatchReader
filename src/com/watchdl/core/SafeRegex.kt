package com.watchdl.core

/**
 * 安全的正则编译。
 *
 * 书源里的正则由第三方作者写，而且 **Android 的 `java.util.regex` 是 ICU 后端，
 * 比 JVM 严格得多**：JVM 上能编译的写法（如 `\《`、`\作者` 这类无效转义，
 * 或 `{{name}}` 这种把 `{` 当量词的写法）在手表上会抛
 * `PatternSyntaxException: Syntax error`。
 *
 * 实测：同一份书源文件，本机 JVM 跑出「可用 43 / 共 54」，
 * 设备上却是「45 条 PatternSyntaxException」。所以**本地全绿不代表设备可用**，
 * 凡是编译第三方正则的地方都必须走这里，不能直接 `Regex(...)`。
 */
object SafeRegex {

    /** 永不匹配任何内容的正则。用于「正则本身无法表达」的兜底。 */
    private val NEVER_MATCH = Regex("\\b\\B")

    /**
     * 永不匹配。用字面空类 \[^\s\S] 而不是 NEVER_MATCH：
     * 后者是编译期常量，前者在这里更直白。
     */
    private val neverMatch: Regex get() = NEVER_MATCH

    /** 编译失败或形状可疑返回 null，调用方按「没有正则」处理。 */
    fun compile(pattern: String?): Regex? {
        if (pattern.isNullOrEmpty()) return null   // 空模式＝没有正则，不当成"匹配一切"
        if (isSuspicious(pattern)) return null
        return runCatching { Regex(pattern) }.getOrElse { null }
    }

    // ── 灾难性回溯的形状判定 ──
    //
    // 旧实现是一句正则 `\([^)]*[+*]\)[+*]`，只认「`)` 前紧邻 + 或 *」这一种形状：
    // `(a+)+` 挡得住，`(\w+\s?)*`、`(a|a)+`、`(?:x+){2,}` 全部放行。
    // 现在改成把正则体解析成小语法树再判形状，覆盖三类指数回溯：
    //   1. 组内有无界量词，组本身又被无界量词套住 —— `(a+)+`、`(\w+\s?)*`、`(?:x+){2,}`
    //   2. 组内交替分支互相重叠（相等或互为前缀），组被无界量词套住 —— `(a|a)+`、`(a|ab)+`
    //   3. 上述两种套多层 —— `((a+))+`
    // 有界量词不判：`(\d{2,4})`、`{1,30}`、`{0,64}` 这类上限写死的形状最坏也只是把
    // 候选数乘上「上限」那么多倍，不是指数。实测推荐规则 #09/#12 里全是这种写法，
    // 把它们判死等于直接破坏功能（compile 返回 null → 规则静默失效）。

    /** 交替分支能取到的字面文本；含量词或嵌套交替则返回 null（无法比较）。 */
    private sealed interface Rx
    private class RxLit(val text: String) : Rx
    private class RxSeq(val items: List<Rx>) : Rx
    private class RxAlt(val alts: List<Rx>) : Rx
    private class RxGrp(val inner: Rx) : Rx
    private class RxRep(val inner: Rx, val unbounded: Boolean) : Rx

    /** `{n,m}` 的 m 超过这个值就算无界（写在正则里的人不会真想要 1000 层嵌套）。 */
    private const val MAX_SAFE_REPEAT = 1000

    private val QUANT_BODY = Regex("""\d+(,\d*)?""")

    /** 正则体的形状是否属于灾难性回溯。纯函数，边界见 test/EngineTest.kt。 */
    fun isSuspicious(pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        val cur = Cur(pattern)
        val tree = parseAlt(cur)
        return dangerous(tree)
    }

    /**
     * 在 JS 源码里找出可疑的正则字面量，返回形如 `/^(a+)+/` 的片段（最多调用方自取）。
     *
     * 为什么要在源码层扫：脚本里的 `/re/` 由 Rhino 交给 java.util.regex 一次跑完，
     * **不经过 `observeInstructionCount`**，超时中断对它完全无效。
     * 不能在执行中被掐断，就只能在执行前拒掉。
     *
     * 词法判定：引号与注释整段跳过，`/` 前一个记号是操作数（标识符、`)`、`]`）时按除法处理，
     * 否则按正则字面量处理 —— 与 Es5Compat.literalEnd 同一套启发式。
     */
    fun suspectPatterns(code: String): List<String> {
        if (code.isEmpty()) return emptyList()
        val bad = mutableListOf<String>()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\'' || c == '"' || c == '`' -> i = skipString(code, i)
                c == '/' && i + 1 < code.length && code[i + 1] == '/' -> i = skipLine(code, i)
                c == '/' && i + 1 < code.length && code[i + 1] == '*' -> i = skipBlock(code, i)
                c == '/' && regexAllowed(code, i) -> {
                    val closeSlash = regexLiteralEnd(code, i)
                    if (closeSlash > i) {
                        val body = code.substring(i + 1, closeSlash)
                        if (isSuspicious(body)) bad += "/" + body.take(60) + "/"
                        var k = closeSlash + 1
                        while (k < code.length && code[k].isLetter()) k++   // 词尾标志 gimsuy
                        i = k
                    } else i++
                }
                else -> i++
            }
        }
        return bad
    }

    // ── 解析 ──

    private class Cur(val s: String) { var i = 0 }

    /** 一个位置的游标：交替 → `|` 分隔的若干序列。 */
    private fun parseAlt(c: Cur): Rx {
        val alts = mutableListOf(parseSeq(c))
        while (c.i < c.s.length && c.s[c.i] == '|') { c.i++; alts += parseSeq(c) }
        return if (alts.size == 1) alts[0] else RxAlt(alts)
    }

    /** 一个序列：原子 + 可选量词，遇到 `|` 或 `)` 或结尾停下。 */
    private fun parseSeq(c: Cur): Rx {
        val items = mutableListOf<Rx>()
        while (c.i < c.s.length) {
            val ch = c.s[c.i]
            if (ch == '|' || ch == ')') break
            var atom: Rx = when (ch) {
                '(' -> {
                    c.i++
                    skipGroupPrefix(c)
                    val inner = parseAlt(c)
                    if (c.i < c.s.length && c.s[c.i] == ')') c.i++
                    RxGrp(inner)
                }
                '[' -> RxLit(charClass(c))
                '\\' -> RxLit(escapeAtom(c))
                else -> { c.i++; RxLit(ch.toString()) }
            }
            val q = readQuantifier(c)
            if (q != null) atom = RxRep(atom, q)
            items += atom
        }
        return RxSeq(items)
    }

    /** `(?:` `(?=` `(?!` `(?<=` `(?<!` `(?<name>` 都不是要判的组，只吃掉前缀。 */
    private fun skipGroupPrefix(c: Cur) {
        val s = c.s
        if (c.i >= s.length || s[c.i] != '?') return
        if (c.i + 1 < s.length && (s[c.i + 1] == ':' || s[c.i + 1] == '=' || s[c.i + 1] == '!')) {
            c.i += 2; return
        }
        if (c.i + 2 < s.length && s[c.i + 1] == '<' && (s[c.i + 2] == '=' || s[c.i + 2] == '!')) {
            c.i += 3; return
        }
        if (c.i + 1 < s.length && s[c.i + 1] == '<') {
            while (c.i < s.length && s[c.i] != '>') c.i++
            if (c.i < s.length) c.i++
            return
        }
        c.i++   // 只吃 `?`，后面照常解析（`(?i)` 这类内联标志）
    }

    /** 消费整个字符类 `[...]`，返回原文。类里的 `+ * ( )` 都是字面量。 */
    private fun charClass(c: Cur): String {
        val s = c.s
        val start = c.i
        var k = c.i + 1
        if (k < s.length && s[k] == '^') k++
        if (k < s.length && s[k] == ']') k++
        while (k < s.length && s[k] != ']') {
            if (s[k] == '\\') k++
            k++
        }
        c.i = if (k < s.length) k + 1 else s.length
        return s.substring(start, c.i)
    }

    /** 消费一个转义（`\d`、`\.`、`\Q..\E`），返回原文。 */
    private fun escapeAtom(c: Cur): String {
        val s = c.s
        val start = c.i
        c.i += if (c.i + 1 < s.length) 2 else 1
        return s.substring(start, c.i)
    }

    /** 读一个量词，返回「是否无界」；没有量词返回 null。惰性/占有修饰一并吃掉。 */
    private fun readQuantifier(c: Cur): Boolean? {
        val s = c.s
        if (c.i >= s.length) return null
        var found = false
        var unbounded = false
        when (s[c.i]) {
            '*' , '+' -> { unbounded = true; found = true; c.i++ }
            '?' -> { found = true; c.i++ }
            '{' -> {
                val close = s.indexOf('}', c.i)
                if (close < 0) return null
                val body = s.substring(c.i + 1, close)
                if (!QUANT_BODY.matches(body)) return null
                val comma = body.indexOf(',')
                unbounded = if (comma < 0) false else {
                    val max = body.substring(comma + 1)
                    max.isEmpty() || (max.toIntOrNull() ?: 0) > MAX_SAFE_REPEAT
                }
                found = true
                c.i = close + 1
            }
        }
        if (!found) return null
        while (c.i < s.length && (s[c.i] == '?' || s[c.i] == '+')) c.i++
        return unbounded
    }

    // ── 形状判定 ──

    /** 子树里有没有无界量词（任意深度）。 */
    /**
     * 组内**边界上**有没有无界量词（开头或结尾那一个）。
     *
     * 为什么要限定「边界」：`(?:[\w…]|\b[%]|‘\w+’|《\w+》)+` 这种形状里
     * `\w+` 被引号夹在中间，拆分点由字面引号定死，不会指数回溯。真的会炸的是
     * `(a+)+`、`(\w+\s?)*` 这一类：无界量词恰好贴在组的开头或结尾，
     * 每次迭代怎么切都是合法的，才能组合爆炸。
     */
    private fun boundaryUnbounded(n: Rx): Boolean = when (n) {
        is RxRep -> n.unbounded
        is RxSeq -> if (n.items.isEmpty()) false
            else boundaryUnbounded(n.items.first()) || boundaryUnbounded(n.items.last())
        is RxAlt -> n.alts.any { boundaryUnbounded(it) }
        is RxGrp -> boundaryUnbounded(n.inner)
        is RxLit -> false
    }

    /** 子树里有没有「被无界量词套住的重叠交替」。 */
    private fun dangerous(n: Rx): Boolean = when (n) {
        is RxRep -> (n.unbounded && (boundaryUnbounded(n.inner) || overlapping(n.inner))) ||
            dangerous(n.inner)
        is RxSeq -> n.items.any { dangerous(it) }
        is RxAlt -> n.alts.any { dangerous(it) }
        is RxGrp -> dangerous(n.inner)
        is RxLit -> false
    }

    /** `(a|a)` `(a|ab)` 这类：任一分支是另一分支的前缀。 */
    private fun overlapping(n: Rx): Boolean = when (n) {
        is RxAlt -> altsOverlap(n.alts)
        is RxGrp -> overlapping(n.inner)
        is RxSeq -> n.items.any { overlapping(it) }
        is RxRep -> false   // 已被外层「组内有无界量词」规则覆盖
        is RxLit -> false
    }

    private fun altsOverlap(alts: List<Rx>): Boolean {
        if (alts.size < 2) return false
        val texts = alts.map { litText(it) }
        for (x in texts.indices) {
            for (y in x + 1 until texts.size) {
                val a = texts[x] ?: continue
                val b = texts[y] ?: continue
                if (a.isEmpty() || b.isEmpty()) continue
                if (a.startsWith(b) || b.startsWith(a)) return true
            }
        }
        return false
    }

    /** 分支的字面文本；含量词或嵌套交替取不到就返回 null。 */
    private fun litText(n: Rx): String? = when (n) {
        is RxLit -> n.text
        is RxGrp -> litText(n.inner)
        is RxRep -> null
        is RxAlt -> if (n.alts.size == 1) litText(n.alts[0]) else null
        is RxSeq -> {
            val sb = StringBuilder()
            var ok = true
            for (it in n.items) {
                val t = litText(it)
                if (t == null) { ok = false; break }
                sb.append(t)
            }
            if (ok) sb.toString() else null
        }
    }

    // ── JS 源码里的正则字面量词法──

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    /** `/` 前是这些字符时，位置上是表达式的开头 → 按正则处理。 */
    private val REGEX_PRE_CHARS = "(=:[,!&|?;{}>+-*%~^".toSet()

    private val REGEX_PRE_WORDS = setOf(
        "return", "throw", "case", "typeof", "instanceof", "in", "of",
        "new", "delete", "void", "do", "else", "yield", "await",
    )

    private fun regexAllowed(code: String, slash: Int): Boolean {
        var k = slash - 1
        while (k >= 0 && code[k].isWhitespace()) k--
        if (k < 0) return true
        if (code[k] in REGEX_PRE_CHARS) return true
        if (!isIdentChar(code[k])) return false
        var s = k
        while (s >= 0 && isIdentChar(code[s])) s--
        return code.substring(s + 1, k + 1) in REGEX_PRE_WORDS
    }

    /** 返回结尾 `/` 的下标；不成立返回 -1。 */
    private fun regexLiteralEnd(code: String, start: Int): Int {
        var i = start + 1
        var inClass = false
        while (i < code.length) {
            val c = code[i]
            if (c == '\\') { i += 2; continue }
            if (c == '\n' || c == '\r') return -1
            if (c == '[') inClass = true
            else if (c == ']') inClass = false
            else if (c == '/' && !inClass) return i
            i++
        }
        return -1
    }

    private fun skipString(code: String, start: Int): Int {
        val q = code[start]
        var i = start + 1
        while (i < code.length) {
            val c = code[i]
            if (c == '\\') { i += 2; continue }
            if (c == q) return i + 1
            if (q != '`' && (c == '\n' || c == '\r')) return i   // 未闭合的单行串，就地收尾
            i++
        }
        return code.length
    }

    private fun skipLine(code: String, start: Int): Int {
        var i = start
        while (i < code.length && code[i] != '\n') i++
        return i
    }

    private fun skipBlock(code: String, start: Int): Int {
        val e = code.indexOf("*/", start + 2)
        return if (e < 0) code.length else e + 2
    }

    /**
     * 设备端自检：对每条正则，同时跑「裸编译」与「按引擎实际路径编译」。
     *
     * 裸编译失败 = 这条正则本身不兼容 ICU；走 compileLenient 后是否可用才是最终结论。
     * 两者都要报，否则分不清「正则坏了」和「引擎没去坏正则」。
     */
    fun probeLenient(patterns: List<Pair<String, String>>): List<String> {
        val bad = mutableListOf<String>()
        for ((label, p) in patterns) {
            try { java.util.regex.Pattern.compile(p) } catch (e: Throwable) {
                val fixed = compileLenient(p)
                bad += if (fixed == null) "$label：裸编译失败，修补后仍失败"
                       else "$label：裸编译失败，修补后可用（已兜住）"
            }
        }
        return bad
    }

    /**
     * 在设备上自检：把一批正则逐个编译，返回失败的那些。
     *
     * 用途：Android 的 ICU 比 JVM 严格，本地测不出设备上的失败。
     * 这个函数在设备上运行，结果直接反映真实情况。
     */
    fun probe(patterns: List<Pair<String, String>>): List<String> {
        val bad = mutableListOf<String>()
        for ((label, p) in patterns) {
            try {
                java.util.regex.Pattern.compile(p)
            } catch (e: Throwable) {
                bad += label + " | " + (e::class.simpleName ?: "") +
                    " " + (e.message ?: "") + " | 正则=" + p.take(70)
            }
        }
        return bad
    }

    /** 编译失败时退回 [fallback]（默认匹配空白）。 */
    fun compileOr(pattern: String?, fallback: String = "\\s+"): Regex =
        compile(pattern) ?: Regex(fallback)

    /**
     * 把第三方写的正则修成 Android 能接受的写法。
     *
     * 已知两类（均为实测）：
     * 1. **无效转义**：`\` 后跟非 ASCII（`\《`、`\作者`）或非特殊 ASCII 时，
     *    ICU 直接报错，JVM 却默默接受。去掉多余的 `\`。
     * 2. **花括号**：`{{name}}` 会被当量词（`Illegal repetition`）。
     *    转成 `\{` 让其成为字面量。
     */
    fun sanitize(pattern: String): String {
        val sb = StringBuilder(pattern.length + 8)
        var i = 0
        // `\p{...}` / `\P{...}` 里的花括号属于语法，不能转义
        var inProp = false
        while (i < pattern.length) {
            val c = pattern[i]
            if (inProp) {
                if (c == '}') { sb.append('}'); inProp = false; i++; continue }
                sb.append(c); i++; continue
            }
            if (c == '\\' && i + 1 < pattern.length) {
                val n = pattern[i + 1]
                if (n == 'p' || n == 'P') { sb.append(c).append(n); inProp = true; i += 2; continue }
                // 合法转义：ASCII 的元字符，或 \d \w \s 这类字符类
                val ok = n in "\\^$.|?*+()[]{}<>/-\"" || n.isLetterOrDigit() && n.code < 128
                sb.append(if (ok) "\\" else "").append(n)
                i += 2
                continue
            }
            if (c == '{') { sb.append("\\{"); i++; continue }
            if (c == '}') { sb.append("\\}"); i++; continue }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** 先修再编译；修完仍失败返回 null。 */
    fun compileLenient(pattern: String?): Regex? {
        if (pattern.isNullOrEmpty()) return null
        // 1) 先去掉 `{{...}}` 模板占位 —— legado 的模板不是正则语法，
        //    混在正则体里必然报错（`Illegal repetition` / `Syntax error`）。
        //    转义成字面量没有意义：模板在正则里从来不是要匹配的文本，只能丢。
        val noTpl = stripTemplates(pattern)
        if (noTpl.isEmpty()) return neverMatch   // 整条正则就是模板，只能不替换
        // 2) 直接编译 3) 修掉花括号与无效转义 4) 仍不行就丢掉不认的 \p{...} 属性
        return compile(noTpl)
            ?: compile(sanitize(noTpl))
            ?: compileNoProps(noTpl)
    }

    /**
     * 丢掉 `{{...}}` 模板占位。
     *
     * 实测 6 条源的正则体里有模板（`{{chapter.title}}`、`{{sourceUrl}}`、
     * `{{source.key}}`、`{{@@.book_other!0:-1@text}}`），全部导致设备端报错。
     * 模板在正则里的语义是「正则体外的变量」，本引擎不展开它们，
     * 所以整个占位当不存在处理（不是当字面量去匹配）。
     */
    private fun stripTemplates(pattern: String): String =
        pattern.replace(Regex("\\{\\{[^}]*\\}\\}"), "")

    /** 去掉 ICU 不认的 `\p{名字}` 属性；全丢光就退成「永不匹配」。 */
    private fun compileNoProps(pattern: String): Regex? {
        val stripped = sanitize(pattern)
            .replace(Regex("\\\\[pP]\\{[^}]*\\}"), "")
            .replace(Regex("\\\\[pP]"), "")
            .replace(Regex("\\[\\]"), "")
        if (stripped.isEmpty()) return neverMatch
        return compile(stripped)
    }
}

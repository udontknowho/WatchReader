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

    /**
     * 灾难性回溯的典型形状：嵌套量词 `(a+)+`、`(a*)*`、`(a+)*`。
     *
     * Rhino 跑普通正则，Java 的 Matcher 对这种形状是指数回溯 —— 20KB 重复字符
     * 就能把线程钉死，且**不会抛异常**，只能眼看着它转。这里在编译期拒掉。
     */
    private val NESTED_QUANT = Regex("""\([^)]*[+*]\)[+*]""")

    /** 编译失败或形状可疑返回 null，调用方按「没有正则」处理。 */
    fun compile(pattern: String?): Regex? {
        if (pattern.isNullOrEmpty()) return null   // 空模式＝没有正则，不当成"匹配一切"
        if (NESTED_QUANT.containsMatchIn(pattern)) return null
        return runCatching { Regex(pattern) }.getOrElse { null }
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

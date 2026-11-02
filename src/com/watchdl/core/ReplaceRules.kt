package com.watchdl.core

import java.io.File

/**
 * 替换净化：读正文时按规则改写文本（#28）。
 *
 * 字段名照 legado 的 `replaceRule`，导出的文件两种应用能互相读。
 * 只实现「正则替换 / 字面替换」这一层，不做 legado 的那套正则 DSL ——
 * 手表上没人会写 `{{}}` 与 `$1` 混排的规则，能删广告尾巴就够用。
 */
class ReplaceRule(
    val name: String,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean,
    /** `text` 只正文 / `title` 只标题 / `both` 两处都过。 */
    val scope: String,
    val enabled: Boolean,
)

object ReplaceRuleStore {

    /** 落盘位置。Activity 启动时指到 `filesDir/replace.json`，和 [BookmarkStore.file] 一致。 */
    @Volatile
    var file: File? = null

    /** scope 的合法取值。认不出的按 `text` 处理，见 [importJson]。 */
    private val SCOPES = setOf("text", "title", "both")

    /** 编译结果缓存：pattern → 编译好的正则，失败存 null。 */
    private val regexCache = HashMap<String, Regex?>()

    /** 解析结果缓存。[cachedAt] 记住它是从哪个文件读的，换了文件就作废。 */
    @Volatile
    private var cached: List<ReplaceRule>? = null
    @Volatile
    private var cachedAt: File? = null

    /**
     * 内置默认表。
     *
     * 只放两条真见过的：盗版站的广告尾巴和求票行。别的站内文字不猜 ——
     * 规则猜错是**删正文**，用户很难定位是哪一条干的，默认表越短越安全。
     * 两条都能在页面上关掉。
     */
    fun defaults(): List<ReplaceRule> = listOf(
        ReplaceRule(
            name = "去掉「本章未完/请记住本站」尾巴",
            // 连行尾换行一起吃掉：只把正文换成空串会留下一整行空行，
            // 翻几章就攒出一大段空白。
            pattern = "(?m)^.*(本章未完|请记住本站|最快更新).*\\n?",
            replacement = "",
            isRegex = true,
            scope = "text",
            enabled = true,
        ),
        ReplaceRule(
            name = "去掉求票行",
            pattern = "(?m)^.*(求月票|求推荐票|投推荐票).*\\n?",
            replacement = "",
            isRegex = true,
            scope = "text",
            enabled = true,
        ),
    )

    /**
     * 当前规则表。
     *
     * 读不到文件、或存档解析不出来，都退回默认表：功能默认可用，
     * 而不是文件一坏就悄悄失效。空表也当解析失败 —— 没有规则等于没配。
     */
    fun list(): List<ReplaceRule> {
        val f = file
        cached?.let { if (cachedAt == f) return it }
        val parsed = if (f != null && f.isFile) {
            // 存档坏了不能让正文读不出来：解析失败就当没有自定义规则。
            runCatching { parse(f.readText(Charsets.UTF_8)) }.getOrNull()
        } else null
        val out = if (parsed.isNullOrEmpty()) defaults() else parsed
        cached = out
        cachedAt = f
        return out
    }

    /** 同步落盘。进程随时可能被杀，异步写会丢。 */
    fun save(items: List<ReplaceRule>) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(dump(items), Charsets.UTF_8)
            // 规则变了，缓存（含编译结果）必须一起作废，否则改完还是老行为。
            cached = items
            cachedAt = f
            synchronized(regexCache) { regexCache.clear() }
        }
    }

    /**
     * 从 legado 同结构的 json 数组导入。返**新增**条数，解析不了返回 -1。
     *
     * 按「名称 + 模式」去重：同一份文件导两次不该把列表翻倍。
     * 导入后立刻落盘 —— 用户点「导入」看到条数，就该已经生效。
     */
    fun importJson(raw: String): Int {
        val objs = objectsOf(raw)
        if (objs.isEmpty()) return -1
        val kept = list().toMutableList()
        var added = 0
        for (obj in objs) {
            val pattern = str(obj, "pattern")
            if (pattern.isNullOrBlank()) continue
            val name = str(obj, "name")?.takeIf { it.isNotBlank() } ?: pattern
            if (kept.any { it.name == name && it.pattern == pattern }) continue
            kept += ReplaceRule(
                name = name,
                pattern = pattern,
                replacement = str(obj, "replacement") ?: "",
                // 缺字段按「字面替换」处理：把字面串当正则跑会多删东西，错的一侧选保守的。
                isRegex = bool(obj, "isRegex", false),
                // 认不出的 scope 也只给正文：动标题会让目录丢章，风险高的一侧不默认。
                scope = str(obj, "scope")?.takeIf { it in SCOPES } ?: "text",
                enabled = bool(obj, "enabled", true),
            )
            added++
        }
        save(kept)
        return added
    }

    /**
     * 逐条应用。scope 不匹配就跳过，正则编译失败也当这条不存在。
     *
     * 每翻一页都要跑一遍全表，所以列表与编译结果都缓存；
     * 只有 [save] 会清缓存，别处不许直接改 replace.json。
     */
    fun apply(text: String, isTitle: Boolean): String {
        if (text.isEmpty()) return text
        var out = text
        for (r in list()) {
            if (!r.enabled) continue
            val scopeOk = when (r.scope) {
                "title" -> isTitle
                "both" -> true
                else -> !isTitle
            }
            if (!scopeOk) continue
            out = if (r.isRegex) {
                val re = compiled(r.pattern) ?: continue
                re.replace(out, r.replacement)
            } else {
                out.replace(r.pattern, r.replacement)
            }
        }
        return out
    }

    /**
     * 编译缓存。
     *
     * 走 [SafeRegex] 而不是裸 `Regex`：规则由第三方文件写进来，
     * Android 的 ICU 后端会拒掉 JVM 上能编译的写法。编译失败也缓存 null，
     * 免得每章都去重编译一条注定失败的正则。
     */
    private fun compiled(pattern: String): Regex? = synchronized(regexCache) {
        if (regexCache.containsKey(pattern)) return regexCache[pattern]
        val re = SafeRegex.compile(pattern)
        regexCache[pattern] = re
        re
    }

    // ── json ──────────────────────────────────────────
    // 存**裸数组**：legado 导出的 replaceRule 就是这个形状。
    // 落盘格式与「可导入格式」合成一种，省掉两套互相转换。

    private fun dump(items: List<ReplaceRule>): String = buildString {
        append('[')
        items.forEachIndexed { i, r ->
            if (i > 0) append(',')
            append('{')
            append("\"name\":\"").append(esc(r.name)).append("\",")
            append("\"pattern\":\"").append(esc(r.pattern)).append("\",")
            append("\"replacement\":\"").append(esc(r.replacement)).append("\",")
            append("\"isRegex\":").append(r.isRegex).append(',')
            append("\"scope\":\"").append(esc(r.scope)).append("\",")
            append("\"enabled\":").append(r.enabled)
            append('}')
        }
        append(']')
    }

    private fun parse(text: String): List<ReplaceRule> =
        objectsOf(text).mapNotNull { obj ->
            val pattern = str(obj, "pattern") ?: return@mapNotNull null
            if (pattern.isBlank()) return@mapNotNull null
            ReplaceRule(
                name = str(obj, "name")?.takeIf { it.isNotBlank() } ?: pattern,
                pattern = pattern,
                replacement = str(obj, "replacement") ?: "",
                isRegex = bool(obj, "isRegex", false),
                scope = str(obj, "scope")?.takeIf { it in SCOPES } ?: "text",
                enabled = bool(obj, "enabled", true),
            )
        }

    /** 裸数组与 `{"list":[...]}` 两种形状都认：外部文件可能包了一层。 */
    private fun objectsOf(raw: String): List<String> =
        TinyJson.objectItems(raw.trim()).ifEmpty { TinyJson.objects(raw, "list") }

    private fun str(obj: String, key: String): String? =
        TinyJson.field(obj, key)?.let {
            if (it.startsWith("\"")) TinyJson.unescape(it.trim('"')) else it
        }

    private fun bool(obj: String, key: String, fallback: Boolean): Boolean =
        when (str(obj, key)?.trim()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> fallback
        }

    /** 只转义必须的几个字符：引号、反斜杠、换行、回车、制表。 */
    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(' ') else append(c)
        }
    }
}

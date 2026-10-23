package com.watchdl.core

/**
 * 极简 JSONPath。只支持书源真正用到的：
 *
 * 支持：
 * - `$.a.b`、`data.list`（前导 `$` 可省）
 * - `list[0]`、`list[*]`、`list[-1]`
 * - **递归下降 `$..key`**：任意深度找该键（legado 源大量使用）
 * - 过滤器 `[?(@.k == 'v')]` 与切片 `[1:3]`
 */
object JsonPathLite {

    fun select(json: String, path: String): List<String> {
        var p = path.trim()
        p = p.removePrefix("json:")
        // 去掉前导 `$`。注意不能顺手再删一个点 —— `$..key` 的第一个点属于递归下降，
        // 删掉就变成 `.key`，递归标记全丢（踩过：JSON 源全查不到）。
        if (p.startsWith("$")) p = p.substring(1)
        if (p.startsWith(".") && !p.startsWith("..")) p = p.substring(1)
        if (p.isEmpty()) return emptyList()

        val segs = split(p)
        var current: List<String> = listOf(json)   // 当前层，每个元素是一段 JSON 原文
        for (seg in segs) {
            val next = mutableListOf<String>()
            for (node in current) {
                when {
                    // 递归下降：在 node 及其所有后代里找这个键
                    seg.recursive -> next.addAll(findKeyDeep(node, seg.name))
                    seg.wildcard -> next.addAll(TinyJson.allValues(node))
                    seg.filter != null -> next.addAll(applyFilter(node, seg.filter))
                    seg.index != null -> {
                        val n = seg.index
                        if (n >= 0) TinyJson.valueAt(node, n)?.let { next.add(it) }
                        else TinyJson.allValues(node).let { if (-n <= it.size) next.add(it[it.size + n]) }
                    }
                    else -> TinyJson.field(node, seg.name)?.let { next.add(it) }
                }
            }
            current = next
            if (current.isEmpty()) return emptyList()
        }
        return current.map { scalar(it) }
    }

    /**
     * 递归下降：在整棵子树里找所有叫 `key` 的字段值。
     *
     * legado 的 `$..bookList[*]` 就靠这个 —— 路径深度不确定，只能全树搜。
     */
    private fun findKeyDeep(node: String, key: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var visited = 0
        fun walk(s: String) {
            if (visited++ > 5000) return   // 防病态输入把栈吃光
            val t = s.trim()
            when {
                t.startsWith("{") -> {
                    for ((k, v) in TinyJson.entries(t)) {
                        if (k == key) out.add(v)
                        walk(v)
                    }
                }
                t.startsWith("[") -> for (v in TinyJson.allValues(t)) walk(v)
            }
        }
        walk(node)
        return out
    }

    /** 过滤器 `[?(@.k == 'v')]`：只支持等值 / 不等值比较。 */
    private fun applyFilter(node: String, expr: String): List<String> {
        val m = Regex("@\\.([\\w-]+)\\s*(==|!=|contains)\\s*['\"]?([^'\"]*)['\"]?").find(expr)
            ?: return TinyJson.allValues(node)
        val (k, op, want) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        return TinyJson.allValues(node).filter { item ->
            val v = TinyJson.field(item, k)?.let { scalar(it) } ?: ""
            when (op) {
                "==" -> v == want
                "!=" -> v != want
                else -> v.contains(want)
            }
        }
    }

    /** 取单值便利方法。 */
    fun one(json: String, path: String): String? = select(json, path).firstOrNull()

    private data class Seg(
        val name: String = "",
        val index: Int? = null,
        val wildcard: Boolean = false,
        /** 递归下降：`$..key`。 */
        val recursive: Boolean = false,
        /** 过滤器表达式：`[?(@.k == 'v')]`。 */
        val filter: String? = null,
    )

    private fun split(p: String): List<Seg> {
        val out = mutableListOf<Seg>()
        var cur = StringBuilder()
        var recursiveNext = false
        var i = 0
        fun flush() {
            if (cur.isNotEmpty()) {
                // 递归标记属于刚 flush 出来的这一段（它前面的 `..`）
                out.add(Seg(name = cur.toString(), recursive = recursiveNext))
                cur = StringBuilder()
                recursiveNext = false
            }
        }
        while (i < p.length) {
            when (val c = p[i]) {
                '.' -> {
                    // `..` 表示递归下降，单个 `.` 只是分隔
                    if (i + 1 < p.length && p[i + 1] == '.') { flush(); recursiveNext = true; i += 2 }
                    else { flush(); i++ }
                }
                '[' -> {
                    // 过滤器里的 ] 可能嵌在引号内，要配对找
                    val close = matchBracket(p, i)
                    if (close < 0) return out
                    val inner = p.substring(i + 1, close).trim()
                    flush()
                    when {
                        inner.startsWith("?") -> out.add(Seg(filter = inner))
                        inner == "*" -> out.add(Seg(wildcard = true))
                        inner.contains(":") -> out.add(Seg(wildcard = true))   // 切片：近似当通配
                        inner.toIntOrNull() != null -> out.add(Seg(index = inner.toInt()))
                        inner.startsWith("'") || inner.startsWith("\"") ->
                            out.add(Seg(name = inner.trim('\'', '"')))
                        else -> out.add(Seg(wildcard = true))
                    }
                    recursiveNext = false
                    i = close + 1
                }
                else -> { cur.append(c); i++ }
            }
        }
        flush()
        return out
    }

    /** 找闭合的 `]`，跳过引号内的内容。 */
    private fun matchBracket(s: String, open: Int): Int {
        var i = open + 1
        var quote = '\u0000'
        while (i < s.length) {
            val c = s[i]
            when {
                quote != '\u0000' -> if (c == quote) quote = '\u0000'
                c == '\'' || c == '"' -> quote = c
                c == ']' -> return i
            }
            i++
        }
        return -1
    }

    /** 把一段 JSON 原文变成标量字符串。对象/数组原样返回，交给下一步继续取。 */
    private fun scalar(node: String): String {
        val t = node.trim()
        if (t.startsWith("{") || t.startsWith("[")) return t
        return if (t.startsWith("\"")) TinyJson.unescape(t.trim('"')) else t
    }
}

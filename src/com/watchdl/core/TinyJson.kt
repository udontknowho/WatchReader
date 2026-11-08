package com.watchdl.core

/**
 * 极简 JSON 取值。只做「按点号路径取标量」，够番茄适配器用。
 *
 * 手表端不引 JSON 库：Android 自带 org.json，但核心层要能在纯 JVM 下自检，
 * 所以这里只写取字符串/数字所需的最小实现。
 */
object TinyJson {

    /** 取 `data.ret_data[0].book_id` 这类路径的值，字符串去引号，找不到返回 null。 */
    fun path(json: String, path: String): String? {
        val cursor = pathOffset(json, path)
        return if (cursor < 0) null else readScalar(json, skipWs(json, cursor))
    }

    /** 同 [path]，但返回值所在的字符偏移；找不到返回 -1。 */
    private fun pathOffset(json: String, path: String): Int {
        var cursor = skipWs(json, 0)
        for (seg in splitPath(path)) {
            cursor = skipWs(json, cursor)
            cursor = when {
                seg.startsWith("[") && seg.endsWith("]") ->
                    descendIndex(json, cursor, seg.substring(1, seg.length - 1).toIntOrNull() ?: return -1)
                else -> descendKey(json, cursor, seg)
            }
            if (cursor < 0) return -1
        }
        return cursor
    }

    /**
     * 取数组里的各个对象原文（每项一个 JSON 对象的字符串）。
     *
     * 给「chapterList 脚本返回对象数组」用：对象原文当一个项作用域，
     * 后续 `$.n` / `$.u` 在它上面取值，正是 legado 的语义。
     * 解析成不了数组时返回空表，调用方退回到整串当标量。
     */
    fun objectItems(array: String): List<String> {
        val s = skipWs(array, 0)
        if (s >= array.length || array[s] != '[') return emptyList()
        val out = mutableListOf<String>()
        var i = skipWs(array, s + 1)
        while (i < array.length && array[i] != ']') {
            if (array[i] != '{') return emptyList()
            val end = objectEnd(array, i)
            if (end < 0) return emptyList()
            out.add(array.substring(i, end + 1))
            i = skipWs(array, end + 1)
            if (i < array.length && array[i] == ',') i = skipWs(array, i + 1)
        }
        return out
    }

    /**
     * 找键的位置，**跳过字符串值内部**。
     *
     * 裸 `indexOf("\"key\"")` 会命中字符串值里的同名片段：
     * `{"items":"[0]","data":{"items":[{"a":1}]}}` 取 `objects(body,"items")`
     * 会把字符串值当数组起点，返回空表。`"page":"[1,2]"` 这类字段真实接口很常见。
     */
    private fun keyOffset(json: String, key: String): Int {
        val needle = "\"$key\""
        var i = 0
        while (i < json.length) {
            when (json[i]) {
                '"' -> {
                    val end = stringEnd(json, i)
                    if (end < 0) return -1
                    // 正好是完整的键（后面跟冒号）才算。stringEnd 返回的是结尾引号的下标，
                    // 所以闭区间终点是 i + needle.length - 1。
                    if (json.startsWith(needle, i) && i + needle.length - 1 == end) {
                        var j = end + 1
                        while (j < json.length && json[j].isWhitespace()) j++
                        if (j < json.length && json[j] == ':') return i
                    }
                    i = end + 1
                }
                else -> i++
            }
        }
        return -1
    }

    /**
     * 扫出某个键下面的所有 JSON 对象（穿透分卷等多层嵌套），返回每个对象的原文。
     * `chapterListWithVolume` 是 `[[{…},{…}],[…]]`，需要逐层铺平。
     */
    fun objects(json: String, key: String): List<String> {
        val at = keyOffset(json, key)
        if (at < 0) return emptyList()
        val open = json.indexOf('[', at)
        if (open < 0) return emptyList()
        val out = mutableListOf<String>()
        // 按**外层数组深度**收尾，不能见到第一个 `]` 就收：
        // `chapterListWithVolume` 是 `[[{…},{…}],[{…}]]`，内层每卷都收一个 `]`，
        // 以前第一卷完就 return，2500 章只抽出第一卷。
        var depth = 1
        var i = open + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '{') {
                val end = objectEnd(json, i)
                if (end < 0) return out
                out.add(json.substring(i, end + 1))
                i = end + 1
                continue
            }
            if (c == '"') {
                // 字符串里的 `]` 不能算数组结尾。
                val end = stringEnd(json, i)
                if (end < 0) return out
                i = end + 1
                continue
            }
            if (c == '[') depth++
            else if (c == ']') {
                depth--
                if (depth == 0) return out
            }
            i++
        }
        return out
    }

    /**
     * 取数组里所有对象的某个字段，用于搜索结果的列表提取。
     *
     * 以前的版本用外层深度计数，遇到 `[[{…},{…}],[…]]` 这种双层嵌套只认得第一层，
     * 2500 章只抽出 75 章。现在改为把所有顶层对象都扫一遍，只看对象的直接字段。
     */
    fun arrayField(json: String, arrayPath: String, field: String): List<String> =
        arrayFieldOrThrow(json, arrayPath, field) ?: emptyList()

    /**
     * 同 [arrayField]，但**区分「路径不存在」与「空数组」**。
     *
     * S18：站点改字段名后 `arrayField` 返回空表，上层判成「今天没榜」，
     * 界面显示「暂无推荐」，与真的没数据无从区分。
     * 路径缺失返回 null（调用方据此判定「接口变了」），空数组返回空表。
     */
    fun arrayFieldOrThrow(json: String, arrayPath: String, field: String): List<String>? {
        // 走路径拿**偏移**，不要拿值段再 indexOf 回来 ——
        // 那样会命中同名键，拿到别的数组。
        val at = pathOffset(json, arrayPath)
        if (at < 0) return null
        val open = skipWs(json, at)
        if (open >= json.length || json[open] != '[') return null
        val out = mutableListOf<String>()
        var i = open + 1
        // 每个分支自己推进游标（i = end + 1）。以前统一在循环底部 i++，
        // 那个 i++ 会把紧跟在对象后的 `]` 跳过去 —— 补上的 `']'` 分支永远命中不了。
        while (i < json.length) {
            val c = json[i]
            if (c == '{') {
                val end = objectEnd(json, i)
                if (end < 0) return out
                path(json.substring(i, end + 1), field)?.let { out.add(it) }
                i = end + 1
                continue
            }
            if (c == '"' || c == '\'') {
                // 纯字符串数组：也当成值收下
                val end = stringEnd(json, i)
                if (end < 0) return out
                if (field == "") out.add(unescape(json.substring(i + 1, end)))
                i = end + 1
                continue
            }
            // 数组结束就停。没有这一支会越过数组末尾，
            // 把整篇 JSON 里后面每一个对象都收进来（只取一个数组却拿到全页的字段）。
            if (c == ']') return out
            i++
        }
        return out
    }

    /** 取某个键的原始值段（对象/数组不展开）。 */
    fun field(json: String, key: String): String? {
        val at = descendKey(json, skipWs(json, 0), key)
        if (at < 0) return null
        val s = skipWs(json, at)
        if (s >= json.length) return null
        return when (json[s]) {
            '{' -> objectEnd(json, s)?.let { json.substring(s, it + 1) }
            '[' -> { val e = arrayEnd(json, s); if (e < 0) null else json.substring(s, e + 1) }
            '"' -> stringEnd(json, s).let { if (it < 0) null else json.substring(s, it + 1) }
            else -> readScalar(json, s)
        }
    }

    /** 取数组第 n 项的原始值段。 */
    fun valueAt(json: String, n: Int): String? {
        val s = skipWs(json, 0)
        if (s >= json.length || json[s] != '[') return null
        var i = skipWs(json, s + 1)
        var count = 0
        while (i < json.length) {
            val c = json[i]
            if (c == ']') return null
            if (count == n) {
                return when (c) {
                    '{' -> objectEnd(json, i)?.let { json.substring(i, it + 1) }
                    '[' -> { val e = arrayEnd(json, i); if (e < 0) null else json.substring(i, e + 1) }
                    '"' -> stringEnd(json, i).let { if (it < 0) null else json.substring(i, it + 1) }
                    else -> readScalar(json, i)
                }
            }
            // 跳过当前项
            when (c) {
                '{' -> { val e = objectEnd(json, i) ?: return null; i = e + 1 }
                '[' -> { val e = arrayEnd(json, i); if (e < 0) return null; i = e + 1 }
                '"' -> { val e = stringEnd(json, i); if (e < 0) return null; i = e + 1 }
                else -> { while (i < json.length && json[i] !in ",]") i++ }
            }
            if (i < json.length && json[i] == ',') { count++; i = skipWs(json, i + 1) }
            else return null
        }
        return null
    }

    /** 取数组或对象的全部子项原文。 */
    fun allValues(json: String): List<String> {
        val s = skipWs(json, 0)
        val out = mutableListOf<String>()
        when {
            s < json.length && json[s] == '[' -> {
                var i = skipWs(json, s + 1)
                while (i < json.length && json[i] != ']') {
                    val end = when (json[i]) {
                        '{' -> objectEnd(json, i).takeIf { it > 0 }?.plus(1)
                        '[' -> arrayEnd(json, i).takeIf { it > 0 }?.plus(1)
                        '"' -> stringEnd(json, i).takeIf { it > 0 }?.plus(1)
                        else -> { var j = i; while (j < json.length && json[j] !in ",]") j++; j }
                    } ?: return out
                    out.add(json.substring(i, end))
                    i = skipWs(json, end)
                    if (i < json.length && json[i] == ',') i = skipWs(json, i + 1)
                }
            }
            s < json.length && json[s] == '{' -> {
                var i = s + 1
                while (i < json.length && json[i] != '}') {
                    if (json[i] == '"') {
                        val ke = stringEnd(json, i); if (ke < 0) return out
                        var j = skipWs(json, ke + 1)
                        if (j >= json.length || json[j] != ':') return out
                        j = skipWs(json, j + 1)
                        val end = when {
                            j < json.length && json[j] == '{' -> objectEnd(json, j).takeIf { it > 0 }?.plus(1)
                            j < json.length && json[j] == '[' -> arrayEnd(json, j).takeIf { it > 0 }?.plus(1)
                            j < json.length && json[j] == '"' -> stringEnd(json, j).takeIf { it > 0 }?.plus(1)
                            else -> { var k = j; while (k < json.length && json[k] !in ",}") k++; k }
                        } ?: return out
                        out.add(json.substring(j, end))
                        i = skipWs(json, end)
                        if (i < json.length && json[i] == ',') i = skipWs(json, i + 1)
                    } else i++
                }
            }
        }
        return out
    }

    /**
     * 遍历一个 JSON 对象的所有直接字段，返回「键 → 值原文」。
     * 递归下降（`$..key`）靠它逐层下钻。
     */
    fun entries(obj: String): List<Pair<String, String>> {
        val s = obj.trim()
        if (s.isEmpty() || s[0] != '{') return emptyList()
        val out = ArrayList<Pair<String, String>>(8)
        var i = s.trimStart().let { if (it.startsWith("{")) 1 else return emptyList() }
        while (i < s.length) {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= s.length || s[i] == '}') break
            if (s[i] != '"') { i++; continue }
            val ke = stringEnd(s, i)
            if (ke < 0) break
            val key = unescape(s.substring(i + 1, ke))
            var j = ke + 1
            while (j < s.length && s[j].isWhitespace()) j++
            if (j >= s.length || s[j] != ':') break
            j++
            while (j < s.length && s[j].isWhitespace()) j++
            if (j >= s.length) break
            val end = when (s[j]) {
                '{' -> objectEnd(s, j).takeIf { it > 0 }?.plus(1)
                '[' -> arrayEnd(s, j).takeIf { it > 0 }?.plus(1)
                '"' -> stringEnd(s, j).takeIf { it > 0 }?.plus(1)
                else -> { var k = j; while (k < s.length && s[k] !in ",}") k++; k }
            } ?: break
            out.add(key to s.substring(j, end))
            i = end
        }
        return out
    }

    /** 返回以 `{` 开头的对象的收尾 `}` 下标；不配平则返回 -1。字符串内部的括号不计。 */
    private fun objectEnd(s: String, openAt: Int): Int {
        var depth = 0
        var i = openAt
        while (i < s.length) {
            when (s[i]) {
                '"' -> { i = stringEnd(s, i); if (i < 0) return -1 }
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0 && s[i] == '}') return i
                    if (depth < 0) return -1
                }
            }
            i++
        }
        return -1
    }

    /** 把 `data.ret_data[0].book_id` 切成 ["data", "ret_data", "[0]", "book_id"]。
     *  下标段带方括号标记，否则会被当成对象键去找。 */
    /**
     * 取纯字符串数组的所有值。`arrayField` 只认对象数组，`allItemIds` 是字符串数组，走这里。
     */
    fun stringArray(json: String, key: String): List<String> {
        val at = json.indexOf("\"$key\"")
        if (at < 0) return emptyList()
        val open = json.indexOf('[', at)
        if (open < 0) return emptyList()
        var i = open + 1
        var depth = 0
        val out = mutableListOf<String>()
        while (i < json.length) {
            when (json[i]) {
                '"' -> {
                    val end = stringEnd(json, i)
                    if (end < 0) return out
                    if (depth == 0) out.add(unescape(json.substring(i + 1, end)))
                    i = end
                }
                '[', '{' -> depth++
                '}', ']' -> if (depth == 0) return out else depth--
            }
            i++
        }
        return out
    }

    /**
     * 从 HTML 里抽取最大的一段 content 字段。
     *
     * 不用正则：`((?:[^"\\]|\\.)*)` 这种模式在 20 KB 以上的正文上会回溯爆栈
     * （实测 StackOverflowError，手表上栈更小更危险）。改用手写线性扫描。
     *
     * @param minLength 小于此长度的 content 视为噪声（页面里常有别的小 content 字段）
     * @param allowEmpty 允许返回空串（正文就是空的情况）
     */
    fun extractContent(html: String, minLength: Int = 200, allowEmpty: Boolean = false): String? {
        var best: String? = null
        var from = 0
        while (true) {
            val key = html.indexOf("\"content\"", from)
            if (key < 0) break
            var i = key + "\"content\"".length
            i = skipWsLocal(html, i)
            if (i >= html.length || html[i] != ':') { from = key + 1; continue }
            i = skipWsLocal(html, i + 1)
            if (i >= html.length || html[i] != '"') { from = key + 1; continue }
            // 从引号起线性扫到未转义的收尾引号
            val end = stringLiteralEnd(html, i)
            if (end < 0) { from = key + 1; continue }
            val value = unescape(html.substring(i + 1, end))
            if (allowEmpty || value.length >= minLength) {
                if (best == null || value.length > best!!.length) best = value
            }
            from = end + 1
        }
        return best
    }

    /** 返回以 `[` 开头的数组的收尾 `]` 下标；不配平则返回 -1。 */
    fun arrayEnd(s: String, openAt: Int): Int {
        var depth = 0
        var i = openAt
        while (i < s.length) {
            when (s[i]) {
                '"' -> { i = stringEnd(s, i); if (i < 0) return -1 }
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0 && s[i] == ']') return i
                    if (depth < 0) return -1
                }
            }
            i++
        }
        return -1
    }

    private fun skipWsLocal(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** 返回字符串字面量收尾引号的下标；未闭合返回 -1。 */
    private fun stringLiteralEnd(s: String, quoteAt: Int): Int {
        var i = quoteAt + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return -1
    }

    private fun splitPath(path: String): List<String> {
        val segs = mutableListOf<String>()
        var cur = StringBuilder()
        var inIndex = false
        for (ch in path) {
            when (ch) {
                '.' -> {
                    if (cur.isNotEmpty()) { segs.add(cur.toString()); cur = StringBuilder() }
                    inIndex = false
                }
                '[' -> {
                    if (cur.isNotEmpty()) { segs.add(cur.toString()); cur = StringBuilder() }
                    cur.append('['); inIndex = true
                }
                ']' -> { cur.append(']'); segs.add(cur.toString()); cur = StringBuilder(); inIndex = false }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) segs.add(cur.toString())
        return segs
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun descendKey(s: String, from: Int, key: String): Int {
        if (from >= s.length || s[from] != '{') return -1
        var depth = 0
        var i = from
        while (i < s.length) {
            when (s[i]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    val end = stringEnd(s, i)
                    if (end < 0) return -1
                    val name = s.substring(i + 1, end)
                    val after = skipWs(s, end + 1)
                    if (depth == 1 && name == key && after < s.length && s[after] == ':') return after + 1
                    i = end
                }
            }
            i++
        }
        return -1
    }

    private fun descendIndex(s: String, from: Int, idx: Int): Int {
        if (from >= s.length || s[from] != '[') return -1
        var i = skipWs(s, from + 1)
        var count = 0
        var depth = 0
        while (i < s.length) {
            val c = s[i]
            if (depth == 0 && count == idx && c != ']') return i
            when (c) {
                '"' -> { val e = stringEnd(s, i); if (e < 0) return -1; i = e }
                '{', '[' -> depth++
                '}', ']' -> { if (depth == 0) return -1; depth-- }
                ',' -> if (depth == 0) count++
            }
            i++
        }
        return -1
    }


    private fun stringEnd(s: String, quoteAt: Int): Int {
        var i = quoteAt + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return -1
    }

    private fun readScalar(s: String, at: Int): String? {
        if (at >= s.length) return null
        if (s[at] == '"') {
            val end = stringEnd(s, at)
            if (end < 0) return null
            return unescape(s.substring(at + 1, end))
        }
        var i = at
        while (i < s.length && s[i] !in ",}] \n\r\t") i++
        val v = s.substring(at, i)
        return v.ifEmpty { null }
    }

    /** 处理 \\uXXXX 与常见转义，供正文的 HTML 片段还原。 */
    fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(n); i += 2 }
                }
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }
}

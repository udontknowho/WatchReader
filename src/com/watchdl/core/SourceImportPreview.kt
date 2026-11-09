package com.watchdl.core

/** 只解析内存文本，不访问文件或网络。确认前不得把 payload 写入正式书源目录。 */
object SourceImportPreview {
    enum class Status { READY, FAILED, DUPLICATE }

    data class Entry(val name: String, val status: Status, val issue: String?, val json: String?)

    data class Preview(val entries: List<Entry>) {
        val ready: Int get() = entries.count { it.status == Status.READY }
        val failed: Int get() = entries.count { it.status == Status.FAILED }
        val duplicates: Int get() = entries.count { it.status == Status.DUPLICATE }
        /** 只包含可导入条目。重复和失败条目不覆盖本地规则。 */
        val payload: String get() = entries.filter { it.status == Status.READY }
            .joinToString(",", "[", "]") { requireNotNull(it.json) }
    }

    fun parse(text: String, existingNames: Set<String> = emptySet()): Preview {
        val root = runCatching {
            require(text.length <= JsonParser.MAX_INPUT) { "书源文件超过 8 MB" }
            val clean = JsonParser.clean(text.removePrefix("\uFEFF")).trim()
            require(isCompleteContainer(clean)) { "JSON 对象或数组未完整闭合" }
            JsonParser.parse(clean)
        }.getOrElse {
            return failure("JSON 解析失败：${it.message ?: "未知原因"}")
        }
        val items = when (root) {
            is List<*> -> root
            is Map<*, *> -> listOf(root)
            else -> return failure("顶层应为书源对象或数组")
        }
        if (items.isEmpty()) return failure("文件中没有书源条目")
        val names = existingNames.toMutableSet()
        return Preview(items.mapIndexed { index, item ->
            val map = item as? Map<*, *>
                ?: return@mapIndexed Entry("第 ${index + 1} 条", Status.FAILED, "不是书源对象", null)
            val json = encode(map)
            val legado = map.containsKey("bookSourceName") || map.containsKey("bookSourceUrl")
            val checked = if (legado) {
                val r = LegadoImport.importAll("[$json]").first()
                Triple(r.name, r.rule != null, r.issue)
            } else {
                val r = SonovelImport.importAllChecked("[$json]").firstOrNull()
                // 当前 MainActivity.buildMulti 只装载 legado，不能把仅能解析的格式宣称已可使用。
                Triple(r?.first ?: "第 ${index + 1} 条", false,
                    r?.third ?: "当前搜索池只支持 legado 格式，请先转换书源")
            }
            val (name, usable, issue) = checked
            when {
                !usable -> Entry(name, Status.FAILED, issue ?: "规则不可用", null)
                !names.add(name) -> Entry(name, Status.DUPLICATE,
                    listOfNotNull("同名书源已存在，跳过且不覆盖", issue).joinToString("；"), null)
                else -> Entry(name, Status.READY, issue, json)
            }
        })
    }

    private fun failure(message: String) = Preview(listOf(Entry("整个文件", Status.FAILED, message, null)))

    /** 原解析器容忍截断输入；预览不得把半份文件规范化后当成有效源写入。 */
    private fun isCompleteContainer(text: String): Boolean {
        if (text.firstOrNull() !in listOf('{', '[')) return false
        val stack = ArrayList<Char>()
        var quoted = false
        var escaped = false
        text.forEachIndexed { index, c ->
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> {
                    stack.add(c)
                    if (stack.size > 200) return false
                }
                '}', ']' -> {
                    if (stack.isEmpty() || stack.removeAt(stack.lastIndex) != (if (c == '}') '{' else '[')) return false
                    if (stack.isEmpty() && index != text.lastIndex) return false
                }
            }
        }
        return !quoted && stack.isEmpty()
    }

    /** 从解析结果生成标准 JSON，同时保留脚本字符串和全部原字段。 */
    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
                }
            }
            append('"')
        }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { encode(it.key.toString()) + ":" + encode(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
        is Boolean, is Number -> value.toString()
        else -> error("不支持的 JSON 类型")
    }
}

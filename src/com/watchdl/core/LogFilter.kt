package com.watchdl.core

// 两条正则编译一次复用：日志屏 3000 行就是 6000 次编译，原来每行现编。
private val LINE_RE = Regex("^\\S+\\s+\\S+\\s+\\[[^]]+]\\s+(\\S+)\\s+(.*)$")
private val ERROR_RE = Regex("(?i)(\\b(error|exception|failed|failure|crash|fatal)\\b|失败|异常|崩溃|错误)")

data class LogFilter(val tag: String = "", val errorsOnly: Boolean = false) {
    fun matches(line: String): Boolean {
        val p = LINE_RE.matchEntire(line)
        if (tag.isNotBlank() && p?.groupValues?.get(1) != tag) return false
        if (errorsOnly) {
            val body = p?.groupValues?.get(2).orEmpty()
            if (!ERROR_RE.containsMatchIn(body)) return false
        }
        return true
    }

    fun filter(lines: List<String>): List<String> = lines.filter(::matches)
}

package com.watchdl.core

data class LogFilter(val tag: String = "", val errorsOnly: Boolean = false) {
    fun matches(line: String): Boolean {
        val p = Regex("^\\S+\\s+\\S+\\s+\\[[^]]+]\\s+(\\S+)\\s+(.*)$").matchEntire(line)
        if (tag.isNotBlank() && p?.groupValues?.get(1) != tag) return false
        if (errorsOnly) {
            val body = p?.groupValues?.get(2).orEmpty()
            if (!Regex("(?i)(\\b(error|exception|failed|failure|crash|fatal)\\b|失败|异常|崩溃|错误)").containsMatchIn(body)) return false
        }
        return true
    }

    fun filter(lines: List<String>): List<String> = lines.filter(::matches)
}

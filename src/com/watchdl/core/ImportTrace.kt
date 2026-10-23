package com.watchdl.core

/**
 * 记录第一条导入异常的完整 cause 链与堆栈。
 *
 * 用途：设备上只看到「引擎正则错误：<字段> | PatternSyntaxException: Syntax error」时，
 * 根本不知道是哪一行代码、哪条正则炸的。这里把堆栈头几帧留下，
 * 界面上直接显示，一次安装就能定位。
 */
object ImportTrace {
    /** 第一条异常的描述（含首个工程内堆栈帧）。null 表示没发生过异常。 */
    @Volatile
    var last: String? = null
        private set

    fun record(t: Throwable) {
        if (last != null) return      // 只留第一条，避免刷屏
        val chain = generateSequence(t) { it.cause }.take(4)
            .joinToString(" <- ") { "${it::class.simpleName}: ${it.message?.take(70)}" }
        // 首个 com.watchdl 的栈帧就是抛点
        val frame = t.stackTrace.firstOrNull { it.className.startsWith("com.watchdl") }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: "无本工程栈帧"
        last = "$frame | $chain"
    }
}

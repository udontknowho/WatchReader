package com.watchdl.core

/**
 * 搜索结果的匹配度分数，越小越靠前。
 *
 * 原先是 `MainActivity` 的私有方法，测试只能在 `FinishTest` 里复刻一份再断言副本 ——
 * 真实现改成任何样子测试都照样绿。挪到这里，测试直接调真实现。
 */
object MatchScore {
    fun of(name: String, keyword: String): Int {
        val n = name.trim()
        val k = keyword.trim()
        return when {
            n == k -> 0              // 书名就是关键词
            n.startsWith(k) -> 1     // 关键词开头（《斗破苍穹》续集之类）
            n.contains(k) -> 2       // 包含
            k.contains(n) -> 3       // 书名是关键词的一部分（短名）
            else -> 4                // 只靠作者命中，排最后
        }
    }
}

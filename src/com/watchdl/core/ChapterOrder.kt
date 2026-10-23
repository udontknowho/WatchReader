package com.watchdl.core

/**
 * 目录的排序与去重。
 *
 * 很多源站的目录页长这样（实测 yetianlian 站）：
 *
 * ```
 * 位置 0-11:  第1593章, 第1464章, 第1463章 …  ← 「最新章节」倒序推荐块
 * 位置 12+:   第一章, 第二章, 第三章 …        ← 真正的正序目录
 * 位置 尾:    … 第1461章, 第1463章, 第1593章  ← 尾部又补了几条
 * ```
 *
 * 直接按页面顺序用，下载器取「前 3 章」拿到的就是全书最后三章 —— 用户打开
 * 手表看到的是结局。所以：先按章节号排序，重复的丢掉。
 *
 * 去重只能按 [ChapterRef.itemId]，不能按标题或章节号：
 * 实测 rulianshi 站「第三百三十七章」在页面里出现三次，标题各不相同
 * （白热化的战斗！/ 大逃亡开始 / 一招），链接也各不相同，是三篇不同正文。
 * 按号去重会把真实章节整篇吃掉。
 */
object ChapterOrder {

    /** 第 N 章。中文数字与阿拉伯数字都要认。 */
    private val NUM = Regex("""第\s*([零〇一二三四五六七八九十百千万两\d]+)\s*[章节回卷篇]""")

    /**
     * 规范化：同链接去重；正文保留页面顺序，页首的「最新章节」块挪到末尾；
     * 没有章节号的条目排在最后，保持原顺序。
     *
     * 不整体按号升序重排：页面顺序就是阅读顺序。按号重排会把番外错位
     * （番外叫「第一章 xxx」时会跑到正文第二位）。
     *
     * 没有任何条目带章节号时原样返回 —— 不能对普通标题瞎排序。
     */
    fun normalise(list: List<ChapterRef>): List<ChapterRef> {
        val uniq = list.distinctBy { it.itemId }
        if (uniq.size < 2) return uniq

        val keyed = uniq.map { it to number(it.title) }
        // 一个号都取不到，说明标题里没有「第N章」格式，不动它。
        if (keyed.none { it.second != null }) return uniq

        val nums = keyed.map { it.second }

        // 页首「最新章节」预置块：只有确认整页是倒序才判。
        //
        // 单看开头几条不够：真实目录里正文本身也有逆序（实测《斗破苍穹》
        // 1914 条有 257 处逆序），按局部递减切会把正文切碎。
        // 倒序目录页的特征是**整页净倒序**：逆序对占多数。
        // 那种页面按号升序重排；其余一律保留页面顺序。
        val pairs = nums.zipWithNext()
        val known = pairs.filter { it.first != null && it.second != null }
        val desc = known.count { it.first!! > it.second!! }
        val ascendingPage = known.isEmpty() || desc * 2 < known.size

        val out = ArrayList<ChapterRef>(uniq.size)
        if (ascendingPage) {
            // 正序页（含正文自身的小幅逆序）：原样使用页面顺序。
            keyed.filter { it.second != null }.forEach { out.add(it.first) }
        } else {
            // 倒序页：按号升序修正，同号保持页面原顺序。
            keyed.filter { it.second != null }.sortedBy { it.second }.forEach { out.add(it.first) }
        }
        // 没有号的补在最后（卷名、公告之类），保持原序。
        keyed.filter { it.second == null }.forEach { out.add(it.first) }
        return out
    }

    /** 按标题去重，保留首次出现。 */
    fun dedupe(list: List<ChapterRef>): List<ChapterRef> {
        val seen = HashSet<String>()
        return list.filter { seen.add(it.title.trim()) }
    }

    /** 取「第 N 章」里的 N；取不到返回 null。 */
    fun number(title: String): Int? {
        val m = NUM.find(title) ?: return null
        val raw = m.groupValues[1]
        return raw.toIntOrNull() ?: cnToInt(raw)
    }

    /**
     * 中文数字转整数，支持到亿位。
     *
     * 只处理章节号会遇到的形式：一、十、十一、二十、一百零五、一千五百九十三。
     */
    fun cnToInt(s: String): Int? {
        if (s.isEmpty()) return null
        var total = 0     // 已结算的「万/亿」段
        var section = 0   // 当前段内累计
        var digit = 0     // 待用的数字

        for (c in s) {
            when (c) {
                '零', '〇' -> digit = 0
                '一' -> digit = 1
                '二', '两' -> digit = 2
                '三' -> digit = 3
                '四' -> digit = 4
                '五' -> digit = 5
                '六' -> digit = 6
                '七' -> digit = 7
                '八' -> digit = 8
                '九' -> digit = 9
                '十' -> {
                    // 「十一」的十前面没有数字，按 1 算
                    section += (if (digit == 0) 1 else digit) * 10
                    digit = 0
                }
                '百' -> {
                    section += (if (digit == 0) 1 else digit) * 100
                    digit = 0
                }
                '千' -> {
                    section += (if (digit == 0) 1 else digit) * 1000
                    digit = 0
                }
                '万' -> {
                    total += (section + digit) * 10000
                    section = 0
                    digit = 0
                }
                else -> return null
            }
        }
        return total + section + digit
    }
}

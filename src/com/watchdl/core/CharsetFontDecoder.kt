package com.watchdl.core

import java.io.File

/**
 * 番茄正文的字体混淆还原。
 *
 * 番茄把正文里的部分汉字替换成私用区码位，再用字符表还原。
 * 算法照抄 gs1147/fanqie-booksource 的 fanqie_server.py：对每个字符，按 code 区间顺序试，
 * 命中则 bias = 码位 - 区间下界，取字符表对应项；表项为 "?" 或越界时保留原字符。
 */
class CharsetFontDecoder(
    val rows: List<List<String>>,
    val code: List<IntRange> = DEFAULT_CODE,
) {
    companion object {
        /** 两行区间（0xE3E8..0xE55B / 0xE3E9..0xE55C），顺序不能颠倒：mode 0 优先。 */
        val DEFAULT_CODE: List<IntRange> = listOf(58344..58715, 58345..58716)

        /**
         * 从 charset.json 读取字符表。
         *
         * 该文件是 UTF-16 编码的两行字符串数组，每行即一张表。用正则逐行取引号内文本，
         * 不引入 JSON 库，方便在手表端和服务端共用。
         */
        fun fromFile(path: String): CharsetFontDecoder {
            val text = File(path).readText(charset = Charsets.UTF_16)
            // 文件是 [["字","字",…],["字",…]] 两行数组；按内层 [ ] 切行，
            // 不能按换行切——换行不可靠，且外层括号会把自己算成一行。
            val rows = Regex("\\[([^\\[\\]]*)]", RegexOption.DOT_MATCHES_ALL)
                .findAll(text)
                .map { m -> Regex("\"([^\"]*)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList() }
                .filter { it.isNotEmpty() }
                .toList()
            return CharsetFontDecoder(rows)
        }
    }

    /** 是否已载入字符表。空表时 [decode] 是恒等变换。 */
    val isReady: Boolean get() = rows.isNotEmpty()

    fun decode(text: String): String {
        if (rows.isEmpty() || text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(mapChar(ch))
        }
        return sb.toString()
    }

    private fun mapChar(ch: Char): Char {
        val u = ch.code
        for (m in rows.indices) {
            val range = code.getOrNull(m) ?: continue
            if (u !in range) continue
            val bias = u - range.first
            val mapped = rows[m].getOrNull(bias)
            if (mapped != null && mapped != "?" && mapped.isNotEmpty()) return mapped[0]
            return ch
        }
        return ch
    }
}

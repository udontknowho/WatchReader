import com.watchdl.core.CharsetFontDecoder

/**
 * 纯 JVM 自检，不碰 Android API。跑法见 build.sh 的 test 目标。
 *
 * 样本取自本机实测：`fanqienovel.com/reader/7556557152165315134` 的正文，
 * 含 1347 个私用区字符，用同一张 charset.json 还原后残留 0 个。
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("  ✓ $name") else { failures++; println("  ✗ $name  $detail") }
    }

    val charsetPath = System.getenv("CHARSET_JSON") ?: ""
    if (charsetPath.isEmpty()) {
        println("未设置 CHARSET_JSON，跳过真实字符表测试")
    } else {
        val decoder = CharsetFontDecoder.fromFile(charsetPath)
        println("字符表：${decoder.rows.size} 行，长度 ${decoder.rows.map { it.size }}")
        check("载入两行表", decoder.rows.size == 2)
        check("第一行非空", decoder.rows[0].size > 300, "实际 ${decoder.rows[0].size}")

        val raw = "\uE3F0\uE4F3\uE4F4\uE42C"
        val decoded = decoder.decode(raw)
        check("私用区字符被还原", decoded.none { it.code in 0xE000..0xF8FF }, "得到 $decoded")
        println("    样本 $raw → $decoded")
    }

    // 不依赖真实字符表的行为：边界、未命中、问号占位
    val table = CharsetFontDecoder(
        rows = listOf(listOf("甲", "乙", "?"), listOf("丙", "丁")),
        code = listOf(0xE000..0xE0FF, 0xE100..0xE1FF),
    )
    check("第一行命中", table.decode("\uE000\uE001") == "甲乙")
    check("问号项保留原字符", table.decode("\uE002") == "\uE002")
    check("第二行命中", table.decode("\uE100\uE101") == "丙丁")
    check("区间外原样保留", table.decode("abc") == "abc")
    check("越界原样保留", table.decode("\uE0FF") == "\uE0FF")
    check("空表恒等变换", CharsetFontDecoder(emptyList()).decode("\uE000x") == "\uE000x")
    check("空串", table.decode("") == "")

    if (failures == 0) {
        println("全部通过")
    } else {
        println("失败 $failures 项")
        kotlin.system.exitProcess(1)
    }
}

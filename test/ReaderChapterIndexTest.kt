import com.watchdl.core.ReaderStyle

/**
 * `ReaderStyle.chapterIndex` 的三级放宽自检（换源回原章就靠它）。
 *
 * 覆盖：① 全等直命中；② 多一个空格；③ 阿拉伯数字中间带空格；④ 尾部多「（上）」；
 * ⑤ 前面多「正文卷 」；⑥ 全角/半角括号混用；⑦ 冒号全角半角；⑧ 英文大小写（含已知限）；
 * ⑨ 完全找不到；⑩ 空列表 / 空关键词；⑪ 多个相似候选时取归一化长度最接近的那条（两个方向）。
 *
 * 为什么挂在 [ChapterOrderTest] 里跑：`build.sh test` 的 runner 列表是固定枚举，
 * 且 build.sh 属脏区（另有任务在改）不能动，所以本文件只提供 [chapterIndexSuite]，
 * 由同属 chapterIndex 领域的 ChapterOrderTest 调一次。
 */
fun chapterIndexSuite(): Int {
    var bad = 0
    /** 断言具体期望值（这里没有 JUnit，失败即打 ✗ 并计一次）。 */
    fun assertEquals(name: String, want: Int, got: Int) {
        if (got == want) println("  ✓ $name")
        else { bad++; println("  ✗ chapterIndex($name)：得到 $got，期望 $want") }
    }
    fun at(titles: List<String>, want: String, expect: Int, name: String) =
        assertEquals(name, expect, ReaderStyle.chapterIndex(titles, want))

    val toc = listOf(
        "第一章 陨落的天才",
        "第二章 斗气大陆",
        "第十二章 风起",
        "第三章 决战",
        "第四章 试炼（上）",
        "第五章：归来",
    )

    println("[chapterIndex] 三级放宽")
    // ① 全等直接命中。
    at(toc, "第二章 斗气大陆", 1, "全等命中")
    // ② 用户记的标题多一个空格：一级不过，二级归一化过。
    at(toc, "第二章  斗气大陆", 1, "多一个空格")
    // ③ 「第 12 章」中间带空格，与目录里的「第十二章」并不等 —— 归一化后只有数字写法不同，
    //    所以这里用同写法的目录验证「数字间空格」这一条：目录写「第12章」，关键词写「第 12 章 」。
    at(listOf("第12章 风起", "第13章 雨落"), "第 12 章 风起", 0, "数字中间带空格")
    // ④ 关键词尾部多「（上）」：三级包含，取回原章。
    at(toc, "第三章 决战（上）", 3, "尾部多（上）")
    // ⑤ 关键词前面多卷名。
    at(toc, "正文卷 第三章 决战", 3, "前面多卷名")
    // ⑥ 全角括号与半角括号混用：括号被归一化滤掉，两侧仍全等。
    at(toc, "第四章 试炼(上)", 4, "全角半角括号混用")
    // ⑦ 冒号全角半角差异。
    at(toc, "第五章: 归来", 5, "冒号全角半角差异")
    // ⑧ 英文大小写：归一化只滤标点空白、不转小写，所以大小写不同是认不出的（已知限）。
    //    同一写法能命中，是这条用例想说明的全部。
    at(listOf("Chapter 5 The End"), "Chapter 5 The End", 0, "英文同写法命中")
    at(listOf("Chapter 5 The End"), "chapter 5 the end", -1, "英文大小写不同（已知限）")
    // ⑨ 完全找不到。
    at(toc, "第九章 无缘", -1, "完全找不到")
    // ⑩ 空列表 / 空关键词。
    at(emptyList(), "第一章 陨落的天才", -1, "空列表")
    at(toc, "   ", -1, "空关键词")
    // ⑪ 两个相似标题都只靠「双向包含」命中时，取归一化长度最接近的那条。
    //    关键词带卷名（比原章长 3 字），两个候选都被关键词包含，差 4 与差 3 —— 要选差 3 的。
    //    两个方向各试一次，确保不是「碰上第一个就返回」。
    val near = listOf("第十二章 破阵", "第十二章 破阵（上）")
    at(near, "正文卷 第十二章 破阵(上)", 1, "多个相似候选取最近（后者）")
    at(near.reversed(), "正文卷 第十二章 破阵(上)", 0, "多个相似候选取最近（前者）")

    return bad
}

/**
 * 目录排序与去重自检。
 *
 * 守的是这条：源站目录页常常把「最新章节」倒序块放在最前面
 * （实测 yetianlian：位置 0-11 是第1593、1464、1463…章，位置 12 才是第一章）。
 * 不排序的话下载器取「前 3 章」拿到的是全书结局。
 */
fun main() {
    var bad = 0
    fun check(name: String, got: Any?, want: Any?) {
        if (got != want) { bad++; println("  ✗ $name: 得到 $got，期望 $want") }
    }

    // --- 中文数字 ---
    for ((t, n) in listOf(
        "第一章 陨落的天才" to 1,
        "第十章" to 10,
        "第十一章" to 11,
        "第二十章" to 20,
        "第一百章" to 100,
        "第一百零五章" to 105,
        "第一千章" to 1000,
        "第一千五百九十三章 古帝洞府所在" to 1593,
        "第九百二十四章" to 924,
        "第1234章" to 1234,
        "第 5 节" to 5,
        "第一卷 风起" to 1,
        "楔子" to null,
        "番外 后来" to null,
    )) check("number('$t')", com.watchdl.core.ChapterOrder.number(t), n)

    // --- 倒序块 + 重复项 ---
    // 页首是号数大的（最新章节预置块），正文在后面。预置块要挪到末尾。
    val raw = listOf(
        "第一千五百九十三章", "第一千四百六十四章", "第一章", "第二章",
        "第一千四百六十三章", "第一千五百九十三章", "第二章",
    )
    val got = com.watchdl.core.ChapterOrder.normalise(
        raw.map { com.watchdl.core.ChapterRef("/x/${it.hashCode()}", it) }
    ).map { it.title }
    check("normalise(页首倒序块)", got,
        listOf("第一章", "第二章", "第一千四百六十三章", "第一千四百六十四章", "第一千五百九十三章"))

    // --- 正文号数乱：保留页面顺序，不按号重排 ---
    // 实测《斗破苍穹》1914 条目录里编号并非单调（257 处逆序）。
    // 按号重排会打乱真实阅读顺序；页面顺序才是对的。
    val jumbled = listOf(
        "第一章 开始" to "/j/1", "第二章 继续" to "/j/2",
        "第九章 跳了" to "/j/3", "第三章 回来" to "/j/4",
    )
    check("normalise(号数乱) 保页面序",
        com.watchdl.core.ChapterOrder.normalise(jumbled.map { (t, u) -> com.watchdl.core.ChapterRef(u, t) }).map { it.title },
        listOf("第一章 开始", "第二章 继续", "第九章 跳了", "第三章 回来"))

    // --- 番外叫「第一章」不能提到正文前面 ---
    // 实测言情小说站：《斗破苍穹》目录里「第一章  五帝破空」是页尾续篇，
    // 以前按号排会把它提到第 2 位，用户点开就看成番外。
    val extra = listOf(
        "第一章 陨落的天才" to "/e/1", "第二章 斗气大陆" to "/e/2",
        "第三百章 结束" to "/e/3", "第一章  五帝破空" to "/e/4",
    )
    check("normalise(番外不提前)",
        com.watchdl.core.ChapterOrder.normalise(extra.map { (t, u) -> com.watchdl.core.ChapterRef(u, t) }).map { it.title },
        listOf("第一章 陨落的天才", "第二章 斗气大陆", "第三百章 结束", "第一章  五帝破空"))

    // --- 全无章节号时不动顺序，同链接去重 ---
    val plain = listOf("楔子" to "/c/1", "番外一" to "/c/2", "楔子" to "/c/1")
    check("normalise(无号)",
        com.watchdl.core.ChapterOrder.normalise(plain.map { (t, u) -> com.watchdl.core.ChapterRef(u, t) }).map { it.title },
        listOf("楔子", "番外一"))

    // --- 同号不同链接：三篇不同正文，全都要留 ---
    // 实测 rulianshi 站「第三百三十七章」出现三次，标题和链接都不同。
    val sameNum = listOf(
        "第三百三十八章  杀鸡儆猴" to "/a/87134.html",
        "第三百三十七章  白热化的战斗！" to "/a/87094.html",
        "第三百三十七章 大逃亡开始" to "/a/87124.html",
        "第三百三十七章 一招" to "/a/87164.html",
    )
    check("normalise(同号)",
        com.watchdl.core.ChapterOrder.normalise(sameNum.map { (t, u) -> com.watchdl.core.ChapterRef(u, t) }).map { it.title },
        listOf("第三百三十八章  杀鸡儆猴", "第三百三十七章  白热化的战斗！", "第三百三十七章 大逃亡开始", "第三百三十七章 一招"))

    // --- 同链接才去重（重复块）---
    val dupLink = listOf(
        "第一章 甲" to "/x/1.html", "第二章 乙" to "/x/2.html", "第一章 甲" to "/x/1.html",
    )
    check("normalise(同链接)",
        com.watchdl.core.ChapterOrder.normalise(dupLink.map { (t, u) -> com.watchdl.core.ChapterRef(u, t) }).map { it.title },
        listOf("第一章 甲", "第二章 乙"))

    // --- chapterIndex（换源回原章的三级放宽）用例在 ReaderChapterIndexTest.kt ---
    bad += chapterIndexSuite()

    println(if (bad == 0) "ChapterOrder 自检通过" else "ChapterOrder 失败 $bad 项")
    if (bad > 0) kotlin.system.exitProcess(1)
}

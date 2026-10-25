/**
 * 收尾四项回归：listBooks 返回 bookId、脏目录清理、搜索排序分数、build.sh 不再覆盖源库。
 */
import com.watchdl.core.BookHit
import com.watchdl.core.JobStore
import com.watchdl.core.MatchScore
import java.io.File

fun main() {
    var bad = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("  ✓ $name")
        else { bad++; println("  ✗ $name${if (detail.isEmpty()) "" else " → $detail"}") }
    }

    val root = File(System.getProperty("java.io.tmpdir"), "finish-" + ProcessHandle.current().pid())
        .apply { mkdirs() }

    // ══════ listBooks 返回 bookId，不是目录名 ══════
    println("[listBooks] 必须回真 bookId")
    val store = JobStore(root)
    val weirdId = "https://www.min-yuan.com/book/123"
    store.writeJob(weirdId, """{"bookId":"$weirdId","name":"测试书","author":"某","total":1,"chapters":[]}""")
    val dirName = JobStore.safeDirName(weirdId)
    check("目录名是净化过的", dirName != weirdId, dirName)

    val books = store.listBooks()
    check("返回 1 本", books.size == 1, books.toString())
    check("返回的是原始 bookId", books.firstOrNull() == weirdId, books.firstOrNull() ?: "空")
    check("能拿它读回作业", books.firstOrNull()?.let { store.readJob(it) } != null)

    // 反例说明：safeDirName 幂等，用目录名 readJob 也能读到同一文件。
    // 所以真正的问题不是「读不到」，而是需要一条路把**原始 bookId** 取回来供 restore 用。
    check("用目录名也能读到文件（净化幂等，不是 bug）", store.readJob(dirName) != null)

    // ══════ 脏目录不该被列进来 ══════
    println("[listBooks] 空目录与脏目录排除")
    File(root, "emptyjob").mkdirs()
    File(root, "https:").mkdirs()
    check("仍然只有 1 本", store.listBooks().size == 1, store.listBooks().toString())

    // ══════ 搜索排序：精确匹配排最前 ══════
    println("[score] 匹配度排序")
    // 调真实现 MatchScore.of。以前这里复刻一份规则再断言副本，
    // 真实现改成任何样子都绿（N12）。
    val score: (String, String) -> Int = { n, k -> MatchScore.of(n, k) }
    val hits = listOf(
        BookHit("1", "斗破苍穹外传", "a", ""),
        BookHit("2", "剑来", "b", ""),
        BookHit("3", "斗破苍穹", "c", ""),
        BookHit("4", "斗破苍穹之药老传奇", "d", ""),
        BookHit("5", "斗破", "e", ""),
    )
    val sorted = hits.sortedBy { score(it.name, "斗破苍穹") }.map { it.name }
    check("精确匹配排第一", sorted.first() == "斗破苍穹", sorted.toString())
    check("开头匹配第二档", sorted[1] == "斗破苍穹外传", sorted.toString())
    check("短名（被含）靠后", sorted.indexOf("斗破") > sorted.indexOf("斗破苍穹外传"), sorted.toString())
    check("无关的排最后", sorted.last() == "剑来", sorted.toString())
    check("全部保留没丢", sorted.size == hits.size)

    // ══════ build.sh 不再无条件覆盖源库 ══════
    println("[build.sh] 不再覆盖已存在的源库")
    // 路径按 working dir 找，不写死绝对路径：仓库搬家或改名后
    // 这里应该跳过，而不是抛 FileNotFoundException 把整个测试拖挂（N12）。
    val shFile = File("build.sh").takeIf { it.isFile }
        ?: File(System.getProperty("user.dir"), "build.sh")
    if (!shFile.isFile) {
        println("  - 找不到 build.sh，跳过（从仓库根目录运行测试才会检查）")
    } else {
    val sh = shFile.readText()
    check("没有无条件的 cp 覆盖",
        !sh.contains("[ -f \"\$ROOT/shareBookSource.json\" ] && cp"))
    check("改成只在缺失时初始化", sh.contains("[ ! -f \"\$ROOT/app/assets/sources/legado.json\" ]"))
    }

    println(if (bad == 0) "收尾四项自检通过" else "收尾失败 $bad 项")
    if (bad > 0) kotlin.system.exitProcess(1)
}

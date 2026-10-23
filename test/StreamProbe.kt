import com.watchdl.core.*

/** 发现页推荐流活体自检：按内置榜单拼流，验证多榜混合与去重。真联网。 */
fun main() {
    var failed = 0

    println("== 云端镜像直连（不需代理）==")
    run {
        // 关键：手机直连 raw.githubusercontent.com 会 SSL 失败，靠镜像才能拉。
        val tmp = java.io.File(
            System.getenv("HOME") + "/rankcfg-test",
        ).apply { deleteRecursively() }
        val (list, msg) = RankConfig.load(tmp)
        val cached = tmp.listFiles()?.any { it.name == "ranks.json" } == true
        println("  %s %s，%d 个榜单，%s".format(
            if (list.isNotEmpty()) "OK  " else "FAIL", msg, list.size,
            if (cached) "已缓存到本地" else "未拉成功（用内置）"))
        list.take(4).forEach { println("      ${it.key} / ${it.name} / ${it.pages}页") }
        if (list.isEmpty()) failed++
        tmp.deleteRecursively()
    }

    println("== 云端配置（若本地仓库存在）==")
    run {
        val f = java.io.File(System.getenv("HOME") + "/watchdl-sources/ranks.json")
        if (!f.isFile) {
            println("  跳过：找不到 ${f.path}")
        } else {
            val r = RankConfig.parse(f.readText())
            val ok = r.size >= 3 && r.all { it.key.isNotBlank() && it.name.isNotBlank() }
            println("  %s 解析出 %d 个榜单".format(if (ok) "OK  " else "FAIL", r.size))
            r.forEach { println("      ${it.key} / ${it.name} / ${it.pages}页") }
            if (!ok) failed++
        }
    }

    println("== 推荐流：按内置榜单拼一批 ==")
    for (r in RankConfig.BUILTIN) {
        val hits = runCatching { RankSource.fetch(r, "male", 1) }.getOrElse { e ->
            println("  ${r.name} FAIL ${e.message}")
            emptyList()
        }
        val ok = hits.size >= 5
        println("  %-10s %s 条数=%d %s".format(
            r.name, if (ok) "OK  " else "FAIL", hits.size,
            hits.take(2).joinToString(" / ") { it.name }))
        if (!ok) failed++
    }

    println("== 混流去重 ==")
    val all = RankConfig.BUILTIN.flatMap { r ->
        runCatching { RankSource.fetch(r, "male", 1) }.getOrDefault(emptyList())
    }
    val dedup = all.distinctBy { "${it.name}\u0001${it.author}" }
    println("  拼接 ${all.size} 条 → 去重 ${dedup.size} 条（重复 ${all.size - dedup.size}）")
    if (dedup.size < 20) { println("  去重后太少"); failed++ }

    if (failed > 0) { println("\n推荐流自检：$failed 项失败"); kotlin.system.exitProcess(1) }
    println("\n推荐流自检：全部通过")
}

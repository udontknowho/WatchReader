/**
 * 云端书源库自检。
 *
 * 不联网 —— 用本地 HTTP 服务冒充远端仓库，测的是「校验、原子替换、
 * 不降级、不冲掉本地手动导入的源」这四条。真联网留给 e2e。
 */
import com.watchdl.core.CloudSource
import java.io.File
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer

fun main() {
    var bad = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("  ✓ $name") else { bad++; println("  ✗ $name ${if (detail.isEmpty()) "" else "→ $detail"}") }
    }

    // ---------- 起一个假仓库 ----------
    val web = File(System.getProperty("java.io.tmpdir"), "cloudsrc-" + ProcessHandle.current().pid())
        .apply { mkdirs() }
    val validJson = """[{"bookSourceName":"假源","bookSourceUrl":"https://example.com","enabled":true,"searchUrl":"https://example.com/s?q={{key}}"}]"""
    File(web, "legado.json").writeText(validJson)
    File(web, "manifest.json").writeText("""{"version":7,"file":"legado.json"}""")
    File(web, "bad.json").writeText("<html>404 Not Found</html>")

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
        val name = ex.requestURI.path.removePrefix("/").ifEmpty { "index.html" }
        val f = File(web, name)
        if (!f.isFile) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
        val b = f.readBytes()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }
    server.start()
    val base = "http://127.0.0.1:${server.address.port}"

    val dir = File(web, "sources").apply { mkdirs() }
    // 本地手动导入的源，更新后必须还在
    val manual = File(dir, "my_manual.json").apply { writeText(validJson) }

    try {
        // ---------- 1. 首次更新 ----------
        println("[1] 首次更新")
        val r1 = CloudSource.update(dir, base)
        check("成功", r1.ok, r1.message)
        check("落地文件存在", File(dir, CloudSource.FILE_NAME).isFile)
        check("内容是远端那份", File(dir, CloudSource.FILE_NAME).readText() == validJson)
        check("没有残留 .tmp", dir.listFiles()?.none { it.name.endsWith(".tmp") } == true)
        check("消息带条数", r1.message.contains("1 条"), r1.message)

        // ---------- 2. 不降级 ----------
        println("[2] 版本相同 → 不重复下载")
        val r2 = CloudSource.update(dir, base)
        check("跳过", !r2.ok, r2.message)
        check("说明已是最新", r2.message.contains("已是最新"), r2.message)

        // ---------- 3. force 覆盖 ----------
        println("[3] force 强制覆盖")
        val r3 = CloudSource.update(dir, base, force = true)
        check("仍然成功", r3.ok, r3.message)

        // ---------- 4. 坏内容拒收 ----------
        println("[4] 远端是 404 页面 → 拒收，且不破坏旧库")
        File(web, "manifest.json").writeText("""{"version":99,"file":"bad.json"}""")
        val before = File(dir, CloudSource.FILE_NAME).readText()
        val r4 = CloudSource.update(dir, base, force = true)
        check("失败", !r4.ok, r4.message)
        check("旧库没被覆盖", File(dir, CloudSource.FILE_NAME).readText() == before)

        // ---------- 5. 手动导入的源存活 ----------
        println("[5] 本地手动导入的源不被冲掉")
        check("my_manual.json 还在", manual.isFile && manual.length() > 0)

        // ---------- 6. 占位地址被拦 ----------
        println("[6] 未配置地址 → 明确报错，不瞎请求")
        // 必须传占位值。早先传 DEFAULT_BASE，填了真地址后这条就变成真联网测试了。
        val r6 = CloudSource.update(dir, "https://raw.githubusercontent.com/CHANGE_ME/x/main")
        check("拒绝", !r6.ok)
        check("说的是没配置", r6.message.contains("没配置"), r6.message)

        // ---------- 7. 版本号记下来了 ----------
        println("[7] 版本号落盘")
        File(web, "manifest.json").writeText("""{"version":7,"file":"legado.json"}""")
        CloudSource.update(dir, base, force = true)
        val ver = File(dir, "${CloudSource.FILE_NAME}.ver")
        check(".ver 写入 7", ver.isFile && ver.readText().trim() == "7", ver.readText())
        // ---------- 8. 镜像回退 ----------
        println("[8] 镜像回退")
        // 第一个地址是死的端口，第二个才是真 server。不轮转就永远拿不到。
        val deadBase = "http://127.0.0.1:1"
        val r8 = CloudSource.update(
            dir, force = true, timeoutMs = 3000, mirrors = listOf(deadBase, base),
        )
        check("坏地址在前也能成功", r8.ok, r8.message)
        check("拿到的是第二个镜像的内容",
            File(dir, CloudSource.FILE_NAME).readText() == validJson)

        println("[9] 全部镜像都死 → 报具体错误")
        val r9 = CloudSource.update(
            dir, force = true, timeoutMs = 2000, mirrors = listOf(deadBase, deadBase),
        )
        check("失败", !r9.ok, r9.message)
        check("错误信息具体", r9.message.contains("下载失败") || r9.message.contains("空内容"), r9.message)

        // ---------- 10. 必须挑版本最高的镜像，不能“第一个能用的就用” ----------
        println("[10] 多镜像版本不一致 → 取高版本")
        // 现场实测：同一时刻 gcore 返 v2（32 条），fastly 返 v4（42 条），
        // 缓存滞后好几版且刷不掉。按顺序试必然挑到旧的那个。
        run {
            val staleDir = File(web, "stale").apply { mkdirs() }
            File(staleDir, "legado.json").writeText(validJson)
            // 旧节点：版本 2；新节点：版本 9。旧节点排在**前面**。
            File(staleDir, "manifest.json").writeText("""{"version":2,"file":"legado.json"}""")
            val staleSrv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            staleSrv.createContext("/") { ex ->
                val f = File(staleDir, ex.requestURI.path.removePrefix("/").ifEmpty { "index.html" })
                if (!f.isFile) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
                val b = f.readBytes()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            staleSrv.start()
            try {
                val staleBase = "http://127.0.0.1:${staleSrv.address.port}"
                // 本地已到 7，旧镜像只有 2 → 若无条件取第一个，会得到「已是最新」而错过 9。
                File(web, "manifest.json").writeText("""{"version":9,"file":"legado.json"}""")
                val freshDir = File(web, "fresh").apply { mkdirs() }
                File(freshDir, "${CloudSource.FILE_NAME}.ver").writeText("7")
                val r10 = CloudSource.update(
                    freshDir, timeoutMs = 3000, mirrors = listOf(staleBase, base),
                )
                check("能从高版本镜像拿到更新", r10.ok, r10.message)
                check("落盘的是 v9", File(freshDir, "${CloudSource.FILE_NAME}.ver").readText().trim() == "9",
                    File(freshDir, "${CloudSource.FILE_NAME}.ver").readText())
            } finally {
                staleSrv.stop(0)
            }
        }

        println("[11] 新版本允许精简，手工导入保持原样")
        val dest = File(dir, CloudSource.FILE_NAME)
        val verFile = File(dir, "${CloudSource.FILE_NAME}.ver")
        val largerJson = "[" + List(3) { validJson.removePrefix("[").removeSuffix("]") }.joinToString(",") + "]"
        dest.writeText(largerJson)
        verFile.writeText("9")
        val manualBefore = manual.readText()
        File(web, "manifest.json").writeText("""{"version":10,"file":"legado.json"}""")
        val compact = CloudSource.update(dir, base)
        check("v10 三条精简为一条", compact.ok && dest.readText() == validJson, compact.message)
        check("精简版本记为 10", verFile.readText() == "10")
        check("手工文件字节不变", manual.readText() == manualBefore)

        println("[12] 未知或无效清单不能缩库")
        for (manifestText in listOf(null, "{}", """{"version":0}""", """{"version":10.5}""", """{"version":-1}""")) {
            dest.writeText(largerJson)
            verFile.writeText("10")
            if (manifestText == null) File(web, "manifest.json").delete()
            else File(web, "manifest.json").writeText(manifestText)
            val denied = CloudSource.update(dir, base)
            check("未知清单拒绝缩库 $manifestText", !denied.ok && denied.message.contains("跳过降级"), denied.message)
            check("旧库及版本不变 $manifestText", dest.readText() == largerJson && verFile.readText() == "10")
        }

        println("[13] 明确同版、旧版仍拒绝，未知本地可接入版本管理")
        for (version in listOf(9, 10)) {
            File(web, "manifest.json").writeText("""{"version":$version,"file":"legado.json"}""")
            val denied = CloudSource.update(dir, base)
            check("版本 $version 不覆盖", !denied.ok && dest.readText() == largerJson, denied.message)
        }
        verFile.delete()
        val firstManaged = CloudSource.update(dir, base)
        check("无本地版本允许受控精简", firstManaged.ok && dest.readText() == validJson, firstManaged.message)

        println("[14] 新版本坏包仍拒绝，不写版本")
        File(web, "manifest.json").writeText("""{"version":11,"file":"bad.json"}""")
        val invalid = CloudSource.update(dir, base)
        check("新版本不能绕过内容校验", !invalid.ok && dest.readText() == validJson, invalid.message)
        check("坏包不能推进版本", verFile.readText() == "10")
    } finally {
        server.stop(0)
        web.deleteRecursively()
    }

    println(if (bad == 0) "CloudSource 自检通过" else "CloudSource 失败 $bad 项")
    if (bad > 0) kotlin.system.exitProcess(1)
}

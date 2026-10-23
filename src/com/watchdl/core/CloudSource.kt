package com.watchdl.core

import java.io.File
import java.security.MessageDigest

/**
 * 云端书源库：从固定 URL 拉整包 `legado.json`，落地到 `filesDir/sources/`。
 *
 * **要解决的事**：书源库原本打在 APK 的 assets 里（`MainActivity.copyAsset`），
 * 每次维护书源都得重新发包。这里让 App 自己拉，改一个 JSON 推上去即可。
 *
 * 几个必须做对的地方（都踩过）：
 *
 * 1. **先校验再落盘**。下载回来的东西可能是 404 页面、CDN 错误页，直接覆盖会
 *    把用户手里能用的源库换成垃圾。`LegadoImport.importAll` 解析不出条目就拒收。
 * 2. **原子替换**。写临时文件再改名，中途断网不会留下半个文件。
 * 3. **不降级**。用远端清单里的版本号比对，比本地旧就跳过（见 [Manifest]）。
 * 4. **不许把本地手动导入的源冲掉**。只覆盖云端自己管的那个文件名。
 */
object CloudSource {

    /**
     * 默认地址。改成你自己的仓库 raw 链接即可。
     *
     * 用 raw.githubusercontent.com 而不是 github.com 的 blob 页面 —— 后者返回 HTML。
     */
    const val DEFAULT_BASE =
        "https://raw.githubusercontent.com/udontknowho/watchdl-sources/main"

    /**
     * 不传 base 时按顺序试的地址。
     *
     * **实测：本机（包括同一网络下的 app）直连 `raw.githubusercontent.com` 会 SSL 失败**，
     * `github.com/.../raw/...` 会 302 跳回 raw 域名，一样拿不到。
     * 所以先试国内直连可达的镜像，raw 放最后当兼底。
     * 与 [RankConfig.MIRRORS] 保持同一套顺序。
     */
    val MIRRORS = listOf(
        // fastly 放第一：实测同一时刻 fastly 已到 v4，gcore 还卡在 v2。
        // 顺序只是兼底，真正的版本选择在 [bestMirror]。
        "https://fastly.jsdelivr.net/gh/udontknowho/watchdl-sources@main",
        "https://gcore.jsdelivr.net/gh/udontknowho/watchdl-sources@main",
        "https://ghproxy.net/https://raw.githubusercontent.com/udontknowho/watchdl-sources/main",
        DEFAULT_BASE,
    )

    /** 清单。`sources` 是相对 [DEFAULT_BASE] 的路径。 */
    data class Manifest(val version: Int, val file: String)

    /** 一次更新的结果。 */
    data class Result(
        val ok: Boolean,
        /** 给人看的一句话。失败原因必须具体，不能只说「失败」。 */
        val message: String,
        /** 真正写入的文件；`ok` 为假或「已是最新」时为 null。 */
        val file: File? = null,
        val bytes: Int = 0,
    )

    /** 云端下来的文件名。固定，方便覆盖与回滚。 */
    const val FILE_NAME = "legado.json"

    /** 一趟更新的总预算。四个镜像 × 20 秒超时 = 一分多钟，用户等不了。 */
    private const val BUDGET_MS = 45_000

    /**
     * 拉取并安装。
     *
     * @param dir 书源目录（`filesDir/sources`）
     * @param base 仓库基地址，末尾不带 `/`
     * @param force 为真时忽略版本号强制覆盖
     * @param onProgress 给 UI 打状态用，可空
     */
    fun update(
        dir: File,
        base: String = "",
        force: Boolean = false,
        timeoutMs: Int = 20_000,
        onProgress: ((String) -> Unit)? = null,
        /** 测试用：覆盖镜像列表。不传就走 [MIRRORS]。 */
        mirrors: List<String>? = null,
    ): Result {
        if (base.contains("CHANGE_ME")) {
            return Result(false, "还没配置云端地址（CloudSource.DEFAULT_BASE 仍是占位值）")
        }
        // 传了 base 就只试它（自检/自定义源），否则走镜像列表。
        val bases = when {
            base.isNotBlank() -> listOf(base)
            mirrors != null -> mirrors
            else -> MIRRORS
        }

        var last: Result? = null
        // 先问一圈：哪个镜像的清单最新就从哪拉。见 [bestMirror] 里关于 jsDelivr 节点滞后的说明。
        if (base.isBlank() && bases.size > 1 && !force) {
            onProgress?.invoke("检查更新…")
            val best = runCatching { bestMirror(bases, minOf(timeoutMs, 8_000)) }.getOrNull()
            if (best != null) {
                val r = updateFrom(dir, best.first.trimEnd('/'), force, timeoutMs, onProgress, best.second)
                if (r.ok || r.message.startsWith("已是最新")) return r
                last = r
            }
        }
        // 同理封总预算：一个镜像 DNX 挂住时不能把「更新书源库」拖成两分钟。用户会以为卡死。
        val deadline = System.currentTimeMillis() + BUDGET_MS
        for ((i, b) in bases.withIndex()) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left < 1_500) {
                last = last ?: Result(false, "云端全部超时（${BUDGET_MS / 1000} 秒）")
                break
            }
            val suffix = if (bases.size > 1) "（${i + 1}/${bases.size}）" else ""
            onProgress?.invoke("检查更新$suffix…")
            val r = updateFrom(dir, b.trimEnd('/'), force, minOf(timeoutMs, left), onProgress)
            if (r.ok) return r
            // 「已是最新」也是成功，不必再去下一个镜像试。
            if (r.message.startsWith("已是最新")) return r
            last = r
        }
        return last ?: Result(false, "没有可用的云端地址")
    }

    /**
     * 问所有镜像要清单，返回**版本最高**的那个地址。
     *
     * 为什么不能“第一个成功就用”：jsDelivr 各节点缓存相互独立，实测同一时刻
     * gcore 返回 version 2（32 条）、fastly 返回 version 4（42 条），缓存落后好几版
     * 且加 cache-buster 也刷不掉。按顺序试就必然挑到落后那个，用户永远停在旧库。
     *
     * 全部失败就返回 null，交给调用方走原来的顺序重试。
     */
    private fun bestMirror(bases: List<String>, timeoutMs: Int): Pair<String, Manifest>? {
        if (bases.size < 2) return null
        val results = bases.mapNotNull { b ->
            val url = b.trimEnd('/')
            runCatching { url to fetchManifest(url, timeoutMs) }.getOrNull()
        }
        // 版本相同时取列表靠前的（顺序即优先级）。
        return results.maxByOrNull { it.second.version }
    }

    /** 从单个地址拉。失败原因必须具体，不能只说「失败」。 */
    private fun updateFrom(
        dir: File,
        base: String,
        force: Boolean = false,
        timeoutMs: Int = 20_000,
        onProgress: ((String) -> Unit)? = null,
        /** 已经拿到清单时直接传入，省一次请求。 */
        known: Manifest? = null,
    ): Result {
        if (base.isBlank()) {
            return Result(false, "云端地址为空")
        }
        val b = base.trimEnd('/')

        // ---- 1. 取清单（拿不到就退回「只拉主文件」）----
        onProgress?.invoke("检查更新…")
        val manifest = known ?: runCatching { fetchManifest(b, timeoutMs) }.getOrNull()

        val dest = File(dir, FILE_NAME)
        if (!force && manifest != null) {
            val local = readVersion(dest)
            if (local != null && local >= manifest.version) {
                return Result(false, "已是最新（版本 $local）")
            }
        }

        // ---- 2. 拉主文件 ----
        onProgress?.invoke("下载书源库…")
        val remotePath = manifest?.file?.takeIf { it.isNotBlank() } ?: FILE_NAME
        val text = runCatching {
            Http.fetch("$b/$remotePath", "GET", null, emptyMap(), null, timeoutMs).body
        }.getOrElse { e ->
            return Result(false, "下载失败：${e.message?.take(80) ?: e.javaClass.simpleName}")
        }
        if (text.isBlank()) return Result(false, "下载到空内容")

        // ---- 3. 校验：必须能解析出书源 ----
        val parsed = runCatching { LegadoImport.importAll(text) }.getOrElse { e ->
            return Result(false, "书源库解析失败：${e.message?.take(80)}")
        }
        if (parsed.isEmpty()) {
            return Result(false, "书源库解析出 0 条（多半拉到了 404 页面）")
        }

        // ---- 3b. 不降级（按条数） ----
        // 版本号只是云端自己写的数字，跟实际条数无关。2026-10-21 踩过两回：
        // 设备上存着 175 条的库，云端 manifest 写着 version 4 / 42 条；
        // 本地已升到 50 条时，jsDelivr 缓存还没刷新的那份仍是 42 条。
        // 只看版本号就会把源换少，用户眼里的源凭空消失。
        // 所以：只要云端比本地少，就不换 —— 少一条也算（书源是整包替换，
        // 没有「少几条是正常的」这种场景）。要降级就显式 force。
        if (!force) {
            val localCount = runCatching {
                LegadoImport.importAll(dest.readText()).size
            }.getOrDefault(0)
            if (localCount > parsed.size) {
                return Result(
                    false,
                    "云端只有 ${parsed.size} 条，本地已有 $localCount 条，跳过降级",
                )
            }
        }

        // ---- 4. 原子落盘 ----
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        runCatching {
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.length() == 0L) error("写出的文件为空")
            if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) error("改名失败") }
        }.onFailure { e ->
            tmp.delete()
            return Result(false, "保存失败：${e.message?.take(80)}")
        }

        val bytes = text.toByteArray(Charsets.UTF_8).size
        // 版本号必须在这里就记下 —— 让调用方再调一次 [rememberVersion] 一定会漏。
        manifest?.version?.let { writeVersion(dest, it) }
        val ver = manifest?.version?.let { "，版本 $it" } ?: ""
        return Result(true, "已更新 ${parsed.size} 条书源$ver", dest, bytes)
    }

    /**
     * 读清单。仓库里放 `manifest.json`：`{"version": 3, "file": "legado.json"}`。
     *
     * 没清单不算错 —— 退回「每次都拉、靠用户手动触发」也能用。
     */
    private fun fetchManifest(base: String, timeoutMs: Int): Manifest {
        val body = Http.fetch("$base/manifest.json", "GET", null, emptyMap(), null, timeoutMs).body
        val v = TinyJson.path(body, "version")?.toDoubleOrNull()?.toInt()
            ?: error("manifest 没有 version")
        return Manifest(v, TinyJson.path(body, "file") ?: FILE_NAME)
    }

    /**
     * 本地库的版本号。
     *
     * 书源库本身是 JSON 数组，塞不下版本字段，所以单独记在旁边的 `.ver` 文件里。
     * 读不到就返回 null（当作未知，允许更新）——宁可能多拉一次，不要卡在旧版。
     */
    private fun readVersion(dest: File): Int? {
        val f = File(dest.parentFile, "${dest.name}.ver")
        if (!f.isFile) return null
        return f.readText().trim().toIntOrNull()
    }

    private fun writeVersion(dest: File, v: Int) {
        runCatching { File(dest.parentFile, "${dest.name}.ver").writeText(v.toString()) }
    }

    /** 内容摘要，用来判断「远端和本地是不是同一份」。诊断与自检用。 */
    fun digest(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
    }
}

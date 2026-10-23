package com.watchdl.core

import java.io.File

/**
 * 榜单配置云端化。
 *
 * **要解决的事**：发现页里「有哪些榜、走哪个接口、要不要分男频女频」原本写死在代码里，
 * 加一个榜、改一次接口地址都得重新发版。改成从云端拉一份 `ranks.json` 驱动。
 *
 * 与 [CloudSource] 的区别：那边拉的是整包书源库（覆盖式，有版本号），
 * 这边是**小配置**，出错代价低得多 —— 拉不到就用内置清单，不让发现页变成空白。
 *
 * 配置文件格式（放在仓库根，跟 manifest.json 同级）：
 * ```json
 * { "version": 2, "ranks": [
 *     { "key": "fanqie", "name": "番茄推荐", "kind": "fanqie" },
 *     { "key": "fqup",   "name": "番茄更新", "kind": "fanqieUpdate", "pages": 5 },
 *     { "key": "qd:yuepiaolist", "name": "起点月票", "kind": "qidian", "board": "yuepiaolist" }
 * ] }
 * ```
 *
 * `kind` 对应 [Kind]，是唯三能取数的实现。云端只能改**用哪个榜、叫什么名、翻几页**，
 * 不能塞进新的取数逻辑 —— 否则就是给了远程代码执行的口子。
 */
object RankConfig {

    /** 一个榜单条目。 */
    data class Rank(
        val key: String,
        val name: String,
        val kind: Kind,
        /** 起点榜的接口代号，`kind=qidian` 时必填。 */
        val board: String = "",
        /** 最多翻几页；0 表示不可翻页。番茄推荐是 0，番茄更新是 5。 */
        val pages: Int = 0,
        /** 是否分男频/女频。 */
        val gendered: Boolean = false,
    )

    /** 取数实现。云端配置只能引用这三者之一。 */
    enum class Kind { FANQIE, FANQIE_UPDATE, QIDIAN }

    /**
     * 内置兜底清单。
     *
     * **拉不到云端时用这个**，也是新装第一次进发现页的默认内容。
     * 改这里等于改代码默认值；想改线上行为请改云端的 ranks.json。
     */
    val BUILTIN = listOf(
        Rank("fqup", "番茄更新", Kind.FANQIE_UPDATE, pages = 5),
        Rank("fanqie", "番茄推荐", Kind.FANQIE),
        Rank("qd:yuepiaolist", "起点月票", Kind.QIDIAN, board = "yuepiaolist", pages = 99, gendered = true),
        Rank("qd:hotsaleslist", "起点畅销", Kind.QIDIAN, board = "hotsaleslist", pages = 99, gendered = true),
    )

    const val FILE_NAME = "ranks.json"

    /**
     * 取配置的候选地址。与 [CloudSource.MIRRORS] 同一套 —— 镜像顺序只维护一处。
     */
    val MIRRORS: List<String> get() = CloudSource.MIRRORS

    /**
     * 拉云端配置并落地。
     *
     * 任何一步失败都**不动本地文件**，退回上次缓存或内置清单 —— 发现页不能因为
     * 一个配置拉不到就打不开。
     *
     * @param dir 缓存目录（`filesDir/sources`）
     * @return (要用的榜单列表, 给人看的一句话)
     */
    fun load(
        dir: File,
        base: String = "",
        timeoutMs: Int = 12_000,
        budgetMs: Int = 20_000,
    ): Pair<List<Rank>, String> {
        val cached = readCached(dir)
        // 传了 base 就只试它（自检用），否则走镜像列表。
        val bases = if (base.isNotBlank()) listOf(base.trimEnd('/')) else MIRRORS

        // 四个镜像串行，每个都卡满超时就是一分半。给整轮封一个总预算：
        // 实测本机 jsdelivr 域名 DNS 会直接挂住（netd 408），不封顶的话
        // 榜单页会长时间停在「正在拉取推荐…」，看着就是卡死。
        val deadline = System.currentTimeMillis() + budgetMs
        for (b in bases) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left < 1_000) break
            val text = runCatching {
                Http.fetch("$b/$FILE_NAME", "GET", null, emptyMap(), null, minOf(timeoutMs, left)).body
            }.getOrNull()
            if (text.isNullOrBlank()) continue

            val parsed = runCatching { parse(text) }.getOrNull()
            if (parsed.isNullOrEmpty()) continue

            // 解析成功才落盘，避免把坏配置缓存下来。
            runCatching {
                dir.mkdirs()
                File(dir, FILE_NAME).writeText(text, Charsets.UTF_8)
            }
            return parsed to "榜单已更新（${parsed.size} 个）"
        }

        return (cached ?: BUILTIN) to
            if (cached != null) "云端取不到，用上次的榜单" else "云端取不到，用内置榜单"
    }

    /** 上次成功拉到的配置；没有就返回 null。 */
    private fun readCached(dir: File): List<Rank>? {
        val f = File(dir, FILE_NAME)
        if (!f.isFile) return null
        return runCatching { parse(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 解析配置。**任何一条不合法就跳过该条**，不让整份配置作废 ——
     * 云端手写 JSON 难免有一两条写错，代价应该只是少一个榜。
     */
    fun parse(text: String): List<Rank> {
        val out = mutableListOf<Rank>()
        for (obj in TinyJson.objects(text, "ranks")) {
            val key = TinyJson.path(obj, "key")?.takeIf { it.isNotBlank() } ?: continue
            val kindStr = TinyJson.path(obj, "kind")?.takeIf { it.isNotBlank() } ?: continue
            val kind = when (kindStr) {
                "fanqie" -> Kind.FANQIE
                "fanqieUpdate" -> Kind.FANQIE_UPDATE
                "qidian" -> Kind.QIDIAN
                else -> continue          // 未知 kind 一律拒绝，不给远程加新逻辑的口子
            }
            val board = TinyJson.path(obj, "board").orEmpty()
            if (kind == Kind.QIDIAN && board.isBlank()) continue
            out.add(
                Rank(
                    key = key,
                    name = TinyJson.path(obj, "name")?.takeIf { it.isNotBlank() } ?: key,
                    kind = kind,
                    board = board,
                    // 页数封顶：云端写个 10000 会让人一直翻下去，也等于放大抓取。
                    pages = (TinyJson.path(obj, "pages")?.toDoubleOrNull()?.toInt() ?: 0)
                        .coerceIn(0, 50),
                    gendered = TinyJson.path(obj, "gendered") == "true",
                )
            )
        }
        return out
    }
}

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
 * { "version": 3, "ranks": [
 *     { "key": "fqr:1141", "name": "西方奇幻", "kind": "fanqieRank", "category": 1141, "rankType": 2, "gendered": true },
 *     { "key": "fanqie",  "name": "番茄推荐", "kind": "fanqie" },
 *     { "key": "fqup",    "name": "番茄更新", "kind": "fanqieUpdate", "pages": 5 },
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
        /** 站点。发现页用它分组，也用它判断是否需要男频/女频。 */
        val site: Site = Site.FANQIE,
        /** 起点榜的接口代号，`kind=qidian` 时必填。 */
        val board: String = "",
        /** 最多翻几页；0 表示不可翻页。番茄推荐是 0，番茄更新是 5。 */
        val pages: Int = 0,
        /** 是否分男频/女频。 */
        val gendered: Boolean = false,
        /** 番茄分类榜的分类 ID（如 1141 西方奇幻）。`kind=fanqieRank` 时必填。 */
        val category: Int = 0,
        /** 番茄分类榜的榜型：1=新书榜，2=阅读榜。 */
        val rankType: Int = 2,
        /** 这个榜属于哪个频道。`gendered=false` 时无意义。 */
        val gender: Gender = Gender.MALE,
    )

    /** 站点。 */
    enum class Site { FANQIE, QIDIAN }

    /** 频道。番茄分类 ID 在男女频之间不通用，必须各自锁定。 */
    enum class Gender { MALE, FEMALE }

    /** 取数实现。云端配置只能引用这四者之一。 */
    enum class Kind { FANQIE, FANQIE_UPDATE, QIDIAN, FANQIE_RANK }

    /**
     * 内置清单。
     *
     * **拉不到云端时用这个**，也是新装第一次进发现页的默认内容。
     *
     * 番茄分类 ID 与起点榜代号均 2026-10-24 逐个实测：番茄 26 个分类 ×
     * 阅读/新书两榜共 52 个组合全部能出数据；起点 5 个 board 均能翻到第 10 页。
     */
    /**
     * 番茄男频分类。顺序按番茄官网的展示顺序，改顺序等于改发现页里的排列。
     * 来源：`/api/config/list?config_key=serial_rank_category_list_common`。
     */
    val MALE_CATS = listOf(
        1141 to "西方奇幻", 1140 to "东方仙侠", 8 to "科幻末世", 261 to "都市日常",
        124 to "都市修真", 1014 to "都市高武", 273 to "历史古代", 27 to "战神赘婿",
        263 to "都市种田", 258 to "传统玄幻", 272 to "历史脑洞", 539 to "悬疑脑洞",
        262 to "都市脑洞", 257 to "玄幻脑洞", 751 to "悬疑灵异", 504 to "抗战谍战",
        746 to "游戏体育", 718 to "动漫衍生", 1016 to "男频衍生",
    )

    /** 番茄女频分类。 */
    val FEMALE_CATS = listOf(
        1139 to "古风世情", 248 to "玄幻言情", 23 to "种田", 79 to "年代",
        267 to "现言脑洞", 246 to "宫斗宅斗", 1015 to "女频衍生",
    )

    /**
     * 起点榜。代号实测全部能翻到第 10 页（每页 20 条）。
     * 其余试过的 `readlist`/`recommlist`/`finishlist` 等一律 404，不要再加。
     */
    val QIDIAN_BOARDS = listOf(
        "yuepiaolist" to "月票榜",
        "hotsaleslist" to "畅销榜",
        "newbooklist" to "新书榜",
        "collectlist" to "收藏榜",
        "updatelist" to "更新榜",
    )

    val BUILTIN = buildList {
        fun fq(cat: Int, name: String, gender: Gender, type: Int) {
            val suffix = if (type == 1) "新书" else "阅读"
            val gk = if (gender == Gender.FEMALE) "f" else "m"
            add(Rank("fqr:$gk:${type}_$cat", "$name · $suffix", Kind.FANQIE_RANK,
                category = cat, rankType = type, gendered = true, gender = gender,
                site = Site.FANQIE))
        }
        for ((cat, name) in MALE_CATS) for (t in intArrayOf(2, 1)) fq(cat, name, Gender.MALE, t)
        for ((cat, name) in FEMALE_CATS) for (t in intArrayOf(2, 1)) fq(cat, name, Gender.FEMALE, t)
        for ((board, name) in QIDIAN_BOARDS) {
            add(Rank("qd:$board", "起点$name", Kind.QIDIAN, board = board, pages = 10,
                gendered = true, site = Site.QIDIAN))
        }
    }

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
                "fanqieRank" -> Kind.FANQIE_RANK
                "qidian" -> Kind.QIDIAN
                else -> continue          // 未知 kind 一律拒绝，不给远程加新逻辑的口子
            }
            val board = TinyJson.path(obj, "board").orEmpty()
            if (kind == Kind.QIDIAN && board.isBlank()) continue
            val category = TinyJson.path(obj, "category")?.toDoubleOrNull()?.toInt() ?: 0
            if (kind == Kind.FANQIE_RANK && category == 0) continue
            out.add(
                Rank(
                    key = key,
                    name = TinyJson.path(obj, "name")?.takeIf { it.isNotBlank() } ?: key,
                    kind = kind,
                    site = if (kind == Kind.QIDIAN) Site.QIDIAN else Site.FANQIE,
                    board = board,
                    // 页数封顶：云端写个 10000 会让人一直翻下去，也等于放大抓取。
                    pages = (TinyJson.path(obj, "pages")?.toDoubleOrNull()?.toInt() ?: 0)
                        .coerceIn(0, 10),
                    gendered = TinyJson.path(obj, "gendered") == "true",
                    category = category,
                    rankType = (TinyJson.path(obj, "rankType")?.toDoubleOrNull()?.toInt() ?: 2)
                        .takeIf { it == 1 || it == 2 } ?: 2,
                    gender = if (TinyJson.path(obj, "gender") == "female") Gender.FEMALE
                             else Gender.MALE,
                )
            )
        }
        return out
    }
}

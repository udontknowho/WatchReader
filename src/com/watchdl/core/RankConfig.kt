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
        // 只出「阅读」榜（type=2）。番茄的「新书」榜（type=1）全是刚发几章的新书，
        // 点进去多数取不到正文 —— 用户要求去掉。要恢复就把它加回这两个数组。
        for ((cat, name) in MALE_CATS) fq(cat, name, Gender.MALE, 2)
        for ((cat, name) in FEMALE_CATS) fq(cat, name, Gender.FEMALE, 2)
        // 起点接口本身接受 gender=male|female，2026-11-03 实测五个 board 的女频
        // 都能返回 20 条、且与男频书目不同。男频沿用老键 `qd:<board>`
        // （存档与云端 ranks.json 里已有），女频另立 `qd:female:<board>`。
        for ((board, name) in QIDIAN_BOARDS) {
            add(Rank("qd:$board", "起点$name", Kind.QIDIAN, board = board, pages = 10,
                gendered = true, site = Site.QIDIAN))
            add(Rank("qd:female:$board", "起点女频$name", Kind.QIDIAN, board = board, pages = 10,
                gendered = true, gender = Gender.FEMALE, site = Site.QIDIAN))
        }
    }

    /** 各 kind 的缺省页数，与内置清单一致。 */
    fun defaultPages(kind: Kind): Int = when (kind) {
        Kind.QIDIAN -> 10       // 每页 20 本，10 页 = 内置的 200 本
        Kind.FANQIE_UPDATE -> 5
        else -> 0               // 番茄推荐/分类榜不可翻页
    }

    const val FILE_NAME = "ranks.json"

    /**
     * 内置表对应的版本号。缓存和云端配置低于它一律当作过期，不采用。
     *
     * 实测过的问题：设备上留着一份 v1 缓存（4 条，番茄分类榜一条都没有），
     * 它非空，于是 `local()` 永远读它、内置表永远出不来，用户看到的就是
     * 「分类榜没了」。云端 v2 又因为 jsdelivr 节点滞后一直没拉下来。
     * 加内置 19+7 个分类榜、改过一次内容就要把这个数往上加。
     *
     * v3（2026-11-03）：起点补女频条目。**设备上留着 v2 缓存的话，它没有
     * 这 5 个女频榜，还会顶掉内置表** —— 所以必须同步升版本号。
     * 代价是云端 v2 的 ranks.json 也不再被采用，要同步发一版 v3。
     */
    const val BUILTIN_VERSION = 3

    /**
     * 取配置的候选地址。与 [CloudSource.MIRRORS] 同一套 —— 镜像顺序只维护一处。
     */
    val MIRRORS: List<String> get() = CloudSource.MIRRORS

    /**
     * 只用本地数据取榜单：磁盘上的上次缓存，没有就内置表。**不联网。**
     *
     * 给主线程用。[load] 最坏要串行问 4 个镜像（总预算 20 秒），
     * 放在主线程里就是点「发现榜单」直接冻住十几秒。
     * 发现页先用这份立刻出画面，云端配置交给后台线程刷。
     */
    fun local(dir: File): List<Rank> =
        // 过期缓存不算数。缓存非空不等于缓存有用 —— 见 [BUILTIN_VERSION]。
        readCachedVersioned(dir)?.takeIf { it.first >= BUILTIN_VERSION }?.second ?: BUILTIN

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
        // N8：以前 bestMirror 串行问 4 个镜像，且 deadline 在它之后才算，
        // 4 个域名都挂住时 4×8 秒 + 后续 45 秒 ≈ 77 秒，与宣称的预算不符。
        // 现在预算从进入 load 就开始计。
        val deadline = System.currentTimeMillis() + budgetMs
        // 过期缓存当作没有，理由同 [BUILTIN_VERSION]：缺榜的旧配置比内置表还差。
        val cachedPair = readCachedVersioned(dir)?.takeIf { it.first >= BUILTIN_VERSION }
        val cached = cachedPair?.second
        val cachedVersion = cachedPair?.first ?: (BUILTIN_VERSION - 1)
        // 传了 base 就只试它（自检用），否则走镜像列表。
        val bases = if (base.isNotBlank()) listOf(base.trimEnd('/')) else MIRRORS

        // 四个镜像串行，每个都卡满超时就是一分半。给整轮封一个总预算：
        // 实测本机 jsdelivr 域名 DNS 会直接挂住（netd 408），不封顶的话
        // 榜单页会长时间停在「正在拉取推荐…」，看着就是卡死。
        var best: Triple<Int, List<Rank>, String>? = null
        for (b in bases) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left < 1_000) break
            val text = runCatching {
                Http.fetch("$b/$FILE_NAME", "GET", null, emptyMap(), null, minOf(timeoutMs, left)).body
            }.getOrNull()
            if (text.isNullOrBlank()) continue

            val parsed = runCatching { parseVersioned(text) }.getOrNull()
            if (parsed == null || parsed.second.isEmpty()) continue
            // 镜像上还挂着的旧版配置也不接，否则会把内置表顶掉。
            if (parsed.first < BUILTIN_VERSION) continue
            // N8：取**最高版本**，不是第一个能解析的。
            // 设备缓存是 v4，先命中滞后的 gcore 节点（v2）就写回 v2，
            // 榜单列表会在两次进入之间抖动。
            if (best == null || parsed.first > best!!.first) {
                best = Triple(parsed.first, parsed.second, text)
            }
            // 版本号已经够高就不必再问后面的镜像。
        }

        val got = best
        if (got != null && got.first > cachedVersion) {
            // 解析成功才落盘，避免把坏配置缓存下来。
            // N8：先写 .tmp 再改名。以前直接 writeText，进程被杀留半个 JSON，
            // 下次启动解析失败 → 静默退回内置榜单。
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.writeText(got.third, Charsets.UTF_8)
                if (!tmp.renameTo(File(dir, FILE_NAME))) {
                    tmp.copyTo(File(dir, FILE_NAME), overwrite = true)
                    tmp.delete()
                }
            }
            return got.second to "榜单已更新（${got.second.size} 个，v${got.first}）"
        }
        if (got != null) {
            // 云端的版本不比缓存新：继续用缓存，不覆盖。
            return got.second to "榜单已是最新（v${cachedVersion}）"
        }

        return (cached ?: BUILTIN) to
            if (cached != null) "云端取不到，用上次的榜单" else "云端取不到，用内置榜单"
    }



    /** 上次成功拉到的配置；没有就返回 null。 */
    private fun readCachedVersioned(dir: File): Pair<Int, List<Rank>>? {
        val f = File(dir, FILE_NAME)
        if (!f.isFile) return null
        return runCatching { parseVersioned(f.readText()) }.getOrNull()
            ?.takeIf { it.second.isNotEmpty() }
    }

    /**
     * 解析并带上版本号。[parse] 是它的无版本版。
     *
     * 版本号缺省按 0 处理：老配置没写 version 时，任何带版本号的云端配置
     * 都算「更新」，不会把已有缓存锁死。
     */
    fun parseVersioned(text: String): Pair<Int, List<Rank>> {
        val v = Regex("\"version\"\\s*:\\s*(\\d+)").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return v to parse(text)
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
                // 「番茄更新」（fanqieUpdate）已从产品中移除，用户要求不要。
                // 枚举值保留：已落到设备上的旧 ranks.json 里还有它，
                // 删除枚举会让那整份配置解析失败，连带丢掉别的榜。
                "fanqieUpdate" -> continue
                "fanqieRank" -> Kind.FANQIE_RANK
                "qidian" -> Kind.QIDIAN
                else -> continue          // 未知 kind 一律拒绝，不给远程加新逻辑的口子
            }
            val board = TinyJson.path(obj, "board").orEmpty()
            if (kind == Kind.QIDIAN && board.isBlank()) continue
            val category = TinyJson.path(obj, "category")?.toDoubleOrNull()?.toInt() ?: 0
            if (kind == Kind.FANQIE_RANK && category == 0) continue
            // 番茄「新书」榜（rankType=1）已从产品中移除，云端配置下发也要挡掉，
            // 否则下发的 26 条新书榜会把它们加回来。
            if (kind == Kind.FANQIE_RANK &&
                TinyJson.path(obj, "rankType")?.toDoubleOrNull()?.toInt() == 1) continue
            out.add(
                Rank(
                    key = key,
                    name = TinyJson.path(obj, "name")?.takeIf { it.isNotBlank() } ?: key,
                    kind = kind,
                    site = if (kind == Kind.QIDIAN) Site.QIDIAN else Site.FANQIE,
                    board = board,
                    // 页数封顶：云端写个 10000 会让人一直翻下去，也等于放大抓取。
                    // S18：以前缺省 0，而调用方对非起点榜强制 pages=1，
                    // 于是照文档示例写「番茄更新 pages:5」的配置也只出 20 条。
                    // 缺省按 kind 给内置值：起点 10 页、番茄更新 5 页、其余 0。
                    pages = (TinyJson.path(obj, "pages")?.toDoubleOrNull()?.toInt()
                        ?: defaultPages(kind)).coerceIn(0, 10),
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

package com.watchdl.core

import java.math.BigInteger
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 番茄小说 **APP 接口**取数。比 [com.watchdl.FanqieSource]（网页端）强的地方：
 * 网页端只给 185 字试读片段，APP 接口给 4000~13000 字完整正文。
 * 详见笔记本页 `fanqie-app-signature`（2026-10-16 端到端验证通过）。
 *
 * 三个入口与 `FanqieSource` 同名同签名，`Downloader` 与界面可直接换用。
 *
 * 对应 Python 参考实现：`tools/fanqie_app_test.py`（注册/签名）+
 * `tools/fanqie_app_verify.py`（目录/正文，含共享密钥派生的修正）。
 */
class FanqieApp(
    private val device: DeviceRegister.Device,
) : BookSource {
    companion object {
        private const val UA_SEARCH =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val UA_API = "com.dragon.read"
        private const val UA_DIR = "Mozilla/5.0 (Linux; Android 13)"
        private const val UA_DH =
            "com.ss.android.article.news/13400 (Linux; U; Android 10; zh_CN; tb8788p1_64_bsp; " +
                "Build/QQ3A.200805.001; Cronet/TTNetVersion:fc4cebd3 2024-12-10 QuicVersion:d9628e3d 2024-10-11)"

        private const val HOST_DIR = "https://api5-normal-sinfonlineb.fqnovel.com"

        /**
         * 目录接口的候选主机。
         *
         * **实测：这台设备（及其网络）到 `sinfonlineb` 存在间歇性 TLS 握手失败**
         * （`handshake_failure` / curl exit 35），而 `api5-normal-lf` 与
         * `api5-normal-sinfonlinec` 同时刻可通。同一主机重试几次往往又能通，
         * 但用户已经白等了十秒。改成换主机重试，把这类偶发降为一次无感重试。
         */
        private val DIR_HOSTS = listOf(
            HOST_DIR,
            "https://api5-normal-lf.fqnovel.com",
            "https://api5-normal-sinfonlinec.fqnovel.com",
        )
        private const val HOST_CONTENT = "https://novel.snssdk.com"
        private const val HOST_SEARCH = "https://api.fanqiesdk.com"

        /** 正文通道的 DH 素数与固定 AES 密钥（JS 原样）。 */
        private val DHP = BigInteger(
            "241031242692103258855207602219756607485695054850245994265411694195810883168" +
                "261222889009385826134161467322714147790401219650364895705058263194273070680" +
                "500922306273474534107340669624601458936165977404102716924945320037872943417" +
                "032584377865919814376319377685986952408894019557734611984354530154704374720" +
                "774996976375008430892633929555996888245787241299381012913029459299994792636" +
                "526405928464720973038494721168143446471443848852094012745984428885933652689" +
                "6320919633919"
        )
        private val KEY_B = Base64.getDecoder().decode("rCXGfd2POMGzeiNIgo4iLg==")

        private const val DIR_Q =
            "&ac=wifi&channel=xiaomi_1967_64&aid=1967&app_name=novelapp" +
                "&version_code=65132&version_name=6.5.1.32&device_platform=android" +
                "&os=android&ssmix=a&device_type=FRD-AL10&device_brand=honor&language=zh" +
                "&os_api=28&os_version=9&manifest_version_code=65132" +
                "&resolution=1080*1920&dpi=480&update_version_code=65132&pv_player=65132" +
                "&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0" +
                "&host_abi=arm64-v8a&dragon_device_type=phone&compliance_status=0"

        private const val CONTENT_BASE = "$HOST_CONTENT/api/novel/book/reader/content/v1" +
            "?aid=13&app_name=novelapp&channel=0&device_platform=android" +
            "&device_type=25053RT47C&os_version=10&support_image=1&version_code=66.9"

        private val rnd = SecureRandom()
    }

    private val dev get() = device.deviceId
    private val iid get() = device.installId

    // ── 搜索 ────────────────────────────────────────────
    /** 搜索。aid=13，走 api.fanqiesdk.com，需签名。书名在 `title` 字段。 */
    override fun search(keyword: String, page: Int): List<BookHit> {
        val base = "$HOST_SEARCH/api/novel/channel/homepage/search/search/v1/" +
            "?q={{kw}}&offset=${(page - 1) * 10}&enterfrom_aid=&enter_from=inner_search" +
            "&app_name=news_article&version_name=7.0.8&app_version=7.0.8" +
            "&channel=tt_huawei2019_yz&version_code=708&device_platform=android" +
            "&parent_enterfrom=novel_list&novel_host=&aid=13&scm_version=1.0.0.4112" +
            "&device_type=25053RT47C&device_brand=Redmi&language=zh&os_api=35&os_version=15" +
            "&openudid=fqapp&update_version_code=70899&plugin=0" +
            "&tma_jssdk_version=1.10.0.0&rom_version=miui__os2.0.209.0.volcnxm"
        val url = base.replace("{{kw}}", URLEncoder.encode(keyword, "UTF-8"))
        val body = signedGet(url, aid = 13, ua = UA_SEARCH).body
        val ids = TinyJson.arrayField(body, "data.ret_data", "book_id")
        val names = TinyJson.arrayField(body, "data.ret_data", "title")
        val authors = TinyJson.arrayField(body, "data.ret_data", "author")
        val abstracts = TinyJson.arrayField(body, "data.ret_data", "abstract")
        return ids.mapIndexed { i, id ->
            BookHit(
                bookId = id,
                // 接口返回的书名带 <em> 高亮标签，不清洗会直接显示成 "<em>斗破苍穹</em>"。
                name = Downloader.stripTags(names.getOrNull(i) ?: "（无题）"),
                author = Downloader.stripTags(authors.getOrNull(i) ?: ""),
                abstractText = abstracts.getOrNull(i) ?: "",
            )
        }
    }

    // ── 目录 ────────────────────────────────────────────
    /**
     * 目录。aid=1967。
     *
     * **章列表在 `data.item_data_list`**（键 `item_id`/`title`），不是网页端的
     * `allItemIds`/`chapterListWithVolume`，也不是 `catalog_data`。
     */
    override fun toc(bookId: String, name: String, author: String): BookInfo {
        // 服务端会软限流：HTTP 200 + code:0，但 item_data_list 被省略。
        // 实测约 1/3 概率为空，重试即可拿到。
        // 另外偶发 TLS 握手失败，因此每次重试都**换一个主机**，不在一棵树上吊死。
        var items = emptyList<String>()
        for (attempt in 0 until 4) {
            val host = DIR_HOSTS[attempt % DIR_HOSTS.size]
            val url = "$host/reading/bookapi/directory/all_items/v" +
                "?book_type=0&item_data_list_md5=&catalog_data_md5=&book_id=$bookId" +
                "&book_info_md5=&need_version=true&device_id=$dev" + DIR_Q
            val body = runCatching { signedGet(url, aid = 1967, ua = UA_DIR).body }.getOrNull()
            items = body?.let { TinyJson.objects(it, "item_data_list") } ?: emptyList()
            if (items.isNotEmpty()) break
            Thread.sleep(700L * (attempt + 1))
        }
        val chapters = items.mapIndexed { i, obj ->
            val id = TinyJson.path(obj, "item_id")
            ChapterRef(
                itemId = id ?: "",
                title = TinyJson.path(obj, "title")?.takeIf { it.isNotEmpty() } ?: "\u7b2c ${i + 1} \u7ae0",
                locked = false,
            )
        }.filter { it.itemId.isNotEmpty() }
        return BookInfo(bookId = bookId, name = name.ifEmpty { bookId }, author = author, chapters = chapters)
    }

    /** 完整取书：搜索定位后直接拉目录。 */
    override fun open(hit: BookHit): BookInfo = toc(hit.bookId, hit.name, hit.author)

    // ── 正文（DH + AES）──────────────────────────────────
    /**
     * 正文。DH 密钥交换后 AES-CBC 解密，返回完整章节 HTML。
     *
     * 流程对应 JS 的 `FQ_dhGet`：
     * 1. 随机私钥 `a`，公钥 `pub = 2^a mod p`，用固定 KEY_B 加密成 `y` 头
     * 2. 服务端回 `y`（加密的服务端公钥）与 `c`（密文）
     * 3. `shared = y^a mod p` → 去前导零取前 32 字节作 AES 密钥
     * 4. `c` 前 16 字节是 IV，其余是密文，PKCS#7 去尾
     *
     * **坑**：`c` 为 `'1'` 或空时，真密文在响应体 `data.content` 里，
     * 这个替换必须在 base64 解码**之前**做。
     */
    /** `bookId` 用不到（正文接口只认 itemId），保留参数是为了满足 [BookSource]。 */
    override fun content(itemId: String, bookId: String): String {
        val a = BigInteger(2048, rnd)
        // BigInteger.TWO 是 Java 9 才有的静态字段，Android 11 (SDK 30) 的
        // core-libart.jar 里没有它：编译期过得去，运行期抛 NoSuchFieldError，
        // 于是内置番茄源 100% 取不到正文（实测 2026-10-27）。用 valueOf(2)，
        // 这个从 Android 1 就有。
        val pub = BigInteger.valueOf(2).modPow(a, DHP)

        // pub 按 JS 的 toByteArray（有符号大端）转字节，再当 latin-1 字符串补齐
        val pubBytes = pub.toByteArray()
        val pubStr = latin1(pubBytes)
        val p = 16 - (pubStr.length % 16)
        val padded = strBytes(pubStr + p.toChar().toString().repeat(p))
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        val c1 = Cipher.getInstance("AES/CBC/NoPadding")
        c1.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY_B, "AES"), IvParameterSpec(iv))
        val y = Base64.getEncoder().encodeToString(iv + c1.doFinal(padded))

        val url = "$CONTENT_BASE&item_id=$itemId"
        val raw = signedGetRaw(url, aid = 13, ua = UA_DH, extra = mapOf("y" to y))
        if (raw.code != 200) error("正文 HTTP ${raw.code}")

        val sy = raw.headers["y"] ?: ""
        var sc = raw.headers["c"] ?: ""
        if (sy.isEmpty()) error("响应缺 y 头（itemId=$itemId）")
        if (sc.isEmpty() || sc == "1") {
            // 坑：密文改到了响应体里，必须在 base64 解码前替换。
            // 响应体是 gzip 的，得先解压才能当 JSON 解析。
            sc = TinyJson.path(String(ungzip(raw.bytes), Charsets.UTF_8), "data.content") ?: ""
        }
        if (sc.isEmpty()) error("响应既无 c 头也无 data.content（itemId=$itemId，该章可能被锁）")

        val shared = BigInteger(1, Base64.getDecoder().decode(sy)).modPow(a, DHP)
        val key = deriveKey(shared)
        val cb = Base64.getDecoder().decode(sc)
        val c2 = Cipher.getInstance("AES/CBC/NoPadding")
        c2.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(cb.copyOfRange(0, 16)))
        val pt = c2.doFinal(cb.copyOfRange(16, cb.size))
        return String(unpad(pt), Charsets.UTF_8)
    }

    /**
     * 共享密钥派生。**这是个坑**：`shared` 只有 192 字节，直接 `toByteArray(384)`
     * 补零再取 hex 前 64 会得到全零密钥。必须先转 384 字节再去前导零，取前 32。
     */
    private fun deriveKey(shared: BigInteger): ByteArray {
        val b = shared.toByteArray()          // 有符号大端，首字节可能是 0x00
        var j = 0
        while (j < b.size - 1 && b[j].toInt() == 0) j++
        return b.copyOfRange(j, b.size).copyOfRange(0, minOf(32, b.size - j))
            .let { if (it.size >= 32) it else ByteArray(32).also { k -> System.arraycopy(it, 0, k, 0, it.size) } }
    }

    // ── 带签名的取数 ──────────────────────────────────────
    private fun signedGet(url: String, aid: Int, ua: String): Http.Response {
        val q = url.substringAfter('?', "")
        val ts = System.currentTimeMillis() / 1000
        val h = TtSign.headers(q, "", ts, dev, iid, aid, rnd.nextInt(Int.MAX_VALUE).toLong())
        return Http.fetch(url, "GET", null, h + mapOf("User-Agent" to ua), null, timeoutMs = 25_000)
    }

    private fun signedGetRaw(
        url: String,
        aid: Int,
        ua: String,
        extra: Map<String, String> = emptyMap(),
    ): Http.Raw {
        val q = url.substringAfter('?', "")
        val ts = System.currentTimeMillis() / 1000
        val h = TtSign.headers(q, "", ts, dev, iid, aid, rnd.nextInt(Int.MAX_VALUE).toLong())
        return Http.fetchRaw(url, "GET", null, null, h + extra, ua, timeoutMs = 25_000)
    }

    // ── 小工具 ──
    /** 番茄响应带 `content-encoding: gzip`，Java 不会自动解（fetchRaw 只收原始字节）。 */
    private fun ungzip(b: ByteArray): ByteArray {
        if (b.size < 2 || (b[0].toInt() and 0xFF) != 0x1F || (b[1].toInt() and 0xFF) != 0x8B) return b
        return java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(b)).use { it.readBytes() }
    }

    private fun unpad(b: ByteArray): ByteArray {
        if (b.isEmpty()) return b
        val p = b[b.size - 1].toInt() and 0xFF
        return if (p in 1..16 && p <= b.size) b.copyOfRange(0, b.size - p) else b
    }

    private fun latin1(b: ByteArray): String = String(CharArray(b.size) { (b[it].toInt() and 0xFF).toChar() })

    private fun strBytes(s: String): ByteArray = ByteArray(s.length) { (s[it].code and 0xFF).toByte() }
}

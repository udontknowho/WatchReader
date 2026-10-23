package com.watchdl.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 番茄 APP 设备身份注册。对应 `tools/fanqie_app_test.py` 的 `tt_enc` / `gz` / `register_one`。
 *
 * 注册出一个**随机假设备**，与用户本人账号无绑定。封也只封这个假身份，
 * 但请求从本机 IP 出去，所以仍要节制。
 */
object DeviceRegister {

    /** 注册接口用的固定盐（JS 原样）。 */
    private const val FQ_FIXED =
        "TdTC5rgxYgkOUrPHpnM7pByyRiuCmrWKGWs521cXdST0m69/COjWjSanLjfBqVovHwWlGJKu8pSXMrYqOKrdWA=="

    private const val UA_REG =
        "com.dragon.read/65132 (Linux; U; Android 10; zh_CN; MI 12; " +
            "Build/MMB29M; Cronet/TTNetVersion:9ac8d95c 2024-11-25 QuicVersion:3f326df4 2024-11-14)"

    private const val URL_REG = "https://log.snssdk.com/service/2/device_register/"

    private val rnd = SecureRandom()

    /** 一个设备身份。缓存复用，别每次请求都注册。 */
    data class Device(val deviceId: String, val installId: String, val openudid: String, val cdid: String)

    /**
     * 设备身份缓存。番茄封的是设备 ID，反复注册会很快被风控盯上，
     * 所以身份一旦拿到就落盘复用。
     *
     * `cacheDir` 传 null 就只在内存里缓存（纯 JVM 自检用）。
     */
    private var memCache: Device? = null
    private var cacheFile: java.io.File? = null

    /** 指定落盘位置（Android 上传 App 私有目录）。应在首次 `get()` 前调。 */
    fun init(cacheDir: java.io.File) {
        cacheFile = java.io.File(cacheDir, "fanqie_device.json")
    }

    /** 取设备身份：有缓存就用缓存，没有才注册。 */
    fun get(): Device {
        loadFromDisk()?.let { return it }
        memCache?.let { return it }
        val d = register()
        memCache = d
        saveToDisk(d)
        return d
    }

    private fun loadFromDisk(): Device? {
        val f = cacheFile ?: return null
        if (!f.isFile) return null
        return try {
            val b = f.readText(Charsets.UTF_8)
            val d = Device(
                TinyJson.path(b, "deviceId") ?: return null,
                TinyJson.path(b, "installId") ?: "",
                TinyJson.path(b, "openudid") ?: "",
                TinyJson.path(b, "cdid") ?: "",
            )
            if (d.deviceId.isEmpty()) null else d
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToDisk(d: Device) {
        val f = cacheFile ?: return
        try {
            f.writeText(
                "{\"deviceId\":\"${d.deviceId}\",\"installId\":\"${d.installId}\"," +
                    "\"openudid\":\"${d.openudid}\",\"cdid\":\"${d.cdid}\"}",
                Charsets.UTF_8,
            )
        } catch (e: Exception) {
            // 写不进去不影响本次使用，下次重新注册而已
        }
    }

    /** 丢弃当前身份（如被服务端拒绝）。 */
    fun reset() {
        memCache = null
        cacheFile?.delete()
    }

    /** 注册一个全新假设备。失败抛异常（含响应前 300 字，便于排查）。 */
    fun register(): Device {
        val openudid = alnum(16)
        val cdid = UUID.randomUUID().toString()
        val clientudid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val ts = now / 1000

        val uuid = UUID.randomUUID().toString()
        val header = linkedMapOf(
            "display_name" to "\u756a\u8304\u5c0f\u8bf4", "update_version_code" to "65132",
            "manifest_version_code" to "65132", "aid" to 1967,
            "channel" to "xiaomi_1967_64", "package" to "com.dragon.read",
            "app_version" to "6.5.1.32", "version_code" to "65132",
            "sdk_version" to "v04.04.05-ov-android", "sdk_target_version" to 29,
            "os" to "Android", "os_version" to "10", "os_api" to "29",
            "device_model" to "MI 12", "device_brand" to "Xiaomi",
            "device_manufacturer" to "Google", "device_category" to "phone",
            "cpu_abi" to "arm64-v8a", "release_build" to uuid,
            "density_dpi" to "560", "display_density" to "mdpi",
            "resolution" to "1440x2392", "language" to "zh",
            "mac" to "11:22:33:44:55:66", "timezone" to 8, "access" to "wifi",
            "not_request_sender" to 0, "carrier" to "CHINA MOBILE", "mcc_mnc" to "46007",
            "rom" to "EMUI-" + alnum(13), "rom_version" to alnum(2),
            "sig_hash" to md5Hex(strBytes(uuid)), "openudid" to openudid, "clientudid" to clientudid,
            "sim_serial_number" to emptyList<String>(), "region" to "CN",
            "tz_name" to "Asia/Shanghai", "tz_offset" to 28800, "sim_region" to "cn",
        )
        val js = buildString {
            append("{\"magic_tag\":\"ss_app_log\",\"header\":{")
            header.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                encodeJsonField(this, k, v)
            }
            append("},\"_gen_time\":").append(now).append('}')
        }
        val post = ttEnc(strBytes(gzipJs(js)))

        val qs = buildQuery(ts, now, cdid, openudid)
        val r = Http.fetchRaw(
            URL_REG + "?" + qs, "POST", post,
            contentType = "application/octet-stream;tt-data=a",
            headers = mapOf("accept-encoding" to "gzip", "connection" to "Keep-Alive"),
            userAgent = UA_REG, timeoutMs = 20_000,
        )
        val body = String(ungzip(r.bytes), Charsets.ISO_8859_1)
        val dev = Regex("\"device_id_str\":\"([0-9]+)\"").find(body)?.groupValues?.get(1)
            ?: error("注册失败：HTTP ${r.code}，响应前 300 字：${body.take(300)}")
        val iid = Regex("\"install_id_str\":\"([0-9]+)\"").find(body)?.groupValues?.get(1) ?: ""
        return Device(dev, iid, openudid, cdid)
    }

    // ── 注册体加密（JS 的 FQ_ttEnc）──
    private fun ttEnc(data: ByteArray): ByteArray {
        val rndBytes = ByteArray(32).also { rnd.nextBytes(it) }
        val inner = sha512(rndBytes)
        val fixedB = base64Decode(FQ_FIXED)
        val hv = sha512(inner + fixedB)
        val key = hv.copyOfRange(0, 16)
        val iv = hv.copyOfRange(16, 32)
        val dh = sha512(data)
        val payload = pad(dh + data)
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = c.doFinal(payload)
        return byteArrayOf(0x74, 0x63, 0x05, 0x10, 0, 0) + rndBytes + ct
    }

    /** JS 的 FQ_gz：gzip 后把第 8、9 字节改成 2、3。 */
    private fun gzipJs(s: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(strBytes(s)) }
        val raw = bos.toByteArray()
        raw[8] = 2
        raw[9] = 3
        return String(raw.map { (it.toInt() and 0xFF).toChar() }.toCharArray())
    }

    /** 参数按键名排序后拼接（JS 里 `P.sort()`）。 */
    private fun buildQuery(ts: Long, nowMs: Long, cdid: String, openudid: String): String {
        val p = listOf(
            "tt_data" to "a", "os_api" to "29", "device_type" to "MI 12", "ssmix" to "a",
            "manifest_version_code" to "65132", "dpi" to "560", "version_name" to "6.5.1.32",
            "ts" to "$ts", "cpu_support64" to "true", "app_type" to "normal",
            "appTheme" to "light", "ac" to "wifi", "host_abi" to "arm64-v8a",
            "update_version_code" to "65132", "channel" to "xiaomi_1967_64",
            "_rticket" to "$nowMs", "device_platform" to "android",
            "version_code" to "65132", "cdid" to cdid, "os" to "android",
            "is_android_pad" to "0", "openudid" to openudid, "package" to "com.dragon.read",
            "resolution" to "1440*2392", "os_version" to "10", "language" to "zh",
            "device_brand" to "Xiaomi", "need_personal_recommend" to "1", "aid" to "1967",
            "minor_status" to "0", "app_name" to "novel", "mcc_mnc" to "46007",
        ).sortedBy { it.first }
        return p.joinToString("&") { (k, v) -> "$k=" + pctEncode(v) }
    }

    /**
     * 对应 Python `urllib.parse.quote(v, safe='')`。
     * Java 的 `URLEncoder` 会留下 `*` 并把空格变成 `+`，两处都不对。
     */
    private fun pctEncode(s: String): String {
        val sb = StringBuilder()
        for (b in strBytes(s)) {
            val c = b.toInt() and 0xFF
            val un = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code.toInt() || c == '_'.code.toInt() ||
                c == '.'.code.toInt() || c == '~'.code.toInt()
            if (un) sb.append(c.toChar()) else sb.append("%%%02X".format(c))
        }
        return sb.toString()
    }

    /** 响应可能带 gzip（我们发了 `accept-encoding: gzip`），需要先解压再找 JSON。 */
    private fun ungzip(b: ByteArray): ByteArray {
        if (b.size < 2 || (b[0].toInt() and 0xFF) != 0x1F || (b[1].toInt() and 0xFF) != 0x8B) return b
        return java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(b)).use { it.readBytes() }
    }

    // ── 小工具 ──
    private fun encodeJsonField(sb: StringBuilder, k: String, v: Any?) {
        sb.append('"').append(k).append("\":")
        when (v) {
            is String -> sb.append('"').append(escape(v)).append('"')
            is List<*> -> sb.append("[]")
            else -> sb.append(v)
        }
    }

    /**
     * 对应 Python `json.dumps(..., ensure_ascii=True)`：非 ASCII 一律转 `\uXXXX`。
     * 写成原字符会让 JSON 多出 UTF-8 字节，进而算出不同的 SHA-512 摘要。
     */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || ch.code > 0x7E -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun alnum(n: Int): String {
        val cs = "abcdefghijklmnopqrstuvwxyz0123456789"
        return String(CharArray(n) { cs[rnd.nextInt(cs.length)] })
    }

    /** 补位到 16 的倍数：PKCS#7，整块时补满一块。 */
    private fun pad(b: ByteArray): ByteArray {
        val p = 16 - (b.size % 16)
        return b + ByteArray(p) { p.toByte() }
    }

    private fun sha512(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(b)
    private fun md5Hex(b: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private fun base64Decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

    /** JS 的 `FQ_s2j`：每字符 charCode 低字节。 */
    private fun strBytes(s: String): ByteArray = ByteArray(s.length) { (s[it].code and 0xFF).toByte() }
}

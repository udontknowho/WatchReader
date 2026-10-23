package com.watchdl.core

/**
 * 头条系（番茄小说）接口签名汇合点。对应 `tools/argus_ref.py` 的 `sign`。
 *
 * 产出四个请求头，加到请求上即可通过服务端校验：
 * `x-ss-req-ticket` / `x-khronos` / `x-argus` / `x-ladon`。
 */
object TtSign {

    /** 固定常量：TOKEN 里的 lic 与签名用的 rp。 */
    private const val LIC = 1611921764L
    private val RP = byteArrayOf(1, 2, 3, 4)

    /**
     * @param q 请求的 query string（不含 `?`）；body 请求传空串
     * @param stub 表单签名，一般为 32 个 0；没有就传空串
     * @param ts 秒级时间戳
     * @param dev device_id
     * @param iid install_id
     * @param aid 13（正文）/ 1967（目录）/ 3040（搜索）
     * @param rv 随机数，0 ~ 2^31
     */
    fun headers(q: String, stub: String, ts: Long, dev: String, iid: String, aid: Int, rv: Long): Map<String, String> {
        val a = aid.toString()
        val h = mutableMapOf(
            "x-ss-req-ticket" to (ts * 1000).toString(),
            "x-khronos" to ts.toString(),
            "x-argus" to Argus.sign(q, stub, ts, dev, iid, a, rv),
            "x-ladon" to Ladon.sign(ts, LIC, a, RP)
        )
        if (stub.isNotEmpty()) h["x-ss-stub"] = stub
        return h
    }
}

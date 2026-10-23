import com.watchdl.core.Sm3
import com.watchdl.core.Ladon
import com.watchdl.core.Argus
import com.watchdl.core.TtSign

/**
 * 签名算法自检。断言值取自 `tools/argus_ref.py`，而后者已用
 * `tools/sign_check.py` 与原版 JS 逐字节对拍通过（3/3）。
 *
 * 这里的任何一个失败都说明移植走样了，不要去改期望值 —— 要改实现对。
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("  ✓ $name") else { failures++; println("  ✗ $name  $detail") }
    }

    println("[SM3] 标准向量与行为")
    check(
        "SM3(\"abc\")",
        Sm3.hash("abc") == "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
        Sm3.hash("abc")
    )
    // GB/T 32905-2016 附录 A 的 64 字节向量，验证分块与长度填充
    check(
        "SM3(64字节 abcd...)",
        Sm3.hash("abcd".repeat(16)) == "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
        Sm3.hash("abcd".repeat(16))
    )
    check("SM3 空串", Sm3.hash("") == "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b", Sm3.hash(""))
    check("SM3 确定性", Sm3.hash("斗破苍穹") == Sm3.hash("斗破苍穹"))
    check("SM3 长度固定 64", Sm3.hash("x".repeat(1000)).length == 64)
    check("SM3 雪崩（改 1 字节全变）", Sm3.hash("a").take(8) != Sm3.hash("b").take(8))

    println("[x-ladon] 与 Python 参考实现逐字节一致")
    val rp = byteArrayOf(1, 2, 3, 4)
    val ladon = Ladon.sign(1700000000L, 1611921764L, "1967", rp)
    check(
        "固定输入产出固定签名",
        ladon == "AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH",
        ladon
    )
    // base64 流内没有对齐边界，前缀是 AQIDBK 而非独立的 AQIDBA==
    check("前 4 字节是 rp", ladon.startsWith("AQIDBK"), ladon.take(8))
    check("同输入确定性", Ladon.sign(1700000000L, 1611921764L, "1967", rp) == ladon)
    check("改 ts 签名变", Ladon.sign(1700000001L, 1611921764L, "1967", rp) != ladon)
    check("改 aid 签名变", Ladon.sign(1700000000L, 1611921764L, "13", rp) != ladon)

    println("[x-argus] 与 Python 参考实现逐字节一致")
    val argus = Argus.sign(
        "a=1&version_name=7.0.8", "0".repeat(32),
        1700000000L, "1234567890", "987654321", "1967", 12345L
    )
    val expectArgus =
        "8oGtly/q30zomF9jsMsfO3JH1WF7TLiPqtHvN1FuiHsrhVSruLUui20IRES8eEg6LBOoApLOn1Qv0vRIDDVJ7wvdyLR+VcQCVh5/qCkrbPtlNDAHtrAmrNdsVeT9NvqYnC4esfKoszKzY9AJqRfe6XnIZiO6vQTKpiy0QEiC8F/mNS6VnGD5bR/EzFh4fuoZoh8A7qxh6wwbTCC47As3Lpt1URJEdqCTCimi5fg6UHOxLz5bqboK8ZFl3HrKoWAyJOANF7/B2L4O7u2IpBRg88eT"
    check("固定输入产出固定签名", argus == expectArgus, argus.take(80) + "...")
    check("同输入确定性", Argus.sign(
        "a=1&version_name=7.0.8", "0".repeat(32),
        1700000000L, "1234567890", "987654321", "1967", 12345L
    ) == argus)
    check("改 rv 签名变", Argus.sign(
        "a=1&version_name=7.0.8", "0".repeat(32),
        1700000000L, "1234567890", "987654321", "1967", 12346L
    ) != argus)
    check("改 dev 签名变", Argus.sign(
        "a=1&version_name=7.0.8", "0".repeat(32),
        1700000000L, "1234567891", "987654321", "1967", 12345L
    ) != argus)
    check("空 query 也能签", Argus.sign(
        "", "", 1700000000L, "1234567890", "987654321", "13", 1L
    ).isNotEmpty())

    println("[TtSign] 四个请求头")
    val h = TtSign.headers("a=1&version_name=7.0.8", "0".repeat(32), 1700000000L, "1234567890", "987654321", 1967, 12345L)
    check("x-khronos 等于 ts", h["x-khronos"] == "1700000000")
    check("x-ss-req-ticket 是毫秒", h["x-ss-req-ticket"] == "1700000000000")
    check("x-argus 与直接调用一致", h["x-argus"] == argus)
    check("x-ladon 与直接调用一致", h["x-ladon"] == ladon)
    check("有 stub 时带 x-ss-stub", h["x-ss-stub"] == "0".repeat(32))
    check("无 stub 时不带该头", TtSign.headers("q=1", "", 1700000000L, "d", "i", 13, 1L)["x-ss-stub"] == null)

    println()
    if (failures == 0) println("签名自检全部通过") else { println("失败 $failures 项"); kotlin.system.exitProcess(1) }
}

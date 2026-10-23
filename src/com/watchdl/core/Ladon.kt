package com.watchdl.core

import java.security.MessageDigest
import java.util.Base64

/**
 * 64 位运算工具。原版 JS 用「字节字符串 + charCode」模拟 64 位整数，
 * Kotlin 直接用 `Long`，关键在于字节序：读小端、写小端。
 */
internal object B64 {
    fun w(lo: Long, hi: Long): Long = (hi shl 32) or (lo and 0xFFFFFFFFL)

    fun rol(v: Long, c0: Int): Long {
        val c = c0 and 63
        return if (c == 0) v else (v shl c) or (v ushr (64 - c))
    }

    fun ror(v: Long, c0: Int): Long = rol(v, (64 - (c0 and 63)) and 63)

    /** 从字节串读 8 字节小端，不足补零。 */
    fun r64(s: ByteArray, o: Int): Long {
        var r = 0L
        for (i in 0 until 8) {
            val b = if (o + i < s.size) s[o + i].toLong() and 0xFF else 0L
            r = r or (b shl (i * 8))
        }
        return r
    }

    /** 两个 64 位值按小端写回 16 字节。 */
    fun w64le2(l: Long, r: Long): ByteArray {
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i] = (l ushr (i * 8)).toByte()
            out[8 + i] = (r ushr (i * 8)).toByte()
        }
        return out
    }

    fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** 十六进制串 → 字节（JS 的 `FQ_unhex`，每 2 个字符 1 字节）。 */
    fun unhex(h: String): ByteArray {
        val out = ByteArray(h.length / 2)
        for (i in out.indices) out[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    /** 字符串 → 每字符 charCode 低字节（JS 的 `FQ_s2j`）。 */
    fun strBytes(s: String): ByteArray = ByteArray(s.length) { (s[it].code and 0xFF).toByte() }

    /** JS 的 `FQ_pad`：PKCS#7 补位，整块时补满一块。 */
    fun pad(s: String): String {
        val p = 16 - (s.length % 16)
        return s + p.toChar().toString().repeat(p)
    }

    // FQ_SZ = FQ_w(0x046d678b, 0x3dc94c3a)
    private const val SZ = (0x3dc94c3aL shl 32) or 0x046d678bL

    /** JS 的 `FQ_zbit`：取 FQ_SZ 的第 i 位。 */
    fun zbit(i: Int): Long = (SZ ushr i) and 1L
}

/**
 * x-ladon 签名。逐行对应 `tools/ladon_ref.py` 的 `lrk` / `lenc` / `ladon`。
 * 改这里必须同步改 Python 并跑 `python3 tools/sign_check.py`。
 */
object Ladon {

    /** 由 rp 与 aid 派生 35 个轮密钥。 */
    private fun lrk(rp: ByteArray, aid: String): LongArray {
        val dg = B64.md5(rp + aid.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
        // 注意：JS 的 FQ_r64 作用在【十六进制字符串】上，取的是字符 charCode，不是解码后的字节
        val dgb = B64.strBytes(dg)

        val ws = LongArray(35)
        ws[0] = B64.r64(dgb, 0)
        ws[1] = B64.r64(dgb, 8)
        ws[2] = B64.r64(dgb, 16)
        ws[3] = B64.r64(dgb, 24)

        var b0 = ws[0]
        var b8 = ws[1]
        val q = ArrayDeque<Long>()
        q.addLast(ws[2]); q.addLast(ws[3])
        for (x in 0 until 0x22) {
            val x9 = b0
            var x8 = b8
            x8 = B64.ror(x8, 8)
            x8 += x9
            x8 = x8 xor x.toLong()
            q.addLast(x8)
            x8 = x8 xor B64.rol(x9, 3)
            ws[x + 1] = x8
            b0 = x8
            b8 = q.removeFirst()
        }
        return ws
    }

    /** 34 轮 Feistel。 */
    private fun lenc(blk: ByteArray, keys: LongArray): ByteArray {
        var l = B64.r64(blk, 0)
        var r = B64.r64(blk, 8)
        for (i in 0 until 34) {
            r = keys[i] xor (l + B64.ror(r, 8))
            l = r xor B64.ror(l, 61)
        }
        return B64.w64le2(l, r)
    }

    /**
     * 生成 x-ladon。rp 是 4 字节（JS 传 `String.fromCharCode(1,2,3,4)`）。
     *
     * @param ts 秒级时间戳
     * @param lic 固定 1611921764
     * @param aid 13（正文接口）/ 1967（目录接口）
     */
    fun sign(ts: Long, lic: Long, aid: String, rp: ByteArray): String {
        val pd = B64.pad("$ts-$lic-$aid")
        val ks = lrk(rp, aid)
        val pb = B64.strBytes(pd)
        val ct = ByteArray(pb.size)
        var o = 0
        while (o < pb.size) {
            System.arraycopy(lenc(pb.copyOfRange(o, o + 16), ks), 0, ct, o, 16)
            o += 16
        }
        return B64.b64(rp + ct)
    }
}

package com.watchdl.core

/**
 * 国密 SM3 哈希（GB/T 32905-2016）。
 *
 * JDK 不内置，引 BouncyCastle 会显著增大 APK，所以这里手写。
 * 逐行对应 `tools/argus_ref.py` 的 `sm3_bytes`，改这里必须同步改那边并跑
 * `python3 tools/sign_check.py`（要与原版 JS 对拍 3/3 一致）。
 */
object Sm3 {

    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt()
    )

    private fun rotl(x: Int, n0: Int): Int {
        val n = n0 and 31
        return (x shl n) or (x ushr (32 - n))
    }

    /** 返回 64 位小写十六进制。 */
    fun hash(s: ByteArray): String {
        val msg = ByteArray(((s.size + 9) / 64 + 1) * 64)
        System.arraycopy(s, 0, msg, 0, s.size)
        msg[s.size] = 0x80.toByte()
        val bitLen = s.size.toLong() * 8
        val lenPos = msg.size - 8
        for (i in 0 until 8) msg[lenPos + i] = (bitLen ushr (56 - i * 8)).toByte()

        val v = IV.copyOf()
        val w = IntArray(68)
        val w1 = IntArray(64)
        var off = 0
        while (off < msg.size) {
            for (i in 0 until 16) {
                val p = off + i * 4
                w[i] = ((msg[p].toInt() and 0xFF) shl 24) or
                    ((msg[p + 1].toInt() and 0xFF) shl 16) or
                    ((msg[p + 2].toInt() and 0xFF) shl 8) or
                    (msg[p + 3].toInt() and 0xFF)
            }
            for (j in 16 until 68) {
                var x = w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)
                x = x xor rotl(x, 15) xor rotl(x, 23)
                w[j] = x xor rotl(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]

            var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
            var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]
            for (j in 0 until 64) {
                val tj = if (j < 16) 0x79cc4519 else 0x7a879d8a
                val ss1 = rotl(rotl(a, 12) + e + rotl(tj, j), 7)
                val ss2 = ss1 xor rotl(a, 12)
                val ff = if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)
                val gg = if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)
                val tt1 = ff + d + ss2 + w1[j]
                val tt2 = gg + h + ss1 + w[j]
                d = c
                c = rotl(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl(f, 19)
                f = e
                e = tt2 xor rotl(tt2, 9) xor rotl(tt2, 17)
            }
            v[0] = v[0] xor a; v[1] = v[1] xor b; v[2] = v[2] xor c; v[3] = v[3] xor d
            v[4] = v[4] xor e; v[5] = v[5] xor f; v[6] = v[6] xor g; v[7] = v[7] xor h
            off += 64
        }
        return v.joinToString("") { "%08x".format(it) }
    }

    /** JS 的 `FQ_sm3` 收字符串，按 charCode 取低字节。UTF-8 正文请先自行转字节。 */
    fun hash(text: String): String = hash(ByteArray(text.length) { (text[it].code and 0xFF).toByte() })
}

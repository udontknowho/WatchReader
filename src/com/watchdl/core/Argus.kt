package com.watchdl.core

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * x-argus 签名。逐行对应 `tools/argus_ref.py` 的
 * `srk` / `senc` / `argus_bean` / `argus`。
 * 改这里必须同步改 Python 并跑 `python3 tools/sign_check.py`。
 */
object Argus {

    // ── 硬编码密钥（JS 原样，书源作者也是这么维护的）──
    private const val FQ_SK = "fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304"
    private const val FQ_AK = "d31e3718288a1027baab59f146a09a9c"
    private const val FQ_AIV = "ea180a0336ed352fcd24e4d50018ae54"

    private const val MASK32 = 0xFFFFFFFFL

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    // ── 自定义分组加密（FQ_srk / FQ_sEnc）──
    private fun srk(key: ByteArray): LongArray {
        val ws = LongArray(72)
        for (i in 0 until 4) ws[i] = B64.r64(key, i * 8)
        for (x in 4 until 72) {
            var m = B64.ror(ws[x - 1], 3) xor ws[x - 3]
            m = m xor B64.ror(m, 1)
            ws[x] = ws[x - 4].inv() xor m xor B64.zbit((x - 4) % 62) xor B64.w(3, 0)
        }
        return ws
    }

    private fun senc(blk: ByteArray, keys: LongArray): ByteArray {
        var l = B64.r64(blk, 0)
        var r = B64.r64(blk, 8)
        for (i in keys.indices) {
            val o = r
            val r1 = B64.rol(r, 1)
            val r8 = B64.rol(r, 8)
            // JS 是半字wise的与：lo 与 lo、hi 与 hi 各算各的
            val nl = B64.w((r1 and MASK32) and (r8 and MASK32), (r1 ushr 32) and (r8 ushr 32))
            r = l xor nl xor B64.rol(r, 2) xor keys[i]
            l = o
        }
        return B64.w64le2(l, r)
    }

    // ── protobuf 手写编码（FQ_ev / FQ_pbv / FQ_pbb）──
    private fun ev(v0: Long): String {
        var v = v0
        val sb = StringBuilder()
        while (v >= 0x80) {
            sb.append(((v % 0x80) or 0x80).toInt().toChar())
            v /= 0x80
        }
        return sb.append(v.toInt().toChar()).toString()
    }

    private fun pbv(f: Int, v: Long): String = ev(f.toLong() * 8) + ev(v)

    private fun pbb(f: Int, v: String): String = ev(f.toLong() * 8 + 2) + ev(v.length.toLong()) + v

    /** 从 query string 取参数（FQ_qp）。 */
    private fun qp(query: String, k: String): String {
        for (part in query.split("&")) {
            val i = part.indexOf('=')
            if (i > 0 && part.substring(0, i) == k) return part.substring(i + 1)
        }
        return ""
    }

    private fun argusBean(q: String, stub: String, ts: Long, dev: String, iid: String, aid: String, rv: Long): String {
        val stubOk = stub.length == 32 && stub.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        val bi = B64.unhex(if (stubOk) stub else "0".repeat(32)).decodeToString2()
        val bh = B64.unhex(Sm3.hash(bi).substring(0, 12)).decodeToString2()
        val qh = if (q.isEmpty()) {
            B64.unhex(Sm3.hash("\u0000".repeat(16)).substring(0, 12)).decodeToString2()
        } else {
            B64.unhex(Sm3.hash(q).substring(0, 12)).decodeToString2()
        }
        val nest = pbv(1, 1) + pbv(2, 1) + pbv(3, 1) + pbv(7, 0xC792ECCC)
        val s23 = pbb(1, "NX551J") + pbv(2, 8196) + pbv(4, 0x80E0D800)
        return pbv(1, 1077940818) + pbv(2, 2) + pbv(3, rv) +
            pbb(4, aid) + pbb(5, dev) + pbb(6, "1611921764") +
            pbb(7, qp(q, "version_name")) + pbb(8, "v04.04.05-ov-android") +
            pbv(9, 134744640) + pbb(10, B64.unhex("0".repeat(16)).decodeToString2()) +
            pbv(11, 0) + pbv(12, ts * 2) + pbb(13, bh) + pbb(14, qh) +
            pbb(15, nest) + pbb(16, iid) + pbb(20, "none") +
            pbv(21, 738) + pbb(23, s23) + pbv(25, 2)
    }

    /** 生成 x-argus。 */
    fun sign(q: String, stub: String, ts: Long, dev: String, iid: String, aid: String, rv: Long): String {
        val pb = B64.pad(argusBean(q, stub, ts, dev, iid, aid, rv))
        val keys = srk(hex(FQ_SK))
        val pbbBytes = B64.strBytes(pb)
        val enc = ByteArray(pbbBytes.size)
        var o = 0
        while (o < pbbBytes.size) {
            System.arraycopy(senc(pbbBytes.copyOfRange(o, o + 16), keys), 0, enc, o, 16)
            o += 16
        }
        val magic = hex("f2f7fcfff2f7fcff")
        val un = magic + enc + hex("eafb2cfe8568519154")
        val buf = ByteArray(un.size)
        System.arraycopy(un, 0, buf, 0, 8)
        for (i in 8 until un.size) buf[i] = (un[i].toInt() xor magic[i % 8].toInt()).toByte()
        buf.reverse()
        val fin = B64.strBytes(B64.pad(buf.decodeToString2() + "ao"))
        val ct = aesCbc(fin, hex(FQ_AK), hex(FQ_AIV))
        return B64.b64(hex("f281") + ct)
    }

    private fun aesCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun ByteArray.decodeToString2(): String =
        String(CharArray(size) { (this[it].toInt() and 0xFF).toChar() })
}

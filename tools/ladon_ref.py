#!/usr/bin/env python3
"""x-ladon 签名的 Python 参考实现。

目的：在写 Kotlin 之前，先把算法验证对。算法照抄自
`fq_app2.json`（🍅番茄小说(APP) 书源）的 jsLib：

    function FQ_ladon(ts, lic, aid, rp) {
      var data = String(ts)+'-'+lic+'-'+aid;
      var pad2 = 16 - (data.length % 16);
      var pd = data; for (i=0;i<pad2;i++) pd += String.fromCharCode(pad2);
      var ks = FQ_lrk(rp, aid);
      var ct = '';
      for (var o=0; o<pd.length; o+=16) ct += FQ_lEnc(pd.substr(o,16), ks);
      return FQ_b64(rp + ct);
    }

关键：JS 里所有 64 位运算都是**按字节字符串**做的（FQ_r64 用 charCodeAt 拼小端），
不是真正的整数。Python 里可以直接用整数模拟，只要注意字节序。

用法：
    python3 tools/ladon_ref.py            # 自测
"""

import hashlib
import base64

MASK32 = 0xFFFFFFFF
MASK64 = 0xFFFFFFFFFFFFFFFF


def w(lo, hi):
    """JS 的 FQ_w：一对 32 位半字，构成一个 64 位值。"""
    return ((hi & MASK32) << 32) | (lo & MASK32)


def w_lo(v):
    return v & MASK32


def w_hi(v):
    return (v >> 32) & MASK32


def rol(v, c):
    """JS 的 FQ_rol：64 位循环左移。"""
    c = ((c % 64) + 64) % 64
    if c == 0:
        return v & MASK64
    return ((v << c) | (v >> (64 - c))) & MASK64


def ror(v, c):
    """JS 的 FQ_ror。"""
    return rol(v, 64 - (c % 64))


def xor(*args):
    r = 0
    for a in args:
        r ^= a
    return r & MASK64


def add(a, b):
    return (a + b) & MASK64


def n64(v):
    return (~v) & MASK64


def r64(s: bytes, o: int = 0) -> int:
    """JS 的 FQ_r64：从字节串读 8 字节小端。"""
    chunk = s[o:o + 8]
    chunk = chunk + b"\x00" * (8 - len(chunk))
    return int.from_bytes(chunk, "little")


def w64le2(l: int, r: int) -> bytes:
    """JS 的 FQ_w64le2：两个 64 位值按小端写回 16 字节。"""
    return l.to_bytes(8, "little") + r.to_bytes(8, "little")


# FQ_SZ = FQ_w(0x046d678b, 0x3dc94c3a)
SZ = w(0x046d678b, 0x3dc94c3a)


def zbit(i: int) -> int:
    """JS 的 FQ_zbit：取 FQ_SZ 的第 i 位（0 或 1）。"""
    if i < 32:
        return (w_lo(SZ) >> i) & 1
    return (w_hi(SZ) >> (i - 32)) & 1


def lrk(rp: bytes, aid) -> list:
    """JS 的 FQ_lrk：由 rp 与 aid 派生 34 轮密钥。

    JS:
      var dg = FQ_md5(rp + String(aid));       // 32 位十六进制串
      w[0..3] = FQ_r64(dg, 0/8/16/24)          // 注意 dg 是【字符串】，按 charCode 取
      ...
    """
    # FQ_md5 返回 32 个字符的十六进制字符串；FQ_r64 作用在【该字符串】上，
    # 取的是每个字符的 charCode（ASCII），不是解码后的字节。
    dg = hashlib.md5(rp.decode("latin-1").encode("latin-1") + str(aid).encode()).hexdigest()
    dgb = dg.encode("ascii")

    # JS 里 w 先填 34 个 0，循环里再写 w[x+1]（x 到 0x21=33），
    # 即最多写到下标 34 —— JS 数组会自动扩容，Python 要先给足 35 个。
    ws = [0] * 35
    ws[0] = r64(dgb, 0)
    ws[1] = r64(dgb, 8)
    ws[2] = r64(dgb, 16)
    ws[3] = r64(dgb, 24)

    b0 = ws[0]
    b8 = ws[1]
    q = [ws[2], ws[3]]
    for x in range(0x22):
        x9 = b0
        x8 = b8
        x8 = ror(x8, 8)
        x8 = add(x8, x9)
        x8 = xor(x8, x & MASK64)
        q.append(x8)
        x8 = xor(x8, rol(x9, 3))
        ws[x + 1] = x8
        b0 = x8
        b8 = q.pop(0)
    return ws


def lenc(blk: bytes, keys: list) -> bytes:
    """JS 的 FQ_lEnc：一轮 Feistel（34 轮）。"""
    l = r64(blk, 0)
    r = r64(blk, 8)
    for i in range(34):
        r = xor(keys[i], add(l, ror(r, 8)))
        l = xor(r, ror(l, 61))
    return w64le2(l, r)


def ladon(ts: int, lic: int, aid, rp: bytes) -> str:
    """生成 x-ladon。rp 是 4 字节（JS 传 String.fromCharCode(1,2,3,4)）。"""
    data = f"{ts}-{lic}-{aid}".encode("latin-1")
    pad = 16 - (len(data) % 16)
    pd = data + bytes([pad]) * pad
    ks = lrk(rp, aid)
    ct = b""
    for o in range(0, len(pd), 16):
        ct += lenc(pd[o:o + 16], ks)
    return base64.b64encode(rp + ct).decode()


def js_string_bytes(js: str) -> bytes:
    """JS 的 String.fromCharCode(1,2,3,4) → Python bytes。"""
    return bytes(ord(c) & 0xFF for c in js)


# ---------------- 自测 ----------------
if __name__ == "__main__":
    print("[x-ladon] Python 参考实现自测\n")

    ok = 0
    fail = 0

    def check(name, cond, detail=""):
        global ok, fail
        if cond:
            ok += 1
            print(f"  ✓ {name}")
        else:
            fail += 1
            print(f"  ✗ {name}  {detail}")

    # 基础运算对拍
    check("rol 32 位", rol(0x00000000FFFFFFFF, 32) == 0xFFFFFFFF00000000,
          hex(rol(0x00000000FFFFFFFF, 32)))
    check("ror 是 rol 的逆", ror(rol(0x123456789ABCDEF0, 17), 17) == 0x123456789ABCDEF0)
    check("w() 组装", w(0x046d678b, 0x3dc94c3a) == 0x3dc94c3a046d678b)
    check("zbit(0) = SZ 最低位", zbit(0) == (SZ & 1))
    check("zbit(33) 取高半字第 1 位", zbit(33) == ((SZ >> 33) & 1))
    check("w64le2 小端", w64le2(0x0807060504030201, 0x100f0e0d0c0b0a09)
          == bytes(range(1, 17)))

    # 派生密钥确定性
    rp = js_string_bytes("\x01\x02\x03\x04")
    ks1 = lrk(rp, 1967)
    ks2 = lrk(rp, 1967)
    check("同一输入派生出相同密钥", ks1 == ks2)
    check("密钥长度 35", len(ks1) == 35, str(len(ks1)))
    check("不同 aid 派生出不同密钥", lrk(rp, 1967) != lrk(rp, 3040))

    # ladon 输出格式
    out = ladon(1700000000, 1611921764, 1967, rp)
    check("输出是 base64", all(c in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=" for c in out))
    raw = base64.b64decode(out)
    check("前 4 字节是 rp", raw[:4] == rp, raw[:4].hex())
    check("密文是 16 的倍数", (len(raw) - 4) % 16 == 0, str(len(raw) - 4))
    check("确定性（同 ts 同输出）", ladon(1700000000, 1611921764, 1967, rp) == out)
    check("不同 ts 输出不同", ladon(1700000001, 1611921764, 1967, rp) != out)

    print(f"\n  示例输出: {out}")
    print(f"\n通过 {ok}，失败 {fail}")
    raise SystemExit(1 if fail else 0)

#!/usr/bin/env python3
"""x-argus / x-ladon 签名的 Python 参考实现。

算法逐行照抄自 `fq_app2.json`（🍅番茄小说(APP) 书源）的 jsLib，
并用 Node 跑原版 JS 对拍验证（见 tools/sign_check.py）。

JS 里所有 64 位运算都是按「字节字符串 + charCode」做的，Python 用整数模拟，
关键是字节序：FQ_r64 读小端，FQ_w64le2 写小端。

    x-ladon = FQ_ladon(ts, 1611921764, aid, b"\x01\x02\x03\x04")
    x-argus = FQ_argus(query_string, stub, ts, device_id, iid, aid, random)
"""

import hashlib
import base64
import random as _random

MASK32 = 0xFFFFFFFF
MASK64 = 0xFFFFFFFFFFFFFFFF

# ── 硬编码密钥（JS 原样）────────────────────────────
FQ_SK = "fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304"
FQ_AK = "d31e3718288a1027baab59f146a09a9c"
FQ_AIV = "ea180a0336ed352fcd24e4d50018ae54"
FQ_SZ_LO = 0x046d678b
FQ_SZ_HI = 0x3dc94c3a


# ── 64 位运算（对应 JS 的 FQ_* 系列）────────────────
def w(lo, hi):
    return ((hi & MASK32) << 32) | (lo & MASK32)


SZ = w(FQ_SZ_LO, FQ_SZ_HI)


def rol(v, c):
    c = ((c % 64) + 64) % 64
    return v & MASK64 if c == 0 else ((v << c) | (v >> (64 - c))) & MASK64


def ror(v, c):
    return rol(v, 64 - (c % 64))


def xor(*a):
    r = 0
    for x in a:
        r ^= x
    return r & MASK64


def add(a, b):
    return (a + b) & MASK64


def n64(v):
    return (~v) & MASK64


def r64(s: bytes, o: int = 0) -> int:
    chunk = s[o:o + 8]
    return int.from_bytes(chunk + b"\x00" * (8 - len(chunk)), "little")


def w64le2(l: int, r: int) -> bytes:
    return l.to_bytes(8, "little") + r.to_bytes(8, "little")


def zbit(i: int) -> int:
    return ((SZ >> i) & 1) & MASK32


# ── x-ladon ───────────────────────────────────────
def lrk(rp: bytes, aid) -> list:
    dg = hashlib.md5(rp + str(aid).encode()).hexdigest().encode("ascii")
    ws = [0] * 35                      # JS 会自扩容到 35
    ws[0], ws[1], ws[2], ws[3] = r64(dg, 0), r64(dg, 8), r64(dg, 16), r64(dg, 24)
    b0, b8 = ws[0], ws[1]
    q = [ws[2], ws[3]]
    for x in range(0x22):
        x9, x8 = b0, b8
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
    l, r = r64(blk, 0), r64(blk, 8)
    for i in range(34):
        r = xor(keys[i], add(l, ror(r, 8)))
        l = xor(r, ror(l, 61))
    return w64le2(l, r)


def ladon(ts: int, lic: int, aid, rp: bytes) -> str:
    data = f"{ts}-{lic}-{aid}".encode("latin-1")
    pad = 16 - (len(data) % 16)
    pd = data + bytes([pad]) * pad
    ks = lrk(rp, aid)
    ct = b"".join(lenc(pd[o:o + 16], ks) for o in range(0, len(pd), 16))
    return base64.b64encode(rp + ct).decode()


# ── SM3（国密，JS 手写版逐行对应）──────────────────
def _rotl32(x, n):
    n &= 31
    return ((x << n) | (x >> (32 - n))) & MASK32


def sm3_bytes(s: bytes) -> str:
    V = [0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
         0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e]
    bitLen = len(s) * 8
    arr = bytearray(s)
    arr.append(0x80)
    while len(arr) % 64 != 56:
        arr.append(0)
    hi, lo = bitLen >> 32, bitLen & MASK32
    arr += bytes([(hi >> 24) & 255, (hi >> 16) & 255, (hi >> 8) & 255, hi & 255,
                  (lo >> 24) & 255, (lo >> 16) & 255, (lo >> 8) & 255, lo & 255])
    for b in range(0, len(arr), 64):
        W = []
        for i in range(16):
            W.append(((arr[b + i * 4] << 24) | (arr[b + i * 4 + 1] << 16)
                      | (arr[b + i * 4 + 2] << 8) | arr[b + i * 4 + 3]) & MASK32)
        for j in range(16, 68):
            x = (W[j - 16] ^ W[j - 9] ^ _rotl32(W[j - 3], 15)) & MASK32
            x = (x ^ _rotl32(x, 15) ^ _rotl32(x, 23)) & MASK32
            W.append((x ^ _rotl32(W[j - 13], 7) ^ W[j - 6]) & MASK32)
        W1 = [(W[j] ^ W[j + 4]) & MASK32 for j in range(64)]
        A, B, C, D, E, F, G, H = V
        for j in range(64):
            Tj = 0x79cc4519 if j < 16 else 0x7a879d8a
            SS1 = _rotl32((_rotl32(A, 12) + E + _rotl32(Tj, j & 31)) & MASK32, 7)
            SS2 = SS1 ^ _rotl32(A, 12)
            FFj = (A ^ B ^ C) & MASK32 if j < 16 else ((A & B) | (A & C) | (B & C)) & MASK32
            GGj = (E ^ F ^ G) & MASK32 if j < 16 else ((E & F) | ((~E) & G)) & MASK32
            TT1 = (FFj + D + SS2 + W1[j]) & MASK32
            TT2 = (GGj + H + SS1 + W[j]) & MASK32
            D = C
            C = _rotl32(B, 9)
            B = A
            A = TT1
            H = G
            G = _rotl32(F, 19)
            F = E
            E = (TT2 ^ _rotl32(TT2, 9) ^ _rotl32(TT2, 17)) & MASK32
        V = [(V[0] ^ A) & MASK32, (V[1] ^ B) & MASK32, (V[2] ^ C) & MASK32, (V[3] ^ D) & MASK32,
             (V[4] ^ E) & MASK32, (V[5] ^ F) & MASK32, (V[6] ^ G) & MASK32, (V[7] ^ H) & MASK32]
    return "".join(("0000000" + format(v, "x"))[-8:] for v in V)


def sm3(text: str) -> str:
    """JS 的 FQ_sm3 接收的是字符串，按 charCode 取低字节。"""
    return sm3_bytes(bytes(ord(c) & 0xFF for c in text))


def hex_to_str(h: str) -> str:
    return "".join(chr(int(h[i:i + 2], 16)) for i in range(0, len(h), 2))


def str_to_bytes(s: str) -> bytes:
    return bytes(ord(c) & 0xFF for c in s)


# ── x-argus 的自定义分组加密（对应 FQ_srk / FQ_sEnc）──
def srk(key: bytes) -> list:
    ws = [r64(key, i * 8) for i in range(4)]
    for x in range(4, 72):
        m = xor(ror(ws[x - 1], 3), ws[x - 3])
        m = xor(m, ror(m, 1))
        ws.append(xor(n64(ws[x - 4]), m, zbit((x - 4) % 62), w(3, 0)))
    return ws


def senc(blk: bytes, keys: list) -> bytes:
    l, r = r64(blk, 0), r64(blk, 8)
    for i in range(len(keys)):
        o = r
        r1, r8 = rol(r, 1), rol(r, 8)
        nl = w(r1 & MASK32 & (r8 & MASK32), ((r1 >> 32) & (r8 >> 32)) & MASK32)
        # JS: FQ_w(FQ_rol(r,1).lo & FQ_rol(r,8).lo, FQ_rol(r,1).hi & FQ_rol(r,8).hi)
        nl = w((r1 & MASK32) & (r8 & MASK32), ((r1 >> 32) & MASK32) & ((r8 >> 32) & MASK32))
        r = xor(l, nl, rol(r, 2), keys[i])
        l = o
    return w64le2(l, r)


# ── protobuf 手写编码（对应 FQ_ev / FQ_pbv / FQ_pbb）──
def ev(v: int) -> str:
    o = ""
    while v >= 0x80:
        o += chr((v % 0x80) | 0x80)
        v //= 0x80
    return o + chr(v)


def pbv(f: int, v: int) -> str:
    return ev(f * 8) + ev(v)


def pbb(f: int, v) -> str:
    e = chr(v) if isinstance(v, int) else str(v)
    return ev(f * 8 + 2) + ev(len(e)) + e


def _pad(s: str) -> str:
    p = 16 - (len(s) % 16)
    if p == 0:
        p = 16
    return s + chr(p) * p


def aes_cbc_encrypt(data: bytes, key: bytes, iv: bytes) -> bytes:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    c = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    return c.update(data) + c.finalize()


def qp(query: str, k: str) -> str:
    """JS 的 FQ_qp：从 query string 取参数。"""
    for part in (query or "").split("&"):
        i = part.find("=")
        if i > 0 and part[:i] == k:
            return part[i + 1:]
    return ""


def argus_bean(q: str, stub: str, ts: int, dev: str, iid: str, aid, rv: int) -> str:
    import re
    bi = hex_to_str(stub) if re.fullmatch(r"[0-9a-fA-F]{32}", stub or "") else hex_to_str("0" * 32)
    bh = hex_to_str(sm3(bi)[:12])
    qh = hex_to_str(sm3(hex_to_str("0" * 32))[:12]) if q == "" else hex_to_str(sm3(q)[:12])
    nest = pbv(1, 1) + pbv(2, 1) + pbv(3, 1) + pbv(7, 0xC792ECCC)
    s23 = pbb(1, "NX551J") + pbv(2, 8196) + pbv(4, 0x80E0D800)
    return (pbv(1, 1077940818) + pbv(2, 2) + pbv(3, rv)
            + pbb(4, str(aid)) + pbb(5, dev) + pbb(6, "1611921764")
            + pbb(7, qp(q, "version_name")) + pbb(8, "v04.04.05-ov-android")
            + pbv(9, 134744640) + pbb(10, hex_to_str("0" * 16))
            + pbv(11, 0) + pbv(12, ts * 2) + pbb(13, bh) + pbb(14, qh)
            + pbb(15, nest) + pbb(16, iid) + pbb(20, "none")
            + pbv(21, 738) + pbb(23, s23) + pbv(25, 2))


def argus(q: str, stub: str, ts: int, dev: str, iid: str, aid, rv: int) -> str:
    pb = _pad(argus_bean(q, stub, ts, dev, iid, aid, rv))
    keys = srk(bytes.fromhex(FQ_SK))
    pbb_bytes = str_to_bytes(pb)
    enc = b"".join(senc(pbb_bytes[o:o + 16], keys) for o in range(0, len(pbb_bytes), 16))
    magic = bytes.fromhex("f2f7fcfff2f7fcff")
    un = magic + enc + bytes.fromhex("eafb2cfe8568519154")
    buf = bytearray(un[:8])
    for i in range(8, len(un)):
        buf.append((un[i] ^ magic[i % 8]) & 255)
    rev = bytes(reversed(buf))
    fin = str_to_bytes(_pad(bytes(rev).decode("latin-1") + "ao"))
    ct = aes_cbc_encrypt(fin, bytes.fromhex(FQ_AK), bytes.fromhex(FQ_AIV))
    return base64.b64encode(bytes.fromhex("f281") + ct).decode()


def sign(q: str, stub: str, ts: int, dev: str, iid: str, aid, rv: int) -> dict:
    """对应 JS 的 FQ_sign：返回该加的四个请求头。"""
    h = {
        "x-ss-req-ticket": str(ts * 1000),
        "x-khronos": str(ts),
        "x-argus": argus(q, stub, ts, dev, iid, aid, rv),
        "x-ladon": ladon(ts, 1611921764, aid, b"\x01\x02\x03\x04"),
    }
    if stub:
        h["x-ss-stub"] = stub
    return h


if __name__ == "__main__":
    rp = b"\x01\x02\x03\x04"
    print("[x-ladon]")
    print("  ", ladon(1700000000, 1611921764, 1967, rp))
    print("\n[x-argus]")
    print("  ", argus("a=1&version_name=7.0.8", "0" * 32, 1700000000, "1234567890", "987654321", 1967, 12345))
    print("\n[sm3] 'abc' =", sm3("abc"))
    print("  期望 66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0")

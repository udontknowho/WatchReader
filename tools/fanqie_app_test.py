#!/usr/bin/env python3
"""番茄 APP 接口验证：设备注册 → 取付费章正文。

目的只有一个：**APP 接口返回的是完整正文，还是另一段试读片段？**

通了 → B 路线成立，可以往 Kotlin 移植。
不通 → 掉头，别浪费时间。

用法：python3 tools/fanqie_app_test.py
"""
import gzip
import hashlib
import json
import os
import random
import string
import sys
import time
import uuid

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from argus_ref import ladon, sign, str_to_bytes  # noqa: E402

UA_REG = ("com.dragon.read/65132 (Linux; U; Android 10; zh_CN; MI 12; "
          "Build/MMB29M; Cronet/TTNetVersion:9ac8d95c 2024-11-25 QuicVersion:3f326df4 2024-11-14)")
UA_API = "com.dragon.read"


# ── 注册用的 AES（FQ_ttEnc 里的算法）──────────────────
FQ_FIXED = "TdTC5rgxYgkOUrPHpnM7pByyRiuCmrWKGWs521cXdST0m69/COjWjSanLjfBqVovHwWlGJKu8pSXMrYqOKrdWA=="


def tt_enc(data: str) -> bytes:
    """对应 JS 的 FQ_ttEnc：随机前缀 + SHA-512 派生密钥 + AES-CBC。"""
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    rnd = bytes(random.randrange(256) for _ in range(32))
    inner = hashlib.sha512(rnd).digest()
    fixed_b = __import__("base64").b64decode(FQ_FIXED)
    hv = hashlib.sha512(inner + fixed_b).digest()
    key, iv = hv[:16], hv[16:32]
    dh = hashlib.sha512(str_to_bytes(data)).digest()
    payload = str_to_bytes(_pad_latin1(dh + str_to_bytes(data)))
    c = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    ct = c.update(payload) + c.finalize()
    return bytes([0x74, 0x63, 0x05, 0x10, 0, 0]) + rnd + ct


def _pad_latin1(b: bytes) -> str:
    p = 16 - (len(b) % 16)
    return b.decode("latin-1") + chr(p) * p


def gz(s: str) -> bytes:
    """对应 FQ_gz：gzip 后把第 8、9 字节改成 2、3。"""
    raw = bytearray(gzip.compress(str_to_bytes(s), mtime=0))
    raw[8] = 2
    raw[9] = 3
    return bytes(raw)


def alnum(n):
    return "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(n))


def register_one(sess) -> dict | None:
    """对应 JS 的 FQ_registerOne：注册一个假设备身份。"""
    openudid = alnum(16)
    cdid = str(uuid.uuid4())
    clientudid = str(uuid.uuid4())

    header = {
        "display_name": "番茄小说", "update_version_code": "65132",
        "manifest_version_code": "65132", "aid": 1967,
        "channel": "xiaomi_1967_64", "package": "com.dragon.read",
        "app_version": "6.5.1.32", "version_code": "65132",
        "sdk_version": "v04.04.05-ov-android", "sdk_target_version": 29,
        "os": "Android", "os_version": "10", "os_api": "29",
        "device_model": "MI 12", "device_brand": "Xiaomi",
        "device_manufacturer": "Google", "device_category": "phone",
        "cpu_abi": "arm64-v8a", "release_build": str(uuid.uuid4()),
        "density_dpi": "560", "display_density": "mdpi",
        "resolution": "1440x2392", "language": "zh",
        "mac": "11:22:33:44:55:66", "timezone": 8, "access": "wifi",
        "not_request_sender": 0, "carrier": "CHINA MOBILE", "mcc_mnc": "46007",
        "rom": "EMUI-" + alnum(13), "rom_version": alnum(2),
        "sig_hash": hashlib.md5(str_to_bytes(str(uuid.uuid4()))).hexdigest(),
        "openudid": openudid, "clientudid": clientudid,
        "sim_serial_number": [], "region": "CN",
        "tz_name": "Asia/Shanghai", "tz_offset": 28800, "sim_region": "cn",
    }
    js = json.dumps({"magic_tag": "ss_app_log", "header": header,
                     "_gen_time": int(time.time() * 1000)}, separators=(",", ":"))
    post = tt_enc(gz(js).decode("latin-1"))

    ts = int(time.time())
    P = [
        ["tt_data", "a"], ["os_api", "29"], ["device_type", "MI 12"], ["ssmix", "a"],
        ["manifest_version_code", "65132"], ["dpi", "560"], ["version_name", "6.5.1.32"],
        ["ts", str(ts)], ["cpu_support64", "true"], ["app_type", "normal"],
        ["appTheme", "light"], ["ac", "wifi"], ["host_abi", "arm64-v8a"],
        ["update_version_code", "65132"], ["channel", "xiaomi_1967_64"],
        ["_rticket", str(int(time.time() * 1000))], ["device_platform", "android"],
        ["version_code", "65132"], ["cdid", cdid], ["os", "android"],
        ["is_android_pad", "0"], ["openudid", openudid], ["package", "com.dragon.read"],
        ["resolution", "1440*2392"], ["os_version", "10"], ["language", "zh"],
        ["device_brand", "Xiaomi"], ["need_personal_recommend", "1"], ["aid", "1967"],
        ["minor_status", "0"], ["app_name", "novel"], ["mcc_mnc", "46007"],
    ]
    P.sort(key=lambda x: x[0])
    from urllib.parse import quote
    qs = "&".join(f"{k}={quote(str(v), safe='')}" for k, v in P)

    url = "https://log.snssdk.com/service/2/device_register/?" + qs
    r = sess.post(url, data=post, headers={
        "Content-Type": "application/octet-stream;tt-data=a",
        "accept-encoding": "gzip", "user-agent": UA_REG, "connection": "Keep-Alive",
    }, timeout=20)

    body = r.text
    import re
    m = re.search(r'"device_id_str":"([0-9]+)"', body)
    m2 = re.search(r'"install_id_str":"([0-9]+)"', body)
    if not m:
        print(f"    [注册] HTTP {r.status_code}，响应前 300 字：{body[:300]}")
        return None
    return {"d": m.group(1), "i": m2.group(1) if m2 else "", "o": openudid, "c": cdid}


def api_get(sess, url: str, dev: str, iid: str, aid=1967, stub="", ua=UA_API):
    """带签名的 GET。"""
    ts = int(time.time())
    q = url.split("?", 1)[1] if "?" in url else ""
    h = sign(q, stub, ts, dev, iid, aid, random.randrange(2_000_000_000))
    h["User-Agent"] = ua
    return sess.get(url, headers=h, timeout=25)


def main():
    sess = requests.Session()
    sess.trust_env = False

    print("=" * 62)
    print("番茄 APP 接口验证")
    print("=" * 62)

    # ── 1. 设备注册 ──
    print("\n[1] 注册设备身份 …")
    dev = register_one(sess)
    if not dev:
        print("  ✗ 注册失败，停在这里")
        return 1
    print(f"  ✓ device_id = {dev['d']}")
    print(f"    install_id = {dev['i']}")

    # ── 2. 搜索（验证签名可用）──
    print("\n[2] 搜索「斗破苍穹」（验证签名）…")
    base = ("https://api.fanqiesdk.com/api/novel/channel/homepage/search/search/v1/"
            "?q={kw}&offset=0&enterfrom_aid=&enter_from=inner_search&app_name=news_article"
            "&version_name=7.0.8&app_version=7.0.8&channel=tt_huawei2019_yz&version_code=708"
            "&device_platform=android&parent_enterfrom=novel_list&novel_host=&aid=13"
            "&scm_version=1.0.0.4112&device_type=25053RT47C&device_brand=Redmi&language=zh"
            "&os_api=35&os_version=15&openudid=fqapp&update_version_code=70899&plugin=0"
            "&tma_jssdk_version=1.10.0.0&rom_version=miui__os2.0.209.0.volcnxm")
    from urllib.parse import quote
    url = base.replace("{kw}", quote("斗破苍穹"))
    try:
        r = api_get(sess, url, dev["d"], dev["i"], aid=13,
                    ua="Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36")
        print(f"  HTTP {r.status_code}，{len(r.text)} 字节")
        d = r.json()
        rd = (d.get("data") or {}).get("ret_data") or []
        print(f"  命中 {len(rd)} 本")
        for it in rd[:3]:
            print(f"    {it.get('title')} / {it.get('author')} / id={it.get('book_id')}")
    except Exception as e:
        print(f"  ✗ 搜索失败：{e}")

    # ── 3. 取目录（APP 接口）──
    print("\n[3] 取《斗破苍穹：萧炎归来》目录 …")
    bid = "7542602438201576472"
    base_q = ("ac=wifi&channel=xiaomi_1967_64&aid=1967&app_name=novelapp"
              "&version_code=65132&version_name=6.5.1.32&device_platform=android"
              "&os=android&ssmix=a&device_type=FRD-AL10&device_brand=honor&language=zh"
              "&os_api=28&os_version=9&manifest_version_code=65132"
              "&resolution=1080*1920&dpi=480&update_version_code=65132&pv_player=65132"
              "&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0"
              "&host_abi=arm64-v8a&dragon_device_type=phone&compliance_status=0")
    dirurl = ("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/directory/all_items/v"
              f"?book_type=0&item_data_list_md5=&catalog_data_md5=&book_id={bid}"
              f"&book_info_md5=&need_version=true&device_id={dev['d']}&" + base_q)
    chapters = []
    try:
        r = api_get(sess, dirurl, dev["d"], dev["i"], aid=1967,
                    ua="Mozilla/5.0 (Linux; Android 13)")
        print(f"  HTTP {r.status_code}，{len(r.text)} 字节")
        d = r.json()
        cats = (d.get("data") or {}).get("catalog_data") or []
        chapters = [(str(c.get("item_id")), str(c.get("catalog_title") or "")) for c in cats]
        print(f"  目录 {len(chapters)} 章")
        for cid, t in chapters[:3]:
            print(f"    {cid}  {t}")
    except Exception as e:
        print(f"  ✗ 目录失败：{e}")

    if not chapters:
        return 1

    # ── 4. 取正文（关键：看是不是完整章节）──
    print("\n[4] 取正文通道（关键验证）…")
    for idx in (0, 1, 50):
        if idx >= len(chapters):
            continue
        cid, title = chapters[idx]
        try:
            txt = dh_get(sess, cid, dev["d"], dev["i"])
            head = txt.replace("\n", " ")[:60]
            print(f"  [{idx+1}] {title[:24]:26} {len(txt):6} 字  {head}")
        except Exception as e:
            print(f"  [{idx+1}] {title[:24]:26} ✗ {str(e)[:70]}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())


# ── 正文通道（DH 密钥交换 + AES 解密）──────────────────
FQ_DHP = ("241031242692103258855207602219756607485695054850245994265411694195810883168"
          "261222889009385826134161467322714147790401219650364895705058263194273070680"
          "500922306273474534107340669624601458936165977404102716924945320037872943417"
          "032584377865919814376319377685986952408894019557734611984354530154704374720"
          "774996976375008430892633929555996888245787241299381012913029459299994792636"
          "526405928464720973038494721168143446471443848852094012745984428885933652689"
          "6320919633919")
FQ_DHU = ("https://novel.snssdk.com/api/novel/book/reader/content/v1"
          "?aid=13&app_name=novelapp&channel=0&device_platform=android"
          "&device_type=25053RT47C&os_version=10&support_image=1&version_code=66.9")
UA_DH = ("com.ss.android.article.news/13400 (Linux; U; Android 10; zh_CN; tb8788p1_64_bsp; "
         "Build/QQ3A.200805.001; Cronet/TTNetVersion:fc4cebd3 2024-12-10 QuicVersion:d9628e3d 2024-10-11)")


def dh_get(sess, item_id: str, dev: str, iid: str) -> str:
    """对应 JS 的 FQ_dhGet：DH 交换 + AES-CBC 解密拿正文。"""
    import base64 as b64
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    P = int(FQ_DHP)
    a = int.from_bytes(os.urandom(256), "big")
    pub = pow(2, a, P)

    iv = os.urandom(16)
    key_b = b64.b64decode("rCXGfd2POMGzeiNIgo4iLg==")

    # pub 转成 JS 的 toByteArray（有符号大端），再用 latin-1 字符串补齐
    pub_b = pub.to_bytes((pub.bit_length() + 8) // 8, "big")
    pub_s = pub_b.decode("latin-1")
    p = 16 - (len(pub_s) % 16)
    padded = str_to_bytes(pub_s + chr(p) * p)
    c1 = Cipher(algorithms.AES(key_b), modes.CBC(iv)).encryptor()
    ct = c1.update(padded) + c1.finalize()
    y = b64.b64encode(iv + ct).decode()

    url = FQ_DHU + "&item_id=" + item_id
    ts = int(time.time())
    q = url.split("?", 1)[1]
    h = sign(q, "", ts, dev, iid, 13, random.randrange(2_000_000_000))
    h["User-Agent"] = UA_DH
    h["y"] = y

    r = sess.get(url, headers=h, timeout=25)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    sy = r.headers.get("y", "")
    sc = r.headers.get("c", "")
    if not sy or not sc:
        raise RuntimeError(f"缺解密参数 y={bool(sy)} c={bool(sc)}")

    shared = pow(int.from_bytes(b64.b64decode(sy), "big"), a, P)
    sh = shared.to_bytes((shared.bit_length() + 8) // 8, "big")
    sh = sh[-384:] if len(sh) > 384 else sh
    aes_key = bytes.fromhex(sh.hex()[:64])[:32]

    cb = b64.b64decode(sc)
    c2 = Cipher(algorithms.AES(aes_key), modes.CBC(cb[:16])).decryptor()
    pt = c2.update(cb[16:]) + c2.finalize()
    pad = pt[-1]
    if 0 < pad <= 16:
        pt = pt[:-pad]
    return pt.decode("utf-8", "ignore")

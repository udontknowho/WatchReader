"""番茄 APP 接口端到端验证：注册 → 目录 → 正文。

已验证结论（2026-10-16）：APP 接口返回【完整正文】（12000+ 字），
而网页端只有试读片段（185 字）。

两个必须记住的坑：
1. 共享密钥派生：shared 只有 192 字节，**不能** to_bytes(384) 补零后取 hex 前 64，
   那会得到全零密钥。必须先去前导零再取前 32 字节（对齐 JS 的 sB 处理）。
2. c 头为 '1' 或空时，真正的密文在【响应体 data.content】里，不是 c 头。
   这个替换必须在 base64 解码【之前】做。

用法：python3 tools/fanqie_app_verify.py
"""
import sys, time, random, requests, base64, os, json as _j
sys.path.insert(0,'tools')
from fanqie_app_test import register_one, sign, FQ_DHP, FQ_DHU, UA_DH, api_get
from argus_ref import str_to_bytes
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
sess = requests.Session(); sess.trust_env=False
dev = register_one(sess)
d = api_get(sess,"https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/directory/all_items/v"
 "?book_type=0&item_data_list_md5=&catalog_data_md5=&book_id=7542602438201576472&book_info_md5="
 "&need_version=true&device_id="+dev['d']+
 "&ac=wifi&channel=xiaomi_1967_64&aid=1967&app_name=novelapp&version_code=65132"
 "&version_name=6.5.1.32&device_platform=android&os=android&ssmix=a&device_type=FRD-AL10"
 "&device_brand=honor&language=zh&os_api=28&os_version=9&manifest_version_code=65132"
 "&resolution=1080*1920&dpi=480&update_version_code=65132&pv_player=65132"
 "&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0&host_abi=arm64-v8a"
 "&dragon_device_type=phone&compliance_status=0", dev['d'], dev['i'], 1967,
 ua="Mozilla/5.0 (Linux; Android 13)").json()
items = (d.get('data') or {}).get('item_data_list') or []
P = int(FQ_DHP)

def derive(shared):
    b = shared.to_bytes(384,'big'); j=0
    while j < len(b)-1 and b[j]==0: j+=1
    return b[j:][:32]

def fetch(cid):
    a = int.from_bytes(os.urandom(256),'big'); pub = pow(2,a,P)
    iv = os.urandom(16); kb = base64.b64decode("rCXGfd2POMGzeiNIgo4iLg==")
    pb = pub.to_bytes((pub.bit_length()+8)//8,'big')
    p = 16-(len(pb.decode('latin-1'))%16)
    c1 = Cipher(algorithms.AES(kb), modes.CBC(iv)).encryptor()
    y = base64.b64encode(iv+c1.update(str_to_bytes(pb.decode('latin-1')+chr(p)*p))+c1.finalize()).decode()
    url = FQ_DHU+"&item_id="+cid
    h = sign(url.split("?",1)[1],"",int(time.time()),dev['d'],dev['i'],13,random.randrange(2_000_000_000))
    h["User-Agent"]=UA_DH; h["y"]=y
    r = sess.get(url, headers=h, timeout=25)
    sy=r.headers.get('y',''); sc=r.headers.get('c','')
    dc = (_j.loads(r.text).get('data') or {}).get('content') or ''
    if sc in ('1','') and dc: sc = dc
    shared = pow(int.from_bytes(base64.b64decode(sy),'big'), a, P)
    cb = base64.b64decode(sc)
    c2 = Cipher(algorithms.AES(derive(shared)), modes.CBC(cb[:16])).decryptor()
    pt = c2.update(cb[16:])+c2.finalize()
    pad = pt[-1]
    if 0 < pad <= 16 and pad <= len(pt): pt = pt[:-pad]
    return pt.decode('utf-8','ignore')

print("修正密钥派生后：")
for idx in (0,1,2,50,100,300,470):
    if idx >= len(items): continue
    it=items[idx]
    try:
        t = fetch(str(it['item_id']))
        han = sum(1 for c in t[:300] if '\u4e00'<=c<='\u9fff')
        print(f"  [{idx+1:3}] {str(it.get('title'))[:20]:22} {len(t):6} 字  汉字{han:3}  {t[:46].replace(chr(10),' ')}")
    except Exception as e:
        print(f"  [{idx+1:3}] ✗ {str(e)[:60]}")
    time.sleep(0.4)

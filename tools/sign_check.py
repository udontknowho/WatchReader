#!/usr/bin/env python3
"""签名算法对拍：Python 参考实现 vs 原版 JS（用 node 跑）。

书源里那 30 KB 的签名代码原样抽出来跑，只把 Packages.java.* 换成 Node 原生，
然后与 tools/argus_ref.py 的输出逐字节比对。

用法：python3 tools/sign_check.py
"""
import json, re, subprocess, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import argus_ref as A

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'fq_app2.json')
TMP = os.path.join(ROOT, 'scratch')

SHIM = """
const _c = require('crypto');
function FQ_s2j(s){ return Buffer.from(String(s),'latin1'); }
function FQ_j2s(b){ return Buffer.from(b).toString('latin1'); }
function FQ_hex(b){ var str=FQ_j2s(b), s=''; for(var i=0;i<str.length;i++){var v=str.charCodeAt(i)&255; s+=(v<16?'0':'')+v.toString(16);} return s; }
function FQ_md5(s){ return _c.createHash('md5').update(FQ_s2j(s)).digest('hex'); }
function FQ_b64(s){ return Buffer.from(FQ_s2j(s)).toString('base64'); }
function FQ_b64d(s){ return FQ_j2s(Buffer.from(String(s),'base64')); }
function FQ_unhex(h){ var r=''; for(var i=0;i<h.length;i+=2) r+=String.fromCharCode(parseInt(h.substr(i,2),16)); return r; }
function FQ_aesCbc(data,key,iv){ const c=_c.createCipheriv('aes-128-cbc', Buffer.from(FQ_s2j(key)), Buffer.from(FQ_s2j(iv))); c.setAutoPadding(false); return FQ_j2s(Buffer.concat([c.update(Buffer.from(FQ_s2j(data))), c.final()])); }
"""

FUNCS = ['FQ_w','FQ_xor','FQ_n64','FQ_add','FQ_rol','FQ_ror','FQ_r64','FQ_w64le2','FQ_zbit',
         'FQ_lrk','FQ_lEnc','FQ_ladon','FQ_srk','FQ_sEnc','FQ_sm3','FQ_ev','FQ_pbv','FQ_pbb',
         'FQ_pad','FQ_argusBean','FQ_argus','FQ_qp']

def extract(src, name):
    m = re.search(r'function ' + re.escape(name) + r'\([^)]*\)\{', src)
    if not m: return None
    i=m.start(); depth=0; j=i
    while j < len(src):
        if src[j]=='{': depth+=1
        elif src[j]=='}':
            depth-=1
            if depth==0: break
        j+=1
    return src[i:j+1]

def cut(src, name):
    m = re.search(r'function ' + re.escape(name) + r'\([^)]*\)\{', src)
    if not m: return src
    i=m.start(); depth=0; j=i
    while j < len(src):
        if src[j]=='{': depth+=1
        elif src[j]=='}':
            depth-=1
            if depth==0: break
        j+=1
    return src[:i]+src[j+1:]

def main():
    jl = json.load(open(SRC, encoding='utf-8'))[0]['jsLib']
    parts = []
    m = re.search(r'var (FQ_SZ\s*=\s*FQ_w\([^)]*\));', jl)
    if m: parts.append(m.group(1)+';')
    parts.append('var FQ_SK="%s",FQ_AK="%s",FQ_AIV="%s";' % (
        re.search(r"FQ_SK='([^']+)'",jl).group(1),
        re.search(r"FQ_AK='([^']+)'",jl).group(1),
        re.search(r"FQ_AIV='([^']+)'",jl).group(1)))
    for fn in FUNCS:
        s = extract(jl, fn)
        if s: parts.append(s)
    parts.append("""
console.log("LADON " + FQ_ladon(1700000000,1611921764,1967,String.fromCharCode(1,2,3,4)));
console.log("ARGUS " + FQ_argus("a=1&version_name=7.0.8","00000000000000000000000000000000",1700000000,"1234567890","987654321",1967,12345));
console.log("SM3 " + FQ_sm3("abc"));
""")
    algo = '\n'.join(parts)
    for fn in ['FQ_md5','FQ_b64','FQ_b64d','FQ_hex','FQ_j2s','FQ_s2j','FQ_unhex','FQ_aesCbc']:
        algo = cut(algo, fn)
    os.makedirs(TMP, exist_ok=True)
    jsfile = os.path.join(TMP, 'js_sign_run.js')
    open(jsfile, 'w').write(SHIM + '\n' + algo)

    out = subprocess.run(['node', jsfile], capture_output=True, text=True).stdout
    js = {}
    for line in out.splitlines():
        p = line.strip().split(' ', 1)
        if len(p) == 2: js[p[0]] = p[1]

    cases = [
        ("SM3('abc')", A.sm3('abc'), js.get('SM3', '')),
        ("x-ladon", A.ladon(1700000000, 1611921764, 1967, b'\x01\x02\x03\x04'), js.get('LADON', '')),
        ("x-argus", A.argus("a=1&version_name=7.0.8", "0"*32, 1700000000, "1234567890", "987654321", 1967, 12345), js.get('ARGUS', '')),
    ]
    ok = fail = 0
    print("=== Python 参考实现 vs 原版 JS ===")
    for name, py, jv in cases:
        same = (py == jv and py != '')
        ok += same; fail += (not same)
        print(f"  {'✓' if same else '✗'} {name}")
        if not same:
            print(f"      Python: {py[:90]}")
            print(f"      JS    : {jv[:90]}")
    print(f"\n一致 {ok}/{len(cases)}")
    return 1 if fail else 0

if __name__ == '__main__':
    raise SystemExit(main())

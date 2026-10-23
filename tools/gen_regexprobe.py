#!/usr/bin/env python3
"""生成 src/com/watchdl/core/RegexProbe.kt —— 设备端正则自检清单。

扫描两处：
1. `src/com/watchdl/core/*.kt` 里所有 `Regex("...")` 字面量
2. `app/assets/sources/legado.json` 里所有 `##正则##` 的正则正文

用途：Android 的 java.util.regex 是 ICU 后端，比 JVM 严格。
本机编译通过不代表设备能用，必须在设备上跑一遍。

    python3 tools/gen_regexprobe.py
"""
import json, re, glob, os

CORE = 'src/com/watchdl/core'
SOURCES = 'app/assets/sources/legado.json'
TARGET = 'src/com/watchdl/core/RegexProbe.kt'


def kt_literal_to_regex(body: str) -> str:
    """把 Kotlin 普通字符串字面量的内容还原成真实字符串。"""
    out, i = [], 0
    mp = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', '\\': '\\', '$': '$'}
    while i < len(body):
        c = body[i]
        if c == '\\' and i + 1 < len(body):
            n = body[i + 1]
            out.append(mp.get(n, '\\' + n))
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def esc(t: str) -> str:
    """转义成 Kotlin 普通字符串字面量。$ 必须转义，否则被当模板起始。"""
    t = t.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$')
    return t.replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')


def collect_literals():
    pats = []
    rx = re.compile(r'Regex\(\s*("""[\s\S]*?"""|"[^"\n]*")\s*\)')
    for f in sorted(glob.glob(f'{CORE}/*.kt')):
        if f.endswith('RegexProbe.kt'):
            continue
        src = open(f, encoding='utf-8').read()
        for m in rx.finditer(src):
            raw = m.group(1)
            body = raw[3:-3] if raw.startswith('"""') else raw[1:-1]
            line = src[:m.start()].count('\n') + 1
            pats.append((f'{os.path.basename(f)}:{line}', kt_literal_to_regex(body)))
    return dedupe(pats)


def collect_source_regexes():
    data = json.load(open(SOURCES, encoding='utf-8'))
    pats = []
    for s in data:
        name = s.get('bookSourceName', '')
        pairs = []
        for k, v in s.items():
            if isinstance(v, dict):
                pairs += [(f'{k}.{kk}', vv) for kk, vv in v.items()]
            else:
                pairs.append((k, v))
        for key, v in pairs:
            if not isinstance(v, str) or '##' not in v:
                continue
            parts = v.split('##')
            if len(parts) >= 2 and parts[1]:
                pats.append((f'{name[:14]} {key}', parts[1]))
    return dedupe(pats)


def dedupe(pats):
    seen, out = set(), []
    for label, p in pats:
        if p in seen:
            continue
        seen.add(p)
        out.append((label, p))
    return out


def block(pairs):
    return '\n'.join(f'        "{esc(l)}" to "{esc(p)}",' for l, p in pairs)


def main():
    lits = collect_literals()
    srcs = collect_source_regexes()
    out = f'''package com.watchdl.core

/**
 * 设备端正则自检清单。
 *
 * **自动生成，不要手改** —— 由 `tools/gen_regexprobe.py` 生成。
 *
 * 用途：Android 的 `java.util.regex` 是 ICU 后端，比 JVM 严格。
 * 本机把全部正则编译通过，设备上却炸了 45 条 ——
 * 所以必须在设备上跑一遍才知道真假。
 */
object RegexProbe {{

    /** 工程自带的字面正则（{len(lits)} 条）。 */
    val ALL: List<Pair<String, String>> = listOf(
{block(lits)}
    )

    /**
     * 书源里 `##正则##` 的正则正文（{len(srcs)} 条）。
     *
     * 这些是**第三方写的正则**，最可疑 —— JVM 说是合法的，ICU 可能不认。
     */
    val FROM_SOURCES: List<Pair<String, String>> = listOf(
{block(srcs)}
    )
}}
'''
    open(TARGET, 'w', encoding='utf-8').write(out)
    print(f'生成 {len(lits)} 条字面正则 + {len(srcs)} 条书源正则 -> {TARGET}')


if __name__ == '__main__':
    main()

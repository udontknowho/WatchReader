"""从 reicon v1.2.5 npm 包里抽 Outline 路径，生成 Kotlin 数据表。

用法：
    python3 gen_reicon.py <解压出来的 package/icons 目录> <输出 .kt>
"""
import re
import sys

# 应用里的图标名 → reicon 的 PascalCase 图标名
MAP = {
    "back": "ArrowLeft",
    "chevron": "ChevronRight",
    "menu": "Menu",
    "search": "Search",
    "more": "Menu2",
    "settings": "Settings",
    "refresh": "Refresh",
    "download": "Download",
    "list": "List",
    "folder": "Folder",
    "file": "File",
    "grid": "Grid",
    "plus": "Plus",
    "close": "X",
    "delete": "Trash",
    "play": "Play",
    "stop": "Stop",
    "bookmark": "Bookmark",
    "brightness": "Sun",
    "swap": "ArrowSwapHorizontal",
    "history": "History",
    "shelf": "Library",
    "explore": "Compass",
    "mine": "User",
    "warning": "Warning",
    "check": "Check",
    "cloud": "Cloud",
    "wifi": "Wifi",
    "save": "Save",
    "link": "Link",
    "edit": "Pen",
    "upload": "Upload",
    "carousel": "CarouselH",
    "select_all": "Checklist",
}

PATH_RE = re.compile(r'<(?!/)(path|polyline|line|circle|rect|polygon)\b([^>]*)>')
D_RE = re.compile(r'\s(?:d|points)="([^"]+)"')
EVENODD_RE = re.compile(r'fill-rule="evenodd"')
STROKE_RE = re.compile(r'\sstroke="(?!none)[^"]*"')


def outline_body(path):
    src = open(path, encoding="utf-8").read()
    m = re.search(r"O:\s*`([^`]*)`", src)
    if not m:
        return None
    body = m.group(1)
    out = []
    for tag, attrs in PATH_RE.findall(body):
        dm = D_RE.search(attrs)
        if not dm:
            continue
        d = dm.group(1)
        if tag != "path":
            d = convert(tag, d, attrs)
        bad = set(re.findall(r"[A-Za-z]", d)) - set("MLHVCZmlhvcz")
        if bad:
            raise SystemExit("%s: 不支持的指令 %s" % (path, sorted(bad)))
        out.append((d, bool(EVENODD_RE.search(attrs)), bool(STROKE_RE.search(attrs))))
    return out or None


def convert(tag, data, attrs):
    """把 polyline / line 之类的元素改写成等效的 path 数据。"""
    nums = [float(x) for x in re.findall(r"-?[0-9]*\.?[0-9]+", data)]
    if tag == "polyline":
        pts = list(zip(nums[0::2], nums[1::2]))
        return "M" + " L".join("%g %g" % p for p in pts)
    if tag == "line":
        x1, y1, x2, y2 = nums[:4]
        return "M%g %g L%g %g" % (x1, y1, x2, y2)
    raise SystemExit("未处理的元素 <%s>" % tag)


def main():
    src_dir, dst = sys.argv[1], sys.argv[2]
    lines = [
        "package com.watchdl.ui.theme",
        "",
        "/**",
        " * 图标几何数据。**生成文件，别手改** —— 改 `tools/gen_reicon.py` 重新生成。",
        " *",
        " * 来源：reicon v1.2.5（MIT），24×24 网格、Outline 权重。它的 Outline 不是描边，",
        " * 而是把描边展开成闭合填充轮廓，所以按 FILL 画就得到 1.5px 线宽的效果，",
        " * 不必让 Paint 走 STROKE（小尺寸下 STROKE 的抗锯齿更毛）。",
        " *",
        " * 一条图标的多个 `<path>` 各自可能带 fill-rule=\"evenodd\"（外轮廓挖空内轮廓），",
        " * 所以存成列表，画的时候按 evenOdd 分别设 Path.FillType。",
        " */",
        "internal class ReiconPath(val d: String, val evenOdd: Boolean, val stroke: Boolean)",
        "",
        "internal val REICON: Map<String, List<ReiconPath>> = mapOf(",
    ]
    total = 0
    for name, res in MAP.items():
        subs = outline_body("%s/%s.js" % (src_dir, res))
        if not subs:
            raise SystemExit("取不到 %s（%s）" % (name, res))
        lines.append('    "%s" to listOf(' % name)
        for d, eo, st in subs:
            total += len(d)
            lines.append('        ReiconPath("%s", %s, %s),' % (d, "true" if eo else "false", "true" if st else "false"))
        lines.append("    ),")
    lines.append(")")
    open(dst, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("写好 %s：%d 个图标，路径数据 %d 字节" % (dst, len(MAP), total))


main()

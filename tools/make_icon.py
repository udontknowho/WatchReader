#!/usr/bin/env python3
"""生成应用图标。MD3 配色，书本 + 下载箭头。

生成的 png 直接给 aapt2 打包用（项目不用 XML 资源，所以用 mipmap-* 位图）。
"""
from PIL import Image, ImageDraw
import os, math

# MD3 深色主题配色（与 ui/Md3.kt 一致）
PRIMARY      = (208, 188, 255)   # #D0BCFF
ON_PRIMARY   = (56, 30, 114)     # #381E72
SURFACE      = (20, 18, 24)      # #141218
SURFACE_4    = (54, 52, 59)      # #36343B

# 手表图标尺寸。Android 会按 density 取；watch 常用 mdpi~xxhdpi
SIZES = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi":  144,
    "mipmap-xxxhdpi": 192,
}


def draw_icon(size, round_icon=False):
    """画一个图标：圆角方底 + 书本轮廓 + 向下箭头。"""
    SS = 4                      # 超采样倍数，边缘更干净
    S = size * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # ── 底色：MD3 primary container 深色底 ──
    pad = S * 0.02
    r = S * 0.5 if round_icon else S * 0.22
    d.rounded_rectangle([pad, pad, S - pad, S - pad], radius=r, fill=SURFACE)

    # ── 书本：两页，中间书脊 ──
    cx = S / 2
    book_top = S * 0.24
    book_bot = S * 0.56
    spine = S * 0.035
    half = S * 0.25

    line_w = max(SS * 2, int(S * 0.035))
    col = PRIMARY

    # 左页（略微倾斜，像翻开的书）
    d.line([(cx - spine, book_top + S * 0.02), (cx - half, book_top)],
           fill=col, width=line_w, joint="curve")
    d.line([(cx - half, book_top), (cx - half, book_bot - S * 0.02)],
           fill=col, width=line_w, joint="curve")
    d.line([(cx - half, book_bot - S * 0.02), (cx - spine, book_bot)],
           fill=col, width=line_w, joint="curve")

    # 右页
    d.line([(cx + spine, book_top + S * 0.02), (cx + half, book_top)],
           fill=col, width=line_w, joint="curve")
    d.line([(cx + half, book_top), (cx + half, book_bot - S * 0.02)],
           fill=col, width=line_w, joint="curve")
    d.line([(cx + half, book_bot - S * 0.02), (cx + spine, book_bot)],
           fill=col, width=line_w, joint="curve")

    # 书脊
    d.line([(cx - spine, book_top + S * 0.02), (cx - spine, book_bot)],
           fill=col, width=line_w)
    d.line([(cx + spine, book_top + S * 0.02), (cx + spine, book_bot)],
           fill=col, width=line_w)

    # ── 下载箭头：从书中间向下 ──
    aw = S * 0.095                      # 箭杆宽
    a_top = S * 0.50
    a_bot = S * 0.88
    d.rounded_rectangle([cx - aw / 2, a_top, cx + aw / 2, a_bot - S * 0.06],
                        radius=aw / 2, fill=PRIMARY)

    # 箭头三角
    head_w = S * 0.185
    d.polygon([
        (cx - head_w, a_bot - S * 0.16),
        (cx + head_w, a_bot - S * 0.16),
        (cx, a_bot),
    ], fill=PRIMARY)

    return img.resize((size, size), Image.LANCZOS)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    base = os.path.join(root, "app", "res")
    for folder, size in SIZES.items():
        outdir = os.path.join(base, folder)
        os.makedirs(outdir, exist_ok=True)
        # 方形图标
        draw_icon(size).save(os.path.join(outdir, "ic_launcher.png"))
        # 圆形图标（部分启动器会用）
        draw_icon(size, round_icon=True).save(os.path.join(outdir, "ic_launcher_round.png"))
        print(f"  {folder}/ic_launcher.png  {size}x{size}")

    # 预览图（放大，给用户看）
    prev = draw_icon(384)
    canvas = Image.new("RGB", (520, 460), (10, 10, 12))
    canvas.paste(prev, (68, 30), prev)
    # 小尺寸预览：真实观感
    small = draw_icon(48)
    canvas.paste(small, (70, 430 - 70), small)
    os.makedirs(os.path.join(root, "ui"), exist_ok=True)
    canvas.save(os.path.join(root, "ui", "icon-preview.png"))
    print("\n预览 → ui/icon-preview.png")


if __name__ == "__main__":
    main()

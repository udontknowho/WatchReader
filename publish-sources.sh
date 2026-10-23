#!/data/data/com.termux/files/usr/bin/bash
# 把 app/assets/sources/legado.json 发布到云端书源仓库。
#
# 用法：
#   ./publish-sources.sh /path/to/你的仓库克隆目录
#
# 做的事：复制 legado.json → 版本号 +1 → 写 manifest.json → 提示提交。
# 不自动 git push —— 推送前你可能想先看一眼 diff。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/assets/sources/legado.json"
DEST="${1:-}"
[ -n "$DEST" ] || { echo "用法：$0 <云端仓库目录>"; exit 2; }
[ -f "$SRC" ] || { echo "找不到 $SRC"; exit 2; }
[ -d "$DEST" ] || { echo "目录不存在：$DEST"; exit 2; }

mkdir -p "$DEST"
cp "$SRC" "$DEST/legado.json"

# 版本号自增。没有旧清单就从 1 开始。
VER=1
if [ -f "$DEST/manifest.json" ]; then
  OLD=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*\([0-9]\+\).*/\1/p' "$DEST/manifest.json" | head -1)
  [ -n "$OLD" ] && VER=$((OLD + 1))
fi

COUNT=$(python3 -c "
import json,sys
try:
    print(len(json.load(open('$DEST/legado.json', encoding='utf-8'))))
except Exception:
    print(0)
")
printf '{\n  "version": %s,\n  "file": "legado.json",\n  "count": %s\n}\n' "$VER" "$COUNT" > "$DEST/manifest.json"

SIZE=$(stat -c %s "$DEST/legado.json")
echo "已发布：$COUNT 条，$((SIZE / 1024)) KB，版本 $VER"
echo "目录：$DEST"
echo
echo "接下来手动提交："
echo "  cd $DEST && git add -A && git commit -m '书源库 v$VER：$COUNT 条' && git push"

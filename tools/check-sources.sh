#!/usr/bin/env bash
# 批量验证书源可用性。
#
# 用法:
#   tools/check-sources.sh [源文件] [关键词] [总分钟] [并发]
#
# 例子:
#   tools/check-sources.sh                              # 查内置书源库
#   tools/check-sources.sh ~/tmp/src2/raw.json          # 查那 641 条
#   tools/check-sources.sh x.json "斗破苍穹,三体" 30 10 # 指定关键词/时长/并发
#
# 产物（都在 build/ 下）:
#   filtered-sources.json  全量明细，分 ok / nohit / dead 三档
#   sources-usable.json    只含能用的源，格式同 legado.json，可直接导入
#   filt-cache.tsv         断点续跑缓存；想全部重测就删掉它
#
# 中断了直接重跑同一条命令，已测过的会跳过。
set -u
cd "$(dirname "$0")/.." || exit 1

SRC="${1:-}"
KEYWORDS="${2:-斗破苍穹,三体,全职高手,诡秘之主}"
MINUTES="${3:-30}"
CONC="${4:-8}"

if [ -n "$SRC" ] && [ ! -f "$SRC" ]; then
  echo "找不到源文件：$SRC" >&2
  exit 1
fi

echo "源文件：${SRC:-app/assets/sources/legado.json（默认）}"
echo "关键词：$KEYWORDS"
echo "时长上限：${MINUTES} 分钟，并发：$CONC"
echo

bash build.sh filt "$KEYWORDS" "$MINUTES" "$SRC" "$CONC"

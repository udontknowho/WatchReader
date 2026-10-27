#!/bin/bash
# 手工构建流水线：kotlinc → d8 → aapt2 → zipalign → apksigner
#
# 不用 Gradle/AGP 的原因：本机是 Termux，SDK 布局与 AGP 预期不符，
# 而 aapt2 / d8 / apksigner 都是独立可用的命令。整条链路无 Maven 下载。
#
# 用法：
#   ./build.sh test             只跑纯 JVM 自检，不打包
#   ./build.sh rank             榜单接口活体自测（真联网）
#   ./build.sh e2e [关键词] [章数]  全链路活体自测（真联网：搜索→目录→下载→存盘）
#   ./build.sh                  打出 build/WatchDownloader.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-$HOME/android-sdk}"
PLATFORM="${PLATFORM:-android-34}"
BT="${BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
JAR="$SDK/platforms/$PLATFORM/android.jar"
KOTLIN_LIB="${KOTLIN_LIB:-$PREFIX/opt/kotlin/lib}"
OUT="$ROOT/build"
MIN_SDK=30
TARGET_SDK=34
APP_VERSION="${APP_VERSION:-1.3.8}"
APP_VERSION_CODE="${APP_VERSION_CODE:-21}"

# kotlinc 读 JAVA_OPTS（$PREFIX/bin/kotlinc 只在未设置时用默认 -Xmx512M）。
# 本机内存被系统压到只剩几百兆，编译峰值必须自己封顶：串行 GC 不占并发 GC 线程池，
# 堆 / 元空间 / 代码缓存都显式给上限，避免一次编译把 Termux 自己拖进被杀名单。
export JAVA_OPTS="${JAVA_OPTS:--Xmx384m -Xms64m -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m -XX:ReservedCodeCacheSize=48m}"

STDLIB="$KOTLIN_LIB/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || STDLIB="$(ls "$KOTLIN_LIB"/kotlin-stdlib-*.jar 2>/dev/null | grep -v sources | head -1)"

# 第三方库。Rhino 用来跑书源里的 @js: 脚本（纯 Java 解释器，手表上必须 -1 关 JIT）。
# libs/ 下放的是官方 jar 剔掉 tools/debugger、optimizer、commonjs 后的瘦身版。
DEPS="$(ls "$ROOT"/libs/*.jar 2>/dev/null | tr '\n' ':' | sed 's/:$//')"
CLASSPATH_ALL="$JAR${DEPS:+:$DEPS}"

# build-tools 里只有 aapt2/apksigner/d8；zipalign 在 Termux 的 PATH 里，要单独找
ZIPALIGN="$BT/zipalign"; [ -x "$ZIPALIGN" ] || ZIPALIGN="$(command -v zipalign || true)"

die() { echo "错误：$*" >&2; exit 1; }

[ -f "$JAR" ] || die "找不到 android.jar：$JAR"
[ -f "$STDLIB" ] || die "找不到 kotlin-stdlib.jar（在 $KOTLIN_LIB）"

# ---- 闸一：同一时间只跑一个构建 ----
# 两个 kotlinc 并发就是 700MB×2、4 核×2，本机直接越线。
exec 9>"${TMPDIR:-/tmp}/watchreader-build.lock"
if ! flock -w "${LOCK_WAIT:-300}" 9; then
  die "另一个构建还在跑，本次放弃（等满 ${LOCK_WAIT:-300} 秒）"
fi

# ---- 闸二：可用内存不够就不开编 ----
# 编译峰值约 700MB。越线会触发系统杀进程，连 Termux 一起死。
# 看 MemAvailable（含可回收缓存），不看 MemFree。
AVAIL_MB=$(( $(awk '/^MemAvailable:/{print $2}' /proc/meminfo) / 1024 ))
MIN_AVAIL_MB="${MIN_AVAIL_MB:-1536}"
if [ "$AVAIL_MB" -lt "$MIN_AVAIL_MB" ] && [ "${WATCH_FORCE:-0}" != "1" ]; then
  die "可用内存只剩 ${AVAIL_MB}MB（要求 ≥${MIN_AVAIL_MB}MB）。先清后台大应用；确实要编就 WATCH_FORCE=1 bash build.sh …"
fi

run_kotlinc() {
  local nl="$1"; shift
  local out="$nl"
  local log; log="$(mktemp)"
  # -nowarn 压掉无害噪音；目标 11 便于 d8 处理。
  # 不能吞掉编译器输出：之前用 `|| true` 把报错也消了，导致后续步骤盲跑。
  if ! kotlinc -jvm-target 11 -nowarn -classpath "$CLASSPATH_ALL" -d "$out" "$@" >"$log" 2>&1; then
    grep -viE 'jansi|UnsatisfiedLinkError|Failed to load native library|osinfo:' "$log" >&2
    rm -f "$log"
    die "kotlinc 编译失败"
  fi
  grep -viE 'jansi|UnsatisfiedLinkError|Failed to load native library|osinfo:' "$log" >&2 || true
  rm -f "$log"
  [ -f "$out" ] || die "kotlinc 未产出 $out"
}

# ---------------- e2e ----------------
# 全链路活体自测。会真联网，所以要显式调用，不进 test 目标。
if [ "${1:-}" = "e2e" ]; then
  mkdir -p "$OUT"
  echo "== 编译 e2e =="
  run_kotlinc "$OUT/e2e.jar" \
    $(find "$ROOT/src/com/watchdl/core" -name '*.kt') \
    "$ROOT/test/E2e.kt"
  echo "== 运行 =="
  java -Xmx512m -cp "$OUT/e2e.jar:$STDLIB${DEPS:+:$DEPS}" E2eKt "${2:-斗破苍穹}" "${3:-3}"
  exit 0
fi

# ---------------- filt ----------------
# 离线筛源。真联网，跑得久（默认 20 分钟总上限），所以不进 test 目标。
if [ "${1:-}" = "filt" ]; then
  mkdir -p "$OUT"
  echo "== 编译 filt =="
  run_kotlinc "$OUT/filt.jar" \
    $(find "$ROOT/src/com/watchdl/core" -name '*.kt') \
    "$ROOT/test/FiltSources.kt"
  echo "== 运行 =="
  java -Xmx512m -cp "$OUT/filt.jar:$STDLIB${DEPS:+:$DEPS}" FiltSourcesKt "${2:-}" "${3:-20}" "${4:-}" "${5:-8}"
  exit 0
fi

# ---------------- toc ----------------
# 目录抓取实测：真联网，每条源搜书再取目录。不进 test 目标。
if [ "${1:-}" = "toc" ]; then
  mkdir -p "$OUT"
  echo "== 编译 toc =="
  run_kotlinc "$OUT/toc.jar" \
    $(find "$ROOT/src/com/watchdl/core" -name '*.kt') \
    "$ROOT/test/TocProbe.kt"
  java -Xmx512m -cp "$OUT/toc.jar:$STDLIB${DEPS:+:$DEPS}" TocProbeKt "${2:-}" "${3:-}" "${4:-}" "${5:-}" || exit 1
  exit 0
fi

# ---------------- live ----------------
# 合格书源统计：搜索 → 目录 → 取正文。真联网，不进 test 目标。
if [ "${1:-}" = "live" ]; then
  mkdir -p "$OUT"
  echo "== 编译 live =="
  run_kotlinc "$OUT/live.jar" \
    $(find "$ROOT/src/com/watchdl/core" -name '*.kt') \
    "$ROOT/test/LiveProbe.kt"
  java -Xmx512m -cp "$OUT/live.jar:$STDLIB${DEPS:+:$DEPS}" LiveProbeKt "${2:-}" "${3:-}" "${4:-}" "${5:-}" || exit 1
  exit 0
fi

# ---------------- rank ----------------
# 榜单接口活体自测。会真联网（番茄 + 起点），不进 test 目标。
if [ "${1:-}" = "rank" ]; then
  mkdir -p "$OUT"
  echo "== 编译 rank =="
  run_kotlinc "$OUT/rank.jar" \
    $(find "$ROOT/src/com/watchdl/core" -name '*.kt') \
    "$ROOT/test/RankProbe.kt" \
    "$ROOT/test/RankConfigTest.kt" \
    "$ROOT/test/StreamProbe.kt" \
    "$ROOT/test/FanqieProbe.kt"
  echo "== 运行 =="
  for t in RankProbe StreamProbe FanqieProbe; do
    java -Xmx512m -cp "$OUT/rank.jar:$STDLIB${DEPS:+:$DEPS}" "${t}Kt" || exit 1
  done
  exit 0
fi

# ---------------- fanqie ----------------
if [ "${1:-}" = "fanqie" ]; then
  mkdir -p "$OUT"
  echo "== 编译 fanqie =="
  run_kotlinc "$OUT/fq.jar" \
    $(find "$ROOT/src/com/watchdl/core" -name '*.kt') \
    "$ROOT/test/FanqieProbe.kt"
  CHARSET_JSON="${CHARSET_JSON:-$HOME/novel/sources/fanqie-booksource/charset.json}" \
    java -Xmx512m -cp "$OUT/fq.jar:$STDLIB${DEPS:+:$DEPS}" FanqieProbeKt "${2:-}" || exit 1
  exit 0
fi

# ---------------- test ----------------
if [ "${1:-}" = "test" ]; then
  mkdir -p "$OUT"
  echo "== 编译核心层 + 自检 =="
  run_kotlinc "$OUT/test.jar" \
    $(find "$ROOT/src/com/watchdl/core" -name '*.kt') \
    $(find "$ROOT/test" -name "*.kt")

  # UI 层（MainActivity 等）不进 JVM 自检 —— 它们依赖 Android 框架，跑不起来。
  # 但**必须至少编译一遍**：否则 src/com/watchdl/*.kt 里的类型错误
  # （改了函数签名却漏改调用点之类）测试全绿也发现不了，要到打包才爆。
  echo "== 编译 UI 层（只校验，不运行） =="
  run_kotlinc "$OUT/uicheck.jar" $(find "$ROOT/src" -name '*.kt')
  rm -f "$OUT/uicheck.jar"
  echo "== 运行 =="
  for t in CoreTest ChapterOrderTest CloudSourceTest DownloadTest EngineTest HttpTest P0RegressionTest FinishTest SignTest RankConfigTest RotationTest AsyncGuardTest NavProgressTest JobLockTest com.watchdl.core.RuleBoundaryTest TocBudgetTest TextSelectorTest LegadoDefaultTest DevLogTest; do
    echo "--- $t ---"
    CHARSET_JSON="${CHARSET_JSON:-$HOME/novel/sources/fanqie-booksource/charset.json}" \
      SONOVEL_RULES="${SONOVEL_RULES:-$HOME/novel/sonovel/rules/main.json}" \
      java -cp "$OUT/test.jar:$STDLIB${DEPS:+:$DEPS}" "${t}Kt" || exit 1
  done
  exit 0
fi

# ---------------- build ----------------
echo "== 1/6 编译 =="
rm -rf "$OUT"; mkdir -p "$OUT/dex" "$OUT/gen"
run_kotlinc "$OUT/app.jar" $(find "$ROOT/src" -name '*.kt')

echo "== 2/6 d8 转 dex =="
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$JAR" \
  --output "$OUT/dex" "$OUT/app.jar" "$STDLIB" "$ROOT"/libs/*.jar >/dev/null
[ -f "$OUT/dex/classes.dex" ] || die "d8 没有产出 classes.dex"

echo "== 3/6 准备资源 =="
mkdir -p "$ROOT/app/assets/sources"
[ -f "$ROOT/app/assets/charset.json" ] || cp "$HOME/novel/sources/fanqie-booksource/charset.json" "$ROOT/app/assets/charset.json"
# 书源：只有 assets 里还没有源库时才从根目录补进去。
# 以前无条件复制 —— 根目录那份一变，打包出来的就不是你以为的版本，
# 而且悄无声息。现在以 app/assets/sources/legado.json 为准（也是云端发布的源）。
if [ ! -f "$ROOT/app/assets/sources/legado.json" ]; then
  if [ -f "$ROOT/shareBookSource.json" ]; then
    cp "$ROOT/shareBookSource.json" "$ROOT/app/assets/sources/legado.json"
    echo "   源库：从 shareBookSource.json 初始化（首包）"
  else
    echo "   源库：无。App 首次启动会从云端拉取。"
  fi
else
  echo "   源库：$(stat -c %s "$ROOT/app/assets/sources/legado.json") 字节（app/assets/sources/legado.json）"
fi

echo "== 4/6 aapt2 编译 + 链接 =="
# 资源必须先 compile 成 zip：link 的 -R 只收编译单元，不收目录
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$OUT/res.zip"
# aapt2 的 --version-code/--version-name 只在 manifest 没声明版本时生效；
# 本项目的 manifest 写了显式值，参数会被忽略，所以这里盖一份临时 manifest 再 sed 注入。
MANIFEST="$OUT/AndroidManifest.xml"
sed -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$APP_VERSION_CODE\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$APP_VERSION\"/" \
  "$ROOT/app/AndroidManifest.xml" > "$MANIFEST"
grep -q "versionCode=\"$APP_VERSION_CODE\"" "$MANIFEST" || die "版本号注入失败：$MANIFEST"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$JAR" \
  --manifest "$MANIFEST" \
  -A "$ROOT/app/assets" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$APP_VERSION_CODE" --version-name "$APP_VERSION" \
  --auto-add-overlay >/dev/null

echo "== 5/6 塞 dex 里和其他资源 =="
(cd "$OUT/dex" && zip -q -X "$OUT/base.apk" classes.dex)

# Rhino 报错文案在 org/mozilla/javascript/resources/Messages*.properties。
# d8 只搬 dex，`.properties` 一个都进不了 APK，于是任何 JS 书源抛错时
# Rhino 取不到文案 bundle，抛 MissingResourceException 把真实错误盖掉，
# 日志里只剩「Can't find bundle for base name ... Messages」。
# 只塞默认的 Messages.properties（无后缀）与 _en：zh_CN 找不到会退化到默认，够用。
RHINO="$ROOT/libs/rhino-slim.jar"
if [ -f "$RHINO" ]; then
  for f in org/mozilla/javascript/resources/Messages.properties \
           org/mozilla/javascript/resources/Messages_en.properties; do
    unzip -p "$RHINO" "$f" > "$OUT/_rhino_msg.properties" || die "从 rhino-slim.jar 读不出 $f"
    (cd "$OUT" && mkdir -p "$(dirname "$f")" && mv _rhino_msg.properties "$f" && zip -q -X "$OUT/base.apk" "$f")
  done
  echo "   Rhino 文案资源已塞入：$(unzip -l "$OUT/base.apk" | grep -c 'javascript/resources/Messages') 个"
fi

echo "== 6/6 对齐 + 签名 =="
[ -x "$ZIPALIGN" ] || die "找不到 zipalign（build-tools 和 PATH 都没有）"
"$ZIPALIGN" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

KS="$ROOT/release.keystore"
INFO="$ROOT/keystore-info.txt"
if [ -f "$KS" ] && [ -f "$INFO" ]; then
  ALIAS="$(sed -n 's/^别名：[[:space:]]*//p' "$INFO" | head -1)"
  PW="$(sed -n 's/^口令：[[:space:]]*//p' "$INFO" | head -1)"
  [ -n "$ALIAS" ] && [ -n "$PW" ] || die "keystore-info.txt 里读不到别名或口令"
  APK="$OUT/WatchDownloader-$APP_VERSION-release.apk"
  KEYINFO="正式签名（$ALIAS）"
else
  # 没准备正式签名时退回 debug，保证流水线仍可用
  KS="$ROOT/debug.keystore"; ALIAS="androiddebugkey"; PW="android"
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias "$ALIAS" \
      -storepass "$PW" -keypass "$PW" -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=WatchDownloader Debug,O=WatchDownloader,C=CN" >/dev/null 2>&1
  fi
  APK="$OUT/WatchDownloader-$APP_VERSION-debug.apk"
  KEYINFO="★ debug 签名（不是正式包）"
fi

# min-sdk 传 24：apksigner 会据此决定签哪些方案，传 30 会跳过 v1/v2。
# 三种方案都签上，装机兼容面最大。
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PW" --key-pass "pass:$PW" \
  --ks-key-alias "$ALIAS" --min-sdk-version 24 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify --min-sdk-version "$MIN_SDK" --print-certs "$APK" >/dev/null \
  || die "签名校验失败"

rm -f "$OUT/aligned.apk" "$OUT/base.apk"
ls -lh "$APK"
echo "版本：$APP_VERSION (code $APP_VERSION_CODE)"
echo "签名：$KEYINFO"
echo "产出：$APK"

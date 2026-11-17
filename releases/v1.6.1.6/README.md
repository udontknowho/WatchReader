# 1.6.1.6 跨章游标与书源修复（code 47，已发布）

2026-11-17。本版收拢连续滚动跨章的三处收尾，修 yiove 书源市场导入，并补上书海阁书源。
已打正式包、已核验版本与签名。

## 改了什么

改动 7 个文件，+247 / −28 行：

| 文件 | 位置 | 问题 |
|---|---|---|
| `src/com/watchdl/ui/reader/ReaderScreen.kt` | 滚动落盘 | 落盘偏移存的是整屏 `scrollY`，恢复端却按**章内偏移**限幅用（`placeBeforeDraw(coerceIn(0, mainLimit))`）。在拼接区读第 N 章时整屏值远超首章高度，被限成「本章读完」，下次打开落到那一章末尾甚至后一章，看着像乱跳。改为 `chapOffset(cursor)` / `chapSpan(cursor)` 按章内偏移与块高存 |
| 同上 | 换章动画 | 旧屏向上、新屏向下各一套方向，实际阅读里只该向下推进。统一为旧屏一律向上滑出 |
| 同上 | 游标 | 补 `chapBounds` 边界表与 `cursor`：滚过追加区后按视口顶反查章号，标题 / 进度 / 章钮跟着走，不再按建屏时的 `index + 1` 重建刚读完的章 |
| `src/com/watchdl/core/ReadingHistory.kt` | `list` | 旧档每换一章一条。读取时按书折叠，每本只留最新一条；不靠写入去重，升级前堆下的行也一起收 |
| `src/com/watchdl/core/YioveSource.kt` | `fetchRule` | 端点 `/shuyuan/book-source/{id}` 已被 Cloudflare 拦成 520。改指 `/import/book-source/{id}`，返回是数组、字段平铺，解析改为按括号配对切原文（不反序列化再序列化，保住 JS 里的转义与换行） |
| `app/assets/sources/legado.json` | 新增 | 书海阁换站重写：旧 `m.shuhaige.net` 搜索被 WAF 全拒，不可救；改 `book.washai.net`，去重型 JS，纯选择器。33 → 34 条 |
| `test/SecondaryFeaturesTest.kt` | 用例 | 跟进按书聚合与裁剪语义 |

## 怎么验证

```bash
cd /data/data/com.termux/files/home/WatchDownloader
bash build.sh test        # 41 组离线回归 + UI 层只编译不运行
```

本轮实测：核心层与 UI 层均编译通过，41 组全部跑完，最后一组 `AppUpdateTest: 45 passed`，全程无 `错误：` 中断。

书海阁单源用真实引擎验收通过：

```bash
OUT=$(mktemp -d $TMPDIR/out.XXXX)
python -B test/SourceAcceptance.py --candidates <候选.json> --out "$OUT" \
  --classpath "build/test.jar:build/app.jar:libs/rhino-slim.jar:$PREFIX/opt/kotlin/lib/kotlin-stdlib.jar" \
  --workers 1 --first-timeout 60 --total-timeout 170 --no-retry
# 结果：{"total": 1, "passed": 1, "elapsedSeconds": 11.3}
```

APK 核验：

| 项 | 值 |
|---|---|
| 版本 | 1.6.1.6（code 47） |
| 包名 | com.watchdl |
| 字节 | 1861337 |
| MD5 | 6d720044114de5ea2521552185128dd8 |
| 签名 | CN=WatchDownloader，SHA-256 `0635de25…76d0` |

## 还没做

- 阅读位置跳章、换章动画方向**未上机实测**，只过了离线回归与编译。
- 批量书源扩容未跑（本地已有 7600 条候选，待筛）。

## 怎么回滚

```bash
cd /data/data/com.termux/files/home/WatchDownloader
git revert <本版提交>
# 或只退阅读器：
git checkout v1.6.1.5 -- src/com/watchdl/ui/reader/ReaderScreen.kt
```

修改前源码归档：`releases/v1.6.1.6-rollback.tar.gz`。

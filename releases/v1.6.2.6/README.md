# 1.6.2.6 目录端点池与翻页错章修复（code 57，已发布）

2026-11-19。本版修三处直接影响可用性的缺陷：目录坏主机反复踩坑、风控错误页
被当正文写进书架、阅读器换章整整错一章。同时把手机档 UI 接上 MIUIX 几何，
手表档取值不变。

## 改了什么

改动 11 个文件，+438 / −59 行。

### 目录与正文

| 文件 | 问题 | 修法 |
|---|---|---|
| `core/FanqieApp.kt` | 主机按 `attempt % DIR_HOSTS.size` 顺序轮，坏掉那台每轮白等一次超时（最长 25 秒），下一轮照样轮到它 | 新增进程级 `EndpointPool`：成功 +1、失败 −1（范围 ±3），连败 2 次剔除 90 秒，成功即解冻 |
| `core/FanqieApp.kt` | 取用不看健康度，请求全压在列表头一台 | 在未剔除的主机里取最高分档，同分按 `cursor` 轮转；池是进程级的，一次踩坑下一次不必再踩 |
| `core/FanqieApp.kt` | 软限流返回 HTTP 200 + code 0 + 空 `item_data_list`，被当成功，主机不扣分 | 空目录也记 `bad(host)`，与 TLS 握手失败同权 |
| `core/FanqieApp.kt` | 全员冷却时若直接抛错，一次网络抖动就放大成整本书取不到目录 | `take()` 解冻最早到期那台再试；只有主机列表为空才报错 |
| `core/Downloader.kt` | 风控页只有几十到几百字，含“下载失败”等字样却当成功写进 jsonl：进度全绿，打开全是提示语 | 加长度与关键词双判：300 至 1200 字且命中 10 条标记之一判 FAILED，错误归“正文无效” |
| `core/Downloader.kt` | 只凭关键词会误杀，“他打开浏览器验证”“请求失败的消息”都是正常文风 | 长正文（≥1200 字）一律放行，门槛取 `MIN_BODY_CHARS × 4`；宁可漏杀，不冤枉一章 |

### 阅读器

| 文件 | 问题 | 修法 |
|---|---|---|
| `ui/reader/ReaderScreen.kt` | 摘块补偿那一帧块顶仍是旧坐标，就地滚一屏立刻重入滚动监听，`cursorAt` 少算一两块，游标往回退 | 摘块全程置 `pruneBusy`：补偿按量好的块高一次减掉，重排落地后再重算游标 |
| `ui/reader/ReaderScreen.kt` | `toNext` / `toPrevious` 取章用可能落后的 `cursor ± 1`，整整错一章（跳下章末或回上两章） | 新增 `vpChapter()`，一律以视口实际所在章为基准；`pruneBusy` 期间退回 `cursor` |
| `ui/reader/ReaderScreen.kt` | 摘块补偿未落地就前插，两次位移落进同一帧互相盖掉 | `prependPrev` 同时看 `pruneBusy`；收尾走 `pruneSettle` 的 `placeBeforeDraw`，另加 400 ms 兜底防锁死 |

### 界面（手机档接 MIUIX 几何）

| 文件 | 问题 | 修法 |
|---|---|---|
| `ui/theme/Md3.kt` | 几何只有一套，米族配色配的是 M3 形状 | 新增 `miuixGeo` 与 `phoneTier`：宽度 ≥320 dp 且族为 MIUIX 才用米族；`R_*` 由常量改 `var`，圆角阶随族切换 |
| `MainActivity.kt` | 切主题只灌颜色、不重算几何；族靠 `palette.id` 反查，顺序反了静默失效 | `applyAppTheme` 在 `applyPalette` 之后调 `Md3.load(this)`，各调用点随后重建当前页 |
| `ui/Tabs.kt` | 手机档底栏仍是 28 dp 文字条，不分档 | 手机档改 `Widgets.navBar`（图标 + 标签）；手表档保留原文字条，取值与行为不变 |
| `ui/theme/Widgets.kt` | 米族底栏与正文同底色，`GradientDrawable` 画不出单边描边 | 改 `LayerDrawable`，把 1 dp 发丝线贴在顶上 |
| `ui/theme/Widgets.kt` | 米族底栏仍走 M3 胶囊加原色文字，两套语汇混在一起 | 米族去胶囊：选中转 `primary`，未选中压到 40% 透明，选中加粗 |
| `ui/theme/Widgets.kt` | 分段控件缝 4 dp，手机上两个按钮看着贴在一起 | 缝改 8 dp，与全局间距刻度同档 |

### 自检

| 文件 | 问题 | 修法 |
|---|---|---|
| `test/DownloadTest.kt` | 端点池与错误页判定无回归 | 新增 16 条：同分轮转、连败剔除、冷却放回、全员冷却兜底、空列表报错；错误页正反用例与落盘检查 |
| `test/ReaderPagingTest.kt` | 摘块补偿与视口取章无回归 | 新增 13 条断言（含连滚 20 章循环内 2 条，实跑 51 次）：补偿前后章号一致、旧坐标反查退两章的对照、跨块顶才归下一章 |

## 怎么验证

```bash
cd /data/data/com.termux/files/home/WatchDownloader
bash build.sh test        # 离线回归全绿（exit=0），UI 层只编译不运行
```

装机与版本核对：

```bash
su -c 'pm install -r build/WatchDownloader-1.6.2.6-release.apk'   # → Success
su -c 'dumpsys package com.watchdl | grep -E "versionName|versionCode"'
# versionCode=57 minSdk=30 targetSdk=34
# versionName=1.6.2.6
```

界面回归要手眼核对：手机档切“米”族看底栏发丝线与无胶囊选中态，
切回默认族看胶囊是否回来；手表档（189×248）应与 1.6.2.5 完全一致。

## 包信息

- 产物：`WatchDownloader-1.6.2.6-release.apk`，1 885 816 字节
- versionCode **57** / versionName **1.6.2.6**，minSdk 30 / targetSdk 34
- 签名：正式签名（watchdl）
- MD5：`650bed082334a0de08adcca7fa86e62c`

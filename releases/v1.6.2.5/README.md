# 1.6.2.5 全码审查修复（code 56，已发布）

2026-11-19。本版落地全码审查的全部剩余修复：JS 引擎的失控正则与原型污染、
补章的错章风险、下载与存储的并发、切主题重复重画、镜像书源校验、书架存储收敛。
已打正式包、已核验版本与签名。

## 改了什么

改动 27 个文件，+1216 / −210 行。

### 下载与存储

| 文件 | 问题 | 修法 |
|---|---|---|
| `core/JobStore.kt`<br>`core/Downloader.kt` | `restoreLight` 每次全量解析 job.json（3000 章），书架、目录、下载页反复调 | 加写版本号 + 快照缓存（键 = job 文件绝对路径，上限 24）；写入自增版本号，缓存自动失效 |
| `core/Downloader.kt` | 预排用探针件验断行，探针件进了缓存；同一本书可能重复构建 | 探针件不再进缓存；`build` 拆出 `buildInner` 并去重 |
| `core/Downloader.kt` | 书源池重建无并发保护 | `rebuildSources` 加锁 |
| `DownloadService.kt` | 四处在主线程 `restore` 大 job.json，卡点击帧 | 全部挪后台；槽位判断与 `launch` 同锁串行；收尾先挂「准备下一本…」再后台挑队 |
| `core/ReadingHistory.kt` | 换一章写一条，档越堆越大；读取走全量 | 按书折叠只留最新一条；流式取尾 |
| `core/Http.kt` | 连接池无上限 | 加上限 |
| `ui/my/CacheScreen.kt` | 缓存统计在主线程算 | 挪后台 |

### JS 引擎

| 文件 | 问题 | 修法 |
|---|---|---|
| `core/JsEngine.kt` | 脚本里的正则字面量（`/^(a+)+$/`）走 `java.util.regex`，Rhino 的指令计数拦不住，回溯能把线程钉死 | 执行前静态扫描脚本源码里的正则字面量，可疑形状直接拒绝，报「脚本含高风险正则」 |
| `core/SafeRegex.kt` | 嵌套量词判据只挡 `)` 前紧邻 `+*`：`(\w+\s?)*`、`(a|a)+`、`(?:x+){2,}` 全放行；判严了又会废掉内置推荐规则 | 改为解析成正则 AST，只判**边界上**的无界量词；有界 `{0,64}`、`{1,30}` 不判 |
| `core/JsEngine.kt` | `initSafeStandardObjects(null, false)` 未封内置原型，脚本能改 `Object.prototype` | 改封（`true`），改原型不再生效 |
| `core/JsEngine.kt` | 超时文案写死「2 秒」，实际预算可能 6 秒 | 文案报本次实际预算；区间与注释统一 |
| `core/JsEngine.kt` | 失败请求 URL 带 token / sign 原样进 DevLog | 查询参数里的 token / sign / key / uid 等值替换成 `***` |
| `core/Es5Compat.kt` | 改写位置表对被跳过的字面量区间填 0，`out.setLength(head)` 可能截断输出 | 跳过区间补全位置，截断点夹紧到输出长度；补「前缀不丢」回归用例 |

### 补章与书源

| 文件 | 问题 | 修法 |
|---|---|---|
| `core/ChapterFallback.kt` | 补章用「双向包含」找同名章，会把「第3章 出发前」的正文当成「第3章 出发」写进本书并标 DONE | 改全等章名匹配：章号必须相等（`第十二章` = `第12章`），同号多义直接放弃，宁可补不到 |
| `core/CloudSource.kt` | 从镜像拉书源没有校验和，与主源口径不一致 | 清单加 `sha256`（兼容 `checksum`），不符即弃用该镜像；无校验和时明确记日志再接受 |

### 界面

| 文件 | 问题 | 修法 |
|---|---|---|
| `ui/my/SettingsScreen.kt` | 切主题分两步写状态，整屏重画两次 | 收敛成一次写入、一次重画 |
| `ui/shelf/ShelfScreen.kt` | 书架旧档（`reading-books.json`）回退散在三处，各自 new 一个 `ReadingBookStore` | 收敛到唯一入口 `legacyShelfRecord` / `shelfRecord` / `shelfRecordFor`；旧档降级为只读迁移 |
| `ui/shelf/ShelfScreen.kt` | 点书优先 job.json 章表，与「shelf.json 是书架真相」矛盾 | 改以 shelf.json 章表为准，为空才退回 job 章表 |
| `MainActivity.kt` | 两个死字段（`ReaderUi.fromShelf`、`tocSrc`） | 删 |

## 怎么验证

```bash
cd /data/data/com.termux/files/home/WatchDownloader
bash build.sh test        # 离线回归全绿（exit=0），UI 层只编译不运行
```

装机与版本核对：

```bash
su -c 'pm install -r build/WatchDownloader-1.6.2.5-release.apk'   # → Success
su -c 'dumpsys package com.watchdl | grep -E "versionName|versionCode"'
# versionCode=56 minSdk=30 targetSdk=34
# versionName=1.6.2.5
```

## 包信息

- 产物：`WatchDownloader-1.6.2.5-release.apk`，1 910 588 字节
- versionCode **56** / versionName **1.6.2.5**，minSdk 30 / targetSdk 34
- 签名：正式签名（watchdl）
- MD5：`16091f00c88a6c64854e91d56d1fc9e5`

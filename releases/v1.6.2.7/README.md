# 1.6.2.7 阅读位置改用字符口径（code 58，已发布）

2026-11-20。本版重做阅读位置模型：位置的真源从「像素」换成「章号 + 章内字符偏移」。
修的是两个反复出现的症状：章首点屏幕下三分之一后直接跳到本章末尾，以及底栏「下一章」
一次跨过四章。像素存档没有唯一真源，存档被限幅成「读完」是前者的成因；窗口预填让
`win.last.ch` 比读者实际所在章多出三章，是后者的成因。

## 改了什么

改动 7 个文件，+185 / −71 行。

### 阅读位置（核心）

| 文件 | 问题 | 修法 |
|---|---|---|
| `ui/reader/ReaderScreen.kt` | 恢复走 `restore.coerceIn(0, mainLimit())`：存档值一大就被夹到本章正文底，一进章就跳到底 | 改 `charToScroll()`，按字符偏移查行再取行顶，**只按 `scrollLimit` 夹取**，绝不用 `mainLimit` |
| `ui/reader/ReaderScreen.kt` | 存档存的是像素：拼接区里整屏 `scrollY` 远超本章可读高度，写盘时被限幅成「读完」 | 新增 `savedPos()`，存的是视口顶那一行的 `(章号, 字符偏移)` |
| `core/ReaderPaging.kt` | 字符换行号没有纯逻辑实现，无法脱离 Android 排版验证 | 新增 `lineOfOffset(starts, chars)`：二分查行，超长夹末行、负数落首行 |
| `MainActivity.kt` | 只有一个 `off_` 键，新旧口径混用无法区分 | 新增 `offc_<tag>`（字符），`oidx_` 章号守卫沿用；换章时 `off_` 与 `offc_` 一起清。缺 `offc_` 落章首，**不拿旧像素当字符解读** |

### 会话内位置

| 文件 | 问题 | 修法 |
|---|---|---|
| `ui/reader/ReaderScreen.kt` | 「返回刚才那章」胶囊、换源回跳、换章前保存都按像素钉位置，跨页后多着一整章正文 | 三处统一按字符解释；`restoreDelta` 字段删除（行内余量由行顶吸收） |
| `ui/reader/MarksScreen.kt` | 书签跳转借道 `off_` 存档，与「上次读到哪」混在一起 | 书签仍按像素（书签本就记的是那一屏），改走 `restoreAt` 钉住分支，不写存档键 |

### 底栏章钮

| 文件 | 问题 | 修法 |
|---|---|---|
| `ui/reader/ReaderScreen.kt` | 「下一章」用 `maxOf(cursor, win.lastOrNull()?.ch ?: cursor)`，窗口预填到 +3 章，一次跳过四章 | 改 `nextChapter(info, vpChapter() + 1)`；「上一章」同样改 `vpChapter()` |

### 可观测性

| 文件 | 问题 | 修法 |
|---|---|---|
| `ui/reader/ReaderScreen.kt` | 章内滚动一跳不留任何日志，多轮排查只能靠猜 | 四处 `DevLog` 埋点：`place`（哪个分支、三个上限）、`page`（翻屏前后与上限）、`cursor`（章号突跳）、`save`（写入的章号与字符数） |

## 怎么验证

纯逻辑：

```bash
bash ./build.sh test          # exit=0
# ReaderPagingTest: 230 checks passed（新增 8 项：字符→行号单调、超长夹末行、负数、空表）
```

装机：

```bash
su -c 'pm install -r build/WatchDownloader-1.6.2.7-release.apk'   # Success
su -c 'dumpsys package com.watchdl | grep -E "versionName|versionCode"'
# versionCode=58 / versionName=1.6.2.7
```

行为：章首点屏幕下三分之一后读 `dev.log`，`place` 行的 `y` 应远小于 `ml`（本章正文底），
`save` 行写入的是字符（几十到几千），不是像素（上万）。章内滚动一跳现在有 `page` 行可查。

## 包信息

- 产物：`WatchDownloader-1.6.2.7-release.apk`，1 885 816 字节
- versionCode **58** / versionName **1.6.2.7**，minSdk 30 / targetSdk 34
- 签名：正式签名（watchdl）
- MD5：`6539734e872a3b847efe79030fffa478`

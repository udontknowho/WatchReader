# 1.6.1.5 审查与修复（code 46，已发布）

2026-11-15。起点是 1.6.1.4 上发现的 6 个 bug，随后并入日夜主题真源收敛与 MD3 收尾。
本版已打正式包、已装机、已在虚拟屏实测。

## 改了什么

改动 3 个文件，+59 / −11 行：

| 文件 | 位置 | 问题 |
|---|---|---|
| `src/com/watchdl/MainActivity.kt` | `setScreen` | `shelfBookLoading` 清零过早：占位详情转正文走同身份早退，标志在早退前就被清，真正的 Reader 那一跳读到 false，多压一层 Detail 占位 → 从书架进正文后按返回落到空白页 |
| 同上 | `setScreen` | 离开进度屏不解绑 `progressNum/progressCard/progressSub/progressTotal`：广播仍刷已脱离窗口的 card，`getChildAt(2)` 索引漂移把 `progressSub` 换成非 TextView，进度尾巴停止更新 |
| 同上 | `openBook` | 取目录回调**没有屏守卫**（全项目 13 处 `bg` 里唯一一处）：晚到结果把人从书架硬拽回目录屏，失败还多弹一层返回栈 |
| `src/com/watchdl/ui/shelf/ShelfScreen.kt` | `readShelfBook` | 失败分支 `showShelf()` 不弹栈 → 返回栈残留一层，按返回「没反应」要连按两次；顺手堵掉空章表 `coerceIn(0, -1)` 抛 `IllegalArgumentException` |
| 同上 | `refreshShelfTocs` | 工作线程直接写 SharedPreferences、主线程重画，无 happens-before → 新章圆点显示中间态。改为收集 `RefreshTocResult` 回主线程落盘 |
| `src/com/watchdl/ui/search/SearchScreen.kt` | `showHits` | 跨轮去重用 `hitKey`（名字+作者）→ 第二次搜索同名不同书被上一轮记录过滤（「搜到了但列表里没有」）。新增 `identityOf`（来源+书号+名+作者）做窗口去重，显示层仍按名字合并 |

## 怎么验证

```bash
cd /data/data/com.termux/files/home/WatchDownloader
bash build.sh test        # 41 组离线回归 + UI 层只编译不运行
```

本轮实测：核心层编译通过 → UI 层编译通过 → 41 组全部跑完，最后一组 `AppUpdateTest: 45 passed`，全程无 `错误：` 中断。

`JsSyntaxProbeTest` 里的 6 处 `✗` 是**探测输出**（报告哪些 ES2015+ 语法被 Rhino 拒绝），不是失败。

**没有上机验证**：手边没有可安装的设备授权，触控、耗时、控件外观仍未实测。

## 怎么回滚

```bash
cd /data/data/com.termux/files/home/WatchDownloader
git checkout -- src/com/watchdl/MainActivity.kt src/com/watchdl/ui/shelf/ShelfScreen.kt src/com/watchdl/ui/search/SearchScreen.kt
# 或整体回到审查前：
tar xzf releases/v1.6.1.5-audit-before.tar.gz
```

## MD3 收尾（同轮追加）

形状与容器 token 落到调用点，不再在调用点写像素：

- `md3Dialog`：圆角 `R_XL`（28 dp）、底色 `surfaceContainerHigh`、`tonal(…, 0.03f)`。
- `menuDialog`：底色 `surfaceContainer`、`tonal(…, 0.04f)`。
- 新增 `Md3.tonal()`；`Md3.load` 记下 `densityScale`，`bg` / `ripple` 内部统一 dp→px，调用点一律写 dp。
- 角标 `4f` → `R_XS`（搜索来源数）；底栏选中背景 `6f` → `R_GRID`；阅读浮动胶囊 `20f` → `capHeight / 2`；发现页旧榜按钮 `32` / `10f` → `TAP` / `TAP / 2`。
- 新章圆点 `3f` 经核查本就是 dp（`Md3.bg` 内部换算），未改。
- Snackbar 与 `showDiscoverLegacy` 全仓无调用点，本版不动。

屏上实测（display 43，378×496，density 2.0）：搜索角标 27×26 px、圆角约 8 px；底栏选中背景高 28 dp、圆角 8 dp；阅读胶囊高 30 dp、圆角 15 dp。

## 验证与边界

- `bash build.sh test`：41 组离线回归全绿，`All checks passed`，无 `error:`。
  `JsSyntaxProbeTest` 的 6 个 `✗` 是探测输出（报告哪些 ES2015+ 语法被 Rhino 拒绝），不是失败。
- `bash build.sh`：正式签名包 1 840 760 字节，md5 `4bfa864a87aeb1650c6778df64ca5537`。
- `pm install -r`：`Success`，versionCode 46 / versionName 1.6.1.5。
- 虚拟屏实测：书架、发现、搜索、目录、阅读、阅读设置均可进入，无 ANR、无 FATAL。
- 未做在线书源全量抽检；书源可用性以实际访问为准。

## 已知未修（记录在案，未动）

- `ReturnPoint.remember` 用 `screenId` 去重，同一关键词二次搜索会丢掉结果屏那层返回。修它会改返回栈语义，需要上机验证，本轮不做。
- `ShelfStore.save` 的等价早退依赖 `length + mtime`：FUSE 存储上 mtime 秒级，同秒同长度的两次保存可能读到旧快照。触发窗口极窄。
- `ReaderUi.bright` 是死状态：底栏亮度滑条已移除，`applyBright()` 恒交还系统（`-1f`）。字段仍在读写，属历史残留。

## 附带产物

- `ui/astryx-watch-v1615.html`（2 152 601 字节）：Astryx 单文件设计稿，7 块 189×248 表盘，断网可看。
- `releases/v1.6.1.5/astryx-refactor-cost.md`：UI 重构工作量评估。

## 补充：日夜主题真源收敛（同轮追加）

用户要求「日夜主题也做一下」，不做跟随系统。查下来日夜主题已存在（`Palette.all`
两套 + 设置「外观」页 + 1.6.1.4 已覆盖 root/窗口/系统栏/对话框），但发现一个真 bug：

**`Md3.applyPalette()` 丢弃传入的 `Palette`，按硬编码常量重建日/夜两套。**
于是 `Palette.all` 与实际渲染已经漂移：

| | `Palette.all`（设置页色点、PaletteTest 验的对象） | 屏上实际渲染 |
|---|---|---|
| day primary | `0xFF586650` | `0xFF3D5942` |
| day surface | `0xFFF2EFE7` | `0xFFF3F0E8` |
| night primary | `0xFFFFFFFF` | `0xFFB6CFAD` |
| night surface | `0xFF0E0E0E` | `0xFF101113` |

设置页色点画的是表左，屏上却是表右；PaletteTest 验的对比度也不是真跑的那套。

修法（视觉不变，最小风险）：

- `src/com/watchdl/core/Palette.kt`：`all` 不再从 `legacy` 派生，显式写出与屏上一致的调校值，成为唯一真源；删掉无用的私有 `mode()`。
- `src/com/watchdl/ui/theme/Md3.kt`：`applyPalette` 改为 `val p = selected`，删掉硬编码重建。
- `test/PaletteTest.kt`：两条 `保留预览底色` 断言跟随真值（曾写死派生值，断言的是不存在的东西）。

验证：`bash build.sh test` 41 组全绿，`PaletteTest: 全部通过`，末尾 `AppUpdateTest: 45 passed`。

另：全项目扫描无写死颜色（`0x[0-9A-F]{8}` 命中的只有 SM3/加密常量与 `ReaderStyle.BG_FANQIE_*`），
阅读页的 `hairline` 按明暗自适应。**阅读页本来就是单栏**，不需改动；两栏只存在于已废弃的预览稿。

回滚：`git checkout -- src/com/watchdl/ui/theme/Md3.kt src/com/watchdl/core/Palette.kt test/PaletteTest.kt`。

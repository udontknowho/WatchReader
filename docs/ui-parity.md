# UI 对照表：legado → 手表版

参考方：`~/ref/legado-novel` @ `2ed09db`（3.26.21，全 Compose）。
目标方：`~/WatchDownloader`（`com.watchdl`，纯 Android View）。
屏级明细见 `legado-screens.md`。

**本文 2026-11-02 按代码逐行重写。** 上一版写的 `PAD=8`、`R_ITEM=10`、
字号阶 17/14/13/12/11、「顶栏图标常量未定」全部过期，照旧表施工会踩空。

## 一、能力边界：能对到什么程度

**结构可以，像素不行。** legado 是手机布局，逐屏假设 ≥360 dp 宽。

本文的「一比一」指三件事：信息层级、分组与标签原文、字号与节奏。
不追求：图标像素级还原、动画曲线、Glass 材质（依赖 Compose）。

裁剪判据只有三条，别拿「一屏显示不下」当理由 —— 纵向列表能滚，装得下任意多项。

1. 同一行摆 N 个元素：189 dp 放 6 个图标，每个不足 28 dp。
2. 需要横向滑动的分页：页签行、HorizontalPager。
3. 希望一眼看完的控件：颜色选择器、两级多选树。

## 二、设备基准

| 项 | 值 |
|---|---|
| 目标机 | OPPO Watch 3 Pro，1.91" LTPO AMOLED |
| 分辨率 | 378 × 496 px，326 ppi |
| dp | 378×496 @320dpi = **189 × 248 dp** |
| 虚拟屏 | `~/vd/vd.sh start 378 496 320` |

`Md3.load()` 按 `screenWidthDp` 分档，189 dp 走手表档。

## 三、现有屏总表（`core/Screen.kt`，12 个 + 3 个辅助）

| 屏 | 类型 | 实现位置 | legado 对应 |
|---|---|---|---|
| `Shelf` | 根屏 | `ui/shelf/ShelfScreen.kt` | 书架 |
| `Discover` | 根屏 | `ui/source/SourceScreen.kt:204` | 发现 |
| `My` | 根屏 | `ui/my/MyScreen.kt` | 我的 |
| `Detail` | 子屏 | `ui/book/DetailScreen.kt` | 书详情 |
| `Toc` | 子屏 | `MainActivity.kt:572` | 目录 |
| `Reader` | 子屏 | `ui/reader/ReaderScreen.kt` | 阅读页 |
| `Settings` | 子屏 | `MainActivity.kt:1070` | 设置总入口 + 阅读设置 |
| `Hits` | 子屏 | `ui/search/SearchScreen.kt` | 搜索结果 |
| `Marks` | 子屏 | `ui/reader/MarksScreen.kt` | 书签 |
| `Sources` | 子屏 | `ui/source/SourceScreen.kt:29` | 书源管理 |
| `Market` | 子屏 | `ui/source/SourceScreen.kt:395` | 无（自建） |
| `Progress` | 子屏 | `MainActivity.kt:760` | 无（自建） |
| `Searching` | 辅助 | `ui/search/SearchScreen.kt:240` | 无（自建） |
| `TocFailed` | 辅助 | `MainActivity.kt:985` | 无（自建） |
| `JobMenu` | 弹层 | `MainActivity.kt:1365` | 无（自建） |

`Market`、`Progress`、`Searching`、`TocFailed`、`JobMenu` 是自建屏，legado 无对应，不追平。

## 四、逐屏现状与差距

### 1. 书架 → `Shelf`

**现状**（`ShelfScreen.kt`）：
`headerRow("书架")` + 右端 trailing（搜索、视图切换）+ 封面墙／列表两档 + `attachTabs(0)`。
列表档副标题 = 「作者 · 进度」（`:93-95`）。⋮ 菜单 11 项（`:210-228`）。
封面 3 列、5:7、圆角 8（`:143`）。

| 差距 | legado 出处 | 现状 | 修法 |
|---|---|---|---|
| 「在读 · 章名」副标题 ✅ | `BookItem.kt:870` `desc = book.durChapterTitle` | 已改：有阅读记录时副标题 =「作者 · 在读 章名」 | 2026-11-02 落地。`restoreLight` 已带章名，不必另读目录 |
| 封面圆角 8，详情 12 | 同一套 5:7 封面 | `ShelfScreen.kt:143` vs `DetailScreen.kt:35` | 属圆角取舍，已判保持原值（§六） |

**不搬**：分组页签、HorizontalPager、快速滚动条、编辑模式工具条、下拉刷新。

### 2. 发现 → `Discover`

**现状**（`SourceScreen.kt:204`）：
`headerRow(源名或「发现」)` + 站点／性别芯片（`:217`）+ 榜单选择 → 条目列表。
条目 = `listItem(书名, 作者 · 源名)`（`:323`）。

| 差距 | legado 出处 | 现状 |
|---|---|---|
| 模型不同，不追平 | 书源卡 → 展开种类栅格 | 站点榜单驱动 |

只对齐视觉：书源卡圆角 12（legado `GlassCard` 12 dp，已一致）。

**不搬**：书源卡内种类栅格、浮动吸顶胶囊、长按六项菜单。

### 3. 我的 → `My`

**现状**（`MyScreen.kt`）：
`headerRow("我的")` + 两张 `groupCard`，分组标题原文照搬。
「规则」5 项、「其它」7 项，共 12 项，`attachTabs(2)`。

| 差距 | legado 出处 | 现状 |
|---|---|---|
| 「书源管理」副标题 ✅ | legado 该项带「新建 • 导入 • 编辑或管理书源」 | 已补同一句副标题（`MyScreen.kt` 的 `e()` 增 `sub` 参数） |
| 「书签」无入口 | `AllBookmarkScreen` 全书聚合 | `Marks` 绑死 `BookInfo`，无跨书屏 |
| 「阅读记录」无入口 | legado 阅读记录页 | `showProgress` 也绑死 `BookInfo` |
| 其余 7 项占位 | — | 点了弹 toast |

### 4. 书详情 → `Detail`

**现状**（`DetailScreen.kt`）：
`topBar(书名, 返回)` + 封面（`R_ITEM` 圆角）+ 书名／作者／书源 + 4 张操作卡 **2×2**（`:55-96`）
+ 在读／最新章名 + 章数统计（`:98-110`）+ 简介卡 + 底部 FAB「阅读」（`:136`）。

| 差距 | legado 出处 | 现状 | 修法 |
|---|---|---|---|
| 缺分类标签／分组名／备注 | `BookInfoScreen.kt` 头部 | `Model.kt:36-56` `BookInfo` 无这些字段 | 先补数据模型，非纯 UI |
| 顶栏只挂返回 | legado 有 编辑／分享／更多 | `:23` | 编辑与更多依赖分组、源变量，我们都没有 |
| 「换源」卡占位 | `ChangeSourceSheet` | `:77` 弹 toast | 见待拍板 |

**不搬**：半透明封面大背景、相关书籍横排、封面共享元素转场。

### 5. 目录 → `Toc`

**现状**（`MainActivity.kt:572`）：
`topBar(书名, 返回, trailing = 搜索 + 更多)` + 简介卡（可展开）+ 「章节」`sectionLabel`
+ 窗口化章条（`TOC_WINDOW` 一窗）+ 前后翻窗按钮 + 自动滚到上次读到的章。
章条 = `chapterItem(序号, 章名, 状态标记, highlight = i == lastRead)`（`:725`）。
⋮ 菜单 2 项：书签、导出 TXT（`:1053`）。

| 差距 | legado 出处 | 现状 | 修法 |
|---|---|---|---|
| 顶栏副标题「当前章 / 总章数」 ✅ | `TocScreen.kt:255-270` `"$durIndex / $totalNum"` | 已加：`topBar(..., subtitle = "n / 总数")` | 2026-11-02 落地，`Widgets.topBar` 新增可选 `subtitle` |
| 当前章 primary 文字 ✅ | 共性节「当前阅读章节文字用 primary」 | 已改：高亮时标题取 `primary`，底色仍留 surface3 | `listItem` 新增 `titleColor` 参数 |
| 缺「定位当前／置顶／置底」 | FAB 四项 | 只有自动滚动；窗口外时失效（`:743`） | 见待拍板 |

**不搬**：书签／笔记两页、粘顶卷标题、长按多选、章条右侧多图标行。
注：`legado-screens.md` 写「章条状态图标不搬」，代码实际搬了（锁／✓／✗）。
以代码为准，文档这句作废。

### 6. 阅读页 → `Reader`

**现状**（`ReaderScreen.kt`）：
`topBar(章节名, 返回, trailing = 设置)`（`:37`）+ 整屏滚动正文
+ 底栏「‹ 上一章 / N / 总数 / 下一章 ›」（`:134-160`）
+ **3×2 工具格**：目录／书签／换源／设置／缓存／更多（`:62-102`）。

| 差距 | legado 出处 | 现状 | 修法 |
|---|---|---|---|
| 顶栏书名与书源名 ✅ | `ReadBookScreen` 顶栏 = 书名 + 「章名 `·` 书源名」 | 已改：标题 = 书名，副行 = 「章名 · 源名」 | `BookSource` 接口不带名字，源名只从 `LegadoSource.displayName` 取，取不到就只显章名 |
| 「换源」格占位 | `ChangeSourceSheet` | `:81` 弹 toast | 见待拍板 |

**不搬**：6 个顶栏按钮、亮度条、朗读、音量键翻页、二级菜单全部项。

### 7. 设置 → `Settings`

**现状**（`MainActivity.kt:1070-1240`）：**单页 7 组**。
外观（`themeChips` 六套两行三列）／阅读显示（预览块 + 字号 + 行距）／表冠（每格位移 + 跟手时长）
／导出（文件名与编码说明）／书源（管理书源）／关于（版本）／开发者（开关 + 导出日志 + 清空日志）。

| 差距 | legado 出处 | 现状 |
|---|---|---|
| 未两页化 | `ConfigNavScreen.kt` 8 项 | 单页 7 组 |
| 缺 备份与恢复／隐私／实验室／封面设置／下载与缓存 | 同上 | 均无入口 |
| 「表冠」「导出」是自创组 | legado 无 | `:1173-1200`、`:1202` |
| 设置项缺描述行 | 条目 = 标题 + 描述 + 当前值 + 控件 | 多数只有标题与值 |
| 「当前值」primary ✅ | `ThemeConfigScreen.kt:1094` | 已改：`listItem` 的 trailing 默认色改 `primary`，⋮ 图标那支仍 `onSurfaceVariant` |

### 8. 搜索 → `Hits`

**现状**（`SearchScreen.kt`）：
`topBar("搜索结果", 返回)` + 窗口化结果列表 + 翻页条 + 「换一批」。
结果项 = `listItem(书名（N 源）, 作者 · 源名)`（`:118-126`）。

| 差距 | legado 出处 | 现状 | 修法 |
|---|---|---|---|
| 结果项封面 ✅ | legado 72 dp | 已补 40 × 56 dp 色块，色按书名哈希（`Md3.nameColor`） | 走 `listItem` 的 `leading`，没另写一行 |

**不搬**：筛选两级树、来源分组布局、质量检测。

### 9. 书签 → `Marks`

**现状**（`MarksScreen.kt`）：`topBar("书签 · 书名")` + `sectionLabel(共 N 条)` + 明细行 + 清空按钮。
与 legado 差异：legado 两级（按书聚合 → 明细），我们平铺，省一次点击。**判定：刻意简化，不算差距。**

### 10. 书源 → `Sources`

**现状**（`SourceScreen.kt:29`）：`topBar("书源")` + 3 条操作项 + 「本地书源」`sectionLabel`
+ 导入统计卡（可用 N / 共 M 条、错误原因、文件数）+ 「全部书源（N）」列表（上限 400 条）。
legado 对应 `book/source/manage`。**不追平**：编辑、调试、订阅。

### 11. 书源市场 → `Market`、12. 进度 → `Progress`、13–15 辅助屏

自建，legado 无对应。不追平。

## 五、组件清单（`ui/theme/Widgets.kt`，26 个公开函数）

| 类别 | 函数 |
|---|---|
| 顶栏 | `topBar` `headerRow` `tabRow` |
| 列表 | `listItem` `groupItem` `chapterItem` `emptyState` |
| 容器 | `groupCard` `card` `scrollBody` `contentColumn` `screenRoot` |
| 控件 | `button` `iconButton` `searchBar` `sliderRow`×2 `progressBar` `navBar` |
| 弹层 | `menuDialog` `longMenuDialog` `styleDialog` |
| 其它 | `sectionLabel` `tintSubtree` `snackbar` |

`groupItem` 是 `listItem` 的薄封装，只换背景。`chapterItem` 也是。

## 六、数值对照（代码实测，2026-11-02）

### 圆角：三处与 legado 不同

| 常量 | 现在 | legado | 影响面 |
|---|---|---|---|
| `R_CARD` | 12（M3 medium） | 16（`SplicedColumnGroup` 默认） | `groupCard`、`card`：详情简介卡、目录简介卡、进度卡 |
| `R_ITEM` | 12（medium） | 书架列表卡 8 | `groupItem`、`listItem`、书源卡、详情操作卡 |
| `R_GRID` | 8（small） | 网格项 4 | 书架封面占位、角标 |

`Md3.kt:124-126` 的注释自称按 M3 shape scale（4/8/12）取值。
legado 用 16 与 4，属于 Miuix 风格，不是 M3 原值。**这是一处取舍，不是纯 bug。**

**已判：保持 M3 原值 12 / 12 / 8。** 2026-11-02 看过 `docs/corner-compare.html`
（四宫格加四倍放大对照页）后定下的。上表只作记录，不要再改。

### 尺寸与字阶：已对齐，不用动

```
PAD 16  GAP 8   TOP_BAR 64  SEARCH 56  BTN 40  ITEM 56  ITEM_TALL 72
TAP 48  HIT 48  NAV 64      NAV_IND_W 48 / H 32      TAB_BAR 48  TAB_H 40
ICON_ACTION 24  ICON_LEAD 24
FS_XL 22 / FS_LG 22 / FS_BODY_LARGE 16 / FS_READ 16 / FS_MD 14 / FS_SM 12 / FS_LABEL 12
```

M3 基线型字阶原值为 22/16/14/12/11。代码只定义了其中 5 档，
`titleMedium 16`、`titleSmall 14`、`labelSmall 11` **没有命名常量**，调用点直接写数字。

### 其余不符

| 项 | 原值 | legado | 现状 |
|---|---|---|---|
| 分组标题 | 12 sp、缩进 2 dp | `labelLarge` 14 sp、缩进 16 dp | ✅ 改为 14 sp / `PAD` |
| 列表项描述透明度 | 不透明 | `bodySmall` + 30% 透明 | ✅ `alpha = 0.7f` |
| 列表项右侧值色 | `onSurfaceVariant` | `primary` | ✅ 默认改 `primary`，图标那支留 `onSurfaceVariant` |

2026-11-02 三项已落地，`bash build.sh test` 全绿（798 项，rc=0）。

## 七、占位清单（19 处，点了弹 toast）

| 屏 | 项 | 位置 |
|---|---|---|
| 书架 ⋮ | 远程书籍／添加本地／更新目录／分组管理／选择模式／书架管理／导出书单／日志 | `ShelfScreen.kt:212-227` |
| 我的·规则 | 替换净化／TXT 目录规则／字典规则／标签规则 | `MyScreen.kt:42-45` |
| 我的·其它 | 书签／阅读记录／缓存管理／文件管理／退出 | `MyScreen.kt:54-59` |
| 阅读页 | 换源（3×2 格） | `ReaderScreen.kt:81` |
| 书详情 | 换源（2×2 卡） | `DetailScreen.kt:77` |

书架 ⋮ 共 11 项，能用的是「布局设置／添加网址／导入书单」。
我的屏共 12 项，能用的是「书源管理／设置／关于」。

## 八、明确不做

| 项 | 理由 |
|---|---|
| 分组页签与横滑切组 | 189 dp 顶栏装不下；分组用下拉替代 |
| 长按多选与拖拽重排 | 手表长按易误触，无批量场景 |
| Glass 材质与共享元素转场 | 依赖 Compose |
| 侧滑抽屉、FAB 展开菜单 | 手表无边缘手势语义 |
| 阅读页 6 个顶栏按钮 | 已降为 3×2 图标格 |
| 表情／字体／颜色选择器 | 189 dp 无法排布 |
| 发现页书源卡内种类栅格 | 一行 6 权重，每格不足 28 dp |
| 目录书签／笔记两页 | 改为独立 `Marks` 屏 |
| 全文搜索正文块 | 189 dp 下每段只剩 1 行 |
| 换源迁移选项 8 个 checkbox | 全默认「沿用」 |
| 卷标题粘顶、快速滚动条 | 手表无对应手势 |

## 九、待拍板

1. **换源要不要建屏**：两处占位指向同一功能，同一坑出现两次。
2. **全文搜索要不要建屏**：目录已有章名过滤（`tocQuery`），但它只搜标题不搜正文。
3. **目录要不要补滑杆跳章**：500 章的书到第 400 章，现在要翻十几次窗口。
4. **设置页要不要两页化**：现在 7 组单页，legado 是 8 项两页。
5. **「我的 → 退出」语义**：legado 结束 Activity，手表上后台下载仍在跑，按钮含义有歧义。
6. **占位 19 项哪些真做**：书架 8 项大多依赖 legado 的分组、WebDav、本地书，我们都没有。

圆角那条已答（保持 12/12/8），不再列入。

## 十、验证方法

- `bash build.sh test`：`BackAffordanceTest`（`topBar` 必须带 `onBack`，三根屏必须用 `headerRow`）
  + `IconDataTest`（图标路径都在 24 网格内）+ `PaletteTest`。
- 装机后用 tesseract `chi_sim` 读文字。坐标从代码常量或 tsv 的 box 算，不拿 OCR 反推坐标。

# 手表端小说下载器

Android 手表（OPPO Watch 3 Pro，方屏）上的小说下载器。自带书源规则引擎与番茄小说适配器，
不需要手机中转，不需要服务端。

## 现在有什么

`src/com/watchdl/core/` —— 纯 Kotlin，不依赖 Android API，可单独跑自检：

| 文件 | 作用 |
|---|---|
| `CharsetFontDecoder` | 番茄正文的字体混淆还原（照抄 `fanqie-booksource` 方案） |
| `TinyJson` | 极简 JSON 路径取值、对象列表、正文抽取、转义还原 |
| `Rule` / `Template` | 书源规则模型与 URL 模板求值（只支持 `{{变量}}` 与四则运算） |
| `JobStore` | 双写状态：`job.json` 原子快照 + `chapters.jsonl` 逐章追加 |
| `BatchPlan` / `Backoff` | 分批、缩批、退避策略（照抄 TND） |
| `Downloader` | 串行下载引擎，支持续传、只重跑失败章、导出 TXT |
| `BookJob` / `ChapterRef` | 作业与章节状态 |

Android 层：

- `FanqieSource.kt` —— 番茄三条链路：搜索、目录、正文
- `MainActivity.kt` —— 方屏界面：搜索 → 目录 → 下载 → 导出

## 构建

不用 Gradle/AGP：本机是 Termux，`aapt2` / `d8` / `apksigner` 都是独立命令，整条链路无 Maven 下载。

```sh
bash build.sh test   # 跑纯 JVM 自检（不打包）
bash build.sh        # 打出 build/WatchDownloader-<版本>-release.apk
```

注意：脚本必须用 `bash build.sh` 调，不能 `./build.sh` —— 本机没有 `/bin/bash`。

依赖：`pkg install kotlin`、JDK 21、`~/android-sdk`（platforms/android-34 + build-tools）。

### 签名

正式签名信息在 `release.keystore` 与 `keystore-info.txt`（**自行备份**，丢了就没法发升级版）。
两个文件都在时自动用正式签名；缺任何一个则退回 debug 签名，流水线仍可用。

### 版本号

**每次改动都要升版本号，不能只在发包时升。** 用户要求，2026-10-21。

两个数一起改，改 `build.sh` 的默认值（`app/AndroidManifest.xml` 里的会被覆盖，但发新包时也应同步）：

| 变量 | 规则 |
|---|---|
| `APP_VERSION` | 主版本.次版本.修订，功能改动升次版本，修 bug 升修订 |
| `APP_VERSION_CODE` | 整数，**只增不减**，每版 +1 |

```sh
# 改 build.sh 第 23-24 行的默认值即可；临时覆盖用环境变量：
APP_VERSION=1.0.1 APP_VERSION_CODE=2 bash build.sh
```

`versionCode` 不升的后果（Android 平台行为，**本项目未实测**）：同版本号覆盖安装会被拒，装新包报 `INSTALL_FAILED_VERSION_DOWNGRADE`。

## 界面

按 OPPO Watch 3 Pro 实测尺寸设计（378×496 px @ 326 ppi → density 2.0 → **189 × 248 dp**）。
屏高只有 248dp，MD3 标准尺寸（应用栏 56 + 搜索框 48 + 按钮 40 = 144dp）会吃掉 58%，
所以全部按紧凑档取值（36 / 34 / 42 / 34）。

设计稿与可点原型在 `ui/`：

| 文件 | 说明 |
|---|---|
| `prototype.html` | 可点原型，含下载进度模拟 |
| `md3-final.html` | 静态设计稿，8 屏 + 尺寸对照尺 |
| `density-compare.html` | density 对照，说明为什么要收窄 |
| `icon-preview.png` | 图标预览 |

图标用 `python3 tools/make_icon.py` 重新生成。

## 协议要点（2026-10-15 实测）

三条链路**无签名、无 Cookie、无服务端**：

| 环节 | 接口 |
|---|---|
| 搜索 | `novel.snssdk.com/api/novel/channel/homepage/search/search/v1/?device_platform=android&parent_enterfrom=novel_channel_search.tab.&offset=&aid=1967&q=` |
| 目录 | `fanqienovel.com/api/reader/directory/detail?bookId=` |
| 正文 | `fanqienovel.com/reader/{item_id}` + `charset.json` 查表还原 |

### 踩过的坑（都是实测，别再踩）

1. **书名字段是 `title`，不是 `book_name`。** 搜索接口不返回 `book_name`。
2. **目录接口只返回两个键**：`allItemIds`（章节顺序）和 `chapterListWithVolume`（分卷嵌套）。
   没有书名和作者，书名要从搜索结果传入。
3. **`chapterListWithVolume` 是 `[[{}],[{}]]` 双层嵌套。** 按下标拍平会与 `allItemIds` 错位
   （实测 2500 章只抽出 75 章）。必须按 `itemId` 建映射连接。
4. **付费章返回 HTTP 404**，不是 IP 被封。`isChapterLock == true` 的章一律取不到，
   下载时直接跳过并标 `LOCKED`，不要浪费请求。
5. **免费章数量会变。** 同一本书（《绝世武神》）早先查是 2435 章免费，后来只有 10 章。
   这是番茄自己的策略，不是缓存问题，每次下载前重新拉目录。
6. **不要用回溯正则抽正文。** `"content":"((?:[^"\\]|\\.)*)"` 在 20 KB 以上的正文上会
   抛 `StackOverflowError`（手表上栈更小）。用 `TinyJson.extractContent` 的线性扫描。
7. **`fanqienovel.com/reader/{id}` 需要正常章节 id。** 拿"深度解读""某某归来"这类条目
   的第一条去取正文容易撞 404，按 `isChapterLock` 过滤后再取。
8. **`charset.json` 是 UTF-16 编码**，读的时候要指定字符集。表有两行，区间重叠
   （58344 / 58345 起），消歧靠"按 mode 0 先试 + 表项非 `?`"。

## 待办

- [ ] `nextContentUrl` 分页（部分书源正文分页，现在只取第一页）
- [ ] EPUB 流式导出（现在只有 TXT）
- [ ] 阅读器字号调整（现在固定 12sp）
- [ ] 真机验证：**界面从未在设备上运行过**

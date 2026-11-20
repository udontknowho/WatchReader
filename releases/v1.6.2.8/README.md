# 1.6.2.8 表冠改增量滚动、冷启动本地优先（code 59，已发布）

2026-11-20

1.6.2.7 把阅读位置换成了「章号 + 章内字符偏移」，页内落点已经准了；这一版修的是**表冠**这条路和**点书开卷**的等待。

## 改了什么

### 表冠跳章

| 文件 | 问题 | 修法 |
| --- | --- | --- |
| `core/CrownMotion.kt` | 每帧写的是 `scrollTo(0, start→target 的插值)`，一个**绝对坐标**。阅读器拼接区摘块、前插都会改内容总高，绝对值就落到别处；内容变矮时还会被 ScrollView 静默夹到新上限。 | 删掉 `start` / `target`，只留 `pending`（还没走完的像素）。`add()` 累加余量，`step(dtMs)` 按 `dt/duration` 折算消费，每帧至少 1 px。增量与内容总高无关。 |
| `ui/theme/Widgets.kt` | 同上，落点是 `scrollTo` 绝对值。 | `crownFrame` 改 `scrollBy(0, want)`。 |
| `ui/theme/Widgets.kt` | 边界判据是 `scrollY >= maxScrollOf(this)`，`maxScrollOf` 是**瞬时值**，摘一块立刻变，内容变矮时误判成「到顶」翻上一章。 | 判据改成「想走却没走动」（`scrollY == before`）。`onGenericMotionEvent` 不再预判边界。 |
| `ui/reader/ReaderScreen.kt` | 表冠贴着边界继续转时，每帧都回一次 `onBoundary`，一次翻好几章。 | `onBoundary` 加 400 ms 冷却。 |
| `ui/reader/ReaderScreen.kt` | 表冠飞行中照常摘块，摘块改内容总高，正在走的位移落到别的字上。 | `pruneWindow` 在表冠运行期间直接返回，表冠停了下次滚动监听自动补摘（窗口上限 4，多留两块内存可接受）。 |

### 冷启动点书

| 文件 | 问题 | 修法 |
| --- | --- | --- |
| `ui/reader/ReaderScreen.kt` | `preview()` 在 `loadOpening` 之前就调 `sourceForBook`：解包资源、解析 80 多个书源 JSON、同步等一次 DeviceRegister。手表上十秒级，而本地早就有正文。 | 新增 `readLocalChapter()`，只读本地缓存、不碰书源；`preview()` 先走这条捷径，命中就直接上屏，未命中才付解析书源的代价。 |
| `ui/reader/ReaderScreen.kt` | 非阅读屏点书时先清屏，几秒白屏看着像卡死。 | 清屏后铺一句「正在打开…」，正文到了整屏换掉。 |

## 怎么验证

1. 表冠：滚到章末继续转，只翻一章；反向转到章首继续转，只回一章，不再跳到上章末尾或两章前。
2. 表冠滚到一半停住，位置不再自己漂。
3. 书架点一本已下载的书，直接进正文，不再白屏等十秒。
4. 阅读位置（1.6.2.7 的字符口径）行为不变。

## 包信息

- 版本：1.6.2.8（code 59）
- 文件：`WatchDownloader-1.6.2.8-release.apk`，1 885 816 字节
- MD5：`29da39f4746006ca52346fee5f9e52f7`
- 签名：V3，`CN=WatchDownloader`
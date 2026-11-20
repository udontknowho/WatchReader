# 1.6.2.9 图标换成 reicon 线稿、新增 astryx 中性配色（code 60）

2026-11-20

两处观感问题：一是图标，手画的齿轮像圆圈加辐条、书架像三根竖条，小屏上认不出原形；二是配色，四套都带色相偏（暖纸、冷蓝），没有一套「纯中性」可选。

## 改了什么

### 图标：Material 填充 → reicon 线稿

| 文件 | 改动 |
| --- | --- |
| `tools/gen_reicon.py` | 新增。从 reicon v1.2.5（MIT）的 npm 包里抽 34 个图标的 Outline 路径，生成 Kotlin 数据表。脚本里带一张「应用图标名 → reicon 名」的映射表，改图标只动这张表。 |
| `ui/theme/ReiconData.kt` | 新增，生成文件。34 个图标共 59 条路径，路径数据 51 556 字节。 |
| `ui/theme/Icons.kt` | `DATA`（手抄的 Material 路径）整个删掉，改用 `REICON`。`IconView.onDraw` 从「画一条 Path」改成「按符号逐条画」，每条独立设 `FillType` 和 `Style`。 |

reicon 的 Outline 有两种形态，得分开处理：

- **多数是展开好的填充轮廓**（`fill="currentColor"`）——按 FILL 画就是 1.5 px 线宽的效果，小尺寸下比真描边干净。
- **少数仍是描边元素**（`fill="none" stroke="currentColor"`，chevron / swap / warning / save / wifi 这五个里有）——这些路径是**中线**，按 FILL 画会变成一块实心，必须走 STROKE。

所以 `ReiconPath` 多了一个 `stroke` 字段，`onDraw` 里按它切 `Paint.Style`。

另外 `fill-rule="evenodd"` 也得逐条尊重：reicon 用外轮廓套内轮廓来挖空（放大镜的镜圈、房子的门窗），用 WINDING 填会糊成实心块。

### 配色：新增 astryx 族（日 / 夜两套）

取自 Meta 开源设计系统 Astryx 的 neutral 主题实际取值：主色就是黑（夜间翻成白），层级全靠中性灰，没有色相偏。

| 套 | 底色 | 主色 | 选中块 |
| --- | --- | --- | --- |
| astryx | `#F1F1F1` | `#1B1B1B` | `#232323` |
| astryx·夜 | `#1B1B1B` | `#F1F1F1` | `#EAEAEA` |

层级方向照本应用的约定（`surface1..4` 浅色逐级变暗），不是 Astryx 那套「卡片比底色亮」——主题之间要能互换，方向得一致。

设置页「配色」从两格变三格：默认 / 米 / 中性。

## 怎么验证

1. 设置 → 阅读与外观 → 外观，配色条里点「中性」：整屏转成灰阶，选中块变近黑。
2. 点「夜」再回「日」，「中性」跟着换深浅，不丢族。
3. 放大镜（书架右上）是空心圆环加斜柄；返回箭头是空心左箭头；列表行的 chevron 是细线而不是实心三角。

## 包信息

- 版本：1.6.2.9（code 60）
- 文件：`WatchDownloader-1.6.2.9-release.apk`，1 906 296 字节
- MD5：`a36cabcf3ee129660476413dd3918fdb`

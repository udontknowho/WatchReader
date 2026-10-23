# legado 语法对照审查（书源引擎）

审查对象：`src/com/watchdl/core/{LegadoSelector,HtmlSelect,Rule,SourceEngine}.kt`
基准：https://hapelee.github.io/legado-with-MD3/dev/syntax.html
方法：读源码 + 用 kotlinc 编译核心层跑探针（探针在 `$TMPDIR`，未入库）。

## 一、逐条状态表

| # | 语法点 | 状态 | 具体问题 | 证据 |
|---|---|---|---|---|
| 1 | `class.X` → `.X` | 已支持 | — | LegadoSelector.kt:157 |
| 2 | `id.X` → `#X` | 已支持 | — | LegadoSelector.kt:156 |
| 3 | `tag.a` → `a` | 已支持 | — | LegadoSelector.kt:158 |
| 4 | `text.串` | 已支持 | 展开为 `*:text(串)`，`matches` 用 `containsText` 判包含；带独立自检 | LegadoSelector.kt:161-169；HtmlSelect.kt:341 |
| 5 | `children` 类型 | **未支持** | 没有分支处理，落到 `else` 变成类选择器 `.children[1]`，恒不命中 | LegadoSelector.kt:170 |
| 6 | 位置 `a.1`（0 起） | **实现有误（仅 `@tag.a.1` 形态）** | `@tag.div.1` 里 `tag` 被当类型分支吃掉，`expandPosition` 先于 `split('.')` 只做正则替换，`div.1` 留在输出里没人展开 | LegadoSelector.kt:139-147、166；探针 `toCss("tag.div.1@text")` → `div.1@text` |
| 7 | 位置 `class.x.1` | 已支持 | — | LegadoSelector.kt:182-188；探针 `class.c.1@text` → `B` |
| 8 | 负数位置 `.-1`（倒数） | **未支持（被静默删除）** | `p.replace(Regex("\\.-\\d+$"), "")` 把 `tag.div.-1` 直接砍成 `div`，语义变成「取第一个」而不是「取倒数第一个」 | LegadoSelector.kt:175；探针 `tag.div.-1@text` → `div@text` |
| 9 | 方括号 `[N]` / `[-1]` | 已支持 | — | HtmlSelect.kt:250-268；探针 `[−1]` → `x3` |
| 10 | 方括号排除 `[!N]` | 已支持（单值） | — | HtmlSelect.kt:247 |
| 11 | **点号排除 `!N` / `!a:b`** | **未支持** | `splitIndex` 只认 `\[...]$`，`!` 不在方括号里就原样留在选择器里，`matches` 拿不到标签 → 恒空 | HtmlSelect.kt:250；探针 `tag.p!1@text`、`tag.p!0:2@text` 全为空 |
| 12 | **列表倒置前缀 `-`** | **未支持** | `-tag.dd@...` 里 `-` 留在段首，`matches` 解析出标签 `-tag`，恒空 | LegadoSelector.kt:139；探针 `-class.l@tag.div@text` → `[]` |
| 13 | 区间 `[a:b]` | **实现有误（含端点）** | legado 区间是左闭右开，我们 `while (i <= b)` 含右端点，`[0:3]` 多取一个 | HtmlSelect.kt:283-290；探针 `tag.p[0:3]` 在 3 项上返回 `[0,1,2]` |
| 14 | 区间 `[a:b:c]` | 部分支持 | 步长可用，但同样含右端点；逆向 `[3:0]` 恒空（step 默认为 +1） | 同上；探针 `tag.div[3:0]` → `[]` |
| 15 | **序号放在无 index 的父层（逐个父取）语义** | 已支持 | 逐父节点应用 picker，实测 `[0:3]` 只取到单个 | HtmlSelect.kt:196-208 |
| 16 | `||` 备选 | 已支持 | 首个有结果胜出 | Rule.kt:214-221 |
| 17 | `&&` 串接 | 已支持 | — | Rule.kt:224 |
| 18 | `%%` 交替 | 已支持（近似） | 按「第 i 项配对拼接」，与文档「依次从各列表取」不同源：文档是交替展开成一项序列，我们是 `a[i]+b[i]` 字符串拼接。列表场景下（书名/链接分列）行为符合意图，但字段字段长度不等时会丢项 | Rule.kt:227-234；探针 `%%` → `[n1u1, n2u2]`，不等长 → `[n1u1, n2, n3]` |
| 19 | 混用分隔符 `a&&b\|\|c` | 部分支持 | `chain` 按优先级在**首个**命中的分隔符上定型，剩余的分隔符沉在段内，不再拆。文档要求「只在同种规则间使用」，可不修，但现在的行为是「静默只拆一种」 | LegadoSelector.kt:56-60；探针 `a&&b\|\|c` → `Chain([a, b\|\|c], And)` |
| 20 | `@css:` | 已支持 | — | LegadoSelector.kt:106-107 |
| 21 | `@json:` / `$.` / `$..` | 部分支持 | `$.` 可用；`$..` 递归依赖 JsonPathLite，此处未深挖 | HtmlSelect.kt:83-86 |
| 22 | `@xpath:` / `//` | 部分支持 | 位置谓词 `[N]` 只在 `parseStep` 里 parse 成 `Pred.Index`，`ok()` 对 Index 恒返回 true ——**位置谓词完全没生效** | HtmlSelect.kt:459-476 |
| 23 | `@js:` 只能放最后 | 已支持 | — | Rule.kt:329-345 |
| 24 | `<js></js>` 任意位置 | 已支持 | — | Rule.kt:334、359 |
| 25 | 正则 AllInOne `:` 开头 | 未支持（未见实现） | Rule.kt 无 AllInOne 分支 | — |
| 26 | 正则 OnlyOne `##re##to###` | 已支持（仅 REGEX_MARK 标记路径） | 普通字段的 `replace` 字段不认 `###` | Rule.kt:126-133 |
| 27 | 净化 `##re##to` 循环 | 部分支持 | 只用 `replace` 单遍替换，不做循环直到稳定 | Rule.kt:378-384 |
| 28 | 模板 `{{}}` | 部分支持 | 只认 `{{$.path}}`，非 `$` 开头的表达式交给 Template/JsEngine | Rule.kt:277-291 |
| 29 | `@textNodes` | **实现有误** | 用 `[^<>]+` 扫 innerHtml，把**标签名本身**也当文本节点（`span`、`/span` 进了结果） | HtmlSelect.kt:399-404；探针 → `[a\nspan\nc\n/span\nb]` |

## 二、按影响面排序的待修清单

### P0-1 点号排除 `!N` / `!a:b` 完全失效
- 影响：legado 文档明列的写法，源里常见（去表头行、去末行）。命中全部为空 → 该字段整段丢。
- 文件：`HtmlSelect.kt` / `splitIndex`
- 改法：正则扩到 `(?:\\[([!\\-\\d:]+)]|!([\\-\\d:]+))$`（段尾），把 `!a:b` 解析成 `Picker.ExcludeRange(a, b)`；`applyPicker` 里过滤 `norm(i) in a..b`。
- 同时 `LegadoSelector.expandPosition` 需要把 `tag.p!1` 转成 `p[!1]` 或原样交给 `splitIndex`（当前 `toCss("tag.p!1@text")` 得到 `p!1@text`，已能被新正则接住，无需额外改）。

### P0-2 列表倒置前缀 `-`
- 影响：`-tag.dd@tag.a@text` 是文档标准写法（目录倒序）。当前结果恒空 → 目录整本取不到。
- 文件：`LegadoSelector.kt` / `expandOne`（段首 `-` 识别）+ `HtmlSelect.kt` / `select`
- 改法：最小侵入是 `expandOne` 把段首 `-` 换成末段 picker `[rev]`，`splitIndex` 加 `Picker.Reverse`，在 `select` 的**最外层**最后一步倒序 `current`（legado 是对每层结果整体倒序）。若不想加 picker，可在 `query` 前加标记位传给 `select`。改动要落在 `select` 的 `idx == 0` 分支后对该层结果反转。

### P0-3 负数位置 `.-1` 被删除
- 影响：`tag.div.-1@text` 取「最后一个」变成取「第一个」，静默错值（不是空，最难发现）。
- 文件：`LegadoSelector.kt` / `expandOne:175`
- 改法：删掉 `p.replace(Regex("\\.-\\d+$"), "")`；改为把末尾 `.-N` 转成 `[-N]`（`div.-1` → `div[-1]`，与 `.book.-1` → `.book[-1]` 合并到 `expandPosition` 的正则里，允许 `-?\\d+`）。`HtmlSelect.applyPicker` 已支持负数。

### P1-4 `tag.div.1` 位置不展开
- 影响：`tag.X.N` 是文档最常见的取第 N 个元素的写法；`@tag.a.1` 形态里 `tag` 分支吃掉 `tag` 后残留 `div.1`，恒空。
- 文件：`LegadoSelector.kt` / `expandOne`
- 改法：在 `token == "tag"` 分支里紧跟一段判断「下一个 token 是纯数字」→ 输出 `[N]`（与 `text` 分支的写法对称，`text` 分支已经这么做了）。根因同源：位置展开发生在 `split('.')` **之前**，而 `tag.` 前缀让 `expandPosition` 的正则 `^([a-zA-Z]...)\\.(\\d+)$` 匹配不到 `tag.div.1`。要么在 `split('.')` 后再统一补一次位置，要么在 `tag` 分支里就地处理。

### P1-5 `children` 类型
- 影响：文档列为五种基本类型之一。当前变成 `.children[1]` 类选择器，恒空。
- 文件：`LegadoSelector.kt` / `expandOne`（加 `"children"` 分支）+ `HtmlSelect` 需要「直接子节点」语义
- 改法：`children.N` → `*` + 只在**直接子节点**里取第 N 个。现有 `select` 的逐父查找会用「区间包含」而不是「直接父」，需要给 `select` 增加一个 `directChildOnly` 参数（或用 `parent.start`/`parent.end` 之间**无其他节点包裹**判直接子级）。这是本清单里唯一需要动选择器主循环的一项，排在编号上靠前是因为它缺失最彻底，实际调用量通常低于 P0。

### P1-6 `[a:b]` 区间含右端点
- 影响：`[0:10]` 会多取一个；配合 `%%`/`&&` 时列错位。
- 文件：`HtmlSelect.kt` / `applyPicker:283-290`
- 改法：`b` 归一化后作为**排他上界**：`while (step > 0 && i < b)` / `while (step < 0 && i > b)`。注意 `b == null` 时上界要取 `len` 而不是 `len-1`。改完要同步改 `TextSelectorTest`/`RuleBoundaryTest` 里依赖含端点行为的断言。

### P1-7 `@textNodes` 把标签名当文本
- 影响：正文用 `@textNodes` 的源会混进 `span`、`/span` 等字样，污染正文。凡是清洗不掉的站名/广告判断也会跟着错。
- 文件：`HtmlSelect.kt` / `extract` 的 `TEXT_NODES` 分支（399-404）、`XPathLite.select` 的同名分支（466-470）
- 改法：不要用 `[^<>]+` 扫整段 innerHtml，应扫描「标签之间的文本」，即 `Regex("(?s)>([^<>]*)<")` 或复用 `nodes()` 已经算好的区间做减法。两处共用，抽一个小函数 `textNodesOf(inner: String): List<String>`。

### P2-8 `%%` 语义偏差
- 影响：文档「依次从各列表取」= 交替展开；当前实现是「同下标拼接」。列表字段（`bookList` + 各列规则）里拼接正好是想要的，但 `chapterList` 这种「多列表合并成一条序列」的场景会错。
- 文件：`Rule.kt` / `eval` 的 `Join.Merge`（227-234）
- 改法：先确认线上源实际用的是哪种。若两种都要，按 `list` 参数分派：`list=false` 走拼接，`list=true` 走交替展开（`cols.flatMap` 按下标 round-robin）。**改动前先跑一遍源库统计，别凭文档改。**

### P2-9 XPath 位置谓词无效
- 影响：`//div[2]` 恒返回全部 div；`contains()` 直接返回空。
- 文件：`HtmlSelect.kt` / `XPathLite.ok`（Index 恒 true）
- 改法：在 `XPathLite.select` 的过滤后、对每个父节点的子集应用 `Pred.Index`（与 CSS 的 `applyPicker` 同义，0 起）。`contains()`/`position()` 保留当前「返回空不猜」的策略。

### P2-10 净化正则只跑一遍
- 影响：`##<br>##\n` 这类需要多轮的规则只做一次。
- 文件：`Rule.kt` / `applyReplace`
- 改法：循环替换直到结果不再变化（上限 10 轮防死循环）。**要确认 legado 到底是不是循环**，文档写「循环替换」，但官方 `AnalyzeRule.replaceRegex` 实际是单次 `replaceAll`。建议先验证再改，本轮不动。

## 三、明确不建议动的地方

- `text.` 展开（`*:text(串)` + `containsText`）：形态与 legado 一致，有独立自检，不动。
- `chain` 的「首个分隔符定型」：文档限制「不能混用」，当前行为与官方 `splitRule` 一致，不动。
- `##re##to###` OnlyOne：已有 `REGEX_MARK` 路径与 `firstOnly` 判断，不动。

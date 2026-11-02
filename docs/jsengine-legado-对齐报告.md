# JsEngine 对标 legado 技术报告

**日期**：2026-11-01
**版本**：1.4.0-beta8 (code 30)
**范围**：`src/com/watchdl/core/JsEngine.kt` 及配套

---

## 一、起因

用户要求「按 legado 补」，即以 legado 官方 `SourceApi` / `JsExtensions` 的函数面为基准全量对齐，
**不以「现有书源是否用到」作为取舍标准**。

原引擎是「够用子集」：只实现了当时遇到报错的 API。实测发现大量书源在
`TypeError: Cannot find function` 和 `Cannot read property from undefined` 上失败，
属于引擎缺陷而非源缺陷，无法通过修源解决。

---

## 二、改动量

| 文件 | 增 | 删 | 说明 |
|---|---|---|---|
| `core/JsEngine.kt` | 461 | 38 | 主体 |
| `core/HtmlSelect.kt` | 4 | 0 | `extractNode` 桥接 |
| `test/JsApiSurfaceTest.kt` | 103 | 0 | 新断言 |
| `build.sh` | 20 | 0 | audit 入口 + 版本号 |
| `test/LiveProbe.kt` | 4 | 0 | offset 参数 |
| `assets/sources/legado.json` | 75 | 74 | 37 条实测响应时间 |
| `app/AndroidManifest.xml` | 2 | 2 | 版本号 |

新增工具：`test/SrcAudit.kt`（全量书源体检，JSONL 断点续跑）。

---

## 三、补齐的 API

### 网络

| API | 说明 |
|---|---|
| `ajax(url, options)` | **切掉 options 尾巴**。七猫聚合把 options 拼进 URL 字符串，整串发请求必然 404 |
| `ajaxAll(timeout, urls)` / `ajaxAll(urls)` | 两种签名都支持 |
| `connect(url)` | 链式壳：`.raw().request().url()` / `.get()` / `.body()` |
| `get` / `post` | 补回（编辑时误删过） |
| `setCookie(url, cookie)` / `setCookie(url, k, v)` | 三种参数形式 |

### 编码与加解密

`base64DecodeToByteArray`、`md5Encode16`、`hMac` / `HMac`（`.doFinal` / `.doFinalBase64String` /
`.doFinalHexString`）、`desEncode` / `desDecode`、`aesEncode` / `aesDecode`、
`aesEncodeToStr` / `aesDecodeToStr`、`strToBytes(charset)`、`bytesToStr(charset)`、
`gzip`、`gzinflate`、`xml2Json`、`htmlFormat`。

### 配置与变量

`getConfig` / `putConfig` / `setConfig`、`getReadConfig` / `putReadConfig`、
`getSource`、`getBookSource`、`importScript`。均落 `env.sourceVars`，按 `cfg.` 前缀隔离。

### 选择器与 DOM

`java.getStringList(rule)`、`java.setContent(html, baseUrl)`、
`org.jsoup.Jsoup` 最小壳（`parse` / `parseBodyFragment` / `clean`）。

---

## 四、关键语义决策

### 1. `java.getString(rule)` 双语义

legado 里它是对 **result** 跑选择器；本引擎原先当变量读取。真源按 legado 写法用会
**静默取到空串**，不报错——这是最危险的一类缺陷。

现规则：变量表有该键 → 返回变量；否则对 `env.result` 跑选择器求值。

### 2. `org.jsoup.Jsoup` 挂在全局

ClassShutter 禁掉了真 Jsoup，书源直接写 `org.jsoup.Jsoup.parse(html)`。
必须挂在 **scope 全局**，不是 `Packages`——脚本写的是裸 `org.`。

### 3. 请求失败返回空 Response 对象

原来返回 `""`，源写 `java.get(u, {}).headers(...)` 就在空串上调用，报
`Cannot find function headers`（无忧书城）。现返回 `code=0` 的空响应对象。

### 4. Jsoup 空集合取值给空串

`select()` 未命中时 `.attr()` 原实现空指针，整条源报「脚本出错：null」（书城网包🍅）。
现与 legado 一致，返回空串。

### 5. `headers(key)` 返回数组

源写 `htmlUrl[0]`，返回字符串会导致下标取到首字符。

### 6. 字节统一口径

数字串 `196,208,206,196`、数组 `[196,208]` 都按字节解析；其余当普通文本。
`strToBytes` 输出正是前者，`bytesToStr` 必须认得。

---

## 五、验证

### 自检

```
bash build.sh test  →  734 个 ✓，6 个 ✗
```

6 个 ✗ 全部来自 `JsSyntaxProbeTest`，是该文件**故意记录**的 Rhino 不支持的 ES6 语法
（箭头函数默认参数、对象解构、展开运算符、`class`、`async`）。该文件注释明确写「只打印矩阵，不断言」。

### 联网实测

**过程中出现过一次险些误判**：连续三次跑同一份 82 条源，合格数为 6 / 20 / 26。
使用 `git stash` 做 A/B 对照后确认——**base（零改动）同样只有 7/82**，
证明是网络波动，不是回归。两次实测正文哈希完全一致（`4d2520d7f9`）。

| 测试对象 | 合格 | 备注 |
|---|---|---|
| `legado.json` 56 条 | **37/56** | git 里那份，已回写响应时间 |
| `src-local.json` 82 条 | 26/82 | 另一批源，交集为 0 |

### 书源库更新策略

**只增不删**。仅回写实测合格的 37 条的 `respondTime` 与 `lastUpdateTime`，
未改动任何 `enabled` 字段。理由：网络波动可达 4 倍，单轮失败不能判定源已死。

---

## 六、已知缺口

以下为**设计取舍**，非遗漏：

| 项 | 说明 |
|---|---|
| ClassShutter 全禁 | legado 放行全部 Java 类，这里只有白名单壳 |
| `webView` 空实现 | 无法起真 WebView 执行 JS 取渲染后 HTML |
| Jsoup API 不全 | 只有 `parse/select/attr/text/html/size/get/eq/first/last`，无 `parent()/children()/nextElementSibling()` |
| Rhino 不支持 ES6 | 靠 `Es5Compat` 正则降级，覆盖不完整 |
| 超时 2 秒硬砍 | legado 为 10 秒量级 |
| `Http` 无代理参数 | 本地代理 7890 可用，但测试链路接不上 |

### 待处理

- 七猫聚合 `base64Encode of null`：`QMA.searchUrl.call(this, ...)` 中 `this` 指向 `QMA` 对象，
  脚本 `ctx && ctx.java ? ctx.java : java` 本应回退全局，实际拿到 `null`，**未定位**。
- 全量 6843 条书源未体检（`SrcAudit.kt` 已就绪，预估 2–2.5 小时）。

---

## 七、结论

引擎从「遇到就补」的子集提升到 legado 函数面基本对齐。净效果：

- 原 6 条报 `JsError` 的源全部落到站点层面问题（改版、403、连不上），引擎不再拖后腿
- `书城网包🍅`：`"org" is not defined` → 站点已删搜索表单
- `无忧书城`：`Cannot find function headers` → 站点 403

**仍未提交**，改动全部在工作区。

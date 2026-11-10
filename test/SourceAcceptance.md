# 书源在线验收工具

`SourceAcceptance.kt` 通过实际 LegadoSource 检查单个候选源。
`SourceAcceptance.py` 调度独立 JVM，限制单源和全轮时间。
工具不属于 `build.sh test` 的离线回归，不应把网络失败判为永久失效。

## 输入与准备

- `--candidates` 指向任意 Legado JSON 数组，不再按中文文件名搜索。
- `--out` 指向不存在或为空的独立证据目录，不覆盖以前的证据。
- `--jar` 默认 `build/test.jar`，需要包含核心代码与 `SourceAcceptance.kt`。
- `--previous` 可选，仅按旧源地址调整复测顺序，不要求备份文件存在。
- 默认使用 `libs/rhino-slim.jar` 和 Termux Kotlin 标准库，可用 `--classpath` 覆盖。
- 先运行 `bash build.sh test` 生成核心测试 jar，再运行：

```sh
timeout 1260 python3 -B test/SourceAcceptance.py \
  --candidates candidates/待验收.json --out .fj-tmp/acceptance-161 \
  --workers 2 --first-timeout 30 --retry-timeout 60 --total-timeout 1200
```

`build.sh test` 会编译 `test/` 下的 Kotlin 文件，包含此探针。
运行前应确认本地候选集合、依赖与内存预算。探针会实际访问第三方站点。

## 判据与输出

- 每源最多尝试 3 个关键词，每个关键词最多取 2 本书。
- 目录不截断，至少有 3 个非公告抽样候选，抽其首、中、末正文。
- 只跳过标题明确的公告、请假条、感言，章节编号优先保留，完整目录不变。
- 每章至少 300 汉字、80 个不同汉字，拒绝重复正文与明显错误页。
- 默认 2 个 JVM，并发可设 1–6，首轮 30 秒、复测 60 秒、全轮 1200 秒。
- 用 `--no-retry` 禁用复测，各轮日志分别保存，不覆盖首轮证据。
- 超时和截断记录为未定状态，不表示书源永久失效。
- 证据、摘要和通过集合均写入指定的 `--out` 目录。
- 按源地址去重的结果为 `passed.json`，不可据此声称全书全时段可用。
- 调度器不会自动覆盖应用源库，也不会提交、推送或发布。

## 离线验证

`PYTHONDONTWRITEBYTECODE=1 python3 -B test/SourceAcceptanceOfflineTest.py` 模拟 JVM 输出。
`bash build.sh test` 自动运行 `TocBudgetTest` 中的目录及抽样回归，不运行在线验收。

## 清理与回滚

工具不修改正式源库。保留所需证据后删除本轮临时目录即可。
不要删除他人正在使用的 `.fj-tmp/` 子目录。

# 书源在线验收工具

`SourceAcceptance.kt` 通过实际 LegadoSource 检查单个候选源。
`SourceAcceptance.py` 调度独立 JVM，限制单源和全轮时间。
工具不属于 `build.sh test` 的离线回归，不应把网络失败判为永久失效。

## 输入与准备

- 项目根目录需要自行提供 `墨辰*7.5*.json` 候选数组，原始合集不入库。
- 编译产物位于 `.fj-tmp/source-integration/core.jar`。
- 此 jar 需要包含核心代码与 `SourceAcceptance.kt`，不包含 Android 界面。
- 原有源库快照放在 `.fj-tmp/source-integration/backup/legado.json`。
- 调度器还使用 `libs/rhino-slim.jar` 和 Termux Kotlin 标准库。
- 先运行 `bash build.sh test` 生成核心测试 jar，再运行：

```sh
mkdir -p .fj-tmp/source-integration/backup
cp app/assets/sources/legado.json .fj-tmp/source-integration/backup/legado.json
cp build/test.jar .fj-tmp/source-integration/core.jar
timeout 1260 python3 test/SourceAcceptance.py
```

`build.sh test` 会编译 `test/` 下的 Kotlin 文件，包含此探针。
运行前应确认本地候选集合、依赖与内存预算。探针会实际访问第三方站点。

## 判据与输出

- 每源最多尝试 3 个关键词，每个关键词最多取 2 本书。
- 目录不少于 3 章且不截断，抽取第一章、中间章和最后一章。
- 每章至少 300 汉字、80 个不同汉字，拒绝重复正文与明显错误页。
- 六个并行 JVM，首轮每源 14 秒，未通过项复测 24 秒，全轮 1200 秒。
- 证据、摘要和通过集合均写入 `.fj-tmp/source-integration/`。
- 按源地址去重的结果为 `passed.json`，不可据此声称全书全时段可用。
- 调度器不会自动覆盖应用源库，也不会提交、推送或发布。

## 清理与回滚

工具不修改正式源库。保留所需证据后删除本轮临时目录即可。
不要删除他人正在使用的 `.fj-tmp/` 子目录。

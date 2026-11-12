# 阅读体验修复 1.6.1.3（code 44）
## 改动与验证
- 优化书架进度读取、目录索引和章节定位缓存，减少主线程写盘。
- 共享当前章与预取请求，移除章末拼接，修复跨章等待、失败及恢复状态。
- 阅读与全局统一日/夜主题，移除独立背景及亮度入口，亮度交还系统。
- 本轮完整离线测试40组通过；UI仅编译校验通过；正式APK构建通过。
- 最终APK经 apksigner verify --verbose --print-certs 验证通过，单签名者，v3签名。
- 证书SHA-256：0635de25e9c423c272b3607c62ed9426405796ba2c67fe0a4626717e557276d0。
- 真机交互、安装和联网行为未验证。
- APK：`/data/data/com.termux/files/home/WatchDownloader/build/WatchDownloader-1.6.1.3-release.apk`
- APK：1828472 字节；MD5 `038219b0f3546e9bdf2897657ce8481d`。
- 回滚包：`/data/data/com.termux/files/home/WatchDownloader/releases/v1.6.1.3-rollback.tar.gz`
- 回滚包：6235993 字节；MD5 `592c0a0dd50fba686ce541eaf8b877a3`；归档内容逐字节比对通过。
## 文件校验（MD5 修改前 → 修改后；diff新增/删除）
| 文件 | 修改前 | 修改后 | + / - |
|---|---|---|---|
| `src/com/watchdl/MainActivity.kt` | f0bd717eed8c2818a53351ccac79dd7c | 885ddde5a3ad0bed6b3eefcdc30a8727 | 65 / 22 |
| `src/com/watchdl/core/JobStore.kt` | 2da5c8e0dfe61396ae913c96e0c71661 | 809c3e4651d0ce17c655f7ae17f02d06 | 49 / 13 |
| `src/com/watchdl/core/ReadingBookStore.kt` | 6dce564e6fdb6ec6a1b2ca0808a014c3 | 9c747a6e6c97c8dc72d1c2090b62db47 | 30 / 5 |
| `src/com/watchdl/core/Palette.kt` | 18739ce7d65e327da90f9bce4292f433 | e40bed21378674de74bd8261ffea0308 | 23 / 7 |
| `src/com/watchdl/ui/my/SettingsScreen.kt` | 15cbb242f6d1c3e95bdf2462013478a2 | 81eaff4c4a212e5323547dc811e2ef75 | 8 / 47 |
| `src/com/watchdl/ui/reader/ReaderScreen.kt` | bb97968e694e245a0dbc16c4fff2baca | 631541e82788545e67a8b3e409b9da62 | 103 / 158 |
| `src/com/watchdl/ui/reader/ReaderSettingsScreen.kt` | 451976ec6fee884bf8e70ed9d2a77be1 | 5b023cd91c867a957c56e7ad20e6736a | 7 / 7 |
| `build.sh` | bc2e7019bbc1157630e656f6e91e65f8 | b6b42951dff5425bf19064463a0ff913 | 3 / 3 |
| `app/AndroidManifest.xml` | 291825a41f011683ceefdc05ecc2bb30 | 2d9bf9280ac676af7816692d0699e09d | 2 / 2 |
| `test/PaletteTest.kt` | 2b4b332f81dd7f62d1a630fdd99f72ab | 02ef41947ce10d88d7d721d1f762455f | 12 / 14 |
| `test/ReaderStoreIndexTest.kt` | 不存在 | 8c1ee77bb0042d9c196e920fdb6c70cb | 75 / 0 |
- 已跟踪10文件合计 +302/-278；新增测试 +75/-0；全部11文件合计 +377/-278。
- `git diff --check`通过；原文件10份均与修改前HEAD一致，未误收修改后文件。
## 回滚（在项目根目录执行；会覆盖上述10文件）
```sh
cd /data/data/com.termux/files/home/WatchDownloader
tar -xzf releases/v1.6.1.3-rollback.tar.gz --exclude=old-build --exclude=ROLLBACK-MANIFEST.txt
rm -- test/ReaderStoreIndexTest.kt
```
- 旧build保存在回滚包内；需恢复时执行以下命令。当前build先移入临时退路目录。
```sh
rollback_tmp=$(mktemp -d "${TMPDIR:-/tmp}/watchdl-rollback.XXXXXX")
mv -- build "$rollback_tmp/build-current"
tar -xzf releases/v1.6.1.3-rollback.tar.gz -C "$rollback_tmp" old-build/build-before.tar.gz
tar -xzf "$rollback_tmp/old-build/build-before.tar.gz" -C "$PWD"
```
- 旧build恢复核验后，可自行删除上述临时退路目录；不影响用户书架与阅读数据。

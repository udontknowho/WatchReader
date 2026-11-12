# 1.6.1.4（code 45）

## 修改
- 书架点书先显示加载页，收藏解析、目录恢复与书源冷初始化移至后台。
- 目录快照保存走跨页面有序后台队列，去除正文入口重复保存。
- 收藏与阅读存储复用快照，无变化不重写完整目录。
- 正文完成缓存采用 LRU，最多 24 章、512K 字符，按来源与书章身份隔离。
- 全局暖白日间、近黑夜间覆盖根布局、窗口、系统栏和平台对话框主题。
- 修复加载占位页误入返回栈、来源池并发初始化和旧设置闭包继续执行。

## 执行与验证
```sh
cd /data/data/com.termux/files/home/WatchDownloader
bash build.sh test
bash build.sh
```
- 41 组回归通过，包含新增缓存和存储并发测试。
- 全量 Android 界面 Kotlin 编译、资源链接、正式签名校验通过。
- APK 核验：com.watchdl，versionName 1.6.1.4，versionCode 45。
- 正式签名证书 SHA-256：0635de25e9c423c272b3607c62ed9426405796ba2c67fe0a4626717e557276d0。
- 未安装、未启动应用。实际点击耗时、网络耗时及日夜原生控件外观未实测。
- 缓存仅驻进程，重启后需重新取在线正文。进程强杀可能丢失尚未执行的保存。

## 回滚
- 修改前已备份 tracked 源码：releases/v1.6.1.4-rollback.tar.gz。
- Git 回滚宜在无未提交改动时使用本次发布提交的 `git revert`。
- 离线恢复源码可在项目根执行 `tar -xzf releases/v1.6.1.4-rollback.tar.gz`，再删除本版新增的 ChapterTextCache.kt 与 ChapterTextCacheTest.kt。
- 归档不含用户数据，不恢复设备安装状态。旧 APK 可在 v1.6.1.3 发布页取得。

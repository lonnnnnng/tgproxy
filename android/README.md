# TGProxy Android

TGProxy Android 是面向 ARM64 手机的 Telegram 频道视频索引和播放器。App 使用 Telegram 用户账号读取已加入频道中的视频，索引保存在本机，播放通过手机内的 Range 服务交给 Media3。它不依赖 Linux TGProxy 服务、Jellyfin 或媒体树。

当前版本为 **0.5.0**，采用紧凑文字列表，支持频道一键更新、逐频道状态、发布日期、视频详情及播放控制面板。仅支持 `arm64-v8a`、Android 8.0/API 26 及以上。

## 构建环境

- Go 1.27.3
- JDK 17
- Android SDK Platform 36 和 Build Tools 36.0.0
- Android NDK 28.2.13676358
- 在 `android/local.properties` 设置本机 Android SDK 路径；该文件不会提交到 Git：

```properties
sdk.dir=/Users/<用户名>/Library/Android/sdk
```

## Debug 构建

从仓库根目录执行：

```sh
cd android
zsh build-core.sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:assembleDebug
```

Go 手机核心会生成为 `app/libs/tgproxy.aar`，Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。Debug application ID 为 `dev.tgproxy.mobile.poc`，应用名称为“TGProxy 验证”，可与正式包并存，也可原地升级本机验证版。

## Release 构建

```sh
cd android
zsh build-release.sh
```

脚本依次构建 ARM64 Go AAR、Release APK、对齐并签名，最后验证 APK 签名和 SHA-256。输出文件为 `app/build/outputs/apk/release/tgproxy-android-arm64.apk`，application ID 为 `dev.tgproxy.mobile`，应用名称为 `TGProxy`。首次运行会在 `android/.signing/` 生成本地 Release 密钥与密码文件；后续升级必须复用同一份签名材料。该目录已被 Git 忽略，应妥善备份，不能提交。

Release 与 `.poc` Debug 使用不同 application ID，因此是两个独立应用，首次安装 Release 时需要单独登录。当前仅包含 `arm64-v8a`。

## 获取发行版

从 [GitHub Releases](https://github.com/lonnnnnng/tgproxy/releases) 下载 `tgproxy-android-arm64.apk`。升级时直接安装新版即可保留 App 数据；请勿先卸载旧版。确认文件完整性时，对照 Android 发行说明中的 SHA-256。

## 安装到手机

打开手机的 USB 调试并授权电脑，从 `android/` 目录执行：

```sh
adb devices -l
# long: 明确选择目标手机，避免连接多台设备时误装。
adb -s SERIAL install --no-incremental -r app/build/outputs/apk/release/tgproxy-android-arm64.apk
```

把 `SERIAL` 替换为上一步确认的 ARM64 手机序列号。覆盖升级需保持 application ID 和签名不变；不要通过卸载旧包来处理升级问题，卸载会删除该包的本机数据。

## 登录与使用

1. 在“我的”页填写带国家区号的手机号，输入 Telegram 验证码；账号启用两步验证时再填写密码。
2. API ID 和 API Hash 是可选项。两项都留空时使用项目预设值；填写自有凭证时必须成对提供。
3. 在“频道 → 添加频道”中从 Telegram 已加入的频道中选择，或输入公开频道链接/ID 添加频道。
4. 频道顶部的更新按钮直接更新全部频道；每行的小更新按钮只更新该频道。首次更新只建立最新一页的索引；更早的视频从“更多 → 扫描历史”补齐。之后更新只同步新增，页面和游标逐页保存。
5. 在“视频”页搜索名称、按频道筛选、查看最近播放、分页和播放。首页采用无封面的紧凑文字列表，显示频道、发布日期和文件大小；可按发布时间或名称排序。点击打开详情，查看完整文件名和发布时间，再播放或续播。切换页面保留列表位置，翻页回到顶部；播放器退出到后台后可继续播放，可从底部入口返回。
6. 在“我的 → 外观”中选择跟随系统、浅色或深色。API 凭证折叠区域不会清空未提交内容，Hash 和两步验证密码默认遮蔽。

发布时间使用已有 Telegram 消息时间，按设备时区显示；缺失时隐藏。没有相册模式或展示模式设置。

更新时当前频道和顶部按钮旋转，原有副文本显示进度，排队频道保持静止。点击运行中的按钮暂停整批；失败在对应行显示，可点击重试，原因在更多菜单查看。批量更新遇到某个频道失败仍继续其余频道。后台通知也提供暂停入口，进程退出后已提交的断点仍保留。

频道菜单的“校验已索引资源”用于更新频道名称及已索引视频信息，处理改名、换片和明确删除；失败批次会保留本机索引，暂停后可继续校验。它与普通更新新增视频是不同操作。

“我的 → 运行诊断”可查看连接状态、下载速度、缓存命中和恢复次数。损坏授权可在账号页重置；重新授权会检查账号归属，旧版无归属记录时可能需要确认原账号。

频道扫描暂停、App 进程被回收或系统结束后台任务后，可以从最后提交的游标继续。移除频道只删除本机对应的索引和播放记录，不会退出 Telegram 频道。

API 凭证和授权会话使用 Android Keystore 加密，文件位于应用 `noBackupFilesDir`；SQLite 位于应用私有目录。内置 Go HTTP 服务只监听 `127.0.0.1`，并要求随机 Bearer token。公开分发前应改用在 Telegram 官方 `my.telegram.org` 为自己的应用申请的 API ID/Hash。详情见 [技术说明](TECHNICAL.md) 和 [设备验收记录](VERIFICATION.md)。

## 播放器控制

底栏直接选择并记住倍速，长按画面临时 2×，松手恢复；双击左侧后退 10 秒、右侧前进 10 秒。左侧上下滑动调亮度，右侧调媒体音量。支持锁定防误触，以及适应、裁切、拉伸、16:9、4:3 五种画面模式。单击显示/隐藏控制栏，更多菜单可查看操作说明、播放信息、字幕和音轨。

锁定后点按画面显示“解锁控制”，系统返回键也可先解锁。亮度仅在当前播放器窗口生效，进入后台恢复；媒体音量保留用户调整。后台播放、同一视频恢复、字幕和音轨沿用 Media3。

控制器已拆为可单独构建 AAR 的 [player-controls 模块](player-controls/README.md)。[Android 0.5.0 发行版](https://github.com/lonnnnnng/tgproxy/releases/tag/android-v0.5.0) 同时提供 App APK 和组件 AAR，组件接入无需 Telegram 或 TGProxy 服务。

## 测试

```sh
cd tgproxy/mobile
go test -race ./...
go vet ./...

cd ../../android
zsh build-core.sh
ANDROID_SERIAL=<已确认的ARM64手机序列号> \
  JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  ./gradlew :app:connectedDebugAndroidTest
```

真实 Telegram 验收用例需要已授权的目标设备，并显式传入 `-e liveTelegram true`；它会读取账号可见频道和消息，并播放已有索引中的视频，不会输出频道名、文件名、播放 URL 或授权数据。

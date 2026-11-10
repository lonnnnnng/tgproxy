# TGProxy Android

TGProxy Android 是面向 ARM64 手机的 Telegram 频道视频索引和播放器。App 使用 Telegram 用户账号读取已加入频道中的视频，索引保存在本机，播放通过手机内的 Range 服务交给 Media3。它不依赖 Linux TGProxy 服务、Jellyfin 或媒体树。

当前版本为 **0.2.1**，仅支持 `arm64-v8a`、Android 8.0/API 26 及以上。

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

1. 在“账号”页填写带国家区号的手机号，输入 Telegram 验证码；账号启用两步验证时再填写密码。
2. API ID 和 API Hash 是可选项。两项都留空时使用项目预设值；填写自有凭证时必须成对提供。
3. 在“频道”页从 Telegram 已加入的频道中选择，或输入公开频道链接/ID 添加频道。
4. 分别扫描历史视频、继续历史扫描，或检查并同步新增内容。每完成一页，视频条目和原始 Telegram 消息游标会一并保存。
5. 在“视频”页搜索名称、按频道筛选、查看最近播放、分页和播放。切换页面保留列表位置，翻页回到顶部；播放器退出到后台后可继续播放，可从底部入口返回。
6. 在“账号 → 外观”中选择跟随系统、浅色或深色。API 凭证折叠区域不会清空未提交内容，Hash 和两步验证密码默认遮蔽。

频道扫描暂停、App 进程被回收或系统结束后台任务后，可以从最后提交的游标继续。移除频道只删除本机对应的索引和播放记录，不会退出 Telegram 频道。

API 凭证和授权会话使用 Android Keystore 加密，文件位于应用 `noBackupFilesDir`；SQLite 位于应用私有目录。内置 Go HTTP 服务只监听 `127.0.0.1`，并要求随机 Bearer token。公开分发前应改用在 Telegram 官方 `my.telegram.org` 为自己的应用申请的 API ID/Hash。详情见 [技术说明](TECHNICAL.md) 和 [设备验收记录](VERIFICATION.md)。

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

# TGProxy Android v0.3.0

本版本升级播放器操作体验，并将播放控制界面拆为可供其他 Android 项目复用的组件。

## 更新内容

- 倍速入口直接显示在控制栏，记住上次选择的倍率。
- 长按画面临时以 2× 播放，松手、取消或进入后台后恢复原倍率。
- 双击画面左侧后退 10 秒，右侧前进 10 秒。
- 左侧上下滑动调节亮度，右侧调节媒体音量；离开播放器后恢复原窗口亮度。
- 新增锁定防误触；点按画面可唤出解锁入口，系统返回键也可解锁。
- 支持适应、填满裁切、拉伸铺满、16:9、4:3，并记住选择。
- 优化标题、进度条、横竖屏布局和操作提示，保留后台播放、音轨及字幕选择。

## 下载与升级

手机安装 `tgproxy-android-arm64.apk`，要求 ARM64、Android 8.0（API 26）及以上。

沿用原应用包名与签名，可从正式版或同签名体验包直接覆盖升级，保留账号、频道和播放记录，请勿先卸载。

## 开发者组件

`player-controls-release.aar` 是可选的播放器控制组件，接收 Media3 Player/MediaController，不依赖 Telegram 或 TGProxy 业务。AAR 不内嵌 Maven 依赖，接入方式与依赖版本见 [组件文档](https://github.com/lonnnnnng/tgproxy/blob/android-v0.3.0/android/player-controls/README.md)。

## 验证

- 发布前已完成用户验收；POCO F2 Pro 设备回归 15 项通过、1 项已登录状态下的登录草稿用例跳过。
- 已验证倍速恢复、手势、锁定、比例切换、大字体、Range 快进及后台播放。
- APK 沿用现有 Release 证书，通过 v2/v3 签名校验。
- APK 与 AAR 均附独立 `.sha256` 校验文件。

APK SHA-256：`12f2fd2a7d827ba0ac57e579ac9d2b25f107cf603ff260796f2d0099a76f0fe6`

AAR SHA-256：`0d011a3ca1e919703e0cfa94c2dc00f54461dc86d60d77d0c957da99bdf92ecf`

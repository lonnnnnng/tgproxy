# TGProxy Android v0.2.1

TGProxy Android 是面向 ARM64 手机的 Telegram 频道视频索引和播放器。本版本完善了频道浏览界面，并提供浅色/深色主题选择。

## 更新内容

- 视频搜索、频道筛选和播放模式固定在列表上方；仅结果列表滚动，修改搜索条件后回到结果顶部。
- 优化视频库、频道管理、账号和播放器界面。
- 支持跟随系统、浅色和深色外观。
- 修正页面切换和主题变化时表单草稿丢失，以及 API Hash 未正确遮蔽的问题。
- 授权失效后可以重新登录并保留本机媒体索引。

## 下载与安装

- APK：`tgproxy-android-arm64.apk`
- ABI：仅 `arm64-v8a`
- 最低 Android：8.0（API 26）
- application ID：`dev.tgproxy.mobile`

升级时直接覆盖安装，不要先卸载，以保留本机授权、频道和播放记录。正式包首次使用需要登录 Telegram。手机号和验证码用于用户授权；API ID/Hash 可选，公开分发前建议填写自己在 Telegram 官方申请的凭证。

## 验证

- APK 使用现有 Release 签名，并通过 v2/v3 签名校验。
- POCO F2 Pro（ARM64）设备回归 12 项通过，1 项登录草稿用例因已有授权跳过；覆盖导航、固定搜索/筛选、主题切换、Range 快进、后台播放、数据库和 Keystore。
- 完整 Debug、Release、Android 测试包构建及 Android Lint 通过。
- 完整 SHA-256 列在 GitHub 发行说明及 `tgproxy-android-arm64.apk.sha256` 中。

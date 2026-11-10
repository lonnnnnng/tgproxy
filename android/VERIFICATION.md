# Android 0.2.0 验证记录

日期：2026-11-10。设备：POCO F2 Pro，ARM64。验收代码位于 `codex/android-poc` 分支。

## 功能与安装

- API ID/Hash 成对可选；留空沿用预设值，自有凭证加密保存在本机。
- 多频道管理、Telegram 频道选择、名称搜索、频道过滤和最近播放。
- 历史扫描与新增同步使用独立游标，每页视频和游标在同一 SQLite 事务提交。
- 前台索引服务支持暂停/续扫；MediaSessionService 支持后台播放、通知控制和进度保存。
- `TGProxy 验证`（`dev.tgproxy.mobile.poc`）原地升级，旧授权保留，当前 1 个频道、391 条视频，交付时已恢复 Telegram 连接。
- 正式签名 `TGProxy`（`dev.tgproxy.mobile`）已独立安装并冷启动成功，登录页浅色/深色均检查过。独立包名需要首次重新授权，未自动迁移 Keystore 会话。

## 自动化和真实链路

Go mobile 模块和 Telegram 模块的 race 测试与 vet 通过；Debug/Release 编译通过。Lint 0 错误、9 条警告，包含目标 SDK/依赖更新提示、仅 ARM64 支持提示及代码风格提示。UI 检测器结果为空。

最终串行运行 9 项设备测试通过：本地 Range 鉴权与字节一致性、快进和后台持续播放、停止后再次打开媒体、Keystore 与 SQLite 持久化、schema 升级、跨频道事务隔离和搜索、任务启动前暂停、页面导航、真实 Telegram 播放。

真实 Telegram 测试读取 58 个频道/超级群组及一页 100 条消息；视频检测到 2 组音轨且已选中一组，向前跳至 60 秒、向后跳至 3 秒后均恢复播放并继续前进。此前真实索引服务的暂停/续扫测试已将索引从 200 条增至 391 条；最后回归未再次追加历史数据。

复测曾出现播放器停止/重开及真实播放等待失败。已调整普通停止与账号退出的会话释放策略，并加入停止后再次播放的用例；修正测试诊断在主线程使用 ActivityScenario 状态查询的问题后，最终串行测试通过。完整结果保存在忽略目录 `app/build/validation/final-tests.txt`；此前失败记录也留在同目录。

## 制品

- APK：`app/build/outputs/apk/release/tgproxy-android-arm64.apk`
- 版本：0.2.0，versionCode 2，minSdk 26，targetSdk 36，仅 arm64-v8a。
- APK SHA-256：`c13d52fd428b6f875d7007590ee5a52af948cd31560542defed5f862752de9d5`
- 本地 APK 和手机已安装 Release 的 SHA-256 一致，APK v2/v3 签名验证通过。
- 证书 SHA-256：`ebbd4841f95c5fe0e7aad410a9f685b8c549ce958fcc18bb39efbe2533eb3206`
- 签名材料位于 `.signing/`，文件权限 0600，已由 Git 忽略。后续升级需复用并安全备份该目录。

正式包只做了安装、冷启动及登录页面检查；真实 Telegram 授权与播放回归在使用相同业务代码的 Debug 包执行。未验证所有视频编码、所有设备或长时间后台扫描。未创建 GitHub Release。

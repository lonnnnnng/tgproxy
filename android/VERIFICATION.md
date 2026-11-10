# Android 验证记录

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


## UI/UX 重设计复测（2026-11-10 下午）

本节对应工作区新版界面，覆盖前面的 APK 校验值。POCO F2 Pro（31936bb6）已保留数据升级 Debug 和正式签名包，原 Debug 授权及 1 个频道、391 条视频保留。

- Debug/Release 编译和 Lint 通过，Lint 0 错误、13 条警告；UI 机械检测结果 `[]`。
- 最终 instrumentation runner 报 `OK (12 tests)`：11 项实际通过，登录草稿用例因 Debug 已授权而按条件跳过。已执行导航、频道草稿、搜索空态、分页与滚动恢复、主题切换、Range、seek、后台及重新打开播放器、SQLite/Keystore 回归。
- 正式包未授权状态完成登录页交互检查：使用未提交的虚构字段，不发送验证码；展开/收起、切换页面及浅深主题后草稿保留。截图确认 API Hash 显示圆点，系统节点 password=true。恢复两包原有“跟随系统”主题，并结束正式包测试草稿所在进程。
- 已检查真实媒体库、频道和账号的浅深色界面；未重新执行 Telegram 登录、授权失效恢复或全量扫描。本轮真实 Telegram 仅观察原账号重连，Range/seek 自动化使用现有本地测试片。
- 本轮曾发现 Android 8.0 不支持导航栏主题属性，已改用兼容 API；发现单行变换覆盖密码遮蔽，已修正控件设置顺序。主题自动化的活动窗口/子节点点击问题已修正，最终整套回归通过。

日志及截图保存在忽略目录 `app/build/validation/`：`redesign-verified-build.txt`、`redesign-verified-tests.txt`、`redesign/login-check-complete.txt` 和 `redesign/` 截图。

最终正式 APK SHA-256：`2853a4012bad60b1af7baa28566a0e675ce7e8c2c5dbcd6b2bed1caf965abdda`。APK v2/v3 签名验证通过，沿用原本地签名。版本仍为 0.2.0 / 2，本轮未发布 GitHub Release。

## 视频工具栏固定位置复测（2026-11-10）

视频摘要、搜索、频道筛选与播放模式现位于结果 `ScrollView` 外。POCO F2 Pro 上执行 `NavigationTest` 得 `OK (6 tests)`，登录草稿测试按当前已登录状态跳过；滑动前后 UIAutomator 读到搜索框位置均为 `[49,360][1031,503]`，最近播放筛选位置均为 `[539,649][1031,757]`。搜索条件更改后回到结果顶部也加入断言。

Debug/Release、Android 测试包及 Lint 通过。新版 Release 沿用原签名并覆盖安装到 POCO，授权及本地媒体数据保留。APK SHA-256：`101318da591657f4cf511016c4c3eba3a6bf66b628ffa61e414e222c651cc747`，版本仍为 0.2.0 / 2；未发布 GitHub Release。验证记录和滑动前后截图在忽略目录 `app/build/validation/pinned-search-*`。

## Android 0.2.1 发布验证（2026-11-10）

版本为 `0.2.1` / `versionCode 3`，发布标签 `android-v0.2.1`，沿用原 Release 包名及签名。以下结果覆盖上文 0.2.0 的开发制品记录；最终下载文件的 SHA-256 以本版本发行说明及校验文件为准。

通过 `build-release.sh` 重建 ARM64 Go AAR、正式 APK 并验证 v2/v3 签名；Debug、Android 测试包和 Lint 构建通过。POCO F2 Pro 的 `NavigationTest`、`CorePlaybackTest`、`IndexDatabaseTest` 串行回归报 `OK (13 tests)`：12 项实际通过，登录草稿用例因设备已授权按条件跳过。覆盖固定搜索/筛选、搜索与分页、页面恢复、主题切换、Range 与快进、后台播放、数据库和 Keystore。此次没有重新登录、触发授权失效或运行全量频道扫描。

记录位于忽略目录 `app/build/validation/android-v0.2.1-*`。GitHub 发布内容使用 [RELEASE-v0.2.1.md](RELEASE-v0.2.1.md)，附正式 APK 和独立校验文件。

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

正式包已在 POCO 上覆盖升级并启动，设备报告 `0.2.1` / `versionCode 3`。从设备读回的安装包与本地发布 APK SHA-256 均为 `62332a455d2023e68111c79cbb0f9924e491d70bf57b33fe8eac0f06f4a9bccd`，签名证书与 0.2.0 相同。

## Media3 可复用播放器控制（2026-11-10）

GSYVideoPlayer v14.0.0 的源码审查确认标准 Exo manager 会自己创建/释放 ExoPlayer，与 TGProxy 的后台服务所有权边界不匹配。本次采用官方 Media3 Views 和本地 `:player-controls` library，具体依据见 [选型记录](player-controls/DECISION.md)。没有创建独立远端仓库，也没有推送或发布新的 GitHub Release。

组件新增直接倍速入口与记忆、长按临时 2×、左右双击前后跳 10 秒、锁定、五种画面比例、左侧亮度/右侧媒体音量。手势取消、失焦、旋转、解绑和宿主停止均结束临时倍率；亮度进入后台恢复原值。媒体 URL、令牌、Go Range 与 MediaSession 继续由宿主处理。

- Debug、Release、测试 APK、组件 Release AAR 及两模块 Lint 均通过。组件 Lint 0 错误、3 条依赖更新提示；App 0 错误、16 条警告。
- POCO F2 Pro 上最终串行运行 `PlayerControlsTest`、`CorePlaybackTest`、`NavigationTest`、`IndexDatabaseTest`：`OK (16 tests)`，15 项实际通过，登录草稿用例因已授权而按条件跳过。日志：`app/build/validation/player-controls-final-verification-tests.log`。
- 验收覆盖 Range 鉴权与内容、MediaController seek、后台播放/重开、倍率选择/记忆、长按抬手/取消/后台恢复、双击、锁定、亮度/音量、比例和旋转。
- 字体 1.3 的额外比例选择/旋转验收 `OK (1 test)`；截图确认五个比例选项完整显示，横竖屏底栏没有截断，设备字体恢复到 1.0。先前失败来自测试把 ListView 的可触摸行误判为不可点击，现已改用节点位置发送真实点击。失败记录与最终截图均保留在忽略目录 `app/build/validation/`。
- 已保留数据覆盖安装 Debug 包和正式签名包。正式包使用本地体验制品 `app/build/outputs/apk/release/tgproxy-android-player-controls.apk`，设备读回 SHA-256 与本地一致：`5c61764ff24e4a16a241fabeebd13cc0c66ea999c801f227d3e92f3144a46956`。v2/v3 签名验证通过，沿用原证书。
- 本地体验包仍为 `0.2.1` / `versionCode 3`，不等同于已发布 GitHub 0.2.1 的 APK。正式包本轮仅覆盖安装与读回校验，交互测试使用同源码的 Debug 包。
- 额外的真实 Telegram 只读在线测试已启动但主动中断，未取得完成结果，不计入本轮通过数。没有全量扫描或重新授权；Go、Telegram API、Range 和服务鉴权实现未修改。TalkBack 完整朗读、其他设备和全部视频编码仍未验收。

组件 AAR 位于 `player-controls/build/outputs/aar/player-controls-release.aar`，SHA-256 为 `0d011a3ca1e919703e0cfa94c2dc00f54461dc86d60d77d0c957da99bdf92ecf`。接入及宿主生命周期见 [player-controls/README.md](player-controls/README.md)。

## Android 0.3.0 发布（2026-11-10）

`android-v0.3.0` 为首个包含可复用 Media3 播放器控制组件的正式版，版本号 `0.3.0` / `versionCode 4`。Release 保留 `dev.tgproxy.mobile` 包名及原签名，支持覆盖升级。

- `build-release.sh` 重建 Go ARM64 AAR、Release APK 并通过 v2/v3 签名校验；Debug、Android 测试 APK、组件 Release AAR 和两模块 Lint 通过。组件 Lint 0 错误、3 条依赖更新提示；App Lint 0 错误、16 条警告。
- 最终设备回归：15 项通过、登录草稿用例 1 项按已授权状态跳过；另有大字体和 4:3/16:9 切换专项测试通过。未完成的真实 Telegram 在线用例不计入通过数。
- APK SHA-256：`12f2fd2a7d827ba0ac57e579ac9d2b25f107cf603ff260796f2d0099a76f0fe6`；AAR SHA-256：`0d011a3ca1e919703e0cfa94c2dc00f54461dc86d60d77d0c957da99bdf92ecf`。
- APK 已覆盖安装到 POCO F2 Pro，账号和应用数据保留；设备报告 `0.3.0` / code `4`，安装包读回 SHA-256 与发布 APK 完全一致。
- Release 附件包含 App APK、组件 AAR 及各自 `.sha256` 文件。组件可独立构建，但当前通过 AAR 分发，尚无独立 Maven 坐标。
- 未修改服务端、Go Range 或 Telegram 登录/索引逻辑；真实频道扫描与授权未触发。

GitHub 发布说明见 [RELEASE-v0.3.0.md](RELEASE-v0.3.0.md)，完整设备日志与截图见忽略目录 `app/build/validation/`。


## 紧凑纯列表改版与发布时间（2026-11-10）

本节为 0.3.0 基础上的本机体验包，版本号仍为 `0.3.0` / `4`，不等同于已发布 `android-v0.3.0` 的文件。仅修改 Android 界面、展示查询及本地播放控制；未重建或修改 Go 核心，未提交、推送或发布。

界面取消相册和封面占位；列表显示现有 Telegram 消息发布日期，详情显示完整时间并按设备时区转换。缺失日期不显示。三个 Tab 无页面大标题，搜索与筛选固定；频道和设置为紧凑行，新增／排序使用面板。播放器收拢更多菜单，保留倍速、手势、锁定和旋转。迷你播放器支持暂停、继续与结束后重播。

- POCO F2 Pro（31936bb6）保留数据覆盖安装 Debug 和 Release；只操作此手机，没有操作另一台 Redmi。
- 单元测试通过：Unix 秒、设备时区、无效日期；Debug、Release、测试 APK、组件 Release AAR 与两模块 Lint 构建通过。App Lint 0 错误、19 条警告；组件 0 错误、3 条依赖更新提示。
- 全套 runner `OK (20 tests)`：19 项通过，登录草稿因 Debug 已授权而跳过 1 项。覆盖详情导航与日期、无封面列表、搜索／分页／固定筛选／草稿／主题，数据库日期排序与续播过滤，Range 字节与鉴权、后台和重新打开、结束后重播，倍速、双击、锁定、亮度音量、比例／旋转及更多菜单。
- 字体 1.3 的专项 runner `OK (7 tests)`，7 项实际通过。最终搜索输入布局修正后再执行 NavigationTest，`OK (7 tests)`，6 项通过、登录草稿 1 项跳过；添加了文字可用高度断言并复核截图。设备字体恢复到原先 1.0，主题偏好也在用例结束恢复。
- 浅深色列表、频道、我的、详情及横竖屏播放器截图已检查。Debug 真实媒体库仍为 1 个频道、391 条视频；Release 仍处于未登录空库状态，两包数据独立，未迁移账号。无重新登录或真实扫描。播放器自动化使用本地测试片，未进行新的 Telegram 取流、全部编码或长时间后台验证。
- 验证中修复了搜索框初始化空指针、大字体提示裁切、底部面板关闭后回调丢失、Media3 按钮图标重叠及结束后的迷你播放器重播。音轨测试最初误用“音轨”查找官方“音频”菜单，已按当前依赖资源修正。先前失败日志保留。
- 签名 APK：`app/build/outputs/apk/release/tgproxy-android-compact-ui.apk`，SHA-256 `a012886f36649827d39215111743d8af97183ed802c696494b90aa0e3ffcaec6`。v2/v3 签名验证通过；沿用证书 `ebbd4841f95c5fe0e7aad410a9f685b8c549ce958fcc18bb39efbe2533eb3206`。已在 POCO 覆盖安装并启动，设备安装包读回 SHA-256 与本地完全一致。

证据保存在忽略目录 `app/build/validation/compact-ui/`：`tests-final-2.log`、`large-font-tests.log`、`search-fit-tests.log`、`build-search-fit.log`、`signature.txt`、`screens/`、`search-fit/` 和 `release-installed.png`。HTML 原型同步为纯列表；浏览器核验了列表、详情、设置入口，控制台无错误。原型中的画面／日期是示例数据。


## Android 0.4.0 发布制品（2026-11-10）

本节对应标签 `android-v0.4.0`，版本 `0.4.0` / `versionCode 5`。发布内容包含紧凑纯列表、发布日期与详情页、频道／我的页面改版，以及播放器面板和迷你播放器修复。用户将在正式发布后自行进行真机验收。

- 使用 `build-release.sh` 重建 Go ARM64 AAR、Release APK 并签名；`testDebugUnitTest`、App／组件 Lint 和组件 Release AAR 构建通过。Lint 0 错误；App 19 条警告，组件 3 条依赖更新提示。
- 发布前的设备验证沿用上节记录：19 项通过、1 项登录条件跳过；大字体专项通过，最终搜索修正后的导航复测 6 项通过、1 项跳过。本次版本号更新后未重复设备测试或覆盖手机。
- `aapt` 核实包名 `dev.tgproxy.mobile`、版本 `0.4.0`／`5`、minSdk 26、targetSdk 36、仅 `arm64-v8a`。
- `apksigner` 验证 v2/v3 签名通过，证书 SHA-256 仍为 `ebbd4841f95c5fe0e7aad410a9f685b8c549ce958fcc18bb39efbe2533eb3206`。
- 正式 APK SHA-256：`4f221168c37da4aeab4731f902cbd4ba38bd5ffe12221d8301d58fde611bbece`。
- 播放器 AAR SHA-256：`3db36604a4086eb8ea0cf083cb71e48e11175fced3da230c63231ee129c8a86c`。

发布说明见 [RELEASE-v0.4.0.md](RELEASE-v0.4.0.md)，构建日志位于忽略目录 `app/build/validation/android-v0.4.0-build.log`。Release 附 APK、组件 AAR 及各自校验文件。

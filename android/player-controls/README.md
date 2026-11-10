# Media3 播放控制组件

`player-controls` 是可复用的 Android Views library，接收 Media3 `Player`，支持 ExoPlayer 和 MediaController。组件不依赖 TGProxy、Telegram、数据库、媒体 URL 或账号凭证。当前随本仓库维护，[Android 0.3.0 发行附件](https://github.com/lonnnnnng/tgproxy/releases/tag/android-v0.3.0) 提供 AAR，尚未发布独立 Maven/JitPack 坐标。

## 能力

| 操作 | 行为 |
| --- | --- |
| 倍速 | 底栏直接打开官方 Media3 倍速菜单；记住上次选择 |
| 长按画面 | 播放中临时 2×；松手、取消、失焦、锁定或进入后台时恢复 |
| 双击画面 | 左侧后退 10 秒、右侧前进 10 秒；不越过媒体边界 |
| 左侧上下滑动 | 调整当前窗口亮度；进入后台恢复原值，包括自动亮度的 -1 |
| 右侧上下滑动 | 调整设备媒体音量；受系统固定音量、安全音量等策略限制 |
| 锁定 | 隐藏控制器并禁用播放手势；点按画面显示解锁入口 |
| 画面比例 | 适应、填满裁切、拉伸铺满、16:9、4:3；记住选择 |
| 原有控制 | Media3 负责播放/暂停、进度条、缓冲、字幕和音轨选择 |

倍速入口随控制栏出现，不需要进入设置二级菜单。播放时 4 秒无操作自动隐藏控制栏，单击画面显示/隐藏。手势区在官方控制器下层，按钮、时间轴和菜单不会被画面手势截获。Touch Exploration 开启时不抢占触摸手势，解锁入口保持可见；完整 TalkBack 朗读流程尚未人工验收。

## 接入

在宿主 Android 工程引入 library module：

```kotlin
// settings.gradle.kts
include(":player-controls")

// app/build.gradle.kts
implementation(project(":player-controls"))
```

依赖 Media3 1.11.1、AppCompat 1.7.1、core-ktx 1.17.0、Material 1.12.0。当前 minSdk 26、compileSdk 36、JDK 17。宿主使用 AppCompat/Material 主题，Player 和控件调用均在主线程执行，其他 Media3 组件保持同一版本。

```kotlin
val controls = VideoControlsView(this).apply {
    attachWindow(window)
    title = "视频标题"
    onBack = { finish() }
    onRotate = { toggleOrientation() }
    onInfo = { showPlaybackInfo() }
}
setContentView(controls)

// long: 接入宿主持有的实例；组件不创建、停止或释放后台内核。
controls.player = mediaController

// Activity.onStop 中先结束临时手势和窗口效果，再解绑控制器。
controls.onHostStop()
controls.player = null
```

示例的 `toggleOrientation()`、`showPlaybackInfo()` 和 `mediaController` 由宿主提供。宿主负责媒体准备、音频焦点、后台服务、窗口安全区及 Player/MediaController 的创建与释放。系统返回键先检查 `controls.isLocked`，锁定时调用 `setLocked(false)`，其余情况执行正常返回。

`title` 由宿主更新。TGProxy 监听 `onMediaMetadataChanged`，防止切换媒体时保留旧标题。服务在 UI 绑定前开始播放时，可调用 `setPlaybackSpeed(PlaybackPreferences(context).speed)` 恢复倍率。长按 2× 不覆盖持久化偏好。

## 构建 AAR

在 Android 工程根目录运行：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :player-controls:assembleRelease
```

输出 `player-controls/build/outputs/aar/player-controls-release.aar`。AAR 不内嵌 Maven 依赖；手动复制到其他项目时须同时声明上文依赖。优先 module 引用，后续独立仓库发布时通过 POM 传递依赖。

## 验证与边界

`PlayerControlsTest` 在 POCO F2 Pro 上通过真实触摸事件验证倍速菜单、长按松手/取消、后台倍率恢复、双击、锁定、亮度/音量、比例和旋转。`CorePlaybackTest` 覆盖 MediaController、Range 与后台服务。详情见 [验收记录](../VERIFICATION.md) 和 [选型记录](DECISION.md)。

当前面向手机触屏，未声明 Android TV 遥控器、投屏、PiP 或所有 OEM 音量策略兼容。Media3 UI 部分 API 属于 UnstableApi，升级时需统一版本、核对自定义布局 ID 并重跑设备测试。

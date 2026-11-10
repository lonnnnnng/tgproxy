# 播放控制层选型

日期：2026-11-10。采用官方 Media3 Views 控制器和薄封装，独立为本地 `:player-controls` library。此次不引入 GSYVideoPlayer，也不新建远端仓库。

## GSY 源码审查

核对 GSYVideoPlayer v14.0.0，提交 `4a7abf9caecbf7def7e2279e7fe2e246ef8e583a`。它提供亮度/音量、比例、锁定和自定义播放器 manager，但标准 Exo 接入会创建自己的 ExoPlayer：

- [IPlayerManager](https://github.com/CarGuo/GSYVideoPlayer/blob/v14.0.0/gsyVideoPlayer-base/src/main/java/com/shuyu/gsyvideoplayer/player/IPlayerManager.java) 要求返回 IJK 风格 IMediaPlayer，并实现初始化、Surface、播放、seek 和释放。
- [GSYVideoBaseManager.initVideo](https://github.com/CarGuo/GSYVideoPlayer/blob/v14.0.0/gsyVideoPlayer-java/src/main/java/com/shuyu/gsyvideoplayer/GSYVideoBaseManager.java) 建立 manager、设置回调并调用 prepareAsync，还管理准备代次、错误恢复与释放。
- [IjkExo2MediaPlayer.prepareAsyncInternal](https://github.com/CarGuo/GSYVideoPlayer/blob/v14.0.0/gsyVideoPlayer-exo_player2/src/main/java/tv/danmaku/ijk/media/exo2/IjkExo2MediaPlayer.java) 创建新 ExoPlayer，无法直接作为接受外部 Media3 Player 的控制 UI 使用。

TGProxy 已由 PlaybackService 持有 ExoPlayer 和鉴权 data source，Activity 只连接 MediaController。标准 GSY 路径会改变内核所有权；另写 manager/IMediaPlayer 桥接需转换线程、状态、Surface 和释放语义，维护范围超过本次控制层补充。因此采用已约定的 Media3 备选路径。

这些是源码兼容性结论，未声称 GSY 存在播放缺陷，也未进行 GSY 真机接入性能比较。

## 复用内容

PlayerView、PlayerControlView、DefaultTimeBar、播放按钮、倍速菜单、字幕和音轨选择来自 Media3 1.11.1 Maven 制品。自定义 XML 使用 exo_playback_speed 直接打开现成倍速菜单。新增代码仅提供手势、偏好保存、锁定、比例选项和宿主回调。

没有复制 GSY 内核或 Media3 播放状态机。Go Range、媒体解析、令牌、UID 校验和后台服务归宿主负责。后续拆独立仓库可以直接迁移此 module；发布前再补坐标、许可证和独立示例工程。

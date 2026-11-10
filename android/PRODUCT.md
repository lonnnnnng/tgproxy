# TGProxy Android

TGProxy Android 在 ARM64 手机上使用 Telegram 用户账号索引所选频道的视频，并通过本机 Range 服务和 Media3 播放。手机直接访问 Telegram，不依赖 Linux TGProxy 服务、Jellyfin 或媒体树。

用户使用手机号、验证码和可选两步验证密码授权。API ID/Hash 可选：两项留空时使用项目预设值，填写时必须成对提供；凭证和登录会话通过 Android Keystore 加密后保存，并排除系统备份。视频索引及游标存入应用私有 SQLite。内置 Go 核心只监听回环地址，媒体访问需随机令牌。

可从 Telegram 已加入频道中选择，或通过公开链接/ID 添加多个频道。扫描按原始 Telegram 消息 ID 分页，在每页事务中同时保存视频和游标；用户可恢复旧消息扫描或独立同步新增内容。一个频道的失败或取消不会覆盖其他频道或已提交页面。

视频库支持跨频道搜索、频道筛选、分页和最近播放。MediaSessionService 在播放器页面退出后维持当前播放，系统通知提供媒体控制，进度定期保存；另提供明确标注的本地测试片。

当前工程仍处于 Android 0.2.0 开发阶段。Debug 包沿用 `.poc` application ID 以原地升级并保留验证数据；Release 使用稳定 application ID `dev.tgproxy.mobile`，首次安装会形成独立应用，需要重新登录。本地签名通过 `build-release.sh` 生成并保存于忽略目录；公开分发前应在 Telegram 官方 `my.telegram.org` 申请自己的 API ID/Hash；项目预设值只适合私下验证，不作为公开发行凭证。

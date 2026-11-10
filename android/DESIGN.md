# TGProxy Android 界面

模式为 Operate，沿用现有 TGProxy 的蓝灰品牌色，采用 Material 3 原生控件。主界面包含视频、频道、账号三个目的地，视频为首页。优先保持操作顺序和状态清楚，不展示虚构内容或伪造海报。

浅色 surface `#F6F7FA`、onSurface `#222A3B`、primary `#3859D6`；深色 surface `#131722`、onSurface `#E9EDF7`、primary `#A5B7FF`。Android DayNight 主题负责随系统切换。正文使用 Material BodyMedium，标题 HeadlineMedium，交互使用 MaterialButton/TextInputLayout/BottomNavigationView。点击区域至少 48dp，屏幕边距24dp。

登录仅展示当前阶段字段，手机号验证码和两步验证密码不写日志；可选 API ID/Hash 两项都留空时使用预设值。顶部显示 Telegram 连接和真实索引进度，扫描中始终保留暂停入口。频道页支持从 Telegram 对话列表添加、查看每频道索引状态、继续历史扫描、同步新内容和移除本地索引。视频页支持按频道筛选、名称搜索、最近播放和分页。播放器使用 Media3 原生控件、音轨诊断、系统媒体会话、音频焦点和后台播放。

验收包含 ARM64 真机本机 Range、后台持续播放、seek、Keystore、旧数据库迁移、频道事务隔离和真实 Telegram 频道/视频链路；合成测试片不代替真实媒体验证。

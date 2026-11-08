---
version: 1
slug: "tgproxy-internal-web-static-index-html"
primary_target: "tgproxy/internal/web/static/index.html"
related_targets: ["tgproxy/internal/web/static/app.js","tgproxy/internal/web/static/style.css","tgproxy/internal/web/server.go"]
---

Scope: tgproxy 的管理控制台，Operate 模式。维护者在局域网浏览器中配置 Telegram 登录、管理频道、运行扫描并核对媒体树和 Jellyfin 更新请求。

内容与约束：保留凭证配置、短信验证码与两步验证登录、频道增删、扫描、手动重建和错误日志。扫描任务要显示频道顺序、已完成频道数、当前频道和已发现视频数；不编造频道总视频数或剩余时长。Jellyfin 后端只提供刷新请求结果，不提供库扫描进度或入库数量，界面必须据实表达。支持浅色和深色主题，首屏优先展示扫描与媒体更新状态。

## Direction contract

THESIS: 用一张频道节目表表达扫描如何逐频道推进，扫描、生成媒体树、通知 Jellyfin 是相连但各自有真实状态的阶段。

OWN-WORLD: 浅色以纸白、松石绿、石墨和信号橙构成；深色以深石墨和深松绿承载同样的语义色。标准表格、表单和按钮保持熟悉；节目表的列节奏与扫描中的频道强调是唯一签名表达。

STORY: 维护者先看 Telegram 是否连接、扫描推进到哪一频道，再分别确认媒体树是否生成以及 Jellyfin 是否收到刷新请求。出错时可读日志并使用扫描、重建或手动刷新恢复。

FIRST VIEWPORT: 顶栏放产品标识、服务连接状态和主题切换；标题行提供“扫描并更新”与“重建媒体树”。主区以三阶段状态带和频道节目表为主体，显示已处理频道数、当前频道已发现视频数及每频道索引数；Jellyfin 阶段明确显示已通知、通知失败、未配置或尚未执行。下方放频道维护、认证表单与可展开日志。

FORM: 采用选项页首位的“频道节目表”形式，以同步任务排序而非虚构时间表；它是本轮方向列表第一项，seed:fce39ffaa00bf151。拒绝暗色开发终端、抽象条纹和固定时长暗示。产品行为保持可操作台账的熟悉结构。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

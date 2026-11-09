---
version: 1
slug: "tgproxy-internal-web-static-index-html"
primary_target: "tgproxy/internal/web/static/index.html"
related_targets: ["tgproxy/internal/web/static/app.js","tgproxy/internal/web/static/style.css","tgproxy/internal/web/server.go"]
---

Scope: 控制台整体重设计，Operate，code-led。用户明确授权「你来决定，直接做一版」，并授权自主设计功能。保持双主题、登录、频道维护、扫描、重建、日志及真实 Jellyfin 通知状态。

## Direction contract

THESIS: 将长页台账重构为任务工作台，概览负责判断状态，频道、日志、连接各有专属工作区。

OWN-WORLD: 冷灰白、深墨灰、清晰钴蓝动作色；14px 系统无衬线正文，柔和边界、12px 面板、8px 控件；频道首字标识使长标题易定位。

STORY: 首先确认连接与同步阶段，再扫描或定位异常；筛选频道、管理来源、筛选并导出本进程日志均使用标准控件。

FIRST VIEWPORT: 216px 固定侧栏，顶部路径与连接状态；30px 标题和主次动作；紧凑索引汇总；宽同步阶段面板与窄 Jellyfin 结果面板；下方最多五个频道摘要，完整频道列表在独立页面。签名交互为三段同步路径随实际状态依次变化，关闭非必要动画。

FORM: 候选来源为数字影像档案目录、影视字幕排版、胶片索引盒、展览导览、现代媒体馆目录、唱片内页、交通站牌。采用第 5 项现代媒体馆目录的对齐、冷灰蓝和清晰索引；seed:ebbaf74ce7f0fb52。用户委托直接执行，不另开选择页。挑战者的暗房叠层、色码、游园图形、可见网格、双栏书页、吉祥物均降低管理任务清晰度；只吸收分区明确、数量对齐与可读状态的纪律。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

# tgproxy

将多个 Telegram 频道的视频汇总成 Jellyfin 可读取的媒体树，播放时按 HTTP Range 拉取所需分块，支持快进、拖动和缓存复用。

## 当前部署

- 控制台：<http://192.168.1.2:8080/>
- Jellyfin：<http://192.168.1.2:8097/>
- tgproxy：服务器用户 `lee` 的 systemd user 服务。
- 媒体目录：`/home/lee/tgproxy/media`，只读挂载到 Jellyfin 的 `/media/tgproxy`。
- Jellyfin 已关闭音视频转码，已启用该媒体库实时监控，并接通重建后的扫描通知。

2026-11-08 已修复空库、视频尾块截断、历史扫描提前停止、同名资源覆盖、缓存取消与错误恢复等问题，详见 [排障与验收记录](docs/AUDIT-20261108.md)。

## 使用

1. 控制台登录 Telegram，添加频道。
2. 点击 **扫描并更新媒体库**。扫描完成后自动生成媒体树并通知 Jellyfin。
3. 等待 Jellyfin 扫描，资源会陆续出现。首次处理大量历史视频需要时间。
4. 删除频道后，可点击 **重建媒体树** 同步移除其旧入口；旧的项目文件会移至备份目录。

扫描、重建和频道修改互斥，错误会保留在日志中。控制台显示的视频数来自索引，不等于 Jellyfin 已完成元数据处理的条目数。

## 拉流设计

```text
Telegram 频道 → 视频索引 → 剧/Season xx/*.strm → Jellyfin
      ↑                        │                 │
      └── 按需分块 ← /stream/<channel>_<message> ←─┘
```

- 单区间、后缀 Range、HEAD、206/416；不支持 multipart Range。
- 默认 512 KiB 分块、512 MiB LRU；同块下载合并，重复 seek 复用缓存。
- 需求下载最多并行 8 个、预取最多 2 个，预取不挤占需求名额。
- 下载和句柄解析有超时；最后一个等待者取消后中止共享任务。
- Telegram 请求对齐并限制在 1 MiB 窗口内；支持有限次数的文件 DC 迁移和过期引用刷新。
- 相同源句柄短期复用，文件身份变化不会混入旧缓存。
- CDN redirect 尚未实现，返回明确错误；文件大小超过 2 GiB 本身不是拒绝条件。

## 开发

Go module 位于 `tgproxy/`，声明 Go 1.25.8。gotd/td 与 tdl core 均固定引用 GitHub fork commit；本地构建不再依赖 `dev/tdl-src/core`。

```sh
cd tgproxy
go test -race ./...
go vet ./...
go build ./...
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o /tmp/tgproxy-linux ./cmd/tgproxy
```

## 文档

- [使用指南](docs/USAGE.md)
- [部署与配置](docs/DEPLOY.md)
- [代码结构](docs/CODE.md)
- [进度与历史](docs/PROGRESS.md)
- [本次审计与验证](docs/AUDIT-20261108.md)

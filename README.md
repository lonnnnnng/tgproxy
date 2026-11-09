# tgproxy

tgproxy 是一个自托管的 Telegram 视频频道代理。它扫描频道中的视频消息，生成 Jellyfin 可识别的 `.strm` 媒体树；播放时由自研 HTTP Range 拉流层按需从 Telegram 读取分块。

tgproxy 负责索引、媒体树和稳定的 Range 读取；视频浏览、元数据展示和播放由 Jellyfin 完成。

## 当前版本

- 最新发行版：[v0.1.1](https://github.com/lonnnnnng/tgproxy/releases/tag/v0.1.1)
- 线上控制台：[http://192.168.1.2:8080/](http://192.168.1.2:8080/)
- 线上 Jellyfin：[http://192.168.1.2:8097/](http://192.168.1.2:8097/)
- 当前部署方式：Linux/amd64 静态二进制 + `systemd --user`

## 工作流

```text
Telegram 频道
      │
      ▼
扫描历史消息 ──► 视频索引 ──► 媒体树同步 ──► Jellyfin 扫描
                                      │
                                      ▼
                         /stream/<channel>_<message>
                                      │
                                      ▼
                           Telegram 按需分块读取
```

控制台中的三个状态阶段彼此独立：

1. **Telegram 扫描**：读取频道历史并更新本地索引。
2. **媒体树**：按剧名、季、集生成或同步 `.strm` 文件。
3. **Jellyfin**：向已配置的 Jellyfin 提交刷新请求。请求成功只代表 Jellyfin 已收到任务，不代表库内元数据已经处理完成。

## 主要能力

- Telegram 手机号、验证码和两步验证登录。
- 添加、删除和搜索频道，支持 `@name`、`https://t.me/...` 和数字 ID。
- 历史消息分页扫描，支持扫描进度、失败保留旧索引和日志查看。
- 自动生成媒体树，清理由项目清单管理的旧入口，并保留备份。
- HTTP `HEAD`、单区间 Range、后缀 Range、`206` 和 `416`。
- LRU 分块缓存、并发请求合并、有限顺序预取和取消恢复。
- Telegram 文件 DC 迁移与过期文件引用刷新。
- 浅色/深色控制台主题，以及桌面和移动端布局。
- Jellyfin 刷新通知和明确的未配置、成功、失败状态。

## 快速使用

生产环境中，直接打开控制台：

1. 登录 Telegram。
2. 添加频道。
3. 点击 **扫描并更新**。
4. 等待频道扫描、媒体树生成和 Jellyfin 通知完成。
5. 打开 Jellyfin，等待媒体库扫描结果出现。

新增内容时再次扫描即可。扫描、重建和频道修改互斥；单个频道失败时会保留该频道的旧索引，并把错误保留在控制台日志中。

更完整的操作说明见 [docs/USAGE.md](docs/USAGE.md)。

## 配置

Go 程序默认使用 `~/.tgproxy` 作为数据目录，也可以通过参数指定：

```text
-data       数据目录，保存配置、Telegram session、索引和访问缓存
-listen     HTTP 监听地址，默认 :8080
-media      媒体树根目录，默认 <data>/media
-strm-base  写入 .strm 的流地址前缀，默认根据局域网地址推导
```

`config.json` 示例：

```json
{
  "app_id": 2040,
  "app_hash": "b18441a1ff607e10a989891a5462e627",
  "listen": ":8080",
  "media_root": "/home/lee/tgproxy/media",
  "strm_base": "http://192.168.1.2:8080",
  "chunk_size": 524288,
  "cache_max_bytes": 536870912,
  "prefetch_chunks": 2,
  "jellyfin_url": "http://127.0.0.1:8097",
  "jellyfin_api_key": "<Jellyfin 专用 API Key>"
}
```

Telegram 默认使用内置的公开应用标识，通常只需要在控制台登录账号。自备 `api_id` / `api_hash` 时可以在控制台覆盖。`jellyfin_url` 和 `jellyfin_api_key` 必须同时配置；session、API key 和频道访问缓存不能提交到 Git。

## 拉流设计

默认分块大小为 512 KiB，默认缓存上限为 512 MiB。Telegram 请求会被限制在合法的 1 MiB 文件窗口内，HTTP Range 再从分块结果中裁剪返回。

同一分块的并发读取会合并为一次源请求。默认预取窗口为后续 8 块，全局最多同时预取 7 块；Telegram 每个 DC 的下载连接池最多 8 条连接，需求读取使用独立的并发名额。首块等待期间即可预取后续块，随机拖动时首包不必等待相邻块完成。

预取窗口跟随每次播放请求移动。请求结束或拖动取消后，仅取消已经没有其他播放请求需要的下载；有限 Range 不会预取超出请求范围的后续块。小缓存会自动缩小预取窗口。文件大小超过 2 GiB 不会被单独拒绝，但 Telegram CDN redirect 当前仍未实现。

流地址格式为：

```text
http://<tgproxy-host>:8080/stream/<channel_id>_<message_id>
```

## 依赖与 fork

tgproxy 是独立运行的 Go 服务，不依赖 tdl 命令行进程。项目使用以下个人 fork 作为 Go module 替换源：

- [lonnnnnng/td](https://github.com/lonnnnnng/td)：`github.com/gotd/td`
- [lonnnnnng/tdl](https://github.com/lonnnnnng/tdl)：`github.com/iyear/tdl/core`

两者保留上游 module path，`tgproxy/go.mod` 固定到具体 fork commit，而不是跟随可变的默认分支。这样可以修复必要问题，同时继续合并上游更新。

## 本地开发

项目 Go module 位于 `tgproxy/`，要求 Go 1.25.8 或兼容版本：

```sh
cd tgproxy
go test -race ./...
go vet ./...
go build ./...
```

构建 Linux/amd64 发布二进制：

```sh
cd tgproxy
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
  go build -trimpath -ldflags='-s -w' \
  -o /tmp/tgproxy-linux-amd64 ./cmd/tgproxy
```

本地启动：

```sh
cd tgproxy
go run ./cmd/tgproxy \
  -data /tmp/tgproxy-data \
  -listen 127.0.0.1:8080 \
  -media /tmp/tgproxy-media \
  -strm-base http://127.0.0.1:8080
```

## 生产部署

线上部署使用用户级 systemd：

```ini
[Service]
ExecStart=%h/tgproxy/tgproxy \
  -data %h/tgproxy/data \
  -listen :8080 \
  -media %h/tgproxy/media \
  -strm-base http://192.168.1.2:8080
Restart=on-failure
RestartSec=5
```

更新流程是：本地交叉编译 → 上传 `tgproxy.new` → 校验 SHA256 → 备份旧二进制 → 重启服务 → 检查 `/api/status`。详细路径、Jellyfin 容器挂载和回滚方式见 [docs/DEPLOY.md](docs/DEPLOY.md)。

## 已知限制

- 不支持 Telegram CDN redirect。
- 不支持 multipart Range。
- Jellyfin 库内资源数和 tgproxy 索引数属于两个阶段，不保证同时一致。
- 外网访问需要同时保证 Jellyfin、客户端和 `.strm` 中的流地址可达。
- 频道文件名不一定包含可靠的剧名和集数，媒体归类规则不能替代人工校正。

## 文档

- [使用指南](docs/USAGE.md)：登录、频道扫描、重建、播放和排障。
- [部署与配置](docs/DEPLOY.md)：服务器目录、systemd、Jellyfin 和回滚。
- [代码结构](docs/CODE.md)：模块职责和关键调用链。
- [项目进度](docs/PROGRESS.md)：修复记录和后续事项。
- [审计记录](docs/AUDIT-20261108.md)：Range、媒体树、Jellyfin 和线上验收证据。
- [设计系统](DESIGN.md)：控制台视觉和交互约定。

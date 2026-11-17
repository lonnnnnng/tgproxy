# 项目进度

> 更新：2026-11-17

## 当前状态

- tgproxy 已完成：Telegram 登录、频道扫描、媒体树生成、Range 拉流、管理控制台
- 媒体服务器：Emby 4.10.2.0（systemd，8096），媒体库 **TG频道** → `/home/lee/tgproxy/media`
- 媒体服务器刷新：重建媒体树后调用 `POST /Library/Refresh`，返回 204 视为已接收
- 已扫描 31,886 个视频，媒体树生成 31,886 个 `.strm`
- 服务：`tgproxy`（systemd user，8080）运行中；Emby 8096 运行中

## 已完成的工程点

| 模块 | 内容 |
|------|------|
| `internal/stream` | LRU chunk 缓存、单区间/后缀 Range、206/416、并发合并、有限预取 |
| `internal/media` | 集数解析、跨频道同名归并、`剧名/Season 01/*.strm` 结构 |
| `internal/telegram` | gotd 客户端、有状态登录（手机号/验证码/2FA）、频道扫描、分块拉流 |
| `internal/web` | 频道管理、扫描、重建媒体树、状态轮询、媒体服务器通知状态 |
| `internal/mediaserver` | 媒体服务器刷新通知（Emby 兼容），拒绝携带密钥重定向 |

## 关键经验

1. **TG 登录必须是有状态会话**：`phone_code_hash` 与 MTProto 连接绑定，跨请求重新发码会导致 `PHONE_CODE_INVALID`
2. **strm 里的流地址必须是媒体服务器可达的地址**：使用宿主机 LAN IP（`192.168.1.2:8080`），不能用 `127.0.0.1`
3. **扫描完成与媒体库入库是两个阶段**：重建成功只代表入口生成，Emby 的元数据扫描在后台继续
4. **尾块 Range 必须按 4096 对齐**：不足一块时发零长度请求会截断 MP4 尾部索引，导致媒体服务器无法探测

## 后续可选

- Telegram CDN 重定向（`upload.getFileCdnRedirect`）尚未支持；超过 2 GiB 本身不是拒绝条件
- 媒体树中无集号文件（电影）按清洗后的名称命名，集号使用 S01E00
- 如需 302/直连优化，属于网盘中间件项目，不在 tgproxy 范围

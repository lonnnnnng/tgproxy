# tgproxy 项目总览

> Telegram 频道媒体 → 统一入口 → Jellyfin 媒体库 的自建代理服务
> 更新日期：2026-11-08

## 项目目标（4 项任务）

1. ✅ **Jellyfin 服务端**：部署在服务器 `192.168.1.2`（用户 lee），端口 **8097**，管理员 `jellyfin / <密码见本地记录，不入库>`
2. ✅ **tgproxy 部署**：多 TG 频道源统一入口 + 自建拉流层（优化 Range 请求，支持进度条拖动）+ 媒体树（同名不同集归类）+ 供 Jellyfin 使用
3. ✅ **关闭服务器转码**：解码由本地客户端/网页完成
4. ⏳ **手机客户端**：等用户登录 TG、扫描频道后配置（地址 `http://192.168.1.2:8097`，账号同上）

## 当前状态（2026-11-08 20:40）

| 项目 | 状态 | 位置 |
|------|------|------|
| 本地开发 | ✅ 完成，测试全过 | `/Users/long/Documents/PiProjects/tgproxy/tgproxy` |
| 服务器 Jellyfin | ✅ 运行中 | 容器 `jellyfin`，端口 8097 |
| 服务器 tgproxy | ✅ 运行中（systemd user 服务） | `~/tgproxy/tgproxy`，端口 8080 |
| Telegram 登录 | ⏳ 等用户网页登录（手机号+验证码一步完成，API 凭证已内置） | http://192.168.1.2:8080 |
| 频道扫描 | ✅ 已入库 2 频道 / 199 条目（登录后重建媒体树即可出片） | 同上 |
| Jellyfin 媒体库 | ✅ 已建 "TG频道"（tvshows → /media/tgproxy） | 8097 |

## 架构

```
TG 频道A ─┐
TG 频道B ─┼→ tgproxy (Go, :8080) ──→ 媒体树 .strm 文件 ──→ Jellyfin (:8097)
TG 频道C ─┘    │  gotd MTProto        剧/Season 01/SxxEyy.strm      │
               │  LRU chunk 缓存                                     │
               │  顺序预取                                           │
               └── /stream/<channel>_<msg>  ← .strm 内容指向这里 ←──┘
                     完整 Range/206 支持（拖进度条）
```

## 文档索引

| 文档 | 内容 |
|------|------|
| [docs/PROGRESS.md](docs/PROGRESS.md) | 详细进度时间线：已完成/待办/下一步 |
| [docs/CODE.md](docs/CODE.md) | 代码结构与各模块说明（2491 行 Go） |
| [docs/DEPLOY.md](docs/DEPLOY.md) | 服务器部署细节：路径、服务、端口、运维命令 |
| [docs/USAGE.md](docs/USAGE.md) | 使用指南：登录、频道管理、Jellyfin 配置、手机客户端 |

## 关键设计决策

- **自研拉流层而非用 tdl 的 --serve**：tdl 的 gotd-contrib partio 无缓存（每次 seek 重发 upload.getFile）、Content-Disposition: attachment、无预取。自研实现 LRU chunk 缓存 + single-flight 去重 + 顺序预取 + 完整 Range（单区间/后缀/416），本地测试 seek 90% 只拉 ≤12 chunks、预取延迟 333ms→84ms
- **媒体树用 .strm 文件**：Jellyfin 原生支持，无需插件；跨频道同名剧归并（normalizeTitle + Season 分组），无集号的（电影）独立成组
- **凭证全走网页**：API 凭证已内置官方桌面端公共标识（tdl 同款，AppID 2040），用户只需手机号+验证码登录；自备凭证可选填覆盖。session 持久化到 `~/tgproxy/data/config.json`，服务器 systemd unit 无需环境变量
- **有状态登录会话**：发码→提交验证码→2FA 全程复用同一条 MTProto 连接（phone_code_hash 与连接绑定，无状态会 PHONE_CODE_INVALID）
- **Jellyfin 12.4 API 适配**：向导流程（FirstUser→User→Configuration→RemoteAccess→Complete）、标准 `Authorization: MediaBrowser` 头、`/Videos/{id}/stream?static=true` 等与 v10 差异详见 docs/DEPLOY.md

## 技术栈

- Go 1.25.8（module `tgproxy`）
- [gotd/td](https://github.com/gotd/td) v0.163.0 — Telegram MTProto 客户端
- [iyear/tdl/core](https://github.com/iyear/tdl) v0.20.4（本地 replace → `../dev/tdl-src/core`）— dcpool DC 连接池
- 服务器：Ubuntu 24.04 x86_64 @ 192.168.1.2（用户 lee，免 sudo 的 docker 组）

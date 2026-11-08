# 项目进度

> 更新：2026-11-08 20:40

## 总体进度

```
[███████████████████████░] ~95%

✅ 1. Jellyfin 服务端部署        — 完成
✅ 2. tgproxy 开发+部署          — 完成（已扫描 2 频道 199 条目，等用户登录后重建树）
✅ 3. 关闭服务器转码             — 完成
⏳ 4. 手机客户端配置             — 待用户完成网页登录后即可（见 USAGE.md）
```

## 已完成

### 开发（本地 macOS）

| 日期 | 工作 | 结果 |
|------|------|------|
| 11-08 | 调研 tdl v0.20.4 拉流缺陷（读源码） | 确认 `--serve` 无缓存/无预取/attachment 下载头，决定自研 |
| 11-08 | `internal/stream`：LRU chunk 缓存 + Range HTTP handler | 测试全过：字节一致性、并发 single-flight 去重、seek 90% 只拉 ≤12 chunks、预取 333ms→84ms |
| 11-08 | `internal/media`：集数解析 + 媒体树归类 | 识别 `S01E02`/`1x02`/`第x集`/`EP02`/`【01】` 等格式；跨频道同名剧归并；tvshow.nfo + .strm 生成 |
| 11-08 | `internal/telegram`：gotd v0.163 客户端 | 分步登录（手机号→验证码→2FA）、频道解析、历史扫描、chunk 拉流（upload.getFile + SetPrecise） |
| 11-08 | `internal/web` + `cmd/tgproxy`：管理页面 | 频道增删、扫描、重建媒体树、状态轮询；登录全走网页 |
| 11-08 | 本地端到端验证 | Docker Jellyfin 12.4 + nginx Range 测试源：6 个 .strm 全部识别、直连播放、25%/50%/90% seek 全 206 |
| 11-08 | 服务器部署 | Jellyfin docker 8097 + tgproxy systemd 8080 + 媒体库，全部就绪 |
| 11-08 | 登录 bug 修复（PHONE_CODE_INVALID） | 见下方「踩过的坑」#1：改为有状态登录会话 |
| 11-08 | 简化登录：内置官方桌面端 API 凭证 | 见下方「踩过的坑」#2；用户只需手机号+验证码一步登录 |

### 部署（服务器 192.168.1.2）

| 项目 | 状态 |
|------|------|
| 交叉编译 linux/amd64 静态二进制 → `~/tgproxy/tgproxy` | ✅ |
| systemd **user** 服务 `tgproxy.service`（enabled，运行中） | ✅ |
| Jellyfin 12.4 docker 容器，端口 8097 | ✅ |
| 向导完成：管理员 `jellyfin / <密码见本地记录，不入库>`，界面 zh-CN | ✅ |
| 媒体库 "TG频道"（tvshows → `~/tgproxy/media`） | ✅ |
| 转码禁用（编码设置 + 用户权限双保险） | ✅ |
| v2 更新：凭证改网页填写，unit 去掉 PLACEHOLDER 环境变量 | ✅ 2026-11-08 19:31 部署并验证 |

## ⚠ 踩过的坑（重要经验）

### 1. TG 登录 PHONE_CODE_INVALID（验证码总是无效）

- **现象**：网页登录时点「发送验证码」后又收到一条新验证码，填第一条报 PHONE_CODE_INVALID
- **根因**：初版登录是"无状态"的——发码和提交验证码各自新建 MTProto 连接，Telegram 发码时返回的 `phone_code_hash` 随连接关闭丢失；提交时重新发码导致旧码失效
- **修复**：登录改为**有状态会话**（`internal/telegram/client.go`）——`SendCode` 建一条持久 loginClient（goroutine 维持 `client.Run` 存活）保存 `loginPending{phone, phoneCodeHash}`；`SignIn`/`SubmitPassword` 复用同一连接与哈希；成功后 `finishLoginLocked` 取 session 并拆除
- **教训**：gotd 的 auth 流程中 phone_code_hash 与连接生命周期绑定，跨请求登录必须保持同一连接

### 2. 两层登录的必要性 + 简化

- **疑问**：为什么要先填 api_id/api_hash 再登账号？
- **结论**：api_id/api_hash 是 Telegram 强制的"应用身份证"，无法省略，但**不该让用户填**——tdl 的做法是内置公共凭证（官方桌面端 `AppID: 2040 / "b18441a1ff607e10a989891a5462e627"`）
- **修复**：`config.Defaults()` 内置桌面端凭证作为默认；用户填过的凭证仍可覆盖（页面凭证卡片保留，供防限流时用自备凭证）
- **教训**：面向用户的登录应该只有"手机号 → 验证码 →（2FA）密码"三步

### 3. Jellyfin 12.4 API 与 v10 差异

见 DEPLOY.md 踩坑表（FirstUser 必须先调、标准 Authorization 头、VirtualFolders 用 query 参数、`/Videos/{id}/stream?static=true`）。

### 4. 本地 e2e 时 strm 地址容器内不可达

- **现象**：本地 Jellyfin docker 测试播放 500 "Connection refused"
- **根因**：strm 里的流地址写了 `127.0.0.1:8081`，Jellyfin 容器内的 127.0.0.1 是容器自己
- **修复**：strm 用 `http://host.docker.internal:8081` + 容器加 `--add-host=host.docker.internal:host-gateway`（服务器部署同理：strm-base 必须是容器可达的宿主 LAN IP）

## ⏳ 待办（按顺序）

1. **用户**：打开 http://192.168.1.2:8080 → 手机号 → 发送验证码 → 填验证码登录（有 2FA 填密码）
2. **用户**：添加频道 → 扫描全部频道 → 重建媒体树
3. **用户**：Jellyfin (8097) 刷新媒体库，确认剧集出现
4. **用户**：手机装 Jellyfin App，服务器 `http://192.168.1.2:8097`，账号 `jellyfin / <密码见本地记录，不入库>`
5. （可选，需时叫我）端到端复验：播放一集、拖进度条确认 206 生效

## 已知问题 / 注意事项

- `go.mod` 有本地 replace：`github.com/iyear/tdl/core => ../dev/tdl-src/core`——在服务器上编译需带 `dev/tdl-src`，或去掉 replace 改用发布版。当前用交叉编译推二进制，无影响
- 服务器 `lee` 用户 sudo 需密码（脚本里不可交互），故 tgproxy 用 **user-level systemd**（`systemctl --user`），已确认 Linger=yes（开机自启不依赖登录会话）
- Jellyfin 12.4 与 v10 API 差异较多（向导端点、认证头、stream 路由），踩坑记录见 DEPLOY.md
- Telegram 文件 >2GB 或 CDN 重定向（`upload.getFileCdnRedirect`）暂不支持——目前返回错误，遇到再实现 CDN 分支
- 媒体树里无集号文件（电影）命名保留清洗后的名字（如 "星际穿越 Interstellar 2014"），集号 S01E00

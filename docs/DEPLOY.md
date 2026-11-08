# 服务器部署

> 服务器：`192.168.1.2`（Ubuntu 24.04.5 x86_64，用户 lee，7.7G 内存 / 138G 可用）
> 部署日期：2026-11-08

> ⚠ 仓库安全：本文件不含私钥路径、密码等敏感信息之外的服务器细节仅限内网 IP；
> `~/tgproxy/data/config.json`（TG session）永远不入库。

## 连接服务器

```bash
ssh -i <私钥> lee@192.168.1.2   # 私钥路径不入库
```

## 运行中的服务

| 服务 | 端口 | 形式 | 位置 |
|------|------|------|------|
| **Jellyfin 12.4.0** | **8097**（容器内 8096） | docker 容器 `jellyfin`，`--restart unless-stopped` | 配置 `~/jellyfin/{config,cache}` |
| **tgproxy** | **8080** | systemd user 服务 `tgproxy.service`（enabled，Linger=yes） | 二进制 `~/tgproxy/tgproxy` |
| pansou | 8899/8900 | docker（原有，勿动） | — |
| emby-server | 8096 | systemd（原有，这就是 Jellyfin 改用 8097 的原因） | /opt/emby-server |

服务器上已有 `~/shipin`（影视聚合 Go 服务）、`~/duanju`，与本项目无关。

## tgproxy

### 目录

```
~/tgproxy/
├── tgproxy          # 静态二进制（linux/amd64，~25MB，本机交叉编译后 scp）
├── data/            # config.json（凭证+session）、library.json（频道+条目）
└── media/           # 媒体树（剧名/Season 01/*.strm）→ 挂进 Jellyfin 容器
```

### systemd unit（user 级）

`~/.config/systemd/user/tgproxy.service`：

```ini
[Unit]
Description=tgproxy - telegram media streaming proxy
After=network-online.target

[Service]
ExecStart=%h/tgproxy/tgproxy -data %h/tgproxy/data -listen :8080 -media %h/tgproxy/media -strm-base http://192.168.1.2:8080
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

> 为什么 user 级：lee 的 sudo 需交互密码，/opt 不可写；`loginctl` 已确认 **Linger=yes**，开机自启不依赖登录会话。

### 运维命令

```bash
systemctl --user status|start|stop|restart tgproxy
journalctl --user -u tgproxy -f          # 日志
curl -s http://192.168.1.2:8080/api/status   # 健康检查
```

### 更新二进制

```bash
# 本机
cd tgproxy && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o /tmp/tgproxy-linux ./cmd/tgproxy
scp -i <私钥> /tmp/tgproxy-linux lee@192.168.1.2:~/tgproxy/tgproxy.new
# 服务器
systemctl --user stop tgproxy && mv ~/tgproxy/tgproxy.new ~/tgproxy/tgproxy \
  && chmod +x ~/tgproxy/tgproxy && systemctl --user start tgproxy
```

## Jellyfin

### 容器参数

```bash
docker run -d --name jellyfin \
  -p 8097:8096 \
  -v ~/jellyfin/config:/config -v ~/jellyfin/cache:/cache \
  -v ~/tgproxy/media:/media/tgproxy:ro \
  --add-host=host.docker.internal:host-gateway \
  --restart unless-stopped jellyfin/jellyfin:latest
```

> `--add-host=host.docker.internal:host-gateway` 是关键：.strm 里的流地址由 Jellyfin 容器发起请求，需能回源到宿主机 8080。

### 已配置

- 管理员：`jellyfin / <密码见本地记录，不入库>`，UI zh-CN / 元数据 CN
- 媒体库 **"TG频道"**（tvshows）→ `/media/tgproxy`（即宿主 `~/tgproxy/media`，只读挂载）
- **转码已禁用**（双保险）：
  1. `/System/Configuration/encoding`：`HardwareAccelerationType=none` + `EnableHardwareEncoding=False`
  2. 用户 jellyfin Policy：`EnableVideoPlaybackTranscoding=False` + `EnableAudioPlaybackTranscoding=False`
- 服务器 ID 4cb37548fafc；管理员用户 Id `d4147df633cc4fa0be1b56862fe96826`

## Jellyfin 12.4 API 踩坑记录（自动化配置用）

| 操作 | 正确做法 |
|------|----------|
| 认证 | `POST /Users/AuthenticateByName {"Username","Pw"}`，头必须用标准 `Authorization: MediaBrowser Client="..." Device="..." DeviceId="..." Version="..."`（用 X-Emby-Authorization 会 400 "request.App null"） |
| 首次向导 | 顺序：`GET /Startup/FirstUser`（必须先调，否则 `POST /Startup/User` 500/404）→ `POST /Startup/User` → `POST /Startup/Configuration {UICulture,MetadataCountryCode,PreferredMetadataLanguage}` → `POST /Startup/RemoteAccess` → `POST /Startup/Complete`，全部 204 |
| 加媒体库 | `POST /Library/VirtualFolders?name=...&collectionType=tvshows&paths=...&refreshLibrary=true`（**query 参数**；body 传 name 会 400 "name field required"） |
| 刷新库 | `POST /Library/Refresh`（`/Items/Refresh` 是 415） |
| 直连播放 | `GET /Videos/{itemId}/stream?MediaSourceId={id}&static=true`（v10 的 `/directstream` 路由 404） |

## 网络

- strm 内写死 `http://192.168.1.2:8080/stream/<channel>_<msg>`（`-strm-base` 指定）
- 局域网内客户端 → Jellyfin 8097 →（direct play）→ 客户端直接拉 tgproxy 8080
- Tailscale 已装（100.97.45.52），外网访问可走 Tailscale（未另行配置）

## 备份要点

- `~/tgproxy/data/`（凭证、session、频道库）
- `~/jellyfin/config/`
- TG session 泄露 = 账号被盗，勿外传

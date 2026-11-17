# 服务器部署

> 服务器：`192.168.1.2`（Ubuntu 24.04.5 x86_64，用户 lee，7.7G 内存）
> 更新日期：2026-11-17（媒体服务器切换为 Emby）

> ⚠ 仓库安全：本文件不含私钥、密码等敏感信息；`~/tgproxy/data/config.json`（TG session + API Key）永远不入库。

## 连接服务器

```bash
ssh -i <私钥> lee@192.168.1.2   # 私钥路径不入库
```

## 运行中的服务

| 服务 | 端口 | 形式 | 位置 |
|------|------|------|------|
| **Emby** | **8096** | systemd `emby-server` | 配置 `/opt/emby-server`，数据 `/var/lib/emby` |
| **tgproxy** | **8080** | systemd user 服务 `tgproxy.service`（enabled，Linger=yes） | 二进制 `~/tgproxy/tgproxy` |
| pansou | 8899/8900 | docker（原有，勿动） | — |

服务器上已有 `~/shipin`、`~/duanju`，与本项目无关。

## tgproxy 配置

推荐在控制台「连接设置 → Emby」中填写服务地址和 API Key，保存后会立即测试并写入配置；也可以直接编辑 `~/tgproxy/data/config.json`。相关字段：

```json
{
  "media_server_url": "http://127.0.0.1:8096",
  "media_server_api_key": "<Emby API Key>"
}
```

- 两项必须同时配置，配置文件保持 `0600`，不入库
- 重建媒体树后调用 `POST /Library/Refresh` 通知 Emby
- 其余字段（`listen`、`media_root`、`strm_base`、缓存参数）保持不变
- `data/peers-*.json` 保存按登录会话隔离的频道访问缓存，不外传

## tgproxy

### 目录

```
~/tgproxy/
├── tgproxy          # 静态二进制（linux/amd64）
├── data/            # config.json（凭证+session）、library.json（频道+条目）
└── media/           # 媒体树（剧名/Season 01/*.strm）
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

> 使用 user 级服务：lee 的 sudo 需交互密码，`/opt` 不可写；已确认 **Linger=yes**，开机自启不依赖登录会话。

### 运维命令

```bash
systemctl --user status|start|stop|restart tgproxy
journalctl --user -u tgproxy -f
curl -s http://192.168.1.2:8080/api/status
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

## Emby

Emby 以 systemd 服务运行，直接读取宿主机目录，无需容器挂载。

### 媒体库

- 每个 TG 频道一个 tvshows 库，库名使用频道标题，路径为 `/home/lee/tgproxy/media/<频道目录>`
- 添加/删除频道时 tgproxy 自动创建/删除对应库
- 媒体树结构：`频道/剧名/Season/...`
- 新库自动配置 TVDB/TMDB/FanArt、`zh-CN`、`CN`
- 转码已禁用：`HardwareAccelerationType=none`、`EnableHardwareEncoding=false`，用户策略关闭视频/音频转码

### 元数据与封面

媒体库必须配置元数据/图片提供器，否则 Emby 不会刮削封面：

- 语言：`zh-CN`；国家：`CN`
- Series：`TheTVDB` + `TheMovieDb`，图片 `TheTVDB` + `FanArt`
- Season/Episode：`TheTVDB`

可在 Emby 媒体库设置里配置，或参考同服务器上已有正常库的 `LibraryOptions` 复制 `TypeOptions`。目录名缺少年份/外部 ID、特别篇（S01E00）等条目可能仍然匹配失败。

### 权限

`/home/lee` 为 `750 lee:lee`，Emby 以 `emby` 用户运行，需要显式授权：

```bash
sudo setfacl -m u:emby:x /home/lee /home/lee/tgproxy
sudo setfacl -R -m u:emby:rX /home/lee/tgproxy/media
```

### API Key

在 Emby 后台「设置 → 高级 → API 密钥」创建，写入 tgproxy 配置。刷新接口：

```
POST /Library/Refresh?api_key=<key>
```

返回 204 表示已接收刷新任务。

## 网络

- strm 内写死 `http://192.168.1.2:8080/stream/<channel>_<msg>`（`-strm-base` 指定）
- 局域网客户端 → Emby 8096 → direct play → 客户端直接拉 tgproxy 8080
- Tailscale 已装（100.97.45.52），外网访问可走 Tailscale（未另行配置）

## 备份要点

- `~/tgproxy/data/`（凭证、session、频道索引、频道访问缓存）
- `~/tgproxy/media/.tgproxy-manifest.json` 和 `~/tgproxy/media.backups/`
- `/var/lib/emby`（Emby 数据库与元数据，需要 sudo）
- TG session 泄露 = 账号被盗，勿外传

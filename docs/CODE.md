# 代码结构

> module `tgproxy`，Go 1.25.8，共 2491 行 Go（含 474 行测试）
> 位置：`/Users/long/Documents/PiProjects/tgproxy/tgproxy/`

```
tgproxy/
├── cmd/tgproxy/main.go      (262)  入口：flags、装配、登录编排、优雅关停
├── internal/
│   ├── config/config.go      (70)  配置持久化（config.json）+ 环境变量覆盖
│   ├── store/store.go       (143)  频道/媒体条目库（library.json）
│   ├── stream/
│   │   ├── cache.go         (279)  LRU chunk 缓存 + single-flight + 预取  ★核心
│   │   ├── cache_test.go    (323)  字节一致性/并发/seek/预取 测试
│   │   └── handler.go       (165)  HTTP Range handler（206/416/HEAD）      ★核心
│   ├── telegram/
│   │   ├── client.go        (210)  gotd 客户端：分步登录 + 长驻连接
│   │   ├── scan.go          (153)  频道解析/历史扫描/文件句柄
│   │   ├── chunk.go         (112)  ChunkSource：upload.getFile 对齐拉取
│   │   └── kv.go             (61)  文件版 session.Storage（备用）
│   ├── media/
│   │   ├── tree.go          (279)  集数解析 + 同名归类 + .strm/nfo 树生成 ★核心
│   │   ├── tree_test.go     (131)
│   │   └── helpers.go        (21)
│   └── web/
│       ├── server.go        (282)  API + 页面路由（Go 1.22 方法模式）
│       └── static/          index.html / app.js / style.css（中文 UI）
└── go.mod                   gotd/td v0.163.0；tdl/core 本地 replace
```

## 模块详解

### stream — 拉流层（解决拖进度条）

- **`stream.Cache`**（`NewCache(src ChunkSource, chunkSize, maxBytes, prefetchN)`）
  - LRU 缓存 chunk（默认 512KB/chunk、512MB 上限），single-flight 合并同 chunk 并发请求
  - `schedulePrefetch`：访问连续（距上次 ≤2 chunk）时顺序预取 N 个
  - `ReadAt`：跨 chunk 组装任意区间，seek 时只拉缺失 chunk
- **`Cache.Handler(src Source)`**：`/stream/<channel>_<msg>` 的 HTTP handler
  - 完整 Range 解析：单区间、后缀（`bytes=-N`）、越界 416 + Content-Range
  - 206 响应带 `Accept-Ranges: bytes`、`Content-Disposition: inline`（修复 tdl 的 attachment 缺陷）
  - `HEAD` 支持；256KB 流式拷贝
- **接口**：`Source.Resolve(file) (ChunkSource, int64 size, string ctype, error)`；`ChunkSource.Chunk(ctx, offset, b) (int64, error)`

### telegram — MTProto 层

- **有状态登录会话**：`SendCode(ctx, phone)` 建持久 loginClient（goroutine 维持连接）+ `loginPending{phone, phoneCodeHash}`；`SignIn(ctx, code)` 复用同一连接（`SESSION_PASSWORD_NEEDED`→`ErrPasswordNeeded`，`PHONE_CODE_INVALID/EXPIRED`→中文错误）；`SubmitPassword(ctx, password)` 完成 2FA；成功后 `finishLoginLocked` 取 session、拆连接；`ResendCode`、`Session()` 辅助
- `Client.Connect(ctx, session, onReady)`：长驻运行，校验授权、建 `dcpool.NewPool`（4 连接）+ peers 管理
- `Client.ChunkSource(h *FileHandle, chunkSize)`：实现 `stream.ChunkSource`
  - `upload.getFile` + `SetPrecise(true)`，limit 按 1MiB/4096 对齐
  - `FILE_MIGRATE`（303）自动换 DC client 重试；CDN redirect 暂不支持
- `ScanChannel(ctx, channelID, limit)`：`messages.getHistory` 200/页分页，`tmedia.GetMedia` 过滤视频
- 关键 API（gotd v0.163）：`peers.Peer.VisibleName()`（非 Title）、`MessagesMessagesClass` 三分支、`auth.ErrPasswordNotProvided`

### media — 媒体树

- `ParseEpisode(name)`：优先级匹配 `S01E02` → `1x02` → `第x集/话/回` → `EP02/E02` → `【01】` → `(01)/[01]` → 裸数字
- `CleanTitle(name)`：剥 1080p/x264/国语/字幕组等噪音标签
- `Classify(items)`：`normalizeTitle + Season` 分组——**跨频道同名不同集归为一组**；无集号（电影）独立成组用 CleanTitle
- `BuildTree(root, groups, strmBase)`：写 `剧名/Season 01/剧名 SxxEyy.strm`（内容 = strmBase + `/stream/<channel>_<msg>`）+ `剧名/tvshow.nfo`

### web — 管理页面

| 端点 | 功能 |
|------|------|
| `POST /api/credentials` | 保存自备 api_id/api_hash（可选，覆盖内置默认） |
| `POST /api/send-code` | 发送登录验证码（有状态会话） |
| `POST /api/sign-in` | 提交验证码（2FA 时返回 401 password required） |
| `POST /api/password` | 提交 2FA 密码 |
| `GET /api/status` | has_credentials / authorized / scanning / channels / items / scan_log |
| `GET/POST/DELETE /api/channels` | 频道管理（@xxx、t.me 链接、数字 ID） |
| `POST /api/scan` | 后台扫描全部频道（goroutine + 日志） |
| `POST /api/rebuild` | Classify → BuildTree 重建媒体树 |
| `GET /stream/{key}` | 拉流（Range 缓存层） |

### cmd/tgproxy/main.go 装配要点

- tgClient 惰性创建：启动即有内置凭证，无凭证时也能建客户端；自备凭证保存后 `startClient` + `connectClient`
- `web.Deps.TGFn func() *telegram.Client` 动态取当前客户端（凭证热更新安全）
- 登录编排：`withLoginClient`（loginMu 串行化）包 `SendCode/SignIn/SubmitPassword`；`onLoginSuccess()` 存 session → `connectClient` 重启长驻客户端
- session 持久化到 config.json，重启自动重连
- flags：`-data`（默认 ~/.tgproxy）、`-listen :8080`、`-media`、`-strm-base`

## 构建/测试

```bash
cd tgproxy
go build ./... && go vet ./... && go test ./...   # 本地全过

# 服务器部署用交叉编译（静态二进制 ~25MB）
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o /tmp/tgproxy-linux ./cmd/tgproxy
scp -i <私钥> /tmp/tgproxy-linux lee@192.168.1.2:~/tgproxy/tgproxy.new
# 服务器上：systemctl --user stop tgproxy && mv tgproxy.new tgproxy && chmod +x tgproxy && systemctl --user start tgproxy
```

## 参考源码（本地 clone）

- `dev/tdl-src/` — iyear/tdl v0.20.4（core 包被 replace 引用）
- `/tmp/gotd-contrib` — gotd/contrib（tdl --serve 的 partio/http_io 实现，缺陷分析对象）

# 代码结构

Go module：`tgproxy/`。gotd/td v0.163.0 与 tdl core 均通过 `go.mod` 固定到 `lonnnnnng` 的 fork commit。

| 模块 | 职责 |
|---|---|
| `cmd/tgproxy/main.go` | flag/配置优先级、HTTP 装配、索引访问检查、句柄缓存与流服务 |
| `cmd/tgproxy/application.go` | 凭证与会话的原子保存、指定客户端的连接生命周期、旧连接关闭 |
| `internal/telegram/client.go` | 有状态登录、就绪通知、登录超时、长连接与资源发布 |
| `internal/telegram/scan.go` | 原始消息游标分页、视频过滤、MIME 提取、扫描进度、文件句柄解析 |
| `internal/telegram/resolve.go` | 频道引用解析、-100 ID 兼容、必要时从已有会话恢复 access hash |
| `internal/telegram/peerstore.go` | 按会话隔离的访问缓存，私有文件持久化 |
| `internal/telegram/pool.go` | DC pool 创建/关闭同步，防止底层 map 在关闭时被并发修改 |
| `internal/telegram/retry.go` | 有界、可取消的 FLOOD_WAIT 等待重试 |
| `internal/telegram/chunk.go` | 文件分块对齐、尾块、DC 迁移、引用过期恢复与文件身份检查 |
| `internal/stream/cache.go` | 有界 LRU/singleflight、需求与预取分离、取消、超时、失败重试 |
| `internal/stream/source.go` | 有界 TTL 句柄缓存，解析合并与独立等待者取消 |
| `internal/stream/handler.go` | HTTP 单 Range/HEAD、首块失败 502/504、中途断流的可识别终止 |
| `internal/media/tree.go` | 预编译的集号/剧名规则、跨频道归类、来源保留和确定性排序 |
| `internal/media/sync.go` | 输出规划、所有权清单、原子文件替换、旧输出备份与失败恢复 |
| `internal/store/store.go` | 频道/视频索引、版本号、消息查找索引、先持久化再提交内存 |
| `internal/persist/json.go` | 私有临时文件 + sync + 同目录 rename 的原子 JSON 保存 |
| `internal/config/config.go` | 默认值、环境覆盖、数值和 URL 校验、配置保存 |
| `internal/jellyfin/client.go` | 重建后通知 Jellyfin 刷新；拒绝携带密钥重定向 |
| `internal/web/server.go` | 同源管理 API、任务互斥、扫描自动重建、错误与进度状态 |
| `internal/web/static/` | 中文控制台、串行轮询、忙碌状态与错误展示 |

## 状态链路

添加频道只保存频道；扫描按频道完整分页，成功后替换该频道索引，失败保留旧索引并记录错误。扫描结束自动用当前索引生成媒体树，再通知 Jellyfin。删除频道同步移除索引，重建时移出已登记的过期输出。

媒体树以 `.tgproxy-manifest.json` 记录文件内容哈希和索引版本；不更换根目录 inode，不覆盖用户改过的文件。历史无清单的树只迁移符合本项目流地址格式的输出。旧文件进入同级 `media.backups/`，不递归清空整个媒体目录。

## 测试

```sh
cd tgproxy
go test -race ./...
go vet ./...
go build ./...
# long: 索引含私人频道信息，真实样本只从临时副本读取，不放进仓库。
TGPROXY_LIBRARY_FIXTURE=/path/to/private-library-copy.json go test ./internal/media -run TestLibraryFixture -count=1 -v
```

TDL 连接池修复分支：<https://github.com/lonnnnnng/tdl/tree/codex/dcpool-lifecycle-fix>。tgproxy 固定使用该分支提交 `09ffd071bb095c73d4d2a83233847806513e0fea`，并使用 gotd fork 提交 `8c39a2b01c8213481f59078867d22413ed05bc12`。版本由 `go.mod` 固定，不跟随可移动分支。

测试覆盖分段字节一致性、尾块、seek、预取、取消交错、失败重试、DC 迁移、过期引用、分页、存储失败、同名来源、目录同步、路径冲突、扫描互斥与同源保护。真实网络、账号登录和客户端解码仍需单独验收。

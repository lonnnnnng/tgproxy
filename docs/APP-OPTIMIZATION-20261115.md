# TGProxy Android 六项优化验收

日期：2026-11-15（北京时间）；实施与验收跨2026-11-14～15。作者：long。

工作区：`/Users/long/Documents/CodexProjects/tgproxy`，分支`codex/android-poc`，优化基线`b7e7a66`。以下记录对应发版前的0.6.0 / code7优化快照，已保留数据覆盖安装到POCO F2 Pro验证包`dev.tgproxy.mobile.poc`。用户随后要求发版，六项优化纳入0.7.0 / code8；正式制品和发布检查见[发行说明](../android/RELEASE-v0.7.0.md)与[验收记录](../android/VERIFICATION.md)。

## 六项结果

| 顺序 | 已实现行为 | 主要实现 |
| --- | --- | --- |
| 1. 下载可靠性 | 初始化／调度异常收尾、重新读取、坏记录与删除失败隔离、16MiB存储余量、空间不足暂停整批、原账号全部继续／失败批量重试、旧停止代次隔离 | `Downloads`、`DownloadStore`、`DownloadStoragePolicy` |
| 2. 观看记录 | 在线／离线按最新观看时间续播，离线同步最近播放，看完归零；同账号／key／document校验，退出账号后独立保存本机进度，换片不继承旧断点 | `PlaybackHistory`、`PlaybackCheckpoint`，下载schema2 |
| 3. 大库性能 | 索引schema4增加排序／频道索引，视频深页改排序值＋key游标；频道查询移到后台；下载历史用RecyclerView每批50行，进度只更新当前记录和可见行 | `MediaDatabase`、`VideoPageCursor`、`DownloadSnapshot`、`SingleItemList`、`DownloadsActivity` |
| 4. 播放 | 同频道排序／搜索范围选片、上一条／下一条、默认关闭的连续播放；本机来源优先、最多三次终止网络错误恢复、后台重绑跟随当前媒体、片尾切来源从头播放 | `PlaybackCatalog`、`PlaybackRetryPolicy`、`PlaybackService`、`PlayerActivity`、`player-controls` |
| 5. 另存 | 应用级队列与dataSync前台服务，进度、取消、持久结果、分页历史、重新选位置、确认清理副本；目标空文件检查、实际持久读写授权、全批中断恢复、通知取消释放服务与唤醒锁 | `ExportTransfer`、`ExportStore`、`Exports`、`ExportService` |
| 6. 触控与适配 | 48dp点击范围、大字体／窄窗口筛选分行、详情元数据分行、下载信息自适应、播放器文字自然增长与窄屏中央控件、键盘完成共用验证码／密码提交、系统栏主题与cutout适配 | 三个业务Activity、`ChannelUpdateButton`、`VideoControlsView`与控制器XML |

保持紧凑纯文字列表。产品、设计、技术说明和组件接入文档已同步。

## 真机与数据边界

- 仅用POCO F2 Pro，serial `31936bb6`，Android16 / API36，ARM64。用户要求后已关闭本次启动的模拟器；最终ADB列表只有两台真机，Redmi未操作。
- 验证包覆盖升级，保留原账号、频道索引和下载；没有清数据、重新登录或运行真实全量扫描。
- 数据／下载故障测试使用独立SQLite和合成文件。生产页面、连续播放和真实SAF使用自己创建的UUID夹具，结束时按这些UUID清理并核对原下载、导出和索引数量。
- 字体原值1.0；物理密度440、原覆盖值396。窄屏阶段临时改fontScale1.5、密度540（1080px对应320dp）；结束恢复1.0／396，ADB回读确认。外观、连续播放、倍速、比例及媒体音量由相关用例恢复原值。
- App启动会恢复既有Telegram连接。媒体／故障夹具主要走合成本机文件和本机Range，不能称整次instrumentation零网络。

## 性能实测

同一POCO、10万条合成索引和1000条下载历史，用例输出如下。数值是单机样本，不代表所有设备和真实频道的时延。

| 用例 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 日期深OFFSET查询 | 48.59ms | 6.43ms |
| 名称深OFFSET查询 | 72.68ms | 4.02ms |
| 实际深页游标查询 | 原页面使用OFFSET | 2.25ms |
| 字面子串搜索 | 0.961ms | 0.960ms |
| 下载进度过程全历史读取 | 11次 | 6次 |
| 合成传输与状态发布耗时 | 423ms | 430ms |

空搜索去除LIKE，非空保留字面子串语义。当前样本未支持改成FTS的必要性。下载优化减少读取／解析和界面重建，不能据此声称下载速度提升。

## 验证结果

构建使用JDK17和项目Gradle wrapper，Go AAR及依赖版本沿用项目现有值。

| 层次 | 结果 | 证据 |
| --- | --- | --- |
| JVM单元测试 | 19项，0失败／错误 | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| 下载／观看／数据库／性能 | 21项真机测试通过 | `03-performance-after-tests.log`，另有01／02专项日志 |
| 初次播放组 | Catalog、Queue、Core共9项通过 | `04-playback-tests.log` |
| 导出存储与复制故障 | 7项通过：完整字节、取消与显式清理、非空／未知／拒绝目标、131条任务中断与分页、重开恢复、初始读取／写入失败 | `05-export-test-retry.log` |
| 默认尺寸界面 | NativeUiTest 4项通过 | `06-ui-normal.log` |
| 字体1.5／320dp | 套件16项无失败；15项通过，已登录设备的登录草稿用例跳过 | `06-ui-large-regression.log` |
| 后台未完成选片／片尾切来源 | PlaybackQueueTest 3项通过，含原前后／连续播放回归与两项新增边界 | `08-lifecycle-regression.log`中的Queue结果 |
| 真实通知取消／真实SAF | 2项通过 | `08-export-lifecycle-retry.log` |
| 最终播放与观看 | Catalog1＋Core7＋History2，共10项通过 | `10-final-playback-history.log` |
| 静态与制品 | App Debug／Release Lint 0错误（38／37警告），组件Lint 0错误（3警告）；Debug、AndroidTest、Release及组件AAR构建通过；APK签名与zipalign检查通过 | `09-final-static-release.log`、Lint XML与命令输出 |

以上日志位于`android/app/build/validation/optimization-20261114/`。警告主要是依赖／工具版本、可选平台、资源、KTX和文案风格提示；没有因本轮操作升级框架或批量改写无关代码。

真实SAF通过系统选择器在POCO的Download目录创建唯一UUID测试片，核对全部字节和持久授权，重建页面后看到完成结果，再按精确文件名清理本次副本。通知取消使用真实通知的PendingIntent制造第二个startId，检查服务停止及`dumpsys power`当前活动锁段不含`TGProxy:Exports`。

独立复核发现并修复：通知取消后的旧startId、丢失选片回调后后台连续切集重绑、已ENDED媒体切到本机来源继承片尾位置。相关回归已通过。

过程中的失败日志保留：测试Provider缺Kotlin运行时改为独立Java入口；系统窗口输入改为UiAutomation并显式标记触屏来源；唤醒锁检查改读活动段；手动系统选择等待改成同一自动化链路。这些初次失败不能计入通过结果。

## 界面查看

本地截图目录：`android/app/build/validation/optimization-20261114/ui/`。

已查看默认和大字体画面的搜索／筛选、播放器、下载状态及真实导出完成结果。长标题维持一至两行省略；大字体下元数据与恢复入口可读，中央操作完整，底部操作条在窄屏可横向滚动。截图包含本机数据，只保存在忽略的构建目录。

## 制品

| 文件 | 用途 | SHA-256 |
| --- | --- | --- |
| `android/app/build/outputs/apk/debug/app-debug.apk` | 已安装的优化验证包，0.6.0 / code7 / `dev.tgproxy.mobile.poc` | `d2025aab99a360847e29deb610aa243c3a22bf3ad7debb8622e4150f822ceda0` |
| `android/player-controls/build/outputs/aar/player-controls-release.aar` | 当前源码的播放控制组件 | `4b5f2f358b66c94474a3dda3ffba872c8f952956e0dad4babc2fba28fa15dfbc` |

优化阶段的Release变体构建为未签名APK，上表保留该阶段Debug及组件校验信息。后续0.7.0正式发布复用原签名，最终附件校验以发行说明及校验文件为准。

## 未验证范围

真实持续弱网、长时功耗、dataSync六小时时限实触发、真实磁盘占满、系统杀进程的全部时序、云／第三方提供器即时取消、完整TalkBack人工朗读及其他ROM／设备尚未全面验收。低空间和SQLite故障有注入测试，不能等同于真实设备写满。

验证码／两步验证检查空值和禁用状态的键盘提交；保留现有授权，没有重跑真实登录。当前非空搜索仍为LIKE子串检索，离线列表和连续播放范围只包含当前约束内的完成文件。

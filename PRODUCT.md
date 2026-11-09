# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

自建服务维护者通过浏览器管理 Telegram 视频来源和 Jellyfin 媒体库同步。

## Product Purpose

tgproxy 汇总 Telegram 频道中的视频，为 Jellyfin 生成可扫描和播放的媒体入口。控制台用于登录、频道维护、扫描历史、生成媒体树并查看 Jellyfin 更新状态；视频浏览与播放由 Jellyfin 客户端承担。

## Operating Context

维护者在局域网通过浏览器操作自托管服务。主要工作流是登录 Telegram、添加频道、扫描视频历史、生成媒体入口，并确认 Jellyfin 扫描状态。

## Capabilities and Constraints

- 保留 Telegram 登录、可选 API 凭证、频道添加/删除、扫描、手动重建、错误日志和 Jellyfin 更新状态。
- 扫描完成后会自动重建媒体树并通知 Jellyfin。
- 首屏优先呈现扫描进度和 Jellyfin 状态。
- 主题需支持浅色和深色。
- tgproxy 视频索引与 Jellyfin 媒体扫描是两个独立阶段，状态和数量需要分别表达。
- 控制台分为同步概览、频道管理、运行日志、连接设置四个工作区。
- 频道支持名称/引用/ID 搜索、状态过滤和排序；日志支持搜索、级别过滤、复制与下载。
- 断线显示数据过期提示；写操作等待结果期间禁用冲突操作；删除需明确确认索引失效影响。

## Brand Commitments

- 产品名称为 tgproxy，界面语言为简体中文。
- 用户确认浅色和深色主题都应可用；没有指定具体调色板或字体。

## Product Principles

- 长时间扫描期间，当前阶段、进度和错误恢复路径应清晰可见。
- 把 Telegram 索引与 Jellyfin 入库作为相关但独立的状态呈现。
- 保持频道维护和媒体更新流程可预期，不隐藏已有操作。
- 桌面优先，窄屏也需保持可读和可操作。

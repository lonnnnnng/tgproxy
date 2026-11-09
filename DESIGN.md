---
name: tgproxy 媒体工作台
description: 四个工作区组成的 Telegram 媒体管理界面
colors:
  canvas: "#f6f7fa"
  surface: "#fff"
  sidebar: "#f0f2f7"
  subtle: "#f7f8fc"
  text: "#222a3b"
  muted: "#5f687d"
  line: "#e5e8f0"
  strong-line: "#c4ccdc"
  accent: "#3859d6"
  accent-hover: "#2946b7"
  accent-soft: "#e8edff"
  on-accent: "#fff"
  green: "#267357"
  green-soft: "#eaf6ef"
  amber: "#885713"
  amber-soft: "#fff4de"
  red: "#b43a48"
  red-soft: "#fff0f1"
  purple: "#7950b5"
  purple-soft: "#f1eafa"
  dark-canvas: "#131722"
  dark-surface: "#1b2130"
  dark-sidebar: "#171c29"
  dark-subtle: "#222a3b"
  dark-text: "#e9edf7"
  dark-muted: "#a5b0c6"
  dark-line: "#30394c"
  dark-strong-line: "#55617a"
  dark-accent: "#a5b7ff"
  dark-accent-hover: "#c0ccff"
  dark-accent-soft: "#2c385e"
  dark-on-accent: "#142346"
  dark-green: "#8ed5ae"
  dark-green-soft: "#213b32"
  dark-amber: "#edc07b"
  dark-amber-soft: "#403523"
  dark-red: "#ffa5b0"
  dark-red-soft: "#422b36"
  dark-purple: "#c9aff7"
  dark-purple-soft: "#352d4b"
typography:
  page-title:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "30px"
    fontWeight: 650
    lineHeight: 1.3
  mobile-title:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "26px"
    fontWeight: 650
    lineHeight: 1.3
  task-title:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "22px"
    fontWeight: 600
    lineHeight: 1.5
  section-title:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "16px"
    fontWeight: 650
    lineHeight: 1.5
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.55
  control:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: 1.55
  supporting:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.55
  mobile-meta:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "11px"
    fontWeight: 400
    lineHeight: 1.55
rounded:
  control: "8px"
  panel: "12px"
  dialog: "14px"
  badge: "5px"
spacing:
  inline: "8px"
  control: "12px"
  group: "20px"
  panel: "24px"
  page: "36px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    rounded: "{rounded.control}"
    height: "40px"
    padding: "9px 15px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.control}"
    height: "40px"
    padding: "9px 15px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.control}"
    height: "40px"
    padding: "9px 12px"
---

# Design System: tgproxy 媒体工作台

## Overview

**Creative North Star: “清晰的媒体目录”**

用户已授权重新设计整套控制台，并委托决定视觉和功能。新版以冷灰白、钴蓝和明确分区承载维护任务，替换旧版绿色节目表。使用标准侧栏、表格、输入框、状态标签；品牌表达来自频道首字标识和依次变化的同步路径。

**Key Characteristics:**

- 概览、频道、日志、连接各有明确职责。
- 浅色和深色共享布局与语义状态。
- 主要内容使用柔和边界，浮层才使用阴影。
- 手机表格重排为分行列表，保留完整标题与可点操作。

## Colors

钴蓝用于主要动作、选中导航和正在执行的阶段。绿色表示成功，琥珀表示待处理，红色表示错误，紫色识别 Jellyfin 集成。频道首字块使用这些软底色区分来源，其色彩不代表健康状态。浅色以冷灰背景、白色内容和墨灰正文区分层次；深色以蓝灰背景和高对比文字维持同样层次。

## Typography

系统无衬线字体承载中文界面，正文 14px，操作与频道标题 13px，辅助文字通常为 12px。桌面页标题 30px，手机 26px；同步任务标题桌面 22px、手机 20px。数量用等宽数字，日志正文及配置字段使用系统等宽字体。手机频道标题换行，桌面长标题截断并提供完整 title。

## Layout

桌面侧栏宽 216px，1150px 以下收至 190px；顶部路径栏 68px。内容最大宽度 1410px，外侧留白 36px。同步主面板与 Jellyfin 结果面板采用不等宽两栏，950px 以下上下排列。700px 以下侧栏变为顶部四项导航，内容边距 16px，频道表重排为分行列表。

## Elevation & Depth

内容面板用 1px 边界与背景色建立层次，不使用普通卡片阴影。删除确认 dialog 与提示条使用柔和浮层阴影；dialog 的遮罩说明页面被暂时阻断。按钮颜色变化 160ms，遵循减少动态效果设置，没有页面进入动画。

## Shapes

面板 12px 圆角，控件 8px，确认对话框 14px，状态标签 5px。图标为同一套 1.7px 线宽 SVG；不要用装饰图形填充工作区。手机删除与主题切换命中区至少 44px。

## Components

- 主按钮用动作色与对比文字，次按钮用表面底色和可见边框；危险按钮仅出现在明确的删除确认中。
- 输入框及 select 使用相同高度、圆角与键盘焦点样式。添加频道在频道页内展开；删除使用原生 dialog 提供焦点约束。
- 同步路径以三项真实业务阶段呈现，不虚构视频总量、剩余时间或 Jellyfin 入库进度。
- 导航使用 hash 路由、aria-current、可见焦点和当前页面标题；跳转主内容不改变当前页面。
- 任务进行中与本地请求尚未完成时锁定冲突操作；网络中断显示旧数据标记和恢复入口。
- 频道与日志仅在内容变化时重建列表，避免状态轮询不断打断浏览与操作。

## Do's and Don'ts

- **Do** 区分已添加频道、视频索引、媒体入口及远端刷新请求。
- **Do** 给空状态、断线和失败提供明确的下一步。
- **Do** 保持双主题、键盘导航与减少动态效果行为。
- **Don't** 把提交 Jellyfin 请求说成入库完成。
- **Don't** 将旧节目表长页的重复频道列表带回新概览。
- **Don't** 在生产 UI 中展示设计流程、生成工具或虚构数据。

---
name: tgproxy 控制台
description: Telegram 频道到 Jellyfin 媒体入口的运维控制台
colors:
  pine: "#146c54"
  pine-strong: "#0c5642"
  pine-soft: "#d9eee3"
  signal: "#c95c2a"
  signal-soft: "#f8e4d7"
  canvas: "#f1f5f2"
  surface: "#ffffff"
  ink: "#1c2923"
  muted: "#53645b"
  rule: "#ccd8d0"
  rule-strong: "#aebeb4"
  quiet: "#5b6a61"
  header: "#174d3e"
  header-hover: "#28614f"
  header-border: "#588072"
  state-ok-dot: "#91d5a7"
  state-warn-dot: "#efad69"
  state-error-dot: "#ff9b91"
  header-border-light: "#bed3c8"
  secondary-rule: "#aebeb4"
  success: "#176c49"
  warning: "#8b501d"
  error: "#a33336"
typography:
  display:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "27px"
    fontWeight: 660
    lineHeight: 1.25
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: 1.5
  caption:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "11px"
    fontWeight: 400
    lineHeight: 1.5
  compact:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.5
  section:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "16px"
    fontWeight: 650
    lineHeight: 1.4
  title:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "18px"
    fontWeight: 650
    lineHeight: 1.35
  count:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "19px"
    fontWeight: 680
    lineHeight: 1
  metric:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "21px"
    fontWeight: 680
    lineHeight: 1
  mobile-heading:
    fontFamily: "-apple-system, BlinkMacSystemFont, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
    fontSize: "24px"
    fontWeight: 660
    lineHeight: 1.25
rounded:
  hairline: "2px"
  sm: "4px"
  md: "5px"
  scroll: "8px"
spacing:
  xs: "6px"
  sm: "9px"
  md: "16px"
  lg: "28px"
components:
  button-primary:
    backgroundColor: "{colors.pine}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    padding: "8px 14px"
    height: "40px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "8px 14px"
    height: "40px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.sm}"
    padding: "8px 11px"
    height: "40px"
  toast:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "12px 16px"
---

# Design System: tgproxy 控制台

## Overview

**Creative North Star: “频道节目表”**

这是一个给自建服务维护者使用的工作界面。视觉借用电视节目表的顺序感和广播色标，但只借用类型、密度、颜色和频道行的强调方式；布局、表单和操作仍遵循标准 Web 运维工具的预期。

界面以纸白或深石墨作为工作底色，松石绿标记主要动作和成功状态，信号橙标记进行中的任务，红色只表示失败。扫描、媒体树和 Jellyfin 是三个独立阶段，任何阶段都不伪造未知的远端进度。

**Key Characteristics:**
- 频道顺序是首屏的主要阅读轴。
- 状态颜色只承担连接、进行中、成功、警告和失败的语义。
- 线性分隔和轻微色调层级承担结构，不使用装饰性阴影。
- 浅色与深色主题共享同一套信息层级。

## Colors

调色板来自局域网运维场景：冷静的松石绿作为动作色，信号橙作为扫描中的现场提示，纸白和深石墨分别适应明亮与低照度环境。

### Primary
- **松石绿** (#146c54): 顶层主要动作、品牌标识和成功导向的控制。
- **深松石绿** (#0c5642): 主要按钮 hover 和强调文本。

### Secondary
- **信号橙** (#c95c2a): 扫描中、提交中和需要注意的状态。

### Neutral
- **纸白** (#f1f5f2): 浅色页面背景。
- **工作表面** (#ffffff): 输入框和次级按钮。
- **石墨文字** (#1c2923): 主要文字。
- **辅助文字** (#53645b): 说明、标签和次级状态。
- **结构线** (#ccd8d0): 表格、阶段带和工作区分隔。

### Named Rules

**The Signal-Only Rule.** 颜色只在它传达状态或主要动作时出现；空白区域不使用彩色装饰。

## Typography

**Display Font:** 系统无衬线字体栈
**Body Font:** 系统无衬线字体栈
**Label/Mono Font:** 仅代码流地址和扫描日志使用系统等宽字体。

**Character:** 中文阅读优先，字重用来建立明确层级，数字使用浏览器的等宽数字特性保持扫描计数稳定。

### Hierarchy
- **Display** (660, 27px, 1.25): 页面标题。
- **Headline** (650, 18px, 1.35): 工作区标题和阶段标题。
- **Title** (620–650, 13–16px, 1.4): 频道名、当前频道和状态项目。
- **Body** (400, 14px, 1.5): 操作说明和表格内容。
- **Label** (600, 12px, 1.5): 状态标签、列标题和辅助信息。

## Layout

内容容器最大宽度为 1240px，桌面左右边距随视口收缩，页面顶部是 60px 深松绿色栏。首屏顺序为页面标题与动作、频道节目表、频道管理和认证工具。节目表使用“阶段带 → 当前频道 → 频道行 → Jellyfin 侧栏”的阅读路径。

在 880px 以下，节目表与 Jellyfin 侧栏、频道表与工具栏改为纵向排列；在 620px 以下，阶段带、按钮、频道行和管理表改为单列。字号不随视口缩放，移动端通过重排内容保持可读。

## Elevation & Depth

系统默认扁平。结构由 1px 分隔线、浅色调容器和主题背景完成；只有底部提示条使用轻微环境阴影。不要用卡片阴影堆叠层级。

## Shapes

输入框、按钮、状态标签和表格操作使用 4–5px 小圆角，避免圆角卡片感。主要区域不包裹在嵌套卡片内，依靠横向分隔线和列分割建立工作区。图标按钮保持 36px 方形命中区并显示键盘焦点环。

## Components

### Buttons
- **Shape:** 4–5px 圆角，40px 高。
- **Primary:** 松石绿背景、白色文字、8px 14px 内边距。
- **Hover / Focus:** hover 加深松石绿；focus-visible 使用 3px 信号色外环。
- **Secondary:** 工作表面背景和结构线边框；破坏性删除使用图标按钮和确认步骤。

### Inputs / Fields
- **Style:** 工作表面背景、1px 结构线边框、4px 圆角、8px 11px 内边距。
- **Focus:** 信号色 3px 外环，不改变布局尺寸。
- **Error / Disabled:** 错误使用浅红背景和红色文字；禁用状态降低不透明度并保留可读标签。

### Navigation
- **Style:** 顶栏只承载 tgproxy 标识、Telegram 连接状态和主题切换；没有把工具操作藏进侧边导航。

### 频道节目表
频道按扫描顺序编号。当前扫描行使用松石软底色，已完成使用成功色，失败使用错误色；节目表右侧独立显示 Jellyfin 请求状态、视频索引数和媒体入口数。

## Do's and Don'ts

### Do:
- **Do** 把频道完成数和当前频道已发现视频数分开表达。
- **Do** 把媒体树生成与 Jellyfin 刷新请求作为两个阶段显示。
- **Do** 为空频道、扫描失败、等待扫描和远端通知失败提供明确恢复路径。
- **Do** 保持浅色和深色主题的语义色一致。

### Don't:
- **Don't** 编造 Jellyfin 的扫描百分比、库内资源数或剩余时间。
- **Don't** 用单一大卡片、渐变背景或装饰性图形替代频道行和任务状态。
- **Don't** 用颜色代替状态文字；所有状态都要能被文字和结构理解。

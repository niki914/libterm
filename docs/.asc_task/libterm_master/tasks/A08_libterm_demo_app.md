# A08 - libterm_demo_app

## 目标

实现一个最小多窗口 Demo App，用于验证 LibTerm 的真实体验。

## 范围

- 多窗口列表。
- 打开 USER/ROOT/Shizuku 终端入口。
- 输出实时显示。
- 输入框发送内容。
- 展示 `OpenResult` 错误。

## 非目标

- 不做完整 Termux UI。
- 不做主题系统。
- 不做命令历史。
- 不做持久化恢复。

## 依赖

- 依赖 A07 runtime。

## 验收

- 可打开至少一个 USER 终端。
- 可发送输入并看到流式输出。
- 可关闭窗口。
- ROOT/Shizuku 不可用时 UI 能展示明确失败。

## 测试要求

- 以手动验收为主。
- 可补少量 UI smoke 测试。
- 不把 demo-app 作为核心逻辑测试入口。


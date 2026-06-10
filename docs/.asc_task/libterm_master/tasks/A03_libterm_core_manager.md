# A03 - libterm_core_manager

## 目标

实现 `TerminalManager`：多窗口终端管理和 `open()` 结果编排。

## 范围

- `open(identity)`。
- `close(id)`。
- `list()`。
- `get(id)`。
- 多 session 隔离。
- 不做自动回退。

## 非目标

- 不实现后端细节。
- 不做 UI tab。
- 不做持久化恢复。

## 依赖

- 依赖 A01 契约。
- 依赖 A02 的 `TerminalSession`。
- 建议依赖 A04 Fake。

## 验收

- 指定身份不可用时返回失败结果，不创建 session。
- 指定身份可用时返回成功结果并加入窗口表。
- 多个 session 的 ID、identity、输出互不串扰。
- `close()` 后窗口表正确移除。
- 重复 close 不崩溃。

## 测试要求

- 必须纯 JVM 单测。
- 使用 FakeProvider/FakeBackendFactory。
- 至少覆盖：open success、provider unavailable、authorization denied、backend start failure、多窗口、重复关闭。


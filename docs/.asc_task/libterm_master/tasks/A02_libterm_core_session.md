# A02 - libterm_core_session

## 目标

实现 `TerminalSession`：一个终端窗口的核心状态机。

## 范围

- 绑定一个 `TerminalBackend`。
- 收集 backend 的 `output` 流。
- 维护有上限的内存输出环形缓冲。
- 暴露 `latest(n)`、`send(input)`、`close()`。
- 暴露 session 当前状态。

## 非目标

- 不做磁盘持久化。
- 不做命令历史。
- 不判断可靠的 `WAITING_INPUT`。
- 不支持 PTY/full-screen TUI。

## 依赖

- 依赖 A01 契约。
- 可与 A04 测试基础设施并行。

## 验收

- 输出超过容量时正确裁剪旧块。
- stdout/stderr 均能进入缓冲且保留标记。
- `send()` 透传到 backend。
- `close()` 幂等。
- backend 异常关闭能反映到 session 状态。

## 测试要求

- 必须纯 JVM 单测。
- 使用 `FakeBackend` 驱动真实状态机。
- 至少覆盖：空输出、大量输出、stderr、close 后 send、backend 失败、并发输出。


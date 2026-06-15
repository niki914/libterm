# Command Execution

## 定位

本页回答 `exec` 和 `write` 如何落到会话、输出流和退出码处理。主要入口是 `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/DefaultTerm.kt`、`libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/TerminalCommandExecutor.kt`、`libterm-runtime/src/main/java/com/niki914/libterm/runtime/CommandResult.kt` 和 `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalSession.kt`。

## 状态判断

| 能力 / 链路 | 状态 | 源码证据 | 边界 |
|:--|:--|:--|:--|
| `open` 到 session 绑定 | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/DefaultTerm.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTermRuntime.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTermSession.kt` | `DefaultTerm` 负责确保 session 存在，runtime 负责包装 session，`LibTermSession` 负责绑定文本输出流水线 |
| `write` 直通会话发送 | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/DefaultTerm.kt`, `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalSession.kt` | 只负责发送输入，不生成 `CommandResult` |
| `exec` marker 退出码采集 | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/TerminalCommandExecutor.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/CommandResult.kt` | 通过 marker 文本从 stdout 中解析 exit code，并汇总 stdout/stderr |
| 输出缓冲和失败传播 | `Stable` | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalSession.kt` | session 负责输出 buffer、后台收流和运行期失败落地 |

## 关键源码

### `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `DefaultTerm.kt` | `open` / `ensureSession` | 确保存在可用 session |
| `TerminalCommandExecutor.kt` | `exec` | 发送带 marker 的 payload，解析退出码并拼装 `CommandResult` |
| `DefaultTerm.kt` | `write` | 直接把输入写入会话 |

### `libterm-core/src/main/kotlin/com/niki914/libterm/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `TerminalSession.kt` | `start` | 启动 backend 并进入运行态 |
| `TerminalSession.kt` | `send` | 向 backend 写入字节流 |
| `TerminalSession.kt` | `latest` | 提供最近输出片段 |
| `TerminalSession.kt` | `close` | 管理关闭时序和结果收敛 |

## 核心链路

1. `DefaultTerm.kt` 在 `ensureSession` 中确保 `TerminalManager` 已打开，并经由 `LibTermRuntime` 包装成 `LibTermSession`。
2. `exec` 通过 `LibTermSession.kt` 串到 `TerminalCommandExecutor.kt`，发送带 marker 的 payload，并收集 stdout/stderr。
3. `TerminalSession.kt` 持续接收 backend 输出、维护 buffer，并在退出或异常时更新状态。

## 调试 / 阅读关注点

| 问题 | 先看 | 判断标准 |
|:--|:--|:--|
| 为什么 `exec` 有 `CommandResult` 而 `write` 没有 | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/TerminalCommandExecutor.kt` | 是否存在 marker 解析和 stdout/stderr 汇总 |
| 输出什么时候会写进 buffer | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalSession.kt` | 是否在后台收流时 append chunk 并 trim |
| timeout 后结果长什么样 | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/TerminalCommandExecutor.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/CommandResult.kt` | 是否返回 `exitCode = null` 且 `timedOut = true` |

## 与其他页面关系

| 相关页面 | 关系 |
|:--|:--|
| `overview/project-overview.md` | 说明公开 `Term` API 是什么 |
| `architecture/runtime-core-backend.md` | 说明 session 是如何被打开和绑定的 |
| `domains/backends/index.md` | 说明输出最终从哪个 backend 实现产出 |
| `reference/source-map.md` | 提供进一步跳到源码的路径地图 |

## 缺口

| 缺口 | 当前状态 | 下一步 |
|:--|:--|:--|
| marker 文本兼容性和极端输出场景的专门说明 | `Unverified` | 如果后续需要更细文档，再继续核对 `TerminalCommandExecutor.kt` 的 marker 解析边界 |

# Backends

## 定位

本页回答 backend SPI 长什么样，以及 libsu、Shizuku、SSH 三条实现链路各自的入口在哪里。主要入口是 `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalBackend.kt`、`libterm-backend-libsu/src/main/java/com/niki914/libterm/backend/libsu/LibsuTerminalBackend.kt`、`libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuTerminalBackend.kt`、`libterm-backend-ssh/src/main/java/com/niki914/libterm/backend/ssh/SshTerminalBackend.kt`。

## 状态判断

| 能力 / 链路 | 状态 | 源码证据 | 边界 |
|:--|:--|:--|:--|
| backend SPI | `Stable` | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalBackend.kt` | 约束 `start`、`send`、`close`、`awaitExit`、`output` |
| libsu backend | `Stable` | `libterm-backend-libsu/src/main/java/com/niki914/libterm/backend/libsu/LibsuTerminalBackend.kt` | 面向 `User` 和 `Su`，通过 libsu shell 适配层提供输出与关闭 |
| Shizuku backend | `Stable` | `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuTerminalBackend.kt` | 固定绑定 `Shizuku` 身份，并通过 clientFactory 建立连接 |
| SSH backend | `Stable` | `libterm-backend-ssh/src/main/java/com/niki914/libterm/backend/ssh/SshTerminalBackend.kt` | 固定绑定 `Ssh` 身份，通过 JSch shell 适配层建立远端会话 |
| Shizuku IPC 接口 | `Stable` | `libterm-backend-shizuku/src/main/aidl/com/niki914/libterm/backend/shizuku/ILibTermShizukuShellService.aidl`, `libterm-backend-shizuku/src/main/aidl/com/niki914/libterm/backend/shizuku/ILibTermShizukuShellCallback.aidl` | 只确认到 AIDL 服务和回调入口，不展开 internal 实现细节 |

## 关键源码

### `libterm-core/src/main/kotlin/com/niki914/libterm/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `TerminalBackend.kt` | `TerminalBackend` | 约束 backend 最小接口 |

### `libterm-backend-libsu/src/main/java/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `libterm-backend-libsu/src/main/java/com/niki914/libterm/backend/libsu/LibsuTerminalBackend.kt` | `LibsuTerminalBackend` | 启动 libsu shell、收集输出并等待退出 |
| `libterm-backend-libsu/src/main/java/com/niki914/libterm/backend/libsu/RealLibsuShellAdapter.kt` | `RealLibsuShellAdapter` | 对接真实 libsu shell 适配层 |

### `libterm-backend-shizuku/src/main/java/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuTerminalBackend.kt` | `ShizukuTerminalBackend` | 启动 client、收集输出并等待退出 |

### `libterm-backend-ssh/src/main/java/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `libterm-backend-ssh/src/main/java/com/niki914/libterm/backend/ssh/SshTerminalBackend.kt` | `SshTerminalBackend` | 启动 SSH shell、桥接输出并等待退出 |
| `libterm-backend-ssh/src/main/java/com/niki914/libterm/backend/ssh/JschSshShellAdapterFactory.kt` | `JschSshShellAdapterFactory` | 使用 JSch 建立会话、配置 host key 策略和认证方式 |
| `libterm-backend-ssh/src/main/java/com/niki914/libterm/backend/ssh/JschFailureMapper.kt` | `JschFailureMapper` | 把 JSch / 网络异常映射成统一 `TerminalFailure` |

### `libterm-backend-shizuku/src/main/aidl/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `libterm-backend-shizuku/src/main/aidl/com/niki914/libterm/backend/shizuku/ILibTermShizukuShellService.aidl` | `ILibTermShizukuShellService` | Shizuku 服务接口 |
| `libterm-backend-shizuku/src/main/aidl/com/niki914/libterm/backend/shizuku/ILibTermShizukuShellCallback.aidl` | `ILibTermShizukuShellCallback` | Shizuku 输出回调接口 |

## 核心链路

1. `TerminalBackend.kt` 规定 session 依赖的 backend 最小契约。
2. `LibsuTerminalBackend.kt`、`ShizukuTerminalBackend.kt` 和 `SshTerminalBackend.kt` 各自实现同一套启动、发送、关闭和等待退出接口。
3. `RuntimeBackendFactory.kt` 把 identity 映射到具体 backend 实现。

## 调试 / 阅读关注点

| 问题 | 先看 | 判断标准 |
|:--|:--|:--|
| 三个 backend 是否共享同一套 SPI | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalBackend.kt` | 是否都实现同名生命周期接口 |
| `User` 与 `Su` 为什么共用同一个 backend 类 | `libterm-backend-libsu/src/main/java/com/niki914/libterm/backend/libsu/LibsuTerminalBackend.kt` | 是否只靠 identity 区分打开的 shell |
| Shizuku 链路是不是走 IPC | `libterm-backend-shizuku/src/main/aidl/com/niki914/libterm/backend/shizuku/ILibTermShizukuShellService.aidl` | 是否存在独立服务与回调接口 |
| SSH 失败为什么会落到不同 failure 类型 | `libterm-backend-ssh/src/main/java/com/niki914/libterm/backend/ssh/JschFailureMapper.kt` | 是否区分认证失败、host key 失败、连接失败和 channel 失败 |

## 与其他页面关系

| 相关页面 | 关系 |
|:--|:--|
| `architecture/runtime-core-backend.md` | 说明 backend 是如何被默认装配选中的 |
| `domains/identities/index.md` | 说明每个 backend 前面的 availability 与 authorization 条件 |
| `reference/source-map.md` | 提供 backend 相关完整源码入口 |

## 缺口

| 缺口 | 当前状态 | 下一步 |
|:--|:--|:--|
| SSH 认证扩展和 host key 策略的专题说明 | `Unverified` | 如果后续需要更细文档，再继续核对 `SshAuth`、`SshHostKeyPolicy` 与 JSch 适配层 |

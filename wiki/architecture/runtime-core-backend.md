# Runtime Core Backend

## 定位

本页回答默认装配怎么从 runtime 走到 core，再落到具体 backend。主要入口是 `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt`、`libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimeBackendFactory.kt` 和 `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalManager.kt`。

## 状态判断

| 能力 / 链路 | 状态 | 源码证据 | 边界 |
|:--|:--|:--|:--|
| 默认 manager 装配 | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt` | runtime 层负责拼装 provider、authorizer、idGenerator、clock 和 backendFactory |
| backend 分流 | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimeBackendFactory.kt` | `User` / `Su` 走 libsu，`Shizuku` 走 Shizuku backend |
| open 主链路 | `Stable` | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalManager.kt` | 先做 availability 判断，再做授权处理，最后创建 session |
| manager 行为回归验证 | `Stable` | `libterm-core/src/test/kotlin/com/niki914/libterm/TerminalManagerTest.kt` | 覆盖授权分支、失败分支、注册顺序和 close 行为 |

## 关键源码

### `libterm-runtime/src/main/java/com/niki914/libterm/runtime/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `LibTerm.kt` | `createDefaultManager` | 拼装默认 manager |
| `LibTerm.kt` | `newUserTerm` / `newSuTerm` / `newShizukuTerm` | 创建三类 `Term` |

### `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `RuntimeBackendFactory.kt` | `create` | 按 identity 分流到 libsu 或 Shizuku backend |

### `libterm-core/src/main/kotlin/com/niki914/libterm/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `TerminalManager.kt` | `open` | 承接 availability、authorization 和 session 创建 |
| `TerminalManager.kt` | `close` / `get` / `list` | 维护 session 注册表 |

## 核心链路

1. `LibTerm.kt` 创建 `TerminalManager` 或直接创建三类 `Term`。
2. `RuntimeBackendFactory.kt` 根据 `TerminalIdentity` 选择 libsu 或 Shizuku backend。
3. `TerminalManager.kt` 在 `open` 内完成 availability 判断、授权分支处理和 `TerminalSession` 注册。

## 调试 / 阅读关注点

| 问题 | 先看 | 判断标准 |
|:--|:--|:--|
| 默认 manager 里有哪些依赖被拼进去 | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt` | 是否已经把 provider、authorizer、backendFactory 一次性装好 |
| identity 到 backend 的映射在哪里定死 | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimeBackendFactory.kt` | 是否只有一个集中分流点 |
| open 失败为什么没有注册 session | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalManager.kt` | 失败是否发生在 availability、authorization 或 backend start 之前 |

## 与其他页面关系

| 相关页面 | 关系 |
|:--|:--|
| `overview/project-overview.md` | 提供项目级模块定位 |
| `domains/identities/index.md` | 展开 availability 与 authorization 的身份差异 |
| `domains/backends/index.md` | 展开 backend SPI 与两个具体实现 |
| `reference/source-map.md` | 提供完整入口路径地图 |

## 缺口

| 缺口 | 当前状态 | 下一步 |
|:--|:--|:--|
| 非默认装配场景下的外部自定义 wiring 说明 | `Unverified` | 如果需要扩展文档，再核对外部是否存在自定义 manager 使用样式 |

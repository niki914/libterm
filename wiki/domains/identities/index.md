# Identities

## 定位

本页回答 `USER`、`ROOT`、`SHIZUKU`、`SSH` 四类身份分别走哪条 availability 和 authorization 路径。主要入口是 `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalIdentity.kt`、`libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimePrivilegeProvider.kt`、`libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimePrivilegeAuthorizer.kt`。

## 状态判断

| 能力 / 链路 | 状态 | 源码证据 | 边界 |
|:--|:--|:--|:--|
| identity 枚举 | `Stable` | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalIdentity.kt` | 包含 `User`、`Su`、`Shizuku`、`Ssh` 四类身份 |
| runtime 层 availability 分发 | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimePrivilegeProvider.kt` | `User` / `Su` 走 libsu provider，`Shizuku` 走 shizuku provider，`Ssh` 直接返回 available |
| runtime 层 authorization 分发 | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimePrivilegeAuthorizer.kt` | `User` / `Su` 复用 libsu availability 结果，`Shizuku` 走专门 authorizer，`Ssh` 直接 granted |
| Shizuku 授权请求 | `Stable` | `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuPrivilegeAuthorizer.kt` | 包含 binder alive 检查、权限请求和超时处理 |
| Shizuku Provider 注册 | `Stable` | `libterm-backend-shizuku/src/main/AndroidManifest.xml` | 清单中注册 `rikka.shizuku.ShizukuProvider` |

## 关键源码

### `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `RuntimePrivilegeProvider.kt` | `getAvailability` | runtime 层分发 availability 判断 |
| `RuntimePrivilegeAuthorizer.kt` | `requestAuthorization` | runtime 层分发 authorization 请求 |

### `libterm-backend-libsu/src/main/java/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `libterm-backend-libsu/src/main/java/com/niki914/libterm/backend/libsu/LibsuPrivilegeProvider.kt` | `getAvailability` | `User` 永远可用，`Su` 依赖 root access 检查 |

### `libterm-backend-shizuku/src/main/java/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuPrivilegeProvider.kt` | `getAvailability` | 判断 Shizuku 是否已安装、运行和授权 |
| `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuPrivilegeAuthorizer.kt` | `requestAuthorization` | 请求授权并处理超时或拒绝 |

## 核心链路

1. `TerminalIdentity.kt` 定义 `User`、`Su`、`Shizuku`、`Ssh` 四个身份。
2. `RuntimePrivilegeProvider.kt` 先按身份把 availability 判断路由到 libsu provider、Shizuku provider，或直接返回 SSH 可用。
3. `RuntimePrivilegeAuthorizer.kt` 再把授权请求路由到 libsu availability 结果、`ShizukuPrivilegeAuthorizer.kt`，或直接给出 SSH 已授权。

## 调试 / 阅读关注点

| 问题 | 先看 | 判断标准 |
|:--|:--|:--|
| 为什么 `User` 不需要额外授权 | `libterm-backend-libsu/src/main/java/com/niki914/libterm/backend/libsu/LibsuPrivilegeProvider.kt` | `TerminalIdentity.User` 是否直接返回 available |
| 为什么 `Shizuku` 会先报 unavailable 再报 denied | `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuPrivilegeProvider.kt` | 是否区分未安装/未运行与未授权两种状态 |
| Shizuku 权限请求从哪里发起 | `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuPrivilegeAuthorizer.kt` | 是否通过 requester 注册 listener、发请求并等待回调 |
| 为什么 `Ssh` 不走本地权限申请 | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimePrivilegeProvider.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimePrivilegeAuthorizer.kt` | 是否在 runtime 层直接视为 available / granted |

## 与其他页面关系

| 相关页面 | 关系 |
|:--|:--|
| `overview/project-overview.md` | 提供对外 API 和错误模型背景 |
| `domains/backends/index.md` | 展开身份最终落到的 backend 实现 |
| `reference/source-map.md` | 提供完整身份相关源码入口 |

## 缺口

| 缺口 | 当前状态 | 下一步 |
|:--|:--|:--|
| Shizuku internal requester 和 access checker 的更细粒度行为说明 | `Unverified` | 若后续需要进一步拆分授权专题，再继续核对 internal 包实现 |

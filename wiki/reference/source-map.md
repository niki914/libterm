# Source Map

> 本页是源码入口地图。它只做两件事：按公共前缀聚合路径；把每个高频入口和对应 wiki 页面连起来。

## 根配置

- `settings.gradle.kts`: 定义模块图；配合 `overview/project-overview.md` 使用。
- `build.gradle.kts`: 定义根级 group、version 和通用构建约束；配合 `overview/project-overview.md` 使用。
- `gradle/libs.versions.toml`: 定义 AGP、Kotlin、coroutines、libsu、Shizuku 版本；配合 `overview/project-overview.md` 使用。

## 核心域模型

### `libterm-core/src/main/kotlin/com/niki914/libterm/`
- `TerminalManager.kt`: 会话打开、鉴权分流和注册入口；对应 `architecture/runtime-core-backend.md`。
- `TerminalSession.kt`: 会话生命周期、输出缓冲和退出等待；对应 `architecture/command-execution.md`。
- `TerminalBackend.kt`: backend 最小契约；对应 `domains/backends/index.md`。
- `TerminalIdentity.kt`: 四类身份枚举；对应 `domains/identities/index.md`。
- `TerminalFailure.kt`: 失败类型定义；对应 `overview/project-overview.md`。

### `libterm-core/src/test/kotlin/com/niki914/libterm/`
- `TerminalManagerTest.kt`: manager 授权分支、注册顺序和关闭行为验证；对应 `architecture/runtime-core-backend.md`。
- `TerminalSessionTest.kt`: session 生命周期和 buffer 行为验证；对应 `architecture/command-execution.md`。

## 运行时门面

### `libterm-runtime/src/main/java/com/niki914/libterm/runtime/`
- `LibTerm.kt`: 对外创建 `Term` 和默认 manager 的门面；对应 `overview/project-overview.md`。
- `Term.kt`: 公开 API 边界；对应 `overview/project-overview.md`。
- `CommandResult.kt`: `exec` 结果模型；对应 `architecture/command-execution.md`。
- `TermResult.kt`: 统一成功/失败壳；对应 `overview/project-overview.md`。
- `LibTermRuntime.kt`: 打开并包装 `LibTermSession`；对应 `architecture/command-execution.md`。
- `LibTermSession.kt`: 绑定文本输出流水线，并把 `exec` 串到命令执行器；对应 `architecture/command-execution.md`。

### `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/`
- `DefaultTerm.kt`: `open`、`exec`、`write` 的会话桥接逻辑；对应 `architecture/command-execution.md`。
- `TerminalCommandExecutor.kt`: `exec` 的 marker 注入、退出码解析和超时处理；对应 `architecture/command-execution.md`。
- `RuntimeBackendFactory.kt`: 默认 manager 的 backend 分流点；对应 `architecture/runtime-core-backend.md`。
- `RuntimePrivilegeProvider.kt`: runtime 层统一分发可用性判断；对应 `domains/identities/index.md`。
- `RuntimePrivilegeAuthorizer.kt`: runtime 层统一分发授权请求；对应 `domains/identities/index.md`。

## libsu backend

### `libterm-backend-libsu/src/main/java/com/niki914/libterm/backend/libsu/`
- `LibsuTerminalBackend.kt`: `USER` 和 `ROOT` 的 shell backend 实现；对应 `domains/backends/index.md`。
- `LibsuPrivilegeProvider.kt`: `User` / `Su` 可用性判断；对应 `domains/identities/index.md`。
- `LibsuRootAccessChecker.kt`: root 可达性检查；对应 `domains/identities/index.md`。
- `RealLibsuShellAdapter.kt`: 真实 libsu shell 适配层；对应 `domains/backends/index.md`。

## Shizuku backend

### `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/`
- `ShizukuTerminalBackend.kt`: Shizuku backend 的 client 生命周期和输出桥接；对应 `domains/backends/index.md`。
- `ShizukuPrivilegeProvider.kt`: Shizuku 可用性判断；对应 `domains/identities/index.md`。
- `ShizukuPrivilegeAuthorizer.kt`: Shizuku 授权请求和超时处理；对应 `domains/identities/index.md`。

### `libterm-backend-shizuku/src/main/`
- `AndroidManifest.xml`: 注册 `rikka.shizuku.ShizukuProvider`；对应 `domains/identities/index.md`。

### `libterm-backend-shizuku/src/main/aidl/com/niki914/libterm/backend/shizuku/`
- `ILibTermShizukuShellService.aidl`: Shizuku 服务接口入口；对应 `domains/backends/index.md`。
- `ILibTermShizukuShellCallback.aidl`: Shizuku 回调接口入口；对应 `domains/backends/index.md`。

## SSH backend

### `libterm-backend-ssh/src/main/java/com/niki914/libterm/backend/ssh/`
- `SshTerminalBackend.kt`: SSH backend 的会话生命周期和输出桥接；对应 `domains/backends/index.md`。
- `JschSshShellAdapterFactory.kt`: 使用 JSch 建立 shell、配置认证和 host key 策略；对应 `domains/backends/index.md`。
- `JschFailureMapper.kt`: 把网络 / JSch 异常映射成统一 `TerminalFailure`；对应 `domains/backends/index.md`。

## Demo 与验证

### `shell-smoke-app/src/main/`
- `AndroidManifest.xml`: launcher 和广播接收器入口；对应 `domains/demo-and-verification/index.md`。

### `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/`
- `MainActivity.kt`: 四类身份的交互式 smoke UI，其中包含 SSH 配置区；对应 `domains/demo-and-verification/index.md`。
- `AdbCommandReceiver.kt`: 通过广播触发 `exec` 并返回 JSON 结果；对应 `domains/demo-and-verification/index.md`。

## 入口索引

| 入口类型 | 推荐路径 | 说明 |
|:--|:--|:--|
| 工程模块图 | `settings.gradle.kts` | 先确定模块边界，再跳转到具体主题页 |
| 公开 API | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt` | 对外入口和默认装配入口 |
| manager 主链路 | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalManager.kt` | open/close、鉴权和会话注册 |
| 命令执行桥接 | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/TerminalCommandExecutor.kt` | `exec` 的 marker 处理、退出码解析和 timeout 语义 |
| backend SPI | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalBackend.kt` | backend 最小契约 |
| demo 验证 | `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/MainActivity.kt` | 手工验证和观察交互 |

## 未核实线索

| 线索 | 暂定状态 | 需要核对的路径 |
|:--|:--|:--|
| Shizuku internal client 细节 | `Unverified` | `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/` |

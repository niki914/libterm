# libterm

一个面向 Android 的终端会话库，基于 libsu 与 Shizuku 提供普通用户、Root、Shizuku 三类 shell 会话。
提供统一 `Term` 门面，既支持阻塞式 `exec`，也支持面向终端模拟器的流式 `write + stream` 交互。

## Demo

本仓库包含一个可运行的 smoke demo：

- [`shell-smoke-app`](./shell-smoke-app)

Demo 会创建 `USER`、`ROOT`、`SHIZUKU` 三个 `Term`，展示显式 `open()`、流式输出渲染和交互式命令输入。

## 对外门面

推荐应用层从 `libterm-runtime` 的 `LibTerm` 进入：

```kotlin
val user = LibTerm.newUserTerm()
val su = LibTerm.newSuTerm()
val shizuku = LibTerm.newShizukuTerm(applicationContext)
```

三个入口的语义：

- `LibTerm.newUserTerm()`：普通 shell，不需要 `Context`
- `LibTerm.newSuTerm()`：root shell，不需要 `Context`
- `LibTerm.newShizukuTerm(context)`：Shizuku shell，必须传 `Context`

`Term` 是新的主门面：

- `open()`：显式打开长期 shell 会话
- `exec(command, timeoutMillis)`：在当前 shell 上执行一条命令，并等待完成或超时
- `write(text)` / `write(bytes)`：向长期 shell 输入流写入数据
- `stream`：持续输出流，适合终端模拟器 UI
- `state`：暴露会话生命周期状态

如果你需要完全自定义后端、权限检查、ID 生成或时钟，也可以继续直接使用 `libterm-core` 的 `TerminalManager` / `TerminalSession`。

## Features

- 多后端身份：`USER`、`ROOT`、`SHIZUKU`
- Android 默认运行时门面：`LibTerm.newUserTerm` / `newSuTerm` / `newShizukuTerm`
- 双模式 API：阻塞式 `exec` + 流式 `write + stream`
- 超时语义：`exec` 支持 `timeoutMillis`，超时时返回当前已流出的部分内容
- 会话生命周期：`Starting`、`Running`、`Closed`、`Failed`
- 输出缓冲：底层仍按 chunk 数和字节数保留最近输出
- 错误模型：打开、发送、运行时退出均返回结构化 `TerminalFailure`
- 可测试核心：`libterm-core` 不依赖 Android，可注入 fake backend/provider

## 模块

| Module | 作用 |
|---|---|
| `libterm-core` | 核心 API、会话管理、状态、错误、抽象后端 |
| `libterm-backend-libsu` | 基于 libsu 的 `USER` / `ROOT` shell 后端 |
| `libterm-backend-shizuku` | 基于 Shizuku 的 shell 后端 |
| `libterm-runtime` | Android 默认门面，组装 core + libsu + Shizuku |
| `shell-smoke-app` | 可运行 demo app |

## Installation

### 源码依赖

将本仓库作为 Gradle composite build 或子模块接入后，应用模块依赖 `libterm-runtime`：

```kotlin
dependencies {
    implementation(project(":libterm-runtime"))
}
```

`libterm-runtime` 会通过 `api(project(":libterm-core"))` 暴露核心 API，并在内部依赖 libsu 与 Shizuku 后端。

### Gradle 配置要求

当前工程配置：

| Field | Value |
|---|---|
| `minSdk` | `26` |
| `compileSdk` | `35` |
| Java/Kotlin target | `17` |
| Kotlin | `2.1.0` |
| Coroutines | `1.10.2` |
| libsu | `6.0.0` |
| Shizuku | `13.1.5` |

如果发布到 JitPack 或 Maven，请以发布产物的实际坐标为准。当前仓库内版本为 `0.0.1-SNAPSHOT`。

## Quick Start

主入口是 `Term`。简单调用方直接使用 `exec`，终端型调用方使用 `open + stream + write`。

```kotlin
import com.niki914.libterm.runtime.TermResult
import com.niki914.libterm.runtime.LibTerm
import kotlinx.coroutines.launch

val term = LibTerm.newUserTerm()

launch {
    when (val result = term.exec("pwd")) {
        is TermResult.Success -> {
            println(result.value.stdout.toByteArray().decodeToString())
        }

        is TermResult.Failure -> {
            println("Exec failed: ${result.failure.message}")
        }
    }
}
```

交互式调用：

```kotlin
import com.niki914.libterm.runtime.TermResult
import com.niki914.libterm.runtime.LibTerm
import kotlinx.coroutines.launch

val term = LibTerm.newShizukuTerm(applicationContext)

launch {
    when (val result = term.open()) {
        is TermResult.Success -> {
            launch {
                term.stream.collect { chunk ->
                    print(chunk.bytes.toByteArray().decodeToString())
                }
            }

            term.write("whoami\n")
            term.write("pwd\n")
        }

        is TermResult.Failure -> {
            println("Open failed: ${result.failure.message}")
        }
    }
}
```

注意：

- `exec` 在当前 shell 上执行命令，不会自动关闭终端，也不会重置工作目录或环境变量
- `exec` 超时时会返回当前已经流出的 `stdout` / `stderr`，并把 `timedOut` 置为 `true`
- `write` 适合 `top`、`sh`、REPL、终端模拟器等长期交互场景
- `stream` 是实时输出流；如果你只需要简单结果，优先使用 `exec`

## 打开不同身份

```kotlin
val user = LibTerm.newUserTerm()
val root = LibTerm.newSuTerm()
val shizuku = LibTerm.newShizukuTerm(applicationContext)
```

三类身份语义：

| Identity | 后端 | 说明 |
|---|---|---|
| `USER` | libsu | 普通 shell，不需要 root 授权 |
| `ROOT` | libsu | root shell，需要设备具备 root 能力并授权 |
| `SHIZUKU` | Shizuku | Shizuku shell，需要 Shizuku 已安装、运行并授权 |

## 权限模式

`Term` 默认按需请求权限：

- `newUserTerm()`：无需额外授权
- `newSuTerm()`：由 libsu 检查和请求 root 授权
- `newShizukuTerm(context)`：由 Shizuku 检查和请求授权

如果你需要 `CHECK_ONLY` 这类更细粒度的权限策略，请改用底层 `TerminalManager` API。

`TerminalManager.open` 默认使用 `AuthorizationMode.REQUEST_IF_NEEDED`：

```kotlin
val result = manager.open(
    identity = TerminalIdentity.SHIZUKU,
    authorizationMode = AuthorizationMode.REQUEST_IF_NEEDED,
)
```

可选模式：

| Mode | 行为 |
|---|---|
| `REQUEST_IF_NEEDED` | 后端未授权时尝试请求授权 |
| `CHECK_ONLY` | 只检查当前可用性，不触发授权请求 |

示例：

```kotlin
val result = manager.open(
    identity = TerminalIdentity.ROOT,
    authorizationMode = AuthorizationMode.CHECK_ONLY,
)
```

## 会话输出

`TerminalSession` 会把后端输出缓存为 `OutputChunk`：

```kotlin
val chunks = session.latest(limit = 128)

val text = chunks.joinToString(separator = "") { chunk ->
    chunk.bytes.toByteArray().decodeToString()
}
```

`OutputChunk` 包含：

```kotlin
data class OutputChunk(
    val stream: OutputStream,
    val bytes: TerminalBytes,
    val timestampMillis: Long,
)
```

`stream` 表示输出来源：

- `OutputStream.STDOUT`
- `OutputStream.STDERR`

默认缓冲配置：

```kotlin
TerminalBufferConfig(
    maxChunkCount = 256,
    maxByteCount = 65_536,
)
```

可在创建默认 manager 时调整：

```kotlin
val manager = LibTerm.createDefaultManager(
    context = applicationContext,
    scope = managerScope,
    bufferConfig = TerminalBufferConfig(
        maxChunkCount = 512,
        maxByteCount = 256 * 1024,
    ),
)
```

## 会话状态

`TerminalSession.state` 是 `StateFlow<SessionState>`：

```kotlin
session.state.collect { state ->
    when (state) {
        SessionState.Starting -> showStatus("starting")
        SessionState.Running -> showStatus("running")
        SessionState.Closed -> showStatus("closed")
        is SessionState.Failed -> showStatus("failed: ${state.failure.message}")
    }
}
```

状态定义：

```kotlin
sealed interface SessionState {
    data object Starting : SessionState
    data object Running : SessionState
    data object Closed : SessionState
    data class Failed(val failure: TerminalFailure) : SessionState
}
```

## TerminalManager API

```kotlin
class TerminalManager {
    suspend fun open(
        identity: TerminalIdentity,
        authorizationMode: AuthorizationMode = AuthorizationMode.REQUEST_IF_NEEDED,
    ): OpenResult<TerminalSession>

    suspend fun close(id: String): Boolean
    fun get(id: String): TerminalSession?
    fun list(): List<TerminalSession>
}
```

- `open`：检查后端可用性和权限，成功后启动并注册会话
- `close`：按会话 ID 关闭并从 manager 移除
- `get`：按会话 ID 查询当前已注册会话
- `list`：返回当前已注册会话快照

## TerminalSession API

```kotlin
class TerminalSession {
    val id: String
    val identity: TerminalIdentity
    val state: StateFlow<SessionState>
    val currentState: SessionState

    fun latest(limit: Int): List<OutputChunk>
    suspend fun send(input: TerminalBytes): SendResult
    suspend fun send(bytes: ByteArray): SendResult
    suspend fun close(): SessionState
}
```

- `latest(limit)`：读取最近 `limit` 个输出 chunk，返回快照
- `send(input)`：向 shell 写入输入，通常命令需要自行追加 `\n`
- `send(bytes)`：`ByteArray` 便捷重载
- `close()`：关闭后端并等待会话进入终态

## 错误模型

打开会话失败返回 `OpenResult.Failure`：

```kotlin
when (val result = manager.open(TerminalIdentity.ROOT)) {
    is OpenResult.Success -> use(result.value)
    is OpenResult.Failure -> handle(result.failure)
}
```

发送输入失败返回 `SendResult.Failed`：

```kotlin
when (val result = session.send("id\n".encodeToByteArray())) {
    SendResult.Sent -> Unit
    is SendResult.Failed -> handle(result.failure)
}
```

`TerminalFailure` 类型：

| Failure | 场景 |
|---|---|
| `BackendUnavailable` | 后端不可用，例如 Shizuku 未安装/未运行、Root 不可用 |
| `AuthorizationDenied` | 权限被拒绝 |
| `AuthorizationFailed` | 权限请求过程失败 |
| `StartupFailed` | shell 启动失败 |
| `RuntimeTerminated` | shell 运行中异常终止 |
| `AlreadyClosed` | 会话或后端已关闭，无法继续发送 |

## 关闭资源

建议在 Activity/ViewModel 销毁时关闭会话，并取消传给 manager 的 `CoroutineScope`：

```kotlin
override fun onDestroy() {
    val sessionIds = manager.list().map { it.id }

    managerScope.launch {
        sessionIds.forEach { id ->
            manager.close(id)
        }
        managerScope.cancel()
    }

    super.onDestroy()
}
```

如果你直接持有 `TerminalSession`，也可以调用：

```kotlin
session.close()
```

## FAQ

### 为什么 `send` 成功后没有立刻读到输出？

`send` 只表示输入已经写入 shell。命令执行和输出回传是异步的，调用方需要稍后读取 `latest(...)`，或持续刷新 UI。

### 命令为什么要追加换行？

shell 通常需要收到回车才会执行命令。因此发送文本命令时一般使用：

```kotlin
session.send(TerminalBytes.of("ls -la\n".encodeToByteArray()))
```

### Root 打不开通常是什么原因？

常见原因是设备没有 root、root 管理器拒绝授权，或当前环境无法通过 libsu 获取 root shell。此时 `open(TerminalIdentity.ROOT)` 会返回 `OpenResult.Failure`，失败原因在 `TerminalFailure.message` 中。

### Shizuku 打不开通常是什么原因？

常见原因是 Shizuku 未安装、未运行或未授权。`libterm-backend-shizuku` 会声明 `ShizukuProvider`，应用仍需要用户侧完成 Shizuku 启动和授权流程。

### 如何只检查权限，不弹授权？

使用 `AuthorizationMode.CHECK_ONLY`：

```kotlin
val result = manager.open(
    identity = TerminalIdentity.SHIZUKU,
    authorizationMode = AuthorizationMode.CHECK_ONLY,
)
```

### 输出会无限增长吗？

不会。`TerminalSession` 只保留最近输出，默认最多 `256` 个 chunk 或 `65_536` 字节。可以通过 `TerminalBufferConfig` 调整。

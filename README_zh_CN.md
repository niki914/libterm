<div align="right">

**[English](README.md)** | 中文

</div>

# libterm

一个面向 Android 的终端会话库，基于 libsu 与 Shizuku 提供 `USER`、`ROOT`、`SHIZUKU` 三类 shell 会话。
提供统一的 `Term` 门面，既支持阻塞式 `exec`，也支持面向终端模拟器的流式 `write + stream` 交互。

## Demo

本仓库包含一个可运行的 smoke demo：

- [`shell-smoke-app`](./shell-smoke-app)

Demo 会创建 `USER`、`ROOT`、`SHIZUKU` 三个 `Term`，展示显式 `open()`、流式输出渲染和交互式命令输入。

## 对外门面

推荐应用层从 `libterm-runtime` 的 `LibTerm` 进入：

```kotlin
val user = LibTerm.newUserTerm()
val root = LibTerm.newSuTerm()
val shizuku = LibTerm.newShizukuTerm(applicationContext)
```

门面入口语义：

- `LibTerm.newUserTerm()`：普通 shell，不需要 `Context`
- `LibTerm.newSuTerm()`：Root shell，不需要 `Context`
- `LibTerm.newShizukuTerm(context)`：Shizuku shell，需要传 `Context`
- `LibTerm.createDefaultManager(...)`：更底层的入口，适合自己管理 session

`Term` 是应用层主门面：

- `open()`：显式打开长期 shell 会话
- `exec(command, timeoutMillis)`：在当前 shell 上执行一条命令，并等待完成或超时
- `write(text)` / `write(bytes)`：向长期 shell 输入流写入数据
- `stream`：持续输出流，适合终端模拟器 UI
- `state`：暴露会话生命周期状态
- `close()`：关闭当前 term，并释放其持有的资源

如果你需要完全自定义后端、权限检查、ID 生成或时钟，也可以继续直接使用 `libterm-core` 的 `TerminalManager` / `TerminalSession`。

## Features

- 多身份支持：`USER`、`ROOT`、`SHIZUKU`
- Android 运行时门面：`LibTerm.newUserTerm` / `newSuTerm` / `newShizukuTerm`
- 双模式 API：阻塞式 `exec` + 流式 `write + stream`
- 超时语义：`exec` 支持 `timeoutMillis`，超时时返回当前已流出的部分内容
- 会话生命周期：`Starting`、`Running`、`Closed`、`Failed`
- 结构化错误模型：统一通过 `TerminalFailure` 表达失败原因
- 可测试核心：`libterm-core` 不依赖 Android，可注入 fake backend/provider

## Installation

### Gradle (JitPack)

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
        mavenCentral()
        google()
    }
}
```

```kotlin
dependencies {
    implementation("com.github.niki914.libterm:libterm-runtime:v1-0.1")
}
```

`libterm-runtime` 会对 Android 调用方暴露核心 API，并在内部组装 libsu 与 Shizuku 后端。

## Quick Start

主入口是 `Term`。简单调用方直接使用 `exec`，终端型调用方使用 `open + stream + write`。

```kotlin
import com.niki914.libterm.runtime.LibTerm
import com.niki914.libterm.runtime.TermResult
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
import com.niki914.libterm.runtime.LibTerm
import com.niki914.libterm.runtime.TermResult
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
| `USER` | libsu | 普通 shell，不需要 Root 授权 |
| `ROOT` | libsu | Root shell，需要设备具备 Root 能力并授权 |
| `SHIZUKU` | Shizuku | Shizuku shell，需要 Shizuku 已安装、运行并授权 |

## 权限模式

`Term` 默认按需请求权限：

- `newUserTerm()`：无需额外授权
- `newSuTerm()`：由 libsu 检查并在需要时请求 Root 授权
- `newShizukuTerm(context)`：由 Shizuku 检查并在需要时请求授权

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

如果你需要调整缓冲上限，可以自己创建 manager：

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

`Term.state` 和 `TerminalSession.state` 都暴露 `StateFlow<SessionState>`：

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

## Term API

```kotlin
interface Term {
    val state: StateFlow<SessionState>
    val stream: Flow<OutputChunk>

    suspend fun open(): TermResult<Unit>

    suspend fun exec(
        command: String,
        timeoutMillis: Long = DEFAULT_EXEC_TIMEOUT_MILLIS,
    ): TermResult<CommandResult>

    suspend fun write(text: String): TermResult<Unit>
    suspend fun write(bytes: ByteArray): TermResult<Unit>
    suspend fun close(): TermResult<Unit>
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

`Term` 层会继续把失败包装成 `TermResult.Failure`：

```kotlin
when (val result = term.exec("id")) {
    is TermResult.Success -> println(result.value.exitCode)
    is TermResult.Failure -> println(result.failure.message)
}
```

`TerminalFailure` 类型：

| Failure | 场景 |
|---|---|
| `BackendUnavailable` | 后端不可用，例如 Shizuku 未安装、未运行，或 Root 不可用 |
| `AuthorizationDenied` | 权限被明确拒绝 |
| `AuthorizationFailed` | 权限请求过程异常失败 |
| `StartupFailed` | shell 启动失败 |
| `RuntimeTerminated` | shell 运行中异常终止 |
| `AlreadyClosed` | 会话或后端已关闭 |

## 关闭资源

建议在 Activity 或 ViewModel 销毁时关闭会话，并取消传给 manager 的 `CoroutineScope`：

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

如果你直接持有 `TerminalSession` 或 `Term`，也可以显式关闭：

```kotlin
session.close()
term.close()
```

## FAQ

### 为什么 `send` 成功后没有立刻读到输出？

`send` 只表示输入已经写入 shell。命令执行和输出回传是异步的，调用方需要稍后读取 `latest(...)`，或者持续消费输出流。

### 命令为什么通常要追加换行？

shell 通常需要收到回车才会执行命令。因此发送文本命令时一般使用：

```kotlin
session.send(TerminalBytes.of("ls -la\n".encodeToByteArray()))
```

### Root 打不开通常是什么原因？

常见原因是设备没有 Root、Root 管理器拒绝授权，或者当前环境无法通过 libsu 获取 Root shell。此时 `open(TerminalIdentity.ROOT)` 会返回 `OpenResult.Failure`。

### Shizuku 打不开通常是什么原因？

常见原因是 Shizuku 未安装、未运行或未授权。后端可以发起授权流程，但前提仍然是用户侧已经具备可用的 Shizuku 运行环境。

### 如何只检查权限，不弹授权？

使用 `AuthorizationMode.CHECK_ONLY`：

```kotlin
val result = manager.open(
    identity = TerminalIdentity.SHIZUKU,
    authorizationMode = AuthorizationMode.CHECK_ONLY,
)
```

### 输出会无限增长吗？

不会。`TerminalSession` 只保留最近输出，缓冲是有上限的；如果你需要更大的窗口，可以通过 `TerminalBufferConfig` 调整。
EOF; __tr_native_ec=$?; pwd -P >| '/var/folders/_c/6lxjwz7n11b_n2934w__bt0c0000gn/T/agent-toolhost/jobs/job-7bdd573ec98346a192ab3dc42735dd08/cwd.txt'; exit "$__tr_native_ec"

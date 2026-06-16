<div align="right">

**[English](README.md)** | 中文

</div>

# libterm

一个面向 Android 的终端会话库，基于 libsu、Shizuku 与 SSH 后端，在统一的 Kotlin-first API 下提供 `USER`、`ROOT`、`SHIZUKU`、`SSH` 四类 shell 会话。

- `LibTerm.openUserTerm()` / `openSuTerm()` / `openShizukuTerm(context)` / `openSshTerm(...)` —— 单会话包装，上手最快。
- `LibTerm.runtime()` —— 多会话宿主，可同时管理多个 session。

## Features

- 多身份支持：`USER`、`ROOT`、`SHIZUKU`、`SSH`
- Kotlin-first 入口：`runtime.open { ... }` 与 `open*Term(...)`
- 多会话能力：`LibTermRuntime` 支持同时打开、列举、关闭多个 session
- 初始目录语义：`cwd` 只作用于打开时的初始工作目录
- 双模式交互：阻塞式 `exec` 与流式 `open + write + stream`
- 统一失败模型：通过 `TerminalFailure` 表达参数错误、鉴权失败和启动失败

## 快速开始

最快的方式是使用单会话包装。每个 `open*Term` 辅助入口都返回懒打开的 `Term`；首次调用 `open()`、`exec()` 或 `write()` 时才会真正打开 shell。

```kotlin
import com.niki914.libterm.runtime.LibTerm
import com.niki914.libterm.runtime.TermResult
import kotlinx.coroutines.launch

val term = LibTerm.openUserTerm(cwd = "/sdcard")

launch {
    when (val result = term.exec("pwd")) {
        is TermResult.Success -> {
            println(result.value.stdout.toByteArray().decodeToString())
        }

        is TermResult.Failure -> {
            println("exec failed: ${result.failure.message}")
        }
    }
}
```

如果你需要终端式交互而不是一次性命令：

```kotlin
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
            println("open failed: ${result.failure.message}")
        }
    }
}
```

按需选择身份：

```kotlin
val user = LibTerm.openUserTerm(cwd = "/sdcard")
val root = LibTerm.openSuTerm(cwd = "/data/local/tmp")
val shizuku = LibTerm.openShizukuTerm(
    context = applicationContext,
    cwd = "/sdcard/Download",
)
val ssh = LibTerm.openSshTerm(
    host = "192.168.1.10",
    username = "shell",
    password = "password",
)
```

仓库内提供了一个可运行的 smoke demo：[`shell-smoke-app`](./shell-smoke-app)。

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
    implementation("com.github.niki914.libterm:libterm-runtime:v4-0.4")
}
```

`libterm-runtime` 面向 Android 调用方暴露主要 API，并在内部组装 libsu、Shizuku 与 SSH 后端。

## 高级 API

### 多会话：`LibTerm.runtime()`

`LibTermRuntime` 是推荐的长期宿主，适合默认配置、多开和批量关闭。

```kotlin
import com.niki914.libterm.OpenResult
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.LibTerm
import kotlinx.coroutines.launch

val runtime = LibTerm.runtime(
    context = applicationContext,
) {
    defaultCwd = "/sdcard"
}

launch {
    val user = runtime.open {
        identity = TerminalIdentity.User
    }

    val root = runtime.open {
        identity = TerminalIdentity.Su
        cwd = "/data/local/tmp"
    }

    if (user is OpenResult.Success && root is OpenResult.Success) {
        val sessions = runtime.listSessions()
        println("opened sessions = ${sessions.map { it.id }}")
        runtime.closeAll()
    }
}
```

需要显式管理多个 session 时使用 `LibTerm.runtime()`；否则上面的 `open*Term()` 辅助入口已经够用。

### `cwd` 语义

- `cwd` 表示会话打开时的初始工作目录，不是单次 `exec()` 的临时目录。
- `exec()` 仍然复用当前 shell 会话，不会在每次执行前重置目录或环境变量。
- `cwd == null` 或空白字符串时保持默认行为。
- `cwd` 非空时必须是绝对路径；相对路径会在库层直接失败。
- 目录是否真的可进入，由目标权限上下文最终验证；因此 root-only 或 Shizuku-only 目录不会被 app 进程提前误判。

示例：

```kotlin
val result = runtime.open {
    identity = TerminalIdentity.User
    cwd = "/sdcard/Download"
}
```

底层对应的 core 参数是：

```kotlin
TerminalOpenOptions(cwd = "/sdcard/Download")
```

### 身份与权限模式

身份映射：

| Identity | 后端 | 说明 |
|---|---|---|
| `USER` | libsu | 普通 shell，不需要 Root 授权 |
| `ROOT` | libsu | Root shell，需要设备具备 Root 能力并授权 |
| `SHIZUKU` | Shizuku | Shizuku shell，需要 `context`、Shizuku 运行环境与授权 |
| `SSH` | SSH | 远端 SSH shell，需要在打开参数中提供 host / port / username / password |

默认权限模式是 `AuthorizationMode.REQUEST_IF_NEEDED`。如果你需要只检查、不触发授权流程：

```kotlin
val result = runtime.open {
    identity = TerminalIdentity.Shizuku
    authorizationMode = AuthorizationMode.CHECK_ONLY
}
```

### 低层 Core API

如果你需要自己持有 registry 或直接测试 core 层，仍然可以使用 `TerminalManager`：

```kotlin
class TerminalManager {
    suspend fun open(
        identity: TerminalIdentity,
        authorizationMode: AuthorizationMode = AuthorizationMode.REQUEST_IF_NEEDED,
        openOptions: TerminalOpenOptions = TerminalOpenOptions(),
    ): OpenResult<TerminalSession>

    suspend fun close(id: String): Boolean
    fun get(id: String): TerminalSession?
    fun list(): List<TerminalSession>
}
```

`openOptions.cwd` 的规则：

- 空白字符串会被归一化为默认行为
- 相对路径会返回 `TerminalFailure.InvalidOpenOptions`
- 合法绝对路径会继续透传给具体 backend

### 错误模型

常见 `TerminalFailure` 类型：

| Failure | 场景 |
|---|---|
| `BackendUnavailable` | 后端不可用，例如 Root 或 Shizuku 不可用 |
| `AuthorizationDenied` | 权限被明确拒绝 |
| `AuthorizationFailed` | 授权流程异常失败 |
| `InvalidOpenOptions` | `identity` 缺失、`cwd` 非绝对路径等调用参数错误 |
| `StartupFailed` | shell 启动失败 |
| `RuntimeTerminated` | shell 运行中异常终止 |
| `AlreadyClosed` | 会话或后端已关闭 |

### 资源关闭

- `Term.close()`：关闭单会话包装
- `LibTermSession.close()`：关闭某个已打开 session
- `LibTermRuntime.close(sessionId)`：按 ID 关闭
- `LibTermRuntime.closeAll()`：关闭当前 runtime 下所有活动会话

如果你把 `scope` 传给了 `LibTerm.runtime()`，仍然应该按宿主生命周期自行取消它。`open*Term()` 辅助入口持有内部 scope，关闭返回的 `Term` 时会释放。

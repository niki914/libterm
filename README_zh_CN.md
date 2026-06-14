<div align="right">

**[English](README.md)** | 中文

</div>

# libterm

一个面向 Android 的终端会话库，基于 libsu 与 Shizuku 提供 `USER`、`ROOT`、`SHIZUKU` 三类 shell 会话。
当前推荐以 Kotlin-first 的 `LibTerm.runtime()` / `LibTerm.term()` 为主入口；旧的 `newUserTerm()`、`newSuTerm()`、`newShizukuTerm()`、`createDefaultManager()` 仍保留，但已进入废弃迁移路径。

## Demo

本仓库包含一个可运行的 smoke demo：

- [`shell-smoke-app`](./shell-smoke-app)

## Features

- 多身份支持：`USER`、`ROOT`、`SHIZUKU`
- Kotlin-first 入口：`runtime.open { ... }` 与 `term { ... }`
- 多会话能力：`LibTermRuntime` 支持同时打开、列举、关闭多个 session
- 初始目录语义：`cwd` 只作用于打开时的初始工作目录
- 双模式交互：阻塞式 `exec` 与流式 `open + write + stream`
- 统一失败模型：通过 `TerminalFailure` 表达参数错误、鉴权失败和启动失败

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

`libterm-runtime` 面向 Android 调用方暴露主要 API，并在内部组装 libsu 与 Shizuku 后端。

## Runtime First API

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

### 单会话：`LibTerm.term()`

`term { ... }` 适合“拿一个交互式会话就用”的场景。

```kotlin
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.LibTerm
import com.niki914.libterm.runtime.TermResult
import kotlinx.coroutines.launch

val term = LibTerm.term {
    identity = TerminalIdentity.User
    cwd = "/sdcard"
}

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

## `cwd` 语义

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

## 身份与权限模式

身份映射：

| Identity | 后端 | 说明 |
|---|---|---|
| `USER` | libsu | 普通 shell，不需要 Root 授权 |
| `ROOT` | libsu | Root shell，需要设备具备 Root 能力并授权 |
| `SHIZUKU` | Shizuku | Shizuku shell，需要 `context`、Shizuku 运行环境与授权 |

默认权限模式是 `AuthorizationMode.REQUEST_IF_NEEDED`。如果你需要只检查、不触发授权流程：

```kotlin
val result = runtime.open {
    identity = TerminalIdentity.Shizuku
    authorizationMode = AuthorizationMode.CHECK_ONLY
}
```

## 迁移说明

推荐迁移关系：

| 旧入口 | 新入口 |
|---|---|
| `LibTerm.newUserTerm()` | `LibTerm.term { identity = TerminalIdentity.User }` |
| `LibTerm.newSuTerm()` | `LibTerm.term { identity = TerminalIdentity.Su }` |
| `LibTerm.newShizukuTerm(context)` | `LibTerm.term(context) { identity = TerminalIdentity.Shizuku }` |
| `LibTerm.createDefaultManager(...)` | `LibTerm.runtime(...)` |

旧入口仍可用，但都已标记为 `@Deprecated`。新的 README 示例默认不再围绕 `TerminalManager` 组织。

## 低层 Core API

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

## 错误模型

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

## 资源关闭

- `Term.close()`：关闭单会话包装
- `LibTermSession.close()`：关闭某个已打开 session
- `LibTermRuntime.close(sessionId)`：按 ID 关闭
- `LibTermRuntime.closeAll()`：关闭当前 runtime 下所有活动会话

如果你把 `scope` 传给了 `LibTerm.runtime()` 或 `LibTerm.term()`，仍然应该按宿主生命周期自行取消它。
EOF; __tr_native_ec=$?; pwd -P >| '/var/folders/_c/6lxjwz7n11b_n2934w__bt0c0000gn/T/agent-toolhost/jobs/job-7bdd573ec98346a192ab3dc42735dd08/cwd.txt'; exit "$__tr_native_ec"

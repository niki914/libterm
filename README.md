<div align="right">

English | **[中文](README_zh_CN.md)**

</div>

# libterm

An Android terminal session library built on top of libsu, Shizuku, and an SSH backend.
It provides `USER`, `ROOT`, `SHIZUKU`, and `SSH` shell sessions behind a single Kotlin-first API.

- `LibTerm.openUserTerm()` / `openSuTerm()` / `openShizukuTerm(context)` / `openSshTerm(...)` — single-session wrappers, the fastest way to get started.
- `LibTerm.runtime()` — multi-session host for managing several sessions at once.

## Features

- Multi-identity shells: `USER`, `ROOT`, `SHIZUKU`, `SSH`
- Kotlin-first runtime API: `runtime.open { ... }` and `open*Term(...)`
- Multi-session host via `LibTermRuntime`
- Initial working directory support via `cwd`
- Dual interaction model: blocking `exec` and streaming `open + write + stream`
- Structured failures through `TerminalFailure`

## Quick Start

The quickest path is a single-session wrapper. Each `open*Term` helper returns a lazy `Term`; the shell is opened on the first `open()`, `exec()`, or `write()` call.

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

For terminal-style interaction instead of one-shot commands:

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

Pick the identity you need:

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

A runnable smoke demo lives in [`shell-smoke-app`](./shell-smoke-app).

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
    implementation("com.github.niki914.libterm:libterm-runtime:v5-0.5")
}
```

`libterm-runtime` exposes the main Android-facing API and wires libsu, Shizuku, and SSH backends internally.

## Advanced API

### Multi-session: `LibTerm.runtime()`

`LibTermRuntime` is the recommended long-lived host for defaults, multi-open, session listing, and batch shutdown.

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

Use `LibTerm.runtime()` when you need to manage multiple sessions explicitly; otherwise the `open*Term()` helpers above are enough.

### `cwd` Semantics

- `cwd` is the initial working directory for opening a session, not a per-`exec()` override.
- `exec()` still reuses the current shell session and does not reset working directory or environment.
- `cwd == null` or blank keeps the backend default behavior.
- A non-empty `cwd` must be an absolute path; relative paths fail fast in the library.
- Actual directory accessibility is validated in the target privilege context, so root-only or Shizuku-only paths are not rejected prematurely in the app process.

Example:

```kotlin
val result = runtime.open {
    identity = TerminalIdentity.User
    cwd = "/sdcard/Download"
}
```

At the core layer this maps to:

```kotlin
TerminalOpenOptions(cwd = "/sdcard/Download")
```

### Identities And Authorization

Identity mapping:

| Identity | Backend | Description |
|---|---|---|
| `USER` | libsu | Regular shell without root authorization |
| `ROOT` | libsu | Root shell, requires root capability and authorization |
| `SHIZUKU` | Shizuku | Shizuku shell, requires `context`, a running Shizuku environment, and authorization |
| `SSH` | SSH | Remote SSH shell, requires SSH host / port / username / password in open options |

The default authorization mode is `AuthorizationMode.REQUEST_IF_NEEDED`. To check without prompting:

```kotlin
val result = runtime.open {
    identity = TerminalIdentity.Shizuku
    authorizationMode = AuthorizationMode.CHECK_ONLY
}
```

### Lower-level Core API

If you need direct registry control or want to test the core layer explicitly, `TerminalManager` is still available:

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

Rules for `openOptions.cwd`:

- blank input is normalized to default behavior
- relative paths return `TerminalFailure.InvalidOpenOptions`
- valid absolute paths continue down to the selected backend

### Error Model

Common `TerminalFailure` types:

| Failure | Scenario |
|---|---|
| `BackendUnavailable` | Backend is unavailable, for example root or Shizuku is not available |
| `AuthorizationDenied` | Authorization was explicitly denied |
| `AuthorizationFailed` | The authorization flow failed unexpectedly |
| `InvalidOpenOptions` | Caller input is invalid, such as missing `identity` or a relative `cwd` |
| `StartupFailed` | Shell startup failed |
| `RuntimeTerminated` | Shell terminated unexpectedly during runtime |
| `AlreadyClosed` | Session or backend is already closed |

### Closing Resources

- `Term.close()` closes the single-session wrapper
- `LibTermSession.close()` closes one opened session
- `LibTermRuntime.close(sessionId)` closes a specific session by ID
- `LibTermRuntime.closeAll()` closes all active sessions owned by the runtime

If you pass your own `scope` into `LibTerm.runtime()`, you still own that scope and should cancel it with your host lifecycle. `open*Term()` helpers own their internal scope and release it when the returned `Term` is closed.

<div align="right">

English | **[中文](README_zh_CN.md)**

</div>

# libterm

An Android terminal session library built on top of libsu and Shizuku.
It provides a unified `Term` facade for `USER`, `ROOT`, and `SHIZUKU` shells, supporting both blocking `exec` and streaming `write + stream` interaction.

## Demo

This repository contains a runnable smoke demo app:

- [`shell-smoke-app`](./shell-smoke-app)

The demo creates three terms for `USER`, `ROOT`, and `SHIZUKU`, then shows explicit `open()`, streaming output rendering, and interactive command input.

## Facade API

Start from `LibTerm` in `libterm-runtime`:

```kotlin
val user = LibTerm.newUserTerm()
val root = LibTerm.newSuTerm()
val shizuku = LibTerm.newShizukuTerm(applicationContext)
```

Facade entry points:

- `LibTerm.newUserTerm()`: regular shell, no `Context` required
- `LibTerm.newSuTerm()`: root shell, no `Context` required
- `LibTerm.newShizukuTerm(context)`: Shizuku shell, requires `Context`
- `LibTerm.createDefaultManager(...)`: lower-level entry when you need to manage sessions yourself

`Term` is the main runtime facade:

- `open()`: explicitly open a long-lived shell session
- `exec(command, timeoutMillis)`: run one command on the current shell and wait for completion or timeout
- `write(text)` / `write(bytes)`: write input into the current long-lived shell
- `stream`: observe live output chunks
- `state`: observe session lifecycle state
- `close()`: close the current term and release owned resources

## Features

- Multi-identity support: `USER`, `ROOT`, `SHIZUKU`
- Android runtime facade: `LibTerm.newUserTerm`, `newSuTerm`, `newShizukuTerm`
- Dual interaction model: blocking `exec` and streaming `write + stream`
- Timeout-aware command execution with partial output preserved on timeout
- Session lifecycle tracking via `Starting`, `Running`, `Closed`, and `Failed`
- Structured error model via `TerminalFailure`
- Testable core in `libterm-core` with backend/provider abstractions

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

`libterm-runtime` exposes the core API and wires libsu + Shizuku backends for Android callers.

## Quick Start

The main entry is `Term`. For simple callers, use `exec`. For terminal-style interaction, use `open + stream + write`.

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

Interactive usage:

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

Notes:

- `exec` runs on the current shell session and does not reset working directory or environment
- `exec` returns partial `stdout` / `stderr` when `timeoutMillis` is reached, with `timedOut = true`
- `write` is intended for long-lived interactive workloads such as `sh`, `top`, REPLs, or terminal UIs
- `stream` is the live output flow; prefer `exec` when you only need a command result

## Open Different Identities

```kotlin
val user = LibTerm.newUserTerm()
val root = LibTerm.newSuTerm()
val shizuku = LibTerm.newShizukuTerm(applicationContext)
```

Identity mapping:

| Identity | Backend | Description |
|---|---|---|
| `USER` | libsu | Regular shell without root authorization |
| `ROOT` | libsu | Root shell; requires root capability and authorization |
| `SHIZUKU` | Shizuku | Shizuku shell; requires Shizuku to be installed, running, and authorized |

## Permission Modes

`Term` uses request-if-needed authorization behavior by default:

- `newUserTerm()`: no extra authorization
- `newSuTerm()`: libsu checks and requests root authorization when needed
- `newShizukuTerm(context)`: Shizuku checks and requests authorization when needed

If you need finer control such as `CHECK_ONLY`, use the lower-level `TerminalManager` API:

```kotlin
val result = manager.open(
    identity = TerminalIdentity.SHIZUKU,
    authorizationMode = AuthorizationMode.REQUEST_IF_NEEDED,
)
```

Available modes:

| Mode | Behavior |
|---|---|
| `REQUEST_IF_NEEDED` | Requests authorization if the backend is not yet authorized |
| `CHECK_ONLY` | Checks availability only and never triggers an authorization request |

Example:

```kotlin
val result = manager.open(
    identity = TerminalIdentity.ROOT,
    authorizationMode = AuthorizationMode.CHECK_ONLY,
)
```

## Output Stream

`TerminalSession` buffers backend output as `OutputChunk`:

```kotlin
val chunks = session.latest(limit = 128)

val text = chunks.joinToString(separator = "") { chunk ->
    chunk.bytes.toByteArray().decodeToString()
}
```

`OutputChunk`:

```kotlin
data class OutputChunk(
    val stream: OutputStream,
    val bytes: TerminalBytes,
    val timestampMillis: Long,
)
```

`stream` indicates the source:

- `OutputStream.STDOUT`
- `OutputStream.STDERR`

To customize buffer limits, build a manager manually:

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

## Session State

`Term.state` and `TerminalSession.state` expose `StateFlow<SessionState>`:

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

State definition:

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

- `open`: checks backend availability and authorization, then starts and registers a session
- `close`: closes a session by ID and removes it from the manager registry
- `get`: returns the current session by ID
- `list`: returns a snapshot of registered sessions

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

- `latest(limit)`: returns the latest buffered output chunks
- `send(input)`: writes input into the shell; callers usually need to append `\n` themselves
- `send(bytes)`: convenience overload for raw bytes
- `close()`: closes the backend and waits for the terminal state to settle

## Error Model

Opening failures return `OpenResult.Failure`:

```kotlin
when (val result = manager.open(TerminalIdentity.ROOT)) {
    is OpenResult.Success -> use(result.value)
    is OpenResult.Failure -> handle(result.failure)
}
```

Send failures return `SendResult.Failed`:

```kotlin
when (val result = session.send("id\n".encodeToByteArray())) {
    SendResult.Sent -> Unit
    is SendResult.Failed -> handle(result.failure)
}
```

`Term` methods wrap failures as `TermResult.Failure`:

```kotlin
when (val result = term.exec("id")) {
    is TermResult.Success -> println(result.value.exitCode)
    is TermResult.Failure -> println(result.failure.message)
}
```

`TerminalFailure` types:

| Failure | Scenario |
|---|---|
| `BackendUnavailable` | Backend is unavailable, for example Shizuku is not installed or root is not available |
| `AuthorizationDenied` | Authorization was explicitly denied |
| `AuthorizationFailed` | Authorization flow failed unexpectedly |
| `StartupFailed` | Shell startup failed |
| `RuntimeTerminated` | Shell terminated unexpectedly during runtime |
| `AlreadyClosed` | Session or backend is already closed |

## Closing Resources

Close sessions when your Activity or ViewModel is destroyed, then cancel the scope you passed to the manager:

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

If you hold a `TerminalSession` or `Term` directly, you can also close it explicitly:

```kotlin
session.close()
term.close()
```

## FAQ

### Why does `send` not immediately produce output?

`send` only means the input has been written to the shell. Command execution and output delivery are asynchronous. Callers should read `latest(...)` later or keep rendering from the output flow.

### Why do commands usually need `\n`?

Shells typically execute only after receiving Enter, so text commands usually look like this:

```kotlin
session.send(TerminalBytes.of("ls -la\n".encodeToByteArray()))
```

### Why does `ROOT` fail to open?

Typical causes are missing root capability, authorization denial from the root manager, or an environment where libsu cannot obtain a root shell. In those cases `open(TerminalIdentity.ROOT)` returns `OpenResult.Failure`.

### Why does `SHIZUKU` fail to open?

Typical causes are Shizuku not being installed, not running, or not authorized yet. The backend can request authorization, but the user still needs a valid Shizuku environment.

### How do I check permission without prompting?

Use `AuthorizationMode.CHECK_ONLY`:

```kotlin
val result = manager.open(
    identity = TerminalIdentity.SHIZUKU,
    authorizationMode = AuthorizationMode.CHECK_ONLY,
)
```

### Does output grow forever?

No. `TerminalSession` only keeps the latest output in a bounded buffer. You can adjust it with `TerminalBufferConfig`.

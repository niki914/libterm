# Project Overview

## 定位

本页回答三个问题：这个仓库是什么、模块怎么分、外部调用面长什么样。项目边界由 `settings.gradle.kts` 定义；公开入口由 `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt` 和 `libterm-runtime/src/main/java/com/niki914/libterm/runtime/Term.kt` 提供。

## 状态判断

| 能力 / 链路 | 状态 | 源码证据 | 边界 |
|:--|:--|:--|:--|
| 多模块工程拆分 | `Stable` | `settings.gradle.kts` | 仓库被拆成 core、runtime、两个 backend 和 smoke app |
| 公开 `Term` API | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/Term.kt` | 对外只暴露 `open`、`exec`、`write`、`close`、`state`、`stream` |
| 默认门面创建入口 | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt` | 统一提供 `newUserTerm`、`newSuTerm`、`newShizukuTerm` 和默认 manager |
| 失败模型 | `Stable` | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalFailure.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/TermResult.kt` | 错误通过 `TerminalFailure` 和 `TermResult` 暴露给上层 |

## 关键源码

### `libterm-runtime/src/main/java/com/niki914/libterm/runtime/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `LibTerm.kt` | `LibTerm` | 负责创建默认 manager 和三类 `Term` |
| `Term.kt` | `Term` | 定义对外 API 面 |
| `CommandResult.kt` | `CommandResult` | 描述 `exec` 返回的 stdout、stderr、exitCode、timedOut |
| `TermResult.kt` | `TermResult` | 统一成功/失败返回壳 |

### `libterm-core/src/main/kotlin/com/niki914/libterm/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `TerminalFailure.kt` | `TerminalFailure` | 定义启动、授权、运行期与关闭相关失败类型 |

## 核心链路

1. 工程模块图先从 `settings.gradle.kts` 确认。
2. 对外调用面从 `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt` 和 `libterm-runtime/src/main/java/com/niki914/libterm/runtime/Term.kt` 进入。
3. 需要继续落到具体运行机制时，跳到 `architecture/runtime-core-backend.md` 或 `architecture/command-execution.md`。

## 调试 / 阅读关注点

| 问题 | 先看 | 判断标准 |
|:--|:--|:--|
| 这个仓库是不是库工程而不是普通 App | `settings.gradle.kts`, `build.gradle.kts` | 是否存在独立 runtime/core/backend 模块和单独 smoke app |
| 上层能直接调用哪些 API | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/Term.kt` | 只看接口定义，不猜测内部细节 |
| 失败如何向外传播 | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalFailure.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/TermResult.kt` | 是否通过统一 failure/result 约束传递 |

## 与其他页面关系

| 相关页面 | 关系 |
|:--|:--|
| `reference/source-map.md` | 提供本页涉及入口的完整源码导航 |
| `architecture/runtime-core-backend.md` | 展开默认装配和 manager 主链路 |
| `domains/identities/index.md` | 展开三类身份的差异和授权边界 |

## 缺口

| 缺口 | 当前状态 | 下一步 |
|:--|:--|:--|
| 发布方式与仓库外接入流程的外部生态说明 | `Unverified` | 若后续需要补发布文档，再核对各模块 `build.gradle.kts` 和产物策略 |

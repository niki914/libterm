# libterm Wiki Index

> 本页是 lookup 总入口。先从这里选主题，再跳到对应页面；需要继续落到源码时，最后回到 `reference/source-map.md`。

## 项目摘要

- 这是一个 Android 多模块终端库工程，模块边界由 `settings.gradle.kts` 定义。
- 对外入口集中在 `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt` 和 `libterm-runtime/src/main/java/com/niki914/libterm/runtime/Term.kt`。
- 会话打开、鉴权和注册主链路集中在 `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalManager.kt`。
- 会话生命周期、输出缓冲和关闭时序集中在 `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalSession.kt`。
- `USER` 和 `ROOT` 走 libsu 路径，`SHIZUKU` 走 Shizuku 路径，分流点见 `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimeBackendFactory.kt`。
- 手工验证入口在 `shell-smoke-app/src/main/AndroidManifest.xml`、`shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/MainActivity.kt` 和 `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/AdbCommandReceiver.kt`。

## 知识地图

| Page | 状态 | 主要源码依据 | 用途 | Start here when |
|:--|:--|:--|:--|:--|
| `overview/project-overview.md` | `Stable` | `settings.gradle.kts`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/Term.kt` | 项目定位、模块职责、公开 API、错误模型 | 你先想搞清项目边界和公开入口 |
| `architecture/runtime-core-backend.md` | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimeBackendFactory.kt`, `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalManager.kt` | 默认装配方式、open/close 主链路、runtime 到 backend 的分层关系 | 你在看整体装配和生命周期 |
| `architecture/command-execution.md` | `Stable` | `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/DefaultTerm.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/CommandResult.kt`, `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalSession.kt` | `exec`、`write`、输出流、buffer 和退出码处理 | 你在看命令执行语义和输出收集 |
| `domains/identities/index.md` | `Stable` | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalIdentity.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/internal/RuntimePrivilegeProvider.kt`, `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuPrivilegeAuthorizer.kt` | 三类身份、可用性判断和授权边界 | 你在看 USER、ROOT、SHIZUKU 的差异 |
| `domains/backends/index.md` | `Stable` | `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalBackend.kt`, `libterm-backend-libsu/src/main/java/com/niki914/libterm/backend/libsu/LibsuTerminalBackend.kt`, `libterm-backend-shizuku/src/main/java/com/niki914/libterm/backend/shizuku/ShizukuTerminalBackend.kt` | backend SPI、libsu 实现、Shizuku IPC 入口 | 你要定位具体 backend 实现文件 |
| `domains/demo-and-verification/index.md` | `Stable` | `shell-smoke-app/src/main/AndroidManifest.xml`, `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/MainActivity.kt`, `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/AdbCommandReceiver.kt` | smoke app、ADB 广播执行和手工验收路径 | 你要自己跑一遍或验证行为 |
| `reference/source-map.md` | `Stable` | `settings.gradle.kts`, `libterm-core/src/main/kotlin/com/niki914/libterm/TerminalManager.kt`, `libterm-runtime/src/main/java/com/niki914/libterm/runtime/LibTerm.kt`, `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/MainActivity.kt` | 按模块和公共前缀聚合后的源码导航图 | 你已经知道主题，但要继续落到源码入口 |

## 阅读路由

| 场景 | 推荐阅读顺序 |
|:--|:--|
| 第一次接手仓库 | `index.md` -> `overview/project-overview.md` -> `reference/source-map.md` |
| 想看默认装配和 manager 链路 | `index.md` -> `architecture/runtime-core-backend.md` -> `reference/source-map.md` |
| 想看 `exec` 与 `write` 的差异 | `index.md` -> `architecture/command-execution.md` -> `reference/source-map.md` |
| 想看身份与授权差异 | `index.md` -> `domains/identities/index.md` -> `reference/source-map.md` |
| 想定位具体 backend | `index.md` -> `domains/backends/index.md` -> `reference/source-map.md` |
| 想手工验证或用 ADB 触发 | `index.md` -> `domains/demo-and-verification/index.md` -> `reference/source-map.md` |

## 状态图例

| 层级 | 含义 | 使用规则 |
|:--|:--|:--|
| `Stable` | 源码路径已核对，行为边界可回指 | 可作为 lookup 主入口 |
| `In Progress` | 只确认到部分接线或骨架 | 必须同时写清缺口 |
| `Proposal` | 只表示目标态或设计意图 | 不能当作源码事实 |
| `Unverified` | 有线索但尚未回到源码核对 | 先去 `reference/source-map.md` 或相关源码继续确认 |

## 维护约束

- 现状判断只接受源码、构建文件、Manifest 和测试作为依据，不接受 README 或规划文档充当实现证明。
- `reference/source-map.md` 负责路径聚合；其他页面只放少量关键路径，不重复倾倒完整源码树。
- 如果后续新增页面，必须先把相对路径登记到本页，再补正文。

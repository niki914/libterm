# Demo And Verification

## 定位

本页回答这个仓库有哪些现成验证入口，以及在没有额外测试平台时该先看哪里。主要入口是 `shell-smoke-app/src/main/AndroidManifest.xml`、`shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/MainActivity.kt`、`shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/AdbCommandReceiver.kt`。

## 状态判断

| 能力 / 链路 | 状态 | 源码证据 | 边界 |
|:--|:--|:--|:--|
| smoke app 启动入口 | `Stable` | `shell-smoke-app/src/main/AndroidManifest.xml` | Manifest 注册了 launcher Activity |
| 三类身份交互验证 | `Stable` | `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/MainActivity.kt` | UI 中直接创建 `USER`、`ROOT`、`SHIZUKU` 三类 `Term` |
| ADB 广播执行验证 | `Stable` | `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/AdbCommandReceiver.kt` | 可通过广播触发 `exec` 并返回 JSON 结果 |

## 关键源码

### `shell-smoke-app/src/main/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `AndroidManifest.xml` | `MainActivity` / `AdbCommandReceiver` | 声明 launcher 和广播接收器入口 |

### `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/`

| 文件 | 关键符号 | 职责 |
|:--|:--|:--|
| `MainActivity.kt` | `MainActivity` | 提供交互式 smoke UI |
| `AdbCommandReceiver.kt` | `AdbCommandReceiver` | 通过广播触发命令执行并生成 JSON 返回值 |

## 核心链路

1. `AndroidManifest.xml` 暴露 launcher 和 `com.niki914.libterm.shellsmoke.action.EXEC` 广播入口。
2. `MainActivity.kt` 在 `onCreate` 内创建三类 `Term` 并允许手工发送输入。
3. `AdbCommandReceiver.kt` 根据广播 extras 创建对应 identity 的 `Term`，执行 `exec` 后返回结果 JSON。

## 调试 / 阅读关注点

| 问题 | 先看 | 判断标准 |
|:--|:--|:--|
| 想手工观察三类身份切换 | `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/MainActivity.kt` | 是否存在三类 session view state 和切换按钮 |
| 想从命令行拿到结构化结果 | `shell-smoke-app/src/main/java/com/niki914/libterm/shellsmoke/AdbCommandReceiver.kt` | 是否把 stdout、stderr、exitCode、timedOut 写进 JSON |
| 想确认广播 action 和导出入口 | `shell-smoke-app/src/main/AndroidManifest.xml` | 是否注册公开 receiver 和 action 名称 |

## 与其他页面关系

| 相关页面 | 关系 |
|:--|:--|
| `domains/identities/index.md` | 说明 demo 中三类 identity 的授权差异 |
| `architecture/command-execution.md` | 说明 receiver 内部调用的 `exec` 语义 |
| `reference/source-map.md` | 提供完整 demo 入口路径 |

## 缺口

| 缺口 | 当前状态 | 下一步 |
|:--|:--|:--|
| 自动化验证脚本和外部调用示例文档 | `Unverified` | 若后续需要，可再单独补 ADB 示例页或参考页 |

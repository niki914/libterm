# A01 - libterm_contract

## 目标

固化 `LibTerm` 的公共契约：模型、接口、错误结果、模块边界和包结构。

## 范围

- `TerminalIdentity`
- `SessionState`
- `OutputChunk`
- `OpenResult`
- `TerminalBackend`
- `PrivilegeProvider`
- `Clock`
- `IdGenerator`
- 包结构与模块依赖规则

## 非目标

- 不实现 libsu 后端。
- 不实现 Shizuku 后端。
- 不实现复杂 UI。
- 不做自动身份回退。

## 依赖

- 依赖 A00 项目模板。

## 验收

- `libterm-core` 为纯 Kotlin/JVM 模块。
- 所有后续模块只依赖契约，不反向污染 core。
- `open()` 结果模型能表达成功、不可用、未授权、启动失败、已关闭等场景。
- 接口能支撑流式输出和主动输入。

## 测试要求

- 可做 API 编译测试。
- 重点不是行为测试，而是契约稳定性。
- 必须证明 core 不依赖 Android SDK 类型。


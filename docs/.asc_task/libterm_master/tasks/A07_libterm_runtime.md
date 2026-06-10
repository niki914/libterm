# A07 - libterm_runtime

## 目标

提供 App 使用的默认入口和依赖组装层。

## 范围

- 创建默认 `TerminalManager`。
- 注入 USER/ROOT/Shizuku providers。
- 注入 backend factory。
- 暴露简洁 Facade。
- 为 demo-app 提供最小接入路径。

## 非目标

- 不实现业务 UI。
- 不隐藏所有错误细节。
- 不在 runtime 内做自动回退。

## 依赖

- 依赖 A02/A03 core。
- 至少依赖 A05 libsu 后端。
- A06 Shizuku 可先接占位，后续补真实实现。

## 验收

- App 能一行创建默认 manager/facade。
- 显式 open USER/ROOT/Shizuku。
- 错误结果透传给调用方。
- runtime 不包含核心状态机逻辑。

## 测试要求

- JVM/Robolectric 侧测试 wiring。
- 使用 fake backend 验证组装逻辑。
- 不在 runtime 测 libsu/Shizuku 细节。


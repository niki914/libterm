# A05 - libterm_backend_libsu

## 目标

实现 USER/ROOT 共用的 libsu 流式 shell 后端。

## 范围

- 一个实现同时支持 `TerminalIdentity.USER` 与 `TerminalIdentity.ROOT`。
- 差异仅在启动 shell 时是否进入 `su`。
- 将 libsu 输出映射为 `OutputChunk` 流。
- 将 `write(input)` 映射到 shell stdin。
- 提供对应 `PrivilegeProvider`。

## 非目标

- 不做 Shizuku。
- 不做 PTY。
- 不做命令输出分组。

## 依赖

- 依赖 A01 契约。
- 可在 core 主线完成前启动调研，但实现验收应等待契约稳定。

## 验收

- USER shell 可启动并收发流。
- ROOT shell 在无 root 时返回明确失败。
- ROOT shell 在有 root 时可启动并收发流。
- close 后资源释放。

## 测试要求

- 单元测试：通过封装一层 libsu adapter fake，测试状态映射和错误映射。
- 仪器/真机 smoke：USER 启动、ROOT 不可用失败、有 root 设备上 ROOT 启动。
- 不把真机测试作为核心逻辑唯一验证手段。


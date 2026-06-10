# A06 - libterm_backend_shizuku

## 目标

复制并魔改 `ServiceManagerCompat-main` 的 Binder/UserService 通道，实现 Shizuku 身份的流式后端。

## 范围

- 复用 ServiceManagerCompat 的 Shizuku Provider/UserService 绑定思路。
- 去掉终端库不需要的系统服务转发业务。
- 保留或改造远端服务进程承载能力。
- 将远端 shell 执行包装为 `TerminalBackend`。
- 提供 Shizuku `PrivilegeProvider`。

## 非目标

- 不把 `IServiceManager` 作为对外 API 暴露给 core。
- 不把 Android `IBinder` 泄漏进 `libterm-core`。
- 不实现系统服务控制 SDK。

## 依赖

- 依赖 A01 契约。
- 建议参考 A05 的流式后端经验后再实现。

## 验收

- 无 Shizuku 环境时返回明确失败。
- 未授权时返回明确失败或触发授权流程。
- 授权后能启动远端服务。
- 远端输出能映射为 `OutputChunk` 流。
- close 能解绑远端服务并释放资源。

## 测试要求

- JVM 单测：测试 adapter 状态转换、错误映射、接口隔离。
- 仪器/真机测试：Shizuku 权限、绑定、远端执行、解绑。
- 必须验证 core 不依赖任何 Android Binder 类型。


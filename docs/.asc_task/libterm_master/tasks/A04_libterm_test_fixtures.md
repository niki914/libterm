# A04 - libterm_test_fixtures

## 目标

提供可靠的测试基础设施，让核心层单测脱离 Android 真机但不脱离真实业务行为。

## 范围

- `FakeBackend`
- `FakeProvider`
- `FakeClock`
- `SequentialIdGenerator`
- 可控输出脚本/错误脚本
- 测试用协程调度支持

## 非目标

- 不 mock Android 权限。
- 不模拟 libsu/Shizuku 真实行为。
- 不替代后端仪器测试。

## 依赖

- 依赖 A01 契约。
- 可与 A02 并行。

## 验收

- Fake 能驱动真实的 `TerminalSession` 和 `TerminalManager`。
- Fake 自身行为可预测、可断言。
- Fake 不引入 Android 依赖。

## 测试要求

- Fake 本身也要有少量自测。
- 重点覆盖输出顺序、失败注入、状态切换、ID 生成确定性。


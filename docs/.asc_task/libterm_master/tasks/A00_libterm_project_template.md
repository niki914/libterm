# A00 - libterm_project_template

## 目标

从 `cmd-android-master` 复制出新的项目模板，建立 `LibTerm` 的根项目和模块骨架。

## 范围

- 创建新 root/module 结构。
- 设置 Gradle project name：`libterm`。
- 设置包名基准：`com.niki914.libterm`。
- 保留可复用的 Android library 配置、Shizuku 配置经验。
- 不保留旧 `com.niki.cmd` API 兼容。

## 非目标

- 不实现核心 API。
- 不接入 libsu 逻辑。
- 不魔改 ServiceManagerCompat。

## 依赖

- 无。必须第一个执行。

## 验收

- 仓库根目录存在新的 LibTerm 模块骨架。
- 新模块命名与 `master.md` 一致。
- 旧 `cmd-android-master` 仍保留，不被破坏。
- 新项目能作为后续小 ASC 的落点。

## 测试要求

- 仅做结构/编译层验证。
- 不要求真机。
- 不写业务单测。


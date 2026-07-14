# KMD Reader Android 文档索引

> 最近更新：2026-07-14
> 仓库状态：课程至 R3 阶段完成，维护休眠

`docs/` 按用途分为当前规划入口、长期知识和历史归档。

## 当前入口

- [冻结 Roadmap](planning/roadmap.md)：本阶段完成边界、长期不变量与维护状态。
- [Post-R3 重启 Backlog](planning/post-r3-reentry-backlog.md)：未来恢复开发前的唯一检查入口。

Android 当前没有活跃实施切片。旧计划中的“下一步”不代表现行优先级。

## 长期知识

- [应用架构](knowledge/architecture/app-architecture.md)
- [页面架构](knowledge/architecture/page-architecture.md)
- [UI Design](knowledge/architecture/ui-design.md)
- [Core 可移植性与 WebView 宿主可行性](knowledge/integration/core-portability-webview-feasibility.md)

主仓库的 Android-Web runtime 协议与 bundle 文档仍是跨仓库契约源；Android 不复制 runtime 语义。

## 历史归档

- [2026 Android 课程至 R3 阶段](archive/course-r3-2026/README.md)

归档保存产品草案、课程阶段、runtime 接入过程、R3 计划/提示词/证据以及旧 R4 假设。它们用于
追溯，不用于直接恢复实施。

## 放置规则

- 当前阶段与恢复顺序放 `planning/`；休眠期只保留少量权威入口。
- 已验证、长期有效的架构与集成事实放 `knowledge/`。
- 已完成计划、旧讨论、证据和被替代方案放 `archive/`。
- 未来恢复时先校准知识文档，再创建新的阶段计划；不要把归档文件直接移回 planning。

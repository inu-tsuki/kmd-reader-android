# Android Reader Planning

> 最近更新：2026-05-27

这里保存仍会影响 Android Reader 后续开发顺序的计划文档。

## 当前入口

- [Roadmap](roadmap.md)
- [Runtime 实现方案](runtime/runtime-implementation-plan.md)
- [ViewModel 与 Runtime Bridge 规划](runtime/viewmodel-runtime-plan.md)
- [Runtime UI 实施计划](runtime/runtime-ui-implementation-plan.md)
- [Runtime UI 抽取计划](runtime/runtime-ui-extraction-plan.md)

## 阶段文档

- [PRD](product/prd.md)
- [阅读体验规划](product/reading-experience-plan.md)
- [Script Reader 与社区交互](product/scriptreader-community-interaction.md)
- [Issue Lifecycle Flow](product/issue-lifecycle-flow.md)
- [第二阶段计划](stages/stage-2-plan.md)
- [第三阶段数据与网络规划](stages/stage-3-data-network-plan.md)
- [第五阶段集成测试报告](stages/stage-5-integration-test-report.md)

## 当前优先级

1. 收束 runtime 错误恢复：renderer 退出、source missing、runtime failed 都要可解释、可重试、可返回。
2. 实施 `ReaderCompanionContainer`：先建状态和空容器，再迁移现有审阅 UI。
3. 补横屏舞台的“横屏观看”入口，避免把竖屏 letterbox 当作最终体验。
4. 推进书架/本地导入/阅读进度，让 Android Reader 更像常规阅读器。
5. 跟踪主仓库 `Work.presentation` 生成草案，但不把生成器作为 Android Reader 近期任务。

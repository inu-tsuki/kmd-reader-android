# KMD Reader Android 文档索引

> 最近更新：2026-06-16

`docs/` 按用途分成三类：开发规划、知识库、归档。新增文档时先判断它是“接下来要做什么”，还是“长期事实是什么”，或只是“历史记录”。

## 开发规划：`planning/`

放仍会影响后续开发顺序、课程交付、阶段切分或 runtime 接入路线的文档。

- [Roadmap](planning/roadmap.md)：Android Reader 路线、技术风险和后续开发顺序的权威入口。
- [PRD](planning/product/prd.md)：课程第一阶段需求文档。
- [阅读体验规划](planning/product/reading-experience-plan.md)：阅读浮层手势、横竖屏 viewport 和审阅脚本查阅。
- [Script Reader 与社区交互](planning/product/scriptreader-community-interaction.md)：阅读、审阅、issue 和 discussion 的社区交互边界。
- [Issue Lifecycle Flow](planning/product/issue-lifecycle-flow.md)：issue 的提出、跳转、关闭和重开模式。
- [Runtime 实现方案](planning/runtime/runtime-implementation-plan.md)：真实 reader runtime 接入 Android 的实现计划。
- [ViewModel 与 Runtime Bridge 规划](planning/runtime/viewmodel-runtime-plan.md)：ViewModel、Repository 和 runtime bridge 的分层设计。
- [Runtime UI 抽取计划](planning/runtime/runtime-ui-extraction-plan.md)：runtime UI 相关的源码上下文、anchor 和 companion 边界。
- [第二阶段计划](planning/stages/stage-2-plan.md)：MVP、页面流转、数据实体和仓库结构。
- [第三阶段数据与网络规划](planning/stages/stage-3-data-network-plan.md)：Retrofit、Room、Repository 与测试。
- [第五阶段集成测试报告](planning/stages/stage-5-integration-test-report.md)：集成测试用例、Bug 清单和性能检查记录。

Runtime 相关计划应以主仓库协议文档 `docs/knowledge/integration/android-webview-runtime-protocol.md` 作为契约源。

## 知识库：`knowledge/`

放长期有效、可反复查阅的架构事实和集成经验。

- [应用架构](knowledge/architecture/app-architecture.md)：Android 应用分层、状态和桌面导航模型。
- [页面架构](knowledge/architecture/page-architecture.md)：页面结构和流转说明。
- [UI Design](knowledge/architecture/ui-design.md)：阅读态全屏 runtime、浮层控件和审阅边栏的界面契约。
- [Core 可移植性与 WebView 宿主可行性](knowledge/integration/core-portability-webview-feasibility.md)：为什么 Android 不复制 core，以及 WebView 宿主边界。

## 归档：`archive/`

放不再作为当前事实来源、但仍值得保留的历史材料。当前暂无归档文档。

## 放置规则

- 阶段顺序、roadmap、课程提交计划：放 `planning/`。
- 长期架构、宿主协议、集成边界：放 `knowledge/`。
- 过期方案、旧讨论、被替代的计划：放 `archive/`。
- 如果一份规划已经变成长期机制，移动到 `knowledge/` 并更新引用。
- 如果一份文档不再指导当前开发，移动到 `archive/` 并在当前文档中保留必要结论。

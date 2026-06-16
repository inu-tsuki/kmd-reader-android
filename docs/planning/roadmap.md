# KMD Reader Android Roadmap

> 文档状态：当前路线
> 最近更新：2026-06-17

## 1. 当前判断

Android Reader 已经越过“课程作业骨架”阶段，进入“真实移动端阅读宿主”阶段。接下来不能继续把它看成一个 WebView demo；它已经承担三件事：

1. 课程项目的可验收 Android 应用。
2. `reader-runtime-web` 的移动端宿主和集成验证场。
3. 未来 KMD 社区阅读、导入、审阅和书架体验的产品原型。

当前路线不再以“能不能交作业”为唯一目标，而是以“系统健康最大，长期痛苦最小”为主线：

- Android 不重写 KMD parser/layout/effect/player。
- Web runtime 是 KMD 播放语义的唯一解释核心。
- `.kmd` frontmatter 是脚本本地播放元信息来源；`Work.presentation` 长期应由导入/发布流程生成。
- `designWidth` / `designHeight` 只属于 `stage` / `interactive` 的设计坐标系；`scroll` / `page` 阅读文档应自适应容器。
- 阅读态以全屏 runtime 为底板，Android UI 只做控制、状态、companion 和社区工具。

## 2. 当前基线

已经具备：

- 课程 MVP 主流程：作品列表、详情、阅读入口、搜索/筛选、审阅入口、导入入口占位。
- 数据层：Retrofit API、Room Entity/Dao/Database、Repository、offline-first 流程和单元测试。
- ViewModel 层：`StateFlow` UI 状态、runtime bridge 事件归约、失败状态保护。
- WebView runtime host：可打包消费 `dist/reader-runtime`，可加载真实 `reader-runtime-web`。
- Runtime bridge：`loadScript -> ready -> play/pause/seek -> progress` 最小链路已闭合。2026-06-17 模拟器 smoke 已确认真实 `reader-runtime-web` bundle 加载、ready→play→progress→ended 全链路闭合（见 `docs/planning/stages/stage-5-integration-test-report.md` IT-03/IT-04）。
- 阅读页：`ReaderDesk` 全屏承载 runtime，外层桌面横滑在阅读态关闭。
- 阅读 chrome：播放控制浮层、dimmed/visible 状态、双指轻点切换。
- 阅读 companion：Review / Issues companion、源码上下文、issue focus 和行级上下文气泡已完成首轮骨架。
- Runtime 可诊断性：renderer 退出报告、bridge trace、host breadcrumbs、visual debug probe 开关。
- Viewport 策略：`.kmd` stage 设计画布优先，`Work.presentation` fallback，阅读类文档保持自适应方向。
- 文档体系：PRD、页面架构、应用架构、UI 设计、runtime UI 实施计划、第五阶段测试报告已分层整理。

仍未完成或待打磨：

- Review / Issues companion 已迁入 `ReaderCompanionContainer`，但还需要模拟器/真机手测尺寸、滚动、关闭手感和 WebView 不重建。
- 审阅源码上下文、issue 定位和本地 issue draft 已有首轮骨架；discussion anchor、后端持久化和正式提交仍待 community-api 演进。
- 横屏舞台还没有正式的“横屏观看”按钮和方向锁定流程。
- WebView renderer 在 Profiler、频繁 seek、重载或图形压力下仍需继续验证和恢复兜底。
- runtime `ready` 后未显示首帧、播放 `ended` 后无重置或提示：2026-06-17 smoke 确认链路闭合但用户只看到黑屏（BUG-09）。这是当前最影响"像真实阅读器"观感的缺口，应优先处理。
- 书架仍未形成真正的本地作品管理、导入持久化、阅读进度和离线缓存。
- `Work.presentation` 仍由 mock/API 手写。生成链路属于主仓库生态草案，不进入 Android Reader 近期 roadmap。

## 3. 当前风险

| 风险 | 状态 | 应对 |
|---|---|---|
| runtime ready/ended 黑屏 | 已出现，已记录 | BUG-09。链路已闭合但无首帧/结束态，优先做 ready 首帧渲染与 ended 重置/重播入口 |
| WebView renderer 退出 | 已出现，已有报告 | UI-5 继续做可恢复错误页、重试、返回和诊断入口 |
| `Work` 与 `.kmd` metadata 双来源 | 已识别 | Android 短期 runtime 以 `.kmd` 为播放事实；生成器路线移到主仓库生态草案 |
| 横屏舞台在竖屏 letterbox 中体验弱 | 已识别 | 浮层提供“横屏观看”入口，竖屏 letterbox 只作为过渡态 |
| Companion 手感与尺寸稳定性 | 已识别 | UI-4 已首轮迁入 `ReaderCompanionContainer`，继续手测 bottom sheet / side panel / Review-Issues 切换 |
| 书架心智未落地 | 已识别 | R3 做 LocalLibrary、导入、进度、离线状态 |
| Phase B 语言设计牵引过大 | 已识别 | Android 验证线不等待 Phase B；只消费当前 runtime contract |

## 4. 路线分层

```text
R0 课程与工程基线
  -> 维持可编译、可测试、可录屏

R1 Runtime 稳定化
  -> renderer 退出可解释、可恢复

R2 Reader UI 成熟化
  -> chrome、companion、错误、横屏观看

R3 本地阅读器能力
  -> 书架、导入、阅读进度、离线缓存

R4 社区与审阅能力
  -> 审阅提交、评论摘要、发现流互动
```

## 5. 阶段路线

### R0：课程与工程基线

状态：基本完成，作为 release safety line 维持。

目标：

- 每轮关键改动后保持 `./gradlew :app:testDebugUnitTest` 通过。
- 每轮关键改动后保持 `./gradlew :app:assembleDebug` 通过。
- 保留第五阶段测试报告和 bug 清单。
- 保持后端不可用时应用不崩溃，有 mock/local fallback。

不再扩张课程范围。课程交付只要求 Android 主流程可运行、UI 集成、测试报告和 bug 修复说明；真实 runtime 深化属于后续加分和产品路线。

### R1：Runtime 稳定化

状态：当前最高优先级之一。

目标：

- WebView renderer 退出时 App 不进程级崩溃。
- 用户看到明确错误：作品、阶段、错误摘要、可重试动作。
- 退出阅读、重试、返回详情都不会访问 destroyed WebView。
- Profiler、旋转屏幕、频繁 seek、后端断开这些场景都有测试记录。

工作：

- 收束 `onRenderProcessGone` 后的 WebView release 路径。
- 将 renderer crash report 和 bridge snapshot 保留到诊断 companion。
- 为 `RetryReaderRuntime` 明确 UI 路径。
- 为 runtime load/source missing/transport ready 竞态补回归测试。
- 维护小型 KMD 压力脚本，用于 seek、viewport、字体和舞台镜头 smoke。

验收：

- renderer 退出显示错误页，不留下黑屏。
- 点击重试可以重新创建 WebView host。
- 返回详情再进入阅读不复用坏掉的 WebView。
- 单测和 assemble 通过。

### R2：Reader UI 成熟化

状态：正在推进。

R2 分成六个切片：

| 切片 | 目标 | 状态 |
|---|---|---|
| UI-1 Chrome 状态化 | 播放控制 visible/dimmed/hidden/pinned | 首轮完成，继续打磨 |
| UI-2 宿主手势 | 双指轻点切换 chrome，不吃 WebView 单指事件 | 首轮完成，待实机确认 |
| UI-3 Viewport/横屏 | stage 设计画布、scroll/page 自适应、横屏观看入口 | policy 首轮完成，横屏按钮待做 |
| UI-4 Companion | 通用附加内容容器，承载 Review / Issues / 行级上下文 | 首轮完成，待手测微调 |
| UI-5 错误恢复 | runtime 失败可解释、可操作 | 进行中 |
| UI-6 进度持久化 | 节流保存阅读进度 | 未开始 |

近期实现顺序：

1. UI-4H 手测：极简 command tray、源码行气泡、重复滚动和关闭手感。
2. UI-4G 手测：Review / Issues 切换时 WebView 不重建、companion 高度不跳。
3. UI-4E 手测：bottom sheet / side panel / shared height。
4. UI-5：错误恢复体验继续收束。
5. UI-3：在 chrome 上加入“横屏观看”按钮和横屏提示。
6. UI-6：阅读进度持久化。

### R3：本地阅读器能力

状态：产品定义完成，功能未完整落地。

目标：

- `书架` 成为应用默认阅读资产页面，而不是个人中心或 mock fallback。
- 用户可以导入本地 `.kmd`，并从书架继续阅读。
- 应用能区分本地可读、仅有元数据、需联网、已缓存、导入失败等状态。

工作：

- 定义 `LocalLibrary` 与 `CommunityDiscovery` 数据边界。
- 导入 `.kmd` 后生成最小本地 `Work` 记录，保存 source 和 active revision；不实现生态级 `Work.presentation` 生成器。
- 本地作品进入书架，并可打开详情和阅读页。
- 阅读进度持久化，书架显示继续阅读。
- 支持移除本地作品、清理缓存和空状态。

依赖：

- R1 runtime 失败恢复至少可用。
- R2 阅读页不因导入作品缺少社区字段而崩溃。

### R4：社区与审阅能力

状态：当前只做最小演示，不做完整社区。

目标：

- 评审志愿者可以在阅读现场查看源码、定位问题，并在独立 Issues companion 中管理 issue。
- 社区发现流可以展示作品属性、评论摘要和预览，但不阻塞阅读器主线。

工作：

- Review companion 最小版：源码全文、播放行、选中行、issue marker、极简 command tray 和源码行上下文气泡。
- Issues companion 最小版：当前 work issue 台账、issue 详情、查看脚本、跳转播放、close/reopen 和 issue draft。
- Runtime inspection 结果与后端 issue 结果合并。
- 轻度修改建议只保存为 suggestion，不直接覆盖 `.kmd`。
- 未来再加入评论摘要、推荐、短视频式预览流和社区互动 companion。

非目标：

- 不做完整编辑器。
- 不做真实登录/权限体系。
- 不在本阶段实现完整审核提交闭环。

## 6. 下一轮开发建议

建议下一轮按这个顺序做：

1. **R1-2 ready/ended 首帧与结束态**（BUG-09）：runtime `ready` 后渲染首帧（不再黑屏），`ended` 后提供重播/返回入口。这是 2026-06-17 smoke 暴露的最影响观感缺口，链路已闭合，只差首帧和结束态。
2. **R2-4I issue draft 布局**（BUG-13）：从 Review 行气泡「提 issue」进入 Issues 后，draft 表单应占主区，不与 issue 台账嵌套滚动。
3. **R1-1 错误恢复收束**：让 renderer 退出、source missing、runtime failed 都有统一恢复 UI。
4. **R2-3 横屏观看按钮与进度保留**：先按钮和提示，再考虑 Activity orientation lock；横屏切换需保留进度（当前未实现）。
5. **R2-4H/G/E 手测**：companion 尺寸、Review↔Issues handoff、bottom sheet / side panel。

注：BUG-11/12（timelineMarkers 缺失）已于 2026-06-17 修复，改为段落级 marker；token 级精确 marker 留待 Phase B parser 重构。正播放跟随交互已实现（Composable 局部状态）。

每一步都要保持：

- `ReaderRuntimeHost` 不因 chrome/companion 开关重建。
- 单指事件继续进入 WebView。
- 失败状态不会被 late runtime event 覆盖。
- 单测和 assemble 通过。

## 7. 暂停事项

近期不要做：

- 不启动 Phase B 新语法实现。
- 不抽纯 `kmd-core` 包。
- 不在 Kotlin 端重写 KMD parser/layout/effect/player。
- 不把完整编辑器能力塞进 Android。
- 不让社区互动阻塞 runtime UI MVP。
- 不把横屏舞台的竖屏 letterbox 当作最终体验。

## 8. 完成定义

当前阶段可以称为“Android Reader runtime UI 成熟”的标准：

```text
打开 App
  -> 进入书架或发现
  -> 打开作品详情
  -> 进入阅读
  -> WebView 加载真实 reader-runtime-web
  -> loadScript 使用同一份 .kmd source
  -> runtime 可见且可 play/pause/seek
  -> 双指轻点可切 chrome
  -> 横屏舞台有横屏观看入口
  -> 打开 companion 审阅不重建 WebView
  -> renderer 退出可解释、可重试、可返回
```

在这条链路稳定前，不宣称 Android Reader 已完成真实阅读体验；只能说它已完成课程 MVP，并具备真实 runtime 集成基础。

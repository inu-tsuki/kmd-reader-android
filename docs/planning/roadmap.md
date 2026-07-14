# KMD Reader Android Roadmap

> 文档状态：当前路线
> 最近更新：2026-07-14

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

- 课程 MVP 主流程：作品列表、详情、阅读入口、搜索/筛选、审阅入口，以及 SAF 本地导入。
- 数据层：Retrofit API、Room Entity/Dao/Database、Repository、offline-first 流程和单元测试。
- ViewModel 层：`StateFlow` UI 状态、runtime bridge 事件归约、失败状态保护。
- WebView runtime host：可打包消费 `dist/reader-runtime`，可加载真实 `reader-runtime-web`。
- Runtime bridge：`loadScript -> ready -> play/pause/seek -> progress` 最小链路已闭合。2026-06-17 模拟器 smoke 已确认真实 `reader-runtime-web` bundle 加载、ready→play→progress→ended 全链路闭合（见 `docs/planning/stages/stage-5-integration-test-report.md` IT-03/IT-04）。
- 阅读页：`ReaderDesk` 全屏承载 runtime，外层桌面横滑在阅读态关闭。
- 阅读 chrome：播放控制浮层、dimmed/visible 状态、双指轻点切换。
- 阅读 companion：Review / Issues companion、源码上下文、issue focus 和行级上下文气泡已完成首轮骨架。
- Runtime 可诊断性：renderer 退出报告、bridge trace、host breadcrumbs、visual debug probe 开关。
- Viewport 策略：`.kmd` stage 设计画布优先，`Work.presentation` fallback，阅读类文档保持自适应方向。
- R3 本地链路：`LocalLibraryEntry`、阅读进度、本地 issue 草稿、裸 `.kmd` / `.kmdwork` 导入、bundle asset host 和本地 revision 播放优先级已落地。
- R3-F/G/H 书架主流程：书架与历史分离、加入/移出书架、进度/时间显示、卡片与详情页续读入口，以及设置/关于入口已落地。
- 文档体系：PRD、页面架构、应用架构、UI 设计、runtime UI 实施计划、第五阶段测试报告已分层整理。

仍未完成或待打磨：

- Review / Issues companion 已迁入 `ReaderCompanionContainer`，但还需要模拟器/真机手测尺寸、滚动、关闭手感和 WebView 不重建。
- 审阅源码上下文、issue 定位和本地 issue draft 已有首轮骨架；discussion anchor、后端持久化和正式提交仍待 community-api 演进。
- WebView renderer 在 Profiler、频繁 seek、重载或图形压力下仍需继续验证和恢复兜底。
- runtime `ready` 后的首帧与 `ended` 呈现仍是产品设计待决项（BUG-09），不能以 runtime 层硬编码替代作者意图。
- 本地阅读器仍待书架体验收束；阅读偏好已通过 SettingsSheet + DataStore 落地，书架仍是功能原型。
- `Work.presentation` 仍由 mock/API 手写。生成链路属于主仓库生态草案，不进入 Android Reader 近期 roadmap。

## 3. 当前风险

| 风险 | 状态 | 应对 |
|---|---|---|
| runtime ready/ended 首帧视觉 | 设计待决 | BUG-09。非 bug：脚本首帧字符全隐 + 黑画布是脚本语义。首帧渲染 / 书封 / ended 呈现待产品方向明确 |
| WebView renderer 退出 | 已出现，已有报告 | UI-5 继续做可恢复错误页、重试、返回和诊断入口 |
| `Work` 与 `.kmd` metadata 双来源 | 已识别 | Android 短期 runtime 以 `.kmd` 为播放事实；生成器路线移到主仓库生态草案 |
| 横屏舞台在竖屏 letterbox 中体验弱 | 已识别 | 横屏观看入口已落地；方向锁定与播放会话保留仍需真机验证 |
| Companion 手感与尺寸稳定性 | 已识别 | UI-4 已首轮迁入 `ReaderCompanionContainer`，继续手测 bottom sheet / side panel / Review-Issues 切换 |
| 本地阅读体验未收束 | 进行中 | R3-G/H/I 已闭合收藏、续读和全局偏好；R3-K 收束书架层级、状态与管理动作 |
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

状态：基本完成（2026-06-17）。onRenderProcessGone release 路径、RetryReaderRuntime、renderer crash 诊断、BUG-14（ended/pause 一致性）均已实现。剩余：load/source missing/transport 竞态的专项回归测试、压力脚本，作为持续维护项。

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
| UI-3 Viewport/横屏 | stage 设计画布、scroll/page 自适应、横屏观看入口 | 首轮完成；方向锁定与会话保留待真机验证 |
| UI-4 Companion | 通用附加内容容器，承载 Review / Issues / 行级上下文 | 首轮完成，待手测微调 |
| UI-5 错误恢复 | runtime 失败可解释、可操作 | 进行中 |
| UI-6 进度持久化 | 节流保存阅读进度 | **移入 R3**（见下方说明） |

近期实现顺序：

1. UI-4H 手测：极简 command tray、源码行气泡、重复滚动和关闭手感。
2. UI-4G 手测：Review / Issues 切换时 WebView 不重建、companion 高度不跳。
3. UI-4E 手测：bottom sheet / side panel / shared height。
4. UI-5：错误恢复体验继续收束。
5. UI-3：验证横屏观看后的会话保留、返回竖屏和 renderer 稳定性。

> UI-6（阅读进度持久化）从 R2 移入 R3。进度数据模型（独立表 vs LocalWork 字段 vs DataStore）本质是 R3 本地数据架构的一部分——进度归属于哪个 work、work 存在哪、外键如何约束，都依赖 R3 的 LocalLibrary / LocalWork 设计。在 R2 用独立 Room 表提前实现会导致外键崩溃（mock 作品不进 Room）或数据归属错乱。R3 统一设计本地数据模型后再实现进度持久化。

### R3：本地阅读器能力

状态：主干能力已落地，进入本地阅读体验收束。

目标：

- `书架` 成为应用默认阅读资产页面，而不是个人中心或 mock fallback。
- 用户可以导入本地 `.kmd`，并从书架继续阅读。
- 应用能区分本地可读、仅有元数据、需联网、已缓存、导入失败等状态。

工作：

- 已完成：`LocalLibrary` 与 `CommunityDiscovery` 数据边界、阅读进度和本地草稿持久化、裸 `.kmd` / `.kmdwork` 导入、bundle 资产加载、本地 revision 播放优先级，以及书架/阅读历史 UI。
- 已完成：浏览/详情加入书架与详情页续读状态。
- 下一步：R3-K 书架体验收束。笔记/书签按价值评估。

> **进度持久化的数据模型教训（2026-06-17）**：进度数据模型必须与 LocalWork 统一设计，不能提前独立实现。曾尝试在 R2 用独立 Room 表 `ReadingProgressEntity`（外键约束 `workId → works.id`），但 mock 作品走 `MockWorkRepository` 从不写入 Room，导致外键约束失败、恢复进度时崩溃。根因：进度归属的 work 在哪存储是 R3 的核心问题，提前建表会把数据架构决策钉死在错误的假设上。R3 实现 LocalWork 时一并决定进度是 LocalWork 字段、独立表还是 DataStore。

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

下一轮按以下可并行工作推进：

1. **体验质量门**：R1 错误恢复收束，并完成 R2 companion、横屏会话保留的模拟器/真机手测；它们是后续 UI 扩张的发布条件。
2. **R3-K 书架体验收束（K1 已完成）**：K1 已交付信息层级、纯 UI 投影、响应式和空/导入状态并通过复审；K2 是可独立排期的排序筛选与非破坏性管理增强，删除/缓存继续受独立数据边界 gate 约束。
3. **R2-4I issue draft 布局**（BUG-13）：从 Review 行气泡进入 Issues 后，draft 表单占主区，避免与 issue 台账嵌套滚动。

注：BUG-09（ready/ended 黑屏）经 2026-06-17 调查降级为**产品设计待决项**——脚本本身首帧字符全隐 + 黑色画布，ready 后黑屏符合脚本语义；是否渲染首帧 / 需要书封 / ended 呈现取决于社区作者意图，runtime 不硬编码。待书封 / 首帧渲染方向明确后再实现。BUG-11/12 已修复（段落级 marker）。正播放跟随交互已实现。

每一步都要保持：

- `ReaderRuntimeHost` 不因 chrome/companion 开关重建。
- 单指事件继续进入 WebView。
- 失败状态不会被 late runtime event 覆盖。
- 单测和 assemble 通过。

R3-I 已完成：它只管理全局阅读偏好，不引入每作品主题/字号，不改变 `.kmd` 前台元数据，不重建 `ReaderRuntimeHost`；自动保存关闭时先安全 flush 当前会话中已节流的进度，再停止后续写入。

R3-K 的边界：它消费 R3-F/G/H 已建立的书架状态与动作，先收束信息层级和多尺寸体验；删除本地作品、清理缓存等破坏性动作必须先定义跨 Room/bundle/revision/draft 的删除边界，不能只做表面按钮。

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

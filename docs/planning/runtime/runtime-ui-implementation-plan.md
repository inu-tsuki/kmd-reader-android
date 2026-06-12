# Android Reader Runtime UI Implementation Plan

> 文档状态：实施计划
> 最近更新：2026-05-28

## 1. 目标

本文把阅读态 UI 草稿整理成可实施计划。目标不是重新设计整套 Android Reader，而是在现有 ViewModel、Repository、WebView Runtime Bridge 已经跑通的基础上，把阅读页从“能播放”推进到“像正式阅读体验”：

- `reader-runtime-web` 全屏作为内容底板。
- 播放控制作为临时浮层，而不是常驻页面组件。
- 审阅工具作为阅读 companion，而不是独立一级页面。
- 作品 viewport、横竖屏、letterbox 和宿主手势有明确策略。
- 错误、调试和 WebView renderer 退出能被解释和恢复。

本计划承接：

- `apps/android-reader/docs/knowledge/architecture/ui-design.md`
- `apps/android-reader/docs/planning/product/reading-experience-plan.md`
- `apps/android-reader/docs/planning/product/scriptreader-community-interaction.md`
- `apps/android-reader/docs/planning/product/issue-lifecycle-flow.md`
- `apps/android-reader/docs/planning/runtime/runtime-implementation-plan.md`
- `apps/android-reader/docs/planning/runtime/runtime-ui-extraction-plan.md`

## 2. 当前基线

已具备：

- `ReaderDesk` 已使用 `Box(fillMaxSize)` 承载 `ReaderRuntimeHost`。
- 阅读态隐藏普通顶部导航，外层 `HorizontalPager` 在阅读页激活时关闭用户滑动。
- `ReaderRuntimeHost` 可以加载真实 `dist/reader-runtime` 或 fallback shell。
- `WebViewReaderRuntimeBridge` 已闭合 `loadScript -> ready -> play/pause/seek -> progress` 的最小链路。
- `PlaybackControlOverlay` 已有播放、暂停、进度和松手提交 seek。
- `Review` 已迁入 `ReaderCompanionContainer`，阅读态打开审阅不再使用 App 级全屏 `ReviewOverlay`。
- `KmdSourceContextView` 已抽出，只读支持 snippet / full 两种源码查看模式。
- `ReaderViewportPolicy` 已能根据 `.kmd` frontmatter、`Work.presentation` 和宿主尺寸生成基础 viewport。

主要缺口：

- 阅读浮层已有首轮状态模型，但 hidden/reviewing 体验还需要继续打磨。
- 双指点击和宿主手势已有首轮实现，还需要真机/模拟器多轮确认手感与冲突边界。
- viewport policy 已有首轮实现，但还缺横屏观看入口、旋转恢复策略和安全区细节。
- companion placement、统一关闭契约和 Review source-first 首轮已实现，还需要模拟器/真机手测尺寸、手势和内容溢出。
- `TopBottomBands` 仍只保留为未来形态；首轮没有进入实际布局。
- runtime 错误恢复已有用户可操作路径，但诊断 companion 化和 debug probe 收束仍需继续打磨。
- 阅读进度持久化尚未开始。

## 3. 非目标

近期不做：

- 不在 Kotlin 端重写 KMD parser、layout、effect 或 player。
- 不让 Android UI 直接解释 `.kmd` 播放语义。
- 不把 WebView 放回卡片、小窗口或滚动页面中。
- 不把审阅做成底部导航 Tab。
- 不做完整编辑器、语法补全、多文件工程和 Git diff。
- 不依赖 Activity 重建来实现 runtime 内部横竖屏切换。

## 4. 目标模型

### 4.1 阅读态层级

```text
ReaderDesk
  ├─ Layer 0: ReaderRuntimeHost
  ├─ Layer 1: RuntimeStatusOverlay
  ├─ Layer 2: ReaderChromeOverlay
  │    ├─ ReaderTopControls
  │    └─ PlaybackControls
  ├─ Layer 3: ReaderCompanionContainer
  │    └─ active companion: review / community / info / diagnostics
  └─ Layer 4: RuntimeError / transient message
```

Layer 0 生命周期只绑定当前阅读会话，不绑定控制浮层是否显示。浮层状态变化不得触发 WebView 重建。

Layer 3 是阅读时附加内容容器，不属于 runtime viewport。它可以占用 letterbox 空间、侧栏、底部 sheet 或半透明浮层，但不能改变 `.kmd` 的设计坐标系。当前 MVP 只实现审阅 companion；社区互动、评论摘要和推荐信息共享同一容器模型，暂不进入实现范围。

Companion 的用户心智必须保持一致：位置变化只是响应式布局，不代表不同类型的面板。所有 companion 都应共享同一套打开、关闭、返回和焦点切换规则；Review 不能因为内容复杂而拥有一套额外的“展开/收起”语法。

### 4.2 状态模型

新增或整理以下状态：

```kotlin
data class ReaderChromeState(
    val visibility: ReaderChromeVisibility = ReaderChromeVisibility.Visible,
    val isPinned: Boolean = false,
    val mode: ReaderChromeMode = ReaderChromeMode.Reading,
    val lastInteractionAtMillis: Long = 0L
)

enum class ReaderChromeVisibility {
    Visible,
    Dimmed,
    Hidden
}

enum class ReaderChromeMode {
    Reading,
    Reviewing,
    Error
}
```

```kotlin
data class ReaderViewportState(
    val presentationMode: PresentationMode,
    val orientationHint: OrientationHint,
    val aspectRatio: String,
    val runtimeViewport: ReaderRuntimeViewport,
    val hostWidthPx: Int,
    val hostHeightPx: Int,
    val letterboxed: Boolean
)
```

```kotlin
data class KmdSourceSnapshot(
    val workId: String,
    val revisionId: String,
    val content: String,
    val fetchedAtMillis: Long,
    val lineOffsets: List<Int>
)
```

```kotlin
data class ReviewSessionState(
    val sourceSnapshot: KmdSourceSnapshot? = null,
    val selectedIssueId: String? = null,
    val selectedLine: Int? = null,
    val draftNote: String = "",
    val draftSuggestions: Map<String, String> = emptyMap(),
    val decision: ReviewDecision? = null
)
```

```kotlin
data class ReaderCompanionState(
    val active: ReaderCompanionType? = null,
    val placement: ReaderCompanionPlacement = ReaderCompanionPlacement.Overlay,
    val expanded: Boolean = false
)

enum class ReaderCompanionType {
    Review,
    Issues,
    CommunitySummary,
    WorkInfo,
    Diagnostics
}

enum class ReaderCompanionPlacement {
    BottomSheet,
    SidePanel,
    TopBottomBands,
    Overlay
}
```

这些状态属于 ViewModel，不属于 WebView。WebView 只负责 runtime 内容和事件。

2026-05-28 约定：`expanded` 是当前实现里的过渡字段，不能继续作为 Review 的用户可见概念扩张。后续应把 companion 尺寸交给 `ReaderCompanionPlacementPolicy` 和 viewport，Review 内部只保留 focus mode。

## 5. 实施切片

### UI-1：阅读浮层状态化

目标：让播放控制从“总是存在的底部控件”变成可控阅读 chrome。

实现状态（2026-05-21）：已完成基础状态化。当前已加入 `ReaderChromeState`、chrome actions、Reducer 逻辑、ReaderDesk 全量/Dimmed 浮层切换、播放 3 秒后退为轻量条、固定/自动按钮和 reducer 单元测试。完整 Hidden 模式等待 UI-2 双指手势接入后再启用到主流程。

任务：

- 在 `KmdReaderState` 中加入 `readerChrome: ReaderChromeState`。
- 新增 actions：
  - `ToggleReaderChrome`
  - `ShowReaderChrome`
  - `HideReaderChrome`
  - `SetReaderChromePinned(Boolean)`
  - `ReaderInteraction`
- 播放中 3 秒无操作后自动隐藏或 dimmed。
- 暂停、拖动进度、发生错误时强制显示。
- 审阅打开时 `mode = Reviewing`，关闭后恢复 `Reading`。

实现点：

- `ReaderDesk` 根据 `ReaderChromeState` 决定是否显示 `ReaderTopOverlay` 和 `PlaybackControlOverlay`。
- Slider 拖动期间保持 `Visible`，松手后再进入自动隐藏计时。
- 不让 `ReaderChromeState` 参与 `ReaderRuntimeHost` key，避免 WebView 重建。

验收：

- 播放中浮层可以隐藏，暂停后浮层显示。
- seek 时浮层不消失。
- 隐藏/显示浮层不会导致 runtime 重新加载。

### UI-2：宿主手势与阅读手势优先级

目标：宿主只保留低冲突手势，中心内容交给 runtime。

实现状态（2026-05-26）：已完成首轮宿主侧双指轻点观察。当前 `ReaderRuntimeHost` 在 WebView 的 `OnTouchListener` 中识别双指轻点并触发阅读 chrome 切换，listener 始终返回 `false`，单指点击、拖动和 runtime 内部交互继续交给 WebView。审阅面板打开时，双指轻点优先关闭审阅；普通阅读态则切换浮层显示状态。双指轻点只触发 toggle，不先触发 `ReaderInteraction`，避免 hidden 状态被先 show 再 toggle 回 hidden。

任务：

- 在 `ReaderRuntimeHost` 或 WebView factory 中接入 host touch observer。
- 识别双指轻点，触发 `ToggleReaderChrome`。
- 未命中双指轻点时不消费事件，继续交给 WebView。
- 阅读态继续关闭外层 pager 横滑。
- 保留系统返回和浮层返回入口。

不推荐：

- 不在 WebView 上方放全屏透明 Compose `pointerInput` 遮罩。
- 不默认用单指点击切换浮层，避免与互动作品冲突。

验收：

- 单指点击、拖动和 WebView 内部交互仍能到达 runtime。
- 双指轻点可以显示/隐藏浮层。
- 审阅打开时双指轻点优先关闭审阅或回到普通阅读 chrome。

### UI-3：Viewport 与横竖屏布局策略

目标：让作品方向、设备方向和 runtime viewport 分离。

实现状态（2026-05-26）：已完成首轮策略抽出并接入 `.kmd` 舞台设计画布。当前已加入 `ReaderViewportState` 和 `ReaderViewportPolicy`，对 `stage` / `interactive` 脚本优先从 `.kmd` frontmatter 的 `designWidth` / `designHeight` 生成 runtime viewport；`scroll` / `page` 阅读文档不使用固定设计画布，而是根据容器和阅读模式自适应。没有可用舞台设计尺寸时，再从 `Work.presentation` 与阅读宿主尺寸生成 fallback viewport、letterbox 标记和 `ReaderSettings`。`ReaderDesk` 会回传宿主尺寸，ViewModel 在阅读会话中通过 `updateSettings` 尝试更新 runtime viewport；阅读浮层会显示当前方向、比例、viewport 与适配状态。暂不强制 Activity 横竖屏，也不承诺播放中无缝重排。

任务：

- 新增 `KmdSourceMetadataParser`，从 `.kmd` frontmatter 读取 `mode`、`designWidth`、`designHeight`。
- 新增 `ReaderViewportPolicy`，舞台类作品优先从脚本设计画布生成 `ReaderViewportState`，阅读类作品保持容器自适应，再回退到 `Work.presentation` 和 host window size。
- 竖屏作品：使用 portrait runtime viewport，控制条在底部。
- 横屏 stage 作品：如果声明了设计画布，则使用脚本尺寸；否则 runtime viewport 使用 Work aspect ratio；竖屏中 letterbox 只是过渡态，浮层应提供“横屏观看”入口。
- adaptive 作品：根据 aspectRatio 和实际 host 尺寸推导。
- 将 `KmdReaderViewModel.readerSettingsFor(work)` 的固定数值迁移到策略类。
- 记录 `letterboxed`，供 UI 决定是否显示横屏提示或“适配方式”按钮。

短期协议：

- 继续在 `loadScript.settings.viewport` 中传入 viewport。
- 方向切换先通过重新 load 当前 work 或 updateSettings 实验，不承诺无缝。

长期协议：

- 增加 `setViewport` command。
- 增加 `viewportChanged` event。
- runtime 在不销毁会话的情况下更新 canvas/world transform。

验收：

- `rain-city` 这类 portrait work 在竖屏中占据合理阅读区域。
- `glass-rail` / `final-test` 这类 landscape work 在竖屏中 letterbox，内容不被裁切。
- 旋转设备后不崩溃；若不能无缝保持进度，必须给出可恢复说明。

### UI-4：Reader Companion 容器与审阅最小版

目标：先建立通用的阅读时附加内容容器，再把审阅从 App 级浮窗迁入阅读现场。

实现状态（2026-05-28）：已完成 UI-4A/B/C/D/E/F/G/H 的首轮骨架。当前已加入 `ReaderCompanionState`、通用 companion actions、reducer 单元测试和 `ReaderCompanionContainer`；阅读态打开审阅时不再显示 App 级全屏 `ReviewOverlay`，而是在 `ReaderDesk` 内显示 `Review` companion。UI-4D 已加入 `KmdSourceSnapshot`、按 issue location 定位源码片段、基于 runtime 播放位置的当前源码行高亮、issue focus、播放锚点跳转、本地 issue draft、close/reopen 状态。源码查看器已抽为只读 `KmdSourceContextView`，支持带前后文的定位片段和全文源码视图。UI-4E 已加入 `ReaderCompanionPlacementPolicy`、统一 dismiss policy、系统返回关闭、WebView 宿主侧栏外双击观察。UI-4F 已把 Review 压缩为脚本查看器；UI-4G 已新增 `Issues` companion 首版，将 issue 台账、close/reopen 和 draft 从 Review 中抽离。UI-4H 已将 Review command tray 收束为 `正播放 Lxx | ⇄`，并加入源码行上下文气泡首版。

产品原则：

- `ReaderCompanionContainer` 是 Android UI 层，不是 Web runtime 的一部分。
- 它可以承载审阅、评论摘要、作品信息、诊断和未来社区互动，但同一时刻只激活一个主 companion。
- 它应优先使用 runtime 周围的非关键空间：竖屏阅读使用底部 sheet 或侧栏抽屉；横屏舞台使用侧栏或上/下信息带。
- placement 只是响应式布局，不代表 companion 类型拥有不同关闭规则。
- 它不负责让横屏舞台在竖屏中变得“可读”；横屏舞台仍应提供横屏观看路径。

MVP companion：

- `Review`：脚本查看器，只负责源码全文、播放行、选中行、issue 编号标记、极简 command tray 和源码行上下文气泡。
- `Issues`：issue 管理台，负责当前 work 的 issue 列表、详情、close/reopen、draft、discussion 入口和跳回 Review 源码行。

实施顺序：

1. `UI-4A`：只建 `ReaderCompanionState`、actions 和 reducer，不改变现有审阅 UI。
2. `UI-4B`：在 `ReaderDesk` 中加入空的 `ReaderCompanionContainer`，验证打开/关闭不会重建 WebView。
3. `UI-4C`：把现有 `ReviewOverlay` 内容迁入 `ReviewCompanionPanel`，先保留 issue 列表和决策按钮。
4. `UI-4D`：补 `KmdSourceSnapshot`、源码片段、issue 定位、issue focus / jump / close。（已完成首轮本地交互闭环）
5. `UI-4E`：根据 `ReaderViewportState` 选择 bottom sheet / side panel / top-bottom bands。（bottom sheet / side panel / overlay 首轮完成，TopBottomBands 待后续）
6. `UI-4F`：Review 变成脚本查看器 + 底部 command bar。（已完成首轮）
7. `UI-4G`：新增 Issues companion，将 issue 管理从 Review 中抽离。（已完成首轮）
8. `UI-4H`：将 Review command bar 收束为全局状态条，把行级动作迁入源码行上下文气泡。（已完成首轮）

任务：

- 新增 `ReaderCompanionContainer`，由 `ReaderDesk` 承载。
- 将阅读态审阅入口从全局 `ReviewOverlay` 迁移为 `ReaderCompanionContainer(type = Review)`。
- 竖屏阅读：默认底部 sheet，高度由 viewport 和内容焦点决定，不暴露 Review 独有的展开/收起按钮。
- 横屏/大屏：默认右侧 side panel。
- 横屏舞台在未切横屏前：允许使用上/下信息带承载简短审阅摘要，但源码查看仍进入 sheet/panel。
- ViewModel 拉取并保存 `KmdSourceSnapshot`。
- ViewModel 解码并保存 runtime 的 `ready.timelineMarkers`、`progressChanged.line` 和 `progressChanged.markerId`，供审阅 companion 定位当前播放源码行。
- Review companion 显示：
  - 源码全文
  - 行号
  - 当前播放行高亮
  - 选中行高亮
  - issue 编号 / marker 高亮
  - 极简 command tray：当前播放行坐标、Review/Issues 切换入口
  - 源码行上下文气泡：跳到此处、提 issue、查看行上 issue、多选等局部动作
- Issues companion 显示：
  - 当前 work 的 issue 列表
  - 当前 issue 位置、状态、严重级别、来源和建议
  - 跳到 Review 源码行
  - 跳到播放位置
  - close / reopen / draft / discussion 入口
- 打开审阅时调用 `runtimeBridge.setInspectionEnabled(true)`。
- 关闭审阅时可调用 `setInspectionEnabled(false)`，但不销毁 runtime。

源码一致性约束：

- Repository 获取的同一份 `source` 同时传给 runtime 和 `KmdSourceSnapshot`。
- 审阅面板不向 WebView 索要源码。
- 播放行以 runtime 事件为主：优先使用 `progressChanged.line`，其次用 `markerId` 查找 `timelineMarkers`，再退回 `positionPayload` 中的行号提示。
- 源码行跳转以 `timelineMarkers.line` 为可播放事实来源：从用户选中的源码行开始，选择第一条 `marker.line >= selectedLine` 的 marker；不要跳回上一段。
- 轻度修改只生成建议，不直接覆盖原文。

验收：

- 阅读中打开审阅，runtime 仍可见且会话不重建。
- Review 可以显示 issue marker 和当前播放行；点击 issue marker 打开 Issues companion 并聚焦 issue。
- Issues 可以列出当前 work 的全部 issue；点击“查看脚本”打开 Review companion 并聚焦源码位置；有 playback anchor 时可显式跳到播放位置。
- 点击源码行后可以高亮选中行，并通过行级上下文气泡跳到该行之后第一条可播放脚本行。
- issue 可以进入本地 draft close / reopen 流程；正式提交等待 community-api 接口。
- 审核备注和决策状态可编辑。
- 关闭审阅回到阅读 chrome。

非目标：

- 不在本轮实现评论、弹幕、推荐或社区互动流。
- 不把 companion 内容注入 WebView DOM。
- 不做完整代码编辑器；审阅修改只生成建议。

### UI-4E：Companion Placement、关闭与内容焦点

目标：让阅读 companion 从“固定浮层”升级为可预测的阅读时附加内容布局系统。它需要根据设备方向、作品 viewport 和 companion 类型选择合适停靠方式，同时保证 runtime 不重建、WebView 交互不被长期遮挡。

实现状态（2026-05-28）：首轮修正已完成。当前已新增 `ReaderCompanionDismissPolicy`、`ReaderCompanionLayout` 和 `ReaderCompanionPlacementPolicy`；`ReaderDesk` 根据 `ReaderViewportState + ReaderCompanionState` 解析布局，`ReaderCompanionContainer` 根据 layout 绘制 bottom sheet / side panel / overlay。关闭方式已统一为系统返回、双指轻点和栏外双击；Review 独有的展开/收起按钮已移除；Review 内部已改为 source-first 的焦点切换结构。

#### 心智修正

旧方向容易形成两套心智：

- `BottomSheet` 像 modal，点外部关闭。
- `SidePanel` 像工具栏，只能显式关闭。
- Review 内部还有独立的“展开/收起面板”按钮。

新的约束是：

- companion 是“阅读现场临时附加层”，不是 modal、页面或独立工具箱。
- placement 只表达屏幕空间如何使用，不表达交互规则差异。
- 所有 companion 共享关闭契约：系统返回、双指轻点、以及可选的栏外双击。
- 单击 companion 外区域不作为关闭主路径，避免误吞 runtime 的单指交互。
- Review 不显示独立的展开/收起按钮；尺寸由 placement 和 viewport 决定。
- 若需要切换内容重点，使用 companion 内部的焦点控件，而不是改变 companion 的存在形态。

#### 准备条件

- `ReaderViewportState.hostWidthPx / hostHeightPx` 必须稳定更新。
- `ReaderViewportState.letterboxed`、`presentationMode`、`orientationHint` 可用于判断当前作品更适合横屏舞台还是竖屏阅读。
- `ReaderCompanionState.active / placement` 已在 ViewModel 状态中维护。
- `ReaderRuntimeHost` 不得把 companion placement、focus mode 或 dismiss 状态放进 key。
- `KmdSourceContextView` 已抽出，源码查看器可以被 sheet 或 side panel 复用。

#### 新增 Policy

新增轻量策略类：

```kotlin
data class ReaderCompanionLayout(
    val placement: ReaderCompanionPlacement,
    val dismissPolicy: ReaderCompanionDismissPolicy = ReaderCompanionDismissPolicy()
)

data class ReaderCompanionDismissPolicy(
    val closeOnBack: Boolean = true,
    val closeOnTwoFingerTap: Boolean = true,
    val closeOnOutsideDoubleTap: Boolean = true,
    val closeOnOutsideSingleTap: Boolean = false,
    val hasModalScrim: Boolean = false
)

object ReaderCompanionPlacementPolicy {
    fun resolve(
        viewport: ReaderViewportState,
        companion: ReaderCompanionState,
        type: ReaderCompanionType
    ): ReaderCompanionLayout
}
```

第一版不做用户自定义停靠，只做自动策略。手动切换可以留到 companion UX 稳定之后。

#### Placement 规则

| 场景 | placement | 关闭契约 | 说明 |
|---|---|---|---|
| 手机竖屏，普通阅读或审阅 | `BottomSheet` | 统一关闭契约 | 更符合移动端阅读心智 |
| 手机竖屏，横屏舞台 letterbox | `BottomSheet` | 统一关闭契约 | 横屏观看入口另做；审阅仍以 sheet 承载 |
| 横屏设备或宽屏，审阅 | `SidePanel` | 统一关闭契约 | 保留 runtime 可交互区域，不因单击内容区误关 |
| 大屏/平板，作品信息或诊断 | `SidePanel` | 统一关闭契约 | 适合长内容和诊断信息 |
| 错误恢复、短诊断、临时信息 | `Overlay` | 统一关闭契约；modal 变体可例外 | 默认仍不吞单击，短确认可单独声明 modal |
| 横屏舞台的轻量摘要 | `TopBottomBands` | 统一关闭契约 | 暂不作为首轮必做，可保留枚举和占位 |

临时判断可以先用：

```text
isPortraitHost = hostHeightPx >= hostWidthPx
isWideHost = hostWidthPx >= hostHeightPx
isStageLike = presentationMode in [Stage, Interactive]

if type == Diagnostics && isWideHost -> SidePanel
else if type == Diagnostics -> Overlay
else if isPortraitHost -> BottomSheet
else -> SidePanel
```

#### Dismiss 规则

- 关闭 companion 的主路径统一为：
  - 系统返回键。
  - 双指轻点阅读区域。
  - 栏外双击，作为鼠标/触控板和大屏用户的辅助方式。
- 单击栏外不关闭 companion，只交给 runtime 或阅读 chrome 处理。
- Review 不提供“收起面板 / 展开面板”文本按钮。
- 可以保留一个所有 companion 共用的轻量关闭图标作为可发现性和无障碍后备，但不能只在 Review 中出现。
- 非 modal companion 默认不显示 scrim；如果短诊断或确认对话需要 scrim，应作为 `Overlay` 的 modal 变体单独记录。
- 关闭 companion 后，WebView 恢复直接接收单指事件。
- side panel 不铺全屏透明点击层，避免拦截 runtime 的单指交互。

#### Surface 尺寸规则

- `BottomSheet`：
  - 默认最大高度约为 420dp 到 460dp。
  - 至少保留 runtime 上半区域可见。
- `SidePanel`：
  - 宽度在 320dp 到 460dp 之间。
  - 不显示 scrim。
- `Overlay`：
  - 宽度不超过 520dp。
  - 居中或靠近底部，适合短诊断、确认和错误辅助信息。
- `TopBottomBands`：
  - 首轮只保留为未来横屏舞台社区互动的形态，不进入必做。

#### Review 内容结构

Review companion 的重点不是“在面板里塞多个列表”，而是帮助审阅者把表现、源码和 issue 对齐。为了避免嵌套滚动条，Review 应采用 source-first 结构：

```text
Review Companion
  Body: KmdSourceContextView fills the companion
    - current playback line highlight
    - issue anchor highlight
    - selected source range
    - search / focus marker
  Bottom Command Bar:
    - current playback line
    - companion switch
  Source Line Bubble:
    - jump to line
    - create issue
    - issue marker actions
    - multi-select
```

规则：

- `KmdSourceContextView` 是唯一主滚动区。
- Review 默认展示源码全文；播放行和 issue 只改变 focus / highlight / scroll position，不把源码切成小卡片。
- 去掉“脚本审阅”、作品标题和独立源码标题等占位文字，把顶部空间交给源码行。
- issue 列表不再包住源码查看器；issue 作为 source highlight、gutter marker 或行内编号出现。
- 正在播放行和当前聚焦 issue 通过同一套 highlight/focus 模型表达。
- 底部 command tray 只承担全局状态和 companion 切换，不承载局部行操作。
- 点击源码行后出现行级上下文气泡，局部动作贴着被操作的行出现。
- 宽屏 side panel 也优先保持“源码 + 极简 command tray”；不在 Review 中加入 issue rail。
- 备注、决策、close/reopen 和 issue draft 都属于 Issues companion 或未来 review decision companion，不和源码列表互相嵌套滚动。

#### 任务

- 新增 `ReaderCompanionDismissPolicy` 和 `ReaderCompanionLayout`。（已完成）
- 新增 `ReaderCompanionPlacementPolicy`。（已完成）
- `ReaderDesk` 根据 `readerViewport + readerCompanion` 计算 layout，并传给 `ReaderCompanionContainer`。（已完成）
- `ReaderCompanionContainer` 接收 layout，而不是只接收 placement。（已完成）
- 移除默认 scrim / outside single tap 关闭路径，避免吞掉 WebView 单指交互。（已完成）
- 调整 `CompanionSurface` 的 bottom sheet / side panel / overlay 尺寸。（首轮完成，待手测微调）
- 确认 companion 的尺寸变化或 focus 切换不改变 `ReaderRuntimeHost` key。（代码结构满足，待手测确认）
- 移除 Review 独有的“收起/展开面板”按钮，保留统一关闭契约。（已完成）
- 将 outside single tap 从默认关闭路径中移除，改为双指轻点 / 栏外双击 / 系统返回。（已完成）
- 把 Review 内部重排为 source-first，避免 issue 列表和源码查看器互相嵌套滚动。（已完成首轮）
- 为 `KmdSourceContextView` 增加 issue/playback focus 控件或外层 focus mode。（已完成首轮）
- 手测 bottom sheet / side panel 下 source view、issue rail 和 draft 文本框的溢出情况。（待做）

#### 验收

- 竖屏打开审阅显示 bottom sheet，双指轻点或系统返回可关闭。
- 横屏/宽屏打开审阅显示 side panel，双指轻点或系统返回可关闭，runtime 仍可接收单指交互。
- diagnostics 在窄屏可作为 overlay，但默认仍使用统一关闭契约；只有明确标记为 modal 的短确认才允许单击外部关闭。
- 打开、关闭、尺寸调整或焦点切换 companion 不重建 WebView。
- 双指轻点在 companion 打开时优先关闭 companion；未打开 companion 时切换阅读 chrome。
- Review 中不存在嵌套滚动条；源码全文、issue 焦点、draft 文本框在 bottom sheet 和 side panel 中都不溢出。

### UI-4F：Review Script Viewer Shell

目标：把 Review companion 从“审阅面板”进一步压缩成“一个大的脚本查看器 + 一条底部可变命令栏”。阅读者和审阅者进入 Review 后，第一眼看到的应该是脚本全文和当前高亮，而不是标题、说明和多行按钮。

实现状态（2026-05-28）：首轮完成。`KmdSourceContextView` 已支持无标题、无内置 surface chrome 和填满父布局；`ReviewCompanionPanel` 默认以源码全文作为主体，移除了顶部 `SectionTitle` 和顶部关闭按钮；新增内置 `ReviewCommandBar`，用横向一行承载 focus chips、issue chips、跳转、issue close/reopen、review decision 和关闭后备。2026-05-28 设计继续收束后，issue close/reopen、issue draft 和 review decision 已迁出 Review，进入独立 `Issues` companion；Review command bar 下一步将继续压缩为极简 command tray，只保留当前播放行坐标和 companion 切换。局部动作迁入 UI-4H 的源码行上下文气泡。

#### 设计原则

- 源码全文是主界面，不是面板里的一个模块。
- 顶部不放“脚本审阅”、作品标题、源码标题或关闭文字按钮。
- 关闭沿用 companion 统一契约：系统返回、双指轻点、栏外双击，以及底部栏里的轻量关闭图标。
- Review 的全局状态收束到底部 command tray；行级操作不再挤在底栏里，而是通过源码行上下文气泡触发。
- 长文本输入、issue close/reopen、issue draft 和审核结论进入 Issues companion，不在 Review 中制造第二个滚动容器。

#### 布局草案

竖屏 bottom sheet：

```text
┌──────────────────────────┐
│  KMD source viewer        │
│  L01 ...                  │
│  L02 ...  [playback]      │
│  L03 ...  [issue]         │
│  ...                      │
├──────────────────────────┤
│ 正播放 L42              ⇄ │
└──────────────────────────┘
```

横屏 side panel：

```text
┌──────────────────────────┐
│  KMD source viewer        │
│  full height              │
│  issue/playback highlight │
├──────────────────────────┤
│ 正播放 L42              ⇄ │
└──────────────────────────┘
```

#### Command Tray 收束

底部命令栏不再承担所有上下文动作，而是固定为阅读现场的全局状态条：

| 区域 | 作用 | 示例 |
|---|---|---|
| Playback locator | 展示并可滚动到当前播放源码行 | `正播放 L42` |
| Companion switch | 在 Review 和 Issues 之间切换 | `⇄` |

规则：

- command tray 固定一行，尽量不横向滚动。
- `正播放 Lxx` 是状态也是动作：点击后滚动源码列表到当前播放行，不执行 seek。
- `⇄` 是深色 companion 切换图标：Review 中打开 Issues，Issues 中返回 Review。
- 跳到此行、提 issue、查看 issue、多选等局部动作都进入 UI-4H 行级气泡。

#### 组件调整

- `KmdSourceContextView` 支持无标题模式：
  - `title: String? = null`
  - `showSurfaceChrome: Boolean = false` 或由外层 surface 承担背景。
  - `modifier.weight(1f)`，不再只靠固定 `maxHeight`。
- `ReviewCompanionPanel` 拆出：
  - `ReviewScriptViewer`
  - `ReviewCommandBar`
  - `ReviewDetailTray`
- `ReviewFocusMode` 可以先继续留在 composable 局部状态；若后续需要旋转恢复，再提升到 ViewModel。
- issue 选择不再显示多行 `IssueFocusRail`；改为源码行 marker 或行级上下文气泡中的 issue 编号入口，点击后进入 Issues companion。
- review decision 不常驻 Review；后续进入 Issues companion 的 work review summary 或独立 review decision flow。

#### 实施切片

1. 让 `KmdSourceContextView` 支持无标题、可填满剩余空间。（已完成）
2. 将 Review 默认 source mode 改为 `Full`，focus mode 只负责滚动和高亮。（已完成）
3. 移除 Review 顶部 `SectionTitle` 和顶部关闭按钮。（已完成）
4. 新增 `ReviewCommandBar`，首轮承载 `播放 / 问题 / 全文 / 备注`、选中行跳转和关闭图标。（已完成，UI-4H 将继续压缩）
5. 把 issue close/reopen、issue draft、review decision 从 Review 迁出，交给 UI-4G Issues companion。（已完成首轮）
6. 手测竖屏 bottom sheet：源码至少占 companion 高度的 80%。（待做）

#### 验收

- 打开 Review 后，主要可见区域是脚本全文。
- companion 顶部没有“脚本审阅”和作品标题占位。
- 底部只有一行 command tray；没有嵌套竖向滚动。
- 播放行、issue 行、选中行可以同时在源码中高亮。
- 选中源码行后可以从行级上下文气泡跳到下一条可播放 marker。
- issue 管理和 review decision 不在 Review 常驻；从 Review 的 issue marker 或行级上下文气泡可以打开 Issues companion。

### UI-4G：Issues Companion 与 Review-Issue Handoff

目标：把 issue 管理从 Review companion 中抽离出来，形成独立的 `Issues` companion。Review 是“脚本现场”，Issues 是“问题台账”；二者共享同一个 companion shell 的高度、placement、关闭契约和当前 work 上下文。

实现状态（2026-05-28）：首轮完成。当前已新增 `ReaderCompanionType.Issues`，`ReaderCompanionContainer` 支持 `IssuesCompanionPanel`；Review 源码行可显示 issue 编号 badge，点击 badge 会选中 issue 并打开 Issues companion；Issues companion 展示当前 work 的 issue 台账、状态、严重级别、来源、行号、详情、查看脚本、播放位、close/reopen 和本地 issue draft。Review command bar 已移除 close/reopen/review decision；剩余的现场跳转、提 issue 和 issue 入口会在 UI-4H 迁入源码行上下文气泡。

#### 设计原则

- `Issues` companion 列满当前 work/revision 的全部 issue，不嵌入 Review。
- `Review` 只显示 issue 编号/marker，不显示完整 issue 列表、关闭表单或审核结论。
- Review 与 Issues 互相切换时保持同一 `ReaderCompanionLayout`，高度和停靠方式联动。
- 切换 companion 不重建 WebView，不改变 runtime viewport，不自动 seek。
- issue 的事实来源仍是 Repository/community-api；Review 只持有 anchor/highlight。

#### 交互路径

从 Issues 到 Review：

```text
Issue row / detail
  -> SelectIssue(issueId)
  -> OpenReaderCompanion(Review)
  -> focus source range
  -> scroll KmdSourceContextView to issue line
  -> show issue marker and selected highlight
```

从 Review 到 Issues：

```text
Tap issue marker on source line
  -> SelectIssue(issueId)
  -> OpenReaderCompanion(Issues)
  -> focus issue detail
```

从 Review 行创建 issue：

```text
Select source line
  -> source line bubble "提 issue"
  -> OpenReaderCompanion(Issues)
  -> start issue draft anchored to selected line / playback anchor
```

播放跳转仍留在 Review：

```text
Select source line in Review
  -> source line bubble "跳转到此处"
  -> seek next timeline marker line >= 42
```

#### Issues Companion Layout

```text
Issues Companion
  Body:
    issue rows for current work
      #1 status severity source L42 message
      #2 status severity source L108 message
  Detail / action tray:
    selected issue summary
    view in Review
    jump playback
    close / reopen
    discussion
  Bottom Command Tray:
    问题 4  开 3  关 1                ⇄
```

规则：

- Issues 的主滚动区是 issue 列表。
- issue detail 可以作为下方 detail tray、右侧短 rail 或列表内展开，但不能再嵌入源码查看器。
- Issues 的统计和 companion 切换入口放在底部 command tray，和 Review 的 `正播放 Lxx | ⇄` 对齐。
- “查看脚本”只切到 Review 并聚焦源码行。
- “跳到播放位置”是显式动作，不因点击 issue 自动 seek。
- Review 中的 issue marker 使用稳定编号或短 badge，例如 `#1`、`#2`；编号来自当前 work issue 排序，不是 issue id。

#### 状态与 Action 草案

```kotlin
enum class ReaderCompanionType {
    Review,
    Issues,
    CommunitySummary,
    WorkInfo,
    Diagnostics
}

data class IssueFocusState(
    val selectedIssueId: String? = null,
    val selectedSourceLine: Int? = null,
    val focusedSourceRange: KmdSourceRange? = null,
    val focusedPlaybackAnchor: PlaybackAnchor? = null,
    val issueDraft: IssueDraft? = null,
    val issueStatusOverrides: Map<String, IssueStatusOverride> = emptyMap(),
    val playbackAnchorsByIssueId: Map<String, PlaybackAnchor> = emptyMap()
)
```

需要新增或调整：

- `OpenReaderCompanion(ReaderCompanionType.Issues)`
- `OpenIssueCompanion(issueId: String?)`，可先用组合 action 实现。
- `OpenReviewAtIssue(issueId: String)`，可先用 `SelectIssue + OpenReview` 组合实现。
- `StartIssueDraftFromSelectedLine`，从 Review 选中行进入 Issues draft。
- `ReaderCompanionPlacementPolicy` 对 `Review` 和 `Issues` 返回同一 layout。

#### 实施切片

1. 新增 `ReaderCompanionType.Issues` 和 placeholder panel。（已完成）
2. 从 `ReviewCommandBar` 移除 close/reopen/review decision；剩余 `打开问题`、`提问题`、`跳 Lx` 等局部动作交给 UI-4H。（已完成首轮）
3. 新增 `IssuesCompanionPanel`，先显示当前 work 全部 issue、状态、严重级别、位置和消息。（已完成）
4. 在 Issues 中实现“查看脚本”：选择 issue，切到 Review，聚焦源码行。（已完成）
5. 在 Review 中为 issue 行显示编号 marker；点击 marker 切到 Issues 并聚焦 issue。（已完成）
6. 将 issue close/reopen、issue draft 从 Review 迁到 Issues。（已完成首轮）
7. 手测 Review <-> Issues 切换时 WebView 不重建、companion 高度不跳变。（待做）

#### 验收

- Review 中看不到完整 issue 列表和 issue 管理表单。
- Issues 中能看到当前 work 所有 issue，并能 close/reopen 本地状态。
- 从 Issues 点击“查看脚本”会打开 Review 并滚动到 issue 行。
- 从 Review 点击 issue marker 会打开 Issues 并显示 issue 详情。
- Review 与 Issues 的高度和 placement 一致，切换不影响 runtime 播放。

### UI-4H：Source Line Context Bubble

目标：将 Review 中“针对某一行/某一段源码”的动作从底部 command tray 中迁出，做成点击源码行后出现的行级上下文气泡。底部 command tray 只保留全局阅读现场信息，源码行气泡负责局部操作。

实现状态（2026-05-28）：首轮完成。当前 `KmdSourceContextView` 支持 `scrollRequestKey` 和行级 `lineContext` 插槽；Review 底部已压缩为 `正播放 Lxx | ⇄`；点击源码行会在该行下方显示 `SourceLineContextBubble`，首版提供紧凑 chip 形式的 `跳转`、`提 issue`、issue 编号入口、`多选`占位和关闭按钮。气泡外点击、点击同一行、切换 companion 或系统返回会关闭气泡；后续可补滚动关闭，并把内联气泡升级为真正贴近行坐标的悬浮气泡。Issues companion 的状态栏也已迁到底部，与 Review 的 command tray 保持同一心智。

#### 设计原则

- command tray 是全局状态条，不是上下文菜单。
- 源码行气泡是“行对象”的上下文菜单，只展示与当前行、当前选区或当前行 issue 相关的动作。
- 气泡贴近被点击行出现；点击气泡外、滚动源码、切换 companion 或系统返回时关闭。
- 气泡不嵌套滚动，不展示长表单；需要长输入或完整 issue 管理时跳到 Issues companion。
- 行动作不直接修改 `.kmd` 源码；轻度修改和 patch 仍属于未来 editor/editor-companion 范畴。

#### 心智模型

```text
Review companion
  Source viewer:
    tap line -> SourceLineContextBubble

  Command tray:
    正播放 L42                 ⇄

Issues companion
  Issue ledger / draft / close / reopen / discussion
```

#### 气泡内容分层

MVP 行动作：

- `跳转到此处`：seek 到当前行之后第一条可播放 marker。
- `提 issue`：以当前行或当前选区为 source anchor，打开 Issues draft。
- `多选`：进入选区模式，先作为占位能力，后续支持多行 issue anchor。

已有 issue 的行：

- 显示当前行 issue 编号，例如 `#1`、`#2`。
- 点击编号打开 Issues companion 并聚焦对应 issue。
- 如果 issue 已在本地关闭，编号使用 closed 状态样式，但 close/reopen 主操作仍放在 Issues。

播放行：

- `正播放` 状态标识可以出现在气泡内，但不替代 command tray 的全局播放行坐标。
- `跳转到此处` 对播放行仍可用，用于重新 seek 到该行最近 marker。

多选模式：

- 单击第二行后形成连续选区。
- 气泡切换为 `为选区提 issue`、`复制选区锚点`、`取消多选`。
- MVP 可以先只保留 `多选` 占位，不提交复杂选区 anchor。

#### 状态草案

```kotlin
data class SourceLineContextState(
    val activeLine: Int? = null,
    val selectedRange: KmdSourceRange? = null,
    val mode: SourceLineContextMode = SourceLineContextMode.SingleLine
)

enum class SourceLineContextMode {
    SingleLine,
    RangeSelecting
}
```

可先放在 composable 局部状态中实现；如果旋转恢复、多 companion 同步或测试需求变强，再提升到 `IssueFocusState` 或单独的 `ReaderSourceContextState`。

#### 实施切片

1. 将 Review command tray 收束为 `正播放 Lxx` 和 `⇄`。（已完成首轮）
2. 点击 `正播放 Lxx` 只滚动源码列表到当前播放行，不 seek。（已完成首轮）
3. 新增 `SourceLineContextBubble`，点击源码行后显示 `跳转到此处 / 提 issue / 多选`。（已完成首轮）
4. 对已有 issue 的行，在气泡中显示 issue 编号入口，点击后打开 Issues 并聚焦 issue。（已完成首轮）
5. 让 `提 issue` 从气泡进入 Issues draft，anchor 优先使用当前行/选区，其次才是播放行。（已完成首轮，选区 anchor 待多选实现）
6. 加入气泡关闭规则：点击外部、滚动、切换 companion、系统返回。（部分完成：点击外部、点击同一行、切换 companion、系统返回和气泡内 `×` 已可关闭；滚动关闭待做）

#### 验收

- Review 底部只剩一行全局 command tray，不再出现 issue 名称、编号摘要、选中行 chip 或多个文字按钮。
- 点击 `正播放 Lxx` 会滚动源码到播放行；重复点击同一行也能重新定位。
- 点击源码行出现行级气泡，气泡不遮挡大量源码。
- 从气泡点 `跳转到此处` 会 seek 到该行之后第一条可播放 marker。
- 从气泡点 `提 issue` 会打开 Issues draft，并锚定当前行。
- 含 issue 的源码行可以从气泡进入 Issues companion 并聚焦对应 issue。

### UI-5：错误、诊断和恢复体验

目标：所有 runtime 失败都要可解释、可操作。

实现状态（2026-05-21）：已完成首轮恢复体验。当前 `ReaderSessionState.Failed` 已保存 phase、sessionId、capabilities 和 bridge diagnostics；阅读页会显示错误面板、重试、返回详情和诊断折叠区；`RetryReaderRuntime` 会刷新 WebView 宿主 token 并重新载入当前作品。renderer crash 仍继续输出 logcat 分段报告。

任务：

- `ReaderSessionState.Failed` 时显示错误面板，包含：
  - workId
  - phase
  - error code
  - message
  - retry / 返回详情 / 查看诊断
- 新增 `RetryReaderRuntime` action，重新发起当前 work 的 `OpenReader` 流程。
- renderer crash 继续输出 logcat `Renderer crash report part N`。
- UI 层不要展示 DOM/Pixi probe；probe 只在 debug flag 下启用。
- 将最近一次 runtime command id、sessionId、capabilities 摘要放进 debug 详情。

验收：

- JS 初始化失败、source 缺失、renderer gone 都能进入可见失败态。
- 用户可以返回详情或重试。
- renderer gone 后不会继续 `evaluateJavascript` 到已销毁 WebView。

### UI-6：进度持久化与恢复

目标：让阅读页逐渐从演示变成可用阅读器。

任务：

- 设计 `ReadingProgressRecord`：
  - workId
  - revisionId
  - progress
  - timeMs
  - updatedAt
- `progressChanged` 节流写入 Room。
- 进入阅读时读取上次进度。
- 如果 runtime 支持 `supportsSeekTime`，优先按 timeMs 恢复；否则按 progress 恢复。
- WebView reload 或 Activity 重建后尝试恢复。

验收：

- 播放到中段退出，再进入可以恢复到附近位置。
- 不因每秒 progress 事件造成数据库写入过密。
- 不影响课程 MVP 的稳定性。

## 6. 推荐实现顺序

近期优先级：

1. UI-4H 手测与手感微调：行气泡位置、重复点击滚动、外部/滚动关闭。
2. UI-4G Review/Issues handoff 手测：确认 WebView 不重建、companion 高度不跳变。
3. UI-4E 手测与尺寸微调。
4. UI-3 横屏观看入口、横屏提示与旋转恢复策略。
5. UI-5 错误恢复体验继续收束，尤其是诊断 companion 化和 debug probe 收束。
6. UI-2 双指点击实机验证与手感微调。
7. UI-6 进度持久化。

原因：

- UI-1 已完成首轮状态化，后续只在具体体验里修边。
- UI-4A-G 已经完成首轮闭环，UI-4H 是把 Review 操作心智收束为“全局状态条 + 行级上下文”的最后一次界面清理。
- UI-3 为横屏/竖屏作品打地基，下一步应补可见入口和恢复说明。
- UI-5 直接提升演示观感和稳定感，但首轮错误页已可用，可以和诊断 companion 后续整合。
- UI-2 已接入宿主观察器，下一步是确认不会影响 runtime 内部手势。
- UI-6 需要数据库 schema 和节流策略，放在 UI 主体验稳定后做。

## 7. 代码落点

已新增 / 预计新增：

- `presentation/ReaderChromeState.kt`
- `presentation/ReaderViewportState.kt`
- `presentation/ReaderCompanionState.kt`
- `presentation/IssueFocusState.kt`
- `domain/policy/ReaderViewportPolicy.kt`
- `domain/policy/ReaderCompanionPlacementPolicy.kt`
- `domain/model/KmdSourceSnapshot.kt`
- `domain/model/KmdSourceAnchor.kt`
- `ui/screen/reader/ReaderChromeOverlay.kt`
- `ui/screen/reader/ReaderCompanionContainer.kt`
- `ui/screen/reader/ReviewCompanionPanel.kt`，当前先内置于 `ReaderCompanionContainer.kt`，后续可拆文件。
- `ui/screen/reader/ReviewCommandBar.kt`，UI-4F 可先内置于 `ReaderCompanionContainer.kt`，稳定后拆出。
- `ui/screen/reader/IssuesCompanionPanel.kt`，UI-4G 可先内置于 `ReaderCompanionContainer.kt`，稳定后拆出。
- `ui/screen/reader/SourceLineContextBubble.kt`，UI-4H 可先内置于 `ReaderCompanionContainer.kt`，稳定后拆出。
- `ui/screen/reader/IssueDetailTray.kt`，用于 issue draft、close/reopen reason 和 future discussion action。
- `ui/component/source/KmdSourceContextView.kt`
- `ui/component/source/KmdSourceLineRow.kt`

预计修改：

- `presentation/KmdReaderState.kt`
- `presentation/KmdReaderAction.kt`
- `presentation/KmdReaderReducer.kt`
- `presentation/KmdReaderViewModel.kt`
- `runtime/ReaderRuntimeBridge.kt`，中期增加 viewport command。
- `runtime/webview/RuntimeMessageCodec.kt`，中期增加 `setViewport`。
- `ui/screen/reader/ReaderDesk.kt`
- `ui/screen/reader/ReaderRuntimeHost.kt`
- `ui/app/KmdReaderApp.kt`
- `data/WorkRepository.kt`，暴露源码快照或 source metadata。

## 8. 测试计划

单元测试：

- `ReaderViewportPolicyTest`
- `ReaderCompanionPlacementPolicyTest`
- `KmdReaderReducerChromeTest`
- `KmdReaderReducerCompanionTest`
- `KmdReaderViewModelTest`
- `RuntimeMessageCodecViewportTest`，在新增协议后补。

手动测试：

| 编号 | 场景 | 预期 |
|---|---|---|
| RUI-01 | 进入阅读页 | runtime 全屏加载，普通顶部导航隐藏 |
| RUI-02 | 播放后等待 | 控制浮层自动收起或 dimmed |
| RUI-03 | 暂停 | 控制浮层常显 |
| RUI-04 | 拖动进度 | 松手才提交 seek，runtime 不崩 |
| RUI-05 | 双指轻点 | 浮层显示/隐藏，单指事件仍给 runtime |
| RUI-06 | 打开审阅 | runtime 保持可见，审阅作为 bottom sheet/side panel 出现 |
| RUI-07 | 横屏作品竖屏播放 | 16:9 letterbox，不裁切关键内容 |
| RUI-08 | renderer gone / source error | 显示失败态，可返回或重试 |
| RUI-09 | 旋转屏幕 | 不崩溃，阅读状态可恢复或有解释 |
| RUI-10 | 竖屏打开审阅 companion | 显示 bottom sheet，双指轻点/系统返回可关闭，不重建 WebView |
| RUI-11 | 横屏/宽屏打开审阅 companion | 显示 side panel，单击 runtime 区域不误关，双指轻点/系统返回可关闭 |
| RUI-12 | 源码全文中点击行并播放选中行 | 跳到该行之后第一条可播放 marker |
| RUI-13 | 从 Review 点击 issue marker | 打开 Issues companion，聚焦对应 issue，companion 高度不跳变 |
| RUI-14 | 从 Issues 点击查看脚本 | 打开 Review companion，聚焦对应源码行，不自动 seek |
| RUI-15 | 点击 Review command tray 的正播放行 | 源码列表滚动到当前播放行，不触发 seek |
| RUI-16 | 点击源码行 | 出现行级上下文气泡，可跳转、提 issue、进入多选占位 |
| RUI-17 | 点击含 issue 行的气泡编号 | 打开 Issues companion 并聚焦对应 issue |

## 9. 设计交付物

Figma 或 Compose prototype 至少覆盖：

- `Reader / Loading`
- `Reader / Ready Controls`
- `Reader / Playing Hidden`
- `Reader / Paused`
- `Reader / Review Sheet`
- `Reader / Review Side Panel`
- `Reader / Error`
- `Reader / Landscape Letterbox`

UI 文案不要出现“桌面”。用户只看到“阅读”“检查”“返回详情”“播放”“暂停”等语义。

## 10. 完成定义

本计划第一轮完成的标准：

- 阅读页全屏 runtime 稳定，不再回到小窗口模型。
- 播放浮层有状态，可显示、隐藏、暂停常显。
- 横屏/竖屏作品使用统一 viewport policy。
- Review companion 只承担源码现场、全局播放坐标和行级上下文气泡；Issues companion 承担 issue 台账和管理。
- companion 在竖屏、横屏和诊断场景下有明确 placement，并共享统一关闭契约。
- runtime 失败能被用户理解并处理。
- `./gradlew :app:testDebugUnitTest` 与 `./gradlew :app:assembleDebug` 通过。

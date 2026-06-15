# Runtime UI Extraction Plan

> 文档状态：实施草案
> 最近更新：2026-05-28

## 1. 目标

本文整理 Android Reader 在当前 runtime UI 阶段值得抽出来单独做的东西。原则是：

> 抽稳定边界，不抽未来幻想。

当前最重要的是把阅读、审阅、源码上下文和 runtime 事件之间的边界变清楚，而不是提前实现完整社区客户端或移动端 editor。

## 2. 抽取判断标准

值得现在抽出来的模块应满足：

- 已经被两个以上 UI 场景使用，或马上会被复用。
- 输入输出能用普通数据模型描述，不依赖 Compose 页面上下文。
- 不需要直接知道 WebView、Repository 或 community-api。
- 抽出后可以降低 UI-4D/E、UI-5、UI-6 的重复和状态缠绕。

暂不抽：

- 未来视觉化 script editor。
- 完整源码编辑器。
- 社区 discussion 全量客户端。
- Kotlin 版 KMD parser/layout/runtime。
- 跨平台设计系统。

## 3. 当前应抽出的东西

### 3.1 `KmdSourceContextView`

只读源码上下文组件。

职责：

- 显示 snippet 或全文源码。
- 行号。
- 当前播放行高亮。
- issue/source range 高亮。
- 选中单行或多行，回调给上层生成 issue/review/discussion anchor。
- 支持展开上下文，例如 `L42 ± 2` 到 `L42 ± 10`。
- 作为 Review 的主滚动区，承载 playback / issue marker / selection / search 的统一 focus。

不负责：

- 编辑源码。
- 语法补全。
- 保存修改。
- 直接调用 Repository 或 runtime。

建议输入：

```kotlin
data class KmdSourceContextState(
    val snapshot: KmdSourceSnapshot?,
    val mode: SourceContextMode,
    val focusedLine: Int? = null,
    val playbackLine: Int? = null,
    val highlights: List<KmdSourceHighlight> = emptyList(),
    val selectedRange: KmdSourceRange? = null
)
```

当前 `ReaderCompanionContainer.SourceSnippetBox` 可以先演化为这个组件。

### 3.2 Source Anchor / Highlight 模型

把“源码第几行”从 UI 文字里抽出来。

建议模型：

```kotlin
data class KmdSourceRange(
    val startLine: Int,
    val startColumn: Int? = null,
    val endLine: Int = startLine,
    val endColumn: Int? = null
)

data class KmdSourceHighlight(
    val range: KmdSourceRange,
    val kind: KmdSourceHighlightKind,
    val label: String? = null
)

enum class KmdSourceHighlightKind {
    Playback,
    Issue,
    Selection,
    Discussion,
    Search
}
```

这些模型未来可以映射到 community-api 的 `DiscussionReference(source_range)`。

### 3.3 Issue Focus / Draft 模型

Issue 提出、跳转和关闭需要单独的 UI 状态，不应塞进 issue 列表项本身。

建议模型：

```kotlin
data class IssueFocusState(
    val selectedIssueId: String? = null,
    val focusedSourceRange: KmdSourceRange? = null,
    val focusedPlaybackAnchor: PlaybackAnchor? = null,
    val issueDraft: IssueDraft? = null,
    val closeDraft: IssueCloseDraft? = null
)
```

职责：

- 记录当前聚焦 issue。
- 让源码上下文滚动/高亮到 issue anchor。
- 暂存从播放行或源码范围创建的新 issue draft。
- 暂存 close/reopen 的 reason 和 note。

不负责：

- 直接提交后端。
- 修改源码。
- 管理 discussion thread。

### 3.4 Playback Position 模型

当前 `ReaderSessionState.Ready` 已保存 `currentLine`、`currentMarkerId`、`timelineMarkers`。下一步可以抽轻量只读视图：

```kotlin
data class ReaderPlaybackPosition(
    val workId: String,
    val progress: Float,
    val timeMs: Long?,
    val durationMs: Long?,
    val line: Int?,
    val markerId: String?
)
```

用途：

- 播放浮层。
- 审阅 companion。
- 进度持久化。
- discussion/review anchor。

不建议让 UI 到处直接读完整 `ReaderSessionState.Ready`，否则 UI 会继续和 runtime session 生命周期耦合。

### 3.5 `ReaderCompanionContainer`

已经抽出，继续保持它是“容器”，不是业务本体。

职责：

- placement：bottom sheet / side panel / overlay。
- active companion：review / issues / info / diagnostics / community summary。
- 统一关闭契约：系统返回、双指轻点、栏外双击。
- 不为 Review 单独提供展开/收起语法。
- 保证 Review 与 Issues 使用同一 layout 策略，切换时高度不跳变。
- 与 Reader chrome 的层级关系。

不负责：

- issue 数据加载。
- discussion 网络请求。
- 源码解析。
- WebView 生命周期。
- companion 内部业务滚动；Review 的长滚动应交给 `KmdSourceContextView`，Issues 的长滚动应交给 issue ledger。

### 3.6 Runtime Event Codec

`RuntimeMessageCodec` 应继续承担协议翻译，不让 ViewModel 直接解析 JSON 字段。

近期应稳定：

- `ready.timelineMarkers`
- `progressChanged.line`
- `progressChanged.markerId`
- `inspectionReported.issues`
- renderer/runtime error metadata

这属于 runtime bridge 的边界，不属于 UI 组件。

## 4. 可以晚点抽的东西

### Review Draft

当审阅面板开始支持“建议修改”“选中范围”“提交讨论”时，再抽：

```kotlin
data class ReviewDraft(
    val workId: String,
    val revisionId: String,
    val anchors: List<ReviewAnchor>,
    val note: String,
    val decision: ReviewDecision?
)
```

现在只保留 review message 和 issue 列表即可。

### Discussion Client

discussion 需要 community-api 模型稳定后再做。当前只在 UI 上预留入口，不要为了占位实现半套 thread store。

### Full Source Viewer

如果 `KmdSourceContextView` 支持 snippet/full mode，就不需要再单独做一个大组件。真正的大型编辑体验应进入 mobile editor 方向。

## 5. 推荐实施顺序

1. 把 `SourceSnippetBox` 抽为 `KmdSourceContextView`，保持只读。（已落地首版，支持 snippet / full 两种模式）
2. 新增 `KmdSourceRange` / `KmdSourceHighlight`。（已落地基础模型）
3. 在 review companion 中用 highlight 表达 issue marker、播放行、选中行。（已在 UI-4D/F 首轮落地）
4. 新增 `IssueFocusState`，让 issue 点击后可以在 Issues 中聚焦详情，并能跳回 Review 源码行。（UI-4D 已有基础状态，UI-4G 待抽 companion）
5. 把 `ReaderPlaybackPosition` 作为 `ReaderSessionState.Ready` 的派生视图。当前源码行 seek 已先按 `timelineMarkers.line >= selectedLine` 实现。
6. 抽出 `IssuesCompanionPanel`，承载 issue ledger、draft、close/reopen 和跳回 Review。（UI-4G）
7. 新增 `SourceLineContextBubble`，承载跳到此处、提 issue、issue 编号入口和多选占位；Review command tray 只保留当前播放行和 companion 切换。（UI-4H 已落地首版）
8. 再考虑 review draft 和 discussion anchor。（本地 issue draft 已落地；discussion anchor 待 community-api）

## 6. 文件落点建议

```text
domain/model/KmdSourceSnapshot.kt
domain/model/KmdSourceAnchor.kt

presentation/ReaderPlaybackPosition.kt   # 当前为内联 data class（见上文），未抽出独立文件
presentation/IssueFocusState.kt

ui/component/source/KmdSourceContextView.kt
ui/component/source/KmdSourceLineRow.kt

ui/screen/reader/ReaderCompanionContainer.kt
ui/screen/reader/ReviewCompanionPanel.kt
ui/screen/reader/SourceLineContextBubble.kt
```

如果组件只服务阅读页，先放 `ui/screen/reader/`；一旦书架详情、review overlay 或未来 editor 也要使用，再移动到 `ui/component/source/`。

## 7. 验收

- 打开审阅不重建 WebView。
- 当前播放行可在源码上下文中高亮。
- issue 定位和播放行高亮不会互相覆盖。
- 定位片段默认带前后文；审阅 companion 中保留全文源码视图，并自动滚到当前 focus 行。
- 选中源码行可以跳到其后的第一条可播放 marker，不回跳到上一段。
- 点击源码行出现上下文气泡，行级动作不挤占底部 command tray。
- 选中源码范围只产生 UI 状态，不直接写社区数据。
- 组件不直接依赖 Repository、ViewModel 或 WebView。

# KMD Reader Android 应用架构

> 项目阶段：第二阶段架构设计
> 文档状态：草案
> 最近更新：2026-05-26

## 1. 架构目标

本项目的长期原则是：

> 系统最大健康性，长期痛苦最小化。

这意味着第二阶段即使只做页面原型，也不把所有逻辑塞进 `MainActivity.kt`。UI 可以先简单，但边界要从一开始就清楚：

- 页面可以快速迭代。
- 数据可以从 mock 平滑迁移到 Room 或远程 API。
- KMD Runtime 不在 Android 端重复实现。
- 活动桌面式导航可以先用本地状态验证，后续再接 Navigation Compose。
- 书架、发现、审核、导入、阅读都依附同一套作品模型，而不是各做一份数据。

## 2. 分层原则

应用采用轻量分层架构：

```text
ui
  -> presentation
      -> domain
          -> data
              -> runtime bridge
```

各层职责：

| 层 | 职责 |
|----|------|
| ui | Compose 页面、组件、样式、交互事件发出 |
| presentation | UI 状态、桌面导航状态、ViewModel、事件归约 |
| domain | 业务模型、用例、规则，例如作品互斥、审核状态 |
| data | repository、mock 数据、未来 Room/网络数据源 |
| runtime bridge | Android 与 KMD Reader Runtime 的通信边界 |

第二阶段可以先不完整实现所有层，但包结构应提前按这个方向准备。

2026-05-26 运行时状态机补充：阅读页的 WebView transport 就绪不等于作品源码已经加载成功。`KmdReaderViewModel` 必须把 `.kmd` source 获取失败视为终态错误，不能被随后到达的 `TransportReady` 覆盖回“Runtime 已连接 / 正在载入作品”。`ReaderRuntimeBridge.prepareLoad(workId)` 用来声明当前目标作品，并清理旧 `loadScript`；只有纯 WebView rebind 才允许重放仍属于当前作品的 `lastLoadRequest`。

2026-05-26 书架边界补充：书架是正式产品能力，不是 mock fallback 或个人设置页。数据层需要区分 `LocalLibrary`（导入作品、随包示例、本地草稿、阅读进度、收藏、缓存）和 `CommunityDiscovery`（社区 API 元数据与发现流）。两者可以共享 `Work` 模型，但必须暴露作品是否有本地 source、是否可离线阅读、是否需要联网。

## 3. 推荐包结构

当前包名为 `com.example.kmd_reader`。课程阶段可以先保留，后续发布前再考虑改为正式包名。

推荐结构：

```text
com.example.kmd_reader/
  MainActivity.kt
  ui/
    app/
      KmdReaderApp.kt
      DeskHost.kt
      AppTopBar.kt
    screen/
      library/
        LibraryDesk.kt
      discovery/
        DiscoveryDesk.kt
        FilterOverlay.kt
      work/
        WorkDetailDesk.kt
      reader/
        ReaderDesk.kt
      importkmd/
        ImportDesk.kt
      review/
        ReviewOverlay.kt
    component/
      WorkCard.kt
      WorkMetaChips.kt
      PreviewFrame.kt
      EmptyState.kt
    theme/
  presentation/
    Desk.kt
    DeskStackState.kt
    KmdReaderState.kt
    KmdReaderAction.kt
    KmdReaderViewModel.kt
  domain/
    model/
      Work.kt
      WorkAttributes.kt
      CommentSummary.kt
      ReviewTask.kt
      ScriptIssue.kt
    policy/
      DeskStackPolicy.kt
      WorkPresentationPolicy.kt
  data/
    WorkRepository.kt
    MockWorkRepository.kt
    mock/
      MockWorks.kt
  runtime/
    ReaderRuntimeBridge.kt
    ReaderRuntimeMessage.kt
```

第二阶段可以从较少文件开始，但不要回到“一个文件无限增长”的形态。

## 4. 状态管理

### 4.1 单向数据流

UI 不直接修改复杂业务状态，而是发出 action：

```text
Composable
  -> KmdReaderAction
  -> ViewModel / reducer
  -> KmdReaderState
  -> Composable
```

这样后续加搜索、审阅、导入、Runtime 回调时，不会在多个页面里各自改状态。

### 4.2 全局 UI 状态

推荐状态：

```kotlin
data class KmdReaderState(
    val deskStack: DeskStackState,
    val works: List<Work>,
    val selectedWorkId: String?,
    val filter: WorkFilter,
    val reviewDraft: ReviewDraft?,
    val readerSession: ReaderSession?
)
```

第二阶段可以只实现其中一部分：

- `deskStack`
- `works`
- `selectedWorkId`
- `isSearchOpen`
- `isReviewOpen`

### 4.3 Action 设计

推荐 action：

```kotlin
sealed interface KmdReaderAction {
    data object OpenLibrary : KmdReaderAction
    data object OpenDiscovery : KmdReaderAction
    data class OpenWork(val workId: String) : KmdReaderAction
    data object OpenReader : KmdReaderAction
    data object OpenImport : KmdReaderAction
    data object CloseCurrentDesk : KmdReaderAction
    data object OpenSearch : KmdReaderAction
    data object CloseSearch : KmdReaderAction
    data object OpenReview : KmdReaderAction
    data object CloseReview : KmdReaderAction
}
```

## 5. 活动桌面导航

活动桌面导航是本项目 UI 架构的核心。它不是普通 Tab，也不是无限导航栈。

### 5.1 状态模型

```kotlin
enum class Desk {
    Library,
    Discovery,
    Detail,
    Reader,
    Import
}

data class DeskStackState(
    val base: List<Desk> = listOf(Desk.Library, Desk.Discovery),
    val extension: List<Desk> = emptyList(),
    val activeIndex: Int = 1,
    val currentWorkId: String? = null,
    val isSearchOpen: Boolean = false,
    val isReviewOpen: Boolean = false
) {
    val desks: List<Desk>
        get() = base + extension
}
```

`activeIndex` 的默认值可以根据产品验证调整：偏常规阅读器时默认打开 `Library`，偏社区发现时默认打开 `Discovery`。无论默认页如何，`Library` 在条带中始终位于 `Discovery` 左侧。

### 5.2 桌面策略

桌面变化放在 `DeskStackPolicy`，不要散落在页面按钮里。

核心规则：

- `Library` 和 `Discovery` 永远保留。
- 打开作品时，替换所有右侧临时桌面，然后追加 `Detail`。
- 打开阅读时，如果已有 `Reader` 则切换过去，否则追加 `Reader`。
- 打开导入时，替换导入流程相关桌面。
- 打开另一个作品时，销毁旧详情、旧阅读、旧审阅。
- 左右滑动只改变 `activeIndex`，不销毁桌面。
- 返回或关闭动作才回收右侧桌面。

### 5.3 实现建议

第二阶段可以用 Compose `HorizontalPager` 做可滑动桌面。若当前依赖中没有 pager，可先用按钮切换验证状态模型，再补 foundation pager 依赖。

为了降低实现风险：

- 第一版允许顶部标签点击切换。
- 第二版再加入左右滑动。
- 页面条带最多保留 `Library + Discovery + Detail + Reader`。
- 搜索和审阅先做 overlay，不进入桌面列表。

## 6. 领域模型

### 6.1 Work

作品是整个应用的中心实体。

```kotlin
data class Work(
    val id: String,
    val title: String,
    val authorName: String,
    val description: String,
    val tags: List<String>,
    val category: String,
    val sourceType: WorkSourceType,
    val lifecycleStatus: WorkLifecycleStatus,
    val presentation: WorkPresentation,
    val contentUri: String,
    val previewUri: String?,
    val script: KmdScriptRef,
    val assetManifest: WorkAssetManifest?,
    val estimatedDurationSec: Int
)
```

`Work` 是社区作品实体，不直接内联 `.kmd` 文本。真实脚本文档通过 active revision 绑定：

```kotlin
data class KmdScriptRef(
    val activeRevisionId: String,
    val activeRevision: KmdScriptRevision,
    val revisions: List<KmdScriptRevision>
)

data class KmdScriptRevision(
    val id: String,
    val label: String,
    val sourceUrl: String,
    val mimeType: String,
    val kmdVersion: String,
    val runtimeVersion: String,
    val createdAt: String,
    val contentHash: String?
)
```

Android 列表和详情先加载 `Work` metadata；进入阅读时再通过 `WorkRepository.getWorkSource(workId)` 获取 `text/x-kmd` source，并把 `work + source + assetManifest` 交给 runtime。

### 6.2 WorkPresentation

用于统一纵向、分页、横屏舞台、互动式 KMD。

```kotlin
data class WorkPresentation(
    val mode: PresentationMode,
    val orientationHint: OrientationHint,
    val aspectRatio: String,
    val interactionLevel: InteractionLevel,
    val previewMode: PreviewMode
)
```

推荐枚举：

```kotlin
enum class PresentationMode { Scroll, Paged, Stage, Interactive }
enum class OrientationHint { Portrait, Landscape, Adaptive }
enum class InteractionLevel { None, Light, Choice }
enum class PreviewMode { Static, Animated, Runtime }
```

### 6.3 Review

审核是作品详情和阅读视图的附属能力，不是独立一级模块。

```kotlin
data class ScriptIssue(
    val id: String,
    val workId: String,
    val severity: IssueSeverity,
    val source: IssueSource,
    val location: String,
    val message: String,
    val suggestion: String
)
```

## 7. 数据层

### 7.1 Repository 边界

UI 和 ViewModel 不直接知道数据来自 mock、Room 还是网络。

```kotlin
interface WorkRepository {
    suspend fun listWorks(): List<Work>
    suspend fun getWork(id: String): Work?
    suspend fun listIssues(workId: String): List<ScriptIssue>
    suspend fun getWorkSource(workId: String): String?
}
```

第二阶段使用：

```text
MockWorkRepository
```

后续替换为：

```text
RoomWorkRepository
RemoteWorkRepository
CompositeWorkRepository
```

### 7.2 数据迁移路线

```text
hardcoded mock
  -> MockWorkRepository
  -> Room + mock seed data
  -> Room + remote API
```

## 8. Runtime Bridge

Android 端不实现 KMD parser、layout 或 renderer。Reader Runtime 是外部能力，Android 只做宿主。

ViewModel 与 Runtime Bridge 的详细演进规划见：

```text
docs/planning/runtime/viewmodel-runtime-plan.md
```

### 8.1 Bridge 边界

```kotlin
interface ReaderRuntimeBridge {
    fun load(work: Work, mode: ReaderRuntimeMode)
    fun seek(positionPayload: String)
    fun updateSettings(settings: ReaderSettings)
}
```

Runtime 回调：

```kotlin
sealed interface ReaderRuntimeEvent {
    data object Ready : ReaderRuntimeEvent
    data class ProgressChanged(val progress: Float, val payload: String) : ReaderRuntimeEvent
    data class InspectionReported(val issues: List<ScriptIssue>) : ReaderRuntimeEvent
    data class Failed(val message: String) : ReaderRuntimeEvent
}
```

第二阶段只做 `ReaderDesk` 占位，不接 WebView。文档和包结构先保留 `runtime/`，避免后面接入时打穿 UI。

## 9. UI 组件原则

页面只负责组合组件，通用展示下沉到 `ui/component`：

- `WorkCard`
- `WorkMetaChips`
- `PreviewFrame`
- `IssueList`
- `DeskTopBar`
- `OverlayPanel`

组件不直接读取 repository，不持有全局业务状态。

## 10. 实现节奏

### 10.1 第二阶段最小健康实现

先实现：

- `domain/model` 的核心数据类。
- `data/MockWorkRepository`。
- `presentation/DeskStackState` 和 `DeskStackPolicy`。
- `ui/app/KmdReaderApp`。
- `LibraryDesk`、`DiscoveryDesk`、`WorkDetailDesk`、`ReaderDesk`。
- 搜索浮层和审阅浮层可以先放在页面内，但 action 和状态要走统一入口。

暂缓：

- Room。
- WebView。
- Navigation Compose。
- 真实文件导入。
- 权限系统。

### 10.2 后续演进

```text
第二阶段：Compose 静态原型 + mock 数据
第三阶段：Room + 本地导入 + 状态持久化
第四阶段：WebView Reader Runtime Bridge
第五阶段：审核检查结果和远程社区 API
```

## 11. 架构约束

- 不在 Android 端复制 KMD parser。
- 不在 UI 组件中写数据源逻辑。
- 不让 `MainActivity.kt` 承担页面实现。
- 不让桌面导航规则散落在多个 Composable 中。
- 不让审核变成独立一级 Tab。
- 不假设所有 KMD 都是纵向阅读。
- 不为了第二阶段截图牺牲长期包结构。

## 12. 下一步

建议下一步先创建包结构和空骨架：

```text
domain/model
data
presentation
ui/app
ui/screen
ui/component
runtime
```

然后用 mock 数据实现第一版 `KmdReaderApp`。这样我们能在长期边界清楚的前提下，快速得到可截图的页面原型。

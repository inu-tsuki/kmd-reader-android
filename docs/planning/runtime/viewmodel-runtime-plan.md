# KMD Reader Android ViewModel 与 Runtime Bridge 规划

> 项目阶段：第三阶段之后的应用骨架规划
> 文档状态：草案
> 最近更新：2026-05-14

## 1. 目标

当前项目已经具备：

- Compose UI 原型。
- 活动页面条带导航。
- Room 数据库层。
- Retrofit 网络层。
- `OfflineFirstWorkRepository`。

下一步不能直接把 Repository、WebView 和阅读状态塞进 Composable。我们需要先补齐两条长期边界：

```text
UI -> ViewModel -> Repository
UI -> Runtime Host -> Runtime Bridge -> KMD Web Runtime
```

目标：

- UI 只展示状态和发出 action。
- ViewModel 管理页面状态、数据加载、副作用和一次性事件。
- Runtime Bridge 只负责 Android 与 KMD Web/Editor runtime 的通信。
- Android 不复制 KMD parser、layout、renderer。
- WebView 是 runtime 宿主，不是业务状态中心。

## 2. 总体分层

推荐目标结构：

```text
com.example.kmd_reader/
  presentation/
    KmdReaderViewModel.kt
    KmdReaderAction.kt
    KmdReaderState.kt
    KmdReaderEffect.kt
    KmdReaderReducer.kt
    ReaderSessionState.kt
  data/
    repository/
      OfflineFirstWorkRepository.kt
      WorkMappers.kt
  runtime/
    ReaderRuntimeBridge.kt
    ReaderRuntimeEvent.kt
    ReaderRuntimeCommand.kt
    ReaderRuntimeState.kt
    webview/
      WebViewReaderRuntimeBridge.kt
      RuntimeMessageCodec.kt
  ui/
    app/
      KmdReaderApp.kt
    screen/
      reader/
        ReaderDesk.kt
        ReaderRuntimeHost.kt
```

关键原则：

- `ViewModel` 可以依赖 `Repository` 和 `ReaderRuntimeBridge` 抽象。
- `Composable` 不直接依赖 DAO、Retrofit 或具体 WebView bridge。
- `runtime/webview` 可以依赖 Android WebView，但不能依赖 Compose 页面。
- `data` 层不知道 runtime 层存在。
- `runtime` 层不知道 Room、Retrofit、桌面导航存在。

## 3. ViewModel 规划

### 3.1 当前问题

当前 `KmdReaderApp` 直接：

- `remember { MockWorkRepository() }`
- `mutableStateOf(KmdReaderState(...))`
- 调用 `KmdReaderReducer.reduce(...)`
- 在 `ReviewOverlay` 中同步读取 `repository.listIssues(...)`

这对原型是可接受的，但接入真实数据后会产生问题：

- Repository 函数已经进入 suspend / offline-first 方向。
- Composable 不适合发网络请求、写 Room、处理失败重试。
- 审阅问题、阅读进度、runtime 事件都需要异步流。
- 旋转屏幕或进程恢复时，纯 `remember` 状态太脆。

### 3.2 ViewModel 职责

`KmdReaderViewModel` 负责：

- 初始化作品列表。
- 响应 `KmdReaderAction`。
- 调用 `OfflineFirstWorkRepository`。
- 维护 `KmdReaderState`。
- 管理加载、错误、刷新时间。
- 拉取当前作品的 `ScriptIssue`。
- 接收 runtime event，并转化为 UI state。
- 发出一次性 UI effect。

不负责：

- 创建或持有 WebView。
- 解析 KMD 脚本。
- 渲染 KMD 内容。
- 直接操作 Compose pager。

### 3.3 State 拆分

建议让 `KmdReaderState` 保持可序列化、可测试：

```kotlin
data class KmdReaderState(
    val deskStack: DeskStackState = DeskStackState(),
    val works: List<Work> = emptyList(),
    val selectedWorkId: String? = null,
    val searchQuery: String = "",
    val selectedMode: PresentationMode? = null,
    val issuesByWorkId: Map<String, List<ScriptIssue>> = emptyMap(),
    val readerSession: ReaderSessionState = ReaderSessionState.Idle,
    val isLoadingWorks: Boolean = false,
    val errorMessage: String? = null
)
```

`DeskStackState.currentWorkId` 后续可以迁移到 `KmdReaderState.selectedWorkId`。桌面条带只负责“打开了哪些页面”，当前作品属于业务状态，不应被导航状态完全占有。

### 3.4 Action 分层

继续保留当前 action，但把异步动作交给 ViewModel：

```kotlin
sealed interface KmdReaderAction {
    data object RefreshWorks : KmdReaderAction
    data class OpenWork(val workId: String) : KmdReaderAction
    data object OpenReader : KmdReaderAction
    data object OpenReview : KmdReaderAction
    data class RuntimeEventReceived(val event: ReaderRuntimeEvent) : KmdReaderAction
}
```

规则：

- 纯状态变化仍走 `KmdReaderReducer`。
- 需要 Repository 或 runtime 的 action 由 ViewModel 先处理，再调用 reducer。
- reducer 保持纯函数，便于单元测试。

### 3.5 一次性 Effect

不要把“弹 Toast、请求 WebView load、打开文件选择器”塞进普通 state。使用一次性 effect：

```kotlin
sealed interface KmdReaderEffect {
    data class ShowMessage(val message: String) : KmdReaderEffect
    data class LoadRuntime(val workId: String) : KmdReaderEffect
    data object OpenImportPicker : KmdReaderEffect
}
```

UI 通过 `LaunchedEffect` 收集 effect。

## 4. Runtime Bridge 规划

### 4.1 边界判断

KMD 的 parser、layout、effect、stage、player 当前在 Web/Editor runtime 中演进。Android 端不做“纯安卓核心移植”，而是宿主化 Web runtime。

```text
Android
  -> WebView
      -> packaged reader runtime web asset
          -> KMD parser/layout/player/stage
```

这避免两个核心同时维护，也避免解析器多源问题。

### 4.2 Runtime 形态

短期：

```text
WebView 加载本地 assets/kmd-runtime/index.html（D0 fallback shell）
```

调试期可选：

```text
WebView 加载开发服务器 URL
```

长期：

```text
packages/reader-runtime-web
  -> 构建静态资源
  -> Android 同步到 generated assets/reader-runtime/
  -> WebView 通过 https://kmd-reader-runtime.local/reader-runtime/index.html 加载
```

不要直接把 `apps/editor/src/core` 散拷进 Android。应先在 KMD 主仓库中形成稳定的 reader runtime web 构建产物。

### 4.3 Bridge 接口

推荐接口：

```kotlin
interface ReaderRuntimeBridge {
    val events: Flow<ReaderRuntimeEvent>

    suspend fun attach()
    suspend fun load(request: ReaderLoadRequest)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(progress: Float)
    suspend fun setInspectionEnabled(enabled: Boolean)
    suspend fun updateSettings(settings: ReaderSettings)
    suspend fun dispose()
}
```

`ReaderRuntimeBridge` 是抽象，具体实现可以是：

- `FakeReaderRuntimeBridge`：单元测试和 UI 原型。
- `WebViewReaderRuntimeBridge`：真实 WebView 通信。

### 4.4 消息协议

Android 到 runtime：

```text
loadScript
play
pause
seek
setInspectionEnabled
updateViewport
updateSettings
```

Runtime 到 Android：

```text
ready
progressChanged
inspectionReported
playbackStateChanged
error
assetRequested
```

统一消息信封：

```json
{
  "id": "msg-001",
  "type": "progressChanged",
  "payload": {
    "progress": 0.42,
    "positionPayload": "segment:3:line:12"
  }
}
```

协议设计要求：

- 所有消息有 `type`。
- 需要回执的消息有 `id`。
- `payload` 使用 JSON，不传 Kotlin/JS 私有对象。
- runtime 错误必须回传可展示 message 和可记录 code。

## 5. ReaderSessionState

阅读状态不要散落在 `ReaderDesk` 或 WebView 中：

```kotlin
sealed interface ReaderSessionState {
    data object Idle : ReaderSessionState
    data class Loading(val workId: String) : ReaderSessionState
    data class Ready(
        val workId: String,
        val progress: Float,
        val isPlaying: Boolean
    ) : ReaderSessionState
    data class Failed(val workId: String, val message: String) : ReaderSessionState
}
```

ViewModel 根据 runtime event 更新它：

```text
OpenReader
  -> readerSession = Loading(workId)
  -> effect LoadRuntime(workId)
Runtime Ready
  -> readerSession = Ready(...)
Runtime Failed
  -> readerSession = Failed(...)
```

## 6. WebView Host 规划

`ReaderRuntimeHost` 是 Compose 与 Android View 的边界：

```text
ReaderDesk
  -> ReaderRuntimeHost
      -> AndroidView(WebView)
      -> WebViewReaderRuntimeBridge
```

职责：

- 创建和销毁 WebView。
- 配置 JavaScript、asset loading、调试开关。
- 将 WebView 生命周期转交给 bridge。
- 把 bridge events 交给 ViewModel。

不职责：

- 直接读取作品列表。
- 直接打开详情页。
- 直接提交审核。
- 保存业务状态。

## 7. 审阅与 Runtime 的关系

审阅不是独立底部 Tab，也不是独立 runtime。

推荐路径：

```text
作品详情
  -> OpenReview
      -> Repository 拉取已有 ScriptIssue
      -> 审阅浮层展示

阅读页
  -> OpenReview
      -> Runtime setInspectionEnabled(true)
      -> Runtime inspectionReported
      -> ViewModel 合并为 issuesByWorkId
```

`ScriptIssue` 来源可能有三类：

- 后端 API 的脚本检查结果。
- 本地导入时的轻量检查结果。
- Runtime 在 WebView 中运行后报告的问题。

这些来源最终都进入同一个 domain model，UI 不区分数据源，只展示问题、建议和位置。

## 8. 实现顺序

### 阶段 A：ViewModel 化当前 App

目标：不改变视觉效果，只移动状态边界。

实现状态：已完成。

当前已落地：

- `presentation/KmdReaderViewModel.kt`
- `presentation/KmdReaderEffect.kt`
- `presentation/ReaderSessionState.kt`
- `KmdReaderApp` 通过 `collectAsStateWithLifecycle()` 绑定 `StateFlow<KmdReaderState>`。
- `ReviewOverlay` 从 `state.issuesByWorkId` 读取问题列表，不再直接访问 repository。
- `ReaderDesk` 读取 `ReaderSessionState`，当前使用 Fake Runtime 状态占位。
- `WorkRepository` 已统一为 suspend 接口，mock 与 offline-first repository 共用同一边界。

1. 新增 `KmdReaderViewModel`。
2. 引入 lifecycle ViewModel Compose 依赖。
3. 将 `KmdReaderApp` 的 `remember + mutableStateOf` 替换为 `viewModel.state.collectAsStateWithLifecycle()`。
4. ViewModel 先使用 `MockWorkRepository` 或当前 fake provider。
5. `ReviewOverlay` 不再直接调用 repository，改读 `state.issuesByWorkId`。

验收：

- `./gradlew :app:testDebugUnitTest` 通过。
- `./gradlew :app:assembleDebug` 通过。
- UI 行为保持原有页面条带模型。
- reducer 仍是纯函数，ViewModel 测试覆盖初始化、打开作品、进入阅读状态。

### 阶段 B：ViewModel 接入 OfflineFirst Repository

目标：浏览页显示 API/Room 数据。

实现状态：已完成基础接入。

当前已落地：

- `data/KmdReaderAppContainer.kt` 负责创建 Room、Retrofit 和 Repository。
- `data/repository/FallbackWorkRepository.kt` 负责 primary 数据源为空或失败时回退到 mock。
- `MainActivity` 通过 `KmdReaderViewModel.Factory` 注入真实 `WorkRepository`。
- `NetworkModule` 使用 Android 模拟器地址 `http://10.0.2.2:3000/`，并缩短本地开发超时，避免后端未启动时 App 长时间等待。
- `AndroidManifest.xml` 已允许本地 HTTP 明文流量，用于课程阶段联调。

1. 创建简单的 dependency provider。
2. ViewModel 初始化时调用 `repository.listWorks(refresh = true)`。
3. 网络失败时显示缓存和轻量错误提示。
4. 打开作品时加载详情和 issues。
5. 保留 mock fallback，方便没有后端时演示。

验收：

- `./gradlew :app:testDebugUnitTest` 通过。
- `./gradlew :app:assembleDebug` 通过。
- 开启 `kmd-community-api` 后，Repository 可刷新远端 seed 数据并写入 Room。
- 断开 API 或 Room 为空时，UI 仍可显示 mock fallback 数据，保证课堂演示不崩。

### 阶段 C：Runtime Bridge 假实现

目标：先让 ViewModel 与 runtime event 模型跑通。

实现状态：已完成 fake runtime bridge。

当前已落地：

- `runtime/ReaderRuntimeBridge.kt`
- `runtime/ReaderRuntimeEvent.kt`
- `runtime/ReaderRuntimeModels.kt`
- `runtime/FakeReaderRuntimeBridge.kt`
- `KmdReaderViewModel` 监听 `ReaderRuntimeEvent`，并由事件更新 `ReaderSessionState`。
- `OpenReader` 后进入 `Loading`，随后由 fake bridge 发出 `Ready` 与 `ProgressChanged`。
- `OpenReview` 时，如果当前阅读会话已就绪，会调用 `setInspectionEnabled(true)` 并合并 `InspectionReported` 问题。
- `KmdReaderAppContainer` 注入 `ReaderRuntimeBridge`，当前实现为 `FakeReaderRuntimeBridge`。

1. 新增 `runtime/` 包和 bridge 接口。
2. 新增 `FakeReaderRuntimeBridge`。
3. `OpenReader` 后进入 `Loading -> Ready`。
4. fake bridge 定时发 `ProgressChanged`。
5. fake bridge 可发 `InspectionReported`。

验收：

- `./gradlew :app:testDebugUnitTest` 通过。
- `FakeReaderRuntimeBridgeTest` 覆盖 load 与 inspection 事件。
- `KmdReaderViewModelTest` 覆盖进入阅读和合并 runtime inspection issue。
- 不接 WebView 也能测试 reader session。
- 审阅浮层可显示 runtime 报告的问题。

### 阶段 D：WebView Runtime Host

目标：接真实 Web runtime，但保持可替换。

实现状态：D0 已完成基础接入；Phase R smoke 已接入 `dist/reader-runtime` 产物消费。

当前已落地：

- `runtime/webview/WebViewReaderRuntimeBridge.kt`
- `runtime/webview/RuntimeMessageCodec.kt`
- `runtime/webview/RuntimeJavascriptBridge.kt`
- `ui/screen/reader/ReaderRuntimeHost.kt`
- 本地 `assets/kmd-runtime/index.html` D0 fallback shell
- 阅读页已能加载 WebView D0 shell，并通过 `ReaderRuntimeBridge` 接收 `ready`、`progressChanged`、`inspectionReported`、`error`。
- 若主仓库存在 `dist/reader-runtime/`，Android Gradle 会在 `preBuild` 阶段同步到 APK `assets/reader-runtime/`，并优先加载真实 `reader-runtime-web` bundle。
- 真实 bundle 通过本地 HTTPS 虚拟域名 `https://kmd-reader-runtime.local/reader-runtime/index.html` 加载，WebView request 拦截器映射到 APK assets；D0 shell 仅作为 fallback。

前置审计：

- 先阅读并落实 `docs/knowledge/integration/core-portability-webview-feasibility.md`。
- 阶段 D 的第一步不是抽 `apps/editor/src/core`，而是验证 WebView 最小宿主、消息协议和生命周期。
- 接真实 KMD runtime 前，需要先处理 Vue/Pinia store、静态资源路径、runtime singleton 和 command registry 的边界。

1. 创建 `ReaderRuntimeHost`。
2. 创建 `WebViewReaderRuntimeBridge`。
3. 先加载一个极小 HTML runtime shell。
4. Android 与 JS 互发 `ready`、`progressChanged`。
5. 再接入 KMD reader runtime web 构建产物。

验收：

- WebView 生命周期稳定。
- 横屏/竖屏作品能按 `WorkPresentation` 配置容器。
- Runtime 错误不会导致 App 崩溃。

### 阶段 E：Reader Runtime Web 产物化

实现状态：已完成。主仓库 `@kmd/reader-runtime-web` 已建立为 workspace package，Android 通过 Gradle 同步消费 `dist/reader-runtime/` 产物（产物路径与拦截关系见主仓库 `docs/knowledge/integration/reader-runtime-web-bundle.md`）。

目标：从 Editor runtime 提取可打包 reader runtime，而不是复制核心。

1. 在 KMD 主仓库规划 `packages/reader-runtime-web`（已完成）。
2. 明确它消费 `apps/editor/src/core` 或未来 `packages/core` 的方式。
3. 输出静态 `index.html + assets`。
4. Android 构建时同步到 generated assets 的 `reader-runtime/` 目录。
5. 建立 runtime message protocol 的版本号。

验收：

- Android repo 不保存 parser/layout/renderer 源码副本。
- Runtime 升级只替换 web 产物或 package 版本。
- 消息协议破坏性变更有版本标记。

## 9. 近期不做

- 不把 `apps/editor/src/core` 直接复制到 Android。
- 不在 Kotlin 中重写 KMD parser。
- 不让 ViewModel 持有 WebView 实例。
- 不把 WebView JavaScript bridge 暴露成任意执行接口。
- 不把审阅做成独立一级导航。
- 不在 UI Composable 中直接调用 Retrofit 或 DAO。

## 10. 路线指针

阶段 D0（WebView 宿主 + `dist/reader-runtime` 产物消费）已完成，见 §阶段 D 实现状态。后续优先级以 [`roadmap.md`](../roadmap.md) 为准，本计划不复写当前任务清单。

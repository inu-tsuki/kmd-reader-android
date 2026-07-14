# Android Reader Runtime 实现方案

> 文档状态：当前实施方案  
> 最近更新：2026-05-20

## 1. 目标

Android Reader 不实现第二套 KMD 核心。真实阅读能力由 WebView 中的 `reader-runtime-web` 承担：

```text
Compose UI
  -> KmdReaderViewModel
      -> ReaderRuntimeBridge
          -> WebViewReaderRuntimeBridge
              -> WebView
                  -> @kmd/reader-runtime-web
                      -> KMD parser/layout/effect/stage/player
```

Android 负责：

- 作品列表、详情、审阅和本地状态。
- 创建 WebView 宿主。
- 向 runtime 发送命令。
- 接收 runtime 事件并更新 UI。
- 保存阅读进度和错误状态。

Web runtime 负责：

- 解析 KMD。
- 构建 layout/stage/player。
- 渲染和播放。
- 回传 ready/progress/playback/error/inspection。

## 2. 当前状态

已完成：

- `ReaderRuntimeBridge` 抽象。
- `FakeReaderRuntimeBridge` 测试实现。
- `WebViewReaderRuntimeBridge`。
- `RuntimeMessageCodec`。
- `ReaderRuntimeHost`。
- D0 shell fallback。
- Gradle 同步 `dist/reader-runtime/` 到 APK `assets/reader-runtime/`。
- WebView 使用本地 HTTPS 虚拟域名加载真实 runtime artifact。
- Android UI 已区分 `runtimeReady` transport 状态、作品 loading、ready、playing 和 failed。
- `RuntimeMessageCodec` 不再伪造 preview source；`loadScript` 的真实 source 由 Repository 提供。
- Mock works 已提供最小真实 KMD source，用于 Android smoke。
- 阅读页已提供播放、暂停和 progress seek 控制。
- Android 已解析 `runtimeReady.capabilities`、`error.code`、`error.commandId`、`timeMs/durationMs` 等 v1 可选字段。
- Web runtime session 已将事件 workId 规范为 Android 传入的 `work.id`。

未完成：

- Android 没有持久化真实播放进度。
- 未完成真机/模拟器 WebView smoke 录屏。
- 横屏/竖屏沉浸式阅读容器还未定型。
- 本地 `.kmd` 文件导入尚未接入真实 source resolver。

因此当前状态应描述为：

> Android 已具备真实 reader-runtime artifact 消费链路，并闭合了 source -> ready -> play/pause/seek -> progress 的最小协议路径；真机/模拟器可见渲染 smoke 与进度持久化仍待完成。

## 3. 协议复审结论

本方案以主仓库协议文档作为契约源：

```text
docs/knowledge/integration/android-webview-runtime-protocol.md
```

复审后需要固定以下约束：

- 所有消息使用 `{ version, id, type, payload }` envelope。Android -> JS 命令必须带 `id`；JS -> Android 事件可以不带 `id`，但可以带 `sessionId`。
- v1 不引入 ack。成功路径由领域事件表达：`runtimeReady` 表示 transport 就绪，`ready` 表示作品加载完成，`progressChanged` 表示播放位置变化。失败统一走 `error`，并尽量带 `commandId`。
- `runtimeReady` 只表示 `window.KmdRuntime.receive()` 已安装，不能表示作品已加载、可播放或已渲染。Android UI 必须把 `runtimeReady` 和 `ready` 分成两个状态。
- `loadScript` 必须包含 `work`，并且至少包含 `source`、`sourceUrl`、`assetManifest` 中的一种。若三者都没有，真实 runtime 应返回结构化 `error`，不能假装加载成功。
- `source` 是 Android 已持有脚本文本时的首选方式。`sourceUrl` 必须是受控 asset URL 或 HTTPS URL，不能把任意 Android 本地路径交给 Web runtime 自由 `fetch()`。
- `assetBaseUrl` / `assetManifest` 是字体、图片、音频等资源的边界。Android 当前真实 bundle 通过 `https://kmd-reader-runtime.local/reader-runtime/index.html` 加载，本地 request 拦截器负责映射到 APK assets。
- `sessionId` 当前可选，但计划应为它预留位置。未来要用它过滤 WebView reload、作品切换或 dispose 后冒出来的旧事件。
- `runtimeReady.capabilities` 应进入 Android 状态层，用于决定检查、精确 seek、timeline markers、互动段落等 UI 是否启用。
- WebView `unbind/rebind` 不等于 `dispose`。重新绑定后可以重放 `lastLoadRequest`；只有显式 `dispose` 才表示销毁 runtime session。

因此最小真实阅读闭环不只是“WebView 能加载 bundle”，而是：

```text
runtimeReady
  -> loadScript(work + source/sourceUrl/assetManifest)
  -> ready / error
  -> play/pause/seek
  -> progressChanged / playbackStateChanged
```

## 4. 黑屏问题的含义

小窗口黑屏不一定意味着 runtime 没加载。它可能表示：

- WebView 加载了页面，但 JS module 初始化失败。
- JS 初始化成功，但 `loadScript` 没有被 flush 到 runtime。
- `runtimeReady` 已回传，但作品还没有进入 `ready`。
- `loadScript` 缺少真实 `source/sourceUrl/assetManifest`，只跑了 preview fallback。
- `loadScript` 成功，但 KMD timeline 停在不可见初始帧。
- runtime 已 ready，但 Android 没有 play/pause/seek 控制。
- Pixi canvas 尺寸为 0 或被容器裁剪。
- 字体或资源加载失败，渲染延迟或报错未显示。
- WebView console 错误没有被转为 Android UI 状态。

所以不能用“APK 中存在 `assets/reader-runtime/index.html`”来证明真实阅读已经完成。必须验证消息与可见渲染。

## 5. 目标阅读链路

期望流程：

```text
用户点击开始阅读
  -> ViewModel: readerSession = Loading(workId)
  -> ReaderRuntimeHost 创建 WebView
  -> Web runtime post runtimeReady(capabilities)
  -> Android 记录 transport ready 和 runtime capabilities
  -> WebViewReaderRuntimeBridge flush loadScript(work + source/sourceUrl/assetManifest)
  -> reader-runtime-web 解析并构建 segment
  -> post ready(workId, durationMs)
  -> Android 显示 Ready
  -> 用户点击播放
  -> Android 发送 play
  -> runtime 推动 timeline
  -> post progressChanged
  -> Android 更新进度条
```

失败流程：

```text
JS 初始化失败 / 脚本解析失败 / 资源加载失败
  -> post error(code, message, commandId, recoverable)
  -> Android readerSession = Failed
  -> UI 展示错误和回退说明
```

## 6. 实施切片

### R-A. 可见状态与错误上报

目标：不再出现无解释黑屏。

任务：

- Android UI 区分 `RuntimeLoading`、`TransportReady`、`WorkLoading`、`Ready`、`Playing`、`Failed`。
- WebView console/error 转为 `ReaderRuntimeEvent.Failed` 或调试日志。
- 阅读页显示 runtime 状态：Loading、Ready、Playing、Failed。
- runtime status overlay 保持可见，至少在 debug build 中显示。
- `RuntimeMessageCodec` 保留 `code/commandId/recoverable` 字段。
- debug 状态中显示最近一次 command id、error code、可选 `sessionId` 和 runtime capability 摘要。

验收：

- JS 初始化失败时 Android 能看到错误文本。
- `runtimeReady` 后但 `ready` 前，UI 显示“runtime 已连接 / 作品加载中”，而不是误判为可播放。
- runtime 已加载但未播放时，页面也有明确状态。

### R-B. WorkId、Source 与 Asset 合约对齐

目标：Android UI 能稳定识别当前作品。

当前风险：

- Web runtime 内部可能使用 KMD metadata title 作为 workId。
- Android 使用 `Work.id` 作为业务主键。
- Android 当前 codec 会为缺少 source 的 work 注入 preview fallback，这只能用于 smoke，不能证明真实 KMD 作品已播放。

任务：

- `loadScript` 传入的 `work.id` 必须贯穿 ready/progress/playback/inspection 事件。
- `loadScript` 必须显式传入 `source`、受控 `sourceUrl` 或 `assetManifest`。
- `sourceUrl` 不允许是任意 Android 本地路径；本地资源必须通过受控 asset host、content resolver 读取后转成 `source`，或映射为受控 URL。
- `settings.assetBaseUrl` 和 `assetManifest.baseUrl` 要与 Android 当前虚拟 HTTPS assets host 对齐。
- mock works 至少提供一个可被当前 parser 播放的真实 KMD source。
- fallback source 只能用于 smoke，不应伪装成真实社区脚本。

验收：

- 打开 `glass-rail` 后，所有 runtime event 的 workId 都是 `glass-rail`。
- 将 `source/sourceUrl/assetManifest` 全部移除时，真实 runtime 返回可见 `error`。
- 至少一个 mock work 使用真实 KMD source，而不是标题/简介生成的 preview source。

### R-C. 播放控制闭环

目标：用户能推动真实 runtime。

任务：

- 新增 `KmdReaderAction.PlayReader`。
- 新增 `KmdReaderAction.PauseReader`。
- 新增 `KmdReaderAction.SeekReader(progress)`。
- ViewModel 调用 `runtimeBridge.play/pause/seek`。
- ReaderDesk 显示播放/暂停按钮和进度控制。
- 根据 `runtimeReady.capabilities` 决定是否启用精确时间 seek、timeline markers 或检查入口；v1 至少保留 `progress: 0..1` seek。

验收：

- 点击播放后 runtime 回传 `playbackStateChanged(playing)`。
- 进度条随 `progressChanged` 更新。
- 点击暂停后 runtime 回传 paused。

### R-D. 阅读容器适配

目标：阅读不再只是小窗口黑盒。

任务：

- 竖屏作品使用较高容器或全屏阅读模式。
- 横屏作品使用固定 16:9 容器或横屏提示。
- WebView 尺寸变化后触发 runtime resize。
- 阅读页避免外层滚动和 WebView 触摸互相抢焦点。

验收：

- 竖屏、横屏、adaptive 三类作品都能看到明确容器。
- 旋转屏幕后不崩溃，runtime 能恢复或重新加载。

### R-E. 进度保存

目标：阅读状态进入 Android 业务层。

任务：

- 将 `ReaderSessionState.Ready.progress` 写入 Repository 或本地偏好。
- 若 runtime 回传 `timeMs/durationMs`，优先保存结构化时间；`positionPayload` 只作为兼容/debug 字段。
- 再次打开同一作品时发送 `seek(progress)`。
- 区分 runtime progress 和浏览流预览 progress。

验收：

- 退出阅读页再回来，进度不丢失。

### R-F. 测试与验收

目标：进入第 5 阶段测试报告。

测试场景：

- D0 shell fallback。
- 真实 reader-runtime-web artifact 加载。
- runtimeReady capability 解析。
- loadScript 成功。
- 缺少 source/sourceUrl/assetManifest 时 error 可见。
- play/pause/seek 成功。
- runtime error 可见。
- WebView rebind 后重放 lastLoadRequest，不误发 dispose。
- 旋转屏幕后不崩。

## 7. 当前验收切线

如果时间只够做最小收尾，优先顺序是：

1. 可见错误和 runtime 状态。
2. `loadScript` 明确携带真实 KMD `source`，并完成 workId 对齐。
3. 播放/暂停按钮。
4. 进度事件显示和最小 seek。
5. 模拟器录屏。

暂缓：

- 完整导入 `.kmd`。
- 全量作品播放兼容。
- 阅读进度持久化。
- 横屏沉浸式 UI。
- 字体裁剪和资源缓存优化。

## 8. 与 Phase R 的关系

主仓库 Phase R 解决的是：

```text
reader-runtime-web artifact 能被构建、打包、宿主加载
```

Android Runtime 实现方案解决的是：

```text
用户能在 Android 应用中完成真实阅读动作
```

前者是基础，后者是产品流程。当前我们已经接近前者收尾，但后者还需要 R-A 到 R-C 的最小闭环。

# KMD Core 可移植性与 WebView 宿主可行性审计

> 项目阶段：阶段 D 前置审计  
> 文档状态：草案  
> 最近更新：2026-05-14

## 1. 结论

阶段 D 不建议直接抽 `apps/editor/src/core/` 为独立 `core` 包，也不建议在 Android/Kotlin 侧重写 KMD 核心。

更稳妥的路线是：

```text
Android Compose
  -> ReaderRuntimeBridge
      -> WebView
          -> reader-runtime-web bundle
              -> 当前 Web runtime 中经过解耦的 parser/layout/player/stage
```

原因：

- 当前 `core` 不是纯语言核心，而是混合了 parser、layout、Pixi 渲染、GSAP 播放、stage 管理、Monaco 编辑器支持和 Web app 宿主。
- 渲染与播放栈本质上已经是浏览器技术栈，放进 Android WebView 比移植到 Kotlin/Canvas 更现实。
- 代码中已经存在一个有价值的宿主边界：`ReaderHost`。但它外层仍被 `readerApp`、`stageManager`、`scriptPlayer` 等单例和 Vue/Pinia store 耦合包住。
- 在边界澄清前抽包，会把不稳定的依赖关系固化成包 API，长期维护痛苦会变大。

因此阶段 D 的目标应从“抽 core”改为：

> 验证 WebView 能稳定宿主一个最小 reader runtime，并为未来抽离 reader-runtime-web 准备前提条件。

## 2. 审计范围

本次检查覆盖：

- `apps/editor/src/core/`
- `apps/editor/src/store/editorStore.ts`
- `docs/planning/ecosystem/repository-strategy.md`
- `apps/android-reader/docs/planning/runtime/viewmodel-runtime-plan.md`

重点检查：

- 是否依赖 Vue、Pinia、Monaco 或 editor 页面状态。
- 是否依赖 DOM、window、fetch、字体和静态资源路径。
- parser 是否可以作为纯模块抽离。
- Pixi/GSAP runtime 是否适合放入 WebView。
- Android 与 Web runtime 的消息边界应如何设计。

## 3. 当前 core 分层判断

| 层级 | 代表文件 | 当前可移植性 | 判断 |
|---|---|---:|---|
| parser / IR | `parser/Parser.ts`, `parser/AstParser.ts`, `parser/lowering.ts` | 中 | 语义上适合抽离，但 `commandCatalog.ts` 依赖 effect/layout/stage manager，需要先解耦命令注册表 |
| command registry | `parser/commandCatalog.ts` | 低 | 反向导入 `effectManager`、`layoutManager`、`stageManager`，使 parser 不是纯模块 |
| layout | `layout/*` | 中 | 可在 Web runtime 中复用，但依赖 `stageManager`、host view、字体测量与浏览器环境 |
| rendering | `KineticText.ts`, `KineticChar.ts`, `render/text/*` | 中 | Pixi/WebView 可承载；不适合直接移植为 Kotlin 核心 |
| effects/player | `effects/*`, `player/*` | 中 | GSAP/Pixi 方向适合 WebView；当前 `ScriptPlayer` 写入 Pinia store |
| stage/host | `stage/ReaderHost.ts`, `stage/StageManager.ts` | 中 | `ReaderHost` 是好边界；`StageManager` 单例和全局注册需要 session 化 |
| editor support | `editor/kmd-lang.ts`, `editor/tmGrammarLoader.ts` | 不应进入 reader runtime | Monaco、TextMate、Oniguruma 属于编辑器能力，应排除 |
| app shell | `App.ts`, `ReaderLayoutHostView.ts` | 低 | 直接依赖 DOM、字体路径、`readerApp` 单例，不应作为长期 runtime 入口 |

## 4. 关键耦合点

### 4.1 parser 还不是纯 core

`parser/commandCatalog.ts` 当前直接导入：

```text
effectManager
styleManager
layoutManager
stageManager
```

这意味着只要 import parser，就可能牵出 Pixi、stage、effect preset 与运行时注册逻辑。未来要抽 `@kmd/core`，需要把命令注册表改成可注入：

```text
parse(source, { commandRegistry })
```

而不是 parser 自己去拿 runtime singleton。

### 4.2 runtime 写入 editor store

以下文件仍直接依赖 `useEditorStore`：

- `player/ScriptPlayer.ts`
- `render/text/TextPlayer.ts`
- `render/text/TextBuildContextResolver.ts`

这使 reader runtime 无法脱离 Vue/Pinia。应改为宿主配置和事件回调：

```text
ReaderRuntimeOptions
  typography
  viewport
  callbacks.onProgress
  callbacks.onTimelineChanged
  callbacks.onError
```

Android WebView runtime 只通过消息协议向 Android 上报事件，不直接知道 Pinia 或 Compose。

### 4.3 宿主边界已有雏形，但入口仍是单例

`stage/ReaderHost.ts` 已经定义了 `ReaderHost`：

```text
mountStage
onResize
addTicker
getScreenSize
setBackgroundColor
```

这是未来 reader runtime 的关键边界。问题在于当前 `App.ts` 和 `ReaderLayoutHostView.ts` 仍使用：

```text
readerApp
stageManager
scriptPlayer
```

这些全局单例会让多个阅读会话、热重载、WebView 销毁重建和测试变复杂。未来应提供 factory：

```text
createReaderRuntime(container, options)
  -> loadSource(source)
  -> play()
  -> pause()
  -> seek(position)
  -> inspect()
  -> dispose()
```

### 4.4 静态资源路径需要宿主化

`App.ts` 中字体路径是 Web 根路径：

```text
/fonts/LXGWWenKai-Regular.ttf
/fonts/SarasaGothicSC-Regular.ttf
/fonts/SmileySans-Oblique.ttf
/fonts/FiraCode-Regular.ttf
```

Android WebView 中不应依赖站点根路径。需要改成：

- runtime bundle 内的相对路径，例如 `./fonts/...`
- 或 Android `WebViewAssetLoader` 暴露的本地资源域名
- 或由 host config 注入 `assetBaseUrl`

`ScriptSourceLoader` 支持 `fetch(input)`，这在 WebView 中也要收紧。Android 应传入脚本文本、受控 URL 或 asset URL，不能让 runtime 任意读取本地路径。

### 4.5 DOM 与浏览器 API 是 WebView 可行性的基础，也是抽纯 core 的边界

当前 runtime 使用：

- `window.devicePixelRatio`
- `document.fonts`
- `FontFace`
- `fetch`
- Pixi canvas/WebGL

这些在 Android WebView 方向是可接受的，但它们说明当前核心不是“纯算法 core”。如果未来要抽更底层的 `@kmd/core`，应把 browser API 放在 `@kmd/reader-runtime-web`，而不是放进纯 core。

## 5. WebView 宿主可行性

### 5.1 可行

WebView 宿主方案是可行的，因为：

- KMD 当前核心渲染栈已经依赖 Pixi、Canvas/WebGL、DOM 字体加载和 GSAP。
- Android WebView 可以承载本地 HTML/JS/CSS/assets。
- Android 侧已经规划了 `ReaderRuntimeBridge`，业务状态不会被塞进 WebView。
- 阶段 C 已经通过 fake bridge 验证了 ViewModel 与 runtime event 的关系。

### 5.2 主要风险

| 风险 | 影响 | 应对 |
|---|---|---|
| WebView WebGL/Canvas 性能差异 | 低端设备可能卡顿 | 阶段 D 先做最小 runtime shell 和性能 smoke test |
| 字体加载路径不一致 | 中文排版、测量、渲染不稳定 | 改成 asset manifest / `assetBaseUrl` |
| JS bridge 暴露过宽 | 安全风险 | 只开放固定消息入口，不提供任意执行能力 |
| 生命周期不清晰 | 页面返回、旋转、销毁后仍播放 | bridge 必须有 `attach/dispose`，runtime 必须有 `dispose` |
| runtime 错误冒泡到 App | 崩溃或白屏 | JS 错误统一转为 `ReaderRuntimeEvent.Error` |
| editor-only 依赖混入 | bundle 过大、加载失败 | reader runtime build 明确排除 Monaco/TextMate/editor store |

## 6. 阶段 D 前提条件

阶段 D 可以开始 WebView 宿主，但接真实 KMD runtime 前，应满足以下条件。

### 6.1 最小 runtime shell

先创建一个不依赖 KMD core 的 HTML shell，验证：

- WebView 能加载本地 assets。
- Android -> JS 的 `load/play/pause/seek` 命令可达。
- JS -> Android 的 `ready/progressChanged/error` 事件可达。
- 横竖屏和返回销毁不会崩溃。

### 6.2 reader runtime 入口

在 Web 侧形成一个 reader 专用入口，不复用 editor app shell：

```text
createReaderRuntime(container, options)
```

入口不得 import：

- Vue component
- Pinia store
- Monaco
- editor panel

### 6.3 Runtime 配置注入

把 `useEditorStore` 依赖替换为 host-provided config：

```text
fontFamily
fontColor
viewport
presentationMode
assetBaseUrl
callbacks
```

### 6.4 命令注册表解耦

parser 不应默认导入 runtime manager。可先保留默认 runtime registry，但要允许显式注入：

```text
runtimeRegistry = createRuntimeCommandRegistryView(runtimeManagers)
parse(source, { commandRegistry: runtimeRegistry })
```

这样未来 `@kmd/core` 才能只包含语言与 IR。

### 6.5 资源策略

统一 runtime assets：

```text
reader-runtime/
  index.html
  assets/
  fonts/
```

Android 侧只加载这份构建产物，不保存 parser/layout/renderer 源码副本。

### 6.6 消息协议版本

消息信封需要带版本：

```json
{
  "version": 1,
  "id": "msg-001",
  "type": "progressChanged",
  "payload": {}
}
```

破坏性变更时升级版本，而不是让 Android 和 runtime 靠隐式字段猜测。

## 7. 建议实施顺序

### D0：可行性 shell

在 Android 仓库实现：

- `ReaderRuntimeHost`
- `WebViewReaderRuntimeBridge`
- 本地 `assets/kmd-runtime/index.html`
- 最小 JS message loop

这一阶段不接真实 KMD core，只验证宿主、桥、生命周期。

### D1：协议固化

把已有 fake bridge 的事件模型与 WebView bridge 对齐：

- `ready`
- `progressChanged`
- `playbackStateChanged`
- `inspectionReported`
- `error`

同时保留 `FakeReaderRuntimeBridge` 做单元测试和无 WebView 演示。

### D2：Web 侧 reader runtime 入口实验

在 KMD 主仓库中创建 reader 专用入口，先消费当前 `apps/editor/src/core`，但只导入 reader 必需模块。

这一步可以仍在主仓库内实验，不急着抽成 `packages/core`。

### D3：真实 runtime 打包进 Android

当 D2 的 bundle 能独立运行后，再复制构建产物到 Android：

```text
app/src/main/assets/kmd-runtime/
```

Android 只与静态产物和消息协议耦合。

## 8. 不在阶段 D 做的事

- 不把 `apps/editor/src/core` 直接复制到 Android。
- 不在 Kotlin 中实现第二套 parser/layout/effect。
- 不把 Monaco/TextMate/编辑器语法高亮打入 reader runtime。
- 不把 WebView 变成业务数据中心。
- 不让 JS bridge 暴露任意代码执行能力。
- 不在 core 边界不清楚时发布 `@kmd/core`。

## 9. 决策

阶段 D 前的技术决策为：

1. Android 继续坚持 `ViewModel -> ReaderRuntimeBridge -> WebView`。
2. 阶段 D 先实现 WebView 最小宿主，不直接接完整 KMD core。
3. 真实 KMD runtime 接入前，先在 Web 侧形成 reader-only runtime entry。
4. `apps/editor/src/core` 暂不抽包；先解耦 store、asset、singleton 和 command registry。
5. 未来抽包顺序应是：

```text
packages/language
  -> packages/reader-runtime-web
      -> packages/core
```

也就是说，先把“可被 Android WebView 宿主的 reader runtime”做出来，再从中沉淀真正纯净的 core。

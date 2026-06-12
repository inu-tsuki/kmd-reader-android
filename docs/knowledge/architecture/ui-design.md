# KMD Reader Android UI Design

> 文档状态：草案
> 最近更新：2026-05-27

## 1. 目标

本文记录 Android Reader 的长期 UI 设计原则。它不是课程截图清单，而是后续 Compose、Figma 和 runtime 宿主实现共同遵守的界面契约。

当前最重要的结论：

> 阅读态中，KMD Reader Runtime 是全屏底板。播放控件、阅读状态、审阅入口和脚本检查工具是 Android 浮层或边栏。

这意味着阅读页不再是“信息页里嵌一个 WebView 小窗口”。阅读页本身就是 runtime 宿主，Android UI 只负责组织控制、状态和社区工具。

## 2. 设计原则

### 2.1 Runtime First

阅读体验以 KMD 内容为中心：

- `ReaderRuntimeHost` 占满阅读桌面的可用空间。
- runtime 不放进滚动容器。
- runtime 不作为卡片、预览框或详情页的一部分。
- runtime 不应被圆角裁剪、固定小高度或信息卡片挤压。
- 作品标题、播放进度和 companion 入口可以覆盖在 runtime 上，但不改变 runtime 的宿主尺寸。

此前的小窗口方案适合 smoke test，不适合真实阅读。真实 runtime 需要稳定、完整、可预测的 viewport，否则会出现黑屏、缩放过小、WebGL renderer 压力异常或交互命中区域错乱。

### 2.2 Controls As Overlay

播放控制属于阅读场景的浮层：

- 默认收起或半透明显示，避免遮挡 KMD 内容。
- 用户点击屏幕、点击控制区或播放状态变化时显示。
- 控制条应包含播放/暂停、进度、当前时间、总时长、审阅入口和返回详情入口。
- 控件视觉上应轻，使用半透明背景和必要的模糊/遮罩，而不是大面积实体卡片。
- 控件不应触发 WebView 重建。

课程 MVP 可以常显控制条，后续再实现自动隐藏。

### 2.3 Companion Container

阅读时附加内容不是独立底部 Tab，而是可呼起的 companion tool。当前阅读现场至少拆成两个相邻心智：`Review` 负责脚本现场和播放跳转，`Issues` 负责 issue 台账和状态管理。未来评论摘要、作品信息、诊断和推荐也应进入同一容器模型：

- 在手机竖屏中，companion 使用底部抽屉或右侧窄浮层。
- 在横屏、平板或折叠屏中，companion 使用侧边栏、上/下信息带或可收起浮层。
- placement 只是响应式布局，不代表不同 companion 有不同关闭规则。
- companion 打开时，runtime 保持运行和可见。
- companion 关闭后回到阅读态，不新增永久页面。
- 所有 companion 共享关闭契约：系统返回、双指轻点，以及可选的栏外双击。
- Review 不提供独有的展开/收起按钮；内容焦点通过内部小控件切换。
- Review companion 以源码上下文为主，只承担源码现场、全局播放坐标、源码行上下文气泡和 issue 标记跳转。
- Issues companion 以当前 work 的 issue 台账为主，承担 issue 列表、详情、关闭/重开、draft 和未来 discussion 入口。
- Review 与 Issues 使用同一 placement/height 策略；二者切换不改变 runtime viewport，也不造成 WebView 重建。

审阅入口可以从作品详情和阅读态进入，但最终都依附当前作品和当前阅读会话。

### 2.4 Navigation Without Naming Desks

`桌面` 是开发者术语，不出现在用户界面中。用户只看到：

- `书架`
- `发现`
- 作品详情
- 阅读
- 导入
- 检查脚本

`书架` 位于 `发现` 左侧。它是读者的默认阅读资产页面，而不是普通个人中心；设置、关于和账号信息应作为书架里的轻量入口，不占用主导航位置。

阅读态可以弱化全局顶部导航。因为 runtime 需要完整屏幕，顶部导航不应永久占据内容空间。推荐做法：

- 普通页面显示顶部标签导航。
- 阅读态隐藏普通顶部导航，改用阅读浮层中的返回/页面标题。
- 用户仍可通过返回按钮、系统返回、边缘滑动或顶部浮层回到详情页。

如果后续保留全局横向桌面滑动，阅读态需要区分边缘手势和 runtime 内部手势，避免和交互式 KMD 冲突。

2026-05-26 实现约定：阅读态的双指轻点由 WebView 宿主侧作为低冲突手势观察，不使用全屏 Compose 透明遮罩。宿主 `OnTouchListener` 只识别手势并返回 `false`，确保单指点击、拖动和互动式 KMD 的内部手势继续传给 runtime。

### 2.5 Bookshelf As Library

书架页面承担常规阅读器的核心心智：用户已经拥有或准备继续阅读的作品都在这里。它要比发现流更安静、更可管理，不做短视频式沉浸预览。

书架页面推荐信息层级：

- 顶部：页面标题 `书架`，右侧放置导入、搜索/排序、设置或更多菜单。
- 继续阅读：最近打开的 1-3 个作品，显示进度、上次阅读时间和“继续”动作。
- 本地作品：导入的 `.kmd`、本地草稿、随包示例，强调离线可读。
- 收藏/缓存：用户从发现流留下的社区作品，显示是否已缓存 source。
- 不可离线：只有元数据但缺少本地 source 的作品，阅读动作应禁用或标注“需联网”。
- 管理状态：空书架、导入失败、缓存清理、移除作品都要有明确反馈。

视觉上，书架应接近阅读应用的图书馆：密度适中、便于扫描、重视标题和进度；不要把它做成账号页、项目介绍页或设置页。项目说明可以保留在底部或更多菜单里，不能压过作品列表。

## 3. 阅读态层级

阅读态使用单个全屏 `Box` 分层：

```text
ReaderDesk
  ├─ Layer 0: ReaderRuntimeHost
  ├─ Layer 1: Runtime status overlay
  ├─ Layer 2: Playback controls
  ├─ Layer 3: Reader companion container
  │    └─ active companion: review / community / info / diagnostics
  └─ Layer 4: transient messages
```

### 3.1 Layer 0: Runtime

职责：

- 加载 `reader-runtime-web` 或 D0 fallback。
- 接收 `loadScript/play/pause/seek/updateSettings`。
- 回传 `runtimeReady/ready/progressChanged/playbackStateChanged/error`。
- 按 Android 传入的 `presentationMode` 和 `viewport` 布局。

约束：

- `Modifier.fillMaxSize()`。
- 不依赖父级滚动。
- 不被 `InfoCard`、详情内容或调试文本挤压。
- 生命周期和当前作品绑定，而不是和控制条展开状态绑定。

### 3.2 Layer 1: Runtime Status

显示加载和错误状态：

- `RuntimeLoading`：加载 WebView 和 runtime bundle。
- `TransportReady`：桥接通道已建立。
- `WorkLoading`：解析脚本并构建 timeline。
- `Failed`：显示错误原因、错误码和可恢复建议。

状态浮层应靠近底部或中心，不用大段说明文字。详细诊断进入阅读 companion 容器中的诊断或审阅面板。

### 3.3 Layer 2: Playback Controls

手机竖屏默认底部控制条：

```text
┌──────────────────────────────┐
│ KMD runtime full screen       │
│                              │
│          content             │
│                              │
│  title / status              │
│  [play]  01:20 / 04:10  [check]
│  ━━━━━━━━━ slider ━━━━━━━━━  │
└──────────────────────────────┘
```

横屏可以使用更短的底部控制条或左下角控制簇，避免遮挡舞台字幕。

控件最小集合：

- 返回详情。
- 播放/暂停。
- 进度条。
- 当前时间/总时长。
- 检查脚本。

后续扩展：

- 倍速。
- 字号/主题。
- 自动播放设置。
- 章节/marker 跳转。

### 3.4 Layer 3: Reader Companion Container

Reader companion 是阅读时附加内容容器，不是 Web runtime 的一部分。它负责承载审阅、issue 管理、评论摘要、作品信息、诊断和未来社区互动。当前实现范围以 `Review` 和 `Issues` 为核心：一个看脚本现场，一个管问题台账。

约束：

- 不改变 runtime viewport 和 `.kmd` 设计坐标系。
- 不把 companion 内容注入 WebView DOM。
- 同一时刻只激活一个主 companion。
- 关闭后回到普通阅读 chrome，不新增永久页面。
- companion 类型不改变关闭规则；位置变化只是为了适配屏幕。
- companion 内部只能有一个主滚动区，避免 issue 列表、源码查看器和备注区互相嵌套滚动。

当前 MVP companion：

- `Review`：脚本查看器。展示源码全文、当前播放行、选中行和 issue 编号标记；底部只保留全局 command tray，局部动作由源码行上下文气泡承载。
- `Issues`：issue 管理台。列出当前 work 的所有 issue，提供筛选、详情、关闭/重开、draft、跳转到 Review 行和未来 discussion 入口。

手机竖屏：

```text
┌──────────────────────────────┐
│ runtime remains visible       │
│                              │
│                              │
│ ┌──────────────────────────┐ │
│ │ Review: source viewer    │ │
│ │ or Issues: issue ledger  │ │
│ │ shared tray / actions    │ │
│ └──────────────────────────┘ │
└──────────────────────────────┘
```

横屏或大屏：

```text
┌──────────────────────┬───────────────┐
│ runtime              │ Review source  │
│                      │ or Issues list │
│                      │ shared height  │
└──────────────────────┴───────────────┘
```

审阅 companion 打开时不暂停 runtime，除非用户显式暂停。面板可以降低 runtime 音量或显示半透明遮罩，但不应销毁阅读会话。

Review 的推荐结构是 source-only：

- 顶部不放“脚本审阅”、作品标题、源码标题或大块说明。
- `KmdSourceContextView` 填满 companion 主体，展示当前播放行、issue anchor、选中行和搜索结果。
- 底部保留一行极简 command tray，只显示当前播放行坐标和 Review/Issues 切换入口。
- 点击源码行后出现源码行上下文气泡，用于跳转到此处、提 issue、查看行上 issue、多选等局部动作。
- issue 在源码里表现为行内编号、badge 或 gutter marker；点击编号打开 `Issues` companion 并聚焦该 issue。
- Review 不再承载 issue close/reopen、issue draft 或审核结论。

Issues 的推荐结构是 ledger-first：

- 主体是一列当前 work 内所有 issue，可筛选 open/closed/severity/source。
- issue 详情、close/reopen、draft 和 discussion 入口在 Issues 内完成。
- 点击 issue 的“查看脚本”会切回 Review companion，聚焦并滚动到对应源码行。
- Issues 的统计和 Review/Issues 切换入口放在底部 command tray，和 Review 的极简 tray 对齐。
- Issues 与 Review 高度、placement 和关闭方式一致，切换时像同一个 companion shell 中换页。

## 4. 主要状态

| 状态 | Runtime | 控制浮层 | Companion |
|------|---------|----------|-----------|
| 未进入阅读 | 不创建 | 不显示 | 不显示 |
| 加载 runtime | 全屏创建 | 显示加载状态 | 可隐藏 |
| 加载作品 | 全屏保留 | 显示构建状态 | 可隐藏 |
| 已就绪 | 全屏保留 | 显示播放控制 | 可呼起 |
| 播放中 | 全屏保留 | 自动收起或半透明 | 可呼起 |
| 暂停 | 全屏保留 | 常显控制 | 可呼起 |
| 错误 | 全屏保留或 fallback | 显示错误操作 | 可打开诊断 companion |

错误状态不应表现为无解释黑屏。至少显示：

- runtime 是否已加载。
- 当前作品 ID。
- 错误消息。
- 是否可重试。
- 是否建议切换模拟器图形设置或使用 fallback shell。

## 5. 与作品形态的关系

Android UI 不直接假设 KMD 是纵向文本。

| 作品形态 | Runtime 底板 | 控制策略 |
|----------|--------------|----------|
| 滚动阅读 | 全屏纵向 viewport | 底部控制条，允许竖向滚动由 runtime 处理 |
| 分页阅读 | 全屏页面 viewport | 底部控制条 + 页码/marker |
| 横屏舞台 | 全屏舞台 viewport，优先横屏观看 | 浮层提供横屏切换入口，竖屏 letterbox 只作为过渡态 |
| 互动舞台 | 全屏交互 viewport，优先横屏观看 | 中心手势优先给 runtime，系统返回使用边缘/按钮 |

`presentationMode`、`orientationHint` 和 `aspectRatio` 决定 Android 传给 runtime 的 `viewport`。如果 `stage` / `interactive` 脚本在 `.kmd` frontmatter 声明了 `designWidth` / `designHeight`，Android 会优先把脚本声明的设计画布作为 runtime viewport；普通 `scroll` / `page` 阅读文档应自适应容器，不把固定设计画布作为布局事实。脚本没有声明可用的舞台设计尺寸时，回退到 `Work.presentation.aspectRatio` 和移动端默认尺寸。

当前原型先采用“作品内固定显示方向 + 横屏入口”的策略：

- 舞台类 `.kmd` 声明设计尺寸时，runtime viewport 使用该尺寸，例如 `1920x1080` 或 `1080x1920`。
- `portrait` 作品使用竖向 runtime viewport。
- `landscape` 舞台作品使用横向舞台 viewport；在手机竖屏中按比例 letterbox 只能作为进入横屏前的兼容态，不应视为理想阅读体验。
- 阅读浮层应提供明确的“横屏观看”入口。后续可以选择自动锁定横屏或由用户显式点击切换；课程阶段优先做按钮和提示，降低 Activity/WebView 生命周期风险。
- `adaptive` 作品根据 `aspectRatio` 推导 viewport。
- 运行中不因为 Android 系统旋转自动重建阅读会话。

产品判断：横屏舞台内容如果长期停留在竖屏 letterbox 中，上下空白不能承载正文，阅读价值很低。因此横屏舞台应被设计为“需要横屏观看”的内容形态，而不是普通竖屏阅读的一种缩略显示。

远期脑暴：竖屏中出现的上下空白、横屏中的侧边空间，可以被社区互动浮层使用。例如横屏短片浏览时采用上/下信息带，竖屏阅读时采用左/右或侧栏式评论摘要、弹幕摘要、审核提示、相关推荐。但这属于社区体验层，不属于当前 runtime UI MVP。

如果后续要支持阅读中切换横竖屏，需要把它作为 runtime 协议能力，而不是单纯依赖 Activity 重建：

1. Android 感知可用窗口尺寸或用户切换意图。
2. ViewModel 生成新的 `ReaderRuntimeViewport`。
3. Runtime Bridge 发送 `resize` / `setViewport` 命令。
4. Web runtime 在不丢失播放进度的前提下重排画面。
5. 失败时保持旧 viewport 并给出可恢复提示。

桌面切换手势在阅读态必须让位给 runtime。当前实现中，外层 `HorizontalPager` 在 `Desk.Reader` 激活时关闭用户滑动，避免横向手势被误判为退出阅读或切换桌面。返回详情通过阅读页顶部返回入口完成。

## 6. Compose 实现约束

推荐结构：

```kotlin
@Composable
fun ReaderDesk(...) {
    Box(Modifier.fillMaxSize()) {
        ReaderRuntimeHost(
            runtimeBridge = runtimeBridge,
            framed = false,
            modifier = Modifier.fillMaxSize()
        )

        RuntimeStatusOverlay(...)
        PlaybackControlOverlay(...)

        ReaderCompanionContainer(
            active = activeCompanion,
            placement = companionPlacement,
        )
    }
}
```

不推荐结构：

```kotlin
Column(Modifier.verticalScroll(...)) {
    SectionTitle(...)
    ReaderRuntimeHost(Modifier.height(300.dp))
    InfoCard(...)
    Slider(...)
}
```

原因：

- 小高度会使 stage 型 KMD 缩放过度。
- 滚动父容器会干扰 WebView、手势和可见区域。
- 卡片化容器会让用户把 runtime 理解为预览，而不是阅读本体。
- 控制条重组不应导致 WebView 重新创建。

## 7. Figma 页面建议

后续 Figma 先画以下帧：

1. 书架 / 有内容：继续阅读 + 本地作品 + 收藏/缓存分组。
2. 书架 / 空状态：导入 `.kmd`、查看随包示例、前往发现。
3. 书架 / 离线状态：可读作品和需联网作品的状态差异。
4. 阅读态加载中：全屏 runtime 底板 + 中央/底部加载状态。
5. 阅读态已就绪：全屏内容 + 底部控制条。
6. 播放中控制收起：只显示极简时间/返回/检查入口。
7. 暂停态：控制条常显。
8. 竖屏审阅抽屉：runtime 保持可见，检查面板覆盖下半屏。
9. 横屏审阅侧栏：runtime 左侧，审阅右侧。
10. 错误态：全屏错误提示 + 重试/返回/查看诊断。

Figma 命名建议：

- `Library / Ready`
- `Library / Empty`
- `Library / Offline`
- `Reader / Loading`
- `Reader / Ready Controls`
- `Reader / Playing Minimal`
- `Reader / Companion Review Sheet`
- `Reader / Companion Review Side Panel`
- `Reader / Companion Community Bands`
- `Reader / Error`

## 8. 当前实现迁移顺序

1. `ReaderDesk` 改为 `Box(fillMaxSize)`。
2. `ReaderRuntimeHost` 增加无边框全屏模式。
3. 播放控制从页面内容改为底部浮层。
4. 状态说明从 `InfoCard` 改为轻量状态浮层。
5. 建立 `ReaderCompanionContainer`，先承载审阅，后续再承载评论摘要、作品信息和诊断。
6. 阅读态隐藏或弱化普通顶部导航。
7. 阅读态关闭外层桌面 pager 的用户滑动。
8. 再处理控制条自动隐藏、横屏 companion 侧栏/信息带和 runtime resize 协议。

这套迁移应保持 ViewModel、Repository、Runtime Bridge 不变，只重排 UI 宿主关系。

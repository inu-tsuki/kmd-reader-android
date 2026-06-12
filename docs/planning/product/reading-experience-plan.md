# Android Reader Reading Experience Plan

> 文档状态：草案
> 最近更新：2026-05-27

## 1. 目标

本文规划 Android Reader 的阅读时体验。它补充 `knowledge/architecture/ui-design.md`，用于指导后续 Compose 原型、Figma 设计、ViewModel 状态和 runtime 协议演进。

相关文档：

- `planning/product/scriptreader-community-interaction.md`
- `planning/runtime/runtime-ui-implementation-plan.md`
- `planning/runtime/runtime-ui-extraction-plan.md`

阅读态不只是“播放 KMD 的 WebView”。它应该成为移动端 KMD 的主要体验现场：

- 让 KMD runtime 成为全屏底板。
- 让播放浮层在需要时出现，在观看时安静退场。
- 让横屏舞台、竖屏阅读、分页和滚动都能共用一套宿主模型。
- 让志愿审核者可以在不离开阅读现场的情况下查看脚本、问题和修改建议。

## 2. 体验原则

### 2.1 Runtime Owns Content

KMD 内容区域的中心手势默认归 runtime。宿主不要把单指点击、拖动、滑动全部拦走，因为未来 KMD 可能是互动舞台、视觉小说或横屏电影式内容。

宿主只保留三类操作：

- 系统级返回：返回详情页或退出阅读。
- 阅读控制：显示/隐藏播放浮层、暂停、进度跳转。
- 审阅工具：查看脚本、问题和审核结论。

### 2.2 Chrome Is Temporary

阅读浮层不是常驻工具栏，而是阅读时的 chrome。

推荐状态：

| 状态 | 行为 |
|------|------|
| `Visible` | 刚进入、暂停、拖动进度、发生错误时显示完整控制 |
| `Dimmed` | 播放中短暂显示轻量进度和返回入口 |
| `Hidden` | 正常观看时隐藏，只保留必要安全区域 |
| `Pinned` | 用户手动固定浮层，常用于调试或无障碍 |
| `Reviewing` | 审阅面板打开，控制浮层变成审阅辅助栏 |

MVP 可以先只实现 `Visible / Hidden / Reviewing`。

### 2.3 Review Is Companion

审阅不是跳到另一个编辑器，而是当前作品的 companion tool。

用户在阅读时打开审阅，应看到同一个作品、同一个播放位置、同一份脚本来源。关闭审阅后回到阅读，不销毁 runtime。

## 3. 双指点击浮层切换

### 3.1 为什么使用双指点击

单指点击很自然，但它会和 KMD 内容交互冲突。横屏互动式 KMD、视觉小说选择、舞台点击继续播放，都可能需要单指。

双指点击适合作为宿主保留手势：

- 误触概率低。
- 不占用 KMD 常见交互。
- 用户学习成本可控。
- Android 侧可以在 WebView touch 层观测，而不必给 Web 页面加全屏透明遮罩。

### 3.2 交互规则

| 操作 | 行为 |
|------|------|
| 双指轻点 runtime | `Hidden <-> Visible` |
| 双指轻点且任意 companion 打开 | 关闭 companion，回到普通阅读浮层 |
| 双击 companion 外区域 | 关闭 companion，作为鼠标/触控板和大屏辅助方式 |
| 播放中停留 3 秒无操作 | `Visible -> Hidden` |
| 暂停、拖动进度、错误 | 强制 `Visible` |
| 用户固定浮层 | 自动隐藏暂停 |

不建议第一版使用“单击屏幕显示浮层”。可以等 runtime 能声明 `interactionEnabled=false` 或作品不是互动内容时，再把单击作为可选快捷方式。

同理，不建议把“单击 companion 外区域”作为默认关闭动作。KMD 作品可能有单指点击、拖动或互动热点，栏外单击应优先交给 runtime 或阅读 chrome；关闭 companion 使用双指轻点、系统返回或栏外双击。

### 3.3 实现边界

推荐在 `ReaderRuntimeHost` 内给 WebView 设置 host touch observer：

```text
WebView touch event
  -> HostGestureDetector 只识别双指轻点
  -> 未命中时不消费事件，继续交给 WebView
  -> 命中时通知 ViewModel toggleReadingChrome()
```

不推荐在 WebView 上方覆盖一个全屏透明 Compose `pointerInput` 层。它很容易成为 hit-test 顶层，导致单指事件不再可靠传给 WebView。

长期需要一个 `HostGesturePolicy`：

```text
contentInteraction: passive | singleTap | multiTouch
hostGestures:
  twoFingerTap: toggleChrome
  edgeSwipe: back
  longPress: none | inspect
```

## 4. 横屏和竖屏切换

### 4.1 三种方向

阅读方向不能只看 Android 当前屏幕方向。我们需要区分：

| 名称 | 来源 | 作用 |
|------|------|------|
| 设备方向 | Android window / sensor | 决定物理屏幕可用空间 |
| 作品方向 | `Work.presentation` 或 KMD 声明 | 表达作者期望的画布形态 |
| runtime viewport | Android 传给 Web runtime 的尺寸 | 决定实际排版和渲染 |

这三者可以不同。例如，手机竖屏里播放横屏舞台时，设备方向是 portrait，作品方向是 landscape，runtime viewport 可以是 16:9 横屏画布并 letterbox。

### 4.2 作品形态

建议把作品形态整理为：

| 类型 | presentationMode | orientationHint | 宿主策略 |
|------|------------------|-----------------|----------|
| 纵向滚动 | `scroll` | `portrait` | 竖屏优先，runtime 占满宽度 |
| 分页阅读 | `page` | `portrait` 或 `adaptive` | 竖屏优先，支持页码/marker |
| 横屏舞台 | `stage` | `landscape` | 横屏优先，浮层提供横屏按钮，竖屏 letterbox 仅作过渡 |
| 横屏互动 | `stage` | `landscape` | 横屏优先，中心手势交给 runtime |
| 自适应作品 | `stage/page/scroll` | `adaptive` | 根据窗口和 `aspectRatio` 推导 |

### 4.3 MVP 策略

当前阶段不要依赖 Activity 自动重建来实现阅读方向切换。MVP 使用“作品内固定 viewport”：

1. Android 获取 `.kmd` source 后先读取 frontmatter 中的 `mode`、`designWidth`、`designHeight`。
2. 如果脚本是 `stage` / `interactive` 且声明了设计画布，`ReaderRuntimeViewport` 使用脚本尺寸。
3. 如果脚本是 `scroll` / `page`，不使用固定设计画布，阅读排版应自适应容器。
4. 如果脚本没有可用舞台设计画布，再从 `Work.presentation` 读取 `presentationMode`、`orientationHint`、`aspectRatio` 生成 fallback viewport。
5. `loadScript` 或 `updateSettings` 把 viewport 传给 runtime。
6. 横屏作品在竖屏手机中可以 letterbox 显示，但必须提供明确的横屏观看入口。
7. 阅读态禁用外层桌面横滑，避免和 runtime 手势冲突。

MVP 不建议一进入横屏舞台就无条件强制 Activity 横屏。当前 WebView runtime 仍在稳定性验证期，自动横竖屏切换会放大 Activity 重建、WebView renderer、播放会话恢复和系统手势冲突。更稳的做法是：

1. 在阅读浮层显示“横屏观看”按钮。
2. 用户点击后由宿主请求横屏，锁定当前阅读 Activity 方向。
3. 退出阅读或点击“竖屏”后恢复默认方向。
4. 如果横屏请求失败，保持竖屏 letterbox 并提示用户旋转设备。

### 4.4 长期策略

真正的横竖屏切换应成为 runtime 协议能力，而不是单纯重启 Activity。

理想流程：

```text
用户点击横屏/竖屏按钮或旋转设备
  -> Android 计算下一 viewport
  -> 暂存当前播放位置
  -> 发送 setViewport/updateSettings
  -> runtime 重排但保留播放状态
  -> runtime 回传 viewportChanged 或 ready
  -> Android 更新浮层布局
```

需要补充的协议能力：

- `ReaderRuntimeSettings.viewport` 已存在，可先复用。
- 后续增加显式命令 `setViewport`。
- 后续增加事件 `viewportChanged`。
- `ready` 事件可追加 `recommendedViewport`、`orientationHint`、`aspectRatio`。

## 5. 审阅时脚本查阅

### 5.1 审阅面板定位

审阅面板是轻量脚本检查工具，不是完整编辑器。

它应该支持：

- 查看 KMD 脚本文本。
- 行号和当前位置高亮。
- 搜索文本。
- 根据 runtime issue 跳到对应行。
- 展示 issue 的严重级别、来源、建议。
- 编写审核备注。
- 给出通过、退回修改、拒绝上架等审核结论。

它暂不承担：

- 完整语法补全。
- 大规模重排版编辑。
- 多文件工程管理。
- 复杂 Git diff。

Review 的用户心智应是“源码上下文 + 问题焦点”，不是“问题列表 + 嵌套源码窗口”。当前播放行、issue、搜索结果和选中行都应通过同一份源码查看器里的 highlight / focus 表达。

### 5.2 手机和横屏布局

手机竖屏：

```text
runtime full screen
  + bottom sheet review panel
    - source context view fills the panel
    - one-line command tray
    - source line context bubble

runtime full screen
  + bottom sheet issues panel
    - all issues for this work
    - issue detail / actions
```

横屏或大屏：

```text
runtime | review side panel
        | source context view
        | one-line command tray
        | source line context bubble

runtime | issues side panel
        | work issue ledger
        | selected issue detail
```

审阅打开时 runtime 不自动暂停。用户可以自己暂停，或在设置中启用“打开审阅时暂停”。

布局约束：

- 不显示 Review 独有的展开/收起按钮。
- 不显示“脚本审阅”、作品标题或源码标题等顶部占位。
- `KmdSourceContextView` 承担主滚动并默认显示全文，避免 issue 列表和源码查看器互相嵌套滚动。
- Review 只做脚本现场：播放行、选中行、issue 编号 marker、极简 command tray 和源码行上下文气泡。
- 底部 command tray 只显示当前播放行坐标和 companion 切换入口；跳转播放、提 issue、查看 issue、多选等局部动作从点击源码行后的气泡触发。
- Issues companion 只做问题台账：当前 work 的所有 issue、issue 详情、close/reopen、draft 和未来 discussion 入口。
- Review 与 Issues 使用同一 companion 高度和 placement；互相切换时不改变 runtime viewport。
- 从 Issues 选中 issue 可以切到 Review 并聚焦源码行；从 Review 行内 issue 编号可以切到 Issues 并打开 issue 详情。
- 审核结论、issue 管理和 discussion 不再挤占 Review 的源码空间。

### 5.3 源码查阅模型

Android 侧需要保存一个只读源码快照：

```text
KmdSourceSnapshot
  workId
  content
  version
  fetchedAt
  lineOffsets
```

审阅面板不直接向 WebView 索要源码。ViewModel 从 Repository 获取 `source`，同时把同一份 `source` 传给 runtime 播放，避免出现“播放的脚本”和“审阅看到的脚本”不是同一份。

### 5.4 位置同步

位置同步分两条线：

- runtime -> Android：`progressChanged` 携带 `line`、`paragraphIndex`、`markerId`。
- issue -> source viewer：`inspectionReported.issues[].location` 或 future structured range 指向源码位置。

推荐未来扩展：

```text
RuntimeIssue.location:
  file?: string
  line?: number
  column?: number
  endLine?: number
  endColumn?: number
```

当前已有 `timelineMarkers` 和 `line` 字段，可以先用它们完成“播放到哪一行”的轻量高亮。

### 5.5 轻度修改

志愿审核者可以提出轻度修改建议，但不直接覆盖作品原文。

MVP 建议只做三件事：

- 在问题上写备注。
- 对单行或短片段写建议文本。
- 生成 `ReviewDraft`，供作者或维护者后续处理。

长期再考虑：

- 结构化 patch。
- 小范围自动修复。
- 审阅者权限分级。
- 修改建议与社区讨论联动。

## 6. 状态与数据

### 6.1 ViewModel 状态

建议新增或整理阅读态状态：

```text
ReaderChromeState
  isVisible
  isPinned
  mode: reading | reviewing | error
  lastInteractionAt

ReaderViewportState
  presentationMode
  orientationHint
  aspectRatio
  runtimeViewport
  letterboxed

ReviewSessionState
  sourceSnapshot
  issues
  selectedIssueId
  selectedLine
  draftNote
  decision
```

### 6.2 Runtime 协议优先级

短期不必扩协议：

- 使用现有 `loadScript.settings.viewport`。
- 使用现有 `updateSettings`。
- 使用现有 `progressChanged.line`、`timelineMarkers`。
- 使用现有 `inspectionReported.issues`。

中期再扩：

- `setViewport`
- `viewportChanged`
- structured source range
- richer `HostGesturePolicy`

## 7. 实施顺序

### Step 1：阅读浮层状态

- ViewModel 增加 `ReaderChromeState`。
- 播放中自动隐藏，暂停/错误显示。
- 双指点击切换浮层。
- 阅读态继续禁用外层桌面横滑。

### Step 2：作品 viewport 策略

- 从 `Work.presentation` 生成 `ReaderRuntimeViewport`。
- 横屏作品在竖屏中 letterbox。
- 浮层根据横屏/竖屏选择底栏或角落控制簇。

### Step 3：审阅脚本查阅

- Repository 暴露当前作品源码快照。
- 审阅面板加入源码查看器、行号、issue 跳转。
- runtime progress 高亮当前行。

### Step 4：轻度修改建议

- 增加 `ReviewDraft`。
- 支持 issue 备注和短片段建议。
- 不直接改写原始 `.kmd`。

### Step 5：runtime 协议增强

- 增加显式 viewport 切换命令和事件。
- 增加结构化源码 range。
- 根据 runtime capability 渐进启用交互能力。

## 8. 课程验收取舍

下一周验收不要一次追完整阅读系统。建议交付：

- 真实 runtime 全屏播放。
- 基础播放浮层。
- 阅读态不崩溃。
- 审阅入口和 issue 面板可用。
- 测试报告覆盖进入阅读、播放、打开审阅、返回详情。

双指点击、横屏 letterbox、源码查阅可以作为展示亮点逐步实现。真正的 runtime 方向热切换和结构化 patch 放到验收后。

# R3-I 全局阅读偏好实施计划

> 状态：Android 宿主实现完成，待审查；runtime 完整语义由主仓库稳定化工作包承接
> 最近更新：2026-07-12
> 前置：R3-B 进度持久化、R3-F `SettingsSheet` 入口、现有 `ReaderRuntimeBridge.updateSettings()`

## 1. 目标

把现有设置占位扩展为可持久化、可即时应用的全局阅读偏好。偏好不附着到作品，不进入 Room 或 `.kmd` frontmatter；阅读中的变更不得重建 `ReaderRuntimeHost` 或重置播放会话。

本切片交付四项设置：

- 字号缩放 `fontScale`
- 主题 `System / Light / Dark`
- 自动保存进度 `autoSaveProgress`
- 减少动态效果 `reducedMotion`

R3-I 的仓库边界是偏好持久化、Compose 主题、Reader 内设置入口、协议投影与宿主状态机。
Web runtime 内部的静默 projection rebuild、presentation-mode capability 与
reduced-motion effect 策略由 KMD 主仓库
`docs/planning/packages/reader-runtime-web.md#runtime-settings-transaction` 承接，不作为
Android 子仓库自身的合并阻塞。R3-I 对这两项的验收止于值可持久化、正确投影并发送到
runtime；跨 mode 的实际视觉/播放语义在主仓库工作包完成后做联合 smoke。

## 2. 偏好模型与默认值

```text
ReaderPreferences
  fontScale: Float = 1.0        // 合法范围 0.85..1.30
  themeMode: ThemeMode = System
  autoSaveProgress: Boolean = true
  reducedMotion: Boolean = false
```

读取持久化值时必须校验并归一化：未知主题回退 `System`，非有限或越界字号回退/夹紧到合法范围。DataStore key 与默认值集中定义，不能分散在 UI、ViewModel 和 mapper 中。

## 3. 数据边界

使用 AndroidX DataStore Preferences，新增 `ReaderPreferencesRepository`：

- 暴露稳定的 `Flow<ReaderPreferences>`。
- 每个设置提供字段级更新，避免调用者用陈旧整对象覆盖其他并发修改。
- DataStore 读取异常回退默认值并保留可诊断信号；单次写入失败不应让 runtime 或 ViewModel 崩溃。
- Repository 注入 `KmdReaderViewModel.Factory`；不使用静态全局单例，不让 Composable 直接读写 DataStore。

`KmdReaderState` 持有当前偏好与加载状态。UI 只显示 state 并 dispatch action。

## 4. UI 契约

继续扩展既有 `SettingsSheet` overlay，不新增 Desk：

- 主题使用三段 segmented control：跟随系统 / 明亮 / 暗色。
- 字号使用带当前百分比的 slider，范围 `0.85..1.30`，步长 `0.05`；提供明确的 100% 默认位置。
- 自动保存进度与减少动态效果使用 switch。
- 保留关于和版本信息，但阅读偏好位于主要区域，项目说明不能压过设置。
- 控件必须支持窄屏、横屏和大字体；设置 sheet 保持可滚动。

slider 拖动可以即时更新内存预览，但持久化应采用完成手势时写入或明确的 debounce，避免每个像素产生 DataStore 写入。

## 5. 应用偏好

### 5.1 主题

`MainActivity` 在 `KmdreaderTheme` 外层观察 ViewModel 偏好：

- `System` 使用 `isSystemInDarkTheme()`。
- `Light` 强制 light scheme。
- `Dark` 强制 dark scheme。

主题切换只引发 Compose 重组，不改变 desk stack、reader session 或 `readerHostRestartToken`。

### 5.2 Runtime settings

建立唯一的 settings resolver，将 viewport 所有字段与用户偏好合并：

```text
ReaderViewportState + ReaderPreferences -> ReaderSettings
```

它必须用于所有生产路径：

1. `ReaderLoadRequest.settings` 初次加载。
2. host 尺寸/方向变化后的 `updateSettings()`。
3. `fontScale` 或 `reducedMotion` 改变后的 `updateSettings()`。

当前 `ReaderViewportPolicy.settingsFor()` 会创建默认 `fontScale=1f`、`reducedMotion=false`；R3-I 必须消除这个覆盖风险。viewport 更新后仍应保留用户偏好，偏好更新后仍应保留 viewport/presentationMode/assetBaseUrl。

偏好在 Reader 尚未 Ready 时只更新 state，下一次 load 消费最新值；Ready 时通过 bridge 热更新。失败显示轻量反馈，但不得重建 host。

## 6. 自动保存进度的状态转换

关闭自动保存不是简单地在写入点加一个 `if`。必须满足顺序契约：

```text
Enabled
  -> flush 当前 Ready session 的最新进度
  -> 内存门控切为 Disabled
  -> 持久化 false
Disabled
  -> ProgressChanged 仍更新会话/UI，但不写 local_library
  -> onCleared 不写进度
  -> 持久化 true
  -> 内存门控切回 Enabled，后续事件恢复既有节流策略
```

实现应将正常节流写、关闭前 flush 和 `onCleared` 的门控集中到同一策略/辅助函数。关闭动作与同时到达的 `ProgressChanged` 必须有明确串行化或 generation/互斥所有权，不能让关闭后的迟到 coroutine 再写入。

关闭只停止未来保存，不清除既有进度；恢复 seek 仍读取最后一次已保存记录。重新开启后，下一次有效进度事件应能落盘，不得被关闭前的节流时间戳长期抑制。

## 7. 实施步骤

1. 增加 DataStore 依赖、偏好模型、key、Repository 与 DI。
2. 将偏好与加载状态接入 `KmdReaderState`，ViewModel 启动时持续收集。
3. 增加偏好更新 actions 与副作用处理；保持 reducer 纯函数。
4. 建立统一 `ReaderSettings` resolver，并替换 load、viewport update 两条旧路径。
5. 为 Ready 会话接入 fontScale/reducedMotion 热更新。
6. 接入主题根节点，验证不改变 Reader host key/token。
7. 实现自动保存关闭前 flush、关闭后停写、重开恢复及竞态防护。
8. 完成 `SettingsSheet` 控件、状态与反馈。
9. 补测试、文档落地记录与 roadmap 状态。

## 8. 回归矩阵

- 默认值与缺 key。
- 四项偏好的单字段持久化和重建后恢复。
- 非法 theme/fontScale 值的回退。
- DataStore 读取/写入失败的容错。
- 初次 Reader load 携带当前 fontScale/reducedMotion。
- Ready 时修改偏好发送 `updateSettings`，Loading/Idle 时不发送。
- viewport 更新保留用户偏好；偏好更新保留 viewport 和 presentationMode。
- 主题 System/Light/Dark 映射，切换不改变 session/restart token。
- 自动保存默认开启，既有 5 秒策略不回归。
- 关闭前 flush 最新进度；关闭后 ProgressChanged 与 onCleared 都不写库。
- 重新开启后下一次有效事件恢复写入。
- 关闭与迟到进度写交错时，Disabled 成为最终持久化行为。
- slider 写入不会形成无界 DataStore 写风暴。

## 9. 门禁与验收

- `./gradlew :app:testDebugUnitTest --rerun-tasks`
- `./gradlew :app:assembleDebug`
- `git diff --check`
- 模拟器/真机 smoke：修改四项偏好、重启、进入阅读、旋转/改变窗口、确认 WebView 不重建且偏好保持。

## 10. 落地记录与验收（2026-07-12）

- `ReaderPreferencesRepository` 使用 DataStore Preferences 的字段级 `edit` 更新，状态由 ViewModel 收集后投影到 `KmdReaderState`；读取异常回退默认值，非法字号归一化到支持范围。
- `ReaderSettingsResolver` 是 viewport 与偏好的唯一生产合并点，覆盖首次 load、viewport update 和 Ready 热更新；主题只驱动 Compose 根主题，不触及 reader host restart token。
- 自动保存的节流写、关闭前 flush 与 `onCleared` 兜底共享同一 mutex。关闭后内存门控立即生效，已保存进度保留；重新开启清除每作品节流窗口，下一次事件可写入。
- `SettingsSheet` 已提供主题、字号、自动保存和减少动态效果控制，同时保留关于信息与滚动容器。

以上记录已经通过 Android、runtime 与模拟器分层验证。字号的静默 projection rebuild
收口仍不属于 R3-I，保留在主仓库的 deferred runtime work 中。

### 自动化证据

- Android：`./gradlew :app:testDebugUnitTest --rerun-tasks` 通过（224 tests，0 failures/errors，2 skipped）；`./gradlew :app:assembleDebug` 通过。
- `ReaderPreferencesTest` 覆盖缺 key 默认值、非法 theme/fontScale、字段级 edit、同一文件的 DataStore 重建恢复，以及 `IOException` 读取回退。
- `KmdReaderViewModelTest` 覆盖关闭前 flush、关闭后 `ProgressChanged` 与 `onCleared` 停写、重开后首个事件恢复写入；受控 `updateProgress()` 挂起点验证在途写、关闭请求和迟到进度事件的串行结果，flush 失败时保持开启并发送 effect。
- Runtime：`pnpm build`、`pnpm test:parser`、`pnpm --filter @kmd/editor test:playback`（328 passed）、`pnpm --filter @kmd/editor test:invariants` 与 `pnpm test:e2e` 均通过。playback 回归验证 Scroll/Page 的初始及热更新字号缩放、热更新触发 typography rebuild，以及 Stage 不缩放且不进入 rebuild。
- 手工 smoke：同一 Reader session 内通过 chrome 的 SettingsSheet 调整字号，Paged/Scroll 初次加载与热更新均生效；主题、自动保存关闭/重开和进度恢复路径已复测。

### 已解决的阻塞与边界决策

- Reader chrome 复用既有 `SettingsSheet` overlay；它不新增 Desk，也不改变 desk stack、
  reader host、session 或播放位置。设置入口因此在活跃阅读会话中可达。
- R3-I 的 Android 边界是持久化偏好、合并 `ReaderSettings`、在 load/Ready 时发送协议，并保持
  host/session 稳定。`fontScale` 的 measurement、render、mode matrix 与重排实现属于主仓库
  [`reader-runtime-web` package plan](../../../../docs/planning/packages/reader-runtime-web.md)，不作为
  R3-I 的独立视觉验收项。
- 自动保存保证应用仍存活时的显式边界：离开 Reader 时 flush，`onCleared` 兜底；关闭后停止
  写入，重开后恢复。后台、系统强杀和 force-stop 不承诺执行最后一次 flush。

Runtime 当前的 typography reflow 仍有宿主中间态债务，已由 `reader-runtime-web` 的 deferred
work 跟踪；它不阻塞 R3-I 的 Android 偏好集成验收。

## 11. 非目标

- 不做每作品覆盖、播放速度、默认方向或 chrome pinned 持久化。
- 不修改 `.kmd` frontmatter 或 runtime 协议字段形状。
- 不实现账号同步、云端偏好、缓存管理。
- 不借设置改动重构 Reader host 生命周期。
- 不承担书架视觉完善；该工作归 R3-K。

# R3-K 书架体验收束计划

> 状态：历史执行记录；K1 已完成，K2 已转入 Post-R3 backlog
> 最近更新：2026-07-14
> 前置：R3-F/G/H/I 已完成

## 1. 定位与决策

R3-F/G/H 已闭合书架、历史、收藏和续读的数据链路，但 `MineDesk` 仍是把两个列表顺序
排开的功能原型。R3-K 将它收束为默认阅读资产首页：安静、可扫描、状态明确，常用动作在
一到两次操作内完成。

本阶段只消费 Android 已经拥有的本地事实，不重新设计 Repository，也不把书架变成账号页、
项目说明页或发现流。

已确认的事实边界：

- `ShelfState.shelf` 是 `onShelf=true` 的资产；`history` 是读过但未在书架的资产，两组互斥。
- `ShelfItem.hasLocalSource` 只能表达“本地可读”或“需要远端 source”，不能表达设备当前
  是否联网。UI 使用“本地可读 / 需联网”，不伪造“当前离线”检测。
- `ImportState` 是全局导入过程状态，可以在书架显示 importing/failed banner，但失败不归属
  某张作品卡片。
- 当前模型不能区分“收藏”与“已缓存社区作品”，K1 不制造这个不存在的分类。
- 继续阅读候选来自书架与历史的合并快照，要求 `0 < progress < 1` 且有 `lastReadAt`，按
  最近阅读排序、workId 去重后取最多 3 项。revision 兼容与最终 seek 继续由既有 Reader
  恢复链路裁决，书架不复制第二套规则。

## 2. 交付拆分

### K1：结构、状态与响应式体验

K1 是本轮发布给编写者的范围，必须能独立合并。

#### 2.1 纯 UI 投影

在 `ui/screen/mine` 建立纯 Kotlin 投影，例如：

```text
ShelfState + BookshelfView + nowMillis -> BookshelfUiModel

BookshelfUiModel
  continueReading: List<ShelfItem>     // 最近 1..3 项
  localShelf: List<ShelfItem>          // onShelf && hasLocalSource
  networkShelf: List<ShelfItem>        // onShelf && !hasLocalSource
  history: List<ShelfItem>             // 现有互斥 history
  emptyState
```

名称可以顺应代码风格，但必须满足：投影是确定性的纯函数；Composable 不查询 Repository、
不解释 `onShelf/lastReadAt/hasLocalSource`；相对时间的 `nowMillis` 可注入，不能让纯测试依赖
墙钟。不要把整个 UI model 塞进 `KmdReaderState`，底层事实仍以 `ShelfState` 为真相源。

`BookshelfView` 使用 `Library / History` 两态。当前视图属于页面瞬时 UI 状态，可用
`rememberSaveable`；它不是阅读偏好，不进 DataStore。

#### 2.2 页面信息架构

- 顶部工具栏：标题“书架”；导入与设置使用 icon button + content description/tooltip。
- 主视图切换：书架 / 历史使用 Material segmented control 或等价 tab，不把两个完整列表接成
  一条无限长页面。
- 继续阅读：仅在 Library 视图且有候选时显示，最多 3 项；突出标题、进度、上次阅读时间和
  直接继续动作。
- 书架资产：先显示“本地可读”，再显示“需联网”；空分组不显示空标题。
- 历史视图：按既有 Repository 顺序展示读过但未收藏的资产；空历史有独立轻量状态。
- 顶层导入状态：Importing 显示稳定进度提示；Failed 显示错误摘要和重新进入导入流程的动作。
- 设置仍是 overlay；关于信息仍在 SettingsSheet，不在书架正文重复项目介绍。

#### 2.3 卡片与动作

- 卡片显示标题、作者、presentation mode、来源状态、进度和相对时间；不重复同一元数据。
- 整卡点击打开详情。直接继续是独立明确动作，不能因嵌套 clickable 产生双触发。
- 进度仅在 `0 < progress < 1` 时显示；完成态不要伪装成“继续阅读”。
- 来源状态只使用现有事实：“本地可读”或“需联网”。不要新增 ConnectivityManager 或网络
  监听来扩大 K1。
- K1 不新增移出书架、删除、清缓存按钮；也不放不可用占位按钮。

#### 2.4 响应式与视觉约束

- 手机竖屏优先。窄屏使用单列；宽屏/横屏可用稳定的自适应 grid，但继续阅读与普通资产要有
  清晰层级，不做嵌套 card。
- 页面使用一个主滚动容器；不要在 `Column.verticalScroll` 中再嵌滚动列表。
- 卡片圆角不超过 8dp，按钮/状态文字不能改变卡片固定操作区的布局。
- 使用 Material 3 主题，不引入单色品牌皮肤；工具动作使用 Material icons。若项目缺少 icon
  artifact，按 version catalog 增加 Compose Material icons 依赖，不手绘 SVG。
- 大字体、长标题、长作者名必须换行或 ellipsis，不得与按钮、badge、进度重叠。
- 不添加解释功能、布局或快捷键的可见教程文字。

### K2：排序、筛选与非破坏性管理

K1 合并后可独立实施：

- 排序：最近阅读、最近导入、标题；默认保持现有 Repository 顺序。
- 筛选：全部、本地可读、需联网；只作用于当前 `ShelfState` snapshot。
- 移出书架：复用 R3-G `ToggleShelf`，提供明确反馈并保持 generation/mutex 所有权。
- 排序/筛选选择状态先保持页面内状态；是否持久化以后单独决定，不借用 R3-I DataStore。

### K2 之外：破坏性数据动作 gate

删除本地作品、删除 revision、清理 bundle/cache 不属于已获准的 K2 实现。开始前必须单独定义：

- `LocalLibraryEntry`、source、bundle 解包目录与 revision 的删除顺序。
- issue draft / annotation 是否级联、保留或阻止删除。
- 正在阅读或被当前 session 使用的资产如何处理。
- Room 与文件系统部分失败时的恢复/补偿策略。

在这份数据边界完成前，UI 不展示删除/清缓存伪按钮。

## 3. 代码边界

K1 预期主要改动：

- `ui/screen/mine/MineDesk.kt`
- `ui/screen/mine/ShelfModels.kt` 或新的相邻 UI projection 文件
- `ui/app/KmdReaderApp.kt`：只补 `ImportState` 等已有 state/action wiring
- 对应纯 JVM 测试与必要的 Compose/UI 测试
- version catalog / app dependencies（仅在 Material icons 确实缺失时）

不得为了卡片展示直接 join `WorkRepository`，不得改 Room schema，不得重写 R3-F/G/H 的刷新、
toggle、进度或 revision 并发所有权。若展示所需事实不存在，先记录为后续数据需求，不从多个
source 在 Composable 中临时拼接。

## 4. 测试与验收矩阵

### 4.1 纯 JVM 投影

- 空 `ShelfState`。
- continue 候选合并 shelf/history、去重、按 lastReadAt 排序、最多 3 项。
- progress 为 0、1、越界或 lastReadAt=null 时不进入 continue。
- local/network 两组只按 `hasLocalSource` 分组，输入列表不被修改。
- Library/History 视图空状态正确且确定。
- 相对时间使用注入时钟，边界输出稳定。

### 4.2 UI/交互

- 导入、设置、打开详情、继续阅读分别只触发一次正确 callback。
- Library/History 切换保持页面结构稳定。
- importing/failed banner 的动作可达，Idle 不占空间。
- 窄屏、大字体、横屏下无裁切、重叠和不可达动作。

### 4.3 截图 smoke

至少保留五组截图或等价验收证据：

1. 有 continue + 本地/需联网资产的手机竖屏。
2. 空书架。
3. 空历史。
4. 大字体或窄屏。
5. 横屏/宽屏。

若当前环境无法运行模拟器，必须明确报告未完成的视觉验证，不得用 JVM 测试代替截图结论。

## 5. 门禁

- `./gradlew :app:testDebugUnitTest --rerun-tasks`
- `./gradlew :app:assembleDebug`
- `git diff --check`

## 6. K1 完成标准

- 首屏能快速识别继续阅读、本地可读和需联网资产。
- 书架与历史是清晰的模式切换，不再是两个无限串联列表。
- 导入、设置、详情和继续阅读在一到两次操作内可达。
- 空、导入中、导入失败状态有明确且真实的下一步。
- 纯投影回归、Android 单测、assemble 与 diff check 通过。
- 完成至少五种目标 viewport/state 的视觉证据，或明确留下尚未完成的模拟器 gate。

## 6.1 K1 实施记录（2026-07-12）

- **实现**：`ShelfState.toBookshelfUiModel(view, nowMillis)` 是唯一的书架事实解释边界。它保持原有 shelf/history 查询语义，Library 中按 `hasLocalSource` 投影为“本地可读 / 需联网”，从两个互斥组汇总有效中段进度为最近、去重的最多三项 continue；相对时间完全由传入时钟生成。
- **交互**：`MineDesk` 使用单一 `LazyVerticalGrid` 主滚动容器和页面内 `BookshelfView`，在窄屏单列、宽屏自适应多列。导入/设置为带 content description 与 tooltip 的 Material 图标；失败导入可真实重进既有 SAF 导入流程。continue 与详情按钮使用独立语义节点，避免双触发。
- **自动验证**：`./gradlew :app:testDebugUnitTest --rerun-tasks`、`./gradlew :app:assembleDebug`、`git diff --check` 均通过；模拟器 `connectedDebugAndroidTest` 8/8 通过，覆盖工具动作、卡片 action、state 驱动的书架/历史切换、history-only continue 与导入状态。卡片 action 使用 section/work-specific test tag，避免“继续阅读”标题与按钮的文本选择器歧义。
- **视觉验收**：`docs/planning/evidence/r3-k1/README.md` 已归档 portrait mixed Library、空 Library、空 History、history-only continue、系统大字体/长文案与横屏证据。横屏 mixed 图曾暴露左侧 display cutout 覆盖首卡；修复后非 Reader desk 使用 safe drawing inset，独立 cutout-safe 横屏图确认内容安全区，Reader runtime 仍保持 edge-to-edge author viewport。mixed 网格图与修复后安全区图共同覆盖横屏验收。
- **K2 保持未开始**：排序、筛选、移出书架和所有删除/缓存动作不属于此次变更。

## 7. 非目标

- 不做推荐流、社区互动、账号中心或项目 landing page。
- 不新增缓存下载协议、ConnectivityManager 或网络状态监听。
- 不实现排序筛选、移出书架、删除作品或清缓存（它们属于 K2/破坏性动作 gate）。
- 不重写 R3-F/G/H/I 的数据与并发所有权。
- 不把阅读偏好控件复制进书架正文。

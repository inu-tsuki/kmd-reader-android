# R3-D 本地导入 —— 调研与方案讨论

> 文档状态：调研稿（已完成 2026-06-25 问题清账 + 2026-07-07 决策补充，仍非最终规范）
> 建立时间：2026-06-22
> 代号：R3-D
> 权威计划：[`r3-local-reader-plan.md`](r3-local-reader-plan.md) §R3-D（当前仅 3 行提纲）
> 性质：本文是为 R3-D 实现做准备的现状勘察 + 方案对比 + 问题清账。§7 记录哪些问题已经阶段性收束、哪些问题被新的模型替代、哪些问题仍需调研；除此之外，所有"倾向"均为讨论提议，**不是已采纳的最终规范**。代码引用以 2026-06-22 仓库状态为准。

本文是讨论的起点，不是结论。落盘目的是让规划者能在一个地方看到：现状有哪些缺口、有哪些可选方案、哪些点必须先拍板才能进实现。

---

## 1. 背景与定位

R3-D 的目标（见 r3-local-reader-plan.md §R3-D）：用户能导入本地文件 → 生成 `LocalLibraryEntry`（`onShelf=true, kmdSource=全文`）→ 出现在书架 → 可阅读。

当前 R3-D 在 r3-local-reader-plan.md 里只有提纲：

```text
### R3-D. 本地导入
- 扩展 frontmatter 解析器
- SAF 文件选择
- 导入 → LocalLibraryEntry（onShelf=true, kmdSource 全文）
```

但"导入一个文件"背后藏着一个更根本的问题：**一个作品要完整成立需要哪些事实，本地导入场景下这些事实从哪来**。本文围绕这个问题展开。

---

## 2. 核心张力：作品定义散落 6 处，缺聚合层

当前一个"作品"要完整成立，需要这些事实同时存在：

| # | 位置 | 内容 | 谁能提供 |
|---|---|---|---|
| 1 | `.kmd` frontmatter | title / mode / design 尺寸 / speed / variables | 作者（脚本本地） |
| 2 | `.kmd` 正文 | 文本 + 内联命令序列 | 作者 |
| 3 | `Work` 实体 | author / tags / category / lifecycle / commentSummary | 社区平台 |
| 4 | `KmdScriptRevision` | revisionId / sourceUrl / contentHash / runtimeVersion | 版本系统 |
| 5 | `WorkAssetManifest` | 字体 / 图片 / shader / 音频 | 骨架存在，无人填充 |
| 6 | `Work.presentation` 投影 | aspectRatio / orientationHint / effectIntensity 等 | 应派生，当前硬编码 mock |

**本地导入场景的根本矛盾**：#3（社区数据）和 #4（云端 revision）在本地导入时都不存在。一个导入的作品必须自包含足够信息，让 reader 在没有云端的情况下合成出一个可用的 `Work`。

这就引出 `.kmdwork` 的设想（见 §4）：本地导入的文件应当聚合 §2 表里的若干项，成为"自包含作品包"。

### 2.1 `.kmdwork` 应该聚合哪几项（2026-06-25 阶段性收束）

- **必须含**：#1（entry `.kmd` 源全文，含 frontmatter）、#5（assetManifest + assets 字节——否则带外部资产的脚本会缺资源）。
- **应预留/倾向含**：#6（`presentation` 派生投影或导出时快照）、#4 的本地 revision manifest / origin mapping。它们服务离线列表、导出、下载为本地、本地协作和版本管理，但不应成为 `.kmd` 脚本内部的作者手写事实。
- **不作为本地权威**：
  - #3 的社区状态部分（commentSummary / lifecycle / review status / ranking）—— [`work-kmd-content-model.md`](../../../../../../docs/knowledge/architecture/work-kmd-content-model.md) 已明确"平台状态不进源文件"。离线 bundle 最多保存导出时快照，并标注它可能过期。
  - 云端颁发的 workId / revisionId 不应被本地作品当作唯一身份。可以保存 remote origin mapping，但本地 identity 仍需由 bundle/revision 模型单独定义。

---

## 3. 硬前置：frontmatter drift（比打包格式更紧急）

`.kmdwork` 在 frontmatter 字段没收敛前定义，会直接继承现有的三端 drift。本节是核实后的现状（2026-06-22），**比单纯信任讨论稿更重要——drift 比初看更广**。

### 3.1 核实后的字段覆盖矩阵

| 字段 | editor parser | editor UI 读写 | reader | community-api | 样本实际用 |
|---|---|---|---|---|---|
| `title` | ✅ | ❌ 不写回 | ❌ 不读 | ✅（`mode` query 等） | ✅ |
| `author` | ✅ | ❌ | ❌ | — | ❌ 从未出现 |
| `mode` | stage/scroll/page | ✅ | ✅ + interactive | scroll/**paged**/stage/interactive | stage |
| `bgColor`/`fontColor`/`fontFamily` | ❌ 不在类型 | ✅ 读写 | ❌ | ❌ | ❌ |
| `kmdVersion` | ❌ | ❌ | ❌ | ❌ | ✅ 仅文档示例 |

来源核实：
- editor reader-runtime contract：`apps/editor/src/core/runtime/ReaderRuntimeContract.ts:5` → `ReaderRuntimePresentationMode = "stage" | "scroll" | "page"`。
- community-api：`apps/community-api/src/domain/types.ts:1` → `PresentationMode = 'scroll' | 'paged' | 'stage' | 'interactive'`；`workDto.ts:5` 同。
- reader：`KmdSourceMetadataParser.kt:45-53` → 认 `page` 和 `paged` 两种 + `stage` + `scroll` + `interactive`。
- editor UI 写回：`apps/editor/src/store/editorStore.ts:100-102` 写 `bgColor/fontColor/fontFamily`。

### 3.2 两个硬冲突

1. **mode 枚举三端不一致**：
   - editor 只认 `stage/scroll/page`；
   - community-api 认 `scroll/paged/stage/interactive`；
   - reader 两边兜底（认 `page` 和 `paged`，并认 `interactive`）。
   - **后果**：导入一个 `interactive` 作品，editor 打开会降级；`page` 与 `paged` 的命名分裂本身就是未对齐的契约。

2. **editor 产出自己 parser 不认的字段**：`bgColor/fontColor/fontFamily` 在 `editorStore.ts:100-102` 写回 frontmatter，但 `ReaderRuntimeContract` 的 frontmatter 类型里没有它们——editor 产出的 frontmatter 自己的 reader-runtime 契约都不能完整往返。

### 3.3 处理建议（2026-06-25 清账后）

frontmatter drift 应独立成一个**前置子项**（暂称 R3-D0，或并入 `docs/knowledge/language/`）：先定义 frontmatter 的功能边界、效力分层和写回规则，再谈字段对齐。否则 `.kmdwork` 会把 drift 固化进文件格式。

落点提议：`docs/knowledge/language/frontmatter-schema.md`（新建）。

2026-06-22 规划讨论补充了一层更重要的判断：这里的问题不只是 editor / reader / community-api 各自实现 drift，而是 **frontmatter 字段本身的功能、效力和应用范围尚未定义**。因此下一步不应直接把现有字段机械对齐，而应先在 KMD 多态性基础上重新讨论 frontmatter：

- frontmatter 是语言事实、作品发布事实、editor 偏好，还是 runtime 启动 hint？
- 哪些字段可以成为 `.kmd` 随身携带的作者事实？
- 哪些字段应属于 `Work.presentation` 的派生结果，而不是作者手写？
- 哪些字段只是特定宿主/editor 的工作区偏好，不应进入可发布/可导入作品？
- `mode` 是否仍是单纯呈现模式，还是应和 capability 声明形成一套可演进关系？

相关生态草案：[`presentation-modes-and-capability-layering-draft.md`](../../../../../../docs/planning/ecosystem/presentation-modes-and-capability-layering-draft.md) 已指出 KMD 可能覆盖视频素材、字幕、提词器、叙事作品等多形态输出，`mode` 与能力集的关系仍是开放问题。R3-D 的 frontmatter schema 不宜绕过这层讨论直接定稿。

---

## 4. `.kmdwork` 打包格式：三种选项

讨论打包格式前，先看现成雏形：`apps/community-api/content/works/{work-id}/rev-1.kmd`——一作品一目录，但目前每目录只有单 `.kmd`，无 manifest。

### 选项 A：纯文本增强（扩展 frontmatter）

不引入新格式。`.kmd` frontmatter 增补字段（`assetManifest` 内联，或 `assets:` 字段指向同目录文件）。导入就是导入一个 `.kmd` + 可选的同目录 `assets/`。

- **优点**：零格式负担，复用现有 parser。
- **缺点**：多文件作品无法单文件分享；`assets/` 约定是隐式的。

### 选项 B：目录约定（标准化现有雏形）

`.kmdwork` 本质是个约定结构的目录：`work.json`（manifest）+ `script.kmd` + `assets/*`。导入接 SAF 选目录或 zip。

- **优点**：贴近云端 `content/works/` 布局，云端/本地一致。
- **缺点**：Android SAF 对目录支持弱（`ACTION_OPEN_DOCUMENT_TREE`），单文件分享不友好。

### 选项 C：单文件容器（zip）

`.kmdwork` 是个 zip，内部是 B 的结构。导入接 SAF `OpenDocument` 选单个文件。

- **优点**：单文件可分享，Android SAF 友好，zip 生态成熟。
- **缺点**：要处理 zip 读/解压；reader-runtime 现在只认 source 文本 / sourceUrl / assetManifest，不认 zip。

### 阶段性回答（2026-06-22）

规划者阶段性回答：选择 **选项 C：`.kmdwork` zip 单文件容器**。理由：它容易拓展和分享，后续无论是否纳入资源引用、如何决定字段，都不会影响"导入/导出一个文件"这个外部形态本身。

仍需注意：这只是**容器形态**收束，不等于 `work.json` 字段、frontmatter schema、revision 模型、多脚本语义已经定稿。zip 先提供外壳，内部结构应继续保持可演进。

原倾向理由仍成立：zip 最契合"本地导入"的分享场景（作者导出一个文件，读者导入一个文件），且 zip 内 manifest 与现有 `ReaderRuntimeAssetManifest`（`ReaderRuntimeContract.ts:87-91`）天然对齐——manifest 描述 assets，zip 体提供 assets 字节。代价是要在 reader 侧加一个 zip→manifest+source 的展开层，但这个展开层可以和 R3-E 的 LocalRevision 源解析共用一个"作品源解析"入口。

### 4.1 多脚本与 zip 的扩展余地

规划者补充：考虑长叙事和互动叙事，KMD 叙事作品应该支持"多脚本的编写"。但最后导出/播放时是否应组装成单脚本，暂时不能决定。这不是单纯的格式问题，而是同时牵涉作品形态、编写规范、跨文档引用、runtime resolver、source anchor、revision 颗粒度。

因此当前建议是：`.kmdwork` zip 结构应**为多脚本留扩展槽**，但 R3-D 不急着承诺多脚本播放语义。可讨论的内部结构方向：

```text
work.json
scripts/
  main.kmd
  chapters/chapter-01.kmd
assets/
```

其中 `work.json` 可只声明 entry script（如 `scripts/main.kmd`）和未来可选的 script graph/import map。R3-D MVP 可以只消费单 entry script；后续讨论已倾向让 runtime/host 直接消费受控的多脚本图，而不是把多脚本强行 flatten 成单脚本再反向还原。但完整语义仍不能在 R3-D 中抢先定稿。

---

## 5. 与现有架构的缝合点（避免设计成孤岛）

`.kmdwork` 若要做，有四个既有接口要对齐，否则会变成第二套事实源。

### 5.1 `WorkAssetManifest` / `ReaderRuntimeAssetManifest`

`.kmdwork` 的 asset 部分应**直接复用**这个类型，而不是另造。核实：`ReaderRuntimeContract.ts:82-84` 已支持 `type: font|image|shader|audio|video|data` + `integrity` + `metadata`，够用。

### 5.2 `Work.presentation` 派生

`work-kmd-content-model.md` 已明确 presentation 应从 source/frontmatter **派生**而非作者手写。2026-06-25 清账后，这一点细化为：`.kmd` 脚本内不应存 `Work.presentation`；`.kmdwork` / Work manifest 可以保存 presentation 派生投影或导出时快照，用于离线列表、书架、预览和筛选。导入时如快照与 entry `.kmd` 的播放事实冲突，应走警告/重新生成等导入策略，而不是让快照反向覆盖 source。

### 5.3 `KmdScriptRevision` 与本地作品的 revisionId

导入的本地作品没有云端 revisionId。早期问题曾是 `KmdScriptRef` 在本地作品上应使用 `revisionId = "local"`，还是用文件 `contentHash` 当 revisionId。2026-06-25 清账后，这个二选一已经过期：`.kmdwork` 可能携带 revision manifest，本地 revision identity 应在提交/manifest/origin 模型中定义，而不是只给单个导入 source 造一个临时字符串。**这仍影响 R3-E**（本地轻度更改基于哪个 `baseRevisionId`），但问题层级已经上移。

规划者补充了更大的导出/下载场景：`.kmdwork` 不只是"单脚本单一版本的打包"，也可能来自：

- community-api 的"下载为本地"；
- editor 的"导出为本地文件"；
- 离线协作和版本管理场景中的作品交换。

这些场景中的文件可能携带不同 revision，并且应尽量与某一发布源同步。因此下一轮不再追问"本地作品 revisionId 怎么造一个字符串"，而应进入 revision 模型设计：

- `.kmdwork` 阶段性倾向携带 revision manifest；
- revision manifest 倾向记录多个 committed revision + 一个 export/upload snapshot；
- authoring snapshot 不属于 `.kmdwork` 的职责，应留给 editor 工作区；
- `local_revisions` 当前 outbox 语义与未来可携带 revision 历史存在冲突，需要重新解释为"本地提交/同步状态"的最小实现，或在 R3 后拆出正式 revision store；
- contentHash / origin / remote mapping / localWorkId 的组合仍待定义。

### 5.4 `LocalLibraryEntry.kmdSource` 容量

现在这个字段就是"全文"语义（`r3-local-reader-plan.md` §2.5）。`.kmdwork`（尤其是带 assets / 多脚本 / revision 的）放不进单个 String 字段。2026-06-25 清账后，倾向是：`LocalLibraryEntry` 继续做轻量用户关系快照，Room 只存轻量索引；长期 source 应进入应用私有 bundle store，播放时再展开/映射到 runtime cache。裸 `.kmd` 或无资产作品可以继续把 `kmdSource` 当作 MVP 快捷路径。

---

## 6. 与既有规划的落点关系

讨论收敛后，建议这样落到文档（落点提议，非定论）：

| 文档 | 作用 | 位置 |
|---|---|---|
| `frontmatter-schema.md`（新建） | 定义 frontmatter 的功能边界、字段效力和 schema，解决 §3 drift 背后的规范缺口。是任何打包格式的前置 | `docs/knowledge/language/`（主仓库） |
| `work-bundle-format.md`（新建） | 定义 `.kmdwork` 结构、与 assetManifest/presentation 派生的关系。扩展 work-kmd-content-model | `docs/knowledge/architecture/`（主仓库） |
| `r3-local-reader-plan.md` §R3-D（扩展） | 从当前 3 行扩展，拆成 R3-D0（frontmatter 收敛，前置）+ R3-D1（打包格式）+ R3-D2（SAF 导入 + 书架专列） | 本树 |

### 6.1 已确认的接线约束（来自架构决策，非讨论）

导入作品只走 `localLibrary.getShelf()`，不污染 `state.works`。这条已定。由此带来的实现约束：R3-F 书架 UI 需要单独配一个 `LocalLibraryEntry → Work` 的卡片映射，因为 `OpenWork` reducer 要 `Work`，不能直接吃 entry。这点要在 R3-F 实现时处理。

### 6.2 现有 Work 模型全量盘点（2026-06-22 追加）

这一轮盘点发现，"Work 模型"并不是一个单点模型，而是至少五个投影叠在一起：

| 位置 | 当前形态 | 主要字段 | 语义 |
|---|---|---|---|
| 主仓库 `work-kmd-content-model.md` | 生态概念模型 | `Work` / `KmdScriptRevision` / `KmdRevisionSource` / `Work.presentation` | 长期权威：Work 是平台作品实体，source/revision 独立，presentation 从 revision 派生 |
| community-api `Work` | API/mock seed 模型 | title / authorName / tags / presentationMode / lifecycle / script.revisions / stats / commentSummary | 远程社区列表、详情、source 路由；当前仍由 seed 手写 |
| Android domain `Work` | App domain 模型 | Work metadata + `KmdScriptRef` + `assetManifest` + attributes + comments | 详情/阅读页所需的聚合 DTO；不等于长期生态权威模型 |
| Android Room `works` | 远程作品缓存 | 几乎展开存储 Android domain `Work` | 离线缓存远程社区作品；包含 active revision 快照 |
| Android `LocalLibraryEntry` | 用户-作品关系快照 | workId/source/onShelf/title/author/presentationMode/aspectRatio/kmdSource/contentUri/progress | 书架、历史、导入状态；当前不保存 activeRevisionId / revision 列表 / assetManifest |

由此得到一个重要结论：**"唯一事实来源"不能理解为只保留一个 Work 对象**。更稳的表达是：每类事实只有一个权威归属，其它模型只能缓存/派生/快照。

初步归属建议（仍待讨论）：

| 事实 | 权威归属 | 可缓存/投影位置 | 当前风险 |
|---|---|---|---|
| 作品平台身份、社区状态、评论摘要、排行 | `Work` / community-api | Android `works`、书架快照 | 本地导入没有这些事实，需要 fallback 或留空 |
| 脚本文本与播放语义 | `.kmd` revision source | `LocalLibraryEntry.kmdSource`、bundle cache、runtime `source` | `kmdSource` 只能装纯文本，无法装带 assets 的 bundle |
| revision 身份、hash、父子关系 | `KmdScriptRevision` / revision manifest | Android `works.activeRevisionId`、未来 `.kmdwork` manifest | `LocalLibraryEntry` 当前丢失 revision 身份 |
| presentation/index 字段 | 从 `.kmd` frontmatter/source 派生 | `Work.presentation`、Android list/card snapshot | 当前 mock/API/Android 多处手写，可能和 source 冲突 |
| 用户本地关系与进度 | `LocalLibraryEntry` | 仅本机 | 不应回流成 Work 事实 |
| asset 路由 | `assetManifest` / bundle manifest | runtime `assetManifest` | Android local_library 当前没有 assetManifest 存储位 |

### 6.3 frontmatter 实际读写盘点

frontmatter 现在不是一个规范 schema，而是几个实现各取所需：

| 字段/结构 | editor parser | editor UI 写回 | Web runtime | Android metadata parser | community seed/source |
|---|---|---|---|---|---|
| `title` | 读，进入 metadata | 不保留/不写回 | 用作 fallback workId | 不读 | `.kmd` 样本使用；API Work 另有 title |
| `author` | 类型中有 | 不保留/不写回 | 无显式使用 | 不读 | 样本未见 |
| `mode` | `stage/scroll/page` | 写 `stage/scroll/page` | 控制 stage fixed ratio；`page` 另有分页清屏语义 | 读 `scroll/page/paged/stage/interactive` | API 为 `scroll/paged/stage/interactive`，样本多为 `stage` |
| `designWidth/designHeight` | 读 | 写 | stage design viewport | 读，仅 stage-like 时使用 | 样本使用 |
| `speed` | 读 | 不保留/不写回 | 影响文字 reveal speed | 不读 | 样本使用 |
| `var:` | 专门解析为 `metadata.variables` | 不保留/不写回 | 写入 `layout.globalMarkers`，供 `var.*` 引用 | 不读 | 样本使用 |
| `bgColor/fontColor/fontFamily` | 宽松读为 unknown metadata，但类型不声明 | 写 | typography/background 由 editor config/runtime settings 使用 | 不读 | 样本未见 |
| `kmdVersion` | 宽松读为 unknown metadata，类型不声明 | 不保留/不写回 | 无显式使用 | 不读 | 文档示例有；revision/API 另有 kmdVersion |

新发现的更严重问题：editor UI 的 frontmatter 写回会用固定 6 个字段整体替换已有 frontmatter。也就是说，作者一旦在 Inspector 改 mode/尺寸/字体，就可能丢失 `title`、`speed`、`var:`、`kmdVersion` 等 runtime/source 字段。这比"字段未对齐"更危险，因为它会破坏源文件往返。

这说明 `frontmatter-schema.md` 不能只列字段，还要定义：

- 字段的效力层级：source truth / derived input / host preference / editor-only。
- editor 写回策略：未知字段、嵌套字段、注释、顺序是否必须保留。
- 哪些字段可以被 UI 控件覆盖，哪些只能由 parser/author/source 管理。

### 6.4 `mode`、presentation mode 与 capability 被折叠在一起

目前至少有三层 mode 被同名/近名字段混在一起：

| 层 | 当前枚举/值 | 作用 |
|---|---|---|
| `.kmd` frontmatter / editor runtime | `stage/scroll/page` | runtime layout/presentation 行为 |
| community-api / Android domain | `scroll/paged/stage/interactive` | 列表筛选、卡片、详情、viewport 策略 |
| Android → Web runtime settings | `scroll/page/stage` | 实际传给 Web runtime 的 presentation mode |

Android `ReaderViewportPolicy` 现在把 `Interactive` 视为 stage-like：viewport/letterbox 走 stage 逻辑，最终传给 Web runtime 的仍是 `"stage"`。这意味着 `interactive` 目前不是 Web runtime 的真实 presentation mode，更像 Work/UI 层的能力或作品形态标签。

这与多态性草案的开放问题对齐：`mode` 与能力集的映射是否显式化？如果不拆开，未来 `subtitle/teleprompter/video/interactive` 会继续挤压同一个枚举。

仍保留的开放问题（未过期）：

- frontmatter 的 `mode: interactive` 是合法源字段吗？如果合法，runtime 不支持 interactive branch 时应如何降级？
- `interactive` 应改为 `capabilities: [interactive]`，还是保留为 mode？
- `paged` 是否应成为 canonical，而 `page` 仅作为 runtime/internal alias？
- `stage` 是否只表示 fixed-ratio presentation，而镜头/电影感能力另由 `capabilities: [cinematics]` 表达？

### 6.5 revision 与本地库之间的缺口

当前 Android `Work` / Room `works` 保存 active revision：`activeRevisionId`、script sourceUrl、mime、kmdVersion、runtimeVersion、contentHash。但 `LocalLibraryEntry` 只保存 `workId`、title/author/presentation 快照、`kmdSource` 和 `contentUri`，没有：

- activeRevisionId；
- contentHash；
- revision list；
- origin work/revision；
- assetManifest；
- source mime/kmd/runtime version。

首次阅读远程作品时，ViewModel 会从 `Work` 创建 `LocalLibraryEntry`，但这个转换也丢弃 revision 身份，只保留 contentUri。对 R3 的"书架/历史/进度"足够；对 `.kmdwork` 的"下载为本地"、离线协作、版本管理不够。

2026-06-25 清账后，倾向是方向 1：`LocalLibraryEntry` 继续只做用户关系快照，revision/bundle 信息另放应用私有 bundle store 或未来 revision store。若未来为了索引效率给 `LocalLibraryEntry` 增加 activeRevisionId/contentHash/assetManifestRef/origin，也应把它们视为**指针或快照**，不能把 `LocalLibraryEntry` 扩成第二个 `Work`。

### 6.6 `.kmdwork` 与 runtime 热路径的边界

Android/Web runtime 协议已经比较明确：`loadScript` 不吃 zip。它吃的是：

```text
work + source/sourceUrl/assetManifest + settings
```

因此 `.kmdwork` 的定位应是**宿主导入/导出容器**，不是 runtime 热路径格式。Android 导入 zip 后必须展开或索引为：

- entry `.kmd` source；
- 受控的 asset baseUrl / assetManifest；
- 可构造 `Work` 或 `LocalLibraryEntry` 的 metadata；
- 可选 revision/origin manifest。

2026-06-25 清账后，其中两项已经阶段性收束：Android 不应把外部原始 `.kmdwork` 当作长期唯一副本；展开目录倾向作为可重建的 import/runtime cache。仍待技术调研的是 Room / 私有 bundle store / cache 的分层，以及 assetManifest URL 如何从 bundle 内相对路径映射到 WebView 可安全访问的播放路径。

### 6.7 community-api 的 summary/detail/source 形状对导出有影响

community-api 当前：

- list/summary DTO 只暴露 active script 的轻量 ref；
- detail DTO 才暴露完整 `script.revisions`、assetManifest、stats、commentSummary；
- source API 分 `GET /works/:id/source` 和 `GET /works/:id/revisions/:revisionId/source`；
- seed 中每个 work 当前只有 `rev-1`，且 presentation/stats 都是手写。

如果未来做"下载为本地 `.kmdwork`"，需要决定下载入口：

- 从 summary 下载：只能得到 active revision 的 sourceUrl 和少量 Work 信息；
- 从 detail 下载：可以拿到 revision 列表和 assetManifest，但仍未拿到所有 revision source；
- 从 revisions/source 下载：需要多次请求才能构造多 revision bundle。

这说明 `.kmdwork` export/download 不是单个前端按钮问题，它需要 community-api 定义明确的 bundle export 语义，或者客户端自己根据 detail + revision source 组装。

### 6.8 2026-06-25 总体讨论补充：阶段性判断

本节记录 2026-06-25 进一步总体讨论。它比 §7 更靠近"未来应当如何设计"，但仍是讨论材料，不是最终格式规范。

#### `.kmdwork` 的内容边界

阶段性判断：

- `.kmdwork` 至少应包含 entry `.kmd` 正文、frontmatter、外部资产和 asset manifest。
- `presentation` 投影为了离线列表、书架、预览和筛选功能有实际价值，可以进入 bundle manifest；但它应被视为**派生投影或导出时快照**，不是 `.kmd` 源文件内部的作者事实，也不应反向覆盖 `.kmd` 的播放/渲染事实。
- 社区平台状态不应作为本地权威存储。review status、commentSummary、ranking、lifecycle 等应从云端获取；离线 bundle 最多保存导出时的只读快照，并明确它可能过期。
- revision 信息因为"本地协作 / 本地版本控制 / 下载为本地 / editor 导出"场景变得重要，`.kmdwork` 应考虑携带 revision manifest 和 origin 信息。

这会把 `.kmdwork` 推向三层结构：

```text
bundle container (.kmdwork zip)
  -> source layer: scripts + assets
  -> metadata layer: work/bundle manifest + presentation snapshot + assetManifest
  -> history layer: revision manifest + origin + export/upload snapshot
```

#### Android 是否保存原始 `.kmdwork`

阶段性判断：Android Reader 不应把用户选择的原始 `.kmdwork` 当作长期唯一副本。导入时应由应用自己的打包/解包器接管，因为未来手机端 editor 也需要打包器，迟早要写。

含义：

- 不依赖外部 SAF Uri 的长期可读性来播放作品。
- 导入后可以保存应用私有格式或展开后的内部表示。
- 原始 zip 是否额外保留为"导入原件备份"，仍可讨论，但不应是播放链路唯一 source store。

#### 展开目录是 import cache 还是长期 store

阶段性判断：倾向 import cache。原因是移动端一次通常只加载/播放一个作品，cache 对内存和磁盘占用更友好。

仍需补充判断：如果不保留原始 zip，而展开目录又只是可重建 cache，那么必须有另一个长期 source store。可能是：

- 应用私有 bundle store：保存规范化后的 `.kmdwork` 或 bundle manifest + blobs；
- Room 只存轻量索引，不存大文本/大资产；
- 当前正在播放的作品才展开到 runtime cache。

#### Room/Uri/source 分层仍需技术调研

规划者暂时无法判断：

- Room 中存 zip Uri、展开目录 Uri、entry source 文本，还是三者分层；
- assetManifest 中的 URL 是 zip 内相对路径、展开后虚拟 HTTPS 路径，还是 Android content resolver 路径。

当前技术事实：

- Android/Web runtime 热路径不吃 zip，只吃 `work + source/sourceUrl/assetManifest + settings`。
- Android 当前 `ReaderLoadRequest` 已支持 `source`、`sourceUrl`、`assetManifest`。
- 真实 SAF/zip/import cache 链路尚未实现，`ImportDesk` 仍是模拟导入。

待补知识：

| 方案 | 优点 | 风险/代价 | 可能定位 |
|---|---|---|---|
| Room 存 entry source 文本 | 简单，纯 `.kmd` 快速可读 | 不适合多脚本和大资产；DB 膨胀 | 裸 `.kmd` MVP 或无资产作品 |
| Room 存 zip/content Uri | 小而简单 | SAF 权限、外部文件变动、长期可访问性不稳定 | 仅保存导入来源/调试信息 |
| 应用私有 bundle store | 可控、离线稳定、适合 assets/revisions | 要实现打包/解包、清理、迁移 | `.kmdwork` 正式导入的长期归宿 |
| 展开目录 cache | runtime 访问方便，可映射虚拟 URL | 需重建和清理；不能单独作为权威 | 当前播放/预览缓存 |
| asset URL 用 zip 内相对路径 | manifest 可跨平台、可导出 | runtime 不能直接读 zip | bundle manifest 内部引用 |
| asset URL 用展开后虚拟 HTTPS | WebView runtime 安全、和现有 assets host 对齐 | Android 需维护 request 拦截映射 | 播放热路径 |
| asset URL 用 content resolver Uri | Android 原生可访问 | WebView/JS 不宜直接自由 fetch | 导入层，不进 runtime manifest |

### 6.9 2026-06-25 总体讨论补充：frontmatter 方向

阶段性判断：KMD 不应只被定义为"作品"。它也可以是提词器、快速动效字幕、视频特效字素材，或未来更多输出形态。因此 frontmatter 不应被单一作品用途锁死；它应优先描述**如何渲染和播放，以及画布上可见内容所需的最低事实**。

这意味着：

- frontmatter 的必填字段应尽量少。
- 必填字段应只服务 runtime/render/playback 的最低需求。
- 不涉及具体播放行为的作品元信息，应尽可能托管到外部 manifest、Work 或 editor/project 配置。
- 作者字段、作品字段、社区字段、editor 工作区字段暂时不宜强行塞进统一 frontmatter schema。

对 §3.3 问题的阶段性回答：

| 问题 | 当前回答 |
|---|---|
| frontmatter 是语言事实、作品发布事实、editor 偏好，还是 runtime hint？ | 都可能参与，但不应混为同一层。更准确地说：frontmatter 应优先是 `.kmd` 播放/渲染事实；作品发布和 editor 偏好应能外置。 |
| 哪些字段可以成为 `.kmd` 随身携带的作者事实？ | 尚不能确定。KMD 还缺少有规模作品实践，作者字段需要边开发、边创作、边验证。 |
| 哪些字段应属于 `Work.presentation` 派生结果，而不是作者手写？ | `Work.presentation` 更像"作品"元信息，不应直接纳入 `.kmd` 脚本本体；它可以由 `.kmd` 播放 hint 派生，也可以作为 `.kmdwork`/Work manifest 的导出快照。 |
| 哪些字段只是 editor 工作区偏好？ | 目前无法确定。可能应由 editor 配置、project 配置或 `.editor` 类文件承载；是否随作品分享由作者/项目决定。 |
| `mode` 是否仍是单纯呈现模式？ | `mode` 越来越像过渡字段。若定义为"播放模式"，它会限制未来创意；若定义为"交互/形态模式"，又会和 capability 纠缠。需要重新命名或拆层。 |

仍开放的问题（同步到 §7.3）：

- frontmatter 是否需要分区，例如 `runtime:`、`canvas:`、`authoring:`，还是继续保持扁平但只保留 runtime 字段？
- `mode` 是否应降级为 legacy alias，而由 `profile` / `intent` / `capabilities` / `playback` 等新字段承接未来语义？
- editor 工作区偏好是否应进入 `.kmdwork`，但不进入 `.kmd`？
- bundle manifest 中的 `presentation` 与 entry `.kmd` frontmatter 冲突时，导入器应报错、警告、还是重新生成？

### 6.10 2026-06-25 总体讨论补充：revision 方向

阶段性判断：revision 的核心作用是版本控制与协作，可能更接近"提交"模型，而不是 R3 当前的"待同步临时 outbox"模型。

对 §7.4 问题的阶段性回答：

| 问题 | 当前回答 |
|---|---|
| `.kmdwork` 是否携带 revision manifest？ | 应该携带。没有清单就无法列举和比较不同版本。 |
| revision manifest 中的 revision 是什么？ | 倾向多个 committed revision + 一个 export/upload snapshot。如果导出/上传快照与最新提交不同步，需要显式记录。 |
| authoring snapshot 是否属于 `.kmdwork`？ | 不应由 `.kmdwork` 负责。创作过程快照是 editor 的工作。 |
| 本地导入后哪些 revision 进入 LocalLibrary/local_revisions/cache？ | 用户书架始终看到同一作品的最新可播放版本。revision 历史应由 reader/editor 的提交历史、diff、审阅功能消费。 |
| 是否还需要 R3 `local_revisions` outbox？ | 若采用提交模型，"本地领先的提交"和"待同步 outbox"应尽量统一为同一模型，而不是两套 revision。 |
| 本地 revision 与 cloud revision 如何关联？ | 云端应完整记录作品提交历史。workId 主要在云端管理中有意义；本地关联可能更依赖 contentHash / origin / remote mapping。 |

这与 R3 现有规划有冲突：`r3-local-reader-plan.md` §2.7 已明确 `local_revisions` 是待同步缓冲，不是永久版本库。现在的新判断意味着后续可能需要把 R3 的 local_revisions 重新解释为"本地提交/同步状态"的一种最小实现，或在 R3 后拆出正式 revision store。

仍开放的问题（同步到 §7.3）：

- commit revision 是否必须包含完整 source snapshot，还是 source snapshot + diff 都需要？
- contentHash 能否作为本地 revision identity？如果两个作品内容相同但来源不同，是否需要 bundle/work scope 一起参与 identity？
- "export snapshot" 与 "committed revision" 不同步时，播放默认读哪个？
- 云端 workId 不存在时，本地作品 identity 如何生成？是 bundleId、entry contentHash、还是随机 localWorkId？
- 如果云端下载为本地，是否应保存 remote origin mapping，但不把 remote workId 当本地唯一身份？

### 6.11 2026-06-25 总体讨论补充：插件生态与多脚本

阶段性判断：插件生态会让编写端需要多脚本协同工作；最终产物是否组装成单脚本，不能只从 reader 简化角度判断。

当前倾向：直接消费多脚本更合适。理由：

- 审阅者和作者需要在播放/解析端修改脚本；
- 如果产出 work 时打成单脚本，展开时又要还原成多脚本视图，source anchor、diff、审阅定位都会复杂化；
- 当前已有播放实时互动链路，额外的 flatten/unflatten 映射会增加很多脆弱性。

仍需保留的限制：

- 多脚本实现完全未开始，不能现在定义完整语义。
- 每个 `.kmd` 是否各自有 frontmatter，以及 entry/frontmatter 局部覆盖规则，暂时无法确定。
- runtime 不应在播放热路径自由读取任意相对路径；多脚本必须通过 bundle manifest/import map/resolver 受控解析。

仍开放的问题（同步到 §7.3）：

- 插件代码、宏库、章节脚本、资源脚本是否都叫 "script"，还是需要 module 类型？
- 多脚本 revision 是 whole-bundle commit，还是 per-file commit？
- source anchor 是否必须包含 `documentId/path + range`？
- 多脚本 runtime 是否一次性加载 dependency graph，还是允许懒加载？移动端离线场景下懒加载如何保证完整性？

### 6.12 2026-07-07 决策补充：export snapshot、localWorkId、R3 规划修订

三项决策（规划者拍板，非讨论倾向）：

1. **export snapshot ≡ 最新提交**。导出 `.kmdwork` 时，若工作区存在未提交更改，editor 应尝试自动提交并提醒作者——这是 editor 侧规范，随 frontmatter/bundle 规范一起落盘。由此 manifest 中的 export snapshot 退化为"指向某个 committed revision 的指针"，不需要独立 snapshot blob；§6.10"export snapshot 与最新 committed revision 不同步时默认读哪个"的问题随之消解（两者恒同步）。
2. **本地作品身份采用独立 UUID（bundleId），不用负数计数**。理由：`.kmdwork` 会在设备间流转，负数计数本质是本机行计数器，两台设备各自的 `-1` 指向不同作品，导出/导入后身份即断裂。bundleId 在作品创建/首次导出时生成并写入 `work.json`，跨设备稳定；云端 workId 只作为 origin mapping 附带，不作本地身份；Room 自增行 ID 仅作本机索引；contentHash 只做内容寻址/去重，不作身份（内容相同、来源不同的两个作品不能撞身份）。
3. **R3 规划即时修订，不留迁移债**。`local_revisions` 不再按"待同步 outbox"实现，而是从一开始按提交模型建 schema（`parentRevisionId` / `contentHash` / `message` / `syncState` / `remoteRevisionId`，提交不可变）。详见 [`r3-local-reader-plan.md`](r3-local-reader-plan.md) §2.7（2026-07-07 修订版）；R3-A 已按旧 schema 建的实体在 R3-E 前完成迁移。

---

## 7. 问题状态清账（2026-06-25，2026-07-07 增补）

本节替代早期"下一轮问题"清单。目标不是把所有问题都定稿，而是把**已经阶段性收束的问题**、**已经被更高层模型替代的问题**和**仍然开放的问题**分开，避免 R3-D 实现继续围着过期二选一打转。

### 7.1 已阶段性收束

| 议题 | 当前状态 |
|---|---|
| `.kmdwork` 外部形态 | 选择 zip 单文件容器。目录结构/manifest 字段未定，但"一个可分享文件"这个外部形态已收束。 |
| `.kmdwork` 是否是 runtime 格式 | 不是。runtime 热路径仍消费 `work + source/sourceUrl/assetManifest + settings`；`.kmdwork` 是宿主导入/导出容器。 |
| Android 是否依赖原始 `.kmdwork` 文件 | 不应把用户选择的外部原件当作长期唯一副本。导入后应进入 app 可控的私有 bundle/store/cache 链路。 |
| 展开目录定位 | 倾向作为 import/runtime cache，而不是长期权威 store。长期 source 需要应用私有 bundle store 或等价机制承载。 |
| `.kmd` 是否承载 `Work.presentation` | 不应在 `.kmd` 脚本本体中承载 `Work.presentation`。bundle/Work manifest 可保存派生投影或导出快照。 |
| 社区状态是否进入本地权威 | 不进入。review status、commentSummary、ranking、lifecycle 等从云端取权威；bundle 仅可保存导出时只读快照。 |
| `.kmdwork` 是否需要 revision manifest | 倾向需要。没有 manifest 就无法列举、比较、同步或携带历史。 |
| authoring snapshot 是否属于 `.kmdwork` | 不属于。创作过程快照应归 editor/project 工作区；`.kmdwork` 关注可交换、可播放、可同步的 revision。 |
| `LocalLibraryEntry` 的角色 | 继续倾向为用户-作品关系快照，不作为 Work/revision/bundle 的唯一事实源。 |
| 是否让代码 agent 拍板概念边界 | 不适合。概念边界由规划文档收束；agent 可做字段盘点、代码入口枚举、安全策略调查等机械调研。 |

### 7.2 已被替代的问题

| 早期问题 | 替代后的问题 |
|---|---|
| "先修 frontmatter drift 吗？" | 先定义 frontmatter 的功能、效力、应用范围和写回规则，再决定字段对齐。 |
| "`revisionId = local` 还是 `contentHash`？" | 进入 commit-like revision manifest 设计：revision identity 需结合 contentHash、bundle/work scope、origin/remote mapping 和本地 identity。 |
| "保存原 zip 还是展开目录？" | 改成长期 store / 导入来源 / runtime cache 三层分工。原 zip 可作为备份或来源记录，但不能是唯一播放 source。 |
| "Work 模型是否做唯一事实来源？" | 改成每类事实各有权威归属：Work 管平台身份，source 管播放语义，revision manifest 管版本，LocalLibraryEntry 管本机关系。 |
| "多脚本最后是否 flatten 成单脚本？" | 改成多脚本 graph/resolver/source anchor 语义设计。当前倾向直接消费受控多脚本图，flatten 不再作为默认方向。 |
| "presentation 是否完全不进 `.kmdwork`？" | `.kmd` 不存 Work presentation；`.kmdwork` manifest 可以存派生投影/导出快照，并定义冲突处理。 |
| "`local_revisions` 只是 outbox 还是历史？" | 改成 R3 outbox 与未来 commit store 的关系问题：可以重新解释为本地提交/同步状态的最小实现，也可后续拆正式 revision store。 |

### 7.3 仍开放的概念问题

frontmatter：

- frontmatter 是否保持扁平 schema，还是分出 `runtime` / `canvas` / `authoring` 等区域。
- `mode` 是否降级为 legacy alias，由 `profile` / `intent` / `capabilities` / `playback` 等字段承接未来语义。
- `bgColor/fontColor/fontFamily` 是作品视觉事实、默认主题建议，还是 editor preview 偏好。
- `designWidth/designHeight` 只对 stage-like/cinematic 输出有效，还是对所有固定画布输出有效。
- editor 写回是否必须保留未知字段、嵌套字段、注释和顺序；若必须，是否需要共享 parser/serializer。

bundle manifest：

- 内部 manifest 命名、版本字段、entry script 字段、多个 entry 的表达方式。
- `.kmdwork` manifest 与 community-api detail DTO 是同构、相似投影，还是完全独立的离线 bundle manifest。
- `presentation` 快照与 entry `.kmd` frontmatter 冲突时，导入器应报错、警告、重新生成，还是保留双份并标记来源。
- 导入后可播放与导入后可编辑/重新打包是否需要不同展开级别。

revision/export（2026-07-07 增补状态）：

- committed revision 保存完整 source snapshot、diff，还是两者都保存——**仍开放**。R3 先按全量 snapshot 实现（`r3-local-reader-plan.md` §2.7），diff 模型待典型作品规模估算后再定。
- ~~export/upload snapshot 与最新 committed revision 不一致时，reader/editor 默认打开哪个~~——**已收束**：导出时自动提交，export snapshot ≡ 最新提交，两者恒同步（§6.12）。
- ~~本地作品 identity 使用 bundleId、随机 localWorkId、entry contentHash，还是组合身份~~——**已收束**：独立 UUID bundleId 写入 `work.json`；contentHash 只做内容寻址/去重，不作身份（§6.12）。
- 云端下载为本地时 remote origin mapping 的具体字段——**方向已定**（origin mapping 附带、remote workId 不作本地身份），字段形状留给 `work-bundle-format.md`。
- community-api 是否需要 server-side bundle export endpoint，还是客户端自行拉 detail + revision source 组装 `.kmdwork`——**仍开放**。

多脚本/插件：

- 多脚本中的每个 `.kmd` 是否各自有 frontmatter；哪些字段只能出现在 entry script，哪些可局部覆盖。
- 插件代码、宏库、章节脚本、资源脚本是否都叫 script，还是需要 module kind。
- 多脚本 revision 是 whole-bundle commit，还是 per-file commit。
- source anchor 是否统一使用 `documentId/path + range/time/segment`。
- 多脚本 runtime 一次性加载 dependency graph，还是允许懒加载；移动端离线场景下如何保证完整性。

### 7.4 仍需补齐的技术信息

以下信息会直接影响 R3-D 实现方案，但当前仓库还没有足够实现事实：

1. Android 私有存储策略：`filesDir` / `cacheDir` / Room / WebViewAssetLoader 或自定义拦截器如何配合。需要明确长期 store、导入来源记录与播放 cache 的边界。
2. SAF 权限策略：导入后是否复制内容到 app 私有目录，是否调用 persistable Uri permission，用户删除原文件时应用应如何表现。
3. WebView asset 映射：展开后的 assets 如何变成 runtime 可安全读取的 `sourceUrl` / `assetManifest.baseUrl`。
4. Zip 安全策略：zip slip、防超大文件、manifest 校验、hash 校验、重复路径和编码问题。
5. 打包器职责：Android reader、手机端 editor、Web editor 是否共享 `.kmdwork` pack/unpack 规则；规则是否应先以 TypeScript/Kotlin 双实现，还是先文档规范。
6. Revision 存储规模：多 revision 是全量 snapshot 会很大；diff 模型又需要 patch 格式和恢复策略。需要估算典型作品大小后再定。
7. Room schema 迁移路径：`LocalLibraryEntry` 是否只增加 bundle/revision 指针，还是引入独立 bundle/revision 表。

---

## 8. 本文的边界

本文**不做**：
- 不把阶段性回答包装成最终规范——§7 已完成问题状态清账，但字段 schema、revision/export 语义和多脚本播放语义仍需后续文档收束。
- 不定义 `.kmdwork` 的最终格式——那是 `work-bundle-format.md` 的工作，且依赖 §7 的决策。
- 不修改 r3-local-reader-plan.md 的既定决策（§2.1 LocalLibraryEntry 模型、§2.3 SAF 流程、§6.1 书架专列约束）。

本文**要做的**是：把散落在会话里的现状勘察和方案对比，沉淀成规划者可查阅的形式，避免每次讨论都要重新推导一遍 §3 的 drift 矩阵。

# R3：本地阅读器能力 —— 实施规划

> 文档状态：规划草案
> 最近更新：2026-06-17
> 代号：R3
> 权威范围：本地数据模型、书架、导入、阅读进度持久化的统一设计

## 0. 背景：为什么 R3 是数据架构阶段

R2 尝试用独立 Room 表做阅读进度持久化，外键约束指向 `works.id`，但用户实际看到的 4 个作品走 `MockWorkRepository`，**从不写入 Room**，导致恢复进度时外键约束失败、崩溃。

根因不是代码 bug，而是**数据归属未定义**：
- 作品从哪来（mock / API / 本地导入）？
- 进度归属于哪个作品，那个作品存哪？
- 本地导入的 `.kmd` 源文本存哪（Room 当前无源文本字段）？
- 书架展示的是哪些作品？

这些问题必须统一设计，不能逐个功能零散实现。R3 就是回答这些问题的阶段。

## 1. 现状约束（2026-06-17 摸底）

| 约束 | 现状 | 对 R3 的影响 |
|---|---|---|
| 作品来源 | Mock（内存静态，不进 Room）/ Remote（API→Room 缓存）/ Local（枚举值已存在 `WorkSourceType.Local`，但无真实导入链路） | Local 链路是 R3 要建的核心 |
| Room migration | `fallbackToDestructiveMigration(dropAllTables=true)`，version 2 | 加 entity/version+1 会清空数据；R3 需引入显式 migration 或接受首版清空 |
| 外键陷阱 | `ScriptIssueEntity` 外键指向 `works.id`，mock 作品不在 Room → 任何外键指向 works 的表对 mock workId 写入都崩 | 进度/书架表**不能**用外键指向 works，除非确保 work 先入 Room |
| 源文本存储 | Room 无源文本字段；OfflineFirst 的 `getWorkSource` 直接走 API 不缓存；mock 源文本在 `MockKmdSources` 内存 object | 本地导入的 `.kmd` 源文本必须有持久化归宿（Room 或文件系统） |
| 导入占位 | `ImportDesk` 硬编码 `OpenWork("choice-room")`；`OpenImportPicker` effect 在 UI 层是空操作；无 SAF | R3 要实现真实文件选择 + 导入流程 |
| 元数据解析 | `KmdSourceMetadataParser` 只解析 mode/designWidth/designHeight，不解析 title/作者/标签 | 导入生成 Work 需要更完整的 frontmatter 解析器 |
| "最近阅读" | `MineDesk` 的 recentWorks 是 `state.works.take(2)`，假的 | R3 进度持久化后才有真实阅读历史 |
| contentUri | 非空 String，目前只是元数据（mock 伪路径 / API sourceUrl），runtime 靠源文本播放 | 本地导入需决定 SAF Uri 怎么存 |

## 2. 核心设计决策

### 2.1 本地作品数据模型：LocalLibraryEntry（书架 + 阅读历史分离）

`LocalLibraryEntry` 是本地作品的唯一数据载体，用 `onShelf` 字段区分**书架**（主动收藏/导入）和**阅读历史**（读过但未必收藏）：

```text
LocalLibraryEntry
  ├─ workId: String（主键）
  ├─ source: WorkSourceType（Local / Remote / Mock）
  ├─ onShelf: Boolean（是否在书架：主动收藏/导入 = true；仅读过 = false）
  ├─ title / authorName / presentation 摘要（从 frontmatter 或 API 元数据生成）
  ├─ kmdSource: String?（.kmd 源文本全文；本地导入必填，社区/mock 可选缓存）
  ├─ contentUri: String（本地导入的文件 Uri，或 API sourceUrl）
  ├─ readingProgress: Float（0..1，进度持久化字段）
  ├─ readingTimeMs: Long?
  ├─ readingDurationMs: Long?
  ├─ lastReadAt: Long?（最近阅读时间；null = 未读过，书架/历史排序用）
  ├─ importedAt: Long?（导入时间；仅 Local 来源有值）
  └─ cachedAt: Long?（远程作品缓存时间）
```

**书架 vs 阅读历史（同表不同视图）：**
- 书架视图：`SELECT * FROM local_library WHERE onShelf = 1 ORDER BY importedAt / lastReadAt`
- 阅读历史视图：`SELECT * FROM local_library WHERE lastReadAt IS NOT NULL ORDER BY lastReadAt DESC`
- 用户导入 `.kmd` → `onShelf=true, importedAt=now`
- 用户读了一个社区/mock 作品 → 自动创建/更新 entry（`onShelf=false, lastReadAt=now`）
- 用户主动"加入书架" → `onShelf=true`

**local_library 是稀疏表，只存"用户与之发生过关系的作品"：**
- **不是**遍历所有缓存社区作品筛 onShelf——用户没碰过的社区作品**不在** local_library 里，它们只在浏览页的 `WorkRepository.listWorks`（社区发现 + mock fallback）展示。
- entry 只在用户行为触发时创建：导入、加入书架、首次阅读。
- 因此书架查询（`onShelf=true`）天然稀疏，不会混入未触及的作品。
- 社区作品加入书架时，标题/作者/presentation 作为**快照**复制进 entry（离线可见）；浏览页的 `WorkRepository` 仍负责社区发现的实时数据。

**为什么是单表而非多表：**
- 进度、导入时间、缓存状态、书架标记都是"作品在本地是什么状态"的属性，一对一关系。
- 单表避免外键陷阱（不指向 `works`，自包含）。
- R2 崩溃证明：拆进度表会假设 work 在 Room，而 mock 作品不在——单表自包含绕过这个假设。

**与 WorkRepository 的关系：**
- `WorkRepository`（社区发现）保持不变：listWorks / getWork / listIssues / getWorkSource。
- 新增 `LocalLibraryRepository`：管理 LocalLibraryEntry 的 CRUD + 进度读写。
- 浏览页仍走 `WorkRepository`（社区发现 + mock fallback）。
- 书架/阅读历史走 `LocalLibraryRepository`。
- 用户从浏览/导入加入书架时，`LocalLibraryRepository` 创建 entry（社区作品存摘要 + contentUri，本地导入存 kmdSource 全文）。

### 2.2 进度持久化：作为 LocalLibraryEntry 字段

进度是 `LocalLibraryEntry.readingProgress / readingTimeMs / readingDurationMs / lastReadAt`。

- 节流写入：每 5 秒或进度变化 >2% 更新 entry。
- 恢复：进入阅读时查 entry，ready 后 seek。
- **前提**：作品必须在 LocalLibraryEntry 里（社区作品首次阅读时自动创建 entry，或手动加入书架）。
- 这天然解决了外键问题：进度不指向 works，而是 entry 自身的字段。

### 2.3 本地导入：SAF + frontmatter 解析

导入流程：
```
用户点「导入」→ SAF 文件选择器 → 读取 .kmd 文件文本
  → KmdSourceMetadataParser（扩展版，解析 title/作者/mode/design 尺寸）
  → 生成 LocalLibraryEntry（source=Local, kmdSource=全文, contentUri=SAF Uri）
  → 写入 Room
  → 加入书架，可打开详情和阅读
```

需要扩展 `KmdSourceMetadataParser` 或新建 `KmdImportParser`，解析完整 frontmatter（title / author / mode / designWidth / designHeight / speed / tags）。

### 2.4 本地数据模型全景：按"附着对象"分层

调研（2026-06-19）发现，围绕作品的本地数据可按**附着对象**分三层，而非按数据类型混合：

```text
┌─ 作品级 ──────────────────────────────────────────────┐
│  local_library                                         │
│    作品本身在本地是什么状态：进度、收藏、导入元数据     │
│    （一对一，主键 workId）                              │
├─ 内容级 ──────────────────────────────────────────────┤
│  local_revisions                                       │
│    本地轻度更改（待同步缓冲）：改过的 .kmd 源文本       │
│    作者纠错 / 审阅者评审，改完同步云端                  │
│    播放优先读取最新未同步版本                           │
│    （一对多，外键→local_library.workId）                │
├─ 社区协作级 ──────────────────────────────────────────┤
│  local_issue_overrides                                 │
│    对社区 issue 的本地操作（close/reopen/draft）        │
│    issue 是作品级实体（绑 workId，非行级、非 revision） │
│    location 是概念标签（如 "scene: crosswalk"），       │
│    不是源码行号——社区 API 不提供结构化 sourceRange     │
│    （一对多，外键→local_library.workId）                │
├─ 个人行级 ────────────────────────────────────────────┤
│  local_annotations（视精力）                           │
│    用户私有笔记/书签（附着在源码行或时间点）            │
│    与 issue 分表：笔记是个人私有，issue 是社区协作       │
│    （一对多，外键→local_library.workId）                │
├─ 全局级 ──────────────────────────────────────────────┤
│  reader_preferences（未来，DataStore）                  │
│    不绑定具体作品的偏好：fontScale、reducedMotion、     │
│    默认方向、chrome pinned 策略                         │
└────────────────────────────────────────────────────────┘
```

**为什么按附着对象分层而非数据类型混合：**
- issue 和笔记都"可能"涉及源码位置，但语义不同：issue 是社区协作审阅（绑 workId，location 是概念标签），笔记是个人私有记录（附着在具体行/时间点）。社区 API 的 issue **没有结构化 sourceRange**，location 是自由文本标签（如 `"scene: crosswalk"`），Android 只能启发式猜行号——issue 本质是作品级/社区协作级实体，不是行级附属。
- 阅读进度是作品级标量，不是行级实体，放 local_library 字段即可。
- 偏好不绑定作品（全局级），适合 DataStore 而非 Room。

**与 community-api 模型的已知差距（2026-06-19 对齐调研）：**
- issue 的 `location` 在 API 端是概念标签，不是行号；Android 的 `KmdSourceSnapshot.lineNumberForIssue()` 启发式猜行号大部分会失败。本地 issue override 不应假设有可靠行号。
- `IssueSource` 枚举不对齐（API 5 值 / Android 8 值，`syntax≠Parser`）；`Layout/Effect/Asset` 来源经 API 往返会丢失。
- issue 无 `revisionId`、无 `status`、无 `createdAt`（设计文档规划但 API 未实现）。本地 override 的 close/reopen 是纯客户端状态，API 暂无对应端点（`POST /issues/:id/actions` 未实现）。
- 这些差距是 community-api 与 reader 之间的契约问题，需在 R4（社区能力）统一对齐，R3 只负责本地 override 持久化，不修 API 契约。

### 2.5 local_library（作品级，R3 核心）

```text
LocalLibraryEntry
  ├─ workId: String（主键）
  ├─ source: WorkSourceType（Local / Remote / Mock）
  ├─ onShelf: Boolean（书架 vs 阅读历史）
  ├─ title / authorName / presentation 摘要（快照）
  ├─ kmdSource: String?（本地导入的源文本全文）
  ├─ contentUri: String
  ├─ readingProgress: Float（0..1）
  ├─ readingTimeMs: Long?
  ├─ readingDurationMs: Long?
  ├─ lastReadAt: Long?
  ├─ importedAt: Long?
  └─ cachedAt: Long?
```

自包含、无外键指向 `works`，避免 mock 作品不在 Room 的崩溃陷阱。

### 2.6 local_issue_overrides（行级，R3）

```text
LocalIssueOverride
  ├─ id: String（主键，"{workId}:{issueId}"）
  ├─ workId: String（外键 → local_library.workId，CASCADE）
  ├─ issueId: String
  ├─ status: String（Open / Closed）
  ├─ reason: String?
  ├─ draftMessage: String?
  ├─ draftSuggestion: String?
  └─ updatedAt: Long
```

issue 台账本身（id/workId/severity/source/location/message/suggestion）是社区事实，走 WorkRepository（API + script_issues 缓存）。本地只存"用户的操作覆盖"。

**注意（2026-06-19 对齐调研）**：issue 不是行级实体。社区 API 的 `location` 是概念标签（如 `"scene: crosswalk"`、`"whole script"`），不是源码行号。issue 绑在 `workId` 上，没有 `revisionId`、没有 `status`、没有结构化 `sourceRange`。Android 的 `KmdSourceSnapshot.lineNumberForIssue()` 只能启发式猜行号，大部分概念标签猜不出。因此：
- 本地 override 不依赖行号，只依赖 issueId + workId。
- UI 展示 issue 时，location 无法可靠映射到源码行的，直接显示原始标签文本（现有 `ReaderCompanionContainer` 已有此 fallback）。
- issue 的 `status`（open/closed/resolved）在 API 端尚未实现（`POST /issues/:id/actions` 未实现），本地 override 的 close/reopen 是纯客户端状态，R4 统一对齐 API 契约。

**位置引用与跳转的设计偏差（2026-06-19 识别，归 R4）：**

当前 reader 把跳转行为绑在 issue 实体上：`issue.location`（自由文本）→ `KmdSourceSnapshot.lineNumberForIssue()` 启发式猜行号 → 猜到则可跳转，猜不到则不可。这有两个偏差：

1. **跳转绑错了对象**：跳转应绑在 issue/discussion 内部携带的**位置标签**上，而非绑在 issue 实体上。一个 issue 可能指向行号、场景标签、时间段、多位置、或无具体位置——跳转能力应消费这些结构化位置引用，而非从一个自由文本反推行号。
2. **位置引用未结构化**：`KmdSourceRange` 只认行号（startLine/endLine），无法表达"scene: crosswalk"或"time: 15s"。`KmdSourceHighlightKind` 已预留 `Discussion`，但整个锚点体系只认行号。

**正确方向（R4 与 community-api 契约一起设计）：**
- 定义结构化 `SourceAnchor` 概念，支持多种位置形式（行号 / 场景标签 / 时间点 / 范围 / 无位置）。
- issue/discussion/review/笔记携带 `SourceAnchor` 列表，跳转行为统一消费它。
- 这要求 community-api 的 `location` 字段从自由文本演进为结构化形式——是跨端契约变更，reader 无法独立解决。
- **R3 不动**：local_issue_overrides 只存 issueId + workId + status，不涉及位置引用建模。现有 `lineNumberForIssue()` 启发式保持原样（已知不完美，但在 API 契约演进前是唯一可行方案）。

### 2.7 local_revisions（本地轻度更改 / 待同步缓冲）

**场景定位（2026-06-19 明确）**：本地修改主要服务于 KMD 作者预览纠错和社区审阅者上架评审——改完即同步云端，云端本就提供 revisions 体系。本地只是加载云端 revision、阅读时临时改一行验证、确认后回传。因此本地 revision 本质是**待同步的临时缓冲（outbox）**，不是永久版本库。权威 revision 历史在云端。

```text
LocalRevision
  ├─ id: String（主键，"localrev-{uuid}"）
  ├─ workId: String（外键 → local_library.workId，CASCADE）
  ├─ baseRevisionId: String（基于哪个云端 revision 改的）
  ├─ source: String（修改后的完整 .kmd 源文本）
  ├─ label: String?（用户给这个改动起的备注名）
  ├─ synced: Boolean（false = 待同步，true = 已推送云端）
  ├─ cloudRevisionId: String?（同步成功后云端的 revisionId）
  ├─ createdAt: Long
  └─ updatedAt: Long
```

**播放优先级**：`getWorkSource(workId)` 改为：最新未同步的 LocalRevision.source → 否则云端 activeRevision → 否则原始 kmdSource。这样用户改一行后立即能预览效果。

**R3 范围（仅接口预留）**：
- 建表 + DAO + Repository 接口（CRUD + getActiveLocalRevision）
- 播放链路优先读取（getWorkSource 改造）
- **不做**编辑 UI（源文本编辑器是重度工作，留后续切片）
- **不做**diff 视图、revision 历史浏览、云端同步链路（依赖云端 revisions API + 协作体系）

### 2.8 local_annotations（行级，R3 范围内预留，视精力实现）

```text
LocalAnnotation
  ├─ id: String（主键，"ann-{uuid}"）
  ├─ workId: String（外键 → local_library.workId，CASCADE）
  ├─ sourceLine: Int（源码行）
  ├─ timeMs: Long?（可选：时间锚点）
  ├─ kind: String（Bookmark / Note）
  ├─ content: String（笔记正文；书签为空）
  ├─ color: String?（标记颜色）
  └─ createdAt: Long
```

与 issue 分表：笔记是个人私有（无 severity、无 sourceRange 结构、无协作语义），字段集不同。R3 若精力允许可建表 + 基础 UI，否则纯预留 schema。

### 2.8 reader_preferences（全局级，R3 不实现，记为空白）

播放速度、fontScale、reducedMotion、chrome pinned 策略、默认方向——这些不绑定作品。适合 **DataStore (Preferences)**，不适合 Room（无关系查询需求）。R3 暂不实现（无 UI 入口），记为已知空白。

### 2.9 discussion / 评论：归 R4

社区 discussion 和评论区是社区事实，走 API，不本地存储。R4 范畴。

### 2.10 Room migration 策略

- version 2 → 3，新增 `local_library` + `local_issue_overrides`（+ 可选 `local_annotations`）。
- 显式 `Migration(2, 3)`（CREATE TABLE），保留 `fallbackToDestructiveMigration` 兜底。
- 所有新表自包含或外键指向 `local_library`（不是 `works`）。

## 3. 工作包

### R3-A. 数据层（local_library + local_issue_overrides [+ local_revisions] [+ local_annotations]）
- 新建 `LocalLibraryEntity`（onShelf / lastReadAt / readingProgress）+ `LocalLibraryDao`
- 新建 `LocalIssueOverrideEntity`（外键→local_library）+ `LocalIssueOverrideDao`
- 新建 `LocalRevisionEntity`（外键→local_library）+ `LocalRevisionDao`（仅接口预留）
- 可选：`LocalAnnotationEntity`（外键→local_library）+ `LocalAnnotationDao`，纯 schema 预留
- migration(2,3)
- 新建 `LocalLibraryRepository`（接口 + Room 实现 + InMemory 默认）：entry CRUD + 进度 + issue override + revision + annotation 读写
- DI 接线
- 单元测试

### R3-B. 进度持久化（local_library.readingProgress 字段）
- ViewModel 注入 LocalLibraryRepository
- ready 后恢复 seek
- progressChanged 节流写入
- onCleared 落盘
- 首次阅读自动创建 entry（onShelf=false）

### R3-C. 本地 issue 操作持久化
- issueStatusOverrides / issueDraft / playbackAnchors → local_issue_overrides
- 进入阅读时恢复
- close/reopen/draft 同步写入

### R3-D. 本地导入
- 扩展 frontmatter 解析器
- SAF 文件选择
- 导入 → LocalLibraryEntry（onShelf=true, kmdSource 全文）

### R3-E. 本地轻度更改（仅存储 + 播放链路，不做编辑 UI）
- local_revisions 表的 Repository 接口（CRUD + getActiveLocalRevision）
- 播放链路改造：`getWorkSource(workId)` 优先读最新未同步 LocalRevision.source → 否则云端 → 否则原始 kmdSource
- **不做**编辑 UI、diff 视图、revision 历史、云端同步链路
- 接口为后续（作者纠错 / 审阅者评审 + 云端 revisions）预留

### R3-F. 书架 UI（书架 + 阅读历史分离）
- MineDesk 改造：书架（onShelf=true）+ 历史（lastReadAt!=null）
- 卡片显示标题、进度、时间
- 继续阅读入口

### R3-G. 加入书架
- 浏览/详情页「加入书架」
- 阅读自动记录历史

### R3-H. 笔记/书签（视精力，纯预留或最小实现）
- local_annotations 表 + 基础 CRUD
- 阅读中长按行加书签/笔记（最小 UI）
- 书架/companion 展示笔记列表

## 4. 依赖与顺序

```text
R3-A 数据层（entry + issue override + revision [+ annotation]，无依赖）
  ├─→ R3-B 进度持久化
  ├─→ R3-C issue 操作持久化
  ├─→ R3-D 本地导入
  ├─→ R3-E 本地轻度更改（存储+播放链路）
  ├─→ R3-F 书架 UI
  │    └─→ R3-G 加入书架
  └─→ R3-H 笔记/书签（视精力）
```

建议顺序：A → B → C → D → E → F → G，H 视精力。

## 5. 验收

- 导入本地 `.kmd` → 出现在书架 → 可阅读播放。
- 社区/mock 作品阅读后 → 阅读历史显示 + 进度恢复。
- 播放到中段退出 → 重新进入 → 恢复进度。
- issue close/reopen/draft 退出阅读后不丢。
- 书架（主动收藏）和阅读历史（读过）分离展示。
- 书架空状态友好。
- 本地轻度更改：有未同步 revision 时播放优先读取它（接口验证，无编辑 UI）。
- （若实现 H）阅读中可加笔记/书签，companion 可查看。
- `./gradlew :app:testDebugUnitTest` + `assembleDebug` 通过。
- 不崩溃（mock 作品进度/issue 写入绕过外键陷阱）。

## 6. 非目标

- 不做生态级 `Work.presentation` 生成器。
- 不做完整编辑器。
- 不做多设备同步。
- 不重构 WorkRepository（社区发现链路保持不变）。
- 不把 mock 作品写入 works 表。
- **不做本地轻度更改的编辑 UI**（源文本编辑器、diff 视图、revision 历史浏览）——留后续切片；R3 只留存储接口 + 播放优先读取链路。
- **不做云端 revision 同步链路**——依赖云端 revisions API + 协作体系，待云端就绪后实现。
- **不做 reader_preferences**（fontScale/reducedMotion/速度/方向偏好）——记为空白，适合 DataStore，待有 UI 入口时实现。
- **不做 discussion/评论**（归 R4，社区事实走 API）。
- **不做 review 提交链路**（submitReview 是 dead code 就绪点，待 R4 接线）。

## 6. 非目标

- 不做生态级 `Work.presentation` 生成器（roadmap 已明确）。
- 不做完整编辑器。
- 不做多设备同步。
- 不重构 WorkRepository（社区发现链路保持不变）。
- 不把 mock 作品写入 works 表（保持 mock 在内存）。

## 7. 与既有规划的对齐

- `roadmap.md` R3 章节的"阅读进度持久化"对应本规划的 R3-B。
- `roadmap.md` R3 章节的"导入 .kmd"对应 R3-C。
- `roadmap.md` R3 章节的"书架"对应 R3-D。
- `runtime-ui-implementation-plan.md` UI-6 移入 R3 的决策已记录在 roadmap。
- `prd.md` 的 ReadingProgressRecord（workId/progress/positionPayload/lastReadAt）与本规划的 LocalLibraryEntry 字段需对齐（本规划用 readingProgress/readingTimeMs/lastReadAt，语义更完整）。

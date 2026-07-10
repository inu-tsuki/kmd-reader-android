# R3：完善的本地阅读器 —— 实施规划

> 文档状态：规划草案
> 最近更新：2026-07-08（采纳 storage spike §7 推荐全表；R3-D 扩写为 D1–D4 切片）
> 代号：R3
> 权威范围：本地数据模型、书架、导入、阅读进度持久化、设置、完整离线阅读体验

## 定位

R3 的目标是**一个拔掉网线也能完整使用的本地 KMD 阅读器**。与 R4（云端社区能力，多仓库协同）对应：R3 建本地完整，R4 建云端完整。

R3 完成标准：不依赖任何后端，用户可以导入 `.kmd`、在书架管理、阅读并恢复进度、调整阅读偏好、做本地 issue/笔记。所有数据本地持久化。

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
  ├─ kmdSource: String?（裸 .kmd 无资产作品的 MVP 快捷路径；.kmdwork 走 bundle 指针，此处留 null）
  ├─ bundleId: String?（指向 filesDir/bundles/<bundleId>/；.kmdwork 导入必填，裸 .kmd / 社区 / mock 为 null）
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
- 用户从浏览/导入加入书架时，`LocalLibraryRepository` 创建 entry（社区作品存摘要 + contentUri；本地导入裸 `.kmd` 存 `kmdSource` 全文，`.kmdwork` 存 `bundleId` 指针、`kmdSource` 留 null）。

### 2.2 进度持久化：作为 LocalLibraryEntry 字段

进度是 `LocalLibraryEntry.readingProgress / readingTimeMs / readingDurationMs / lastReadAt`。

- 节流写入：每 5 秒或进度变化 >2% 更新 entry。
- 恢复：进入阅读时查 entry，ready 后 seek。
- **前提**：作品必须在 LocalLibraryEntry 里（社区作品首次阅读时自动创建 entry，或手动加入书架）。
- 这天然解决了外键问题：进度不指向 works，而是 entry 自身的字段。

### 2.3 本地导入：SAF + frontmatter 解析

导入流程（双格式，对齐 R3-D3 采纳的 storage spike）：
```
用户点「导入」→ SAF 文件选择器 → 读 magic bytes 判 .kmdwork vs 裸 .kmd
  ├─ 裸 .kmd → 读文件文本 → KmdSourceMetadataParser（扩展版）
  │    → 生成 LocalLibraryEntry（source=Local, kmdSource=全文, bundleId=null, contentUri=SAF Uri）
  └─ .kmdwork → 解压进 filesDir/bundles/<bundleId>/（spike §5.2 Z1–Z9 防护）
       → 校验 work.json + contentHash → 生成 LocalLibraryEntry（source=Local, kmdSource=null, bundleId=指针, contentUri=SAF Uri）
  → 写入 Room → 加入书架，可打开详情和阅读
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
│    本地提交（commit 模型最小实现）：.kmd 源文本快照     │
│    作者纠错 / 审阅者评审，本地领先的提交同步云端        │
│    播放优先读取最新提交                                 │
│    （一对多，外键→local_library.workId）                │
├─ 草稿级 ──────────────────────────────────────────────┤
│  local_drafts                                          │
│    通用草稿缓冲：issue/discussion/review 写到一半的内容 │
│    不绑定具体社区实体类型，payload JSON 透传            │
│    （一对多，外键→local_library.workId）                │
├─ 个人行级 ────────────────────────────────────────────┤
│  local_annotations（视精力）                           │
│    用户私有笔记/书签（附着在源码行或时间点）            │
│    （一对多，外键→local_library.workId）                │
├─ 全局级 ──────────────────────────────────────────────┤
│  reader_preferences（DataStore，R3-I）                  │
│    不绑定具体作品的偏好：fontScale、reducedMotion、     │
│    默认方向、chrome pinned 策略                         │
└────────────────────────────────────────────────────────┘
```

**为什么按附着对象分层而非数据类型混合：**
- issue/discussion/review 的**社区操作**（close/reopen/提交）本质是云端行为，本地持久化会制造状态分裂——离线关闭的 issue 别人看不到，价值低。issue 和 discussion 应统一为云端模型（R4）。R3 只保留"草稿缓冲"（写到一半防丢），不做社区操作的本地化。
- 阅读进度是作品级标量，不是行级实体，放 local_library 字段即可。
- 偏好不绑定作品（全局级），适合 DataStore 而非 Room。

**issue/discussion 本地化的决策（2026-06-19）：**
- 不建 `local_issue_overrides`：close/reopen/提交归 R4 云端。本地持久化这些操作会制造"本地标记 closed 但云端仍 open"的状态分裂，且 issue（社区协作）和 discussion 应统一实现。
- 只建 `local_drafts`：issue/discussion/review 写到一半的草稿本地缓冲（写到一半退出不丢）。payload 是 JSON 透传，R3 不关心内部结构，各类型自己序列化。R3-B 时 issue draft 写入此表；R4 时 discussion/review draft 也用此表。

### 2.5 local_library（作品级，R3 核心）

```text
LocalLibraryEntry
  ├─ workId: String（主键）
  ├─ source: WorkSourceType（Local / Remote / Mock）
  ├─ onShelf: Boolean（书架 vs 阅读历史）
  ├─ title / authorName / presentation 摘要（快照）
  ├─ kmdSource: String?（裸 .kmd 无资产作品 MVP 快捷路径；.kmdwork 留 null）
  ├─ bundleId: String?（指向 filesDir/bundles/<bundleId>/；.kmdwork 导入必填，其余 null）
  ├─ contentUri: String
  ├─ readingProgress: Float（0..1）
  ├─ readingTimeMs: Long?
  ├─ readingDurationMs: Long?
  ├─ lastReadAt: Long?
  ├─ importedAt: Long?
  └─ cachedAt: Long?
```

自包含、无外键指向 `works`，避免 mock 作品不在 Room 的崩溃陷阱。
`kmdSource` vs `bundleId` 双路径对齐 R3-D3 / storage spike §2.5：裸 `.kmd` 走 `kmdSource` 全文快捷路径；带 assets 的 `.kmdwork` 走 `bundleId` 指针，source 从 bundle store 读（spike C4 红线：Room 只存轻量索引，不内联全量）。

### 2.6 local_drafts（草稿级，通用缓冲）

issue/discussion/review 的社区操作（close/reopen/提交）归 R4 云端。R3 只保留"写到一半的草稿"本地缓冲：

```text
LocalDraft
  ├─ id: String（主键，"draft-{uuid}"）
  ├─ workId: String（外键 → local_library.workId，CASCADE）
  ├─ type: String（"issue" | "discussion" | "review"）
  ├─ payload: String（JSON，各类型自己的字段序列化）
  └─ updatedAt: Long
```

- R3 只建表 + 存取接口（saveDraft / getDrafts / deleteDraft）。
- payload 是 JSON 透传，R3 不关心内部结构——issue draft 存 IssueDraft 的 JSON，discussion/review 各自序列化。
- R3-B 时 issue draft 写入此表；R4 时 discussion/review draft 也用此表。
- 提交云端成功后删除对应 draft（R4 消费）。

**为什么不建 local_issue_overrides（2026-06-19 决策）：**
issue/discussion/review 的 close/reopen/提交本质是云端行为。本地持久化这些操作会制造状态分裂（本地 closed 但云端 open，别人看不到）。issue 和 discussion 应统一为云端模型（R4）。R3 只做"草稿防丢"，不做社区操作本地化。

**位置引用与跳转的设计偏差（2026-06-19 识别，归 R4）：**

当前 reader 把跳转行为绑在 issue 实体上：`issue.location`（自由文本）→ `KmdSourceSnapshot.lineNumberForIssue()` 启发式猜行号 → 猜到则可跳转，猜不到则不可。这有两个偏差：

1. **跳转绑错了对象**：跳转应绑在 issue/discussion 内部携带的**位置标签**上，而非绑在 issue 实体上。一个 issue 可能指向行号、场景标签、时间段、多位置、或无具体位置——跳转能力应消费这些结构化位置引用，而非从一个自由文本反推行号。
2. **位置引用未结构化**：`KmdSourceRange` 只认行号（startLine/endLine），无法表达"scene: crosswalk"或"time: 15s"。`KmdSourceHighlightKind` 已预留 `Discussion`，但整个锚点体系只认行号。

**正确方向（R4 与 community-api 契约一起设计）：**
- 定义结构化 `SourceAnchor` 概念，支持多种位置形式（行号 / 场景标签 / 时间点 / 范围 / 无位置）。
- issue/discussion/review/笔记携带 `SourceAnchor` 列表，跳转行为统一消费它。
- 这要求 community-api 的 `location` 字段从自由文本演进为结构化形式——是跨端契约变更，reader 无法独立解决。
- **R3 不动**：不涉及位置引用建模。现有 `lineNumberForIssue()` 启发式保持原样（已知不完美，但在 API 契约演进前是唯一可行方案）。

### 2.7 local_revisions（本地提交 —— commit 模型最小实现）

**语义修订（2026-07-07 决策）**：本节原定位是"待同步的临时缓冲（outbox）"。`.kmdwork` 调研（[`r3-d-local-import-research.md`](r3-d-local-import-research.md) §6.10、§6.12）确立 revision 走提交（commit）模型后，"待同步 outbox"与"可携带的 revision 历史"应是同一模型——所谓待同步，就是**本地领先的提交**。因此 `local_revisions` 从一开始就按本地提交建 schema，不再另设 outbox 概念。

原 2026-06-19 的场景定位（作者预览纠错 / 审阅者上架评审，改完同步云端）仍然成立，只是重新解释为：每次确认的修改产生一个本地提交；同步 = 把本地领先的提交推送云端；云端保存作品完整提交历史，仍是权威。

```text
LocalRevision（本地提交）
  ├─ id: String（主键，"rev-{uuid}"，本地提交身份）
  ├─ workId: String（外键 → local_library.workId，CASCADE）
  ├─ parentRevisionId: String?（父提交：上一个本地提交 id，或 origin 云端 revisionId；首个提交可为 null）
  ├─ contentHash: String（source 内容哈希；去重与本地/云端关联用，不作唯一身份）
  ├─ sourcePath: String（filesDir 下的相对路径，指向全量 source snapshot；source 不内联 Room——2026-07-08 采纳 spike §6.4）
  ├─ storageMode: String（恒 "full"；diff 模型预留位，未来切换不破坏 schema）
  ├─ message: String?（提交说明）
  ├─ syncState: String（"local" = 本地领先 / "synced" = 已推送云端）
  ├─ remoteRevisionId: String?（推送成功后云端 revisionId，origin mapping）
  └─ createdAt: Long（提交不可变，无 updatedAt）
```

`sourcePath` 不等同于 `bundleId` 路径。按作品形态分两类：
- `.kmdwork` / `bundleId != null`：写 `bundles/<bundleId>/revisions/<revId>.kmd`，与 bundle store 同根。
- 裸 `.kmd` / `bundleId == null`：写 `local-revisions/<workKey>/revisions/<revId>.kmd`，其中 `workKey` 是由 `workId` 派生的安全单路径段（hash/UUID 均可），不得直接信任任意外部 `workId` 作为目录名。原始导入 source 仍可留在 `LocalLibraryEntry.kmdSource` 快捷路径；一旦存在本地提交，播放优先读取 `sourcePath` 指向的 snapshot。

与原 outbox schema 的差异：`baseRevisionId` → `parentRevisionId`（从"基于哪个云端版本"泛化为提交父指针，本地提交可以链式相接）；`label` → `message`；`synced: Boolean` → `syncState`；新增 `contentHash`；删去 `updatedAt`（提交不可变，修改即新提交）。

**播放优先级**（语义不变，措辞按提交模型）：`getWorkSource(workId)` = 最新本地提交的 source（无论 syncState——最新提交即最新可播放版本）→ 否则云端 activeRevision → 否则原始 kmdSource。书架/播放始终展示最新提交；`.kmdwork` 的导出规则（导出时自动提交，export snapshot ≡ 最新提交，见调研稿 §6.12）保证这一视图跨导入导出一致。

**对 R3-A 已交付实体的影响**：`LocalRevisionEntity` 已按旧 outbox schema 建表（migration 2→3），但仍处"仅接口预留"阶段、无生产写入路径。schema 修订与 R3-D1 的 `LocalLibraryEntity` 指针字段**合并为同一次 migration 3→4**（少一次迁移）；现在是改 schema 代价最小的窗口。

**R3 范围（仅接口预留）**：
- schema 修订 + DAO + Repository 接口（append-only create + read/list/getLatestRevision + 按 contentHash 查找；提交不可变，不提供原地更新语义）
- 播放链路优先读取（getWorkSource 改造）
- 不新增用户可触发的本地提交入口；R3-E 只为测试、后续 `.kmdwork` revision manifest 导入、editor / cloud revision 接入预留写入面
- **不做**编辑 UI（源文本编辑器是重度工作，留后续切片）
- **不做**diff 视图、revision 历史浏览 UI、云端同步链路（依赖云端 revisions API + 协作体系）

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

- version 2 → 3，新增 `local_library` + `local_revisions` + `local_drafts`（+ 可选 `local_annotations`）。
- 显式 `Migration(2, 3)`（CREATE TABLE ×3），保留 `fallbackToDestructiveMigration` 兜底。
- 所有新表自包含或外键指向 `local_library`（不是 `works`）。

## 3. 工作包

### R3-A. 数据层（local_library + local_revisions + local_drafts）
- 新建 `LocalLibraryEntity`（onShelf / lastReadAt / readingProgress）+ `LocalLibraryDao`
- 新建 `LocalRevisionEntity`（外键→local_library）+ `LocalRevisionDao`（仅接口预留）
- 新建 `LocalDraftEntity`（外键→local_library）+ `LocalDraftDao`（通用草稿缓冲）
- 可选：`LocalAnnotationEntity`（外键→local_library）+ `LocalAnnotationDao`，纯 schema 预留
- migration(2,3)
- 新建 `LocalLibraryRepository`（接口 + Room 实现 + InMemory 默认）：entry CRUD + 进度 + shelf + revision + draft 读写
- DI 接线
- 单元测试

#### R3-A 数据层 bug 修复记录

> R3-A 落地后，对 `data/local/` 下全部 Dao 做了一次 `@Insert(REPLACE)` 级联风险扫查。
> 模式：父表用 `@Insert(REPLACE)` 是先删后插，会触发子表 `ON DELETE CASCADE`，
> 任何一次父表字段更新（刷新时间戳、改字段）都会误删子表数据。
> 修法统一为 `@Upsert`（编译为 `INSERT … ON CONFLICT DO UPDATE`，原地更新）。

- **Finding 1（PR #3）**：`LocalLibraryDao.upsert` 用 `@Insert(REPLACE)` →
  `updateProgress` / `setOnShelf` 会级联删 `local_revisions` / `local_drafts`。改 `@Upsert`。
- **Finding 2（PR #3）**：`LocalLibraryRepository` 缺 `saveRevision` / `clearRevisionsForWork`
  契约，补齐。
- **Finding 3（PR #3）**：`MIGRATION_2_3` 未暴露，迁移测试无法复跑。改 `internal` 可见 + 迁移测试。
- **Finding 4（fix/r3-work-dao-cascade）**：`WorkDao.upsert` / `upsertAll` 用 `@Insert(REPLACE)` →
  `works` 是 `script_issues` 的父表（`ON DELETE CASCADE`），刷新 work 元数据（`syncedAt` /
  `activeRevisionId`）会级联删该 work 的全部 `script_issues`。与 Finding 1 同型。改 `@Upsert`，
  加 `workUpsertDoesNotCascadeDeleteIssues` / `workUpsertAllDoesNotCascadeDeleteIssues` 回归。

> 备注：`LocalRevisionDao.upsert` / `LocalDraftDao.upsert` 也用了 `@Insert(REPLACE)`，
> 但它们是叶子表（无子表），REPLACE 不会级联删任何数据 —— 不是 bug。是否顺手统一为
> `@Upsert` 仅是代码一致性问题，留待后续顺手处理。

### R3-B. 进度持久化（local_library.readingProgress 字段）

#### 范围（R3-B 实施时确认）

- **纯进度持久化**：不纳入"自动保存进度开关"（开关归 R3-I 设置页，需要 DataStore reader_preferences）。
- **节流策略**：时间间隔节流（播放中定时落盘，默认 5s）+ `onCleared` 兜底落盘。

#### 落地现状（R3-B 实施前调研）

- **落点已就绪（R3-A 交付）**：`LocalLibraryRepository` 已有 `getEntry` / `updateProgress(workId, progress, timeMs, durationMs, now)` / `upsertEntry`（接口 `data/repository/LocalLibraryRepository.kt:55-75`，Room + `InMemoryLocalLibraryRepository` 双实现齐全）。
- **关键约束**：`updateProgress` 在 **entry 不存在时直接 return（空操作）**（`RoomLocalLibraryRepository.kt:104`、`InMemoryLocalLibraryRepository.kt:175`）。因此"首次阅读自动创建 entry"是进度写入的前置条件，必须先于任何进度写入执行。
- **唯一接入缺口**：`KmdReaderViewModel` 构造函数（`:28-32`）未注入 `LocalLibraryRepository`；`MainActivity.kt:18-23` 的 `Factory` 与 `AppContainer.localLibraryRepository`（`KmdReaderAppContainer.kt:39-45`）已造好但没接进 ViewModel。
- **进度事件链路完备**：`Ready(durationMs)` → `ProgressChanged(progress/timeMs/durationMs)` → `seek(progress)` 全部就绪。
- **时序安全结论**：进度恢复的安全路径是"业务 `Ready` 事件到达 → ViewModel 进入 `Ready` 态 → 读持久化进度 → seek"。`seekReader()`（`:392-404`）有"必须 Ready 才 seek"守卫，不能在 Ready 前 seek。

#### 实施步骤

##### 步骤 1：ViewModel 注入 LocalLibraryRepository（接入点）
- `KmdReaderViewModel` 构造函数新增参数 `localLibrary: LocalLibraryRepository`，默认值 `InMemoryLocalLibraryRepository()`（保持现有测试/调用不破）。
- `KmdReaderViewModel.Factory`（`:666-677`）新增 `localLibrary` 字段并在 `create()` 传入。
- `MainActivity.kt:18-23` 的 Factory 调用补 `localLibrary = appContainer.localLibraryRepository`。

##### 步骤 2：首次阅读自动创建 entry（onShelf=false）
- 位置：`loadCurrentReaderWork()`（`:127-199`）的 `viewModelScope.launch` 内，拿到 `source` 后、`runtimeBridge.load(...)` 前。
- 语义：`getEntry(work.id)` 先判存在性，不存在才 `upsertEntry(...)`（走 get-then-insert，不盲目 upsert，避免覆盖已有进度）。
- entry 字段取值（Work → LocalLibraryEntry）：`workId`=work.id、`source`=work.sourceType、`onShelf`=false、`title`=work.title、`authorName`=work.authorName、`presentationMode`=work.presentation.mode、`aspectRatio`=work.presentation.aspectRatio、`contentUri`=work.contentUri、`readingProgress`=0f，时间字段（readingTimeMs/readingDurationMs/lastReadAt/importedAt/cachedAt）=null，`kmdSource`=null。

##### 步骤 3：Ready 后恢复 seek（含 duration 匹配守卫）
- 位置：`handleRuntimeEvent` 的 `ReaderRuntimeEvent.Ready` 分支（`:231-251`）`_state.update {...}` 之后。
- `viewModelScope.launch` 读 `localLibrary.getEntry(event.workId)`：
  - 有 entry 且 `readingProgress` ∈ (0,1) 且 `readingDurationMs` 与 `event.durationMs` **双方都非 null 且相等** → `runtimeBridge.seek(readingProgress)`。
  - duration 不匹配（换源/修订导致 duration 变化）→ 不恢复，防"旧进度 + 新 duration = 跳到错位置"。
  - **任一方为 null（无可靠基准）→ 不恢复**（OQ 审阅决议：严格语义。新作品首次阅读时 entry.readingDurationMs=null，但此时 progress=0 已被 `progress ∈ (0,1)` 守卫拦住，严格语义对其无实际影响）。
  - progress=0 或无 entry → 不 seek（首次阅读/已读完）。
- 直接调 `runtimeBridge.seek` 而非 `onAction(SeekReader)`，避免重复 reduce；失败静默。

##### 步骤 4：progressChanged 时间间隔节流写入
- 位置：`handleRuntimeEvent` 的 `ProgressChanged` 分支（`:252-283`）末尾。
- 新增实例字段 `private var lastProgressSavedAt: Long = 0L`（与 `nowMillis` 同一时钟）。
- `viewModelScope.launch`：当 `nowMillis() - lastProgressSavedAt >= PROGRESS_SAVE_INTERVAL_MS`（companion const，默认 **5000ms**）→ `updateProgress(workId, progress, timeMs, durationMs, nowMillis())`，更新 `lastProgressSavedAt`。失败静默。
- 5s 间隔依据：单本 20min 作品播放期间写 ~240 次（vs 不节流的数千次），断电最多丢 5s 进度；Room WAL 下单写 <1ms 不影响 UI。

##### 步骤 5：onCleared 落盘（兜底）
- 位置：`onCleared()`（`:661-664`）。
- 取当前 `readerSession` 若是 `Ready`，用其 `workId`/`progress`/`timeMs`/`durationMs` 做最后一次 `updateProgress`。
- **关键约束**：`onCleared` 时 `viewModelScope` 已取消，不能用 `viewModelScope.launch`。用 `runBlocking` 同步落盘（Android ViewModel 落盘标准做法，onCleared 窗口短）。失败静默。

#### 测试清单（复刻 KmdReaderViewModelTest + ManualRuntimeBridge 模式）

1. `openReaderAutoCreatesLibraryEntryOnShelfFalse`：open work → open reader → assert repo 含 `entry(workId, onShelf=false)`。
2. `readyRestoresSavedSeekProgress`：预置 entry(progress=0.42, durationMs=D) → open reader → emit `Ready(durationMs=D)` → assert `runtimeBridge.seekCalls` 含 0.42f。
3. `readyDoesNotRestoreSeekWhenDurationMismatched`：entry(progress=0.42, duration=D) → emit `Ready(durationMs=D*2)` → assert `seekCalls` 为空。
4. `progressChangedThrottledWrites`：进 Ready → 连发多个 ProgressChanged（间隔 <5s）→ assert 写 1 次；`nowMillis` 推进 ≥5s 再发一个 → assert 写第 2 次。
5. `progressChangedPersistsProgressFields`：节流写入后 assert entry 的 readingProgress/readingTimeMs/readingDurationMs 与事件一致。
6. `onClearedFlushesLatestProgress`：进 Ready + 发 ProgressChanged（未到节流窗口）→ `onCleared()` → assert entry 含最新 progress（兜底落盘生效）。

#### 审阅修复（PR #5 review，2026-06-21）

R3-B 首版落 PR #5 后审阅发现 1 High + 2 Medium 数据完整性风险 + 1 Open Question，全部修复：

- **F1（High）迟到事件 workId 门控**：WebView 单例复用，切 work 后旧 work 的迟到 `Ready`/`ProgressChanged` 仍能到达。原代码无条件用 `event.workId` 重建 session + 写 Room → 数据腐败。修复：以 `deskStack.currentWorkId` 为基准门控（OpenWork 立即更新它，比 readerSession 更早反映切换）。`sessionId` 不可用（每个 WebView 进程固定一次，跨 work swap 不变）。
- **F2（Medium）restore seek 后回写 state**：`Ready` 设 `progress=0f`，restore 只调 `bridge.seek` 不回写 state，restore 与 runtime echo `ProgressChanged` 之间若 `onCleared`，flush 写 `0f` 覆盖恢复点。修复：seek 成功后回写 `session.progress`。
- **F3（Medium）节流计数器 per-work**：`lastProgressSavedAt` 原为 ViewModel 全局单值，work A 存盘后 5s 内开 work B，B 的首个进度事件被误中 A 的窗口而丢弃。修复：改为 `mutableMapOf<String, Long>()` by workId，map 不含 key 即首次（同时移除 `hasSavedProgress` 标志）。
- **OQ（Open Question）duration 守卫严格化**：原代码"任一 null 就恢复"（宽松），与文档 `==` 措辞不符。决议：**严格——双方都非 null 且相等才恢复**（最保守，无可靠基准不恢复）。对齐代码注释与本节措辞。

新增 6 个回归用例（`staleReadyEventDoesNotMutateSession...` / `staleProgressEventDoesNotPersist...` / `restoreSeekThenFlushBeforeProgressEcho...` / `throttleResetsAcrossWorks` / `readyDoesNotRestoreSeekWhenSavedDurationIsNull` / `readyDoesNotRestoreSeekWhenEventDurationIsNull`），KmdReaderViewModelTest 20 → 26 用例。

#### 不做（范围外）

- 自动保存进度开关 / DataStore reader_preferences（→ R3-I）。
- 书架 UI / 历史列表（→ R3-F）、详情页"继续阅读"按钮态（→ R3-H）、issue 草稿本地缓冲（→ R3-C）。

### R3-C. issue 草稿本地缓冲

issue draft（写到一半的 message + suggestion + 锚点信息）写入 `local_drafts`（type=`"issue"`），用户编辑到一半退出不丢。close/reopen 不本地持久化（归 R4 云端 action log）。

#### 范围确认
- **做**：草稿自动保存（debounce）、StartIssueDraft 时恢复未提交草稿、提交/取消后删除草稿。
- **不做**：close/reopen 的本地持久化（→ R4 云端 action log）；discussion/review 草稿（→ R4，复用 `local_drafts` 表 + 不同 type）。

#### 落地现状
数据层（`LocalDraftEntity` + FK CASCADE、`LocalDraftDao`、DB v3 migration、Repository 接口 + Room/InMemory 双实现）由 R3-A 交付，无需 schema 变更。本节只做 ViewModel 接线 + 序列化。

#### 实施步骤

**步骤1. 序列化**
- `IssueDraft` / `PlaybackAnchor` / `KmdSourceRange` / `IssueSeverity` 加 `@Serializable`（kotlinx.serialization，插件已在 app 模块）。
- `IssueDraft` 新增 `id: String` 字段（持久化主键 `"draft-{uuid}"`，默认 `""`）。
- `IssueDraft.toJson()` / `issueDraftFromJson(payload)` 编解码，`Json { ignoreUnknownKeys = true }` 容忍未来字段。

**步骤2. debounce 自动保存（时钟比较，镜像 R3-B 进度节流）**
- `lastDraftSavedAt: Map<draftId, Long>` + `DRAFT_SAVE_INTERVAL_MS = 1_000L`。
- `onAction` 拦截 `UpdateIssueDraftMessage/Suggestion` → reduce → `scheduleDraftSave()`（窗口内跳过，窗口边界处写入）。
- `onCleared` 兜底：`flushDraftOnCleared()` 用 `runBlocking` 强存一次当前草稿（防"最后一字不按键就退出"丢失）。
- **不用 delay/Job/debounce**——与现有 ViewModel 时钟节流模式一致，在 `UnconfinedTestDispatcher` 下可测。

**步骤3. StartIssueDraft 时恢复**
- `onAction` 拦截 `StartIssueDraftFromPlayback` → `startIssueDraftWithPersistence()`：先 reduce 建骨架 → 生成 UUID id（`AssignIssueDraftId`）→ 查 `getDraftsByType(workId, "issue")` → 有则 `UpdateIssueDraftFromPersisted` 恢复。
- **恢复语义**：恢复 message + suggestion + severity；**不恢复** sourceRange/playbackAnchor（每次重新采集锚点更安全）。
- 两个内部 action（`AssignIssueDraftId` / `UpdateIssueDraftFromPersisted`）保持 reducer 纯函数——UUID 生成和 Room IO 都在 VM 侧。

**步骤4. 提交/取消后删除**
- `submitIssueDraft()` 开头调 `deleteCurrentDraftIfAny()`（已转为 issue，不再需要缓冲）。
- `onAction` 拦截 `CancelIssueDraft` → `deleteCurrentDraftIfAny()` → reduce。

**步骤5. 类型常量**
- `LocalDraftTypes` object（`ISSUE` / `DISCUSSION` / `REVIEW`），避免裸字符串散落。

#### 测试清单（KmdReaderViewModelTest +6，28 → 34）
- `updateIssueDraftMessageDebouncesWrites` — clock=0 存，clock=100 跳过，clock=1001 存
- `startIssueDraftRestoresPersistedMessage` — 预置草稿，StartIssueDraft 后恢复 message/suggestion/severity
- `startIssueDraftWithNoPersistedDraftUsesDefaults` — 无持久化草稿用 reducer 默认值
- `submitIssueDraftDeletesPersistedDraft` — 提交后 local_drafts 为空
- `cancelIssueDraftDeletesPersistedDraft` — 取消后 local_drafts 为空
- `draftSerializeRoundTripPreservesFields` — IssueDraft ↔ JSON 字段无损

#### 审阅修复（PR #6 review，2026-06-21）

- **F1（High）恢复草稿 id 错配**：`startIssueDraftWithPersistence` 原总是先生成新 UUID 再恢复，但恢复 action 只带 message/suggestion/severity 不带 persisted id → submit/cancel 删的是新 UUID，旧 `draft-old` 留为孤儿 → 下次又恢复。修复：查库先判断——有持久化草稿则复用其 `LocalDraft.id`（`AssignIssueDraftId(restored.id)`），无则才生成新 UUID。
- **F2（Medium）trailing flush 缺失**：`scheduleDraftSave` 是 leading-edge throttle（首次即写、窗口内跳过），不安排 trailing save。用户在窗口内打完最后一字后若切走（SelectSourceLine/ClearIssueFocus/SelectIssue），最后一笔丢失。修复：这些清空/替换 issueDraft 的导航型 action 前调 `flushDraftSynchronously()`（忽略窗口立即存）。onCleared 仍作最终兜底。

新增 2 个回归用例（KmdReaderViewModelTest 34 → 36）：
- `restoredDraftSubmitDeletesCorrectRowNotOrphan` — 预置 draft-old，恢复后 submit，断言 draft-old 行被删（无孤儿）
- `trailingFlushPersistsLastEditBeforeNavigation` — 窗口内打完最后一字 → SelectSourceLine → 断言最后一笔已落盘

#### 审阅修复第二轮（PR #6 review round 2，2026-06-22）

- **F1（Medium）异步恢复无 workId 守卫**：`startIssueDraftWithPersistence` 的 Room 查询返回后直接 dispatch `AssignIssueDraftId`/`UpdateIssueDraftFromPersisted`，不校验当前 issueDraft 是否还是当初那个 work。用户快速从 A 起草→切 B 起草，A 的迟到恢复会覆盖 B 的草稿。修复：发起时捕获 `requestedWorkId` 作为 request token，查询返回后校验 `_state.value.issueFocus.issueDraft?.workId == requestedWorkId`，不匹配则丢弃。
- **F2（续）OpenWork 未纳入 trailing flush**：上一轮 F2 覆盖了 SelectSourceLine/ClearIssueFocus/SelectIssue，但遗漏了 OpenWork（reducer 重建 IssueFocusState 丢弃 draft）。修复：OpenWork 在 reduce 前调 `flushDraftSynchronously()`。至此所有清空/替换 draft 的 action 均已纳入 pre-flush guard。

新增 2 个回归用例（KmdReaderViewModelTest 36 → 38）：
- `staleRestoreDoesNotOverwriteDraftAfterWorkSwitch` — A 起草→切 B 起草，断言当前草稿是 B 的（A 迟到恢复被丢弃）
- `openWorkFlushesPendingDraftBeforeReplacingIssueFocus` — 窗口内打完最后一字 → OpenWork，断言最后一笔已落盘

#### 审阅修复第二轮补强（PR #6 review round 2 residual，2026-06-22）

非阻塞测试覆盖瑕疵（reviewer 指出，不作为合并门槛）：`staleRestoreDoesNotOverwriteDraftAfterWorkSwitch` 原版在 A 起草后立刻 `advanceUntilIdle()`，A 的恢复在 B 起草前已提前完成，并未真正制造“迟到 A 覆盖 B”的竞态窗口——生产代码的 `requestedWorkId` guard 正确但此用例证明力不足。

修复：引入 `ControllableLocalLibraryRepository`（可挂起 fake），用 `CompletableDeferred` 精确卡住 A 的第一次 `getDraftsByType`，B 起草完成后再释放 gate，真正复现迟到路径。**经验证**：临时注释掉 guard 后此用例必须失败（race window 被打开），恢复 guard 后通过——证明其回归价值成立。

> 注意：fake 不能用 `Mutex`/`synchronized` 包裹 `gate.await()`——挂起时持锁会让 B 的查询一并阻塞，把竞态窗口塌缩掉。UnconfinedTestDispatcher 单线程且无抢占，普通 Boolean 标志位即可安全区分“第一次调用”。

### R3-D. 本地导入（2026-07-08 扩写）

> **决策记录（2026-07-08）**：采纳 [`r3-d-storage-spike.md`](r3-d-storage-spike.md) §7 推荐汇总表**全表**——
> 存储分层方案 C（`filesDir/bundles/` 权威 + `cacheDir/runtime-extract/` 播放 cache + Room 轻量索引）、
> SAF 复制导入（不留持久权限）、asset host 两步走、zip Z1–Z9 防护、revision 全量 snapshot + 文件指针。
> **前置已清**：frontmatter 收敛（主仓库 `docs/knowledge/language/frontmatter-schema.md` + editorStore 写回修复）；
> 存储技术调研（spike）。早期现状勘察与问题清账见 [`r3-d-local-import-research.md`](r3-d-local-import-research.md)。

#### R3-D1. 数据层扩展（与 R3-E schema 修订合并为一次 migration 3→4）
- `LocalLibraryEntity` 加指针字段：`bundleId` / `activeRevisionId` / `contentHash` / `originWorkId`（spike §2.5；只加指针/快照，不内联全量——C4 红线）
- `LocalRevisionEntity` 按 §2.7 提交模型修订（`parentRevisionId` / `contentHash` / `message` / `syncState` / `remoteRevisionId` / `sourcePath` 指针 / `storageMode` 恒 `"full"`）
- 同一 `Migration(3, 4)` 完成两组变更；迁移测试对齐 R3-A 模式（`MIGRATION_3_4` internal 可见）

#### R3-D2. `.kmdwork` unpacker + bundle store
- `ZipInputStream` 流式解压 + Z1–Z9 防护清单全量（spike §5.2；上限常量 50MB / 10MB / 1024 entries；API 28/29 手动 zip slip 防御）
- 写入 `filesDir/bundles/<bundleId>/`（`work.json` + `scripts/` + `assets/` + 可选 `original.kmdwork` 备份，默认保留、空间紧张可清）
- `work.json` 校验（Z8 引用闭合）+ contentHash 校验（Z5），失败拒绝导入并清理半解文件
- 清理策略接线（spike §2.4：导入清旧 cache / 删作品删三处 / `onTrimMemory` 清 cache / 启动孤儿扫描）

#### R3-D3. SAF 导入 + 书架
- `ACTION_OPEN_DOCUMENT`，`type="*/*"` + `EXTRA_MIME_TYPES`（zip 双 MIME + text/plain + text/markdown）；导入后读 magic bytes（`PK\x03\x04`）判 `.kmdwork` vs 裸 `.kmd`
- 复制进私有目录；**不调** `takePersistableUriPermission`；`contentUri` 存原始 SAF Uri 仅作来源记录（访问 try-catch 兜底）
- 双格式路径：裸 `.kmd` 走 `kmdSource` 快捷路径（§2.3/§2.5 保留）；`.kmdwork` 走 bundle store，`kmdSource` 留 null
- `KmdSourceMetadataParser` 扩展：title / author / speed；mode 按主仓库 `frontmatter-schema.md`（`paged` → `page` 归一化；`interactive` 降级 `stage` + 诊断）
- 生成 `LocalLibraryEntry`（`onShelf=true, importedAt=now, bundleId` 指针）→ 书架
- `ImportDesk` 真实接线（替换 mock；`OpenImportPicker` effect 的 no-op collector 补实现）

#### R3-D4. 播放接线（asset host + source 分支）
- `getWorkSource(workId)` 加分支：`bundleId != null` → 从 bundle store 读（最新提交优先，对齐 §2.7 播放优先级）
- `shouldInterceptRequest` 扩展新 host `kmd-reader-assets.local`，从 `cacheDir/runtime-extract/<bundleId>/` 服务（spike §4.3 第一步；`WebViewAssetLoader` 迁移留 R3 后）
- `assetManifest.baseUrl` 改写为 `https://kmd-reader-assets.local/<bundleId>/`，改写后的 manifest 由展开层持久化（spike §4.6 倾向，已采纳）
- **补 mapper 的 fonts 透传**：现状 `toReaderRuntimeAssetManifest`（`ReaderRuntimeMappers.kt:5-14`）丢弃 `fonts`，bundle 自带字体到不了 FontFace，必须修
- 展开层：load 前 ensure `cacheDir/runtime-extract/<bundleId>/` 存在（从 bundle store 解压/复制）
- 接线点：`KmdReaderViewModel.kt:188-231` / `ReaderRuntimeMappers.kt:5-14` / `ReaderRuntimeHost.kt:290-310`

**R3-D4 完成记录（2026-07-10）**：
- `getWorkSource` 的 bundleId 分支已在 R3-D3 的 `LocalAwareWorkRepository.getWorkSource()` 实现（`bundleId != null && bundleStore != null` → `readEntrySource`）；D4 未重复，revision 优先级留 R3-E。
- fonts 透传根因：domain `WorkAssetManifest` 缺 `fonts` 字段导致 `toReaderRuntimeAssetManifest` 丢弃。修复方式：domain `WorkAssetManifest` 加 `fonts: List<WorkFontAsset>`（形状对齐 `ReaderRuntimeFontAsset`），mapper 补 `fonts.map{toReader()}`。远程路径（API DTO / Room 无 fonts 列）默认 `emptyList()`，不改 DTO/Room schema；本地作品 fonts 由 `LocalAwareWorkRepository.toWork()` 从 `BundleManifest.assetManifest` 现读，每次 rebuild。
- assetManifest + baseUrl 改写：`LocalAwareWorkRepository.toWork(bundleStore)` 对 bundleId 作品读 `BundleManifest.assetManifest`，映射 baseUrl → `https://kmd-reader-assets.local/<bundleId>/`，fonts/assets 透传；裸 .kmd（bundleId=null）assetManifest=null。
- 展开层：`BundleStore.ensureExtracted(bundleId)` 把 `filesDir/bundles/<bundleId>/{assets,scripts}` 复制到 `cacheDir/runtime-extract/<bundleId>/`（idempotent，半解失败清理 cache）；ViewModel `loadCurrentReaderWork` 在 `runtimeBridge.load` 前按 baseUrl 解析 bundleId 调用。展开缓存子目录名由 `BundleStoreModule.RUNTIME_EXTRACT_DIR` 单一常量定义。
- shouldInterceptRequest：新增 `BundleAssetHost = "kmd-reader-assets.local"`，命中后 `resolveBundleAssetPath`（纯函数，单测覆盖路径解析 + 穿越 guard）解析 bundleId/rest，`openBundleAsset` 经 `resolveBundleAssetFile` 从 `cacheDir/runtime-extract/<bundleId>/<rest>` 服务（与 ensureExtracted 写入位置引用同一常量对齐）；RuntimeAssetHost 分支不动，两 host 共存。

**R3-E 完成记录（2026-07-09）**：
- `RevisionSourceStore`（新增 `data/bundle/`）：把 `LocalRevision.sourcePath`（filesDir 下相对路径）解析为实际文件做读写。两类形态由路径本身区分——`.kmdwork` 落 `bundles/<bundleId>/revisions/<revId>.kmd`，裸 `.kmd` 落 `local-revisions/<workKey>/revisions/<revId>.kmd`（`workKey` 由 `workId` SHA-256 前 16 hex 派生，不信任外部 workId 作目录名）。安全 guard 对齐 BundleStore defense-in-depth：禁 `..`/绝对路径/反斜杠/非 `.kmd` 扩展名 + canonical guard 防符号链接逃逸。写入 atomic（tmp + rename）。路径构造 helper `bundleRevisionPath` / `plainKmdRevisionPath` 暴露给调用方构造 sourcePath。
- DAO append-only 收紧：`LocalRevisionDao` `@Insert(REPLACE)` → `@Insert(ABORT)`，同 id 重复 insert 抛冲突异常，强制调用方写新 id。`LocalLibraryRepository.saveRevision` 接口注释点明 append-only；Room 实现改调 `revisionDao.insert`；InMemory 实现用 `require(id !in revisions)` 模拟 ABORT。补 `revisionInsertDuplicateIdThrows` 测试验证 DB 层 ABORT。
- getWorkSource 播放优先级（§2.7）：`LocalAwareWorkRepository.getWorkSource` 在 isLocalEntry 分支内、kmdSource/bundleStore 快捷路径之前插入 revision 优先级——`getLatestRevision(workId)` 命中且 `revisionSourceStore.readSource(sourcePath)` 读到内容时优先返回。revision 记录存在但 source 文件丢失（bundle 被删/文件损坏）→ fallback 到下一优先级（kmdSource/bundleStore），因为 R3-E 是接口预留、生产写入面尚未接入，残缺 revision 不应阻塞已有 source 的播放。
- `KmdReaderAppContainer` 注入 `RevisionSourceStore(appContext.filesDir)`，`LocalAwareWorkRepository` 构造加 `revisionSourceStore: RevisionSourceStore? = null` 参数。
- 回归测试：`RevisionSourceStoreTest`（bundle/裸 .kmd round-trip + 穿越 guard + canonical symlink guard + 非 .kmd 拒绝 + delete + miss + workKey 派生）；`LocalAwareWorkRepositoryTest` 扩展 3 条（revision 优先于 kmdSource、revision 文件丢失 fallback、revision 优先于 BundleStore）；`KmdReaderDatabaseTest` 补 ABORT 测试。
- 未做：编辑 UI、用户可触发本地提交入口、diff 视图、revision 历史 UI、云端同步链路、`.kmdwork` revision manifest 导入解析（后续切片）。

**R3-E 审查修复（2026-07-09）**：
- **版本身份一致（High）**：`toWork()` 原把 `Work.script.activeRevisionId` 映射成 `LocalLibraryEntry.activeRevisionId ?: "local"`（导入时写死的指针），但 `getWorkSource()` 读 `getLatestRevision(workId)` 的 source。保存新 revision 后，播放内容来自 `rev-local-1` 但 runtime snapshot / issue draft / `Work.script` 仍标 `"local"` 或旧 `exportRevisionId`——内容与身份脱节。修复：`toWork()` 从文件级私有函数改为 `LocalAwareWorkRepository` 的 `suspend` 成员方法，内部调 `localLibrary.getLatestRevision(workId)` 投影最新 revision 的 `id/contentHash/message/createdAt` 到 `Work.script`；无 revision 时回退 entry 指针。`listWorks` / `getWork` 调用方不需改（suspend map 合法）。回归测试 4 条：`getWork_activeRevisionIdMatchesLatestRevision`、`getWork_fallsBackToEntryRevisionIdWhenNoRevision`、`listWorks_activeRevisionIdMatchesLatestRevision`、`getWork_getWorkSource_identityConsistency`（核心断言：`work.script.activeRevisionId` 与实际播放的 revision id 一致）。
- **missing source fallback 边界一致（二轮 Medium）**：`getWorkSource()` 在 latest revision 记录存在但 source 文件缺失时 fallback 到 kmdSource / BundleStore 播放旧内容，但 `toWork()` 无条件投影 latest revision，导致"播放旧 source 但 Work.script 标缺文件的 revision"脱节。修复：`toWork()` 只投影"source 可读的 latest revision"——`revisionSourceStore.readSource(sourcePath) != null` 才投影，否则回退 entry 指针，与 `getWorkSource()` 的 fallback 分支对齐。回归测试 `getWork_doesNotProjectRevisionWhenSourceFileMissing`：revision 记录存在但文件缺失时 `Work.script.activeRevisionId` 标 entry 级 `"local"` 而非 `"rev-missing"`，`getWorkSource` 返回 kmdSource，两者不脱节。
- **writeSource 覆写保护（Medium）**：`RevisionSourceStore.writeSource()` 类注释承诺"不原地覆写已有 sourcePath"，但 `renameTo(file)` 可替换目标文件、fallback `copyTo(file, overwrite = true)` 明确覆写。DAO ABORT 只防重复 revision id，不防不同 revision 指向同一 sourcePath，也不防先写文件后 DB insert 失败导致旧 snapshot 被改写。修复：写入前检查目标文件已存在则抛 `IllegalStateException`；fallback `copyTo` 改 `overwrite = false`（双重保护）。回归测试 2 条：`writeSourceRefusesToOverwriteExistingFile`（原内容不变）、`writeSourceDifferentRevisionsDoNotCollide`（同目录不同 revId 互不覆写）。

#### 主仓库核实结论（2026-07-08，关闭 spike §4.6 第一开放项）
- runtime 字体全部经原生 **FontFace API** 加载（主仓库 `core/App.ts:290-298`），URL 由 `RuntimeAssetPolicy.resolveRuntimeAssetUrl` 按 `assetManifest.baseUrl` 解析；FontFace 的 `url()` 请求走 WebView 资源加载管线，**可被 `shouldInterceptRequest` 拦截**——现有 runtime 随包字体正是这样经 `kmd-reader-runtime.local` 加载的（`reader-runtime-web-bundle.md`）。
- FontFace 注册成功后不再重复走 Pixi `Assets.load`；Android WebView 下无宿主 fonts 清单时跳过 20MB+ 默认字体（`kmdLoadDefaultFonts=1` 可强制）——bundle 自带字体经 `assetManifest.fonts → collectRuntimeFonts → FontFace`，主仓库链路已就绪，Android 侧只欠 D4 的 fonts 透传。
- `assetManifest.assets`（图片/shader/audio）是契约占位，core 当前无消费点（无 `bg`/图片指令）——D4 无需覆盖；未来图片指令经 Pixi `Assets.load`（fetch/Image），同样可拦截。
- `resolveControlledSourceUrl` 安全门要求 https 或 baseUrl 内——`https://kmd-reader-assets.local/<bundleId>/` 满足。

### R3-E. 本地提交（仅存储 + 播放链路，不做编辑 UI）
- local_revisions schema 修订为提交模型（§2.7；migration 3→4 已并入 R3-D1，一次迁移完成两组变更）
- Repository 接口（append-only create + read/list/getLatestRevision + 按 contentHash 查找；提交不可变，不提供原地更新语义）
- 播放链路改造：`getWorkSource(workId)` 优先读最新本地提交 → 否则云端 activeRevision → 否则原始 kmdSource
- **不做**编辑 UI、用户可触发的本地提交入口、diff 视图、revision 历史 UI、云端同步链路
- 接口为后续（作者纠错 / 审阅者评审 + `.kmdwork` revision manifest + 云端 revisions）预留

**后续收束方向（R3-E 后，不阻塞当前切片）**：
- ~~将 `getWork()` / `listWorks()` 的 revision 投影与 `getWorkSource()` 的 source 选择收敛为统一 resolver，例如 `resolveLocalPlayable(workId)`。~~ **已落地（2026-07-09）**：`LocalAwareWorkRepository.resolveLocalPlayable(workId)` 统一解析 entry + playableRevision + source，`getWork` / `listWorks` / `getWorkSource` 消费同一结果。`toWork` 不再独立查 `getLatestRevision` + `readSource`，改为直接用 `LocalPlayable.playableRevision` 投影 `Work.script`，从结构上保证身份与播放内容一致。
- ~~resolver 同时返回 entry、latestRevision、source 与 Work projection，保证 `Work.script.activeRevisionId` 和实际播放 source 来自同一个解析结果。~~ **已落地**：`LocalPlayable` 数据类包含 `entry` / `playableRevision` / `source`，resolver 一次解析供三个路径消费。
- 真实提交入口出现后，再评估是否把 `LocalLibraryEntry.activeRevisionId/contentHash` 作为 denormalized 快照维护；该写入应由提交 use-case 统一处理，而不是分散在 Repository 调用方。

### R3-F. 书架 UI（书架 + 阅读历史分离 + 设置入口）
- MineDesk 改造：书架（onShelf=true）+ 历史（lastReadAt!=null）
- 卡片显示标题、进度、时间
- 继续阅读入口
- 书架页提供设置/关于入口（page-architecture 要求）

### R3-G. 加入书架
- 浏览/详情页「加入书架」
- 阅读自动记录历史

### R3-H. 详情页「继续阅读」按钮态
- 详情页阅读按钮根据阅读历史显示「开始阅读」或「继续阅读」（PRD 5.3/7.1）
- 有进度时显示上次阅读位置摘要

### R3-I. 设置页（阅读偏好）
- 新建设置页：字号（fontScale）、主题（明/暗）、自动保存进度开关、reducedMotion（PRD 5.6/7.1）
- 偏好持久化（DataStore Preferences）
- 阅读时应用偏好：fontScale → ReaderSettings.fontScale，主题 → Compose 主题，reducedMotion → ReaderSettings.reducedMotion
- reader_preferences 从"R3 不做"升级为 R3 必做（PRD MVP 要求）

### R3-J. 笔记/书签（视精力，纯预留或最小实现）
- local_annotations 表 + 基础 CRUD
- 阅读中长按行加书签/笔记（最小 UI）
- 书架/companion 展示笔记列表

## 4. 依赖与顺序

```text
R3-A 数据层（entry + revision + drafts [+ annotation]，无依赖）
  ├─→ R3-B 进度持久化
  ├─→ R3-C issue 草稿本地缓冲
  ├─→ R3-D 本地导入
  ├─→ R3-E 本地提交（存储+播放链路）
  ├─→ R3-F 书架 UI（含设置入口）
  │    └─→ R3-G 加入书架
  ├─→ R3-H 详情页继续阅读按钮态（依赖 B 的进度）
  ├─→ R3-I 设置页（阅读偏好，独立无依赖）
  └─→ R3-J 笔记/书签（视精力）
```

建议顺序：A → B → C → D → E → F → G → H → I，J 视精力。
I（设置）独立无依赖，可与 D/E/F 并行。

## 5. 验收

**完整本地阅读器标准（拔掉网线可用）：**
- 导入本地 `.kmd` → 出现在书架 → 可阅读播放。
- 导入 `.kmdwork`（含自带字体等资产）→ 出现在书架 → 可播放，资产经 `kmd-reader-assets.local` 虚拟 host 加载；zip 安全校验失败的包被拒绝且不留半解文件。
- 社区/mock 作品阅读后 → 阅读历史显示 + 进度恢复。
- 播放到中段退出 → 重新进入 → 恢复进度。
- 详情页有进度时显示「继续阅读」+ 位置摘要。
- issue close/reopen/draft 退出阅读后不丢。
- 书架（主动收藏）和阅读历史（读过）分离展示，书架有设置入口。
- 书架空状态友好。
- 设置页可调字号/主题/自动保存/reducedMotion，偏好持久化，阅读时生效。
- 本地提交：有本地提交时播放优先读取最新提交（接口验证，无编辑 UI）。
- （若实现 J）阅读中可加笔记/书签，companion 可查看。
- `./gradlew :app:testDebugUnitTest` + `assembleDebug` 通过。
- 不崩溃（mock 作品进度/issue 写入绕过外键陷阱）。

## 6. 非目标

- 不做生态级 `Work.presentation` 生成器。
- 不做完整编辑器。
- 不做多设备同步。
- 不重构 WorkRepository（社区发现链路保持不变）。
- 不把 mock 作品写入 works 表（保持 mock 在内存）。
- **不做本地提交的编辑 UI**——留后续切片；R3 只留存储接口 + 播放优先读取链路。
- **不做云端 revision 同步链路**——R4 范畴。
- **不做 discussion/评论**——R4 范畴（社区事实走 API）。
- **不做 review 提交链路**——R4 范畴。
- **不做 SourceAnchor 结构化位置引用**——R4 范畴（与 community-api 契约一起）。

## 7. 与既有规划的对齐

- `roadmap.md` R3 章节 → 本规划全面覆盖并扩展为"完善本地阅读器"。
- `prd.md` 7.1 MVP 表 → 书架/导入/进度/设置/数据层均在 R3；审核工作台拆散到 R2 companion + R3-E + R4。
- `page-architecture.md` → 书架含设置入口；审核体验在详情入口 + 阅读 companion（非独立 Tab）。
- `runtime-ui-implementation-plan.md` UI-6 → 对应 R3-B。
- R3 与 R4 的边界：R3 = 本地完整（离线可用），R4 = 云端完整（多仓库协同）。

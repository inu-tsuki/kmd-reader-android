# R4：社区与审阅能力 —— 规划草案

> 文档状态：规划草案
> 最近更新：2026-06-19
> 代号：R4
> 权威范围：community-api 契约演进、issue/discussion 结构化、review 提交闭环、位置引用模型

## 0. 背景：R3 调研积累的认知

R3 的 community-api 对齐调研识别出一批跨端契约差距。这些差距不是 reader 本地能解决的，必须 R4 在 community-api 与 reader 之间统一对齐。R4 是"让社区模型与 reader 模型真正对接"的阶段。

## 1. 现状基线

### community-api（apps/community-api/）
- 6 个 GET 端点：works、works/:id、works/:id/source、works/:id/revisions/:revisionId/source、works/:id/issues、health
- 1 个 POST 端点：reviews
- 内存 store + seed 数据，无持久化数据库
- issue 直接返回 domain 对象（无 DTO 投影层）
- review POST 不回传完整记录（reviewerName/note/createdAt 丢失）

### reader 已有的社区能力（R2 首轮）
- Review companion：源码全文、播放行、选中行、issue marker、行级气泡
- Issues companion：issue 台账、close/reopen（本地）、issue draft（本地）、查看脚本、跳转播放
- 但这些都依赖本地内存状态，close/reopen/draft 退出即丢（R3 才持久化）

## 2. 已识别的契约差距（R3 调研，R4 解决）

| 差距 | 现状 | R4 方向 |
|---|---|---|
| **issue location 是自由文本** | `"scene: crosswalk"` 概念标签，reader 启发式猜行号 | 结构化 SourceAnchor（见 §3） |
| **IssueSource 枚举不对齐** | API 5 值 / reader 8 值，syntax≠Parser | 统一枚举，API 补全或 reader 收敛 |
| **issue 无 status** | close/reopen 纯 reader 本地 | `POST /issues/:id/actions` action log |
| **issue 无 revisionId** | 绑 workId，跨 revision 不可靠复现 | issue 绑 workId+revisionId |
| **issue 无 createdAt** | 无时间线 | 补 createdAt |
| **review 提交不回传** | POST /reviews 不返回完整记录 | 补 GET /reviews 或 POST 返回完整 |
| **CommentSummary 结构不同** | API {count,preview} / reader {summary,highlights,concerns} | 统一结构 |
| **discussion/thread 未实现** | 两边都无 | R4 新建 |

## 3. 核心设计：结构化 SourceAnchor

**问题**：跳转应绑在位置标签上，而非 issue/discussion 实体。一个 issue 可能指向行号、场景标签、时间段、多位置或无位置。

**方向**：定义结构化位置引用，支持多种形式：

```text
SourceAnchor（概念，非单一表）
  ├─ type: "line" | "scene" | "time" | "range" | "tag" | "none"
  ├─ line?: Int                  （type=line/range）
  ├─ endLine?: Int               （type=range）
  ├─ scene?: String              （type=scene，如 "crosswalk"）
  ├─ timeMs?: Long               （type=time）
  ├─ tag?: String                （type=tag，脚本内自定义标签）
  └─ label: String               （人类可读，如 "十字路口场景"）
```

- issue/discussion/review/笔记携带 `anchors: SourceAnchor[]`（可多个）。
- reader 的跳转行为统一消费 anchor：type=line → 跳源码行；type=time → seek；type=scene → 搜索脚本场景标记；type=none → 不可跳转。
- 这要求 community-api 的 issue `location` 字段从 `string` 演进为 `SourceAnchor[]`（或保留 location string + 新增 anchors 字段做兼容过渡）。

**与 .kmd 脚本的关系**：scene/tag 锚点需要脚本本身支持场景标记语法（如 `@ scene.crosswalk`）。这是 Phase B 语言层面的能力，R4 先定义 anchor 模型，脚本侧标记语法视 Phase B 进度。

## 4. 工作包

### R4-A. issue 契约对齐（community-api + reader）
- community-api：issue 补 `revisionId`、`status`、`createdAt`；IssueSource 枚举对齐
- community-api：issue 的 `location` 演进为 `anchors: SourceAnchor[]`（或兼容过渡）
- reader：ScriptIssue domain + DTO 同步更新
- reader：lineNumberForIssue 启发式替换为 SourceAnchor 解析

### R4-B. issue action log（community-api + reader）
- community-api：`POST /issues/:id/actions`（close/reopen/reopen with reason）
- reader：close/reopen 从纯本地状态改为"本地先记 + 同步云端"
- reader：issue 状态合并云端权威 + 本地 pending

### R4-C. issue 创建（community-api + reader）
- community-api：`POST /works/:id/issues`
- reader：issue draft 提交从纯本地改为"提交云端 + 本地缓冲"
- reader：R3 的 local_issue_overrides synced 字段在这里消费

### R4-D. discussion / thread（community-api + reader）
- community-api：discussion 实体 + `GET /issues/:id/threads` + `POST /issues/:id/threads`
- reader：Issues companion 或独立 companion 展示 discussion
- 区分：issue 是结构化审阅（severity/source/anchor），discussion 是自由讨论（无 severity，纯文本线程）

### R4-E. review 提交闭环（community-api + reader）
- community-api：`POST /reviews` 返回完整记录；补 `GET /works/:id/reviews`
- reader：接上 submitReview（当前是 dead code）；review 决策 UI 收集 note
- review 与 issue 的关系：review 是作品级上架决定，issue 是行级/场景级审阅问题，二者独立但可关联

### R4-F. SourceAnchor 跳转统一（reader）
- 定义 SourceAnchor 解析器：anchor → 可跳转目标（行号 / 时间 / 场景 / null）
- issue/discussion/review/笔记共用同一跳转入口
- UI：anchor 不可解析时显示 label 文本（现有 fallback 行为保留）

### R4-G. 评论摘要统一（community-api + reader）
- CommentSummary 结构对齐
- 浏览页/详情页展示评论摘要

## 5. 依赖与顺序

```text
R4-A issue 契约对齐（含 SourceAnchor 定义）
  ├─→ R4-B issue action log（依赖 A 的 status 字段）
  ├─→ R4-C issue 创建（依赖 A 的 anchor 模型）
  ├─→ R4-F SourceAnchor 跳转（依赖 A 的 anchor 定义）
  └─→ R4-D discussion/thread（依赖 A 的 anchor，可与 B/C 并行）
R4-E review 提交闭环（独立，可并行）
R4-G 评论摘要（独立，低优先）
```

建议顺序：A（契约地基）→ F（跳转消费 anchor）→ B（action log）→ C（创建）→ D（discussion）→ E（review）→ G（摘要）。

## 6. 与 R3 的衔接

- R3 的 `local_issue_overrides` 在 R4 获得 `synced` 语义：本地 close/reopen 提交后标记 synced。
- R3 的 `local_revisions` 在 R4 获得同步链路：本地轻度更改提交为云端新 revision。
- R3 只建本地存储，R4 建云端交互。R4 依赖 R3 的数据层存在。

## 7. 与 Phase B 的衔接

- SourceAnchor 的 scene/tag 类型依赖脚本场景标记语法（Phase B 语言层）。
- R4 先定义 anchor 模型 + line/time 类型；scene/tag 类型待 Phase B 语法就绪后激活。
- 不阻塞：R4-A/B/C/E/G 不依赖 Phase B；只有 anchor 的 scene/tag 解析 + R4-F 的场景跳转依赖。

## 8. 验收

- issue 带 revisionId + status + createdAt + anchors。
- close/reopen 同步云端，离线时本地缓冲。
- issue draft 提交云端成功。
- discussion thread 可查看和回复。
- review 决策可提交并回显。
- SourceAnchor 跳转：行号/时间锚点可跳，场景/标签锚点视脚本支持。
- 枚举值两边一致，无静默丢失。

## 9. 非目标

- 不做完整权限/登录体系。
- 不做实时推送（WebSocket）——issue/discussion 靠 pull。
- 不做推荐算法/短视频预览流（roadmap 列为"未来"）。
- 不做跨 revision 自动验证。
- 不在本阶段实现完整社区互动（关注/粉丝/动态流）。
- SourceAnchor 的 scene/tag 解析依赖 Phase B，R4 只预留模型。

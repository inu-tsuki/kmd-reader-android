# Script Reader And Community Interaction

> 文档状态：产品草案
> 最近更新：2026-05-28

## 1. 目标

本文整理 Android Script Reader 与未来 KMD 社区的交互模式。它回答三个问题：

- Reader 在社区里承担什么角色。
- 阅读、审阅、issue、discussion 如何互相锚定。
- 哪些能力属于当前 Reader，哪些应交给 community-api 或 editor。

长期社区事实模型见主仓库：

- `docs/knowledge/architecture/work-kmd-content-model.md`
- `docs/planning/apps/community-api/collaboration-model.md`

Issue 的提出、跳转和关闭流程见：

- `apps/android-reader/docs/planning/product/issue-lifecycle-flow.md`

## 2. 核心原则

- `community-api` 是社区事实来源，Reader 只是 client。
- Reader 负责“看见作品、播放作品、理解问题、发起讨论或审阅动作”。
- Reader 不直接修改已发布 revision，也不成为源码事实来源。
- 所有 issue、discussion、review decision 都应锚定到 `Work + KmdScriptRevision`。
- 源码行、高亮、播放时间和 issue 引用统一通过 reference/anchor 表达，不把讨论文本塞进 issue 大字段。

## 3. 角色

| 角色 | 主要需求 | Reader 中的入口 |
|---|---|---|
| 普通读者 | 发现作品、阅读/观看、收藏、评论 | 发现流、书架、作品详情、阅读浮层 |
| 作者 | 自查作品、看反馈、回到 editor 修改 | 作品详情、阅读 companion、issue/discussion 跳转 |
| 联合作者 | 理解某段表现、参与讨论、提交修改建议 | 阅读 companion、源码上下文、discussion |
| 志愿审阅者 | 判断能否上架、定位问题、写审阅结论 | Review companion、Issues companion、runtime inspection、review decision |
| 管理者/Moderator | 处理上架提名、冲突和举报 | 后台或 community-web，Reader 只保留轻入口 |

任何人都可以进入轻审阅视角；是否能提名上架、标记通过或关闭 issue，由社区权限决定，不由 Reader UI 硬编码身份。

## 4. 交互场景

### 4.1 发现与浏览

发现流不是普通视频流的复刻。KMD 作品可能是阅读、舞台、互动或分页内容，所以发现流只负责预览和判断是否进入：

```text
Discover feed
  -> Work card / preview
  -> Work detail
  -> Read
  -> Shelf / follow / discussion
```

发现流可以展示：

- 作品标题、作者、标签、presentation mode。
- 评论摘要、issue 数、审核状态。
- 短预览或 runtime 生成的轻量片段。
- 是否已加入书架、是否离线可读。

### 4.2 阅读现场

阅读现场以 Web runtime 为底板，社区交互作为 companion 出现：

```text
ReaderDesk
  -> ReaderRuntimeHost
  -> ReaderChrome
  -> ReaderCompanionContainer
       review | issues | discussion summary | work info | diagnostics
```

Reader companion 可以使用底部 sheet、侧栏、上下信息带或半透明浮层，但它不是 runtime viewport 的一部分，不改变 `.kmd` 设计坐标系。不同 placement 只表示屏幕空间不同，不表示关闭规则不同；所有 companion 应共享系统返回、双指轻点和栏外双击这组关闭契约。

`Review` 和 `Issues` 是当前社区交互的最小双核心：

- `Review`：脚本现场，只看源码、播放行、选中行和 issue marker。
- `Issues`：问题台账，管理当前 work 的 issue、draft、close/reopen 和 future discussion。
- 二者共享 companion 高度和 placement，可以互相跳转，但不同时作为两个长滚动区显示。

### 4.3 审阅现场

审阅者需要在播放现场理解“这个表现怎么回事”，因此 Review companion 的核心不是表单，而是脚本现场：

- 当前播放行。
- runtime timeline marker。
- issue 编号 / marker。
- 源码只读上下文。

Issues companion 承担管理动作：

- 当前 work 所有 issue。
- issue 详情、状态、严重级别、来源和建议。
- close / reopen / draft。
- 查看脚本、跳到播放位置和 future discussion 入口。

当前 Reader 应支持：

```text
playback position
  -> source line highlight
  -> select source range
  -> open Issues to create issue draft
  -> optionally open discussion
```

Reader 不在此阶段直接做源码修改。轻度修改建议只作为 review draft 或 discussion post，后续由 editor 承接。

### 4.4 Discussion 与 Issue

Issue 不拥有 discussion thread。更健康的模型是：

```text
DiscussionThread
  -> DiscussionReference(targetType = issue)
  -> DiscussionReference(targetType = source_range)
  -> DiscussionReference(targetType = playback_time)
```

这样一个 thread 可以同时引用：

- 某个 issue。
- 某段源码范围。
- 某个播放时间或 timeline marker。
- 某个用户、标签或话题。

Reader 中的讨论入口应是“基于上下文发起讨论”，而不是孤立评论框。

## 5. 数据流

推荐数据流：

```text
GET /works
GET /works/:id
GET /works/:id/source
GET /works/:id/issues
GET /works/:id/threads?revisionId=...

Reader Repository
  -> Room cache
  -> ViewModel state
  -> ReaderCompanionContainer

Reader actions
  -> POST /reviews
  -> POST /works/:id/issues
  -> POST /works/:id/threads
```

当前课程/MVP 阶段不要求实现全部接口。已有 `works/source/issues/reviews` 足够支撑演示；thread 和 structured issue 可以先做 mock 或文档约束。

## 6. Anchor 模型

Reader 需要的 anchor 最少包括：

```text
SourceAnchor
  workId
  revisionId
  startLine
  startColumn?
  endLine?
  endColumn?

PlaybackAnchor
  workId
  revisionId
  timeMs?
  progress?
  markerId?
  line?

IssueAnchor
  issueId
  workId
  revisionId
```

Android 本地可以先用轻量模型；提交到社区时映射为 `DiscussionReference` 或 `ScriptIssue.sourceRange`。

## 7. 当前 MVP 边界

当前 Reader 近期做：

- 书架/发现/详情/阅读主流程。
- Review companion 中的脚本查看器。
- Issues companion 中的问题台账。
- 只读源码上下文、播放行高亮、issue 定位。
- issue draft、issue focus、显式跳转和 mock close。
- 审阅结论和轻备注先归入 Issues / future review decision flow，不挤占 Review 源码空间。

暂不做：

- 完整社区讨论流。
- 完整源码编辑器。
- Moderator 后台。
- 多人协作、权限系统和通知系统。
- AI 自动总结或自动审核的正式能力。

## 8. 与 Editor 的交接

Reader 产生的是上下文、问题和建议：

```text
source range
  -> issue / review note / discussion
  -> editor opens same Work revision
  -> author edits working draft
  -> submit new revision
```

Editor 才负责修改、版本提交和高级特效编辑。Reader 不应因为“方便改一行”而变成第二套半成品 editor。

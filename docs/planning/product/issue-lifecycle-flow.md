# Issue Lifecycle Flow

> 文档状态：产品 / UI-4D 草案
> 最近更新：2026-05-27

## 1. 目标

本文定义 Script Reader 中 issue 的提出、跳转和解决模式。它服务 UI-4D 的源码上下文、issue 定位和审阅 companion，也为未来 community-api 的 issue/discussion/revision 模型留出稳定边界。

核心目标：

- 审阅者能从播放现场提出 issue。
- 作者或读者能从 issue 跳回对应表现和源码上下文。
- issue 可以被关闭、重开，并且关闭原因可追溯。
- Reader 不把 issue 变成源码事实来源，也不直接修改发布 revision。

## 2. 核心原则

- Issue 必须锚定到 `workId + revisionId`，否则源码行、播放时间和修复状态无法稳定复现。
- Issue 可以有多个 anchor：源码范围、播放时间、timeline marker、runtime diagnostic。
- Reader 创建的是 issue draft 或 issue action；社区事实由 community-api 保存。
- Close 不是删除。关闭是状态变更，必须保留原因、操作者和时间。
- “已修复”通常意味着新 revision 解决了旧 revision 的问题，而不是修改旧 revision。

## 3. Issue 状态

建议状态：

```text
Draft
  本地草稿，尚未提交到社区。

Open
  已提交，等待作者、审阅者或社区处理。

Confirmed
  已确认是有效问题，可选状态；课程/MVP 可以先不做。

Resolved
  已有处理方案或修复 revision，等待验证。

Closed
  已关闭，不再作为当前待处理问题。

Reopened
  关闭后被重新打开；可以等价为 Open + reopenedAt。
```

建议关闭原因：

```text
fixed
wont_fix
duplicate
not_reproducible
invalid
outdated_revision
deferred
```

MVP 可只显示：

- 打开
- 已解决
- 已关闭

但数据模型应保留 reason，避免后续迁移痛苦。

## 4. Anchor 模型

Reader 本地需要的最小 anchor：

```kotlin
data class IssueAnchor(
    val workId: String,
    val revisionId: String,
    val sourceRange: KmdSourceRange? = null,
    val playback: PlaybackAnchor? = null,
    val markerId: String? = null,
    val runtimeDiagnosticId: String? = null
)

data class PlaybackAnchor(
    val timeMs: Long? = null,
    val progress: Float? = null,
    val line: Int? = null,
    val markerId: String? = null
)
```

提交到 community-api 时可以映射为：

- `ScriptIssue.sourceRange`
- `DiscussionReference(targetType = source_range)`
- `DiscussionReference(targetType = playback_time)`
- `DiscussionReference(targetType = issue)`

## 5. 提出 Issue

### 5.1 入口

Reader 中 issue 可以从四个入口提出：

| 入口 | 默认 anchor |
|---|---|
| 当前播放行 | `playback.line + markerId + timeMs` |
| 源码选中范围 | `sourceRange` |
| runtime inspection issue | `runtimeDiagnosticId + sourceRange?` |
| discussion 上下文 | `threadId + sourceRange/playback` |

### 5.2 UI 流程

```text
Reader / Review Companion
  -> source context 或 playback line
  -> 选择“提出问题”
  -> Issue Draft Sheet
     - severity
     - title / message
     - source range preview
     - playback position preview
     - suggestion / note
  -> 保存草稿 or 提交
```

### 5.3 MVP 表单

MVP 不做复杂模板，只需要：

- severity: Info / Warning / Error
- message
- suggestion
- anchor preview
- submit / cancel

可以自动填充：

- workId
- revisionId
- current playback line
- current markerId
- selected source range

## 6. 跳转 Issue

### 6.1 跳转目标

点击 issue 后不只是展开卡片，而是恢复上下文：

```text
Issue card
  -> Open work detail or reader
  -> Ensure source snapshot
  -> Open Issues companion
  -> Focus issue detail
  -> Offer "查看脚本" to open Review at source range
  -> If playback anchor exists, offer seek/jump to runtime position
```

### 6.2 Reader 内跳转

如果用户已经在阅读同一 work/revision：

```text
onIssueClick(issueId)
  -> set selectedIssueId
  -> open Issues companion
  -> focus issue detail
  -> show "查看脚本" action
  -> show "跳到播放位置" action if playback anchor exists
```

默认不自动 seek。原因是审阅者可能只想看源码，不想打断当前播放。可以提供显式按钮：

- 查看脚本
- 跳到播放位置
- 打开讨论

### 6.3 跨页面跳转

如果 issue 来自作品详情、书架或发现流：

```text
OpenIssue(issueId)
  -> load Work
  -> open Reader desk or Work detail
  -> load source snapshot
  -> open Issues companion
  -> focus issue detail
```

是否自动进入 Reader 可以按入口决定：

- 作品详情 issue 列表：默认打开 Issues companion，但不自动播放。
- 阅读中 issue 列表：保持阅读现场。
- 通知/讨论：优先打开对应 discussion，提供进入 reader 的按钮。

## 7. 解决与关闭

### 7.1 Close 是状态变更

关闭 issue 应创建 action record，而不是删除 issue：

```text
IssueAction {
  id
  issueId
  type: close | reopen | resolve | comment | link_revision
  reason?
  note?
  actorId
  createdAt
  linkedRevisionId?
}
```

MVP 可以先不实现 `IssueAction` 实体，但 UI 和 API 命名应朝这个方向设计。

### 7.2 关闭入口

Reader 中允许的 close 入口：

- issue 卡片上的“关闭”。
- review decision 中的“已处理/建议通过”。
- 作者从 editor 提交修复 revision 后，在 Reader 中确认旧 issue 已解决。

### 7.3 关闭表单

关闭时最少填写：

- reason
- note，可空
- linkedRevisionId，可空

如果选择 `fixed`，推荐要求 linkedRevisionId。没有 revision 时可以暂存为 `resolved`，等待下一次提交。

### 7.4 Reopen

关闭后的 issue 可以重开：

```text
Closed
  -> Reopen
  -> Open
```

重开必须写 note，例如：

- 新 revision 仍复现。
- 关闭时引用错了 revision。
- 用户补充了更明确的 playback anchor。

## 8. UI 状态

Reader ViewModel 近期可以维护：

```kotlin
data class IssueFocusState(
    val selectedIssueId: String? = null,
    val focusedSourceRange: KmdSourceRange? = null,
    val focusedPlaybackAnchor: PlaybackAnchor? = null,
    val issueDraft: IssueDraft? = null,
    val closeDraft: IssueCloseDraft? = null
)
```

先不要求独立文件；可以在 UI-4D 抽 `KmdSourceContextView` 时一起拆。

Issues companion 推荐布局：

```text
Issue list
  - status pill
  - severity pill
  - message
  - location summary
  - jump buttons
  - close/reopen action

Action area
  - view in Review
  - jump playback
  - open discussion
  - close / reopen
```

Review companion 只显示源码现场：

```text
Source viewer
  - playback highlight
  - selected issue marker / number
  - selected source line highlight

Command tray
  - current playback line
  - Review / Issues switch

Source line bubble
  - jump line to playback
  - create issue draft in Issues from selected line / range
  - open Issues focused on marker issue
  - multi-select placeholder
```

## 9. MVP 切片

实现备注（2026-05-28）：UI-4D 首轮已经在 Android Reader 中落地本地闭环，UI-4F 又把 `Review` companion 收束为脚本查看器，UI-4G 新增 `Issues` companion 并迁出 issue 管理。UI-4H 首版已把 Review 的底栏压缩成 `正播放 Lxx | ⇄`，并用源码行上下文气泡承载跳转、提 issue、查看 issue 和多选占位。后端持久化、issue action log 和 discussion thread 仍按本文后续 API 草案推进。

### UI-4D.1：Issue Focus

- 点击 issue 后在 Issues companion 中选中。
- “查看脚本”打开 Review 并聚焦 issue 源码行。
- 当前播放行和 issue 行可以同时高亮。

### UI-4D.2：Jump Actions

- issue 卡片出现“查看脚本”。
- 如果有 playback anchor，显示“跳到播放位置”。
- 源码全文中点击任意行后，可以显式跳到“该行之后第一条可播放脚本行”。
- 源码行跳转不得跳回上一段；如果该行之后没有 timeline marker，则提示不可跳转。
- 跳转不重建 WebView。

### UI-4D.3：Create Issue Draft

- 从当前播放行或源码片段创建本地 issue draft。
- 不提交后端也可以先在 UI 中展示。

### UI-4D.4：Close Draft

- issue 卡片出现关闭动作。
- 先本地 mock close，更新状态和显示原因。
- 后续接 `PATCH /issues/:issueId` 或 `POST /issues/:issueId/actions`。

## 10. 后端/API 草案

短期可沿用：

```text
GET /works/:id/issues
```

下一步建议：

```text
POST  /works/:id/issues
PATCH /issues/:issueId
POST  /issues/:issueId/actions
GET   /issues/:issueId/threads
```

`PATCH /issues/:issueId` 适合课程和 mock 阶段。正式社区更推荐 action log：

```text
POST /issues/:issueId/actions
{
  "type": "close",
  "reason": "fixed",
  "note": "Fixed in revision rev-12",
  "linkedRevisionId": "rev-12"
}
```

## 11. 非目标

- 不在 Reader 内直接编辑源码。
- 不让 close 自动修改 revision。
- 不把 discussion thread 挂成 issue 子对象。
- 不做权限系统细节。
- 不做跨 revision 自动验证。

## 12. 验收

- 从播放行或源码范围可以形成 issue draft。
- 点击 issue 能定位源码行。
- 有 playback anchor 的 issue 可以显式跳到播放位置。
- 关闭 issue 后仍可查看历史信息和关闭原因。
- 已关闭 issue 可以重开。
- 所有操作都不要求 WebView 重建。

# R3-H 详情页续读状态实施计划

> 状态：已落地
> 最近更新：2026-07-11
> 依赖：R3-B 进度持久化、R3-F 书架状态投影、R3-G 书架并发收束

## 1. 目标

让作品详情页准确表达用户下一次打开作品时会发生什么：可恢复同一版本的中段进度时显示「继续阅读」，否则显示「开始阅读」。有可恢复进度时展示简短的位置摘要。

本切片只补齐详情页展示状态。点击后继续复用 `OpenReader` 和 Ready 后的 `restoreSeekOnReady()`，不新增导航 action，不在详情页主动 seek。

## 2. 产品语义

### 2.1 按钮状态

| 本地状态 | 主按钮 | 辅助摘要 |
|---|---|---|
| 无 entry，或 `progress <= 0` | 开始阅读 | 无；若只有 `lastReadAt`，可仅显示上次阅读时间 |
| `0 < progress < 1`，revision 兼容 | 继续阅读 | `已读 N% · 上次阅读 今天/X 天前/X 个月前`；无时间时只显示百分比 |
| `progress >= 1` | 开始阅读 | `已读完`，可附上次阅读时间 |
| `0 < progress < 1`，保存 revision 与当前 revision 均非 null 且不相等 | 开始阅读 | `作品已更新，将从头开始` |

revision 兼容规则必须与 `restoreSeekOnReady()` 一致：任一 revision 为 null 时向后兼容，视为可恢复；只有双方非 null 且不同才拒绝恢复。

百分比使用 clamp 后的整数值，避免异常持久化值泄漏到 UI。未来若要显示播放时间，应另行扩展；R3-H 不把 nullable `readingTimeMs/readingDurationMs` 引入详情页契约。

### 2.2 时间格式

将 MineDesk 现有的粗粒度时间格式抽成纯函数并共享：调用方显式传入 `now`，测试不得依赖系统时钟。

- 当天或未来时间：`今天`
- 1 天：`1 天前`
- 2-29 天：`N 天前`
- 30 天及以上：`N 个月前`

## 3. 状态与实现边界

### 3.1 单一状态流

详情页只消费 `KmdReaderState.shelfState`，不得在 Composable 或 `KmdReaderApp` 中直接访问 Repository/DAO。

`shelf` 与 `history` 是互斥分组：已上架且读过的 entry 位于 `shelf`，不在 `history`。按 `currentWorkId` 查询详情状态时必须覆盖两组，建议在 `ShelfState` 提供唯一的 lookup/helper，避免调用方重复拼接规则。

### 3.2 最小数据扩展

在 `ShelfItem` 增加 `activeRevisionId`，由 `LocalLibraryEntry.toShelfItem()` 投影。当前 revision 使用 `selectedWork.script.activeRevisionId`。不增加 Room schema、DAO 方法或额外异步加载。

新增纯展示模型，例如 `WorkDetailReadingState`，集中持有：

- `buttonLabel`
- `summary: String?`
- `canResume`（若模型内部需要）

展示决策放在纯函数中，输入至少包含 `ShelfItem?`、当前 revision 和 `now`。`WorkDetailDesk` 只渲染模型并继续回调既有 `onOpenReader`。

### 3.3 UI 约束

- 摘要放在主操作区、按钮组之前，使用次级正文颜色。
- 保留现有 `FlowRow`，窄屏和大字体下按钮可换行。
- 不改变 Reader host、desk 导航或 shelf toggle 的所有权。
- 不为了本功能重建 `ReaderRuntimeHost`。

## 4. 实施步骤

1. 扩展 `ShelfItem` 的 revision 投影，并为 `ShelfState` 增加跨 shelf/history 的 workId 查询。
2. 抽取可注入 `now` 的共享相对时间格式函数，迁移 MineDesk 使用点，保持现有文案不变。
3. 增加详情页阅读展示纯模型与 resolver，完整编码进度、完成态和 revision 兼容规则。
4. 在 `KmdReaderApp` 组装当前作品对应的展示模型，传入 `WorkDetailDesk`。
5. 更新详情页主按钮和摘要；点击仍只 dispatch `OpenReader`。
6. 添加纯函数单测与必要的状态集成测试，更新 R3 状态文档。

## 5. 回归矩阵

至少覆盖：

- 无 entry。
- `progress = 0`。
- `progress` 位于 `(0, 1)`，revision 相同。
- 保存或当前 revision 为 null 的向后兼容路径。
- 两个非 null revision 不同。
- `progress = 1` 及越界值。
- `lastReadAt = null`。
- 仅有 `lastReadAt`、但 progress 为 0。
- 已上架且有进度的 entry（只存在于 shelf）。
- 未上架的历史 entry（只存在于 history）。
- 切换作品后详情展示随 `currentWorkId` 更新。
- 时间边界：今天、1 天、29 天、30 天，以及未来时间。

## 6. 验收与门禁

- 详情页状态与 Ready 后实际恢复行为一致，不出现「继续阅读」但实际因 revision 变化从头开始。
- 详情页点击继续复用既有加载与恢复链路。
- 书架卡片现有按钮、时间文案和分组行为无回归。
- `./gradlew :app:testDebugUnitTest --rerun-tasks`
- `./gradlew :app:assembleDebug`
- `git diff --check`

实现完成后同步：

- `docs/planning/r3-local-reader-plan.md`：补 R3-H 落地记录与测试数。
- `docs/planning/README.md`：将 R3-H 标为已落地。
- `docs/knowledge/architecture/page-architecture.md`：更新 R3-H/I 的待办状态。

## 7. 非目标

- 不修改进度持久化或恢复算法。
- 不新增「继续阅读」action 或手动 seek 路径。
- 不引入新的 Room 查询、表或 migration。
- 不显示精确播放时间，不处理按章节/段落的位置摘要。
- 不实现 R3-I 阅读偏好或 R3-J 笔记/书签。

## 8. 落地记录（2026-07-11）

- `ShelfItem` 已投影 `activeRevisionId`，`ShelfState.findByWorkId()` 覆盖互斥的 `shelf` 与 `history` 分组；详情页只读取该状态快照。
- `WorkDetailReadingState` 的纯 resolver 与 `restoreSeekOnReady()` 使用相同 revision 兼容规则。详情页继续复用 `OpenReader`，Ready 后恢复链路仍是唯一 seek 所有者。
- 相对时间格式抽为显式 `now` 的纯函数，MineDesk 保持原有文案。纯 JVM 测试覆盖进度/revision/时间边界、两分组 lookup 与切换作品后无状态残留。

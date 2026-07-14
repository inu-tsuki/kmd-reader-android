# R3-H 实现者提示词

你正在 `/home/inu-tsuki/projects/playground/kmd/apps/android-reader` 实现 R3-H「详情页继续阅读按钮态」。请直接完成代码、测试与文档更新，不要只给方案，也不要提交或推送，等待主审查者复核。

开始前完整阅读：

- `docs/planning/r3-h-work-detail-continuation-plan.md`
- `docs/planning/r3-local-reader-plan.md` 的 R3-B、R3-F、R3-G、R3-H 段落
- `docs/knowledge/architecture/page-architecture.md` 的书架与作品详情契约
- `app/src/main/java/com/example/kmd_reader/presentation/KmdReaderViewModel.kt` 中 `restoreSeekOnReady()` 和 `refreshShelfNow()`
- `WorkDetailDesk.kt`、`KmdReaderApp.kt`、`ShelfModels.kt`、`MineDesk.kt`

实现目标：

1. 详情页仅在 `0 < readingProgress < 1` 且 revision 兼容时显示「继续阅读」，否则显示「开始阅读」。
2. revision 兼容规则必须镜像 `restoreSeekOnReady()`：保存与当前 revision 都非 null 且不同才不兼容；任一为 null 时兼容。
3. 可恢复时显示 `已读 N% · 上次阅读 ...`；无 `lastReadAt` 时只显示百分比。
4. `progress >= 1` 显示「开始阅读」和 `已读完`；revision 不兼容显示「作品已更新，将从头开始」。
5. 点击主按钮仍走既有 `OpenReader`，由 Ready 后恢复链路负责 seek。不得新增 continue action 或自行 seek。

架构约束：

- 不在 Composable 或 `KmdReaderApp` 直接查询 Repository/DAO。
- 只通过 `KmdReaderState.shelfState` 获取本地状态。
- 已读 entry 可能在 `shelf` 或 `history`；按 workId lookup 必须覆盖两组，不能只查 history。
- 给 `ShelfItem` 增加 `activeRevisionId` 并从 `LocalLibraryEntry` 投影；不改 Room schema。
- 将展示判断收敛为纯模型/纯 resolver，`WorkDetailDesk` 只负责渲染。
- 抽取 MineDesk 的相对时间格式为共享纯函数，显式传入 `now`；保持 MineDesk 现有文案不变。
- 保留详情页 `FlowRow` 的窄屏/大字体换行能力；摘要使用次级正文样式。
- 不改 Reader host 生命周期、进度写入、恢复算法、书架 toggle 或 generation token。

测试要求：

- 为纯 resolver 覆盖：无 entry、0、中段、1、越界、null revision、相同 revision、不同 revision、null lastReadAt、仅 lastReadAt。
- 覆盖 shelf/history 两个互斥分组的 lookup，尤其是 `onShelf=true` 且有进度的作品。
- 覆盖相对时间的今天、1 天、29 天、30 天、未来时间边界。
- 覆盖切换 currentWorkId 后详情模型不会沿用上一作品状态。
- 若现有 JVM 测试环境不适合 Compose UI 测试，优先通过纯展示模型测试保证语义，不要为了本切片引入脆弱的 UI 测试框架。

文档收尾：

- 在 `r3-local-reader-plan.md` 写 R3-H 落地记录、实现边界与测试覆盖。
- 更新 `docs/planning/README.md` 和 `page-architecture.md` 的 R3-H 状态，但不要提前把 R3-I 标为完成。
- 值得长期保留的实现决策写入 docs，不把临时审查叙事塞进代码注释。

完成前运行：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks
./gradlew :app:assembleDebug
git diff --check
```

最终汇报：改动文件、状态模型与 revision 语义、测试结果、尚存风险。不要提交或推送。

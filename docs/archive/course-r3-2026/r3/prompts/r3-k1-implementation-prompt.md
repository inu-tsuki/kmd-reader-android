# R3-K1 实现者提示词

你正在 `/home/inu-tsuki/projects/playground/kmd/apps/android-reader` 实现 R3-K1 书架体验收束。
请直接完成代码、测试和必要文档更新，不要只输出方案；不要提交、推送或修改主仓库，等待
主审查者复核。

开始前完整阅读：

- `docs/planning/r3-k-bookshelf-experience-plan.md`
- `docs/knowledge/architecture/ui-design.md` 的 Bookshelf As Library
- `docs/knowledge/architecture/page-architecture.md` 的书架/Desk 边界
- `docs/planning/r3-local-reader-plan.md` 的 R3-F/G/H/K 与并发审查记录
- `MineDesk.kt`、`ShelfModels.kt`、`KmdReaderApp.kt`
- `KmdReaderState.kt`、`KmdReaderViewModel.kt` 的 `refreshShelfNow()` / `toggleShelf()`
- `ImportState`、`LocalLibraryEntry` 与 `WorkDetailReadingState.kt`

只实现 K1，不开始 K2。

必须交付：

1. 建立纯 Kotlin 书架 UI 投影：从 `ShelfState + BookshelfView + nowMillis` 派生 continue、
   local shelf、network-required shelf、history 和空状态。命名可调整，但 Composable 不得自行
   解释 `onShelf/lastReadAt/hasLocalSource`，也不得查询 Repository。
2. continue 候选合并 shelf/history，要求 `0 < progress < 1` 且 `lastReadAt != null`，按最近
   阅读排序、workId 去重、最多 3 项。不要复制 revision 兼容规则；最终恢复由现有 Reader
   链路裁决。
3. 重构 `MineDesk`：顶部书架工具栏、Library/History segmented control、continue 区、
   本地可读与需联网分组，以及独立空状态。不要继续把书架和历史作为两个完整列表串联。
4. 顶部导入和设置使用 Material icon button，并提供 content description 与 tooltip；若项目
   缺少 icons artifact，通过 version catalog 增加 Compose Material icons 依赖，不手绘 SVG。
5. 卡片展示标题、作者、mode、来源状态、进度、相对时间；整卡打开详情，continue 是独立
   动作且不能双触发。完成态不显示 continue。
6. 将现有 `ImportState` 接入书架顶层：Importing 有稳定提示，Failed 显示摘要与重新进入导入
   流程的真实动作，Idle 不占空间。不要假装失败属于某个 ShelfItem。
7. 手机竖屏优先，窄屏单列；宽屏/横屏可使用自适应 grid。页面只保留一个主滚动容器，禁止
   嵌套滚动卡片/列表。大字体、长标题、长作者名不能与 badge 或动作重叠。
8. 更新 R3-K 落地记录和相关 roadmap 状态，但不要把 K2 标为完成。

现有事实边界：

- `hasLocalSource=true` 只能标“本地可读”；false 标“需联网”，不代表设备当前离线。
- 当前没有可靠事实区分“收藏”与“缓存”，不要创建这两个分组。
- `ShelfState.shelf/history` 是互斥组；不要改变 Repository 查询语义。
- `BookshelfView` 是页面瞬时状态，可用 `rememberSaveable`，不进 DataStore/ViewModel。
- 相对时间必须从可注入 `nowMillis` 派生，纯测试不能依赖 `System.currentTimeMillis()`。

严格禁止：

- 不改 Room schema、DAO、Repository 或 R3-F/G/H 的 refresh generation/mutex/字段级 UPDATE。
- 不新增 ConnectivityManager、网络监听、缓存下载协议或新的 source availability 推断。
- 不实现排序、筛选、移出书架、删除作品、删除 revision 或清缓存。
- 不放不可用占位按钮，不把关于/项目说明复制进书架正文。
- 不为了 UI join 社区 `WorkRepository`，不把整个派生 UI model 存进 `KmdReaderState`。
- 不创建嵌套 cards，不用超大标题或营销式 hero，不引入新单色视觉体系。

测试至少覆盖：

- 空状态与 Library/History 视图投影。
- continue 合并、排序、去重、take(3)，以及 progress=0/1/越界、lastReadAt=null 的排除。
- local/network 分组且不修改输入。
- 注入时钟下的相对时间边界。
- 导入、设置、详情、continue callback 各触发一次；segmented view 切换稳定。
- ImportState Idle/Importing/Failed。

视觉验收：

- 至少验证并保存：有内容手机竖屏、空书架、空历史、大字体/窄屏、横屏/宽屏五组截图或
  等价证据。
- 检查文本/按钮不重叠，最长标题和作者名不溢出，工具图标有可访问描述。
- 若环境无法运行模拟器，明确报告未完成的视觉 gate，不得声称 UI 已完整验收。

完成前运行：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks
./gradlew :app:assembleDebug
git diff --check
```

最终汇报必须列出：改动文件、投影规则、测试结果、截图/viewport 清单、仍未验证的风险。

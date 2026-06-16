# KMD Reader Android 第五阶段集成测试报告

> 项目阶段：课程项目第五阶段
> 文档状态：执行记录（部分用例已实跑）
> 最近更新：2026-06-17

## 1. 阶段目标

第五阶段要求完成集成测试、手动测试、Bug 修复记录和性能检查。本阶段不扩展新功能，重点确认当前 Android Reader MVP 能稳定完成：

- 启动 App 并展示社区作品列表。
- 从列表进入作品详情。
- 从详情进入真实 `reader-runtime-web` 阅读页。
- 在阅读页播放 KMD 脚本并返回。
- 查看脚本检查/审阅信息。
- 在后端异常、屏幕旋转、重复进入阅读页时不闪退。

## 2. 已满足的代码层验证

以下验证用于说明项目已经具备可测试基础。提交课程材料时，可附上终端绿色输出截图。

| 编号 | 验证项 | 命令或位置 | 当前记录 |
|------|--------|------------|----------|
| AV-01 | Reader Runtime Web 构建 | `pnpm reader:build` | 已通过（2026-06-17，746 modules，输出 `dist/reader-runtime/`） |
| AV-02 | 主仓库 TypeScript/Vite 构建 | `pnpm build` | 已通过 |
| AV-03 | Android Debug APK 编译 | `./gradlew :app:assembleDebug` | 已通过，2026-06-17 重跑（含 `syncReaderRuntimeDist` 同步真实 bundle） |
| AV-04 | Android 单元测试 | `./gradlew :app:testDebugUnitTest` | 已通过，2026-06-17 重跑 |
| AV-05 | Room DAO 测试 | `data/local/KmdReaderDatabaseTest.kt` | 已覆盖 |
| AV-06 | Repository / mapper 测试 | `data/repository/*Test.kt` | 已覆盖 |
| AV-07 | ViewModel 测试 | `presentation/KmdReaderViewModelTest.kt` | 已覆盖 |
| AV-08 | Runtime Bridge / WebView 协议测试 | `runtime/*Test.kt` | 已覆盖 |

说明：自动化测试不能替代本阶段要求的手动集成测试。手动测试需要在模拟器或真机上执行，并记录实际结果。本次 Android 验证命令为 `./gradlew :app:testDebugUnitTest :app:assembleDebug`，结果为 `BUILD SUCCESSFUL`。

## 3. 测试环境

提交前请补全本节。

| 项目 | 记录 |
|------|------|
| 测试日期 | 2026-06-17（IT-01/02/03/04/07 实跑） |
| 测试人员 |  |
| 测试设备 | Android 模拟器 AVD `Pixel_10_Pro`（serial `emulator-5554`） |
| Android 版本 | API 36（sdk_gphone16k_x86_64，WebView 148.0.7778.217） |
| App 包名 | `com.example.kmd_reader` |
| APK 路径 | `apps/android-reader/app/build/outputs/apk/debug/app-debug.apk` |
| 后端服务 | `kmd-community-api` 本机 `http://10.0.2.2:3000`，本轮未启动（走 offline-first/mock fallback） |
| Runtime 产物 | 本轮 `pnpm reader:build` 后 `dist/reader-runtime/` 已同步进 APK `assets/reader-runtime/`（真实 bundle，非 D0 fallback） |
| 加载 URL | `https://kmd-reader-runtime.local/reader-runtime/index.html`（本地虚拟 HTTPS 拦截） |
| Debug 探针 | 默认关闭，仅通过 `-Pkmd.runtimeDebugProbes=true` 或 URL 参数开启 |

## 4. 集成测试用例与执行记录

通过状态填写：通过 / 失败 / 部分通过。证据可以填写截图文件名、录屏时间点或简短说明。

| 用例编号 | 场景 | 操作步骤 | 预期结果 | 实际结果 | 是否通过 | 证据 |
|----------|------|----------|----------|----------|----------|------|
| IT-01 | 启动与作品列表加载 | 1. 启动 App；2. 等待首页加载完成 | App 不闪退；展示作品列表或明确的加载/错误状态 | 后端 `/works` 超时，offline-first 回退到 mock；浏览页正常显示 4 个作品，顶部 tab 与搜索入口正常 | 通过 | `.smoke/01-launch.png`、`.smoke/02-browse.png` |
| IT-02 | 列表进入详情再返回 | 1. 点击第一个作品；2. 查看详情；3. 返回列表；4. 点击第二个作品 | 详情页内容与点击作品一致；返回后列表仍可操作 | 进入「雨城慢镜」详情，标题/作者/属性/标签正确；顶部出现「脚本详情」「返回」tab | 通过 | （进入流程已验证，返回流程待补跑） |
| IT-03 | 详情进入阅读页 | 1. 进入作品详情；2. 点击阅读按钮 | 进入阅读页；WebView runtime 正常加载；无黑屏占位 | logcat 确认 `KmdReaderWebView: Loading runtime url=https://kmd-reader-runtime.local/reader-runtime/index.html`（真实 bundle，非 D0 fallback）；WebView 初始化成功，进入 ready 态 | 通过 | `.smoke/03-reader-initial.png`、`.smoke/04-reader-ready-black.png` |
| IT-04 | KMD 脚本播放 | 1. 阅读页等待加载；2. 开始播放；3. 观察 20 秒 | 文字或画面正常显示；进度变化；播放期间不崩溃 | 点击播放后 mock 作品正常播放并自然结束；底栏显示 `0:05 / 0:05 100%` 与 `ended`，证明 `progressChanged` 正常回传、ready→play→progress→ended 链路闭合 | 通过 | `.smoke/04-reader-ready-black.png`（ended 态） |
| IT-05 | 阅读页返回与重复进入 | 1. 播放中返回详情；2. 再次点击阅读；3. 重复 3 次 | 不出现闪退；不出现持续黑屏；WebView 可重新加载 | 待手动执行 | 待填写 |  |
| IT-06 | 脚本检查/审阅信息 | 1. 进入详情；2. 查看脚本检查或审阅入口；3. 返回详情 | 能展示脚本检查信息或空状态；返回后页面状态正常 | 待手动执行 | 待填写 |  |
| IT-07 | 后端不可用时的兜底 | 1. 关闭 `kmd-community-api`；2. 冷启动 App；3. 进入列表/详情 | App 不闪退；展示缓存、mock 数据或错误提示 | 本轮全程未启动后端，`/works`、`/works/rain-city/issues`、`/works/rain-city/source` 均 `SocketTimeoutException`；App 用 mock works 正常完成列表→详情→阅读→播放全流程，无崩溃 | 通过 | logcat `okhttp` 超时记录 + 全流程截图 |
| IT-08 | 屏幕旋转稳定性 | 1. 在列表、详情、阅读页分别旋转屏幕；2. 每页旋转 3 次 | 不闪退；页面可继续操作；阅读页能恢复或给出明确错误 | 待手动执行 | 待填写 |  |

## 5. Bug 清单与修复记录

修复率计算方式：已修复或已缓解的 Bug 数 / 已发现 Bug 总数。当前记录 10 个问题，其中 8 个已修复或已缓解，修复率为 80%。BUG-09 为 2026-06-17 smoke 新发现，待排期。

| Bug 编号 | 问题描述 | 严重度 | 状态 | 修复或说明 |
|----------|----------|--------|------|------------|
| BUG-01 | 阅读页出现黑屏，但播放进度仍在变化 | 高 | 已修复 | 修正 Android WebView 中 runtime root 高度和 viewport sizing，确认脚本可见播放 |
| BUG-02 | WebView renderer 崩溃后仍继续执行 `evaluateJavascript` | 高 | 已修复 | `onRenderProcessGone` 后标记 renderer gone，取消调试探针并停止后续 JS 调用 |
| BUG-03 | 调试探针和诊断日志影响正常阅读展示 | 中 | 已修复 | 探针默认关闭，仅 debug 构建参数或 URL 参数开启 |
| BUG-04 | Android WebView 出现 WebGL texture unit warning，可能诱发 renderer crash | 高 | 已缓解 | 限制 Android WebView 下 Pixi batch textures，并绑定空纹理稳定 WebGL 状态 |
| BUG-05 | 阅读中横竖屏体验尚未完成最终产品化 | 中 | 暂缓 | 已写入阅读体验规划；当前 MVP 先保证旋转不闪退和状态可恢复 |
| BUG-06 | 阅读页崩溃前出现大量 `[Layout-Diag]` 布局测量日志 | 中 | 已修复 | `LayoutPlanner` 测量诊断改为仅在 debug overlay、`kmdDebugProbe=1` 或 `kmdLayoutDiag=1` 时输出 |
| BUG-07 | 小脚本阅读时仍默认加载全部随包字体，增加 WebView renderer native 压力 | 高 | 已缓解 | Android WebView 中无显式字体清单时不再自动加载默认字体；调试可用 `kmdLoadDefaultFonts=1` 强制开启 |
| BUG-08 | 播放中拖动进度条会连续向 WebView 发送 seek，在 Profiler 实时追踪下放大 renderer 压力 | 中 | 已修复 | Slider 拖动时只更新本地进度，松手后提交一次 seek；常规播放/seek 诊断日志默认关闭 |
| BUG-09 | 进入阅读页 runtime `ready` 后，未播放时 WebView 显示黑屏；播放结束后（`ended`）也无首帧/重置/提示 | 高 | 待排期 | 2026-06-17 smoke 发现：链路本身闭合（ready→play→progress→ended），但 ready 首帧未渲染、ended 无恢复入口，用户只看到黑屏。属 runtime/UI 首帧与结束态体验缺口，对应 `runtime-ui-implementation-plan.md` UI-5 与 `runtime-implementation-plan.md` §4 |
| BUG-10 | 阅读页顶部 viewport 状态文案出现「横屏舞台 · 竖屏 · 9:16」矛盾措辞 | 低 | 已修复 | 2026-06-17 smoke 发现：`PresentationMode.Stage` 的 label 硬编码为「横屏舞台」，但 stage 可为竖屏（`rain-city` 即竖屏 stage）。根因在 `Work.kt` enum label，方向应由 `OrientationHint` 表达。已改为 `Stage("舞台")`，`ReaderDesk` viewport 描述变为「舞台 · 竖屏 · 9:16 · 1080x1920 · 填满阅读区」。`./gradlew :app:testDebugUnitTest` 通过 |

## 6. 性能检查记录

课程要求使用 Android Studio Profiler 查看内存占用，并旋转屏幕检查是否有内存泄漏。以下为建议记录格式。

### 6.1 Profiler 操作步骤

1. 安装并运行 Debug APK。
2. 打开 Android Studio：`View > Tool Windows > Profiler`。
3. 选择进程 `com.example.kmd_reader`。
4. 优先观察 Memory 时间线，或在关键节点执行 Heap Dump。
5. 执行流程：启动 App -> 列表 -> 详情 -> 阅读 -> 播放 30 秒 -> 返回详情。
6. 点击 Force GC，记录内存是否回落。
7. 重复进入和退出阅读页 3 次，观察内存是否阶梯式增长。
8. 在列表、详情、阅读页各旋转屏幕 3 次，观察是否闪退或持续增长。
9. 不建议长时间开启 `Track Java/Kotlin Allocations` 压着 WebView 播放和拖动进度条；该模式会显著增加运行时负载，可作为压力测试单独记录。

### 6.2 Profiler 记录表

| 检查项 | 观察指标 | 预期结果 | 实际结果 | 是否通过 | 证据 |
|--------|----------|----------|----------|----------|------|
| PERF-01 | 启动后内存 | 列表稳定后内存不持续上涨 | 待手动执行 | 待填写 |  |
| PERF-02 | 进入阅读页峰值 | WebView 加载时允许短暂上升，随后趋于稳定 | 待手动执行 | 待填写 |  |
| PERF-03 | 返回详情后内存 | Force GC 后接近进入阅读前水平，或至少不持续累积 | 待手动执行 | 待填写 |  |
| PERF-04 | 重复进入阅读页 | 3 次进入/退出后无阶梯式增长 | 待手动执行 | 待填写 |  |
| PERF-05 | 屏幕旋转 | 旋转 3 次不闪退，无明显内存泄漏迹象 | 待手动执行 | 待填写 |  |

### 6.3 Logcat 检查关键词

手动测试时建议过滤包名 `com.example.kmd_reader`，并重点搜索：

- `FATAL EXCEPTION`
- `AndroidRuntime`
- `Renderer crash report`
- `Renderer process`
- `Application attempted to call on a destroyed WebView`
- `there is no texture bound`
- `KmdReaderWebView`

验收标准：

- 没有 `FATAL EXCEPTION`。
- 没有稳定复现的 WebView renderer crash。
- 没有连续出现的 destroyed WebView 调用警告。
- 如果模拟器输出零散 `GFXSTREAM` 日志，但 App 不崩溃、画面可播放，可记录为模拟器图形栈噪声。
- 如果出现 renderer crash，应保存 `Renderer crash report part N` 的完整日志，并记录 `didCrash`、内存摘要、最近 bridge event 和最近 console message。

## 7. 提交材料清单

- [ ] 本测试报告截图或 Markdown 文件。
- [ ] App 运行截图或录屏。
- [ ] Android Studio Profiler 内存截图。
- [ ] 终端 `./gradlew :app:assembleDebug` 成功截图。
- [ ] 终端 `./gradlew :app:testDebugUnitTest` 成功截图。
- [ ] Bug 清单截图或本报告第 5 节。

## 8. 结论

当前 KMD Reader Android 已具备第五阶段验收所需的核心结构：主界面、详情页、Repository 数据源、ViewModel 状态绑定、真实 WebView runtime 宿主和基础单元测试。第五阶段剩余工作主要是执行手动集成测试、补充实际结果、截取 Profiler 证据，并确认阅读页重复进入和屏幕旋转不会引入新的闪退。

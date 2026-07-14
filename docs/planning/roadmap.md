# KMD Reader Android Roadmap

> 文档状态：维护休眠；课程至 R3 开发阶段已完成
> 最近更新：2026-07-14

## 当前判断

Android Reader 已完成本轮课程开发与 R3 本地阅读器阶段。仓库保留为 KMD 的 Android
阅读宿主、移动端集成样本和未来社区客户端原型，但在 KMD 社区契约稳定前不再连续扩张。

本轮正式完成边界是 **R0–R2 已交付骨架与稳定化成果，加 R3-A–I 和 R3-K1**。R3-J、
R3-K2 以及删除、缓存和云端能力不是未完成的 R3 验收项，而是未来重新立项时评估的增强。

## 已冻结基线

- Compose 应用：书架/历史、发现、详情、导入、阅读、设置和 Review / Issues companion。
- 本地阅读：裸 `.kmd` 与 `.kmdwork` SAF 导入、私有 bundle store、bundle asset host、
  本地 revision 播放优先级和离线阅读。
- 阅读状态：进度节流保存与恢复、收藏/移出书架、详情续读、真实历史、issue draft 防丢。
- 阅读偏好：DataStore 持久化主题、字号、自动保存和 reduced motion；Android 负责持久化、
  UI 与协议投影，最终 runtime 语义仍由主仓库负责。
- 书架体验：纯 UI 投影、Library/History 分段、继续阅读、本地可读/需联网分组、导入状态、
  响应式网格、大字体和 display cutout 安全区。
- Runtime 宿主：真实 `reader-runtime-web` artifact、WebView bridge、全屏 reader、错误诊断、
  renderer 恢复入口和 viewport 策略。

R3-K1 最终视觉证据与课程阶段执行记录位于
[`../archive/course-r3-2026/`](../archive/course-r3-2026/README.md)。

## 长期不变量

- Android 是宿主，不重写 KMD parser、layout、effect 或 player。
- Web runtime 是播放语义的唯一解释核心。
- 宿主偏好不得重写作者构图；`fontScale` 不改变 Stage / Interactive 的设计舞台。
- `.kmd` 与 bundle 内容是播放事实；平台 `Work` 元数据不替代脚本语义。
- 破坏性动作必须先定义 Room、bundle、revision、draft 与活跃 session 的一致删除边界。
- 社区事实由未来 community API 权威化；本地 draft 只是防丢缓冲，不代表 close/reopen 或提交已同步。

## 未来恢复入口

未来重新开发时不要直接续写旧 R3 字母切片。先执行
[`post-r3-reentry-backlog.md`](post-r3-reentry-backlog.md) 的恢复检查，重新核实主仓库 runtime、
社区契约、Android 工具链和数据库现状，再定义新的阶段与验收。

恢复前不要：

- 依据归档 R4 草案直接实现 API 或 schema。
- 把 Android 重新变成 Phase B、社区后端或 runtime 重构的入口 gate。
- 在没有数据生命周期设计时添加删除作品或清缓存按钮。
- 将 debug Release 当作生产签名或应用商店发布物。

## 冻结验证线

最终基线至少保持：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks
./gradlew :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
git diff --check
```

最终源码、runtime commit、工具链、APK SHA-256 和已知限制记录在 GitHub `r3-final` Pre-release。

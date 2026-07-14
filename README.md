# KMD Reader Android

KMD Reader Android 是 KMD 的移动端阅读宿主，也是 2026 Android 课程项目的最终实现。

课程至 R3 的连续开发已经结束，仓库当前处于维护休眠状态。最终基线完成了本地导入、书架、
阅读进度、全局偏好、Web runtime 宿主与轻量审阅骨架；未来将在 KMD 社区契约稳定后重新评估
Android 侧开发，而不是继续扩张旧 R3 切片。

## 已交付能力

- 导入裸 `.kmd` 与含 assets 的 `.kmdwork`，存入应用私有存储并离线阅读。
- 书架与阅读历史分离，支持收藏、详情续读、继续阅读、本地可读/需联网状态和响应式布局。
- 节流保存与恢复阅读进度；issue draft 退出防丢。
- DataStore 持久化主题、字号、自动保存和 reduced motion 偏好。
- WebView 加载真实 `reader-runtime-web`，闭合 load/ready/play/pause/seek/progress/ended 协议链路。
- 阅读 chrome、Review / Issues companion、源码上下文、viewport 策略和 renderer 错误恢复骨架。
- Room、Retrofit/OkHttp、Repository、ViewModel/StateFlow、Compose 和自动化回归测试。

Android 只负责宿主、业务状态和移动端 UI；KMD parser、layout、effect 与 player 语义由 Web runtime
唯一解释。宿主字号等偏好不得重写 Stage / Interactive 的作者构图。

## 项目结构

```text
app/src/main/java/com/example/kmd_reader/
  data/             Room、remote API、本地 bundle/revision 与 repositories
  domain/           领域模型和导航策略
  presentation/     UI state、effects 与 reducer
  runtime/          Reader Runtime bridge、协议与 WebView host
  ui/               Compose 应用、页面、组件与主题
docs/
  planning/         冻结 roadmap 与未来重启入口
  knowledge/        长期架构和集成事实
  archive/          课程至 R3 的计划、执行记录与证据
```

## 构建与验证

当前冻结工具链为 JDK 21、Gradle 9.4.1、AGP 9.2.1、Kotlin 2.2.10、compile SDK 36.1、
min SDK 28 和 target SDK 36。

Android 构建会在父 KMD 仓库存在 `dist/reader-runtime/` 时把真实 runtime 同步进 APK；否则只保留
D0 fallback。发布或集成验证前应先在 KMD 主仓库构建 runtime：

```bash
cd ../..
pnpm reader:build
cd apps/android-reader
./gradlew :app:testDebugUnitTest --rerun-tasks
./gradlew :app:assembleDebug
```

设备/模拟器可用时再运行：

```bash
./gradlew :app:connectedDebugAndroidTest
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 文档与最终版本

- [文档索引](docs/README.md)
- [冻结 Roadmap](docs/planning/roadmap.md)
- [Post-R3 重启 Backlog](docs/planning/post-r3-reentry-backlog.md)
- [课程至 R3 历史归档](docs/archive/course-r3-2026/README.md)
- [应用架构](docs/knowledge/architecture/app-architecture.md)

最终稳定点通过 Git tag `r3-final` 和同名 GitHub Pre-release 标记。Release 附带 debug-signed APK、
Android/runtime commit、验证结果与 SHA-256；它是课程阶段可安装快照，不是生产签名发布物。

## License

本仓库与 KMD 主仓库一致，使用 [Apache License 2.0](LICENSE)。第三方依赖继续遵循各自许可证。

## 未来恢复开发

恢复时先阅读 Post-R3 backlog，重新核实当前 Android 工具链、主仓库 runtime 协议、community API
schema 和数据迁移边界，再建立新的里程碑。归档 R4 草案和 R3-J/K2 名称只作历史来源，不是现行规范。

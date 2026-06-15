# KMD Reader Android

KMD Reader Android 是一个面向 KMD 创作内容的移动端阅读与社区浏览应用原型。

本项目是 Android 课程项目，同时也是 KMD 内容生态移动端宿主的早期验证。当前阶段已经从 UI 原型推进到数据层、ViewModel 和 WebView runtime host 验证：用户可以浏览 KMD 作品、查看脚本详情、进入阅读页、刷新社区数据，并在详情或阅读中呼起轻量脚本审阅面板。

## MVP 功能

- 浏览 KMD 作品列表和作品属性。
- 查看脚本详情。
- 进入 KMD 阅读页。
- 模拟本地 `.kmd` 文件导入。
- 呼起搜索/筛选浮层。
- 呼起脚本审阅浮层。
- 通过 Repository 从本地数据库和社区 API 获取作品数据。

## 交互模型

应用不采用传统底部导航，而是采用顶部标签 + 左右滑动的活动页面模型。

当前主页面：

- `我的`
- `浏览`

从主页面进入内容后，会在右侧打开临时页面：

- `脚本详情`
- `阅读`
- `导入`

搜索/筛选和脚本审阅不是一级页面，而是浮层。

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- ViewModel + StateFlow
- Room
- Retrofit / OkHttp
- WebView
- Gradle Kotlin DSL

当前已经具备 Room、Retrofit、Repository、WebView 宿主和真实 `reader-runtime-web` artifact 消费链路。最小 `loadScript -> ready -> play/pause/seek -> progress` 路径已经闭合；后续重点是模拟器/真机 smoke、错误恢复、横屏观看入口、阅读进度持久化和本地导入。

## 项目结构

```text
app/src/main/java/com/example/kmd_reader/
  data/             repository, local database and remote API
  domain/           model and navigation policy
  presentation/     UI state and reducer
  runtime/          Reader Runtime bridge and WebView contract
  ui/               Compose app, screens, components, theme
docs/
  planning/         roadmap, course plans and runtime implementation plans
  knowledge/        app architecture and integration facts
  archive/          historical documents
```

## 运行方式

使用 Android Studio 打开本项目，或在命令行执行：

```bash
./gradlew :app:assembleDebug
```

Debug APK 构建产物位于：

```text
app/build/outputs/apk/debug/
```

## 文档

- [文档索引](docs/README.md)
- [Roadmap](docs/planning/roadmap.md)
- [PRD](docs/planning/product/prd.md)
- [应用架构](docs/knowledge/architecture/app-architecture.md)
- [页面架构](docs/knowledge/architecture/page-architecture.md)
- [Runtime 实现方案](docs/planning/runtime/runtime-implementation-plan.md)
- [Core 可移植性与 WebView 宿主可行性](docs/knowledge/integration/core-portability-webview-feasibility.md)

## 当前状态

当前版本已经可以作为课程阶段性交付基础。后续计划：

- 完成第 5 阶段集成测试报告、手测记录、Profiler 证据和 bug 清单。
- 继续收束 runtime 错误恢复：renderer 退出、source missing、runtime failed 都要可解释、可重试、可返回。
- 手测 Review / Issues companion：切换时 WebView 不重建、companion 高度不跳、源码行气泡关闭手感可靠。
- 补横屏舞台的“横屏观看”入口。
- 接入本地 `.kmd` 文件选择器。
- 将阅读进度持久化到本地数据层。

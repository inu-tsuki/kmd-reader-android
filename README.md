# KMD Reader Android

KMD Reader Android 是一个面向 KMD 创作内容的移动端阅读与社区浏览应用原型。

本项目是 Android 课程项目，同时也是 KMD 内容生态移动端宿主的早期验证。当前阶段重点验证 UI 架构和页面流转：用户可以浏览 KMD 作品、查看脚本详情、进入阅读占位页、模拟导入本地脚本，并在详情或阅读中呼起轻量脚本审阅面板。

## MVP 功能

- 浏览 KMD 作品列表和作品属性。
- 查看脚本详情。
- 进入 KMD 阅读占位页。
- 模拟本地 `.kmd` 文件导入。
- 呼起搜索/筛选浮层。
- 呼起脚本审阅浮层。

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
- Gradle Kotlin DSL

当前使用 mock 数据，不接入真实后端、Room 数据库或 KMD Web Runtime。

## 项目结构

```text
app/src/main/java/com/example/kmd_reader/
  data/             mock repository
  domain/           model and navigation policy
  presentation/     UI state and reducer
  runtime/          future KMD Reader Runtime bridge
  ui/               Compose app, screens, components, theme
docs/
  app-architecture.md
  page-architecture.md
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

- [应用架构](docs/app-architecture.md)
- [页面架构](docs/page-architecture.md)

## 当前状态

当前版本是第二阶段 UI-only 原型，已经完成可运行的页面骨架。后续计划：

- 补充页面流转图截图。
- 使用 Figma 细化视觉设计。
- 接入本地导入文件选择器。
- 接入 Room 保存阅读进度和导入作品。
- 接入 KMD Reader Web Runtime。

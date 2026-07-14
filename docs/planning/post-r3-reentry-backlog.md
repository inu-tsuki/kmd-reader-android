# Android Reader Post-R3 重启 Backlog

> 文档状态：休眠期恢复入口，不是活跃冲刺计划
> 最近更新：2026-07-14

## 使用规则

Android Reader 已在 R3-K1 结束本轮连续开发。以下项目不按旧字母切片排队，也不承诺全部实现。
未来恢复时先完成“恢复检查”，再按当时的用户价值和外部契约建立新阶段。

每项都必须重新核实当前基线；归档文档只说明 2026-07 的历史判断。

## 恢复检查

1. 定位 `r3-final` tag 和 Pre-release，核对 Android commit、主仓库 runtime commit 与 APK 校验和。
2. 使用当前受支持的 JDK / AGP / Gradle / Android SDK 打开项目，先处理工具链兼容，不夹带产品功能。
3. 在主仓库重新构建 `reader-runtime-web`，运行 Android unit、assemble 和 device gates。
4. 对照主仓库协议与社区 API 的真实 schema，复核 bridge、DTO、Room migration 和数据所有权。
5. 清点本文件与 issue tracker，按新现实定义里程碑；旧 R3-J/K2/R4 名称仅保留来源意义。

## 可独立评估的 Android 增强

| 候选 | 当前基线 | 启动条件与首次验证 |
|---|---|---|
| 书架排序、筛选、移出书架 | K1 已有纯投影与 `ToggleShelf`；选择状态未持久化 | 先做当前 UI/状态审计；保持 Repository 顺序为默认，不改 Room schema |
| 私人笔记与书签 | R3 未建 annotation 生产能力 | 先确定 SourceAnchor/位置模型是否已由社区与语言层稳定，再决定本地 schema |
| Companion、横屏与错误恢复打磨 | 已有首轮 UI、会话与 renderer 恢复骨架 | 在当前 Android/WebView 版本重跑旋转、滚动、关闭、重载和 renderer 压力实机矩阵 |
| 额外阅读偏好 | 已有主题、字号、自动保存、reduced motion | 播放速度、chrome pinned、默认方向须先核实 runtime capability 与宿主所有权 |

## 必须先设计数据生命周期

以下工作不能从 UI 按钮开始：

- 删除本地作品、source、bundle、original archive 和 runtime extract cache。
- 删除或保留 revision、issue draft、未来 annotation 的规则。
- 活跃 reader session 正在使用资产时的阻止、退出或延迟删除策略。
- Room 成功而文件失败、文件成功而 Room 失败时的补偿、重试与启动清扫。
- snapshot 压缩、diff storage 或大量 revision 清理策略。

启动交付物应先是一份数据生命周期计划和故障矩阵，随后才允许出现删除/清缓存入口。

## 依赖社区契约稳定

- 远程作品下载、离线缓存、缓存新鲜度和版本失效。
- issue/discussion/review 的创建、close/reopen、提交与云端回显。
- 本地 draft 到云端提交、revision 同步、冲突处理、身份与权限。
- 结构化 SourceAnchor 以及跨 revision 的定位兼容。

这些能力的权威模型在 KMD 主仓库和 community API。Android 只消费已稳定且有集成测试的契约；
归档的 R4 reader 草案不是接口规范。

## 依赖主仓库 Runtime / Language

- runtime settings transaction 与 typography rebuild 的原子性。
- reduced-motion 对 effect、timeline 和 stage modifier 的完整语义。
- 新语法/IR 重构完成后的 bridge、inspection 与 SourceAnchor 适配。
- `Work.presentation` 和可发布 bundle 元数据的生成链路。
- `WebViewAssetLoader` 等宿主资源管线迁移，只有在现有自定义 HTTPS host 出现真实限制时再评估。

主仓库工作可以独立推进；Android 休眠不构成其阶段入口 gate。

## 重新立项的完成条件

一个未来 Android 阶段只有同时具备以下内容才可开始：明确用户价值、单一权威仓库、稳定输入契约、
迁移/失败策略、自动化门禁、设备验证矩阵，以及与 `r3-final` 的兼容或迁移说明。

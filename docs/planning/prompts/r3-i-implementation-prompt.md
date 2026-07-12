# R3-I 实现者提示词

你正在 `/home/inu-tsuki/projects/playground/kmd/apps/android-reader` 实现 R3-I 全局阅读偏好。请直接完成代码、测试和文档更新，不要只输出方案；不要提交或推送，等待主审查者复核。

开始前完整阅读：

- `docs/planning/r3-i-reader-preferences-plan.md`
- `docs/planning/r3-local-reader-plan.md` 的 §2.8、R3-B、R3-I
- `docs/planning/roadmap.md` 的 R3 与下一轮建议
- `SettingsSheet.kt`、`MainActivity.kt`、`Theme.kt`
- `KmdReaderState.kt`、`KmdReaderAction.kt`、`KmdReaderViewModel.kt`
- `ReaderRuntimeModels.kt`、`ReaderViewportPolicy.kt`、`ReaderRuntimeBridge.kt`

必须交付：

1. 使用 AndroidX DataStore Preferences 实现 `ReaderPreferencesRepository`，并通过 `KmdReaderAppContainer` / ViewModel Factory 注入。
2. 偏好模型固定为：`fontScale=1f`、`themeMode=System`、`autoSaveProgress=true`、`reducedMotion=false`；字号范围 `0.85..1.30`，非法持久化值安全回退。
3. `KmdReaderState` 持有偏好；Composable 只读 state、dispatch action，不直接访问 DataStore。
4. 扩展既有 `SettingsSheet`：主题用三段 control，字号用 slider，两个布尔项用 switch；保留关于信息和滚动能力。
5. 主题作用于 Compose 根主题；System/Light/Dark 映射正确，切换不能改变 desk、reader session 或 `readerHostRestartToken`。
6. 建立唯一 `ReaderViewportState + ReaderPreferences -> ReaderSettings` resolver，并用于初次 load、viewport update、偏好热更新三条路径。
7. Ready 时修改 fontScale/reducedMotion 走 `runtimeBridge.updateSettings()`；Idle/Loading 时只更新 state，下一次 load 使用最新值。不得重建 `ReaderRuntimeHost`。
8. 自动保存关闭前 flush 当前 Ready session 最新进度，关闭后阻止节流写和 onCleared 写，重开后恢复既有策略。

并发与所有权约束：

- DataStore 使用字段级更新，不允许 `read old object -> copy -> write whole object` 覆盖并发设置。
- `ReaderSettings` 不得由多个 mapper 各自填默认值；viewport 变化不能把 fontScale/reducedMotion 重置，偏好变化不能丢 viewport/presentationMode。
- 自动保存切换与 `ProgressChanged` 写入必须串行化或使用等价的窄状态所有权。请添加受控 coroutine 测试，证明关闭后的迟到写不能落库。
- 关闭自动保存不清除已保存进度，恢复 seek 仍可使用最后记录。
- 重新开启时重置或正确处理 per-work 节流状态，保证下一次有效事件不会被旧窗口误杀。
- DataStore/bridge 失败不得崩溃或重建 host；按现有 effect 风格给出轻量反馈。

测试至少覆盖：

- 默认值、每字段保存、重建恢复、非法值与读写异常。
- settings resolver 的双向保留：viewport 更新保留偏好，偏好更新保留 viewport。
- initial load 和 Ready 热更新；Idle/Loading 不误发 bridge command。
- System/Light/Dark 映射和主题切换的 session/restart-token 不变式。
- 自动保存默认开启、关闭前 flush、关闭后 ProgressChanged/onCleared 停写、重开恢复。
- 关闭与 in-flight progress write 的确定性交错测试。
- SettingsSheet 的控制值和 callback；若 JVM Compose 测试代价过高，将 UI 映射提取为纯模型测试，不引入脆弱框架。

文档收尾：

- 在 `r3-i-reader-preferences-plan.md` 增加落地记录。
- 将 `r3-local-reader-plan.md`、`roadmap.md`、`docs/planning/README.md` 中 R3-I 更新为已落地；不要提前标记 R3-K。
- 保留 R3-K 作为后续书架体验收束，不把其排序/筛选/视觉工作夹带进本切片。

完成前运行：

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks
./gradlew :app:assembleDebug
git diff --check
```

最终汇报改动文件、偏好状态流、settings 合并所有权、自动保存竞态处理、测试结果和残余风险。不要提交或推送。

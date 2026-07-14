# R3-K1 AVD 视觉证据

设备：`emulator-5554`（Pixel_10_Pro），portrait 基线分辨率 1280×2856，landscape
分辨率 2856×1280。目录只保留最终验收矩阵；早期截断、叠加 Desk 和 pager 修复前的候选图
已在提交前清理。

## 最终截图矩阵

使用 `reader-visual-scroll` 和 `reader-visual-paged` 作为远端 source fixture：

| 文件 | 状态 | 结果 |
| --- | --- | --- |
| `final-01-portrait-font1.0-mixed-library-1.png`、`final-01-portrait-font1.0-mixed-library-2.png` | 竖屏 mixed Library | 覆盖 continue、本地可读与需联网分组。 |
| `final-02-portrait-font1.0-empty-library.png` | 竖屏空 Library | 覆盖真实导入入口与无残留分组的空态。 |
| `final-03-portrait-font1.0-empty-history.png` | 竖屏空 History | 覆盖独立的 History 空态。 |
| `final-04-portrait-font1.0-history-only-continue.png` | history-only continue | 覆盖书架为空而 history 有中段进度时显示 continue、且不显示“还没有作品”。 |
| `final-05-portrait-font1.5-long-text.png` | 系统大字体、长标题/作者 | 覆盖标签与操作区换行，不应重叠或裁切。 |
| `final-06-landscape-font1.0-mixed-library.png` | 横屏 mixed Library | 修复前截图；左侧 display cutout 覆盖首卡标题，不作为最终通过证据。 |
| `final-06-landscape-font1.0-mixed-library-cutout-safe.png` | 横屏空 Library（cutout-safe 验证） | 修复后截图；non-reader 内容已避开左侧 cutout。与上一张 mixed 网格图组合证明横屏布局与安全区。 |

远端 fixture 仅提供作品元数据与 KMD source；“本地可读”必须通过 Android SAF 导入取得，continue/history 必须通过真实播放进度持久化取得。横屏采用修复前 mixed 网格图定位缺陷、修复后空态图确认根级 safe drawing inset 的组合证据；目标矩阵已闭合。

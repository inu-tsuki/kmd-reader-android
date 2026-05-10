package com.example.kmd_reader.domain.model

data class Work(
    val id: String,
    val title: String,
    val authorName: String,
    val description: String,
    val tags: List<String>,
    val category: String,
    val sourceType: WorkSourceType,
    val lifecycleStatus: WorkLifecycleStatus,
    val presentation: WorkPresentation,
    val contentUri: String,
    val previewUri: String?,
    val estimatedDurationSec: Int,
    val attributes: WorkAttributes,
    val commentSummary: CommentSummary
)

data class WorkPresentation(
    val mode: PresentationMode,
    val orientationHint: OrientationHint,
    val aspectRatio: String,
    val interactionLevel: InteractionLevel,
    val previewMode: PreviewMode
)

data class WorkAttributes(
    val effectIntensity: EffectIntensity,
    val commandCount: Int,
    val externalAssetCount: Int,
    val complexityLevel: ComplexityLevel,
    val runtimeVersion: String
)

data class CommentSummary(
    val summary: String,
    val highlights: List<String>,
    val concerns: List<String>
)

enum class WorkSourceType(val label: String) {
    Mock("社区样例"),
    Local("本地导入"),
    Remote("远程社区")
}

enum class WorkLifecycleStatus(val label: String) {
    Draft("草稿"),
    Submitted("待审核"),
    Published("已上架"),
    NeedsChanges("需修改"),
    Rejected("已拒绝")
}

enum class PresentationMode(val label: String) {
    Scroll("滚动阅读"),
    Paged("分页阅读"),
    Stage("横屏舞台"),
    Interactive("互动舞台")
}

enum class OrientationHint(val label: String) {
    Portrait("竖屏"),
    Landscape("横屏"),
    Adaptive("自适应")
}

enum class InteractionLevel(val label: String) {
    None("无交互"),
    Light("轻交互"),
    Choice("选项交互")
}

enum class PreviewMode(val label: String) {
    Static("静态预览"),
    Animated("动效预览"),
    Runtime("运行时预览")
}

enum class EffectIntensity(val label: String) {
    Low("低动效"),
    Medium("中动效"),
    High("高动效")
}

enum class ComplexityLevel(val label: String) {
    Simple("简单"),
    Moderate("中等"),
    Complex("复杂")
}

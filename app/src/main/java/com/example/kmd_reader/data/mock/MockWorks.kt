package com.example.kmd_reader.data.mock

import com.example.kmd_reader.domain.model.CommentSummary
import com.example.kmd_reader.domain.model.ComplexityLevel
import com.example.kmd_reader.domain.model.EffectIntensity
import com.example.kmd_reader.domain.model.InteractionLevel
import com.example.kmd_reader.domain.model.IssueSeverity
import com.example.kmd_reader.domain.model.IssueSource
import com.example.kmd_reader.domain.model.OrientationHint
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.PreviewMode
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.domain.model.WorkAttributes
import com.example.kmd_reader.domain.model.WorkLifecycleStatus
import com.example.kmd_reader.domain.model.WorkPresentation
import com.example.kmd_reader.domain.model.WorkSourceType

object MockWorks {
    val works = listOf(
        Work(
            id = "rain-city",
            title = "雨城慢镜",
            authorName = "Mira",
            description = "一段在雨夜街灯下展开的动态诗。文字随水迹滑落，适合竖屏慢读。",
            tags = listOf("诗", "雨夜", "竖屏"),
            category = "实验文本",
            sourceType = WorkSourceType.Mock,
            lifecycleStatus = WorkLifecycleStatus.Published,
            presentation = WorkPresentation(
                mode = PresentationMode.Scroll,
                orientationHint = OrientationHint.Portrait,
                aspectRatio = "9:16",
                interactionLevel = InteractionLevel.None,
                previewMode = PreviewMode.Animated
            ),
            contentUri = "mock/rain-city.kmd",
            previewUri = null,
            estimatedDurationSec = 210,
            attributes = WorkAttributes(
                effectIntensity = EffectIntensity.Medium,
                commandCount = 48,
                externalAssetCount = 0,
                complexityLevel = ComplexityLevel.Moderate,
                runtimeVersion = "0.2-preview"
            ),
            commentSummary = CommentSummary(
                summary = "读者喜欢它的节奏，但希望雨滴动效不要遮住最后几行。",
                highlights = listOf("氛围强", "节奏安静"),
                concerns = listOf("末段略暗")
            )
        ),
        Work(
            id = "star-manual",
            title = "星图操作手册",
            authorName = "Noel",
            description = "分页式科幻手册，每页像一张仪表说明卡，适合碎片阅读。",
            tags = listOf("科幻", "分页", "手册"),
            category = "视觉小说",
            sourceType = WorkSourceType.Mock,
            lifecycleStatus = WorkLifecycleStatus.Published,
            presentation = WorkPresentation(
                mode = PresentationMode.Paged,
                orientationHint = OrientationHint.Adaptive,
                aspectRatio = "free",
                interactionLevel = InteractionLevel.Light,
                previewMode = PreviewMode.Static
            ),
            contentUri = "mock/star-manual.kmd",
            previewUri = null,
            estimatedDurationSec = 360,
            attributes = WorkAttributes(
                effectIntensity = EffectIntensity.Low,
                commandCount = 35,
                externalAssetCount = 2,
                complexityLevel = ComplexityLevel.Simple,
                runtimeVersion = "0.2-preview"
            ),
            commentSummary = CommentSummary(
                summary = "结构清晰，像在翻一本会发光的小册子。",
                highlights = listOf("信息层级好", "适合分页"),
                concerns = listOf("互动提示可以更明显")
            )
        ),
        Work(
            id = "glass-rail",
            title = "玻璃铁轨",
            authorName = "Toki",
            description = "横屏电影式 KMD。镜头、字幕和舞台标记共同构成一段短片。",
            tags = listOf("横屏", "舞台", "电影感"),
            category = "动态短片",
            sourceType = WorkSourceType.Mock,
            lifecycleStatus = WorkLifecycleStatus.Submitted,
            presentation = WorkPresentation(
                mode = PresentationMode.Stage,
                orientationHint = OrientationHint.Landscape,
                aspectRatio = "16:9",
                interactionLevel = InteractionLevel.None,
                previewMode = PreviewMode.Runtime
            ),
            contentUri = "mock/glass-rail.kmd",
            previewUri = null,
            estimatedDurationSec = 150,
            attributes = WorkAttributes(
                effectIntensity = EffectIntensity.High,
                commandCount = 91,
                externalAssetCount = 4,
                complexityLevel = ComplexityLevel.Complex,
                runtimeVersion = "0.2-preview"
            ),
            commentSummary = CommentSummary(
                summary = "审核者认为舞台调度很漂亮，但移动端性能还要观察。",
                highlights = listOf("镜头感强", "舞台明确"),
                concerns = listOf("高动效", "外部资源较多")
            )
        ),
        Work(
            id = "choice-room",
            title = "选择之室",
            authorName = "Ivy",
            description = "横屏互动式脚本，读者在关键段落选择下一句台词。",
            tags = listOf("互动", "横屏", "选择"),
            category = "互动叙事",
            sourceType = WorkSourceType.Local,
            lifecycleStatus = WorkLifecycleStatus.Draft,
            presentation = WorkPresentation(
                mode = PresentationMode.Interactive,
                orientationHint = OrientationHint.Landscape,
                aspectRatio = "16:9",
                interactionLevel = InteractionLevel.Choice,
                previewMode = PreviewMode.Static
            ),
            contentUri = "local/choice-room.kmd",
            previewUri = null,
            estimatedDurationSec = 420,
            attributes = WorkAttributes(
                effectIntensity = EffectIntensity.Medium,
                commandCount = 74,
                externalAssetCount = 1,
                complexityLevel = ComplexityLevel.Moderate,
                runtimeVersion = "0.2-preview"
            ),
            commentSummary = CommentSummary(
                summary = "本地草稿，适合用来验证互动式 Reader Host。",
                highlights = listOf("选择结构清楚"),
                concerns = listOf("需要真实交互桥接")
            )
        )
    )

    val issues = mapOf(
        "glass-rail" to listOf(
            ScriptIssue(
                id = "issue-rail-1",
                workId = "glass-rail",
                severity = IssueSeverity.Warning,
                source = IssueSource.Performance,
                location = "scene: bridge",
                message = "同一段落内存在多个高强度舞台移动。",
                suggestion = "建议压缩预览片段，或降低移动端默认动效。"
            ),
            ScriptIssue(
                id = "issue-rail-2",
                workId = "glass-rail",
                severity = IssueSeverity.Info,
                source = IssueSource.Asset,
                location = "asset: rail_glow.png",
                message = "引用了 4 个外部资源。",
                suggestion = "上架前确认资源可离线缓存。"
            )
        ),
        "choice-room" to listOf(
            ScriptIssue(
                id = "issue-choice-1",
                workId = "choice-room",
                severity = IssueSeverity.Warning,
                source = IssueSource.Effect,
                location = "line 42",
                message = "选项出现前仍有持续行为特效。",
                suggestion = "进入选择状态前清理行为轨道。"
            )
        )
    )
}

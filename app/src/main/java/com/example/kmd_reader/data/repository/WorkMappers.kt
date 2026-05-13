package com.example.kmd_reader.data.repository

import com.example.kmd_reader.data.local.ScriptIssueEntity
import com.example.kmd_reader.data.local.WorkEntity
import com.example.kmd_reader.data.remote.dto.ScriptIssueDto
import com.example.kmd_reader.data.remote.dto.WorkDto
import com.example.kmd_reader.data.remote.dto.WorkStatsDto
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

private const val ListSeparator = "\u001F"

fun Work.toEntity(syncedAt: Long): WorkEntity {
    return WorkEntity(
        id = id,
        title = title,
        authorName = authorName,
        description = description,
        tags = tags.packList(),
        category = category,
        sourceType = sourceType.name,
        lifecycleStatus = lifecycleStatus.name,
        presentationMode = presentation.mode.name,
        orientationHint = presentation.orientationHint.name,
        aspectRatio = presentation.aspectRatio,
        interactionLevel = presentation.interactionLevel.name,
        previewMode = presentation.previewMode.name,
        contentUri = contentUri,
        previewUri = previewUri,
        estimatedDurationSec = estimatedDurationSec,
        effectIntensity = attributes.effectIntensity.name,
        commandCount = attributes.commandCount,
        externalAssetCount = attributes.externalAssetCount,
        complexityLevel = attributes.complexityLevel.name,
        runtimeVersion = attributes.runtimeVersion,
        commentSummary = commentSummary.summary,
        commentHighlights = commentSummary.highlights.packList(),
        commentConcerns = commentSummary.concerns.packList(),
        syncedAt = syncedAt
    )
}

fun WorkDto.toEntity(syncedAt: Long): WorkEntity {
    val stats = stats ?: WorkStatsDto()
    return WorkEntity(
        id = id,
        title = title,
        authorName = authorName,
        description = description,
        tags = tags.packList(),
        category = tags.firstOrNull().orEmpty(),
        sourceType = WorkSourceType.Remote.name,
        lifecycleStatus = lifecycleStatusFromApi(lifecycleStatus).name,
        presentationMode = presentationModeFromApi(presentationMode).name,
        orientationHint = orientationHintFromApi(orientationHint).name,
        aspectRatio = aspectRatio,
        interactionLevel = interactionLevelFromApi(interactionLevel).name,
        previewMode = previewModeFromApi(previewMode).name,
        contentUri = "community/$id.kmd",
        previewUri = coverUrl.ifBlank { null },
        estimatedDurationSec = estimatedDurationSec,
        effectIntensity = effectIntensityFromStats(stats).name,
        commandCount = stats.lines,
        externalAssetCount = 0,
        complexityLevel = complexityLevelFromStats(stats).name,
        runtimeVersion = "community-api",
        commentSummary = commentSummary?.toDomainSummary().orEmpty(),
        commentHighlights = commentSummary?.preview.orEmpty().packList(),
        commentConcerns = emptyList<String>().packList(),
        syncedAt = syncedAt
    )
}

fun WorkEntity.toDomain(): Work {
    return Work(
        id = id,
        title = title,
        authorName = authorName,
        description = description,
        tags = tags.unpackList(),
        category = category,
        sourceType = enumValueOrDefault(sourceType, WorkSourceType.Remote),
        lifecycleStatus = enumValueOrDefault(lifecycleStatus, WorkLifecycleStatus.Draft),
        presentation = WorkPresentation(
            mode = enumValueOrDefault(presentationMode, PresentationMode.Scroll),
            orientationHint = enumValueOrDefault(orientationHint, OrientationHint.Adaptive),
            aspectRatio = aspectRatio,
            interactionLevel = enumValueOrDefault(interactionLevel, InteractionLevel.None),
            previewMode = enumValueOrDefault(previewMode, PreviewMode.Static)
        ),
        contentUri = contentUri,
        previewUri = previewUri,
        estimatedDurationSec = estimatedDurationSec,
        attributes = WorkAttributes(
            effectIntensity = enumValueOrDefault(effectIntensity, EffectIntensity.Medium),
            commandCount = commandCount,
            externalAssetCount = externalAssetCount,
            complexityLevel = enumValueOrDefault(complexityLevel, ComplexityLevel.Moderate),
            runtimeVersion = runtimeVersion
        ),
        commentSummary = CommentSummary(
            summary = commentSummary,
            highlights = commentHighlights.unpackList(),
            concerns = commentConcerns.unpackList()
        )
    )
}

fun ScriptIssue.toEntity(syncedAt: Long): ScriptIssueEntity {
    return ScriptIssueEntity(
        id = id,
        workId = workId,
        severity = severity.name,
        source = source.name,
        location = location,
        message = message,
        suggestion = suggestion,
        syncedAt = syncedAt
    )
}

fun ScriptIssueDto.toEntity(syncedAt: Long): ScriptIssueEntity {
    return ScriptIssueEntity(
        id = id,
        workId = workId,
        severity = issueSeverityFromApi(severity).name,
        source = issueSourceFromApi(source).name,
        location = location,
        message = message,
        suggestion = suggestion,
        syncedAt = syncedAt
    )
}

fun ScriptIssueEntity.toDomain(): ScriptIssue {
    return ScriptIssue(
        id = id,
        workId = workId,
        severity = enumValueOrDefault(severity, IssueSeverity.Info),
        source = enumValueOrDefault(source, IssueSource.Parser),
        location = location,
        message = message,
        suggestion = suggestion
    )
}

private fun List<String>.packList(): String = joinToString(ListSeparator)

private fun String.unpackList(): List<String> {
    if (isBlank()) {
        return emptyList()
    }
    return split(ListSeparator)
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, fallback: T): T {
    return enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

private fun lifecycleStatusFromApi(value: String): WorkLifecycleStatus {
    return when (value.normalizedApiValue()) {
        "published" -> WorkLifecycleStatus.Published
        "submitted" -> WorkLifecycleStatus.Submitted
        "draft" -> WorkLifecycleStatus.Draft
        "needs_changes" -> WorkLifecycleStatus.NeedsChanges
        "rejected" -> WorkLifecycleStatus.Rejected
        else -> WorkLifecycleStatus.Draft
    }
}

private fun presentationModeFromApi(value: String): PresentationMode {
    return when (value.normalizedApiValue()) {
        "scroll" -> PresentationMode.Scroll
        "paged" -> PresentationMode.Paged
        "stage" -> PresentationMode.Stage
        "interactive" -> PresentationMode.Interactive
        else -> PresentationMode.Scroll
    }
}

private fun orientationHintFromApi(value: String): OrientationHint {
    return when (value.normalizedApiValue()) {
        "portrait" -> OrientationHint.Portrait
        "landscape" -> OrientationHint.Landscape
        "adaptive" -> OrientationHint.Adaptive
        else -> OrientationHint.Adaptive
    }
}

private fun interactionLevelFromApi(value: String): InteractionLevel {
    return when (value.normalizedApiValue()) {
        "read_only", "none" -> InteractionLevel.None
        "light", "light_interactive" -> InteractionLevel.Light
        "choice", "interactive" -> InteractionLevel.Choice
        else -> InteractionLevel.None
    }
}

private fun previewModeFromApi(value: String): PreviewMode {
    return when (value.normalizedApiValue()) {
        "clip", "animated" -> PreviewMode.Animated
        "runtime" -> PreviewMode.Runtime
        "cover", "none", "static" -> PreviewMode.Static
        else -> PreviewMode.Static
    }
}

private fun issueSeverityFromApi(value: String): IssueSeverity {
    return when (value.normalizedApiValue()) {
        "info" -> IssueSeverity.Info
        "warning" -> IssueSeverity.Warning
        "error" -> IssueSeverity.Error
        else -> IssueSeverity.Info
    }
}

private fun issueSourceFromApi(value: String): IssueSource {
    return when (value.normalizedApiValue()) {
        "syntax" -> IssueSource.Parser
        "layout" -> IssueSource.Layout
        "effect" -> IssueSource.Effect
        "asset" -> IssueSource.Asset
        "performance" -> IssueSource.Performance
        "metadata" -> IssueSource.Metadata
        "accessibility" -> IssueSource.Accessibility
        "runtime" -> IssueSource.Runtime
        else -> IssueSource.Parser
    }
}

private fun effectIntensityFromStats(stats: WorkStatsDto): EffectIntensity {
    return when {
        stats.effects >= 30 -> EffectIntensity.High
        stats.effects >= 10 -> EffectIntensity.Medium
        else -> EffectIntensity.Low
    }
}

private fun complexityLevelFromStats(stats: WorkStatsDto): ComplexityLevel {
    return when {
        stats.lines >= 180 || stats.effects >= 30 -> ComplexityLevel.Complex
        stats.lines >= 80 || stats.effects >= 10 -> ComplexityLevel.Moderate
        else -> ComplexityLevel.Simple
    }
}

private fun com.example.kmd_reader.data.remote.dto.CommentSummaryDto.toDomainSummary(): String {
    return if (count <= 0) {
        "暂无社区评论。"
    } else {
        "$count 条社区评论摘要。"
    }
}

private fun String.normalizedApiValue(): String = trim().lowercase()

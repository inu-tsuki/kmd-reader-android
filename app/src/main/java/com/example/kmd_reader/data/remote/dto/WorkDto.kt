package com.example.kmd_reader.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkDto(
    val id: String,
    val title: String,
    val authorName: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val presentationMode: String,
    val orientationHint: String,
    val aspectRatio: String,
    val lifecycleStatus: String,
    val interactionLevel: String = "read_only",
    val previewMode: String = "cover",
    val estimatedDurationSec: Int,
    val coverUrl: String = "",
    val stats: WorkStatsDto? = null,
    val commentSummary: CommentSummaryDto? = null
)

@Serializable
data class WorkStatsDto(
    val scenes: Int = 0,
    val lines: Int = 0,
    val effects: Int = 0
)

@Serializable
data class CommentSummaryDto(
    val count: Int = 0,
    val preview: List<String> = emptyList()
)

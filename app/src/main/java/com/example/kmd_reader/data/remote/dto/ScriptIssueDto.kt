package com.example.kmd_reader.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ScriptIssueDto(
    val id: String,
    val workId: String,
    val severity: String,
    val source: String,
    val location: String,
    val message: String,
    val suggestion: String
)

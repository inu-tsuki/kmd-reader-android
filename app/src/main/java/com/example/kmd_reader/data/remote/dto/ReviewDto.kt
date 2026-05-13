package com.example.kmd_reader.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReviewRequestDto(
    val workId: String,
    val reviewerName: String,
    val decision: String,
    val note: String
)

@Serializable
data class ReviewResponseDto(
    val id: String,
    val workId: String,
    val decision: String,
    val accepted: Boolean
)

package com.example.kmd_reader.runtime

import com.example.kmd_reader.domain.model.Work

data class ReaderLoadRequest(
    val work: Work
)

data class ReaderSettings(
    val reducedMotion: Boolean = false,
    val fontScale: Float = 1f
)

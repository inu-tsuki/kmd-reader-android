package com.example.kmd_reader.presentation

sealed interface ReaderSessionState {
    data object Idle : ReaderSessionState

    data class Loading(
        val workId: String
    ) : ReaderSessionState

    data class Ready(
        val workId: String,
        val progress: Float,
        val isPlaying: Boolean
    ) : ReaderSessionState

    data class Failed(
        val workId: String,
        val message: String
    ) : ReaderSessionState
}

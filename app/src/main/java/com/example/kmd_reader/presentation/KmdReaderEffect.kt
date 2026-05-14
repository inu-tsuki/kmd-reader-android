package com.example.kmd_reader.presentation

sealed interface KmdReaderEffect {
    data class ShowMessage(val message: String) : KmdReaderEffect
    data class LoadRuntime(val workId: String) : KmdReaderEffect
    data object OpenImportPicker : KmdReaderEffect
}

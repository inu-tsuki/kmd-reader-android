package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.model.PresentationMode

sealed interface KmdReaderAction {
    data object OpenMine : KmdReaderAction
    data object OpenBrowse : KmdReaderAction
    data class OpenWork(val workId: String) : KmdReaderAction
    data object OpenReader : KmdReaderAction
    data object OpenImport : KmdReaderAction
    data object CloseCurrentDesk : KmdReaderAction
    data class SetActiveDesk(val index: Int) : KmdReaderAction
    data object OpenSearch : KmdReaderAction
    data object CloseSearch : KmdReaderAction
    data class UpdateQuery(val query: String) : KmdReaderAction
    data class ToggleMode(val mode: PresentationMode) : KmdReaderAction
    data object OpenReview : KmdReaderAction
    data object CloseReview : KmdReaderAction
    data class SetReviewMessage(val message: String) : KmdReaderAction
}

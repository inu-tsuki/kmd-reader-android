package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.KmdSourceSnapshot
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work

data class KmdReaderState(
    val deskStack: DeskStackState = DeskStackState(),
    val works: List<Work> = emptyList(),
    val searchQuery: String = "",
    val selectedMode: PresentationMode? = null,
    val issuesByWorkId: Map<String, List<ScriptIssue>> = emptyMap(),
    val sourceSnapshotsByWorkId: Map<String, KmdSourceSnapshot> = emptyMap(),
    val readerSession: ReaderSessionState = ReaderSessionState.Idle,
    val readerChrome: ReaderChromeState = ReaderChromeState(),
    val readerCompanion: ReaderCompanionState = ReaderCompanionState(),
    val issueFocus: IssueFocusState = IssueFocusState(),
    val readerViewport: ReaderViewportState = ReaderViewportState(),
    val readerHostRestartToken: Int = 0,
    val isLoadingWorks: Boolean = false,
    val errorMessage: String? = null
) {
    val selectedWork: Work?
        get() = works.firstOrNull { it.id == deskStack.currentWorkId }

    val filteredWorks: List<Work>
        get() = works.filter { work ->
            val matchesQuery = searchQuery.isBlank() ||
                work.title.contains(searchQuery, ignoreCase = true) ||
                work.authorName.contains(searchQuery, ignoreCase = true) ||
                work.tags.any { it.contains(searchQuery, ignoreCase = true) }
            val matchesMode = selectedMode == null || work.presentation.mode == selectedMode
            matchesQuery && matchesMode
        }
}

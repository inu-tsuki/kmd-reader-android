package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.Work

data class KmdReaderState(
    val deskStack: DeskStackState = DeskStackState(),
    val works: List<Work> = emptyList(),
    val searchQuery: String = "",
    val selectedMode: PresentationMode? = null
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

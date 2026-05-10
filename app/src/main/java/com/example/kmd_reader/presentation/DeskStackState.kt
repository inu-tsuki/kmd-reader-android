package com.example.kmd_reader.presentation

data class DeskStackState(
    val base: List<Desk> = listOf(Desk.Mine, Desk.Browse),
    val extension: List<Desk> = emptyList(),
    val activeIndex: Int = 1,
    val currentWorkId: String? = null,
    val isSearchOpen: Boolean = false,
    val isReviewOpen: Boolean = false,
    val reviewMessage: String? = null
) {
    val desks: List<Desk>
        get() = base + extension
}

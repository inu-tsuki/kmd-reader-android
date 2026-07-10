package com.example.kmd_reader.presentation

data class DeskStackState(
    val base: List<Desk> = listOf(Desk.Mine, Desk.Browse),
    val extension: List<Desk> = emptyList(),
    val activeIndex: Int = 1,
    val currentWorkId: String? = null,
    val isSearchOpen: Boolean = false,
    val isReviewOpen: Boolean = false,
    val reviewMessage: String? = null,
    /** R3-F：设置/关于 overlay 开关（与 isSearchOpen 同构，不增加 Desk 条带）。 */
    val isSettingsOpen: Boolean = false
) {
    val desks: List<Desk>
        get() = base + extension
}

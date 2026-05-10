package com.example.kmd_reader.domain.policy

import com.example.kmd_reader.presentation.Desk
import com.example.kmd_reader.presentation.DeskStackState
import kotlin.math.max
import kotlin.math.min

object DeskStackPolicy {
    fun openMine(state: DeskStackState): DeskStackState =
        state.copy(activeIndex = 0, isSearchOpen = false)

    fun openBrowse(state: DeskStackState): DeskStackState =
        state.copy(activeIndex = 1, isReviewOpen = false)

    fun openWork(state: DeskStackState, workId: String): DeskStackState =
        state.copy(
            extension = listOf(Desk.Detail),
            activeIndex = 2,
            currentWorkId = workId,
            isSearchOpen = false,
            isReviewOpen = false,
            reviewMessage = null
        )

    fun openReader(state: DeskStackState): DeskStackState {
        if (state.currentWorkId == null) return state
        val extension = if (Desk.Reader in state.extension) {
            state.extension
        } else {
            listOf(Desk.Detail, Desk.Reader)
        }
        return state.copy(
            extension = extension,
            activeIndex = state.base.size + extension.indexOf(Desk.Reader),
            isSearchOpen = false
        )
    }

    fun openImport(state: DeskStackState): DeskStackState =
        state.copy(
            extension = listOf(Desk.Import),
            activeIndex = 2,
            isSearchOpen = false,
            isReviewOpen = false,
            reviewMessage = null
        )

    fun closeCurrentDesk(state: DeskStackState): DeskStackState {
        if (state.activeIndex <= 1) {
            return state.copy(activeIndex = 1, isSearchOpen = false, isReviewOpen = false)
        }

        val keepExtensionCount = max(0, state.activeIndex - state.base.size)
        val newExtension = state.extension.take(keepExtensionCount)
        val currentWorkId = if (Desk.Detail in newExtension) state.currentWorkId else null

        return state.copy(
            extension = newExtension,
            activeIndex = min(state.activeIndex - 1, state.base.lastIndex + newExtension.size),
            currentWorkId = currentWorkId,
            isReviewOpen = false,
            reviewMessage = null
        )
    }

    fun setActive(state: DeskStackState, index: Int): DeskStackState =
        state.copy(activeIndex = index.coerceIn(0, state.desks.lastIndex))

    fun openSearch(state: DeskStackState): DeskStackState =
        state.copy(activeIndex = 1, isSearchOpen = true, isReviewOpen = false)

    fun closeSearch(state: DeskStackState): DeskStackState =
        state.copy(isSearchOpen = false)

    fun openReview(state: DeskStackState): DeskStackState =
        state.copy(isReviewOpen = true, isSearchOpen = false, reviewMessage = null)

    fun closeReview(state: DeskStackState): DeskStackState =
        state.copy(isReviewOpen = false)

    fun setReviewMessage(state: DeskStackState, message: String): DeskStackState =
        state.copy(reviewMessage = message)
}

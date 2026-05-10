package com.example.kmd_reader.presentation

import com.example.kmd_reader.domain.policy.DeskStackPolicy

object KmdReaderReducer {
    fun reduce(state: KmdReaderState, action: KmdReaderAction): KmdReaderState =
        when (action) {
            KmdReaderAction.OpenMine -> state.copy(
                deskStack = DeskStackPolicy.openMine(state.deskStack)
            )

            KmdReaderAction.OpenBrowse -> state.copy(
                deskStack = DeskStackPolicy.openBrowse(state.deskStack)
            )

            is KmdReaderAction.OpenWork -> state.copy(
                deskStack = DeskStackPolicy.openWork(state.deskStack, action.workId)
            )

            KmdReaderAction.OpenReader -> state.copy(
                deskStack = DeskStackPolicy.openReader(state.deskStack)
            )

            KmdReaderAction.OpenImport -> state.copy(
                deskStack = DeskStackPolicy.openImport(state.deskStack)
            )

            KmdReaderAction.CloseCurrentDesk -> state.copy(
                deskStack = DeskStackPolicy.closeCurrentDesk(state.deskStack)
            )

            is KmdReaderAction.SetActiveDesk -> state.copy(
                deskStack = DeskStackPolicy.setActive(state.deskStack, action.index)
            )

            KmdReaderAction.OpenSearch -> state.copy(
                deskStack = DeskStackPolicy.openSearch(state.deskStack)
            )

            KmdReaderAction.CloseSearch -> state.copy(
                deskStack = DeskStackPolicy.closeSearch(state.deskStack)
            )

            is KmdReaderAction.UpdateQuery -> state.copy(searchQuery = action.query)

            is KmdReaderAction.ToggleMode -> state.copy(
                selectedMode = if (state.selectedMode == action.mode) null else action.mode
            )

            KmdReaderAction.OpenReview -> state.copy(
                deskStack = DeskStackPolicy.openReview(state.deskStack)
            )

            KmdReaderAction.CloseReview -> state.copy(
                deskStack = DeskStackPolicy.closeReview(state.deskStack)
            )

            is KmdReaderAction.SetReviewMessage -> state.copy(
                deskStack = DeskStackPolicy.setReviewMessage(state.deskStack, action.message)
            )
        }
}

package com.example.kmd_reader.ui.app

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kmd_reader.presentation.Desk
import com.example.kmd_reader.presentation.KmdReaderAction
import com.example.kmd_reader.presentation.KmdReaderEffect
import com.example.kmd_reader.presentation.KmdReaderViewModel
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.ui.screen.browse.BrowseDesk
import com.example.kmd_reader.ui.screen.browse.FilterOverlay
import com.example.kmd_reader.ui.screen.importkmd.ImportDesk
import com.example.kmd_reader.ui.screen.mine.MineDesk
import com.example.kmd_reader.ui.screen.reader.ReaderDesk
import com.example.kmd_reader.ui.screen.review.ReviewOverlay
import com.example.kmd_reader.ui.screen.work.WorkDetailDesk

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KmdReaderApp(
    modifier: Modifier = Modifier,
    viewModel: KmdReaderViewModel = viewModel(),
    runtimeBridge: ReaderRuntimeBridge? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dispatch: (KmdReaderAction) -> Unit = viewModel::onAction

    val desks = state.deskStack.desks
    val pagerState = rememberPagerState(initialPage = state.deskStack.activeIndex) { desks.size }
    val latestActiveIndex by rememberUpdatedState(state.deskStack.activeIndex)
    val latestLastIndex by rememberUpdatedState(desks.lastIndex)
    var programmaticTargetPage by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is KmdReaderEffect.ShowMessage -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is KmdReaderEffect.LoadRuntime -> Unit
                KmdReaderEffect.OpenImportPicker -> Unit
            }
        }
    }

    LaunchedEffect(state.deskStack.activeIndex, desks.size) {
        val targetPage = state.deskStack.activeIndex.coerceIn(0, desks.lastIndex)
        if (pagerState.settledPage != targetPage) {
            programmaticTargetPage = targetPage
            try {
                pagerState.animateScrollToPage(targetPage)
            } finally {
                if (programmaticTargetPage == targetPage) {
                    programmaticTargetPage = null
                    val settledPage = pagerState.settledPage.coerceIn(0, latestLastIndex)
                    if (settledPage != latestActiveIndex && settledPage != targetPage) {
                        dispatch(KmdReaderAction.SetActiveDesk(settledPage))
                    }
                }
            }
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { page ->
                val settledPage = page.coerceIn(0, latestLastIndex)
                val pendingTarget = programmaticTargetPage
                if (pendingTarget != null && settledPage != pendingTarget) {
                    return@collect
                }
                if (settledPage != latestActiveIndex) {
                    dispatch(KmdReaderAction.SetActiveDesk(settledPage))
                }
            }
    }

    LaunchedEffect(desks.size) {
        if (state.deskStack.activeIndex > desks.lastIndex) {
            dispatch(KmdReaderAction.SetActiveDesk(desks.lastIndex))
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AppTopBar(
                    desks = desks,
                    activeIndex = state.deskStack.activeIndex,
                    onDeskClick = { index -> dispatch(KmdReaderAction.SetActiveDesk(index)) },
                    onCloseCurrent = { dispatch(KmdReaderAction.CloseCurrentDesk) }
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (desks[page]) {
                        Desk.Mine -> MineDesk(
                            recentWorks = state.works,
                            onOpenImport = { dispatch(KmdReaderAction.OpenImport) },
                            onOpenWork = { dispatch(KmdReaderAction.OpenWork(it)) }
                        )

                        Desk.Browse -> BrowseDesk(
                            works = state.filteredWorks,
                            resultCount = state.filteredWorks.size,
                            onOpenSearch = { dispatch(KmdReaderAction.OpenSearch) },
                            onOpenWork = { dispatch(KmdReaderAction.OpenWork(it)) }
                        )

                        Desk.Detail -> WorkDetailDesk(
                            work = state.selectedWork,
                            onOpenReader = { dispatch(KmdReaderAction.OpenReader) },
                            onOpenReview = { dispatch(KmdReaderAction.OpenReview) },
                            onOpenImport = { dispatch(KmdReaderAction.OpenImport) }
                        )

                        Desk.Reader -> ReaderDesk(
                            work = state.selectedWork,
                            readerSession = state.readerSession,
                            runtimeBridge = runtimeBridge,
                            onOpenReview = { dispatch(KmdReaderAction.OpenReview) }
                        )

                        Desk.Import -> ImportDesk(
                            onMockImport = { dispatch(KmdReaderAction.OpenWork(it)) }
                        )
                    }
                }
            }

            if (state.deskStack.isSearchOpen) {
                FilterOverlay(
                    query = state.searchQuery,
                    selectedMode = state.selectedMode,
                    resultCount = state.filteredWorks.size,
                    onQueryChange = { dispatch(KmdReaderAction.UpdateQuery(it)) },
                    onToggleMode = { dispatch(KmdReaderAction.ToggleMode(it)) },
                    onClose = { dispatch(KmdReaderAction.CloseSearch) }
                )
            }

            if (state.deskStack.isReviewOpen) {
                val work = state.selectedWork
                ReviewOverlay(
                    work = work,
                    issues = work?.let { state.issuesByWorkId[it.id] }.orEmpty(),
                    reviewMessage = state.deskStack.reviewMessage,
                    onDecision = { dispatch(KmdReaderAction.SetReviewMessage(it)) },
                    onClose = { dispatch(KmdReaderAction.CloseReview) }
                )
            }
        }
    }
}

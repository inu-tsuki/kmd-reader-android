package com.example.kmd_reader.ui.app

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.kmd_reader.presentation.ImportState
import com.example.kmd_reader.presentation.KmdReaderAction
import com.example.kmd_reader.presentation.KmdReaderEffect
import com.example.kmd_reader.presentation.KmdReaderViewModel
import com.example.kmd_reader.presentation.ReaderCompanionType
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.ui.screen.browse.BrowseDesk
import com.example.kmd_reader.ui.screen.browse.FilterOverlay
import com.example.kmd_reader.ui.screen.importkmd.ImportDesk
import com.example.kmd_reader.ui.screen.mine.MineDesk
import com.example.kmd_reader.ui.screen.mine.SettingsSheet
import com.example.kmd_reader.ui.screen.reader.ReaderDesk
import com.example.kmd_reader.ui.screen.review.ReviewOverlay
import com.example.kmd_reader.ui.screen.work.WorkDetailDesk
import com.example.kmd_reader.ui.screen.work.resolveWorkDetailReadingState

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
    val activeDesk = desks.getOrNull(state.deskStack.activeIndex)
    val isReaderActive = activeDesk == Desk.Reader
    val pagerState = rememberPagerState(initialPage = state.deskStack.activeIndex) { desks.size }
    val latestActiveIndex by rememberUpdatedState(state.deskStack.activeIndex)
    val latestLastIndex by rememberUpdatedState(desks.lastIndex)
    var programmaticTargetPage by remember { mutableStateOf<Int?>(null) }

    // R3-D3：SAF 文件选择器。OpenDocument 支持 EXTRA_MIME_TYPES。
    // 支持双格式：.kmdwork zip（application/zip, application/x-zip-compressed）+ 裸 .kmd（text/plain, text/markdown）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            dispatch(KmdReaderAction.ImportFromUri(uri))
        } else {
            dispatch(KmdReaderAction.CancelImport)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effectFlow.collect { effect ->
            when (effect) {
                is KmdReaderEffect.ShowMessage -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is KmdReaderEffect.LoadRuntime -> Unit
                KmdReaderEffect.OpenImportPicker -> {
                    importLauncher.launch(arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "text/plain",
                        "text/markdown"
                    ))
                }
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

    BackHandler(enabled = isReaderActive && state.readerCompanion.active != null) {
        dispatch(KmdReaderAction.CloseReaderCompanion)
    }

    BackHandler(enabled = state.deskStack.isSettingsOpen) {
        dispatch(KmdReaderAction.CloseSettings)
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
                if (!isReaderActive) {
                    AppTopBar(
                        desks = desks,
                        activeIndex = state.deskStack.activeIndex,
                        onDeskClick = { index -> dispatch(KmdReaderAction.SetActiveDesk(index)) },
                        onCloseCurrent = { dispatch(KmdReaderAction.CloseCurrentDesk) }
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !isReaderActive
                ) { page ->
                    when (desks[page]) {
                        Desk.Mine -> MineDesk(
                            shelfState = state.shelfState,
                            onOpenImport = { dispatch(KmdReaderAction.OpenImport) },
                            onOpenWork = { dispatch(KmdReaderAction.OpenWork(it)) },
                            onContinueReading = { workId ->
                                dispatch(KmdReaderAction.OpenWork(workId))
                                dispatch(KmdReaderAction.OpenReader)
                            },
                            onOpenSettings = { dispatch(KmdReaderAction.OpenSettings) }
                        )

                        Desk.Browse -> BrowseDesk(
                            works = state.filteredWorks,
                            resultCount = state.filteredWorks.size,
                            onOpenSearch = { dispatch(KmdReaderAction.OpenSearch) },
                            onOpenWork = { dispatch(KmdReaderAction.OpenWork(it)) },
                            shelfWorkIds = state.shelfState.shelf.map { it.workId }.toSet(),
                            onToggleShelf = { dispatch(KmdReaderAction.ToggleShelf(it)) }
                        )

                        Desk.Detail -> WorkDetailDesk(
                            work = state.selectedWork,
                            onShelf = state.shelfState.shelf.any {
                                it.workId == state.deskStack.currentWorkId
                            },
                            readingState = resolveWorkDetailReadingState(
                                item = state.shelfState.findByWorkId(state.deskStack.currentWorkId),
                                currentRevisionId = state.selectedWork?.script?.activeRevisionId,
                                now = System.currentTimeMillis()
                            ),
                            onOpenReader = { dispatch(KmdReaderAction.OpenReader) },
                            onOpenReview = { dispatch(KmdReaderAction.OpenReview) },
                            onOpenImport = { dispatch(KmdReaderAction.OpenImport) },
                            onToggleShelf = {
                                state.deskStack.currentWorkId?.let {
                                    dispatch(KmdReaderAction.ToggleShelf(it))
                                }
                            }
                        )

                        Desk.Reader -> ReaderDesk(
                            work = state.selectedWork,
                            readerSession = state.readerSession,
                            readerChrome = state.readerChrome,
                            readerCompanion = state.readerCompanion,
                            readerViewport = state.readerViewport,
                            issues = state.selectedWork
                                ?.let { state.issuesByWorkId[it.id] }
                                .orEmpty(),
                            sourceSnapshot = state.selectedWork
                                ?.let { state.sourceSnapshotsByWorkId[it.id] },
                            issueFocus = state.issueFocus,
                            reviewMessage = state.deskStack.reviewMessage,
                            readerHostRestartToken = state.readerHostRestartToken,
                            runtimeBridge = runtimeBridge,
                            onBackToDetail = { dispatch(KmdReaderAction.CloseCurrentDesk) },
                            onOpenReview = { dispatch(KmdReaderAction.OpenReview) },
                            onOpenSettings = { dispatch(KmdReaderAction.OpenSettings) },
                            onOpenIssues = {
                                dispatch(KmdReaderAction.OpenReaderCompanion(ReaderCompanionType.Issues))
                            },
                            onRetryRuntime = { dispatch(KmdReaderAction.RetryReaderRuntime) },
                            onReviewDecision = { dispatch(KmdReaderAction.SetReviewMessage(it)) },
                            onSelectIssue = { dispatch(KmdReaderAction.SelectIssue(it)) },
                            onSelectSourceLine = {
                                dispatch(KmdReaderAction.SelectSourceLine(it))
                            },
                            onJumpIssueToPlayback = {
                                dispatch(KmdReaderAction.JumpIssueToPlayback(it))
                            },
                            onJumpSelectedSourceLineToPlayback = {
                                dispatch(KmdReaderAction.JumpSelectedSourceLineToPlayback)
                            },
                            onCloseIssue = { dispatch(KmdReaderAction.CloseIssue(it)) },
                            onReopenIssue = { dispatch(KmdReaderAction.ReopenIssue(it)) },
                            onStartIssueDraft = {
                                dispatch(KmdReaderAction.StartIssueDraftFromPlayback)
                            },
                            onDraftMessageChange = {
                                dispatch(KmdReaderAction.UpdateIssueDraftMessage(it))
                            },
                            onDraftSuggestionChange = {
                                dispatch(KmdReaderAction.UpdateIssueDraftSuggestion(it))
                            },
                            onSubmitIssueDraft = { dispatch(KmdReaderAction.SubmitIssueDraft) },
                            onCancelIssueDraft = { dispatch(KmdReaderAction.CancelIssueDraft) },
                            onCloseCompanion = { dispatch(KmdReaderAction.CloseReaderCompanion) },
                            onReaderHostSizeChanged = { widthPx, heightPx ->
                                dispatch(KmdReaderAction.UpdateReaderHostSize(widthPx, heightPx))
                            },
                            onPlay = { dispatch(KmdReaderAction.PlayReader) },
                            onPause = { dispatch(KmdReaderAction.PauseReader) },
                            onSeek = { dispatch(KmdReaderAction.SeekReader(it)) },
                            onReaderInteraction = { dispatch(KmdReaderAction.ReaderInteraction()) },
                            onToggleChrome = {
                                if (state.readerCompanion.active != null) {
                                    dispatch(KmdReaderAction.CloseReaderCompanion)
                                } else {
                                    dispatch(KmdReaderAction.ToggleReaderChrome())
                                }
                            },
                            onShowChrome = { dispatch(KmdReaderAction.ShowReaderChrome()) },
                            onDimChrome = { dispatch(KmdReaderAction.DimReaderChrome) },
                            onSetChromePinned = { dispatch(KmdReaderAction.SetReaderChromePinned(it)) }
                        )

                        Desk.Import -> ImportDesk(
                            importState = state.importState,
                            onPickFile = {
                                importLauncher.launch(arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "text/plain",
                                    "text/markdown"
                                ))
                            }
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

            if (state.deskStack.isReviewOpen && !isReaderActive) {
                val work = state.selectedWork
                ReviewOverlay(
                    work = work,
                    issues = work?.let { state.issuesByWorkId[it.id] }.orEmpty(),
                    reviewMessage = state.deskStack.reviewMessage,
                    onDecision = { dispatch(KmdReaderAction.SetReviewMessage(it)) },
                    onClose = { dispatch(KmdReaderAction.CloseReview) }
                )
            }

            if (state.deskStack.isSettingsOpen) {
                SettingsSheet(
                    preferences = state.readerPreferences,
                    onFontScalePreview = { dispatch(KmdReaderAction.PreviewReaderFontScale(it)) },
                    onFontScaleCommit = { dispatch(KmdReaderAction.SetReaderFontScale(it)) },
                    onThemeModeChange = { dispatch(KmdReaderAction.SetThemeMode(it)) },
                    onAutoSaveProgressChange = { dispatch(KmdReaderAction.SetAutoSaveProgress(it)) },
                    onReducedMotionChange = { dispatch(KmdReaderAction.SetReducedMotion(it)) },
                    onClose = { dispatch(KmdReaderAction.CloseSettings) }
                )
            }
        }
    }
}

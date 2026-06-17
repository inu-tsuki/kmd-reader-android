package com.example.kmd_reader.ui.screen.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.KmdSourceHighlight
import com.example.kmd_reader.domain.model.KmdSourceHighlightKind
import com.example.kmd_reader.domain.model.KmdSourceRange
import com.example.kmd_reader.domain.model.KmdSourceSnapshot
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.presentation.IssueDraft
import com.example.kmd_reader.presentation.IssueFocusState
import com.example.kmd_reader.presentation.IssueLocalStatus
import com.example.kmd_reader.presentation.ReaderCompanionLayout
import com.example.kmd_reader.presentation.ReaderCompanionPlacement
import com.example.kmd_reader.presentation.ReaderCompanionState
import com.example.kmd_reader.presentation.ReaderCompanionType
import com.example.kmd_reader.ui.component.KmdPill
import com.example.kmd_reader.ui.component.source.KmdSourceContextMode
import com.example.kmd_reader.ui.component.source.KmdSourceContextView
import com.example.kmd_reader.ui.component.source.KmdSourceLineBadge

@Composable
fun ReaderCompanionContainer(
    state: ReaderCompanionState,
    layout: ReaderCompanionLayout,
    work: Work,
    issues: List<ScriptIssue>,
    sourceSnapshot: KmdSourceSnapshot?,
    currentPlaybackLine: Int?,
    issueFocus: IssueFocusState,
    reviewMessage: String?,
    onReviewDecision: (String) -> Unit,
    onSelectIssue: (String) -> Unit,
    onSelectSourceLine: (Int) -> Unit,
    onJumpIssueToPlayback: (String) -> Unit,
    onJumpSelectedSourceLineToPlayback: () -> Unit,
    onCloseIssue: (String) -> Unit,
    onReopenIssue: (String) -> Unit,
    onStartIssueDraft: () -> Unit,
    onDraftMessageChange: (String) -> Unit,
    onDraftSuggestionChange: (String) -> Unit,
    onSubmitIssueDraft: () -> Unit,
    onCancelIssueDraft: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenIssues: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = state.active ?: return

    Box(modifier = modifier.fillMaxSize()) {
        when (active) {
            ReaderCompanionType.Review -> ReviewCompanionPanel(
                work = work,
                issues = issues,
                sourceSnapshot = sourceSnapshot,
                currentPlaybackLine = currentPlaybackLine,
                issueFocus = issueFocus,
                layout = layout,
                onSelectIssue = onSelectIssue,
                onSelectSourceLine = onSelectSourceLine,
                onJumpSelectedSourceLineToPlayback = onJumpSelectedSourceLineToPlayback,
                onStartIssueDraft = onStartIssueDraft,
                onOpenIssues = onOpenIssues
            )

            ReaderCompanionType.Issues -> IssuesCompanionPanel(
                issues = issues,
                sourceSnapshot = sourceSnapshot,
                issueFocus = issueFocus,
                layout = layout,
                onSelectIssue = onSelectIssue,
                onJumpIssueToPlayback = onJumpIssueToPlayback,
                onCloseIssue = onCloseIssue,
                onReopenIssue = onReopenIssue,
                onDraftMessageChange = onDraftMessageChange,
                onDraftSuggestionChange = onDraftSuggestionChange,
                onSubmitIssueDraft = onSubmitIssueDraft,
                onCancelIssueDraft = onCancelIssueDraft,
                onOpenReview = onOpenReview
            )

            ReaderCompanionType.CommunitySummary,
            ReaderCompanionType.WorkInfo,
            ReaderCompanionType.Diagnostics -> PlaceholderCompanionPanel(
                type = active,
                layout = layout,
                onClose = onClose
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.ReviewCompanionPanel(
    work: Work,
    issues: List<ScriptIssue>,
    sourceSnapshot: KmdSourceSnapshot?,
    currentPlaybackLine: Int?,
    issueFocus: IssueFocusState,
    layout: ReaderCompanionLayout,
    onSelectIssue: (String) -> Unit,
    onSelectSourceLine: (Int) -> Unit,
    onJumpSelectedSourceLineToPlayback: () -> Unit,
    onStartIssueDraft: () -> Unit,
    onOpenIssues: () -> Unit
) {
    val selectedIssue = issues.firstOrNull { it.id == issueFocus.selectedIssueId }
    val selectedIssueLine = selectedIssue?.let { sourceSnapshot?.lineNumberForIssue(it) }
        ?: issueFocus.focusedSourceRange?.startLine
    val selectedSourceLine = issueFocus.selectedSourceLine
    var activeContextLine by remember(work.id) { mutableStateOf<Int?>(null) }
    var scrollRequestKey by remember(work.id) { mutableStateOf(0) }
    var focusMode by remember(work.id, issueFocus.selectedIssueId) {
        mutableStateOf(
            if (selectedIssueLine != null) {
                ReviewFocusMode.FullSource
            } else {
                ReviewFocusMode.Playback
            }
        )
    }
    // 正播放跟随：默认开启，用户主动滑动或聚焦 issue/源码行时停止，点「正播放」恢复。
    var followPlayback by remember(work.id) { mutableStateOf(true) }
    val sourceFocusLine = when {
        focusMode == ReviewFocusMode.FullSource ->
            selectedSourceLine ?: selectedIssueLine ?: currentPlaybackLine
        followPlayback -> currentPlaybackLine
        else -> null
    }
    val sourceEmptyText = when {
        sourceSnapshot == null -> "源码快照载入中。"
        else -> "源码快照暂不可用。"
    }
    val issueBadges = sourceIssueBadges(
        issues = issues,
        sourceSnapshot = sourceSnapshot,
        selectedIssueId = issueFocus.selectedIssueId
    )
    val outsideBubbleInteraction = remember { MutableInteractionSource() }
    val outsideBubbleModifier = if (activeContextLine != null) {
        Modifier.clickable(
            interactionSource = outsideBubbleInteraction,
            indication = null,
            onClick = { activeContextLine = null }
        )
    } else {
        Modifier
    }

    CompanionSurface(
        layout = layout
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(outsideBubbleModifier)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KmdSourceContextView(
                snapshot = sourceSnapshot,
                modifier = Modifier.weight(1f),
                mode = KmdSourceContextMode.Full,
                focusLine = sourceFocusLine,
                scrollRequestKey = scrollRequestKey,
                highlights = sourceHighlights(
                    playbackLine = currentPlaybackLine,
                    issueLine = selectedIssueLine,
                    selectedLine = selectedSourceLine
                ),
                badges = issueBadges,
                maxHeight = null,
                showSurfaceChrome = false,
                onLineClick = { lineNumber ->
                    activeContextLine = if (activeContextLine == lineNumber) {
                        null
                    } else {
                        lineNumber
                    }
                    focusMode = ReviewFocusMode.FullSource
                    onSelectSourceLine(lineNumber)
                },
                onBadgeClick = { issueId ->
                    onSelectIssue(issueId)
                    onOpenIssues()
                },
                lineContext = { lineNumber ->
                    if (lineNumber == activeContextLine) {
                        SourceLineContextBubble(
                            lineNumber = lineNumber,
                            issueBadges = issueBadges.forLine(lineNumber),
                            isPlaybackLine = lineNumber == currentPlaybackLine,
                            onJumpToLine = {
                                onSelectSourceLine(lineNumber)
                                onJumpSelectedSourceLineToPlayback()
                                activeContextLine = null
                            },
                            onStartIssueDraft = {
                                onSelectSourceLine(lineNumber)
                                onStartIssueDraft()
                                onOpenIssues()
                                activeContextLine = null
                            },
                            onOpenIssue = { issueId ->
                                onSelectIssue(issueId)
                                onOpenIssues()
                                activeContextLine = null
                            },
                            onDismiss = { activeContextLine = null }
                        )
                    }
                },
                emptyText = sourceEmptyText,
                onUserScroll = { followPlayback = false }
            )

            ReviewCommandTray(
                currentPlaybackLine = currentPlaybackLine,
                followPlayback = followPlayback,
                onScrollToPlaybackLine = {
                    if (currentPlaybackLine != null) {
                        activeContextLine = null
                        focusMode = ReviewFocusMode.Playback
                        followPlayback = true
                        scrollRequestKey += 1
                    }
                },
                onOpenIssues = onOpenIssues
            )
        }
    }
}

private enum class ReviewFocusMode {
    Playback,
    FullSource
}

@Composable
private fun ReviewCommandTray(
    currentPlaybackLine: Int?,
    followPlayback: Boolean,
    onScrollToPlaybackLine: () -> Unit,
    onOpenIssues: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 高亮仅在「跟随中」时亮起；用户滑动/聚焦 issue 后变暗，提示点击可恢复跟随。
            KmdPill(
                text = currentPlaybackLine?.let { "正播放 L$it" } ?: "未播放",
                selected = currentPlaybackLine != null && followPlayback,
                onClick = currentPlaybackLine?.let { { onScrollToPlaybackLine() } }
            )
            Box(modifier = Modifier.weight(1f))
            CompanionSwitchButton(onClick = onOpenIssues)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceLineContextBubble(
    lineNumber: Int,
    issueBadges: List<KmdSourceLineBadge>,
    isPlaybackLine: Boolean,
    onJumpToLine: () -> Unit,
    onStartIssueDraft: () -> Unit,
    onOpenIssue: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val bubbleInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .padding(start = 31.dp, end = 4.dp, top = 1.dp, bottom = 3.dp)
            .widthIn(max = 360.dp)
            .clickable(
                interactionSource = bubbleInteraction,
                indication = null,
                onClick = {}
            ),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(7.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SourceLineBubbleChip(
                text = if (isPlaybackLine) "正播放 L$lineNumber" else "L$lineNumber",
                selected = isPlaybackLine
            )
            SourceLineBubbleChip(
                text = "跳转",
                onClick = onJumpToLine
            )
            SourceLineBubbleChip(
                text = "提 issue",
                onClick = onStartIssueDraft
            )
            issueBadges.forEach { badge ->
                SourceLineBubbleChip(
                    text = badge.label,
                    selected = badge.selected,
                    onClick = { onOpenIssue(badge.id) }
                )
            }
            SourceLineBubbleChip(text = "多选", enabled = false)
            SourceLineBubbleChip(text = "×", onClick = onDismiss)
        }
    }
}

@Composable
private fun SourceLineBubbleChip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val baseModifier = if (enabled && onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        modifier = baseModifier,
        shape = RoundedCornerShape(6.dp),
        color = when {
            !enabled -> colors.surfaceVariant.copy(alpha = 0.44f)
            selected -> colors.primaryContainer
            else -> colors.surfaceVariant.copy(alpha = 0.78f)
        },
        contentColor = when {
            !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
            selected -> colors.onPrimaryContainer
            else -> colors.onSurfaceVariant
        }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun CompanionSwitchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        onClick = onClick
    ) {
        Text(
            text = "⇄",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BoxScope.IssuesCompanionPanel(
    issues: List<ScriptIssue>,
    sourceSnapshot: KmdSourceSnapshot?,
    issueFocus: IssueFocusState,
    layout: ReaderCompanionLayout,
    onSelectIssue: (String) -> Unit,
    onJumpIssueToPlayback: (String) -> Unit,
    onCloseIssue: (String) -> Unit,
    onReopenIssue: (String) -> Unit,
    onDraftMessageChange: (String) -> Unit,
    onDraftSuggestionChange: (String) -> Unit,
    onSubmitIssueDraft: () -> Unit,
    onCancelIssueDraft: () -> Unit,
    onOpenReview: () -> Unit
) {
    val openCount = issues.count { issueFocus.statusFor(it.id).status == IssueLocalStatus.Open }
    val closedCount = issues.size - openCount

    CompanionSurface(layout = layout) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // draft 态独占主区：提 issue 时 draft 表单铺满 companion，
            // issue 台账隐藏，避免两者嵌套滚动（BUG-13 / UI-4G）。
            // 取消或提交 draft 后自动回到台账视图。
            val draft = issueFocus.issueDraft
            if (draft != null) {
                IssueDraftEditor(
                    draft = draft,
                    modifier = Modifier.weight(1f),
                    onMessageChange = onDraftMessageChange,
                    onSuggestionChange = onDraftSuggestionChange,
                    onSubmit = onSubmitIssueDraft,
                    onCancel = onCancelIssueDraft
                )
            } else if (issues.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "当前作品还没有问题。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = issues,
                        key = { _, issue -> issue.id }
                    ) { index, issue ->
                        IssueLedgerRow(
                            issueNumber = index + 1,
                            issue = issue,
                            selected = issue.id == issueFocus.selectedIssueId,
                            status = issueFocus.statusFor(issue.id).status,
                            lineNumber = sourceSnapshot?.lineNumberForIssue(issue),
                            onSelectIssue = onSelectIssue,
                            onOpenReview = onOpenReview,
                            onJumpIssueToPlayback = onJumpIssueToPlayback,
                            onCloseIssue = onCloseIssue,
                            onReopenIssue = onReopenIssue
                        )
                    }
                }
            }

            IssuesCommandTray(
                issueCount = issues.size,
                openCount = openCount,
                closedCount = closedCount,
                onOpenReview = onOpenReview
            )
        }
    }
}

@Composable
private fun IssuesCommandTray(
    issueCount: Int,
    openCount: Int,
    closedCount: Int,
    onOpenReview: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KmdPill("问题 $issueCount", selected = true)
            KmdPill("开 $openCount")
            KmdPill("关 $closedCount")
            Box(modifier = Modifier.weight(1f))
            CompanionSwitchButton(onClick = onOpenReview)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IssueLedgerRow(
    issueNumber: Int,
    issue: ScriptIssue,
    selected: Boolean,
    status: IssueLocalStatus,
    lineNumber: Int?,
    onSelectIssue: (String) -> Unit,
    onOpenReview: () -> Unit,
    onJumpIssueToPlayback: (String) -> Unit,
    onCloseIssue: (String) -> Unit,
    onReopenIssue: (String) -> Unit
) {
    val isClosed = status == IssueLocalStatus.Closed

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
        onClick = { onSelectIssue(issue.id) }
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                KmdPill("#$issueNumber", selected = selected)
                KmdPill(status.label, selected = isClosed)
                KmdPill(issue.severity.label)
                KmdPill(issue.source.label)
                KmdPill(lineNumber?.let { "L$it" } ?: issue.location)
            }
            Text(
                text = issue.message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = issue.suggestion,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (selected) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(
                    onClick = {
                        onSelectIssue(issue.id)
                        onOpenReview()
                    }
                ) {
                    Text("查看脚本")
                }
                TextButton(onClick = { onJumpIssueToPlayback(issue.id) }) {
                    Text("播放位")
                }
                if (isClosed) {
                    TextButton(onClick = { onReopenIssue(issue.id) }) {
                        Text("重开")
                    }
                } else {
                    TextButton(onClick = { onCloseIssue(issue.id) }) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueDraftEditor(
    draft: IssueDraft,
    onMessageChange: (String) -> Unit,
    onSuggestionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "新问题草稿 · ${draft.sourceRange?.lineLabel ?: "播放锚点"}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = draft.message,
                onValueChange = onMessageChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("问题描述") },
                minLines = 2
            )
            OutlinedTextField(
                value = draft.suggestion,
                onValueChange = onSuggestionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("建议处理") },
                minLines = 2
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onSubmit) {
                    Text("提交本地问题")
                }
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun BoxScope.PlaceholderCompanionPanel(
    type: ReaderCompanionType,
    layout: ReaderCompanionLayout,
    onClose: () -> Unit
) {
    CompanionSurface(
        layout = layout
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = type.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onClose) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun BoxScope.CompanionSurface(
    layout: ReaderCompanionLayout,
    content: @Composable () -> Unit
) {
    val surfaceModifier = when (layout.placement) {
        ReaderCompanionPlacement.BottomSheet -> Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .heightIn(max = 460.dp)

        ReaderCompanionPlacement.SidePanel -> Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .widthIn(min = 320.dp, max = 460.dp)

        ReaderCompanionPlacement.TopBottomBands -> Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .heightIn(max = 220.dp)

        ReaderCompanionPlacement.Overlay -> Modifier
            .align(Alignment.CenterEnd)
            .fillMaxWidth()
            .widthIn(max = 460.dp)
            .heightIn(max = 420.dp)
    }

    Surface(
        modifier = surfaceModifier.padding(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 10.dp,
        tonalElevation = 6.dp,
        content = content
    )
}

private fun sourceHighlights(
    playbackLine: Int?,
    issueLine: Int? = null,
    selectedLine: Int? = null
): List<KmdSourceHighlight> =
    listOfNotNull(
        playbackLine?.toSourceHighlight(
            kind = KmdSourceHighlightKind.Playback,
            label = "播放"
        ),
        issueLine?.toSourceHighlight(
            kind = KmdSourceHighlightKind.Issue,
            label = "Issue"
        ),
        selectedLine?.toSourceHighlight(
            kind = KmdSourceHighlightKind.Selection,
            label = "选中"
        )
    )

private fun sourceIssueBadges(
    issues: List<ScriptIssue>,
    sourceSnapshot: KmdSourceSnapshot?,
    selectedIssueId: String?
): List<KmdSourceLineBadge> =
    issues.mapIndexedNotNull { index, issue ->
        val lineNumber = sourceSnapshot?.lineNumberForIssue(issue) ?: return@mapIndexedNotNull null
        KmdSourceLineBadge(
            id = issue.id,
            lineNumber = lineNumber,
            label = "#${index + 1}",
            selected = issue.id == selectedIssueId
        )
    }

private fun List<KmdSourceLineBadge>.forLine(lineNumber: Int): List<KmdSourceLineBadge> =
    filter { badge -> badge.lineNumber == lineNumber }

private fun Int.toSourceHighlight(
    kind: KmdSourceHighlightKind,
    label: String
): KmdSourceHighlight =
    KmdSourceHighlight(
        range = KmdSourceRange(this),
        kind = kind,
        label = label
    )

private val ReaderCompanionType.label: String
    get() = when (this) {
        ReaderCompanionType.Review -> "脚本审阅"
        ReaderCompanionType.Issues -> "问题台账"
        ReaderCompanionType.CommunitySummary -> "评论摘要"
        ReaderCompanionType.WorkInfo -> "作品信息"
        ReaderCompanionType.Diagnostics -> "诊断信息"
    }

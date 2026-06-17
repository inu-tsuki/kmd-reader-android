package com.example.kmd_reader.ui.screen.reader

import android.content.Context
import android.content.pm.ActivityInfo
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.policy.ReaderCompanionPlacementPolicy
import com.example.kmd_reader.domain.model.KmdSourceSnapshot
import com.example.kmd_reader.domain.model.PresentationMode
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.presentation.IssueFocusState
import com.example.kmd_reader.presentation.ReaderCompanionState
import com.example.kmd_reader.presentation.ReaderChromeMode
import com.example.kmd_reader.presentation.ReaderChromeState
import com.example.kmd_reader.presentation.ReaderChromeVisibility
import com.example.kmd_reader.presentation.ReaderSessionPhase
import com.example.kmd_reader.presentation.ReaderSessionState
import com.example.kmd_reader.presentation.ReaderViewportState
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.runtime.ReaderRuntimeCapabilities
import com.example.kmd_reader.ui.component.InfoCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReaderDesk(
    work: Work?,
    readerSession: ReaderSessionState,
    readerChrome: ReaderChromeState,
    readerCompanion: ReaderCompanionState,
    readerViewport: ReaderViewportState,
    issues: List<ScriptIssue>,
    sourceSnapshot: KmdSourceSnapshot?,
    issueFocus: IssueFocusState,
    reviewMessage: String?,
    readerHostRestartToken: Int,
    runtimeBridge: ReaderRuntimeBridge?,
    onBackToDetail: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenIssues: () -> Unit,
    onRetryRuntime: () -> Unit,
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
    onCloseCompanion: () -> Unit,
    onReaderHostSizeChanged: (widthPx: Int, heightPx: Int) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onReaderInteraction: () -> Unit,
    onToggleChrome: () -> Unit,
    onShowChrome: () -> Unit,
    onDimChrome: () -> Unit,
    onSetChromePinned: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (work == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            InfoCard(
                title = "Reader Runtime 未加载",
                body = "请先从脚本详情进入阅读页。"
            )
        }
        return
    }

    val isPlaying = readerSession.isPlaying(work.id)
    LaunchedEffect(
        work.id,
        isPlaying,
        readerChrome.visibility,
        readerChrome.isPinned,
        readerChrome.mode
    ) {
        if (
            isPlaying &&
            !readerChrome.isPinned &&
            readerChrome.mode == ReaderChromeMode.Reading &&
            readerChrome.visibility == ReaderChromeVisibility.Visible
        ) {
            delay(3_000)
            onDimChrome()
        }
    }

    val showFullChrome = readerChrome.visibility == ReaderChromeVisibility.Visible ||
        readerChrome.mode != ReaderChromeMode.Reading
    val showDimmedChrome = readerChrome.visibility == ReaderChromeVisibility.Dimmed &&
        readerChrome.mode == ReaderChromeMode.Reading
    val hasActiveCompanion = readerCompanion.active != null
    val companionLayout = readerCompanion.active?.let { active ->
        ReaderCompanionPlacementPolicy.resolve(
            viewport = readerViewport,
            companion = readerCompanion,
            type = active
        )
    }
    val failedSession = readerSession as? ReaderSessionState.Failed
    val currentPlaybackLine = readerSession.currentLineFor(work.id)
    var showDiagnostics by remember(work.id, failedSession?.message, failedSession?.sessionId) {
        mutableStateOf(false)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val size = coordinates.size
                onReaderHostSizeChanged(size.width, size.height)
            }
    ) {
        key(readerHostRestartToken) {
            ReaderRuntimeHost(
                runtimeBridge = runtimeBridge,
                framed = false,
                onTwoFingerTap = {
                    onToggleChrome()
                },
                onRuntimeDoubleTap = {
                    if (hasActiveCompanion) {
                        onCloseCompanion()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showFullChrome) {
            ReaderTopOverlay(
                work = work,
                readerViewport = readerViewport,
                onBackToDetail = {
                    onReaderInteraction()
                    onBackToDetail()
                },
                onOpenReview = {
                    onReaderInteraction()
                    onOpenReview()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }

        if (failedSession == null) {
            RuntimeStatusOverlay(
                workId = work.id,
                readerSession = readerSession,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
        }

        if (failedSession == null && hasActiveCompanion) {
            ReaderCompanionContainer(
                state = readerCompanion,
                layout = requireNotNull(companionLayout),
                work = work,
                issues = issues,
                sourceSnapshot = sourceSnapshot,
                currentPlaybackLine = currentPlaybackLine,
                issueFocus = issueFocus,
                reviewMessage = reviewMessage,
                onReviewDecision = onReviewDecision,
                onSelectIssue = onSelectIssue,
                onSelectSourceLine = onSelectSourceLine,
                onJumpIssueToPlayback = onJumpIssueToPlayback,
                onJumpSelectedSourceLineToPlayback = onJumpSelectedSourceLineToPlayback,
                onCloseIssue = onCloseIssue,
                onReopenIssue = onReopenIssue,
                onStartIssueDraft = onStartIssueDraft,
                onDraftMessageChange = onDraftMessageChange,
                onDraftSuggestionChange = onDraftSuggestionChange,
                onSubmitIssueDraft = onSubmitIssueDraft,
                onCancelIssueDraft = onCancelIssueDraft,
                onOpenReview = onOpenReview,
                onOpenIssues = onOpenIssues,
                onClose = onCloseCompanion,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (showFullChrome) 62.dp else 0.dp)
            )
        }

        if (showFullChrome && !hasActiveCompanion) {
            PlaybackControlOverlay(
                work = work,
                readerSession = readerSession,
                readerChrome = readerChrome,
                onPlay = {
                    onReaderInteraction()
                    onPlay()
                },
                onPause = {
                    onReaderInteraction()
                    onPause()
                },
                onSeek = {
                    onReaderInteraction()
                    onSeek(it)
                },
                onSetChromePinned = onSetChromePinned,
                onReaderInteraction = onReaderInteraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            )
        } else if (showDimmedChrome && !hasActiveCompanion) {
            ReaderMinimalChrome(
                work = work,
                readerSession = readerSession,
                onShowChrome = {
                    onReaderInteraction()
                    onShowChrome()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            )
        }

        if (failedSession != null) {
            ReaderRuntimeErrorPanel(
                work = work,
                failure = failedSession,
                showDiagnostics = showDiagnostics,
                onToggleDiagnostics = {
                    onReaderInteraction()
                    showDiagnostics = !showDiagnostics
                },
                onRetry = {
                    onReaderInteraction()
                    showDiagnostics = false
                    onRetryRuntime()
                },
                onBackToDetail = {
                    onReaderInteraction()
                    onBackToDetail()
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(18.dp)
            )
        }
    }
}

@Composable
private fun ReaderTopOverlay(
    work: Work,
    readerViewport: ReaderViewportState,
    onBackToDetail: () -> Unit,
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 仅舞台类作品提供方向入口；阅读类文档保持容器自适应，不需要切方向。
    val canToggleOrientation = readerViewport.presentationMode.let {
        it == PresentationMode.Stage || it == PresentationMode.Interactive
    }
    val isHostLandscape = readerViewport.isHostLandscape

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.58f),
        contentColor = Color.White,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBackToDetail) {
                Text("返回")
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = work.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = readerViewport.chromeText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (canToggleOrientation) {
                OutlinedButton(onClick = {
                    // 主流视频 App 模式：点击只「轻推」一次方向，然后释放回跟随系统。
                    // 设定目标方向让系统执行旋转，短暂延迟后设 UNSPECIFIED，
                    // 让重力感应重新主导——用户转回竖屏时 App 自然跟随。
                    val activity = context.findActivity() ?: return@OutlinedButton
                    val target = if (isHostLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    activity.requestedOrientation = target
                    scope.launch {
                        delay(1_500)
                        activity.requestedOrientation =
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }) {
                    Text(if (isHostLandscape) "竖屏" else "横屏观看")
                }
            }
            OutlinedButton(onClick = onOpenReview) {
                Text("检查")
            }
        }
    }
}

@Composable
private fun RuntimeStatusOverlay(
    workId: String,
    readerSession: ReaderSessionState,
    modifier: Modifier = Modifier
) {
    val status = readerSession.statusFor(workId) ?: return
    val isError = readerSession is ReaderSessionState.Failed
    Surface(
        modifier = modifier,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
        } else {
            Color.Black.copy(alpha = 0.58f)
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            Color.White
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = status.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            status.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderRuntimeErrorPanel(
    work: Work,
    failure: ReaderSessionState.Failed,
    showDiagnostics: Boolean,
    onToggleDiagnostics: () -> Unit,
    onRetry: () -> Unit,
    onBackToDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "阅读 Runtime 出错",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = failure.message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RuntimeDiagnosticChip(label = "作品", value = failure.workId.ifBlank { work.id })
                RuntimeDiagnosticChip(label = "阶段", value = failure.phase?.label ?: "未知")
                RuntimeDiagnosticChip(label = "错误", value = failure.code ?: "runtime.host")
                failure.commandId?.let { RuntimeDiagnosticChip(label = "命令", value = it) }
                failure.sessionId?.let { RuntimeDiagnosticChip(label = "会话", value = it.take(8)) }
                RuntimeDiagnosticChip(
                    label = "恢复",
                    value = when (failure.recoverable) {
                        true -> "可重试"
                        false -> "需返回"
                        null -> "未知"
                    }
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onRetry) {
                    Text("重试")
                }
                OutlinedButton(onClick = onBackToDetail) {
                    Text("返回详情")
                }
                TextButton(onClick = onToggleDiagnostics) {
                    Text(if (showDiagnostics) "收起诊断" else "查看诊断")
                }
            }

            if (showDiagnostics) {
                RuntimeDiagnosticsBox(failure = failure)
            }
        }
    }
}

@Composable
private fun RuntimeDiagnosticChip(
    label: String,
    value: String
) {
    Surface(
        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = "$label: $value",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RuntimeDiagnosticsBox(failure: ReaderSessionState.Failed) {
    val scrollState = rememberScrollState()
    val diagnostics = buildString {
        appendLine("workId=${failure.workId}")
        appendLine("phase=${failure.phase?.name ?: "-"}")
        appendLine("code=${failure.code ?: "-"}")
        appendLine("commandId=${failure.commandId ?: "-"}")
        appendLine("sessionId=${failure.sessionId ?: "-"}")
        appendLine("recoverable=${failure.recoverable ?: "-"}")
        appendLine("capabilities=${failure.capabilities.toDiagnosticsText()}")
        failure.diagnostics?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            append(it)
        }
    }

    Surface(
        color = Color.Black.copy(alpha = 0.28f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = diagnostics,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(scrollState)
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PlaybackControlOverlay(
    work: Work,
    readerSession: ReaderSessionState,
    readerChrome: ReaderChromeState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSetChromePinned: (Boolean) -> Unit,
    onReaderInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val committedProgress = readerSession.progressFor(work.id)
    var pendingSeekProgress by remember(work.id) { mutableStateOf<Float?>(null) }
    val sliderProgress = pendingSeekProgress ?: committedProgress

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.68f),
        contentColor = Color.White,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Slider(
                value = sliderProgress.coerceIn(0f, 1f),
                onValueChange = { progress ->
                    pendingSeekProgress = progress
                },
                onValueChangeFinished = {
                    pendingSeekProgress?.let(onSeek)
                    pendingSeekProgress = null
                },
                enabled = readerSession.canControl(work.id),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = if (readerSession.isPlaying(work.id)) onPause else onPlay,
                    enabled = readerSession.canControl(work.id)
                ) {
                    Text(if (readerSession.isPlaying(work.id)) "暂停" else "播放")
                }
                Text(
                    text = readerSession.progressText(work.id),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = readerSession.playbackText(work.id),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = {
                        onReaderInteraction()
                        onSetChromePinned(!readerChrome.isPinned)
                    }
                ) {
                    Text(if (readerChrome.isPinned) "自动" else "固定")
                }
            }
        }
    }
}

@Composable
private fun ReaderMinimalChrome(
    work: Work,
    readerSession: ReaderSessionState,
    onShowChrome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.46f),
        contentColor = Color.White,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = work.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = readerSession.progressText(work.id),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f)
            )
            TextButton(onClick = onShowChrome) {
                Text("显示")
            }
        }
    }
}

private data class RuntimeStatusText(
    val title: String,
    val detail: String? = null
)

private fun ReaderSessionState.statusFor(workId: String): RuntimeStatusText? =
    when (this) {
        ReaderSessionState.Idle -> RuntimeStatusText("Reader Runtime 待机")
        is ReaderSessionState.Loading -> if (this.workId == workId) {
            RuntimeStatusText(
                title = phase.label,
                detail = when (phase) {
                    ReaderSessionPhase.RuntimeLoading -> "正在创建阅读会话"
                    ReaderSessionPhase.TransportReady -> "正在载入作品"
                    ReaderSessionPhase.WorkLoading -> "正在构建时间线"
                }
            )
        } else {
            RuntimeStatusText("Reader Runtime 待机")
        }
        is ReaderSessionState.Ready -> null
        is ReaderSessionState.Failed -> RuntimeStatusText(
            title = "Reader Runtime 加载失败",
            detail = listOfNotNull(message, code?.let { "code: $it" }).joinToString("\n")
        )
    }

private fun ReaderSessionState.progressFor(workId: String): Float =
    when (this) {
        is ReaderSessionState.Ready -> if (this.workId == workId) progress else 0f
        is ReaderSessionState.Loading -> if (this.workId == workId) 0.1f else 0f
        else -> 0f
    }

private fun ReaderSessionState.canControl(workId: String): Boolean =
    this is ReaderSessionState.Ready && this.workId == workId

private fun ReaderSessionState.isPlaying(workId: String): Boolean =
    this is ReaderSessionState.Ready && this.workId == workId && isPlaying

private fun ReaderSessionState.currentLineFor(workId: String): Int? =
    if (this is ReaderSessionState.Ready && this.workId == workId) {
        currentLine
    } else {
        null
    }

private fun ReaderSessionState.progressText(workId: String): String {
    val progress = progressFor(workId)
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    if (this is ReaderSessionState.Ready && this.workId == workId) {
        val time = timeMs?.let(::formatMs)
        val duration = durationMs?.let(::formatMs)
        return listOfNotNull(time, duration?.let { "/ $it" }, "$percent%")
            .joinToString(separator = " ")
    }
    return "$percent%"
}

private fun ReaderSessionState.playbackText(workId: String): String =
    when (this) {
        is ReaderSessionState.Ready -> if (this.workId == workId) {
            playbackState ?: if (isPlaying) "playing" else "ready"
        } else {
            "idle"
        }
        is ReaderSessionState.Loading -> phase.label
        is ReaderSessionState.Failed -> "error"
        ReaderSessionState.Idle -> "idle"
    }

private fun ReaderRuntimeCapabilities?.toDiagnosticsText(): String {
    if (this == null) {
        return "-"
    }
    val enabledFeatures = listOfNotNull(
        "protocol=$protocolVersion",
        "sourceText".takeIf { supportsSourceText },
        "sourceUrl".takeIf { supportsSourceUrl },
        "assets".takeIf { supportsAssetManifest },
        "seekTime".takeIf { supportsSeekTime },
        "markers".takeIf { supportsTimelineMarkers },
        "inspection".takeIf { supportsInspection },
        "interactive".takeIf { supportsInteractiveSegments }
    )
    return enabledFeatures.joinToString(separator = ", ")
}

private fun ReaderViewportState.chromeText(): String {
    val viewport = "${runtimeViewport.width}x${runtimeViewport.height}"
    val fit = if (letterboxed) "比例适配" else "填满阅读区"
    return "${presentationMode.label} · ${orientationHint.label} · $aspectRatio · $viewport · $fit"
}

private fun formatMs(value: Long): String {
    val totalSeconds = value / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}

// 从 Composable Context 取出 Activity，用于设置 requestedOrientation（横屏切换）。
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

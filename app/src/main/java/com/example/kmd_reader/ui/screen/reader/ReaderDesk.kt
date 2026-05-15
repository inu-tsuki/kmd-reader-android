package com.example.kmd_reader.ui.screen.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.OrientationHint
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.presentation.ReaderSessionState
import com.example.kmd_reader.runtime.ReaderRuntimeBridge
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.SectionTitle

@Composable
fun ReaderDesk(
    work: Work?,
    readerSession: ReaderSessionState,
    runtimeBridge: ReaderRuntimeBridge?,
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (work == null) {
        InfoCard(
            title = "Reader Runtime 未加载",
            body = "请先从脚本详情进入阅读页。",
            modifier = modifier.padding(16.dp)
        )
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle("阅读", work.title)
        ReaderRuntimeHost(
            runtimeBridge = runtimeBridge,
            modifier = Modifier
                .fillMaxWidth()
                .height(runtimeHostHeight(work))
        )
        InfoCard(
            title = readerSession.titleText(work.id),
            body = readerSession.bodyText()
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { readerSession.progressFor(work.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = "${work.presentation.mode.label} · ${work.presentation.orientationHint.label} · ${work.presentation.aspectRatio}",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenReview) {
                Text("呼起审阅")
            }
            OutlinedButton(onClick = onOpenReview) {
                Text("检查脚本")
            }
        }
        Text(
            text = "左右滑动可回到脚本详情或浏览，阅读状态不会因为切换页面而销毁。",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun ReaderSessionState.titleText(workId: String): String =
    when (this) {
        ReaderSessionState.Idle -> "Reader Runtime 待机"
        is ReaderSessionState.Loading -> if (this.workId == workId) {
            "Reader Runtime 准备中"
        } else {
            "Reader Runtime 待机"
        }
        is ReaderSessionState.Ready -> if (this.workId == workId) {
            "Reader Runtime 已就绪"
        } else {
            "Reader Runtime 待机"
        }
        is ReaderSessionState.Failed -> "Reader Runtime 加载失败"
    }

private fun ReaderSessionState.bodyText(): String =
    when (this) {
        ReaderSessionState.Idle ->
            "这里会加载本地 WebView Runtime shell，下一步再替换为 @kmd/reader-runtime-web。"
        is ReaderSessionState.Loading ->
            "正在创建 WebView 阅读会话，并等待 runtime ready 事件。"
        is ReaderSessionState.Ready ->
            "WebView D0 shell 已通过 ReaderRuntimeBridge 回传进度，审阅入口会触发一次 runtime 检查事件。"
        is ReaderSessionState.Failed ->
            message
    }

private fun ReaderSessionState.progressFor(workId: String): Float =
    when (this) {
        is ReaderSessionState.Ready -> if (this.workId == workId) progress else 0f
        is ReaderSessionState.Loading -> if (this.workId == workId) 0.1f else 0f
        else -> 0f
    }

private fun runtimeHostHeight(work: Work): Dp =
    when (work.presentation.orientationHint) {
        OrientationHint.Portrait -> 360.dp
        OrientationHint.Landscape -> 220.dp
        OrientationHint.Adaptive -> 300.dp
    }

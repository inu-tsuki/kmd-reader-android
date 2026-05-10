package com.example.kmd_reader.ui.screen.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.PreviewFrame
import com.example.kmd_reader.ui.component.SectionTitle

@Composable
fun ReaderDesk(
    work: Work?,
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
        PreviewFrame(work)
        InfoCard(
            title = "Reader Runtime 占位",
            body = "这里未来会加载 @kmd/reader-runtime-web。当前只验证不同作品形态的阅读宿主位置。"
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { 0.42f },
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

package com.example.kmd_reader.ui.screen.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.ScriptIssue
import com.example.kmd_reader.domain.model.Work
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.KmdPill
import com.example.kmd_reader.ui.component.SectionTitle

@Composable
fun ReviewOverlay(
    work: Work?,
    issues: List<ScriptIssue>,
    reviewMessage: String?,
    onDecision: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.34f))
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 10.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionTitle("脚本审阅", work?.title ?: "未选择脚本")
                InfoCard(
                    title = "审阅窗口",
                    body = "这是阅读/详情里呼起的浮层，不是独立一级页面。关闭后回到原页面。"
                )
                if (issues.isEmpty()) {
                    Text("暂无严重问题。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    issues.forEach { issue ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                KmdPill(issue.severity.label)
                                KmdPill(issue.source.label)
                                KmdPill(issue.location)
                            }
                            Text(issue.message, fontWeight = FontWeight.SemiBold)
                            Text(issue.suggestion, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                reviewMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDecision("已标记：建议通过") }) {
                        Text("通过")
                    }
                    OutlinedButton(onClick = { onDecision("已标记：退回修改") }) {
                        Text("退回")
                    }
                    OutlinedButton(onClick = { onDecision("已标记：拒绝上架") }) {
                        Text("拒绝")
                    }
                }
                OutlinedButton(onClick = onClose) {
                    Text("收起审阅")
                }
            }
        }
    }
}

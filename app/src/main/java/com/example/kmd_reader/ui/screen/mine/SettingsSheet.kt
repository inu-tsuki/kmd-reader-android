package com.example.kmd_reader.ui.screen.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.SectionTitle
import com.example.kmd_reader.ui.component.StatRow

/**
 * R3-F：设置/关于 overlay（page-architecture §7.1 要求书架页提供设置/关于入口）。
 *
 * 与 FilterOverlay 同构：半透明背景 + Surface 卡片，不新增 Desk 条带。
 * 当前只放项目说明 + 版本占位项；R3-I 设置页在此扩展阅读偏好（fontScale/主题/reducedMotion）。
 */
@Composable
fun SettingsSheet(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .padding(18.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionTitle("设置 / 关于", "项目信息和阅读偏好。")
                InfoCard(
                    title = "KMD Reader",
                    body = "KMD 作品阅读器 —— 活动桌面式导航，本地导入与社区发现。"
                )
                StatRow(label = "版本", value = "R3-F (开发中)")
                StatRow(label = "KMD Runtime", value = "WebView Host")
                StatRow(label = "阅读偏好", value = "待 R3-I 接入")
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }
}
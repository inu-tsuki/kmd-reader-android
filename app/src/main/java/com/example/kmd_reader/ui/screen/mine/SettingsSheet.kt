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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.ui.component.InfoCard
import com.example.kmd_reader.ui.component.SectionTitle
import com.example.kmd_reader.ui.component.StatRow
import com.example.kmd_reader.data.preferences.ReaderPreferences
import com.example.kmd_reader.data.preferences.ThemeMode

/**
 * 设置/关于 overlay。与 FilterOverlay 同构，不新增 Desk 条带；阅读偏好由 R3-I DataStore
 * 状态驱动，滑动中的字号预览与最终持久化使用独立回调。
 */
@Composable
fun SettingsSheet(
    preferences: ReaderPreferences,
    onFontScalePreview: (Float) -> Unit,
    onFontScaleCommit: (Float) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAutoSaveProgressChange: (Boolean) -> Unit,
    onReducedMotionChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sliderValue = remember(preferences.fontScale) { mutableFloatStateOf(preferences.fontScale) }
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
                SectionTitle("阅读设置", "")
                Text("主题")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = preferences.themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                        ) { Text(if (mode == ThemeMode.System) "跟随系统" else if (mode == ThemeMode.Light) "明亮" else "暗色") }
                    }
                }
                Text("字号 ${(preferences.fontScale * 100).toInt()}%")
                Slider(
                    value = sliderValue.floatValue,
                    onValueChange = {
                        sliderValue.floatValue = (it * 20).toInt() / 20f
                        onFontScalePreview(sliderValue.floatValue)
                    },
                    onValueChangeFinished = { onFontScaleCommit(sliderValue.floatValue) },
                    valueRange = ReaderPreferences.MIN_FONT_SCALE..ReaderPreferences.MAX_FONT_SCALE,
                    steps = 8
                )
                SettingSwitch("自动保存阅读进度", preferences.autoSaveProgress, onAutoSaveProgressChange)
                SettingSwitch("减少动态效果", preferences.reducedMotion, onReducedMotionChange)
                SectionTitle("关于", "")
                InfoCard(
                    title = "KMD Reader",
                    body = "KMD 作品阅读器 —— 活动桌面式导航，本地导入与社区发现。"
                )
                StatRow(label = "版本", value = "1.0（课程最终版）")
                StatRow(label = "KMD Runtime", value = "WebView Host")
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

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

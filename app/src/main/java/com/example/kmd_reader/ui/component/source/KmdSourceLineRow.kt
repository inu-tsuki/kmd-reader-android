package com.example.kmd_reader.ui.component.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kmd_reader.domain.model.KmdSourceHighlight
import com.example.kmd_reader.domain.model.KmdSourceHighlightKind

data class KmdSourceLineBadge(
    val id: String,
    val lineNumber: Int,
    val label: String,
    val selected: Boolean = false
)

@Composable
internal fun KmdSourceLineRow(
    lineNumber: Int,
    text: String,
    focused: Boolean,
    highlights: List<KmdSourceHighlight>,
    badges: List<KmdSourceLineBadge> = emptyList(),
    onLineClick: (Int) -> Unit,
    onBadgeClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasPlayback = highlights.any { it.kind == KmdSourceHighlightKind.Playback }
    val hasIssue = highlights.any { it.kind == KmdSourceHighlightKind.Issue }
    val hasSelection = highlights.any { it.kind == KmdSourceHighlightKind.Selection }
    val hasDiscussion = highlights.any { it.kind == KmdSourceHighlightKind.Discussion }
    val hasSearch = highlights.any { it.kind == KmdSourceHighlightKind.Search }
    val isMarked = focused || highlights.isNotEmpty()

    val rowColor = when {
        hasPlayback && (hasIssue || hasSelection) ->
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.62f)
        hasPlayback -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.56f)
        hasIssue || hasSelection -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        hasDiscussion -> MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.14f)
        hasSearch -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)
        else -> Color.Transparent
    }
    val accentColor = when {
        hasPlayback && (hasIssue || hasSelection) -> MaterialTheme.colorScheme.secondary
        hasPlayback -> MaterialTheme.colorScheme.tertiary
        hasIssue || hasSelection -> MaterialTheme.colorScheme.primary
        hasDiscussion -> MaterialTheme.colorScheme.surfaceTint
        hasSearch -> MaterialTheme.colorScheme.outline
        focused -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val textColor = when {
        hasPlayback && (hasIssue || hasSelection) -> MaterialTheme.colorScheme.onSecondaryContainer
        hasPlayback -> MaterialTheme.colorScheme.onTertiaryContainer
        hasIssue || hasSelection -> MaterialTheme.colorScheme.onPrimaryContainer
        focused -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowColor, RoundedCornerShape(6.dp))
            .clickable { onLineClick(lineNumber) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 20.dp)
                .background(accentColor, RoundedCornerShape(4.dp))
        )
        Text(
            text = lineNumber.toString().padStart(3, ' '),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isMarked) {
                accentColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f)
            }
        )
        Text(
            text = text.ifBlank { " " },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            fontWeight = if (isMarked) FontWeight.SemiBold else FontWeight.Normal
        )
        badges.forEach { badge ->
            Text(
                text = badge.label,
                modifier = Modifier
                    .background(
                        if (badge.selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        RoundedCornerShape(5.dp)
                    )
                    .clickable { onBadgeClick(badge.id) }
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (badge.selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

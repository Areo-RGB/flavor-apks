package com.paul.sprintsync.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class CardHighlightIntent {
    NONE,
    ACTIVE,
    WARNING,
    ERROR,
}

@Composable
fun SprintSyncCard(
    modifier: Modifier = Modifier,
    highlightIntent: CardHighlightIntent = CardHighlightIntent.NONE,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = when (highlightIntent) {
        CardHighlightIntent.ACTIVE -> MaterialTheme.colorScheme.secondaryContainer
        CardHighlightIntent.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        CardHighlightIntent.ERROR -> MaterialTheme.colorScheme.errorContainer
        CardHighlightIntent.NONE -> MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = when (highlightIntent) {
        CardHighlightIntent.ACTIVE -> MaterialTheme.colorScheme.onSecondaryContainer
        CardHighlightIntent.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        CardHighlightIntent.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        CardHighlightIntent.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

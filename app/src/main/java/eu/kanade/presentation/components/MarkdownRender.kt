package eu.kanade.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun MarkdownRender(
    content: String,
    modifier: Modifier = Modifier,
) {
    MarkdownText(
        markdown = content,
        modifier = modifier.fillMaxWidth(),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
    )
}

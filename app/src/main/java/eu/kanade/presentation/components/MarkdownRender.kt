package eu.kanade.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.Markdown

@Composable
fun MarkdownRender(
    content: String,
    modifier: Modifier = Modifier,
) {
    Markdown(
        content = content,
        modifier = modifier.fillMaxWidth(),
    )
}

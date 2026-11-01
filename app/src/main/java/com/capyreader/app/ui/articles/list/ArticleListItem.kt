package com.capyreader.app.ui.articles.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.capyreader.app.ui.articles.FaviconBadge
import com.capyreader.app.ui.components.ProvideContentColorTextStyle
import com.capyreader.app.ui.theme.CapyTheme

@Composable
fun ArticleListItem(
    headlineContent: @Composable () -> Unit,
    overlineContent: @Composable() (() -> Unit)? = null,
    supportingContent: @Composable() (() -> Unit)? = null,
    leadingContent: @Composable() (() -> Unit)? = null,
    trailingContent: @Composable() (() -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    compact: Boolean = false,
) {
    Surface(
        color = colors.containerColor,
    ) {
        val leadingSize = 16.dp

        val verticalSpacing = if (compact) CompactVerticalSpacing else VerticalSpacing
        val topPadding = if (compact) CompactTopPadding else TopPadding
        val bottomPadding = if (compact) CompactBottomPadding else BottomPadding

        val overlineStartPadding = if (leadingContent != null) {
            leadingSize + verticalSpacing
        } else {
            0.dp
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compact) Modifier.heightIn(min = CompactMinHeight) else Modifier)
                .padding(
                    top = topPadding,
                    start = VerticalPadding,
                    end = verticalSpacing + 4.dp,
                    bottom = bottomPadding,
                )
        ) {
            if (!compact) {
                overlineContent?.let {
                    Row(Modifier.padding(start = overlineStartPadding)) {
                        ProvideTextStyleFromToken(
                            colors.overlineContentColor,
                            typography.labelSmall,
                            it,
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(verticalSpacing)
            ) {
                leadingContent?.let {
                    Column(Modifier.size(leadingSize)) { it() }
                }
                Column(Modifier.weight(1f)) {
                    ProvideTextStyleFromToken(
                        colors.contentColor,
                        typography.bodyLarge,
                    ) {
                        headlineContent()
                    }

                    if (compact) {
                        overlineContent?.let {
                            Row(Modifier.padding(top = CompactOverlineTopPadding)) {
                                ProvideTextStyleFromToken(
                                    colors.overlineContentColor,
                                    typography.labelSmall,
                                    it,
                                )
                            }
                        }
                    }

                    supportingContent?.let {
                        ProvideTextStyleFromToken(
                            colors.supportingContentColor,
                            typography.bodyMedium,
                            it,
                        )
                    }
                }
                trailingContent?.let {
                    Column {
                        ProvideTextStyleFromToken(
                            colors.trailingContentColor,
                            typography.labelSmall,
                            it,
                        )
                    }
                }
            }
        }
    }
}

private val VerticalSpacing = 16.dp

private val VerticalPadding = 16.dp

private val TopPadding = 8.dp

private val BottomPadding = 16.dp

private val CompactVerticalSpacing = 8.dp

private val CompactTopPadding = 8.dp

private val CompactBottomPadding = 8.dp

private val CompactOverlineTopPadding = 2.dp

private val CompactMinHeight = 48.dp

@Composable
private fun ProvideTextStyleFromToken(
    color: Color,
    textStyle: TextStyle,
    content: @Composable () -> Unit,
) =
    ProvideContentColorTextStyle(
        contentColor = color,
        textStyle = textStyle,
        content = content
    )

@Preview
@Composable
private fun ArticleListItemPreview() {
    CapyTheme {
        ArticleListItem(
            headlineContent = {
                Text("Headline")
            },
            overlineContent = {
                Text("Overline")
            },
            supportingContent = {
                Text("Supporting")
            },
            leadingContent = {
                FaviconBadge(url = null)
            },
            trailingContent = {
                Text("Trailing")
            },
        )
    }
}

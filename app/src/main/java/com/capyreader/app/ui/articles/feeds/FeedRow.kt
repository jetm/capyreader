package com.capyreader.app.ui.articles.feeds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.capyreader.app.R
import com.capyreader.app.ui.articles.CountBadge
import com.capyreader.app.ui.articles.FaviconBadge
import com.capyreader.app.ui.articles.ListTitle
import com.capyreader.app.ui.articles.list.FeedActionMenu
import com.capyreader.app.ui.fixtures.FeedSample
import com.jocmp.capy.Feed
import com.jocmp.capy.accounts.Source

@Composable
fun FeedRow(
    selected: Boolean,
    feed: Feed,
    onSelect: (feed: Feed) -> Unit,
    onMarkAllRead: () -> Unit = {},
    showContextMenu: Boolean = true,
    source: Source = Source.LOCAL,
) {
    val (showMenu, setShowMenu) = remember { mutableStateOf(false) }

   Box {
       DrawerItem(
           icon = {
               FaviconBadge(url = feed.faviconURL)
           },
           label = { ListTitle(feed.title) },
           badge = {
               Row(
                   verticalAlignment = Alignment.CenterVertically,
                   horizontalArrangement = Arrangement.spacedBy(8.dp),
               ) {
                   if (feed.errorMessage != null) {
                       Icon(
                           Icons.Rounded.ErrorOutline,
                           contentDescription = stringResource(R.string.feed_error_indicator),
                           modifier = Modifier.size(18.dp),
                       )
                   }
                   CountBadge(count = feed.count, showBadge = feed.showUnreadBadge)
               }
           },
           selected = selected,
           onClick = {
               onSelect(feed)
           },
           onLongClick = {
               if (showContextMenu) {
                   setShowMenu(true)
               }
           }
       )

       if (showContextMenu) {
           FeedActionMenu(
               expanded = showMenu,
               feed = feed,
               onDismissMenuRequest = {
                   setShowMenu(false)
               },
               onMarkAllRead = onMarkAllRead,
               source = source,
           )
       }
   }
}

@Preview
@Composable
fun FeedRowPreview() {
    val feed = FeedSample().values.take(1).first()

    MaterialTheme {
        FeedRow(
            feed = feed,
            onSelect = {},
            selected = false
        )
    }
}

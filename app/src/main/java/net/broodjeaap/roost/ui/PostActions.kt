package net.broodjeaap.roost.ui

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.broodjeaap.roost.data.Feed
import net.broodjeaap.roost.data.Post

/** Height of the action buttons; the share icon (18 dp plus 7 dp padding) matches it. */
private val ACTION_HEIGHT = 32.dp

/**
 * Score plus small buttons under a post: its comments ([onOpenComments]; null where the thread is
 * already open), its subreddit, its author, and share, which copies the link.
 */
@Composable
fun PostActions(post: Post, state: AppState, onOpenComments: (() -> Unit)?, modifier: Modifier = Modifier) {
    // A compact row: Material would pad every button to a 48 dp touch target, which made the row
    // (and the share icon) look oversized next to its small labels.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        ActionRow(post, state, onOpenComments, modifier)
    }
}

@Composable
private fun ActionRow(post: Post, state: AppState, onOpenComments: (() -> Unit)?, modifier: Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val comments = "${count(post.commentCount)} comments"
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            post.score?.let { "${count(it)} points" } ?: "score hidden",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        if (onOpenComments != null) {
            ActionButton(comments, onClick = onOpenComments)
        } else {
            Text(
                "· $comments",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        // Long names give way (ellipsized) before the share button does.
        ActionButton("r/${post.subreddit}", Modifier.weight(1f, fill = false)) {
            state.openFeed(Feed.Subreddit(post.subreddit))
        }
        if (post.author != "[deleted]") {
            ActionButton("u/${post.author}", Modifier.weight(1f, fill = false)) {
                state.openFeed(Feed.User(post.author))
            }
        }
        Icon(
            Icons.Filled.Share,
            contentDescription = "Copy link",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable {
                    // Android shows its own "copied" confirmation.
                    val link = "https://www.reddit.com${post.permalink}"
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(post.title, link))) }
                }
                .padding(7.dp)
                .size(18.dp),
        )
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp),
        // Overrides the button's 40 dp default minimum height.
        modifier = modifier.heightIn(min = ACTION_HEIGHT),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

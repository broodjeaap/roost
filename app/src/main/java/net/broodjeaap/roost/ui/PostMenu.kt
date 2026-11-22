package net.broodjeaap.roost.ui

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import kotlinx.coroutines.launch
import net.broodjeaap.roost.data.Feed
import net.broodjeaap.roost.data.Post

/**
 * Makes [content] a post: tap runs [onOpen] (null where the post is already open), long press
 * shows the post's actions where the finger is.
 */
@Composable
fun PostPressable(post: Post, state: AppState, onOpen: (() -> Unit)?, content: @Composable () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var pressAt by remember { mutableStateOf(IntOffset.Zero) }
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxWidth()
            // Only observes where each press starts; the clickable below still handles the gesture.
            .pointerInput(Unit) {
                awaitEachGesture {
                    pressAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position.round()
                }
            }
            .combinedClickable(
                onClick = { onOpen?.invoke() },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            )
    ) {
        content()
        // A zero-size anchor at the press point; the menu opens there and flips near screen edges.
        Box(
            Modifier
                .offset { pressAt }
                .size(0.dp)
        ) {
            PostMenu(post, state, expanded = menuOpen, showOpen = onOpen != null) { menuOpen = false }
        }
    }
}

@Composable
private fun PostMenu(post: Post, state: AppState, expanded: Boolean, showOpen: Boolean, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val item: @Composable (String, () -> Unit) -> Unit = { label, action ->
        DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); action() })
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showOpen) item("Open comments") { state.openThread(post.permalink, post) }
        item("r/${post.subreddit}") { state.openFeed(Feed.Subreddit(post.subreddit)) }
        if (post.author != "[deleted]") item("u/${post.author}") { state.openFeed(Feed.User(post.author)) }
        item("Share (copy link)") {
            // Android shows its own "copied" confirmation.
            val link = "https://www.reddit.com${post.permalink}"
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(post.title, link))) }
        }
    }
}

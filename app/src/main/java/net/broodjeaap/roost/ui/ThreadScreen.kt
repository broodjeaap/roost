package net.broodjeaap.roost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.broodjeaap.roost.data.CommentNode
import net.broodjeaap.roost.data.Post

/** One visible line of the flattened comment tree. */
private sealed interface CommentRow {
    val key: String
    val depth: Int

    /** [hidden] is the number of replies folded away, or null when expanded. */
    data class Item(val comment: CommentNode.Comment, override val depth: Int, val hidden: Int?) : CommentRow {
        override val key get() = comment.id
    }

    data class More(val more: CommentNode.More, override val depth: Int) : CommentRow {
        override val key get() = "more-${more.id}"
    }

    data class Continue(val node: CommentNode.Continue, override val depth: Int) : CommentRow {
        override val key get() = "continue-${node.url}"
    }
}

/** Boost-style depth colours, cycling. */
private val depthColors = listOf(
    Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFFFFC107), Color(0xFFFF5722), Color(0xFF9C27B0), Color(0xFF00BCD4),
)

private fun descendants(node: CommentNode): Int = when (node) {
    is CommentNode.Comment -> node.children.sumOf { 1 + descendants(it) }
    is CommentNode.More -> node.count ?: 0
    is CommentNode.Continue -> 0
}

private fun flatten(nodes: List<CommentNode>, thread: ThreadState, depth: Int = 0, out: MutableList<CommentRow> = mutableListOf()): List<CommentRow> {
    for (node in nodes) {
        when (node) {
            is CommentNode.Comment -> {
                val collapsed = thread.collapsed[node.id] ?: node.collapsedByDefault
                out += CommentRow.Item(node, depth, if (collapsed) descendants(node) else null)
                if (!collapsed) flatten(node.children, thread, depth + 1, out)
            }
            is CommentNode.More -> out += CommentRow.More(node, depth)
            is CommentNode.Continue -> out += CommentRow.Continue(node, depth)
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(thread: ThreadState, state: AppState) {
    val post = thread.post
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { state.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(post?.let { "r/${it.subreddit}" } ?: "Thread") },
            )
        },
    ) { padding ->
        val rows = flatten(thread.comments, thread)
        LazyColumn(
            state = thread.listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (post != null) item(key = "post") { PostDetail(post, state) }
            val error = thread.error
            when {
                error != null -> item(key = "error") {
                    ErrorPanel(error, state) { state.loadThread(thread) }
                }
                thread.loading && thread.comments.isEmpty() -> item(key = "loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is CommentRow.Item -> CommentItem(row, state) { state.toggleCollapsed(thread, row.comment) }
                    is CommentRow.More -> ActionRow(
                        row.depth,
                        when {
                            thread.loadingMore[row.more.id] == true -> "Loading…"
                            row.more.count != null -> "Load ${row.more.count} more"
                            else -> "Load more comments"
                        },
                    ) { state.loadMore(thread, row.more.id) }
                    is CommentRow.Continue -> ActionRow(row.depth, "Continue this thread →") {
                        state.openUrl(row.node.url)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostDetail(post: Post, state: AppState) {
    Column(
        Modifier.padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PostMeta(post, Modifier.padding(horizontal = 12.dp))
        Text(post.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 12.dp))
        PostTags(post, Modifier.padding(horizontal = 12.dp))
        PostMedia(post, state, inFeed = false, onOpen = { state.openUrl(post.url) })
        post.bodyHtml?.let { html ->
            Text(
                rememberHtml(html, state::openUrl),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        PostStats(post, Modifier.padding(horizontal = 12.dp))
    }
    HorizontalDivider(thickness = 6.dp, color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun DepthIndent(depth: Int, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        if (depth > 0) {
            Spacer(Modifier.width((depth * 10).dp))
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(depthColors[(depth - 1) % depthColors.size])
            )
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun CommentItem(row: CommentRow.Item, state: AppState, onToggle: () -> Unit) {
    val comment = row.comment
    val authorColor = when {
        comment.moderator -> Color(0xFF4CAF50)
        comment.submitter -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    DepthIndent(row.depth) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = authorColor, fontWeight = FontWeight.SemiBold)) { append(comment.author) }
                    withStyle(SpanStyle(color = muted)) {
                        append(" · ")
                        append(comment.score?.let { "${count(it)} pts" } ?: "[score hidden]")
                        append(" · ")
                        append(age(comment.createdMillis))
                        row.hidden?.let { append("  (+$it)") }
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            if (row.hidden == null) {
                Text(rememberHtml(comment.bodyHtml, state::openUrl), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun ActionRow(depth: Int, label: String, onClick: () -> Unit) {
    DepthIndent(depth) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

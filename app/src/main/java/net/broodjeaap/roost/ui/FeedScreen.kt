package net.broodjeaap.roost.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.broodjeaap.roost.data.BlockedException
import net.broodjeaap.roost.data.Feed
import net.broodjeaap.roost.data.NotLoggedInException
import net.broodjeaap.roost.data.Post
import net.broodjeaap.roost.data.Sort
import net.broodjeaap.roost.data.TimeFilter
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(state: AppState) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            SubredditDrawer(state) { feed ->
                scope.launch { drawer.close() }
                state.openFeed(feed)
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Subreddits")
                        }
                    },
                    title = {
                        Column {
                            Text(state.feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (state.sort.timed) "${state.sort.label} · ${state.time.label}" else state.sort.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        SortMenu(state)
                        OverflowMenu(state)
                    },
                )
            },
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                val error = state.feedError
                when {
                    state.posts.isEmpty() && error != null -> ErrorPanel(error, state, state::retryFeed)
                    state.posts.isEmpty() && state.feedLoading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> LazyColumn(state = state.feedListState, modifier = Modifier.fillMaxSize()) {
                        items(state.posts, key = { it.id }) { post ->
                            PostCard(post, state)
                        }
                        item(key = "footer") { FeedFooter(state) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostCard(post: Post, state: AppState) {
    val open = { state.openThread(post.permalink, post) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = open)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PostMeta(post, Modifier.padding(horizontal = 12.dp))
        Text(post.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
        PostTags(post, Modifier.padding(horizontal = 12.dp))
        PostMedia(post, state, inFeed = true, onOpen = open)
        PostStats(post, Modifier.padding(horizontal = 12.dp))
    }
    HorizontalDivider(thickness = 6.dp, color = MaterialTheme.colorScheme.surfaceVariant)
}

/** Reaching this item is what loads the next page; nothing is fetched ahead of scrolling. */
@Composable
private fun FeedFooter(state: AppState) {
    val error = state.feedError
    Box(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            error != null -> ErrorPanel(error, state, state::retryFeed)
            state.feedLoading -> CircularProgressIndicator()
            state.nextUrl != null -> LaunchedEffect(state.nextUrl) { state.loadNext() }
            else -> Text("End of the list", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SortMenu(state: AppState) {
    var open by remember { mutableStateOf(false) }
    var timedSort by remember { mutableStateOf<Sort?>(null) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Sort") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Sort.available(state.feed).forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label) },
                    onClick = {
                        open = false
                        if (sort.timed) timedSort = sort else state.setSort(sort)
                    },
                )
            }
        }
        DropdownMenu(expanded = timedSort != null, onDismissRequest = { timedSort = null }) {
            TimeFilter.entries.forEach { time ->
                DropdownMenuItem(
                    text = { Text(time.label) },
                    onClick = {
                        timedSort?.let { state.setSort(it, time) }
                        timedSort = null
                    },
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Refresh") }, onClick = { open = false; state.refresh() })
            DropdownMenuItem(text = { Text("Show page") }, onClick = { open = false; state.overlay = Overlay.PAGE })
            DropdownMenuItem(text = { Text("Log in") }, onClick = { open = false; state.overlay = Overlay.LOGIN })
        }
    }
}

@Composable
private fun SubredditDrawer(state: AppState, onSelect: (Feed) -> Unit) {
    val subscriptions by state.subscriptions.collectAsState()
    var input by remember { mutableStateOf("") }
    val go = {
        val name = input.trim().removePrefix("/").removePrefix("r/").trim('/')
        if (name.isNotEmpty()) {
            input = ""
            onSelect(Feed.Subreddit(name))
        }
    }
    ModalDrawerSheet {
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Go to subreddit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = go) { Text("Go") }
                }
            }
            item { DrawerItem("Front page", state.feed == Feed.Front) { onSelect(Feed.Front) } }
            listOf("popular", "all").forEach { name ->
                item(key = "fixed-$name") {
                    DrawerItem("r/$name", state.feed == Feed.Subreddit(name)) { onSelect(Feed.Subreddit(name)) }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text(
                    "Subscriptions",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
            items(subscriptions, key = { "sub-$it" }) { name ->
                DrawerItem("r/$name", state.feed == Feed.Subreddit(name)) { onSelect(Feed.Subreddit(name)) }
            }
        }
    }
}

@Composable
private fun DrawerItem(label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .height(48.dp),
    )
}

/** Explains a failed load and offers what can be done about it; blocks are shown, never retried automatically. */
@Composable
fun ErrorPanel(error: Throwable, state: AppState, onRetry: () -> Unit) {
    val message = when (error) {
        is NotLoggedInException -> "Not logged in."
        is BlockedException -> {
            val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(error.retryAtMillis))
            "Reddit served a block page (${error.reason}). Nothing is loaded before $until."
        }
        else -> error.message ?: error.toString()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error is NotLoggedInException) {
                Button(onClick = { state.overlay = Overlay.LOGIN }) { Text("Log in") }
            } else {
                OutlinedButton(onClick = { state.overlay = Overlay.PAGE }) { Text("Show page") }
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

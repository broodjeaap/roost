package net.broodjeaap.roost.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import net.broodjeaap.roost.data.PageTab
import net.broodjeaap.roost.data.WebViewRedditSource

/**
 * The app draws over the two hidden WebViews (feed and thread), which stay attached and laid out
 * underneath so Reddit's pages behave as in a normal browser. Overlays (login, a block page,
 * debugging) reveal one of them: login and the feed use the feed tab, an open thread its own tab.
 */
@Composable
fun RoostApp(state: AppState, source: WebViewRedditSource) {
    val overlay = state.overlay
    BackHandler(enabled = overlay != null || state.threads.isNotEmpty()) { state.back() }
    val shown = if (overlay == Overlay.PAGE && state.threads.isNotEmpty()) source.threadTab else source.feedTab

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (overlay != null) OverlayContent(overlay, state, shown, source)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (overlay != null) Modifier.navigationBarsPadding().imePadding() else Modifier)
            ) {
                listOf(source.feedTab, source.threadTab).forEach { tab ->
                    AndroidView(
                        factory = { tab.webView },
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (tab === shown) 1f else 0f),
                    )
                }
            }
        }
        if (overlay == null) {
            Surface(Modifier.fillMaxSize()) {
                val thread = state.threads.lastOrNull()
                if (thread != null) ThreadScreen(thread, state) else FeedScreen(state)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlayContent(overlay: Overlay, state: AppState, tab: PageTab, source: WebViewRedditSource) {
    val url by tab.currentUrl.collectAsState()
    var json by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (overlay == Overlay.LOGIN) {
        var sawAuthPage by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { tab.webView.loadUrl(WebViewRedditSource.LOGIN_URL) }
        // Reddit leaves the login pages once the session exists.
        LaunchedEffect(url) {
            val current = url ?: return@LaunchedEffect
            val onAuthPage = listOf("/login", "/account", "/register", "/password").any { it in current }
            if (onAuthPage) sawAuthPage = true
            else if (sawAuthPage && current.contains("reddit.com")) state.loginFinished()
        }
    }

    TopAppBar(
        title = {
            Column {
                Text(if (overlay == Overlay.LOGIN) "Log in to Reddit" else "Reddit page")
                Text(
                    url.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            if (overlay == Overlay.PAGE) {
                TextButton(onClick = {
                    scope.launch {
                        json = runCatching { source.extract(tab).toString(2).replace("\\/", "/") }
                            .getOrElse { it.toString() }
                    }
                }) { Text("Extract") }
            }
            IconButton(onClick = { state.overlay = null }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        },
    )
    json?.let { text ->
        Surface(tonalElevation = 2.dp, modifier = Modifier.heightIn(max = 360.dp)) {
            Column {
                TextButton(onClick = { json = null }) { Text("Close JSON") }
                SelectionContainer(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

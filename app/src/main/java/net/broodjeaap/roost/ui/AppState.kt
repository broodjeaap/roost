package net.broodjeaap.roost.ui

import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.broodjeaap.roost.data.CommentNode
import net.broodjeaap.roost.data.Feed
import net.broodjeaap.roost.data.Listing
import net.broodjeaap.roost.data.NotLoggedInException
import net.broodjeaap.roost.data.Post
import net.broodjeaap.roost.data.RedditSource
import net.broodjeaap.roost.data.Sort
import net.broodjeaap.roost.data.SubredditMatch
import net.broodjeaap.roost.data.TimeFilter

/** What sits on top of the app, revealing the shared WebView. */
enum class Overlay { LOGIN, PAGE }

class ThreadState(val permalink: String, initialPost: Post?) {
    var post by mutableStateOf(initialPost)
    var comments by mutableStateOf<List<CommentNode>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    /** Comment id -> collapsed; absent means Reddit's default. */
    val collapsed = mutableStateMapOf<String, Boolean>()
    /** "load more" ids currently being fetched. */
    val loadingMore = mutableStateMapOf<String, Boolean>()
    val listState = LazyListState()
}

class AppState(
    private val source: RedditSource,
    private val scope: CoroutineScope,
    private val openExternal: (String) -> Unit,
) {
    var feed by mutableStateOf<Feed>(Feed.Front)
        private set
    var sort by mutableStateOf(Sort.default(Feed.Front))
        private set
    var time by mutableStateOf(TimeFilter.DAY)
        private set
    var posts by mutableStateOf<List<Post>>(emptyList())
        private set
    /** False once the feed has no more posts to load (or before the first page arrives). */
    var hasMore by mutableStateOf(false)
        private set
    var feedLoading by mutableStateOf(false)
        private set
    var feedError by mutableStateOf<Throwable?>(null)
        private set

    /** Open threads, innermost last; empty means the feed is showing. */
    val threads = mutableStateListOf<ThreadState>()

    var overlay by mutableStateOf<Overlay?>(null)

    /** Id of the post whose video is playing; only one plays at a time. */
    var playing by mutableStateOf<String?>(null)

    /** Kept here so the scroll position survives opening a thread; replaced when the feed changes. */
    var feedListState by mutableStateOf(LazyListState())
        private set

    val subscriptions = source.subscriptions

    private var feedJob: Job? = null

    /** Set by [loginFinished] until the next successful load; stops login → reload → login loops. */
    private var justLoggedIn = false

    private data class FeedEntry(val feed: Feed, val sort: Sort, val time: TimeFilter)

    /** Feeds to return to on back, most recent last. */
    private val feedHistory = mutableStateListOf<FeedEntry>()

    /** Whether back stays in the app; when false it goes to the system (closing the app). */
    val canGoBack: Boolean
        get() = overlay != null || threads.isNotEmpty() || feedHistory.isNotEmpty()

    /** Switches to another feed; back returns to the current one. */
    fun openFeed(feed: Feed, sort: Sort = Sort.default(feed), time: TimeFilter = this.time) {
        if (feed != this.feed) feedHistory.add(FeedEntry(this.feed, this.sort, this.time))
        show(feed, sort, time)
    }

    /** Re-sorts the current feed; not a separate back step. */
    fun setSort(sort: Sort, time: TimeFilter = this.time) = show(feed, sort, time)

    private fun show(feed: Feed, sort: Sort, time: TimeFilter) {
        this.feed = feed
        this.sort = sort
        this.time = time
        threads.clear()
        playing = null
        refresh()
    }

    fun refresh() {
        feedJob?.cancel()
        posts = emptyList()
        feedListState = LazyListState()
        hasMore = false
        loadFeed { source.listing(feed, sort, time) }
    }

    /** Called when the end of the list becomes visible. */
    fun loadNext() {
        if (!hasMore || feedLoading || feedError != null) return
        loadFeed { source.morePosts() }
    }

    fun retryFeed() {
        feedError = null
        if (posts.isEmpty()) refresh() else loadNext()
    }

    private fun loadFeed(block: suspend () -> Listing) {
        feedJob = scope.launch {
            feedLoading = true
            feedError = null
            try {
                val listing = block()
                val known = posts.mapTo(HashSet()) { it.id }
                posts = posts + listing.posts.filter { it.id !in known }
                hasMore = listing.hasMore
                justLoggedIn = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feedError = e
                onError(e)
            } finally {
                feedLoading = false
            }
        }
    }

    fun openThread(permalink: String, post: Post? = null) {
        val thread = ThreadState(permalink, post)
        playing = null
        threads.add(thread)
        loadThread(thread)
    }

    fun loadThread(thread: ThreadState) {
        scope.launch {
            thread.loading = true
            thread.error = null
            try {
                val result = source.thread(thread.permalink)
                thread.post = result.post
                thread.comments = result.comments
                justLoggedIn = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thread.error = e
                onError(e)
            } finally {
                thread.loading = false
            }
        }
    }

    fun loadMore(thread: ThreadState, moreId: String) {
        if (thread.loadingMore[moreId] == true) return
        scope.launch {
            thread.loadingMore[moreId] = true
            try {
                thread.comments = source.loadMoreComments(thread.permalink, moreId).comments
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thread.error = e
                onError(e)
            } finally {
                thread.loadingMore.remove(moreId)
            }
        }
    }

    fun toggleCollapsed(thread: ThreadState, comment: CommentNode.Comment) {
        thread.collapsed[comment.id] = !(thread.collapsed[comment.id] ?: comment.collapsedByDefault)
    }

    /** Back press: returns false when there is nothing left to close. */
    fun back(): Boolean = when {
        overlay != null -> {
            overlay = null
            true
        }
        threads.isNotEmpty() -> {
            threads.removeAt(threads.lastIndex)
            playing = null
            true
        }
        feedHistory.isNotEmpty() -> {
            val previous = feedHistory.removeAt(feedHistory.lastIndex)
            // The hidden feed page has moved on, so the previous feed loads again.
            show(previous.feed, previous.sort, previous.time)
            true
        }
        else -> false
    }

    suspend fun searchSubreddits(query: String): List<SubredditMatch> = source.searchSubreddits(query)

    fun loginFinished() {
        overlay = null
        justLoggedIn = true
        if (threads.isEmpty()) refresh() else loadThread(threads.last())
    }

    /** Reddit links open in the app; everything else in the browser. */
    fun openUrl(url: String) {
        val uri = Uri.parse(if (url.startsWith("/")) "https://www.reddit.com$url" else url)
        val host = uri.host.orEmpty()
        val segments = uri.pathSegments
        val isReddit = host == "reddit.com" || host.endsWith(".reddit.com")
        when {
            isReddit && segments.size >= 3 && segments[0] == "r" && segments[2] == "comments" ->
                openThread(uri.path.orEmpty())
            isReddit && segments.size >= 2 && segments[0] == "comments" -> openThread(uri.path.orEmpty())
            isReddit && segments.size == 2 && segments[0] == "r" -> openFeed(Feed.Subreddit(segments[1]))
            isReddit && segments.size >= 2 && segments[0] in setOf("u", "user") -> openFeed(Feed.User(segments[1]))
            else -> openExternal(uri.toString())
        }
    }

    private fun onError(e: Exception) {
        if (e !is NotLoggedInException) return
        // Straight after a login, "not logged in" means the login didn't take; show the error instead.
        if (justLoggedIn) justLoggedIn = false else overlay = Overlay.LOGIN
    }
}

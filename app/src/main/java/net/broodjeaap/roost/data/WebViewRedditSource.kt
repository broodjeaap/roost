package net.broodjeaap.roost.data

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume

private const val TAG = "Roost"

/** New Reddit's own host: always the new site, even for accounts whose preference is old Reddit. */
private const val BASE_URL = "https://sh.reddit.com/"
private const val LOAD_TIMEOUT_MS = 30_000L
private const val THREAD_TIMEOUT_MS = 8_000L
private const val SCROLL_TIMEOUT_MS = 10_000L
private const val MORE_TIMEOUT_MS = 15_000L
private const val NAV_TIMEOUT_MS = 5_000L
private const val POLL_MS = 250L
private const val INITIAL_BACKOFF_MS = 60_000L
private const val MAX_BACKOFF_MS = 30 * 60_000L
private val MORE_ID = Regex("^(t1_[a-z0-9]+|${CommentNode.More.TOP})$")

/** One hidden browser tab: a WebView plus what its client reports about the current page. */
class PageTab internal constructor(val webView: WebView) {
    internal var pageLoad: CompletableDeferred<Int>? = null
    internal var status = 200

    private val _currentUrl = MutableStateFlow<String?>(null)
    /** Whatever the WebView shows, including pages the user navigates to while it is visible. */
    val currentUrl: StateFlow<String?> = _currentUrl

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                status = 200
                _currentUrl.value = url
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) status = response.statusCode
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageLoad?.completeExceptionally(PageException(request.url.toString(), error.description.toString()))
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                _currentUrl.value = url
                // Persist the session so it survives the app being killed.
                CookieManager.getInstance().flush()
                pageLoad?.complete(status)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                _currentUrl.value = url
            }
        }
    }
}

/**
 * Reads new Reddit's mobile site in real (hidden) WebViews with extract.js, one request at a time.
 * Listings stay loaded in [feedTab], because more posts come from scrolling that page; threads
 * open in [threadTab], like a second browser tab. Every page request carries a real browser
 * fingerprint.
 */
class WebViewRedditSource(feedView: WebView, threadView: WebView, private val script: String) : RedditSource {
    val feedTab = PageTab(feedView)
    val threadTab = PageTab(threadView)

    private val mutex = Mutex()
    private var seenPosts = HashSet<String>()
    private var loadedThread: String? = null
    private var blockedUntil = 0L
    private var blockReason = ""
    private var backoffMs = INITIAL_BACKOFF_MS

    private val _subscriptions = MutableStateFlow<List<String>>(emptyList())
    override val subscriptions: StateFlow<List<String>> = _subscriptions

    override suspend fun listing(feed: Feed, sort: Sort, time: TimeFilter): Listing = mutex.withLock {
        val url = listingUrl(feed, sort, time)
        val page = load(feedTab, url)
        expect(page, "listing")
        // Multireddits, r/all and the like land on the front page instead of failing.
        if (feed is Feed.Subreddit && page.optString("route") == "frontpage") {
            throw PageException(url, "Reddit sent ${feed.title} to the front page")
        }
        val posts = PageParser.posts(page)
        seenPosts = posts.mapTo(HashSet()) { it.id }
        if (_subscriptions.value.isEmpty()) loadSubscriptions()
        Listing(posts, hasMore = posts.isNotEmpty())
    }

    override suspend fun morePosts(): Listing = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val url = feedTab.webView.url.orEmpty()
            val deadline = SystemClock.uptimeMillis() + SCROLL_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                // Reddit's infinite scroll loads the next batch and drops old posts from the page,
                // so new posts are recognised by id rather than by count.
                evaluate(feedTab, "roost.scrollToEnd()")
                delay(POLL_MS)
                val page = checked(url, extract(feedTab), feedTab.status)
                val fresh = PageParser.posts(page).filter { seenPosts.add(it.id) }
                if (fresh.isNotEmpty()) return@withContext Listing(fresh, hasMore = true)
            }
            Listing(emptyList(), hasMore = false)
        }
    }

    override suspend fun thread(permalink: String): CommentThread = mutex.withLock {
        loadThread(permalink)
    }

    override suspend fun loadMoreComments(permalink: String, moreId: String): CommentThread = mutex.withLock {
        require(MORE_ID.matches(moreId)) { "Unexpected comment id $moreId" }
        // Another thread may have been opened since; the expansion only exists on a fresh page then.
        if (loadedThread != permalink) loadThread(permalink)
        withContext(Dispatchers.Main.immediate) {
            val url = threadTab.webView.url.orEmpty()
            if (evaluate(threadTab, "roost.loadMore('$moreId')") != "true") {
                throw PageException(url, "Nothing to expand for $moreId")
            }
            if (!waitFor(threadTab, "roost.moreLoaded('$moreId')", MORE_TIMEOUT_MS)) {
                throw PageException(url, "Loading more comments timed out")
            }
            val page = checked(url, extract(threadTab), threadTab.status)
            expect(page, "thread")
            PageParser.thread(page)
        }
    }

    private suspend fun loadThread(permalink: String): CommentThread {
        val url = BASE_URL + permalink.trimStart('/')
        load(threadTab, url)
        // Comments render shortly after the page itself.
        waitFor(threadTab, "roost.threadReady()", THREAD_TIMEOUT_MS)
        val page = checked(url, extract(threadTab), threadTab.status)
        expect(page, "thread")
        loadedThread = permalink
        return PageParser.thread(page)
    }

    /** The list only reaches the page when the nav drawer opens, so open it once and close it again. */
    private suspend fun loadSubscriptions() = withContext(Dispatchers.Main.immediate) {
        if (evaluate(feedTab, "roost.toggleNav()") != "true") return@withContext
        val deadline = SystemClock.uptimeMillis() + NAV_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            delay(POLL_MS)
            val subscriptions = PageParser.subscriptions(extract(feedTab))
            if (!subscriptions.isNullOrEmpty()) {
                _subscriptions.value = subscriptions
                break
            }
        }
        evaluate(feedTab, "roost.toggleNav()")
        if (_subscriptions.value.isEmpty()) Log.w(TAG, "No subscription list at ${feedTab.webView.url}")
    }

    private suspend fun load(tab: PageTab, url: String): JSONObject = withContext(Dispatchers.Main.immediate) {
        if (System.currentTimeMillis() < blockedUntil) throw BlockedException(blockReason, blockedUntil)
        if (tab === threadTab) loadedThread = null
        val done = CompletableDeferred<Int>()
        tab.pageLoad = done
        tab.webView.loadUrl(url)
        val status = try {
            withTimeout(LOAD_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            throw PageException(url, "Page load timed out")
        } finally {
            tab.pageLoad = null
        }
        checked(url, extract(tab), status)
    }

    /** Turns block pages, HTTP errors and logged-out pages into exceptions; backs off on blocks. */
    private fun checked(url: String, page: JSONObject, status: Int): JSONObject {
        if (!page.optBoolean("ok")) {
            Log.w(TAG, "Extraction failed for $url: ${page.optString("error")}")
            throw PageException(url, "Extraction failed")
        }
        if (status == 403 || status == 429 || page.optString("page") == "blocked") {
            blockReason = page.optString("blockReason").ifEmpty { "HTTP $status" }
            blockedUntil = System.currentTimeMillis() + backoffMs
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            Log.w(TAG, "Blocked at $url ($blockReason), backing off until $blockedUntil")
            throw BlockedException(blockReason, blockedUntil)
        }
        if (status >= 400) throw PageException(url, "HTTP $status")
        if (!page.optBoolean("loggedIn")) {
            Log.w(TAG, "Not logged in at ${page.optString("url")} (requested $url, page '${page.optString("page")}', title '${page.optString("title")}')")
            throw NotLoggedInException()
        }
        backoffMs = INITIAL_BACKOFF_MS
        PageParser.subscriptions(page)?.takeIf { it.isNotEmpty() }?.let { _subscriptions.value = it }
        return page
    }

    private fun expect(page: JSONObject, type: String) {
        if (page.optString("page") != type) {
            val url = page.optString("url")
            Log.w(TAG, "Expected a $type page at $url, got '${page.optString("page")}' (${page.optString("title")})")
            throw PageException(url, "Unexpected page: ${page.optString("title")}")
        }
    }

    /** Runs extract.js on [tab]'s current page; also used by the debug overlay. */
    suspend fun extract(tab: PageTab): JSONObject = withContext(Dispatchers.Main.immediate) {
        val raw = evaluate(tab, "roost.extract()")
        try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Log.w(TAG, "Unparseable extract.js result for ${tab.webView.url}: $raw", e)
            throw PageException(tab.webView.url.orEmpty(), "Unparseable extraction result")
        }
    }

    private suspend fun waitFor(tab: PageTab, condition: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (evaluate(tab, condition) == "true") return true
            delay(POLL_MS)
        }
        return false
    }

    /** Defines window.roost (cheap, idempotent) and evaluates [expression] against it. */
    private suspend fun evaluate(tab: PageTab, expression: String): String = suspendCancellableCoroutine { cont ->
        tab.webView.evaluateJavascript("$script\n;$expression") { cont.resume(it) }
    }

    companion object {
        fun listingUrl(feed: Feed, sort: Sort, time: TimeFilter): String {
            if (feed is Feed.Front) return BASE_URL
            val name = (feed as Feed.Subreddit).name
            val query = if (sort.timed) "?t=${time.param}" else ""
            return "${BASE_URL}r/$name/${sort.path}/$query"
        }

        const val LOGIN_URL = "https://www.reddit.com/login/"
    }
}

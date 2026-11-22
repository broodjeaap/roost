package net.broodjeaap.roost.data

import android.graphics.Bitmap
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
private const val BASE_URL = "https://old.reddit.com/"
private const val LOAD_TIMEOUT_MS = 30_000L
private const val MORE_TIMEOUT_MS = 15_000L
private const val INITIAL_BACKOFF_MS = 60_000L
private const val MAX_BACKOFF_MS = 30 * 60_000L
private val MORE_ID = Regex("^t1_[a-z0-9]+$")

/**
 * Loads old.reddit pages in a real (hidden) WebView, one at a time, and reads them with extract.js.
 * Every request to Reddit goes through this WebView, so it carries a real browser fingerprint.
 */
class WebViewRedditSource(private val webView: WebView, private val script: String) : RedditSource {
    private val mutex = Mutex()
    private var pageLoad: CompletableDeferred<Int>? = null
    private var mainFrameStatus = 200
    private var loadedThread: String? = null
    private var blockedUntil = 0L
    private var blockReason = ""
    private var backoffMs = INITIAL_BACKOFF_MS

    private val _subscriptions = MutableStateFlow<List<String>>(emptyList())
    override val subscriptions: StateFlow<List<String>> = _subscriptions

    private val _currentUrl = MutableStateFlow<String?>(null)
    /** Whatever the WebView shows, including pages the user navigates to while it is visible. */
    val currentUrl: StateFlow<String?> = _currentUrl

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                mainFrameStatus = 200
                _currentUrl.value = url
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) mainFrameStatus = response.statusCode
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
                pageLoad?.complete(mainFrameStatus)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                _currentUrl.value = url
            }
        }
    }

    override suspend fun listing(feed: Feed, sort: Sort, time: TimeFilter): Listing =
        nextPage(listingUrl(feed, sort, time))

    override suspend fun nextPage(nextUrl: String): Listing = mutex.withLock {
        val page = load(nextUrl)
        expect(page, "listing")
        PageParser.listing(page)
    }

    override suspend fun thread(permalink: String): CommentThread = mutex.withLock {
        loadThread(permalink)
    }

    override suspend fun loadMoreComments(permalink: String, moreId: String): CommentThread = mutex.withLock {
        require(MORE_ID.matches(moreId)) { "Unexpected comment id $moreId" }
        // The WebView may have moved on since; the expansion only exists on a freshly loaded page then.
        if (loadedThread != permalink) loadThread(permalink)
        withContext(Dispatchers.Main.immediate) {
            val url = webView.url.orEmpty()
            if (evaluate("roost.loadMore('$moreId')") != "true") {
                throw PageException(url, "\"load more comments\" link $moreId is not on the page")
            }
            try {
                withTimeout(MORE_TIMEOUT_MS) {
                    while (evaluate("roost.moreLoaded('$moreId')") != "true") delay(250)
                }
            } catch (e: TimeoutCancellationException) {
                throw PageException(url, "Loading more comments timed out")
            }
            val page = checked(url, extract(), mainFrameStatus)
            expect(page, "thread")
            PageParser.thread(page)
        }
    }

    private suspend fun loadThread(permalink: String): CommentThread {
        val page = load(BASE_URL + permalink.trimStart('/'))
        expect(page, "thread")
        loadedThread = permalink
        return PageParser.thread(page)
    }

    private suspend fun load(url: String): JSONObject = withContext(Dispatchers.Main.immediate) {
        if (System.currentTimeMillis() < blockedUntil) throw BlockedException(blockReason, blockedUntil)
        loadedThread = null
        val done = CompletableDeferred<Int>()
        pageLoad = done
        webView.loadUrl(url)
        val status = try {
            withTimeout(LOAD_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            throw PageException(url, "Page load timed out")
        } finally {
            pageLoad = null
        }
        checked(url, extract(), status)
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
        if (!page.optBoolean("loggedIn")) throw NotLoggedInException()
        backoffMs = INITIAL_BACKOFF_MS
        PageParser.subscriptions(page).takeIf { it.isNotEmpty() }?.let { _subscriptions.value = it }
        return page
    }

    private fun expect(page: JSONObject, type: String) {
        if (page.optString("page") != type) {
            val url = page.optString("url")
            Log.w(TAG, "Expected a $type page at $url, got '${page.optString("page")}' (${page.optString("title")})")
            throw PageException(url, "Unexpected page: ${page.optString("title")}")
        }
    }

    /** Runs extract.js on the current page; exposed for the debug overlay. */
    suspend fun extract(): JSONObject = withContext(Dispatchers.Main.immediate) {
        val raw = evaluate("roost.extract()")
        try {
            JSONObject(raw)
        } catch (e: JSONException) {
            Log.w(TAG, "Unparseable extract.js result for ${webView.url}: $raw", e)
            throw PageException(webView.url.orEmpty(), "Unparseable extraction result")
        }
    }

    /** Defines window.roost (cheap, idempotent) and evaluates [expression] against it. */
    private suspend fun evaluate(expression: String): String = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript("$script\n;$expression") { cont.resume(it) }
    }

    companion object {
        fun listingUrl(feed: Feed, sort: Sort, time: TimeFilter): String {
            val prefix = when (feed) {
                Feed.Front -> ""
                is Feed.Subreddit -> "r/${feed.name}/"
            }
            val query = if (sort.timed) "?t=${time.param}" else ""
            return "$BASE_URL$prefix${sort.path}/$query"
        }

        const val LOGIN_URL = "https://www.reddit.com/login/"
    }
}

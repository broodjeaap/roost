package net.broodjeaap.roost.data

import kotlinx.coroutines.flow.StateFlow

/** Everything the UI may ask of Reddit. Only implementations know how pages are fetched and parsed. */
interface RedditSource {
    suspend fun listing(feed: Feed, sort: Sort, time: TimeFilter): Listing

    /** Loads the page after a listing, using [Listing.nextUrl]. */
    suspend fun nextPage(nextUrl: String): Listing

    suspend fun thread(permalink: String): CommentThread

    /** Expands a [CommentNode.More] in [permalink]'s thread and returns the whole updated thread. */
    suspend fun loadMoreComments(permalink: String, moreId: String): CommentThread

    /** Subscribed subreddits, refreshed from every page load. */
    val subscriptions: StateFlow<List<String>>
}

sealed interface Feed {
    val title: String

    data object Front : Feed {
        override val title = "Front page"
    }

    /** A subreddit, `popular`, `all` or a multireddit like `a+b`. */
    data class Subreddit(val name: String) : Feed {
        override val title = "r/$name"
    }
}

enum class Sort(val path: String, val label: String, val timed: Boolean) {
    BEST("best", "Best", false),
    HOT("hot", "Hot", false),
    NEW("new", "New", false),
    RISING("rising", "Rising", false),
    CONTROVERSIAL("controversial", "Controversial", true),
    TOP("top", "Top", true);

    companion object {
        /** Best only exists for the front page. */
        fun available(feed: Feed): List<Sort> = if (feed is Feed.Front) entries else entries - BEST

        fun default(feed: Feed): Sort = if (feed is Feed.Front) BEST else HOT
    }
}

enum class TimeFilter(val param: String, val label: String) {
    HOUR("hour", "Past hour"),
    DAY("day", "Today"),
    WEEK("week", "This week"),
    MONTH("month", "This month"),
    YEAR("year", "This year"),
    ALL("all", "All time"),
}

sealed class RedditException(message: String) : Exception(message)

class NotLoggedInException : RedditException("Not logged in")

/** Reddit served a block, captcha or rate-limit page; nothing is loaded again before [retryAtMillis]. */
class BlockedException(val reason: String, val retryAtMillis: Long) : RedditException("Blocked: $reason")

/** The page loaded but wasn't something the extractor understands (or the load failed). */
class PageException(val url: String, message: String) : RedditException("$message ($url)")

package net.broodjeaap.roost.data

data class Post(
    val id: String,
    val title: String,
    val author: String,
    val subreddit: String,
    /** Null when the subreddit hides scores. */
    val score: Int?,
    val commentCount: Int,
    val permalink: String,
    val url: String,
    val domain: String,
    val createdMillis: Long?,
    val nsfw: Boolean,
    val spoiler: Boolean,
    val stickied: Boolean,
    val isSelf: Boolean,
    val flair: String?,
    val thumbnail: String?,
    /** Self text; only present on thread pages (listings don't include it). */
    val bodyHtml: String?,
    val media: Media?,
)

sealed interface Media {
    data class Image(val url: String, val width: Int?, val height: Int?) : Media
    data class Gallery(val images: List<Image>) : Media
    /** [hlsUrl] for v.redd.it, [mp4Url] for plain video files; [gif] means silent and looping. */
    data class Video(
        val hlsUrl: String?,
        val mp4Url: String?,
        val width: Int?,
        val height: Int?,
        val gif: Boolean,
    ) : Media
}

sealed interface CommentNode {
    data class Comment(
        val id: String,
        val author: String,
        val score: Int?,
        val createdMillis: Long?,
        val permalink: String,
        val bodyHtml: String,
        val submitter: Boolean,
        val moderator: Boolean,
        val flair: String?,
        /** Reddit collapses some comments (e.g. low score) by default. */
        val collapsedByDefault: Boolean,
        val children: List<CommentNode>,
    ) : CommentNode

    /** "load more comments": fetched in place by [RedditSource.loadMoreComments]. */
    data class More(val id: String, val count: Int?) : CommentNode

    /** "continue this thread": a separate page. */
    data class Continue(val url: String) : CommentNode
}

data class Listing(val posts: List<Post>, val nextUrl: String?)

data class CommentThread(val post: Post, val comments: List<CommentNode>)

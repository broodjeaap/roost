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
    val isSelf: Boolean,
    /** Added to the front page by Reddit from a subreddit I'm not subscribed to. */
    val recommended: Boolean,
    val flair: String?,
    val thumbnail: String?,
    /** Self text; a preview in listings, complete on thread pages. */
    val body: List<BodyBlock>,
    val media: Media?,
)

sealed interface Media {
    /** One image or video; galleries are made of these. */
    sealed interface Item : Media {
        val width: Int?
        val height: Int?
    }

    data class Image(val url: String, override val width: Int?, override val height: Int?) : Item
    data class Gallery(val items: List<Item>) : Media
    /** [hlsUrl] for v.redd.it and Bluesky, [mp4Url] for plain video files; [gif] means silent and looping. */
    data class Video(
        val hlsUrl: String?,
        val mp4Url: String?,
        val poster: String?,
        override val width: Int?,
        override val height: Int?,
        val gif: Boolean,
    ) : Item
    /** A video that plays in its own app (YouTube): shown as [poster], tapping opens [url]. */
    data class ExternalVideo(val url: String, val poster: String) : Media
}

/** Rich text (post bodies, comments) in reading order: text between embedded images and GIFs. */
sealed interface BodyBlock {
    data class Text(val html: String) : BodyBlock
    data class Embed(val media: Media) : BodyBlock
}

sealed interface CommentNode {
    data class Comment(
        val id: String,
        val author: String,
        val score: Int?,
        val createdMillis: Long?,
        val permalink: String,
        val body: List<BodyBlock>,
        val submitter: Boolean,
        val moderator: Boolean,
        /** Reddit collapses some comments (e.g. AutoModerator) by default. */
        val collapsedByDefault: Boolean,
        val children: List<CommentNode>,
    ) : CommentNode

    /**
     * "N more replies" under comment [id], or more top-level comments when [id] is [TOP];
     * fetched in place by [RedditSource.loadMoreComments].
     */
    data class More(val id: String, val count: Int?) : CommentNode {
        companion object {
            const val TOP = "top"
        }
    }

    /** A reply chain too deep to expand inline; it opens as its own page. */
    data class Continue(val url: String) : CommentNode
}

data class Listing(val posts: List<Post>, val hasMore: Boolean)

data class CommentThread(val post: Post, val comments: List<CommentNode>)

/** A subreddit suggested by Reddit's search; [detail] is its one-line blurb (visitors, why suggested). */
data class SubredditMatch(val name: String, val detail: String?)

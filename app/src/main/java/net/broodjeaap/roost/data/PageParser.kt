package net.broodjeaap.roost.data

import org.json.JSONArray
import org.json.JSONObject

/** Maps extract.js output onto the typed models. */
internal object PageParser {
    fun posts(page: JSONObject): List<Post> = page.getJSONArray("posts").objects().map(::post)

    fun thread(page: JSONObject): CommentThread =
        CommentThread(post(page.getJSONObject("post")), comments(page.getJSONArray("comments")))

    /** Null when the page doesn't carry the list (the nav drawer hasn't been opened). */
    fun subscriptions(page: JSONObject): List<String>? =
        page.optJSONArray("subscriptions")?.let { array -> List(array.length()) { array.getString(it) } }

    fun subredditMatches(array: JSONArray): List<SubredditMatch> =
        array.objects().map { SubredditMatch(it.getString("name"), it.str("detail")) }

    private fun post(json: JSONObject) = Post(
        id = json.getString("id"),
        title = json.str("title").orEmpty(),
        author = json.getString("author"),
        subreddit = json.str("subreddit").orEmpty(),
        score = json.int("score"),
        commentCount = json.optInt("commentCount"),
        permalink = json.getString("permalink"),
        url = json.str("url").orEmpty(),
        domain = json.str("domain").orEmpty(),
        createdMillis = json.long("created"),
        nsfw = json.optBoolean("nsfw"),
        spoiler = json.optBoolean("spoiler"),
        isSelf = json.optBoolean("isSelf"),
        recommended = json.optBoolean("recommended"),
        flair = json.str("flair"),
        thumbnail = json.str("thumbnail"),
        body = body(json.getJSONArray("body")),
        media = json.optJSONObject("media")?.let(::media),
    )

    private fun body(array: JSONArray): List<BodyBlock> = array.objects().mapNotNull { block ->
        when (block.getString("kind")) {
            "media" -> media(block.getJSONObject("media"))?.let(BodyBlock::Embed)
            else -> BodyBlock.Text(block.getString("html"))
        }
    }

    private fun media(json: JSONObject): Media? = when (json.getString("type")) {
        "image" -> image(json)
        "gallery" -> Media.Gallery(json.getJSONArray("images").objects().map(::image))
        "video" -> Media.Video(
            hlsUrl = json.str("hlsUrl"),
            mp4Url = json.str("mp4Url"),
            poster = json.str("poster"),
            width = json.int("width"),
            height = json.int("height"),
            gif = json.optBoolean("gif"),
        )
        else -> null
    }

    private fun image(json: JSONObject) = Media.Image(json.getString("url"), json.int("width"), json.int("height"))

    private fun comments(array: JSONArray): List<CommentNode> = array.objects().map { json ->
        when (json.getString("kind")) {
            "more" -> CommentNode.More(json.getString("id"), json.int("count"))
            "continue" -> CommentNode.Continue(json.getString("url"))
            else -> CommentNode.Comment(
                id = json.getString("id"),
                author = json.getString("author"),
                score = json.int("score"),
                createdMillis = json.long("created"),
                permalink = json.str("permalink").orEmpty(),
                body = body(json.getJSONArray("body")),
                submitter = json.optBoolean("submitter"),
                moderator = json.optBoolean("moderator"),
                collapsedByDefault = json.optBoolean("collapsed"),
                children = comments(json.getJSONArray("children")),
            )
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key).ifEmpty { null }

    private fun JSONObject.int(key: String): Int? = if (isNull(key)) null else getDouble(key).toInt()

    private fun JSONObject.long(key: String): Long? = if (isNull(key)) null else getDouble(key).toLong()
}

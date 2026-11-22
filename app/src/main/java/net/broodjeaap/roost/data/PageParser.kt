package net.broodjeaap.roost.data

import org.json.JSONArray
import org.json.JSONObject

/** Maps extract.js output onto the typed models. */
internal object PageParser {
    fun listing(page: JSONObject): Listing =
        Listing(page.getJSONArray("posts").objects().map(::post), page.str("nextUrl"))

    fun thread(page: JSONObject): CommentThread =
        CommentThread(post(page.getJSONObject("post")), comments(page.getJSONArray("comments")))

    fun subscriptions(page: JSONObject): List<String> =
        page.optJSONArray("subscriptions")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()

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
        stickied = json.optBoolean("stickied"),
        isSelf = json.optBoolean("isSelf"),
        flair = json.str("flair"),
        thumbnail = json.str("thumbnail"),
        bodyHtml = json.str("bodyHtml"),
        media = json.optJSONObject("media")?.let(::media),
    )

    private fun media(json: JSONObject): Media? = when (json.getString("type")) {
        "image" -> image(json)
        "gallery" -> Media.Gallery(json.getJSONArray("images").objects().map(::image))
        "video" -> Media.Video(
            hlsUrl = json.str("hlsUrl"),
            mp4Url = json.str("mp4Url"),
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
                bodyHtml = json.str("bodyHtml").orEmpty(),
                submitter = json.optBoolean("submitter"),
                moderator = json.optBoolean("moderator"),
                flair = json.str("flair"),
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

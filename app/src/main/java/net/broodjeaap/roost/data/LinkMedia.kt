package net.broodjeaap.roost.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/** Imgur's own web client id; its post API takes no other credentials. */
private const val IMGUR_CLIENT_ID = "546c25a59c58ad7"

/**
 * Media behind link posts to other hosts, which Reddit shows only as a thumbnail. Direct files,
 * single Imgur files, Giphy and YouTube are known from the URL alone; Imgur posts, X (through
 * fxtwitter), Redgifs, Streamable and Bluesky take a request to their public APIs.
 * Use from the main thread; lookups are kept for the session.
 */
class LinkMedia(private val http: OkHttpClient) {
    /** URL -> media; a null value is a link known to have none. */
    private val resolved = HashMap<String, Media?>()

    private var redgifsToken: String? = null

    /** Media known without a request: from the URL alone, or looked up before. */
    fun peek(url: String): Media? = resolved[url] ?: (MediaLink.parse(url) as? MediaLink.Ready)?.media

    /** Null when the link has no media; also when the lookup failed, which is tried again next time. */
    suspend fun resolve(url: String): Media? {
        if (url in resolved) return resolved[url]
        val media = try {
            MediaLink.parse(url)?.let { fetch(it) }
        } catch (_: IOException) {
            return null
        } catch (_: JSONException) {
            null
        }
        resolved[url] = media
        return media
    }

    private suspend fun fetch(link: MediaLink): Media? = when (link) {
        is MediaLink.Ready -> link.media
        is MediaLink.ImgurPost -> imgur(link)
        is MediaLink.Tweet -> tweet(link.id)
        is MediaLink.Redgifs -> redgifs(link.id)
        is MediaLink.Streamable -> streamable(link.id)
        is MediaLink.BlueskyPost -> bluesky(link)
    }

    /** Image links (imgur.com/<id>) may be albums and album links images; the hint is tried first. */
    private suspend fun imgur(post: MediaLink.ImgurPost): Media? {
        val kinds = if (post.album) listOf("albums", "media") else listOf("media", "albums")
        for (kind in kinds) {
            val json = getJson("https://api.imgur.com/post/v1/$kind/${post.id}?client_id=$IMGUR_CLIENT_ID&include=media") ?: continue
            return gallery(json.getJSONArray("media").objects().map { item ->
                val id = item.getString("id")
                if (item.str("type") == "video" || item.str("mime_type") == "image/gif") {
                    // GIFs are served as MP4 too.
                    val sound = item.optJSONObject("metadata")?.optBoolean("has_sound") == true
                    Media.Video(null, "https://i.imgur.com/$id.mp4", "https://i.imgur.com/$id.jpg", item.int("width"), item.int("height"), gif = !sound)
                } else {
                    Media.Image(item.getString("url"), item.int("width"), item.int("height"))
                }
            })
        }
        return null
    }

    private suspend fun tweet(id: String): Media? {
        val json = getJson("https://api.fxtwitter.com/status/$id") ?: return null
        val all = json.optJSONObject("tweet")?.optJSONObject("media")?.optJSONArray("all") ?: return null
        return gallery(all.objects().mapNotNull { item ->
            when (val type = item.str("type")) {
                "photo" -> Media.Image(item.getString("url"), item.int("width"), item.int("height"))
                "video", "gif" -> video(item.getString("url"), item.str("thumbnail_url"), item.int("width"), item.int("height"), gif = type == "gif")
                else -> null
            }
        })
    }

    private suspend fun redgifs(id: String): Media? {
        val url = "https://api.redgifs.com/v2/gifs/$id"
        val json = try {
            getJson(url, "Authorization" to "Bearer ${redgifsToken()}")
        } catch (e: HttpStatusException) {
            if (e.code != 401) throw e
            // Temporary tokens expire after a day.
            redgifsToken = null
            getJson(url, "Authorization" to "Bearer ${redgifsToken()}")
        }
        val gif = json?.optJSONObject("gif") ?: return null
        val urls = gif.getJSONObject("urls")
        return video(
            url = urls.str("hd") ?: urls.str("sd") ?: return null,
            poster = urls.str("poster") ?: urls.str("thumbnail"),
            width = gif.int("width"),
            height = gif.int("height"),
            gif = !gif.optBoolean("hasAudio"),
        )
    }

    private suspend fun redgifsToken(): String = redgifsToken
        ?: getJson("https://api.redgifs.com/v2/auth/temporary")?.getString("token")?.also { redgifsToken = it }
        ?: throw IOException("No Redgifs token")

    private suspend fun streamable(id: String): Media? {
        val json = getJson("https://api.streamable.com/videos/$id") ?: return null
        // 2: processed and playable.
        if (json.optInt("status") != 2) return null
        val files = json.optJSONObject("files") ?: return null
        val file = files.optJSONObject("mp4") ?: files.optJSONObject("mp4-mobile") ?: return null
        return video(https(file.getString("url")), json.str("thumbnail_url")?.let(::https), file.int("width"), file.int("height"), gif = false)
    }

    private suspend fun bluesky(post: MediaLink.BlueskyPost): Media? {
        val url = "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread".toHttpUrl().newBuilder()
            .addQueryParameter("uri", "at://${post.actor}/app.bsky.feed.post/${post.rkey}")
            .addQueryParameter("depth", "0")
            .addQueryParameter("parentHeight", "0")
            .build()
        val json = getJson(url) ?: return null
        return blueskyEmbed(json.optJSONObject("thread")?.optJSONObject("post")?.optJSONObject("embed"))
    }

    private fun blueskyEmbed(embed: JSONObject?): Media? = when (embed?.str("\$type")) {
        "app.bsky.embed.images#view" -> gallery(embed.getJSONArray("images").objects().map(::blueskyImage))
        "app.bsky.embed.gallery#view" -> gallery(embed.getJSONArray("items").objects().mapNotNull { item ->
            when {
                item.has("playlist") -> blueskyVideo(item)
                item.has("fullsize") -> blueskyImage(item)
                else -> null
            }
        })
        "app.bsky.embed.video#view" -> blueskyVideo(embed)
        // A quote post with media of its own.
        "app.bsky.embed.recordWithMedia#view" -> blueskyEmbed(embed.optJSONObject("media"))
        else -> null
    }

    private fun blueskyImage(json: JSONObject): Media.Image {
        val ratio = json.optJSONObject("aspectRatio")
        return Media.Image(json.getString("fullsize"), ratio?.int("width"), ratio?.int("height"))
    }

    private fun blueskyVideo(json: JSONObject): Media.Video {
        val ratio = json.optJSONObject("aspectRatio")
        return video(json.getString("playlist"), json.str("thumbnail"), ratio?.int("width"), ratio?.int("height"), gif = false)
    }

    private suspend fun getJson(url: String, vararg headers: Pair<String, String>): JSONObject? =
        getJson(url.toHttpUrl(), *headers)

    /** Null for a client error (gone, not found, not a post): the link has no media. */
    private suspend fun getJson(url: HttpUrl, vararg headers: Pair<String, String>): JSONObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> JSONObject(response.body.string())
                401, 429 -> throw HttpStatusException(response.code)
                in 400..499 -> null
                else -> throw HttpStatusException(response.code)
            }
        }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")
}

/** One item as itself, several as a gallery. */
private fun gallery(items: List<Media.Item>): Media? = if (items.size > 1) Media.Gallery(items) else items.firstOrNull()

private fun video(url: String, poster: String?, width: Int?, height: Int?, gif: Boolean) = Media.Video(
    hlsUrl = url.takeIf { ".m3u8" in it },
    mp4Url = url.takeUnless { ".m3u8" in it },
    poster = poster,
    width = width,
    height = height,
    gif = gif,
)

/** Streamable gives protocol-relative URLs. */
private fun https(url: String) = if (url.startsWith("//")) "https:$url" else url

/** What a link post's URL points at, as far as the URL itself tells. */
internal sealed interface MediaLink {
    /** Known from the URL alone. */
    data class Ready(val media: Media) : MediaLink
    data class ImgurPost(val id: String, val album: Boolean) : MediaLink
    data class Tweet(val id: String) : MediaLink
    data class Redgifs(val id: String) : MediaLink
    data class Streamable(val id: String) : MediaLink
    data class BlueskyPost(val actor: String, val rkey: String) : MediaLink

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "webm")
        private val X_HOSTS = setOf("x.com", "twitter.com", "mobile.twitter.com", "fxtwitter.com", "vxtwitter.com", "fixupx.com")
        private val ALPHANUMERIC = Regex("[A-Za-z0-9]+")
        private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

        fun parse(url: String): MediaLink? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val host = parsed.host.removePrefix("www.").removePrefix("m.")
            val path = parsed.pathSegments.filter { it.isNotEmpty() }
            val extension = path.lastOrNull().orEmpty().substringAfterLast('.', "").lowercase()
            return when {
                host == "i.imgur.com" -> path.singleOrNull()?.let(::imgurFile)
                host == "imgur.com" -> imgurPage(path)
                host == "youtu.be" -> youtube(url, path.firstOrNull())
                host == "youtube.com" || host == "music.youtube.com" || host == "youtube-nocookie.com" -> youtube(
                    url,
                    if (path.firstOrNull() == "watch") parsed.queryParameter("v") else path.getOrNull(1)?.takeIf { path[0] in setOf("shorts", "live", "embed", "v") },
                )
                host == "giphy.com" -> giphy(path.getOrNull(1)?.takeIf { path[0] in setOf("gifs", "embed") }?.substringAfterLast('-'))
                host.endsWith(".giphy.com") -> giphy(
                    // media.giphy.com/media/[v1.<token>/]<id>/giphy.gif, i.giphy.com/<id>.gif
                    if (path.firstOrNull() == "media") path.getOrNull(path.size - 2) else path.singleOrNull()?.substringBefore('.'),
                )
                host in X_HOSTS -> path.indexOf("status").takeIf { it >= 0 }
                    ?.let { path.getOrNull(it + 1) }
                    ?.takeIf { it.all(Char::isDigit) }
                    ?.let(::Tweet)
                host == "redgifs.com" || host == "v3.redgifs.com" -> path.getOrNull(1)
                    ?.takeIf { path[0] == "watch" || path[0] == "ifr" }
                    ?.let { Redgifs(it.lowercase()) }
                host == "streamable.com" -> (if (path.firstOrNull() in setOf("e", "o", "s")) path.getOrNull(1) else path.singleOrNull())
                    ?.takeIf(ALPHANUMERIC::matches)
                    ?.let(::Streamable)
                host == "bsky.app" -> if (path.size >= 4 && path[0] == "profile" && path[2] == "post") BlueskyPost(path[1], path[3]) else null
                // pbs.twimg.com/media/<id>?format=jpg&name=large
                host == "pbs.twimg.com" && parsed.queryParameter("format") != null -> Ready(Media.Image(url, null, null))
                extension in IMAGE_EXTENSIONS -> Ready(Media.Image(url, null, null))
                extension in VIDEO_EXTENSIONS -> Ready(video(url, null, null, null, gif = false))
                else -> null
            }
        }

        private fun imgurFile(file: String): MediaLink? {
            val id = file.substringBefore('.')
            if (!ALPHANUMERIC.matches(id)) return null
            return when (val extension = file.substringAfter('.', "").lowercase()) {
                "" -> ImgurPost(id, album = false)
                in IMAGE_EXTENSIONS -> Ready(Media.Image("https://i.imgur.com/$id.$extension", null, null))
                // Imgur serves every animation and video as MP4, with a JPEG still. A .gifv or
                // .mp4 may have sound, so only a .gif plays as a silent loop.
                "gif", "gifv", "mp4" -> Ready(
                    Media.Video(null, "https://i.imgur.com/$id.mp4", "https://i.imgur.com/$id.jpg", null, null, gif = extension == "gif"),
                )
                else -> null
            }
        }

        private fun imgurPage(path: List<String>): MediaLink? {
            val first = path.firstOrNull() ?: return null
            // New-style links end their id in a title slug: /gallery/some-title-<id>.
            val id = when {
                path.size == 1 -> return if ('.' in first) imgurFile(first) else first.takeIf(ALPHANUMERIC::matches)?.let { ImgurPost(it, album = false) }
                first == "a" || first == "gallery" -> path[1]
                first == "t" && path.size >= 3 -> path[2]
                else -> return null
            }.substringAfterLast('-')
            return id.takeIf(ALPHANUMERIC::matches)?.let { ImgurPost(it, album = true) }
        }

        private fun youtube(url: String, id: String?): MediaLink? = id?.takeIf(YOUTUBE_ID::matches)?.let {
            Ready(Media.ExternalVideo(url, "https://i.ytimg.com/vi/$it/hqdefault.jpg"))
        }

        private fun giphy(id: String?): MediaLink? = id?.takeIf(ALPHANUMERIC::matches)?.let {
            Ready(Media.Video(null, "https://i.giphy.com/$it.mp4", "https://media.giphy.com/media/$it/giphy_s.gif", null, null, gif = true))
        }
    }
}

package net.broodjeaap.roost.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import net.broodjeaap.roost.data.BodyBlock
import net.broodjeaap.roost.data.LinkMedia
import net.broodjeaap.roost.data.Media
import net.broodjeaap.roost.data.Post

/** HTTP for video, sharing the WebView's user agent; provided by MainActivity. */
val LocalVideoDataSource = staticCompositionLocalOf<DataSource.Factory> { error("No video data source") }

/** Looks up media behind link posts; provided by MainActivity. */
val LocalLinkMedia = staticCompositionLocalOf<LinkMedia> { error("No link media") }

/** Feed media is cropped to at most this tall (width / height); threads show it whole. */
private const val MIN_FEED_RATIO = 0.8f

/** Images and GIFs embedded in text (comments mostly) are shown at most this wide. */
private val EMBED_MAX_WIDTH = 320.dp

@Composable
fun PostMeta(post: Post, modifier: Modifier = Modifier) {
    Text(
        text = listOf("r/${post.subreddit}", "u/${post.author}", age(post.createdMillis))
            .filter { it.isNotEmpty() }
            .joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun PostTags(post: Post, modifier: Modifier = Modifier) {
    val tags = buildList {
        if (post.nsfw) add("NSFW" to MaterialTheme.colorScheme.error)
        if (post.spoiler) add("Spoiler" to MaterialTheme.colorScheme.onSurfaceVariant)
        post.flair?.let { add(it to MaterialTheme.colorScheme.secondary) }
    }
    if (tags.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { (label, color) ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Media, or a link preview for link posts. Reddit shows links to other hosts (Imgur, YouTube…)
 * only as a thumbnail, so their media is looked up from the URL. [inFeed] crops tall media.
 */
@Composable
fun PostMedia(post: Post, state: AppState, inFeed: Boolean, onOpen: () -> Unit) {
    when (val media = post.media ?: if (post.isSelf) null else rememberLinkMedia(post.url)) {
        is Media.Image -> ImageMedia(media, inFeed, Modifier.clickable(onClick = onOpen))
        is Media.Gallery -> GalleryMedia(media, state, inFeed)
        is Media.Video -> VideoBox(
            key = post.id,
            video = media,
            fallbackPoster = post.thumbnail,
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio(media.width, media.height, inFeed)),
        )
        is Media.ExternalVideo -> PlayPoster(
            poster = media.poster,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9)
                .background(Color.Black)
                .clickable { state.openUrl(media.url) },
        )
        // Also while the lookup runs, or when it finds nothing.
        null -> if (!post.isSelf) LinkPreview(post) { state.openUrl(post.url) }
    }
}

/** Media behind [url]; null while it's looked up and when there is none. */
@Composable
private fun rememberLinkMedia(url: String): Media? {
    val links = LocalLinkMedia.current
    val media by produceState(remember(url) { links.peek(url) }, links, url) {
        if (value == null) value = links.resolve(url)
    }
    return media
}

/** Rich text with its embedded images and GIFs, which Reddit shows small; so does this. */
@Composable
fun RichBody(blocks: List<BodyBlock>, state: AppState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is BodyBlock.Text -> Text(rememberHtml(block.html, state::openUrl), style = MaterialTheme.typography.bodyMedium)
                is BodyBlock.Embed -> EmbeddedMedia(block.media, state)
            }
        }
    }
}

@Composable
private fun EmbeddedMedia(media: Media, state: AppState) {
    val size = Modifier
        .widthIn(max = EMBED_MAX_WIDTH)
        .fillMaxWidth()
    when (media) {
        is Media.Image -> ZoomableImage(
            url = media.url,
            contentScale = ContentScale.Fit,
            modifier = size
                .then(if (media.width != null && media.height != null) Modifier.aspectRatio(ratio(media.width, media.height, false)) else Modifier)
                .clip(RoundedCornerShape(4.dp))
                .clickable { state.openUrl(media.url) },
        )
        is Media.Video -> VideoBox(
            key = media.mp4Url ?: media.hlsUrl.orEmpty(),
            video = media,
            fallbackPoster = null,
            state = state,
            modifier = size
                .aspectRatio(ratio(media.width, media.height, false))
                .clip(RoundedCornerShape(4.dp)),
        )
        is Media.Gallery, is Media.ExternalVideo -> Unit
    }
}

private fun ratio(width: Int?, height: Int?, inFeed: Boolean): Float {
    val ratio = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else 16f / 9
    return if (inFeed) ratio.coerceAtLeast(MIN_FEED_RATIO) else ratio
}

@Composable
private fun ImageMedia(image: Media.Image, inFeed: Boolean, modifier: Modifier = Modifier) {
    ZoomableImage(
        url = image.url,
        contentScale = if (inFeed) ContentScale.Crop else ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio(image.width, image.height, inFeed))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun GalleryMedia(gallery: Media.Gallery, state: AppState, inFeed: Boolean) {
    val first = gallery.items.first()
    val pager = rememberPagerState { gallery.items.size }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio(first.width, first.height, inFeed))
            .background(Color.Black)
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            when (val item = gallery.items[page]) {
                is Media.Image -> ZoomableImage(
                    url = item.url,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                is Media.Video -> VideoBox(
                    key = item.mp4Url ?: item.hlsUrl.orEmpty(),
                    video = item,
                    fallbackPoster = null,
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = "${pager.currentPage + 1}/${gallery.items.size}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** A poster with a play button until tapped; [key] identifies it for "only one video plays". */
@Composable
private fun VideoBox(key: String, video: Media.Video, fallbackPoster: String?, state: AppState, modifier: Modifier) {
    val sized = modifier.background(Color.Black)
    if (state.playing == key) {
        VideoPlayer(video, sized)
    } else {
        PlayPoster(video.poster ?: fallbackPoster, sized.clickable { state.playing = key })
    }
}

/** A dimmed [poster] with a play button over it. */
@Composable
private fun PlayPoster(poster: String?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.6f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Play",
            tint = Color.White,
            modifier = Modifier.size(64.dp),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(video: Media.Video, modifier: Modifier) {
    val context = LocalContext.current
    val dataSource = LocalVideoDataSource.current
    val player = remember(video) {
        val item = MediaItem.Builder()
            .setUri(video.hlsUrl ?: video.mp4Url)
            .apply { if (video.hlsUrl != null) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                setMediaItem(item)
                repeatMode = if (video.gif) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                volume = if (video.gif) 0f else 1f
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = !video.gif } },
        modifier = modifier,
    )
}

@Composable
private fun LinkPreview(post: Post, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (post.thumbnail != null) {
                AsyncImage(
                    model = post.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp),
                )
            }
            Column(Modifier.padding(12.dp)) {
                Text(post.domain, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    post.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

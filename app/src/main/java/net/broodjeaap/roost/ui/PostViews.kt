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
import net.broodjeaap.roost.data.Media
import net.broodjeaap.roost.data.Post

/** HTTP for video, sharing the WebView's user agent; provided by MainActivity. */
val LocalVideoDataSource = staticCompositionLocalOf<DataSource.Factory> { error("No video data source") }

/** Feed media is cropped to at most this tall (width / height); threads show it whole. */
private const val MIN_FEED_RATIO = 0.8f

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

@Composable
fun PostStats(post: Post, modifier: Modifier = Modifier) {
    val score = post.score?.let { "${count(it)} points" } ?: "score hidden"
    Text(
        text = "$score · ${count(post.commentCount)} comments",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Media, or a link preview for link posts. [inFeed] crops tall media. */
@Composable
fun PostMedia(post: Post, state: AppState, inFeed: Boolean, onOpen: () -> Unit) {
    when (val media = post.media) {
        is Media.Image -> ImageMedia(media, inFeed, Modifier.clickable(onClick = onOpen))
        is Media.Gallery -> GalleryMedia(media, inFeed)
        is Media.Video -> VideoMedia(post, media, state, inFeed)
        null -> if (!post.isSelf) LinkPreview(post) { state.openUrl(post.url) }
    }
}

private fun ratio(width: Int?, height: Int?, inFeed: Boolean): Float {
    val ratio = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else 16f / 9
    return if (inFeed) ratio.coerceAtLeast(MIN_FEED_RATIO) else ratio
}

@Composable
private fun ImageMedia(image: Media.Image, inFeed: Boolean, modifier: Modifier = Modifier) {
    AsyncImage(
        model = image.url,
        contentDescription = null,
        contentScale = if (inFeed) ContentScale.Crop else ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio(image.width, image.height, inFeed))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
private fun GalleryMedia(gallery: Media.Gallery, inFeed: Boolean) {
    val first = gallery.images.first()
    val pager = rememberPagerState { gallery.images.size }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio(first.width, first.height, inFeed))
            .background(Color.Black)
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = gallery.images[page].url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = "${pager.currentPage + 1}/${gallery.images.size}",
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

@Composable
private fun VideoMedia(post: Post, video: Media.Video, state: AppState, inFeed: Boolean) {
    val modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(ratio(video.width, video.height, inFeed))
        .background(Color.Black)
    if (state.playing == post.id) {
        VideoPlayer(video, modifier)
    } else {
        Box(modifier.clickable { state.playing = post.id }, contentAlignment = Alignment.Center) {
            val poster = video.poster ?: post.thumbnail
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

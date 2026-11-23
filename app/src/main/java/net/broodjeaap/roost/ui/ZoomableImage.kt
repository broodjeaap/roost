package net.broodjeaap.roost.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision

private const val MAX_ZOOM = 5f

/** Zoomed originals are decoded at most this large on either side (also keeps them within GPU texture limits). */
private const val FULL_SIZE_LIMIT = 4096

/** `preview.redd.it/[title-v0-]<id>.<ext>?…`: the original is `i.redd.it/<id>.<ext>`. */
private val PREVIEW = Regex("""^https://preview\.redd\.it/(?:[^/?]*-v0-)?([A-Za-z0-9]+)\.(png|jpe?g|gif|webp)\?""")

/** The full-size original behind a Reddit image URL, or null when there is none to get. */
private fun fullSizeUrl(url: String): String? = when {
    url.startsWith("https://i.redd.it/") -> url
    else -> PREVIEW.find(url)?.let { "https://i.redd.it/${it.groupValues[1]}.${it.groupValues[2]}" }
}

private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

/**
 * An image that zooms in place with two fingers, clipped to its bounds. The zoom stays after the
 * fingers lift: one finger then pans, leaving the drag to the enclosing list or pager at an edge.
 * Pinching back out to 1x resets it. Zoomed in, the full-size original is drawn over the preview,
 * which Reddit serves at only 640px wide. Gestures on [modifier] (`clickable`) see a pinch as consumed.
 */
@Composable
fun ZoomableImage(url: String, contentScale: ContentScale, modifier: Modifier = Modifier) {
    val zoom = remember(url) { ZoomState() }
    Box(modifier.pinchZoom(zoom), propagateMinConstraints = true) {
        AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxWidth())
        val full = remember(url) { fullSizeUrl(url) }
        if (full != null && zoom.scale > 1f) {
            val context = LocalPlatformContext.current
            AsyncImage(
                model = remember(full) {
                    ImageRequest.Builder(context)
                        .data(full)
                        .size(FULL_SIZE_LIMIT, FULL_SIZE_LIMIT)
                        .precision(Precision.INEXACT)
                        .build()
                },
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private fun Modifier.pinchZoom(zoom: ZoomState): Modifier = clipToBounds()
    .pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                // Unzoomed single-finger drags belong to the list or pager.
                if (fingers == 0 || (fingers == 1 && zoom.scale == 1f)) continue
                val scale = (zoom.scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                val offset = if (scale == 1f) {
                    Offset.Zero
                } else {
                    // Keep the content point under the fingers' previous centroid under the current one.
                    val centroid = event.calculateCentroid()
                    val previous = centroid - event.calculatePan()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val moved = centroid - center - (previous - center - zoom.offset) * (scale / zoom.scale)
                    val maxX = (scale - 1f) * size.width / 2f
                    val maxY = (scale - 1f) * size.height / 2f
                    Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                }
                if (fingers >= 2 || offset != zoom.offset) {
                    event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                }
                zoom.scale = scale
                zoom.offset = offset
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer {
        scaleX = zoom.scale
        scaleY = zoom.scale
        translationX = zoom.offset.x
        translationY = zoom.offset.y
    }

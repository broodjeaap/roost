package net.broodjeaap.roost.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration

fun age(createdMillis: Long?, now: Long = System.currentTimeMillis()): String {
    if (createdMillis == null) return ""
    val minutes = (now - createdMillis) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d"
        minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)}mo"
        else -> "${minutes / (60 * 24 * 365)}y"
    }
}

fun count(value: Int): String = when {
    value >= 1_000_000 -> "%.1fm".format(value / 1_000_000f)
    value >= 10_000 -> "${value / 1000}k"
    value >= 1_000 -> "%.1fk".format(value / 1000f)
    else -> value.toString()
}

/** Reddit's rendered markdown as styled text; links go through [onLink]. */
@Composable
fun rememberHtml(html: String, onLink: (String) -> Unit): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(html, linkColor) {
        val text = AnnotatedString.fromHtml(
            html,
            linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            linkInteractionListener = { link -> (link as? LinkAnnotation.Url)?.let { onLink(it.url) } },
        )
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        text.subSequence(0, end)
    }
}

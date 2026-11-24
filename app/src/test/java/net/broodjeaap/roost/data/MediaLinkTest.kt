package net.broodjeaap.roost.data

import net.broodjeaap.roost.data.MediaLink.BlueskyPost
import net.broodjeaap.roost.data.MediaLink.ImgurPost
import net.broodjeaap.roost.data.MediaLink.Ready
import net.broodjeaap.roost.data.MediaLink.Redgifs
import net.broodjeaap.roost.data.MediaLink.Streamable
import net.broodjeaap.roost.data.MediaLink.Tweet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaLinkTest {
    private fun parse(url: String) = MediaLink.parse(url)

    @Test
    fun imgur() {
        assertEquals(Ready(Media.Image("https://i.imgur.com/AbC12.png", null, null)), parse("https://i.imgur.com/AbC12.png"))
        assertEquals(Ready(Media.Image("https://i.imgur.com/AbC12.jpg", null, null)), parse("https://imgur.com/AbC12.jpg"))
        val gifv = Ready(Media.Video(null, "https://i.imgur.com/AbC12.mp4", "https://i.imgur.com/AbC12.jpg", null, null, gif = false))
        assertEquals(gifv, parse("https://i.imgur.com/AbC12.gifv"))
        assertEquals(gifv.copy(media = (gifv.media as Media.Video).copy(gif = true)), parse("http://i.imgur.com/AbC12.gif"))
        assertEquals(ImgurPost("AbC12", album = false), parse("https://imgur.com/AbC12"))
        assertEquals(ImgurPost("AbC12", album = false), parse("https://m.imgur.com/AbC12"))
        assertEquals(ImgurPost("TcBmP", album = true), parse("https://imgur.com/a/TcBmP"))
        assertEquals(ImgurPost("fXeL4VM", album = true), parse("https://imgur.com/gallery/carefully-curated-vol-1030-fXeL4VM"))
        assertEquals(ImgurPost("fXeL4VM", album = true), parse("https://imgur.com/t/funny/fXeL4VM"))
        assertNull(parse("https://imgur.com/user/someone/posts"))
    }

    @Test
    fun youtube() {
        val poster = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        for (url in listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "https://youtube.com/shorts/dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        )) {
            assertEquals(url, Ready(Media.ExternalVideo(url, poster)), parse(url))
        }
        assertNull(parse("https://www.youtube.com/@channel"))
        assertNull(parse("https://www.youtube.com/playlist?list=PL123"))
    }

    @Test
    fun giphy() {
        val video = Ready(
            Media.Video(null, "https://i.giphy.com/3o7TKSjRrfIPjeiVyM.mp4", "https://media.giphy.com/media/3o7TKSjRrfIPjeiVyM/giphy_s.gif", null, null, gif = true),
        )
        assertEquals(video, parse("https://giphy.com/gifs/cat-funny-3o7TKSjRrfIPjeiVyM"))
        assertEquals(video, parse("https://media.giphy.com/media/3o7TKSjRrfIPjeiVyM/giphy.gif"))
        assertEquals(video, parse("https://media1.giphy.com/media/v1.Y2lkPTc5/3o7TKSjRrfIPjeiVyM/giphy.gif?cid=1"))
        assertEquals(video, parse("https://i.giphy.com/3o7TKSjRrfIPjeiVyM.gif"))
    }

    @Test
    fun apiHosts() {
        assertEquals(Tweet("1732824684683784516"), parse("https://x.com/SpaceX/status/1732824684683784516/photo/1"))
        assertEquals(Tweet("1732824684683784516"), parse("https://twitter.com/i/web/status/1732824684683784516?s=20"))
        assertNull(parse("https://x.com/SpaceX"))
        assertEquals(Redgifs("recklesstriangularshorebird"), parse("https://www.redgifs.com/watch/RecklessTriangularShorebird"))
        assertEquals(Streamable("dnd1"), parse("https://streamable.com/dnd1"))
        assertEquals(Streamable("dnd1"), parse("https://streamable.com/e/dnd1"))
        assertEquals(BlueskyPost("bsky.app", "3l6oveex3ii2l"), parse("https://bsky.app/profile/bsky.app/post/3l6oveex3ii2l"))
    }

    @Test
    fun directFiles() {
        val jpg = "https://example.com/photos/cat.JPG?width=640"
        assertEquals(Ready(Media.Image(jpg, null, null)), parse(jpg))
        assertEquals(Ready(Media.Video(null, "https://files.catbox.moe/x1y2z3.mp4", null, null, null, gif = false)), parse("https://files.catbox.moe/x1y2z3.mp4"))
        assertNull(parse("https://www.nytimes.com/2026/11/24/world/article.html"))
        assertNull(parse("https://example.com/animation.gif"))
    }
}

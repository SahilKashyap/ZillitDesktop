package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitLog
import java.nio.ByteBuffer
import org.jcodec.api.FrameGrab
import org.jcodec.common.io.ByteBufferSeekableByteChannel
import org.jcodec.scale.AWTUtil

/**
 * A poster frame for a picked video, as JPEG bytes.
 *
 * What the web's `captureThumbnail` does with a `<video>` and a canvas, done
 * here with jcodec — pure Java, so no ffmpeg install to ask of anyone. H.264
 * in MP4 is what desktop pickers overwhelmingly produce; HEVC (recent iPhone
 * exports) is outside jcodec's reach and returns null, which posts the video
 * without a thumbnail — exactly what happened before this existed.
 *
 * Null on anything undecodable, never a throw: a thumbnail is an ornament,
 * and failing to decorate must not fail the post.
 */
fun videoThumbnailJpeg(bytes: ByteArray): PosterFrame? = try {
    val channel = ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(bytes))
    val grab = FrameGrab.createFrameGrab(channel)

    // A beat into the clip rather than frame zero: openings are often black
    // or mid-fade, and a black rectangle is not a poster.
    runCatching { grab.seekToSecondPrecise(POSTER_SECOND) }

    val picture = grab.nativeFrame ?: return null
    AWTUtil.toBufferedImage(picture).toPosterFrame(MAX_EDGE)
} catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
    // Codec not supported, truncated file, not a video at all — same answer.
    ZillitLog.d(TAG) { "no thumbnail for this video: ${throwable::class.simpleName}" }
    null
}



private const val TAG = "VideoThumbnails"
private const val POSTER_SECOND = 1.0
private const val MAX_EDGE = 480

package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.domain.AudioRecorder
import com.zillit.desktop.feature.home.domain.RecordedAudio
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The system microphone, through `javax.sound.sampled`.
 *
 * ## The format
 *
 * 16 kHz, 16-bit, mono PCM, wrapped as WAV on stop. Voice needs no more, and
 * the arithmetic matters: this is ~32 KB/s, so a two-minute message is under
 * 4 MB through the same S3 pipeline photos use. WAV rather than a compressed
 * codec because the JVM ships no encoder — and every receiving client plays it
 * through a platform player that cannot be missing PCM.
 *
 * ## Threads
 *
 * The capture loop owns a plain thread rather than a coroutine: `line.read`
 * blocks in native code and does not cancel, so a coroutine would only dress
 * the thread up. [stop] closes the line, which unblocks the read, which lets
 * the thread finish — in that order.
 */
class JvmAudioRecorder : AudioRecorder {

    private var line: TargetDataLine? = null
    private var captured: ByteArrayOutputStream? = null
    private var capture: Thread? = null

    override suspend fun start(): ZillitResult<Unit> = withContext(Dispatchers.IO) {
        if (line != null) {
            return@withContext ZillitResult.Failure(
                ZillitError.Validation("A recording is already running."),
            )
        }

        try {
            val info = DataLine.Info(TargetDataLine::class.java, FORMAT)
            if (!AudioSystem.isLineSupported(info)) {
                return@withContext ZillitResult.Failure(noMicrophone())
            }

            val opened = AudioSystem.getLine(info) as TargetDataLine
            opened.open(FORMAT)
            opened.start()

            val sink = ByteArrayOutputStream()
            line = opened
            captured = sink

            capture = thread(name = "zillit-audio-capture") {
                val buffer = ByteArray(BUFFER_BYTES)
                try {
                    // The loop ends when stop() closes the line and read returns
                    // what it has; -1 or a closed line ends it — or the cap does.
                    while (opened.isOpen && sink.size() < MAX_CAPTURE_BYTES) {
                        val read = opened.read(buffer, 0, buffer.size)
                        if (read <= 0) break
                        sink.write(buffer, 0, minOf(read, MAX_CAPTURE_BYTES - sink.size()))
                    }
                    if (sink.size() >= MAX_CAPTURE_BYTES) {
                        ZillitLog.w(TAG) {
                            "recording reached the $MAX_CAPTURE_MINUTES-minute cap; releasing the microphone"
                        }
                    }
                } finally {
                    // Whatever ended the loop, the microphone goes back. A
                    // capture thread that died still holding it kept the mic
                    // live with nothing recording — and the OutOfMemoryError
                    // this cap exists to stop is exactly how it died
                    // (2026-10-18). `stop` still answers with what was
                    // captured, so the user keeps their message either way.
                    runCatching {
                        opened.stop()
                        opened.close()
                    }
                }
            }

            ZillitResult.Success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            // SecurityException here is the OS denying microphone access.
            ZillitLog.w(TAG) { "could not open the microphone: ${throwable::class.simpleName}" }
            reset()
            ZillitResult.Failure(noMicrophone())
        }
    }

    override suspend fun stop(): ZillitResult<RecordedAudio> = withContext(Dispatchers.IO) {
        val open = line
            ?: return@withContext ZillitResult.Failure(
                ZillitError.Validation(str(S.desktop_nothing_is_recording)),
            )

        try {
            // Only if it is still ours to close: the capture thread releases
            // the line itself when the cap ends a recording, and stopping a
            // line that is already closed is not something the platform
            // promises to tolerate. Pressing stop on a capped recording must
            // hand back the thirty minutes, not an error.
            if (open.isOpen) {
                open.stop()
                open.close()
            }
            capture?.join(CAPTURE_JOIN_MILLIS)

            val pcm = captured?.toByteArray() ?: ByteArray(0)
            val durationMillis = pcmDurationMillis(pcm.size, FORMAT)

            if (pcm.isEmpty()) {
                return@withContext ZillitResult.Failure(
                    ZillitError.Validation(str(S.desktop_nothing_captured_check_mic)),
                )
            }

            val wav = ByteArrayOutputStream().also { out ->
                AudioInputStream(
                    ByteArrayInputStream(pcm),
                    FORMAT,
                    (pcm.size / FORMAT.frameSize).toLong(),
                ).use { stream -> AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out) }
            }

            ZillitResult.Success(RecordedAudio(wav.toByteArray(), durationMillis))
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            ZillitLog.w(TAG) { "recording failed to finish: ${throwable::class.simpleName}" }
            ZillitResult.Failure(ZillitError.Storage(str(S.desktop_recording_not_saved)))
        } finally {
            reset()
        }
    }

    override fun cancel() {
        runCatching {
            line?.stop()
            line?.close()
            capture?.join(CAPTURE_JOIN_MILLIS)
        }
        reset()
    }

    private fun reset() {
        line = null
        captured = null
        capture = null
    }

    internal companion object {
        const val TAG = "AudioRecorder"

        val FORMAT = AudioFormat(SAMPLE_RATE, BITS, CHANNELS, true, false)

        const val SAMPLE_RATE = 16_000f
        const val BITS = 16
        const val CHANNELS = 1
        const val BUFFER_BYTES = 4096
        const val CAPTURE_JOIN_MILLIS = 1_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val BITS_PER_BYTE = 8
        const val SECONDS_PER_MINUTE = 60

        /**
         * The longest a single message may run.
         *
         * A ceiling has to exist because the sink is held in memory and
         * nothing else ends the capture: a recording left running grew the
         * buffer until the array could not grow again, and the
         * `OutOfMemoryError` landed on the capture thread
         * (`Required array length 2147483639 + 9 is too large`, 2026-10-18) —
         * about eighteen hours at this format's 32 KB/s.
         *
         * Thirty minutes is far longer than any voice message anyone sends and
         * still only ~55 MB, which is a comfortable fraction of the heap and
         * well under the 256 MB a full-size fetch may already hold.
         */
        const val MAX_CAPTURE_MINUTES = 30

        /** [MAX_CAPTURE_MINUTES] as the byte count this format needs for it. */
        val MAX_CAPTURE_BYTES = SAMPLE_RATE.toInt() *
            (BITS / BITS_PER_BYTE) * CHANNELS *
            MAX_CAPTURE_MINUTES * SECONDS_PER_MINUTE

        fun noMicrophone() = ZillitError.Storage(
            technical = "no usable TargetDataLine",
            userMessage = str(S.desktop_microphone_not_opened),
        )
    }
}

/**
 * How long [byteCount] of PCM in [format] actually plays for.
 *
 * Measured from the samples rather than the wall clock, which is what [stop]
 * used to report. The two agree on an ordinary recording; where they disagree
 * the samples are the honest answer — a capture the cap cut short, or one the
 * line dropped frames from, would otherwise be announced as longer than it
 * plays.
 */
internal fun pcmDurationMillis(byteCount: Int, format: AudioFormat): Long =
    byteCount.toLong() * JvmAudioRecorder.MILLIS_PER_SECOND /
        (format.frameSize * format.frameRate.toLong())

package com.surfcam.app.data.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.surfcam.app.domain.model.CaptureConfig
import com.surfcam.app.domain.model.EncodedSample

/**
 * Encodeur H.265 matériel alimenté par une surface (la caméra écrit directement dedans, sans copie).
 * Un thread dédié vide la sortie de l'encodeur et transmet chaque image encodée à [Listener].
 */
class HevcEncoder(
    private val config: CaptureConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onFormat(format: MediaFormat)
        fun onSample(sample: EncodedSample)
        fun onError(error: Exception)
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null

    @Volatile
    private var running = false

    /** Configure et démarre l'encodeur ; renvoie la surface dans laquelle la caméra doit filmer. */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyFrameIntervalSec)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(
                MediaFormat.KEY_PROFILE,
                if (config.tenBit) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
            )
            if (config.tenBit) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            codec = encoder
            inputSurface = surface
            running = true
            drainThread = Thread({ drain(encoder) }, "hevc-drain").also { it.start() }
            return surface
        } catch (e: Exception) {
            encoder.release()
            throw e
        }
    }

    fun stop() {
        running = false
        drainThread?.join(1_000)
        drainThread = null
        codec?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
    }

    private fun drain(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = encoder.outputFormat
                    if (config.tenBit) {
                        // Le conteneur doit annoncer HLG, sinon les lecteurs montrent des couleurs ternes.
                        out.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        out.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                        out.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    }
                    listener.onFormat(out)
                } else if (index >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && !isConfig) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        listener.onSample(EncodedSample(bytes, info.presentationTimeUs, key))
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        } catch (e: IllegalStateException) {
            if (running) listener.onError(e)
        }
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 20_000L

        /** Vrai si l'encodeur H.265 du téléphone sait faire du 10 bits (profil Main 10). */
        fun supportsMain10(): Boolean = try {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes }
                .any { info ->
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels
                        .any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                }
        } catch (_: Exception) {
            false
        }
    }
}

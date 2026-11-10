package com.kp.p2pvid.rec

import android.content.Context
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.kp.p2pvid.view.Layout
import com.kp.p2pvid.view.Party
import com.kp.p2pvid.view.ViewState
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records what the call screen shows: the current [view] layout of both streams, drawn on
 * the GPU into an H.264 encoder surface, plus mic and remote audio mixed into AAC.
 *
 * Video: each incoming frame is copied into a per-party texture right away (so camera and
 * decoder buffers are released immediately), and a 30 fps tick composes the layout from
 * those textures. Audio: the mic callback is the clock; remote PCM is buffered in a FIFO
 * and pulled in step with the mic.
 */
class Recorder(
    context: Context,
    rootEgl: EglBase.Context,
    private val self: Party,
    val width: Int,
    val height: Int,
    initialView: ViewState,
    private val onFinished: (name: String?, error: Throwable?) -> Unit,
) {
    @Volatile var view: ViewState = initialView
    @Volatile var mirrorLocal: Boolean = true
    @Volatile var micEnabled: Boolean = true
    /** "Show overlay" setting: draws the light frame around the PiP. Off = video only. */
    @Volatile var showOverlay: Boolean = true

    private val output = MediaOutput.create(context)
    private val muxer = SyncMuxer(output.createMuxer())

    private val thread = HandlerThread("rec-gl").apply { start() }
    private val gl = Handler(thread.looper)

    private lateinit var egl: EglBase
    private lateinit var drawer: GlRectDrawer
    private lateinit var frameDrawer: VideoFrameDrawer
    private val fbos = HashMap<Party, GlTextureFrameBuffer>()
    private val lastFrameNs = HashMap<Party, Long>()

    private val videoEncoder: MediaCodec
    private val audioEncoder: MediaCodec
    private val audioLock = Any()
    private var audioClosed = false
    private var audioSamplesWritten = 0L
    private var audioStartUs = -1L
    private val remoteFifo = PcmFifo()

    private val baseUs = System.nanoTime() / 1000
    @Volatile private var running = true

    init {
        val vfmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder.configure(vfmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()

        val afmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioMix.RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()

        val ready = CountDownLatch(1)
        gl.post {
            egl = EglBase.create(rootEgl, EglBase.CONFIG_RECORDABLE)
            egl.createSurface(inputSurface)
            egl.makeCurrent()
            drawer = GlRectDrawer()
            frameDrawer = VideoFrameDrawer()
            ready.countDown()
            gl.post(tick)
        }
        ready.await(2, TimeUnit.SECONDS)
    }

    // ---------------- video ----------------

    private inner class FrameTap(val party: Party) : VideoSink {
        val inFlight = AtomicInteger(0)
        override fun onFrame(frame: VideoFrame) {
            if (!running || inFlight.get() > 1) return
            frame.retain()
            inFlight.incrementAndGet()
            val posted = gl.post {
                try { if (running) copyToTexture(party, frame) } finally { frame.release(); inFlight.decrementAndGet() }
            }
            if (!posted) { frame.release(); inFlight.decrementAndGet() }
        }
    }

    val localSink: VideoSink = FrameTap(self)
    val remoteSink: VideoSink = FrameTap(self.other)

    private fun copyToTexture(party: Party, frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        val fbo = fbos.getOrPut(party) { GlTextureFrameBuffer(GLES20.GL_RGBA) }
        fbo.setSize(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.frameBufferId)
        frameDrawer.drawFrame(frame, drawer, null, 0, 0, w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        lastFrameNs[party] = System.nanoTime()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val start = System.nanoTime()
            runCatching { composeFrame(start) }.onFailure { Log.e(TAG, "compose", it) }
            drain(videoEncoder, SyncMuxer.Track.VIDEO, endOfStream = false)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            gl.postDelayed(this, (1000L / FPS - elapsedMs).coerceAtLeast(1))
        }
    }

    private fun composeFrame(nowNs: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        for (tile in Layout.tiles(view, width, height)) {
            val b = tile.box
            val glY = height - b.y - b.h
            if (tile.isPip) {
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                if (showOverlay) {
                    // thin light frame around the picture-in-picture
                    GLES20.glScissor(b.x - 2, glY - 2, b.w + 4, b.h + 4)
                    GLES20.glClearColor(1f, 1f, 1f, 0.7f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                }
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glScissor(b.x, glY, b.w, b.h)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            }
            val fbo = fbos[tile.party] ?: continue
            val fresh = nowNs - (lastFrameNs[tile.party] ?: 0L) < STALE_NS
            if (!fresh || fbo.width == 0) continue // camera off / stalled → black tile

            val (sx, sy) = Layout.cropScale(fbo.width, fbo.height, b.w, b.h)
            val mirror = tile.party == self && mirrorLocal
            val m = Matrix().apply {
                preTranslate(0.5f, 0.5f)
                preScale(if (mirror) -sx else sx, sy)
                preTranslate(-0.5f, -0.5f)
            }
            drawer.drawRgb(fbo.textureId, RendererCommon.convertMatrixFromAndroidGraphicsMatrix(m),
                fbo.width, fbo.height, b.x, glY, b.w, b.h)
        }
        egl.swapBuffers(nowNs)
    }

    // ---------------- audio ----------------

    /** Remote decoded audio (any rate/channels). */
    fun onRemoteAudio(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int) {
        if (!running || bits != 16) return
        val shorts = ShortArray(frames * channels)
        data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, minOf(shorts.size, data.remaining() / 2))
        remoteFifo.write(AudioMix.resample(AudioMix.toMono(shorts, channels), rate))
    }

    /** Raw mic audio; drives the audio clock. */
    fun onMicAudio(pcm: ByteArray, rate: Int, channels: Int) {
        if (!running) return
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        var mic = AudioMix.resample(AudioMix.toMono(shorts, channels), rate)
        if (!micEnabled) mic = ShortArray(mic.size)
        val mixed = AudioMix.mix(mic, remoteFifo.read(mic.size))
        synchronized(audioLock) {
            if (audioClosed) return
            if (audioStartUs < 0) audioStartUs = System.nanoTime() / 1000 - baseUs
            queueAudio(mixed, audioStartUs + audioSamplesWritten * 1_000_000L / AudioMix.RATE, eos = false)
            audioSamplesWritten += mixed.size
            drain(audioEncoder, SyncMuxer.Track.AUDIO, endOfStream = false)
        }
    }

    private fun queueAudio(samples: ShortArray, ptsUs: Long, eos: Boolean) {
        var offset = 0
        do {
            val idx = audioEncoder.dequeueInputBuffer(10_000)
            if (idx < 0) return
            val buf = audioEncoder.getInputBuffer(idx)!!
            buf.clear()
            val n = minOf(samples.size - offset, buf.capacity() / 2)
            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, offset, n)
            val pts = ptsUs + offset * 1_000_000L / AudioMix.RATE
            val last = offset + n >= samples.size
            audioEncoder.queueInputBuffer(idx, 0, n * 2, pts,
                if (eos && last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            offset += n
        } while (offset < samples.size)
    }

    // ---------------- output ----------------

    private fun drain(codec: MediaCodec, track: SyncMuxer.Track, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idle = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || ++idle > 200) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> muxer.addTrack(track, codec.outputFormat)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0) {
                        // video pts come from swapBuffers(nanoTime); shift to the shared base
                        if (track == SyncMuxer.Track.VIDEO) info.presentationTimeUs -= baseUs
                        muxer.write(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Finalizes the file on a background thread; [onFinished] is called when done. */
    fun stop() {
        if (!running) return
        gl.post {
            running = false
            gl.removeCallbacks(tick)
            var error: Throwable? = null
            runCatching {
                videoEncoder.signalEndOfInputStream()
                drain(videoEncoder, SyncMuxer.Track.VIDEO, endOfStream = true)
            }.onFailure { error = it }
            runCatching {
                synchronized(audioLock) {
                    audioClosed = true
                    if (audioStartUs >= 0) {
                        queueAudio(ShortArray(0), audioStartUs + audioSamplesWritten * 1_000_000L / AudioMix.RATE, eos = true)
                        drain(audioEncoder, SyncMuxer.Track.AUDIO, endOfStream = true)
                    }
                }
            }.onFailure { error = error ?: it }
            val wrote = runCatching { muxer.finish() }.getOrElse { error = error ?: it; false }
            runCatching { videoEncoder.release() }
            runCatching { audioEncoder.release() }
            runCatching { fbos.values.forEach { it.release() } }
            runCatching { drawer.release(); frameDrawer.release(); egl.release() }
            if (wrote) output.publish() else output.discard()
            onFinished(if (wrote) output.displayName else null, if (wrote) null else (error ?: IllegalStateException("nothing recorded")))
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "Recorder"
        const val FPS = 30
        private const val STALE_NS = 1_000_000_000L
    }
}

/** MediaMuxer wrapper: waits for both tracks, buffers early samples, keeps timestamps monotonic. */
class SyncMuxer(private val muxer: MediaMuxer) {
    enum class Track { VIDEO, AUDIO }

    private val ids = HashMap<Track, Int>()
    private val lastPts = HashMap<Track, Long>()
    private val pending = ArrayList<Triple<Track, ByteBuffer, MediaCodec.BufferInfo>>()
    private var started = false
    private var samples = 0

    @Synchronized fun addTrack(track: Track, format: MediaFormat) {
        if (started || track in ids) return
        ids[track] = muxer.addTrack(format)
        if (ids.size == 2) {
            muxer.start()
            started = true
            pending.forEach { (t, b, i) -> writeNow(t, b, i) }
            pending.clear()
        }
    }

    @Synchronized fun write(track: Track, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.presentationTimeUs < 0) return
        if (!started) {
            if (pending.size > 600) return // ~10 s safety cap
            val copy = ByteBuffer.allocateDirect(info.size)
            buf.position(info.offset); buf.limit(info.offset + info.size)
            copy.put(buf); copy.flip()
            val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending += Triple(track, copy, i)
            return
        }
        writeNow(track, buf, info)
    }

    private fun writeNow(track: Track, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val last = lastPts[track] ?: -1L
        if (info.presentationTimeUs <= last) info.presentationTimeUs = last + 1
        lastPts[track] = info.presentationTimeUs
        muxer.writeSampleData(ids.getValue(track), buf, info)
        samples++
    }

    /** Returns true if a playable file was written. */
    @Synchronized fun finish(): Boolean {
        val ok = started && samples > 0
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        return ok
    }
}

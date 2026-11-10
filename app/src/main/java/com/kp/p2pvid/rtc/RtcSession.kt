package com.kp.p2pvid.rtc

import android.content.Context
import android.util.Log
import com.kp.p2pvid.data.Quality
import com.kp.p2pvid.net.SignalChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/**
 * One WebRTC call over the LAN. No ICE servers are configured, so only host candidates
 * are gathered and media flows directly between the two devices' LAN addresses.
 *
 * The host (inviter) creates the offer; SDP and ICE travel over the authenticated
 * [SignalChannel]. View/director/recording control goes over a negotiated data channel.
 */
class RtcSession(
    private val context: Context,
    private val eglBase: EglBase,
    private val signal: SignalChannel,
    private val isOfferer: Boolean,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val localFeed: VideoFanout,
    private val remoteFeed: VideoFanout,
) {
    interface Listener {
        fun onConnectionState(state: PeerConnection.PeerConnectionState)
        fun onControlOpen()
        fun onControlMessage(text: String)
        fun onSignalClosed()
        /** Raw mic PCM (called on the audio record thread). */
        fun onMicSamples(samples: JavaAudioDeviceModule.AudioSamples)
        /** Remote decoded PCM (called on the audio thread). */
        fun onRemoteAudio(data: ByteBuffer, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int)
    }

    private lateinit var factory: PeerConnectionFactory
    private lateinit var pc: PeerConnection
    private lateinit var control: DataChannel
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoSender: RtpSender? = null
    private var quality = Quality.MEDIUM
    private var captureRunning = false
    private var readJob: Job? = null

    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo
    private val _frontCamera = MutableStateFlow(true)
    val frontCamera: StateFlow<Boolean> = _frontCamera

    var localAudio: AudioTrack? = null
        private set
    private var remoteAudio: AudioTrack? = null

    /** Smoothed mic level 0..1, computed from raw samples. */
    @Volatile var micLevel: Double = 0.0
        private set
    @Volatile var micEnabled = true

    private val remoteSink = org.webrtc.AudioTrackSink { data, bits, rate, ch, frames, _ ->
        listener.onRemoteAudio(data, bits, rate, ch, frames)
    }

    fun start(initialQuality: Quality, front: Boolean) {
        quality = initialQuality
        ensureInitialized(context)

        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { s ->
                micLevel = micLevel * 0.6 + rms(s) * 0.4
                listener.onMicSamples(s)
            }
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        adm.release()

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }
        pc = factory.createPeerConnection(config, observer) ?: throw IOException("could not create peer connection")

        control = pc.createDataChannel("ctl", DataChannel.Init().apply { negotiated = true; id = 0; ordered = true })
        control.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (control.state() == DataChannel.State.OPEN) listener.onControlOpen()
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                listener.onControlMessage(String(bytes, Charsets.UTF_8))
            }
        })

        createLocalMedia(front)
        readJob = scope.launch(Dispatchers.IO) { readSignals() }
        if (isOfferer) scope.launch {
            runCatching { negotiateAsOfferer() }.onFailure { Log.e(TAG, "offer failed", it) }
        }
    }

    private fun createLocalMedia(front: Boolean) {
        val audioConstraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }
        audioSource = factory.createAudioSource(audioConstraints)
        localAudio = factory.createAudioTrack("a0", audioSource).also { pc.addTrack(it, listOf("s0")) }

        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames.toList()
        val name = names.firstOrNull { enumerator.isFrontFacing(it) == front } ?: names.firstOrNull()
        if (name != null) {
            _frontCamera.value = enumerator.isFrontFacing(name)
            val cap = enumerator.createCapturer(name, null)
            val helper = SurfaceTextureHelper.create("capture", eglBase.eglBaseContext)
            val source = factory.createVideoSource(false)
            cap.initialize(helper, context, source.capturerObserver)
            cap.startCapture(quality.width, quality.height, quality.fps)
            captureRunning = true
            capturer = cap; textureHelper = helper; videoSource = source
            val track = factory.createVideoTrack("v0", source)
            track.addSink(localFeed)
            videoSender = pc.addTrack(track, listOf("s0"))
            _localVideo.value = track
            applySenderParams()
        }
    }

    // ---- signaling ----

    private suspend fun negotiateAsOfferer() {
        val offer = createSdp(offer = true)
        setSdp(local = true, offer)
        signal.send(JSONObject().put("t", "offer").put("sdp", offer.description))
    }

    private suspend fun readSignals() {
        try {
            while (true) {
                val msg = signal.receive()
                when (msg.optString("t")) {
                    "offer" -> {
                        setSdp(local = false, SessionDescription(SessionDescription.Type.OFFER, msg.getString("sdp")))
                        onRemoteDescriptionSet()
                        val answer = createSdp(offer = false)
                        setSdp(local = true, answer)
                        signal.send(JSONObject().put("t", "answer").put("sdp", answer.description))
                    }
                    "answer" -> {
                        setSdp(local = false, SessionDescription(SessionDescription.Type.ANSWER, msg.getString("sdp")))
                        onRemoteDescriptionSet()
                    }
                    "ice" -> {
                        val c = IceCandidate(msg.getString("mid"), msg.getInt("idx"), msg.getString("c"))
                        synchronized(pendingIce) {
                            if (remoteDescriptionSet) pc.addIceCandidate(c) else pendingIce += c
                        }
                    }
                    "bye" -> { listener.onSignalClosed(); return }
                }
            }
        } catch (e: Exception) {
            // Socket closed. Once media is up this is harmless; the data channel carries control.
            Log.i(TAG, "signal channel closed: ${e.message}")
            listener.onSignalClosed()
        }
    }

    private fun onRemoteDescriptionSet() = synchronized(pendingIce) {
        remoteDescriptionSet = true
        pendingIce.forEach(pc::addIceCandidate)
        pendingIce.clear()
    }

    private suspend fun createSdp(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(IOException("sdp create: $error"))
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }
        if (offer) pc.createOffer(obs, MediaConstraints()) else pc.createAnswer(obs, MediaConstraints())
    }

    private suspend fun setSdp(local: Boolean, sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
        val obs = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(IOException("sdp set: $error"))
        }
        if (local) pc.setLocalDescription(obs, sdp) else pc.setRemoteDescription(obs, sdp)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            scope.launch {
                runCatching {
                    signal.send(JSONObject().put("t", "ice").put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("c", c.sdp))
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            when (val track = transceiver.receiver.track()) {
                is VideoTrack -> {
                    track.addSink(remoteFeed)
                    _remoteVideo.value = track
                }
                is AudioTrack -> {
                    remoteAudio = track
                    track.addSink(remoteSink)
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) applySenderParams()
            listener.onConnectionState(newState)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    // ---- controls ----

    fun sendControl(text: String): Boolean {
        if (!::control.isInitialized || control.state() != DataChannel.State.OPEN) return false
        return control.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    fun setMic(on: Boolean) {
        micEnabled = on
        localAudio?.setEnabled(on)
    }

    fun setCamera(on: Boolean) {
        val cap = capturer ?: return
        _localVideo.value?.setEnabled(on)
        if (on && !captureRunning) {
            cap.startCapture(quality.width, quality.height, quality.fps); captureRunning = true
        } else if (!on && captureRunning) {
            runCatching { cap.stopCapture() }; captureRunning = false
        }
    }

    fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { _frontCamera.value = isFront }
            override fun onCameraSwitchError(error: String?) { Log.w(TAG, "switch camera: $error") }
        })
    }

    /** Applies live: capture format + encoder bitrate cap, no renegotiation. */
    fun setQuality(q: Quality) {
        if (q == quality) return
        quality = q
        if (captureRunning) capturer?.changeCaptureFormat(q.width, q.height, q.fps)
        applySenderParams()
    }

    private fun applySenderParams() {
        val sender = videoSender ?: return
        runCatching {
            val params = sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            params.encodings.forEach {
                it.maxBitrateBps = quality.maxBitrateBps
                it.maxFramerate = quality.fps
            }
            sender.parameters = params
        }.onFailure { Log.w(TAG, "sender params", it) }
    }

    /** Remote audio level 0..1 from the receiver stats (inbound-rtp audioLevel). */
    suspend fun remoteAudioLevel(): Double = suspendCancellableCoroutine { cont ->
        if (!::pc.isInitialized) { cont.resume(0.0); return@suspendCancellableCoroutine }
        pc.getStats { report ->
            val level = report.statsMap.values
                .firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
                ?.members?.get("audioLevel") as? Double
            if (cont.isActive) cont.resume(level ?: 0.0)
        }
    }

    /**
     * Teardown order matters: every native call on a track must happen while the factory
     * and its threads are still alive. Detach our sinks first, then the peer connection
     * (which disposes remote tracks), then our own local track refs, then sources, and
     * the factory last.
     */
    fun close() {
        readJob?.cancel()
        signal.close()
        val local = _localVideo.value
        val remote = _remoteVideo.value
        _localVideo.value = null
        _remoteVideo.value = null
        runCatching { local?.removeSink(localFeed) }
        runCatching { remote?.removeSink(remoteFeed) }
        runCatching { remoteAudio?.removeSink(remoteSink) }
        if (::control.isInitialized) runCatching { control.unregisterObserver(); control.dispose() }
        if (::pc.isInitialized) runCatching { pc.dispose() }
        runCatching { local?.dispose() }
        runCatching { localAudio?.dispose() }
        localAudio = null
        remoteAudio = null
        runCatching { audioSource?.dispose() }
        runCatching { if (captureRunning) capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { textureHelper?.dispose() }
        if (::factory.isInitialized) runCatching { factory.dispose() }
    }

    private fun rms(s: JavaAudioDeviceModule.AudioSamples): Double {
        if (!micEnabled) return 0.0
        val buf = ByteBuffer.wrap(s.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = buf.remaining()
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf.get(i) / 32768.0
            sum += v * v
        }
        return sqrt(sum / n)
    }

    companion object {
        private const val TAG = "RtcSession"
        @Volatile private var initialized = false

        fun ensureInitialized(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }
}

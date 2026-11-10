package com.kp.p2pvid.call

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kp.p2pvid.data.Prefs
import com.kp.p2pvid.data.Settings
import com.kp.p2pvid.net.AuthException
import com.kp.p2pvid.net.InviteCode
import com.kp.p2pvid.net.LanAddress
import com.kp.p2pvid.net.SignalChannel
import com.kp.p2pvid.rec.Recorder
import com.kp.p2pvid.rtc.RtcSession
import com.kp.p2pvid.rtc.VideoFanout
import com.kp.p2pvid.view.Control
import com.kp.p2pvid.view.DirectorState
import com.kp.p2pvid.view.Party
import com.kp.p2pvid.view.SpeakerDetector
import com.kp.p2pvid.view.ViewMode
import com.kp.p2pvid.view.ViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.PeerConnection.PeerConnectionState
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer

enum class Phase { IDLE, HOSTING, JOINING, CONNECTING, IN_CALL }

data class CallUi(
    val phase: Phase = Phase.IDLE,
    val invite: String? = null,
    val self: Party = Party.HOST,
    val view: ViewState = ViewState(),
    val director: Party? = null,
    val micOn: Boolean = true,
    val camOn: Boolean = true,
    val frontCamera: Boolean = true,
    val reconnecting: Boolean = false,
    val recording: Boolean = false,
    val recordingSinceMs: Long = 0,
    val peerRecording: Boolean = false,
    val message: String? = null,
) {
    val isDirector get() = director != null && director == self
    val isFollower get() = director != null && director != self
    val peer get() = self.other
}

class CallViewModel(app: Application) : AndroidViewModel(app) {
    val eglBase: EglBase = EglBase.create()
    private val prefs = Prefs(app)
    val settings: StateFlow<Settings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private var session: RtcSession? = null

    /** Stable video feeds per party. Renderers/recorder attach here, never to native tracks. */
    val localFeed = VideoFanout()
    val remoteFeed = VideoFanout()

    private var server: ServerSocket? = null
    private var setupJob: Job? = null
    private var callJobs = mutableListOf<Job>()
    private var director = DirectorState(Party.HOST)
    private var speaker = SpeakerDetector(Party.JOINER)
    private var disconnectJob: Job? = null
    @Volatile private var recorder: Recorder? = null
    private val audioRouter = AudioRouter(app)

    val lanAddress: String? get() = LanAddress.find()

    init {
        // Live quality changes from settings apply to the running call.
        viewModelScope.launch {
            settings.collect { s ->
                session?.setQuality(s.quality)
                recorder?.showOverlay = s.showOverlay
            }
        }
    }

    // ---------------- setup ----------------

    fun host() {
        if (_ui.value.phase != Phase.IDLE) return
        val ip = LanAddress.find()
        if (ip == null) {
            message("Not connected to a local network. Join Wi-Fi or turn on a hotspot.")
            return
        }
        setupJob = viewModelScope.launch {
            try {
                val srv = withContext(Dispatchers.IO) { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) } }
                server = srv
                val invite = InviteCode.generate(ip, srv.localPort)
                _ui.value = CallUi(phase = Phase.HOSTING, invite = invite.encode(), self = Party.HOST)
                val channel = SignalChannel.accept(srv, invite.secret)
                closeServer()
                beginCall(channel, Party.HOST)
            } catch (e: Exception) {
                closeServer()
                if (_ui.value.phase == Phase.HOSTING) {
                    _ui.value = CallUi(message = "Could not start call: ${e.message}")
                }
            }
        }
    }

    fun join(code: String) {
        if (_ui.value.phase != Phase.IDLE) return
        val invite = InviteCode.parse(code)
        if (invite == null) {
            message("That code doesn't look right. It has 16 characters.")
            return
        }
        _ui.value = CallUi(phase = Phase.JOINING, self = Party.JOINER)
        setupJob = viewModelScope.launch {
            try {
                val channel = SignalChannel.connect(invite)
                beginCall(channel, Party.JOINER)
            } catch (e: AuthException) {
                _ui.value = CallUi(message = "Wrong invite code.")
            } catch (e: IOException) {
                _ui.value = CallUi(message = "Can't reach the other device. Make sure both are on the same Wi-Fi or hotspot, and the code is current.")
            }
        }
    }

    fun cancelSetup() {
        setupJob?.cancel()
        closeServer()
        _ui.value = CallUi()
    }

    private fun closeServer() {
        runCatching { server?.close() }
        server = null
    }

    private fun beginCall(channel: SignalChannel, self: Party) {
        val s = settings.value
        director = DirectorState(self)
        speaker = SpeakerDetector(self.other)
        _ui.value = CallUi(
            phase = Phase.CONNECTING,
            self = self,
            view = ViewState(mode = s.defaultMode, lockTarget = self.other, speaker = self.other),
            frontCamera = s.frontCamera,
        )
        val app = getApplication<Application>()
        CallService.start(app)
        audioRouter.start()
        val rtc = RtcSession(app, eglBase, channel, isOfferer = self == Party.HOST, viewModelScope, rtcListener, localFeed, remoteFeed)
        session = rtc
        rtc.start(s.quality, s.frontCamera)
        callJobs += viewModelScope.launch { rtc.frontCamera.collect { f -> _ui.update { it.copy(frontCamera = f) }; recorder?.mirrorLocal = f } }
        callJobs += viewModelScope.launch {
            delay(20_000)
            if (_ui.value.phase == Phase.CONNECTING) endCall("Couldn't establish the video connection on this network.")
        }
        callJobs += viewModelScope.launch { speakerLoop() }
    }

    private val rtcListener = object : RtcSession.Listener {
        override fun onConnectionState(state: PeerConnectionState) {
            viewModelScope.launch {
                when (state) {
                    PeerConnectionState.CONNECTED -> {
                        disconnectJob?.cancel()
                        _ui.update { it.copy(phase = Phase.IN_CALL, reconnecting = false) }
                    }
                    PeerConnectionState.DISCONNECTED -> {
                        _ui.update { it.copy(reconnecting = true) }
                        disconnectJob?.cancel()
                        disconnectJob = launch {
                            delay(10_000)
                            endCall("Connection lost.")
                        }
                    }
                    PeerConnectionState.FAILED -> endCall("Connection lost.")
                    else -> Unit
                }
            }
        }

        override fun onControlOpen() {
            viewModelScope.launch {
                if (recorder != null) send(Control.Recording(true))
            }
        }

        override fun onControlMessage(text: String) {
            val msg = Control.parse(text) ?: return
            viewModelScope.launch { onControl(msg) }
        }

        override fun onSignalClosed() {
            // If media never came up, the setup socket closing means the peer is gone.
            viewModelScope.launch {
                if (_ui.value.phase == Phase.CONNECTING) endCall("The other device left.")
            }
        }

        override fun onMicSamples(samples: JavaAudioDeviceModule.AudioSamples) {
            recorder?.onMicAudio(samples.data, samples.sampleRate, samples.channelCount)
        }

        override fun onRemoteAudio(data: ByteBuffer, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int) {
            recorder?.onRemoteAudio(data, bitsPerSample, sampleRate, channels, frames)
        }
    }

    // ---------------- view + director ----------------

    private fun onControl(msg: Control) {
        when (msg) {
            is Control.Director -> {
                val value = if (msg.claim) _ui.value.peer else null
                if (director.onRemote(value, msg.epoch)) {
                    _ui.update { it.copy(director = director.director) }
                }
            }
            is Control.View -> if (director.isFollower) {
                setView(_ui.value.view.copy(mode = msg.mode, lockTarget = msg.lockTarget))
            }
            is Control.Speaker -> if (director.isFollower) {
                speaker.reset(msg.who)
                setView(_ui.value.view.copy(speaker = msg.who))
            }
            is Control.Recording -> _ui.update { it.copy(peerRecording = msg.on) }
            Control.Bye -> endCall("The other device ended the call.")
        }
    }

    private fun setView(v: ViewState) {
        _ui.update { it.copy(view = v) }
        recorder?.view = v
    }

    fun setMode(mode: ViewMode) {
        if (_ui.value.isFollower) return
        val cur = _ui.value.view
        // Locking keeps whoever is on screen right now.
        val lock = if (mode == ViewMode.LOCKED && cur.mode != ViewMode.LOCKED) cur.main else cur.lockTarget
        applyLocalView(cur.copy(mode = mode, lockTarget = lock))
    }

    /** Tap on the picture-in-picture: pin that person to the main view. */
    fun focusOn(party: Party) {
        if (_ui.value.isFollower) return
        applyLocalView(_ui.value.view.copy(mode = ViewMode.LOCKED, lockTarget = party))
    }

    private fun applyLocalView(v: ViewState) {
        setView(v)
        if (director.isDirector) send(Control.View(v.mode, v.lockTarget))
    }

    fun setDirector(on: Boolean) {
        if (on) {
            val epoch = director.claim()
            send(Control.Director(true, epoch))
            val v = _ui.value.view
            send(Control.View(v.mode, v.lockTarget))
            send(Control.Speaker(v.speaker))
        } else if (director.isDirector) {
            send(Control.Director(false, director.release()))
        }
        _ui.update { it.copy(director = director.director) }
    }

    private suspend fun speakerLoop() {
        while (viewModelScope.isActive) {
            delay(200)
            val rtc = session ?: return
            if (_ui.value.phase != Phase.IN_CALL) continue
            val remote = runCatching { rtc.remoteAudioLevel() }.getOrDefault(0.0)
            if (director.isFollower) continue // the director decides who is on screen
            val ui = _ui.value
            val levels = mapOf(ui.self to (if (ui.micOn) rtc.micLevel else 0.0), ui.peer to remote)
            val who = speaker.update(levels, SystemClock.elapsedRealtime())
            if (who != ui.view.speaker) {
                setView(ui.view.copy(speaker = who))
                if (director.isDirector) send(Control.Speaker(who))
            }
        }
    }

    // ---------------- media controls ----------------

    fun toggleMic() {
        val on = !_ui.value.micOn
        session?.setMic(on)
        recorder?.micEnabled = on
        _ui.update { it.copy(micOn = on) }
    }

    fun toggleCamera() {
        val on = !_ui.value.camOn
        session?.setCamera(on)
        _ui.update { it.copy(camOn = on) }
    }

    fun switchCamera() = session?.switchCamera()

    fun updateSettings(s: Settings) = viewModelScope.launch { prefs.update(s) }

    // ---------------- recording ----------------

    fun toggleRecording(landscape: Boolean) {
        if (recorder != null) stopRecording() else startRecording(landscape)
    }

    private fun startRecording(landscape: Boolean) {
        val ui = _ui.value
        if (ui.phase != Phase.IN_CALL) return
        val (w, h) = if (landscape) 1280 to 720 else 720 to 1280
        val rec = try {
            Recorder(getApplication(), eglBase.eglBaseContext, ui.self, w, h, ui.view) { name, err ->
                viewModelScope.launch {
                    message(if (name != null) "Saved to Movies/P2Pvid/$name" else "Recording failed: ${err?.message}")
                }
            }
        } catch (e: Exception) {
            message("Can't record: ${e.message}")
            return
        }
        rec.mirrorLocal = ui.frontCamera
        rec.micEnabled = ui.micOn
        rec.showOverlay = settings.value.showOverlay
        localFeed.add(rec.localSink)
        remoteFeed.add(rec.remoteSink)
        recorder = rec
        _ui.update { it.copy(recording = true, recordingSinceMs = SystemClock.elapsedRealtime()) }
        send(Control.Recording(true))
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        localFeed.remove(rec.localSink)
        remoteFeed.remove(rec.remoteSink)
        rec.stop()
        _ui.update { it.copy(recording = false) }
        send(Control.Recording(false))
    }

    // ---------------- teardown ----------------

    fun hangUp() = endCall(null)

    private fun endCall(reason: String?) {
        if (session == null && _ui.value.phase == Phase.IDLE) return
        stopRecording()
        send(Control.Bye)
        disconnectJob?.cancel()
        callJobs.forEach { it.cancel() }
        callJobs.clear()
        val rtc = session
        session = null
        rtc?.close()
        audioRouter.stop()
        CallService.stop(getApplication())
        _ui.value = CallUi(message = reason)
    }

    private fun send(c: Control) {
        session?.sendControl(c.toJson())
    }

    fun message(text: String) = _ui.update { it.copy(message = text) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    override fun onCleared() {
        endCall(null)
        cancelSetup()
        eglBase.release()
    }
}

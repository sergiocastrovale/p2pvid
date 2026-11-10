package com.kp.p2pvid.rtc

import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A stable, pure-Kotlin video sink that forwards frames to any number of targets
 * (screen renderers, the recorder).
 *
 * Only [RtcSession] attaches this to a native WebRTC track, and it detaches it in the
 * right order during teardown. UI and recorder only add/remove themselves here, so they
 * never call into a native track that may already be disposed (that would crash the
 * process natively, which no try/catch can stop).
 */
class VideoFanout : VideoSink {
    private val targets = CopyOnWriteArraySet<VideoSink>()

    fun add(sink: VideoSink) { targets += sink }
    fun remove(sink: VideoSink) { targets -= sink }

    override fun onFrame(frame: VideoFrame) {
        for (t in targets) t.onFrame(frame)
    }
}

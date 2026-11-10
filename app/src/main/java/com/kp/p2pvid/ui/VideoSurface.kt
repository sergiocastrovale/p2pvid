package com.kp.p2pvid.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * A WebRTC video renderer slot. The slot stays in place across layout changes; only the
 * track feeding it changes, which avoids re-creating surfaces when the view mode switches.
 * Always center-crops (aspect fill), matching the recorder.
 */
@Composable
fun VideoSurface(
    egl: EglBase,
    track: VideoTrack?,
    mirror: Boolean,
    overlay: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
            }
        },
        update = { r ->
            r.setMirror(mirror)
            val current = r.tag as? VideoTrack
            if (current !== track) {
                current?.let { runCatching { it.removeSink(r) } }
                if (track == null) r.clearImage() else runCatching { track.addSink(r) }
                r.tag = track
            }
        },
        onRelease = { r ->
            (r.tag as? VideoTrack)?.let { runCatching { it.removeSink(r) } }
            r.tag = null
            r.release()
        },
    )
}

package com.kp.p2pvid.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kp.p2pvid.rtc.VideoFanout
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * A WebRTC video renderer slot. The slot stays in place across layout changes; only the
 * feed it listens to changes, which avoids re-creating surfaces when the view mode switches.
 * Always center-crops (aspect fill), matching the recorder.
 *
 * Renderers subscribe to a [VideoFanout], never to a native track directly, so tearing
 * down the call can't race with the UI releasing its renderers.
 */
@Composable
fun VideoSurface(
    egl: EglBase,
    feed: VideoFanout,
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
            val current = r.tag as? VideoFanout
            if (current !== feed) {
                current?.remove(r)
                r.clearImage()
                feed.add(r)
                r.tag = feed
            }
        },
        onRelease = { r ->
            (r.tag as? VideoFanout)?.remove(r)
            r.tag = null
            r.release()
        },
    )
}

package com.kp.p2pvid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kp.p2pvid.view.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Outgoing stream quality. Each device controls only what it sends. */
enum class Quality(val label: String, val width: Int, val height: Int, val fps: Int, val maxBitrateBps: Int) {
    LOW("Low · 360p", 640, 360, 15, 600_000),
    MEDIUM("Medium · 720p", 1280, 720, 30, 2_000_000),
    HIGH("High · 1080p", 1920, 1080, 30, 5_000_000),
    MAX("Max · 1080p+", 1920, 1080, 30, 12_000_000),
}

data class Settings(
    val quality: Quality = Quality.MEDIUM,
    val defaultMode: ViewMode = ViewMode.DYNAMIC,
    val frontCamera: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Status pills, name tags and PiP frame. Off: only video, overlay appears on tap. */
    val showOverlay: Boolean = true,
)

private val Context.store by preferencesDataStore("settings")

class Prefs(private val context: Context) {
    private object K {
        val quality = stringPreferencesKey("quality")
        val mode = stringPreferencesKey("default_mode")
        val front = booleanPreferencesKey("front_camera")
        val screenOn = booleanPreferencesKey("keep_screen_on")
        val overlay = booleanPreferencesKey("show_overlay")
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            quality = p[K.quality]?.let { runCatching { Quality.valueOf(it) }.getOrNull() } ?: Quality.MEDIUM,
            defaultMode = p[K.mode]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.DYNAMIC,
            frontCamera = p[K.front] ?: true,
            keepScreenOn = p[K.screenOn] ?: true,
            showOverlay = p[K.overlay] ?: true,
        )
    }

    suspend fun update(s: Settings) {
        context.store.edit {
            it[K.quality] = s.quality.name
            it[K.mode] = s.defaultMode.name
            it[K.front] = s.frontCamera
            it[K.screenOn] = s.keepScreenOn
            it[K.overlay] = s.showOverlay
        }
    }
}

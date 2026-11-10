package com.kp.p2pvid.rec

import android.content.ContentValues
import android.content.Context
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An MP4 destination in Movies/P2Pvid that shows up in the gallery once finished. */
class MediaOutput private constructor(
    private val context: Context,
    val displayName: String,
    private val uri: Uri?,
    private val pfd: ParcelFileDescriptor?,
    private val file: File?,
) {
    fun createMuxer(): MediaMuxer =
        if (pfd != null) MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        else MediaMuxer(file!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    fun publish() {
        runCatching { pfd?.close() }
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        file?.let { MediaScannerConnection.scanFile(context, arrayOf(it.absolutePath), arrayOf("video/mp4"), null) }
    }

    fun discard() {
        runCatching { pfd?.close() }
        uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        file?.delete()
    }

    companion object {
        const val FOLDER = "P2Pvid"

        fun create(context: Context): MediaOutput {
            val name = "P2Pvid_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values) ?: error("MediaStore insert failed")
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: error("cannot open output")
                MediaOutput(context, name, uri, pfd, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
                dir.mkdirs()
                MediaOutput(context, name, null, null, File(dir, name))
            }
        }
    }
}

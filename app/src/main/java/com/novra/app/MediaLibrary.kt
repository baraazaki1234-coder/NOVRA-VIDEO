package com.novra.app

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.net.Uri

data class LocalMedia(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String = "",
    val durationMs: Long,
    val sizeBytes: Long,
    val isVideo: Boolean
)

object MediaLibrary {
    fun videos(context: Context): List<LocalMedia> = query(
        context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true
    )

    fun music(context: Context): List<LocalMedia> = query(
        context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false
    )

    private fun query(context: Context, collection: Uri, video: Boolean): List<LocalMedia> {
        val projection = if (video) arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE
        ) else arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE
        )
        val result = mutableListOf<LocalMedia>()
        context.contentResolver.query(
            collection, projection, null, null,
            if (video) MediaStore.Video.Media.DATE_ADDED + " DESC"
            else MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val artistCol = if (!video) cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST) else -1
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += LocalMedia(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = cursor.getString(titleCol) ?: "Untitled",
                    artist = if (artistCol >= 0) cursor.getString(artistCol)?.takeUnless { it == "<unknown>" }.orEmpty() else "",
                    durationMs = cursor.getLong(durationCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    isVideo = video
                )
            }
        }
        return result
    }
}

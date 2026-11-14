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
    val isVideo: Boolean,
    val dateAddedSeconds: Long = 0L,
    val albumId: Long = -1L
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
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED
        ) else arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val result = mutableListOf<LocalMedia>()
        val selection = if (video) null else "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        context.contentResolver.query(
            collection, projection, selection, null,
            if (video) MediaStore.Video.Media.DATE_ADDED + " DESC"
            else MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val artistCol = if (!video) cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST) else -1
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateAddedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val albumIdCol = if (!video) cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID) else -1
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += LocalMedia(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = cursor.getString(titleCol) ?: "Untitled",
                    artist = if (artistCol >= 0) cursor.getString(artistCol)?.takeUnless { it == "<unknown>" }.orEmpty() else "",
                    durationMs = cursor.getLong(durationCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    isVideo = video,
                    dateAddedSeconds = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L,
                    albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else -1L
                )
            }
        }
        return result
    }
}

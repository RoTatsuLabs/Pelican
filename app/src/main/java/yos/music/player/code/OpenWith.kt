package yos.music.player.code

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import yos.music.player.data.libraries.YosMediaItem

/** Plays an audio file opened from another app as a one-song queue */
suspend fun playOpenedAudio(context: Context, uri: Uri) {
    // Wait for the saved queue to be restored, so it can't replace this one
    withTimeoutOrNull(5_000) { MediaController.playbackRestored.await() } ?: return
    val item = withContext(Dispatchers.IO) { openedAudioItem(context, uri) }
    MediaController.prepare(item, listOf(item))
}

private fun openedAudioItem(context: Context, uri: Uri): YosMediaItem {
    val retriever = MediaMetadataRetriever()
    try {
        runCatching { retriever.setDataSource(context, uri) }
        fun meta(key: Int) = runCatching { retriever.extractMetadata(key) }
            .getOrNull()?.takeIf { it.isNotBlank() }

        val fileName = runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment

        return YosMediaItem(
            uri = uri,
            mediaId = uri.toString(),
            mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                ?: meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: fileName?.substringBeforeLast('.'),
            writer = null,
            compilation = null,
            composer = null,
            artists = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            albumArtists = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            thumb = null,
            trackNumber = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.toIntOrNull(),
            discNumber = null,
            genre = meta(MediaMetadataRetriever.METADATA_KEY_GENRE),
            recordingDay = null,
            recordingMonth = null,
            recordingYear = null,
            releaseYear = null,
            artistId = null,
            albumId = null,
            genreId = null,
            author = null,
            addDate = null,
            duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            modifiedDate = null,
            cdTrackNumber = null
        )
    } finally {
        retriever.release()
    }
}

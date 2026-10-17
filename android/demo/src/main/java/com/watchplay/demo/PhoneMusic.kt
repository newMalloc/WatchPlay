package com.watchplay.demo

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Plays the phone's own music and reports what it is doing.
 *
 * Everything that touches `MediaPlayer` runs on one scheduler thread — reads included, because a
 * `MediaPlayer` is not safe to poke from two threads. The mirroring side only ever reads the `@Volatile`
 * snapshot, so `pollUpdate` on the SDK's control thread never blocks on the media stack.
 *
 * Tracks come from `MediaStore`, i.e. the user's real library, not from a bundled sample: the point of the
 * music page is to mirror whatever is playing on the phone.
 */
class PhoneMusic(private val context: Context) : DemoMusic {

    /** Why nothing can be played, if anything. */
    enum class State {
        /** `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` has not been granted. */
        NO_PERMISSION,

        /** Permission granted, but the library is empty. */
        EMPTY,

        /** A track list is loaded. */
        READY,
    }

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "watchplay-music").apply { isDaemon = true }
        }

    private var player: MediaPlayer? = null
    private var playlist: List<Track> = emptyList()
    private var index = 0
    private var released = false

    @Volatile
    private var state: State = State.NO_PERMISSION

    @Volatile
    private var trackTitle: String = ""

    @Volatile
    private var trackArtist: String = ""

    @Volatile
    private var playing: Boolean = false

    @Volatile
    private var trackProgress: Float = 0f

    @Volatile
    private var trackCover: String? = null

    init {
        // A quarter-second ticker keeps the watch's progress bar honest without letting `pollUpdate` read
        // `MediaPlayer.currentPosition` itself.
        scheduler.scheduleWithFixedDelay({ refreshProgress() }, PROGRESS_PERIOD_MS, PROGRESS_PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    override val available: Boolean
        get() = state == State.READY

    override val title: String
        get() = when (state) {
            State.READY -> trackTitle
            State.EMPTY -> context.getString(R.string.music_missing)
            State.NO_PERMISSION -> context.getString(R.string.music_permission)
        }

    override val artist: String
        get() = when (state) {
            State.READY -> trackArtist
            else -> ""
        }

    override val isPlaying: Boolean
        get() = playing

    override val progress: Float
        get() = trackProgress

    override val cover: String?
        get() = trackCover

    override val statusText: String
        get() = if (state == State.READY) "" else title

    /** Loads the phone's track list. Safe to call once the permission has been granted. */
    fun load() {
        scheduler.execute { queryLibrary() }
    }

    override fun toggle() {
        scheduler.execute {
            val current = player
            when {
                current == null -> startAt(index)
                current.isPlaying -> {
                    current.pause()
                    playing = false
                }

                else -> {
                    current.start()
                    playing = true
                }
            }
        }
    }

    override fun next() {
        scheduler.execute {
            if (playlist.isEmpty()) return@execute
            startAt((index + 1) % playlist.size)
        }
    }

    override fun previous() {
        scheduler.execute {
            if (playlist.isEmpty()) return@execute
            startAt((index - 1 + playlist.size) % playlist.size)
        }
    }

    /** Stops playback and frees the player; the object must not be used afterwards. */
    fun release() {
        released = true
        scheduler.execute { stopAndRelease() }
        scheduler.shutdown()
    }

    // ---------------------------------------------------------------- scheduler thread only

    private fun queryLibrary() {
        if (released) return
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} > ${MIN_TRACK_MS}"
        val tracks = ArrayList<Track>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                while (cursor.moveToNext() && tracks.size < MAX_TRACKS) {
                    val id = cursor.getLong(idColumn)
                    tracks += Track(
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        title = cursor.getString(titleColumn).orEmpty(),
                        artist = cursor.getString(artistColumn).orEmpty().takeUnless {
                            it.isEmpty() || it == MediaStore.UNKNOWN_STRING
                        }.orEmpty(),
                        durationMs = cursor.getLong(durationColumn),
                        albumId = cursor.getLong(albumColumn),
                    )
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "no permission to read the music library", error)
            state = State.NO_PERMISSION
            return
        }
        playlist = tracks
        state = if (tracks.isEmpty()) State.EMPTY else State.READY
        index = 0
        tracks.firstOrNull()?.let {
            trackTitle = it.title
            trackArtist = it.artist
            // Art for the track the scene shows before anyone presses play.
            trackCover = readCover(it)
        }
    }

    private fun startAt(position: Int) {
        val track = playlist.getOrNull(position) ?: return
        stopAndRelease()
        index = position
        trackTitle = track.title
        trackArtist = track.artist
        trackProgress = 0f
        // Cleared before the player is even built: showing the previous track's art under this track's
        // name is worse than showing none for the few hundred milliseconds the fetch takes.
        trackCover = null
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            created.setDataSource(context, track.uri)
            created.setOnCompletionListener {
                // Unattended playback keeps going, which is what a mirrored player should do.
                startAt((index + 1) % playlist.size)
            }
            created.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "media error what=$what extra=$extra")
                playing = false
                true
            }
            created.prepare()
            created.start()
            player = created
            playing = true
            // One assignment, after the title is already the new track's: the scene never shows last
            // track's art next to this track's name.
            trackCover = readCover(track)
        } catch (error: Exception) {
            Log.w(TAG, "cannot play ${track.uri}", error)
            created.release()
            playing = false
        }
    }

    private fun stopAndRelease() {
        player?.let { current ->
            try {
                if (current.isPlaying) current.stop()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "player was not in a stoppable state", error)
            }
            current.release()
        }
        player = null
        playing = false
    }

    /**
     * Reads the track's artwork as a `data:` URI, or `null` when nothing can be found.
     *
     * Three sources, in the order a phone can actually reach them:
     *
     *  1. the picture embedded in the file (`MediaMetadataRetriever.embeddedPicture`) — what an album rip
     *     carries;
     *  2. `ContentResolver.loadThumbnail` (`API 29+`), where the media provider generates the thumbnail
     *     itself — this is what makes tracks without an embedded picture show art on devices whose provider
     *     extracts it;
     *  3. the legacy `content://media/external/audio/albumart/<album_id>` path, still served by some
     *     vendor providers.
     *
     * A `content://` thumbnail cannot be handed to the scene: `06-rendering.md` §3.2 sets
     * `blockNetworkLoads = true`, so the document makes no requests and the phone is the one that reads
     * the picture. 96 px matches what the watch page draws (16vmin of a 466 px panel is 75 px), and a
     * quality-80 JPEG that size is 2–6 kB of base64 — small enough to ride inside one state update,
     * which is why the demo pushes it once per track instead of once per second.
     *
     * A missing picture is not an error: plenty of tracks have none, and the scene keeps its gradient
     * disc for them.
     */
    private fun readCover(track: Track): String? {
        embeddedCover(track)?.let { return it }
        providerThumbnail(track)?.let { return it }
        legacyAlbumArt(track)?.let { return it }
        Log.i(TAG, "no cover art for ${track.title}: embedded, provider and album_art all empty")
        return null
    }

    /** The picture inside the file itself. */
    private fun embeddedCover(track: Track): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, track.uri)
            val picture = retriever.embeddedPicture ?: return null
            encodeThumbnail(picture, "embedded")
        } catch (error: Exception) {
            // A corrupt picture must not cost the user their music.
            Log.w(TAG, "no embedded picture for ${track.title}", error)
            null
        } finally {
            try {
                retriever.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "cannot release the metadata retriever", error)
            }
        }
    }

    /** The provider's own thumbnail, which it may synthesise for tracks that carry no picture. */
    private fun providerThumbnail(track: Track): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val bitmap = context.contentResolver.loadThumbnail(
                track.uri,
                Size(COVER_PX, COVER_PX),
                null,
            ) ?: return null
            try {
                encodeBitmap(bitmap, "provider")
            } finally {
                bitmap.recycle()
            }
        } catch (error: Exception) {
            // `IOException` for "no thumbnail", `SecurityException` when the provider says no.
            null
        }
    }

    /** The album-art row some vendor providers still export. */
    private fun legacyAlbumArt(track: Track): String? {
        if (track.albumId <= 0L) return null
        val uri = ContentUris.withAppendedId(ALBUM_ART_URI, track.albumId)
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                encodeThumbnail(stream.readBytes(), "album_art")
            }
        } catch (error: Exception) {
            null
        }
    }

    /** Decodes a picture down to [COVER_PX]² and returns it as a `data:` URI. */
    private fun encodeThumbnail(picture: ByteArray, source: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(picture, 0, picture.size, options) ?: return null
        return try {
            encodeBitmap(decoded, source)
        } finally {
            decoded.recycle()
        }
    }

    /** Re-encodes an already decoded picture; [source] only names it in the log. */
    private fun encodeBitmap(bitmap: Bitmap, source: String): String? {
        val thumbnail = squareThumbnail(bitmap)
        return try {
            val jpeg = ByteArrayOutputStream(COVER_PX * COVER_PX)
            if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, jpeg)) return null
            Log.i(TAG, "cover art from $source: ${thumbnail.width}x${thumbnail.height} ${jpeg.size()} B JPEG")
            COVER_PREFIX + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (thumbnail !== bitmap) thumbnail.recycle()
        }
    }

    /**
     * Centre-crops [source] to a square and scales it to [COVER_PX].
     *
     * The watch shows the middle of the picture inside a circle anyway, so the crop loses nothing
     * visible, and the scene's `object-fit: cover` then has no work left to do per frame.
     */
    private fun squareThumbnail(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val cropped = if (side == source.width && side == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        }
        val scaled = Bitmap.createScaledBitmap(cropped, COVER_PX, COVER_PX, true)
        if (cropped !== source && cropped !== scaled) cropped.recycle()
        return scaled
    }

    /** Power-of-two subsample that still leaves 2× [COVER_PX] to scale down from. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= COVER_PX * 2) sample *= 2
        return sample
    }

    private fun refreshProgress() {
        val current = player ?: return
        try {
            val duration = current.duration
            if (duration > 0) {
                trackProgress = (current.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            }
        } catch (error: IllegalStateException) {
            // The player was released between the null check and the read.
            Log.w(TAG, "cannot read playback position", error)
        }
    }

    private data class Track(
        val uri: Uri,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val albumId: Long,
    )

    companion object {
        private const val TAG = "WatchPlayMusic"

        /** Progress sampling period; the scene repaints at ~10 fps and the data arrives every second. */
        private const val PROGRESS_PERIOD_MS = 250L

        /** Ignore ringtones and notification blips: the library query already filters by `IS_MUSIC`. */
        private const val MIN_TRACK_MS = 10_000L

        /** Enough for a demo picker without holding a huge cursor. */
        private const val MAX_TRACKS = 200

        /** Album-art edge in pixels; the scene draws it at ~75 px on a 466 px panel. */
        private const val COVER_PX = 96

        /** JPEG quality for the art: at 96 px the difference above 80 is invisible, the bytes are not. */
        private const val COVER_QUALITY = 80

        /** Scheme the scene needs, since it cannot fetch anything itself. */
        private const val COVER_PREFIX = "data:image/jpeg;base64,"

        /**
         * Album-art endpoint some vendor providers still answer.
         *
         * Removed from AOSP in Android 10 in favour of provider thumbnails, which is why it is the last
         * fallback rather than the first.
         */
        private val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

package com.watchplay.demo

import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
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
                while (cursor.moveToNext() && tracks.size < MAX_TRACKS) {
                    val id = cursor.getLong(idColumn)
                    tracks += Track(
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        title = cursor.getString(titleColumn).orEmpty(),
                        artist = cursor.getString(artistColumn).orEmpty().takeUnless {
                            it.isEmpty() || it == MediaStore.UNKNOWN_STRING
                        }.orEmpty(),
                        durationMs = cursor.getLong(durationColumn),
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
        }
    }

    private fun startAt(position: Int) {
        val track = playlist.getOrNull(position) ?: return
        stopAndRelease()
        index = position
        trackTitle = track.title
        trackArtist = track.artist
        trackProgress = 0f
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
    )

    companion object {
        private const val TAG = "WatchPlayMusic"

        /** Progress sampling period; the scene repaints at ~10 fps and the data arrives every second. */
        private const val PROGRESS_PERIOD_MS = 250L

        /** Ignore ringtones and notification blips: the library query already filters by `IS_MUSIC`. */
        private const val MIN_TRACK_MS = 10_000L

        /** Enough for a demo picker without holding a huge cursor. */
        private const val MAX_TRACKS = 200
    }
}

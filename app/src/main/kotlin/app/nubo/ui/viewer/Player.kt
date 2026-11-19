package app.nubo.ui.viewer

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.SurfaceHolder
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nubo.NuboApp
import app.nubo.drive.Item
import app.nubo.media.ChunkReader

/**
 * The platform MediaPlayer over a [ChunkReader]: the player asks for byte ranges, the reader fetches and
 * decrypts only those chunks. No local server and no plain file. Asks for audio focus while it plays and
 * pauses when another app takes it. Call [release] when the page goes away. Playback starts at [startAt] ms
 * (the place before a rotation: review 51).
 */
@Stable
internal class Player(app: NuboApp, item: Item, video: Boolean, private val startAt: Int = 0) {
    private val source = ChunkReader(app.content, item).dataSource()
    private val audio = app.getSystemService(AudioManager::class.java)
    private var mp: MediaPlayer? = MediaPlayer()
    private var preparing = false
    private var playWhenReady = false
    private var released = false

    var prepared by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var duration by mutableIntStateOf(0)
        private set
    var position by mutableIntStateOf(startAt)
        private set
    var width by mutableIntStateOf(0)
        private set
    var height by mutableIntStateOf(0)
        private set

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause() }
        .build()

    init {
        mp?.apply {
            setAudioAttributes(attributes)
            setOnPreparedListener {
                preparing = false
                prepared = true
                this@Player.duration = it.duration.coerceAtLeast(0)
                if (startAt in 1 until this@Player.duration) it.seekTo(startAt.toLong(), MediaPlayer.SEEK_CLOSEST)
                // this@Player: a bare start() is MediaPlayer.start() here, with no audio focus and no play state.
                if (playWhenReady) this@Player.start()
            }
            setOnVideoSizeChangedListener { _, w, h ->
                width = w
                height = h
            }
            setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> buffering = true
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> buffering = false
                }
                false
            }
            setOnCompletionListener {
                playing = false
                position = this@Player.duration
                audio.abandonAudioFocusRequest(focus)
            }
            setOnSeekCompleteListener { tick() }
            setOnErrorListener { _, _, _ ->
                preparing = false
                playing = false
                error = "This file cannot be played here."
                audio.abandonAudioFocusRequest(focus)
                true
            }
        }
    }

    /** Starts loading (once); plays when ready if [play]. */
    fun prepare(play: Boolean) {
        val p = mp ?: return
        playWhenReady = play
        if (prepared) {
            if (play) start()
            return
        }
        if (preparing || error != null) return
        preparing = true
        try {
            p.setDataSource(source)
            p.prepareAsync()
        } catch (_: Exception) {
            preparing = false
            error = "This file cannot be played here."
        }
    }

    fun start() {
        val p = mp ?: return
        if (!prepared) return prepare(true)
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (position >= duration && duration > 0) p.seekTo(0)
        p.start()
        playing = true
    }

    fun pause() {
        playWhenReady = false
        val p = mp ?: return
        if (prepared && p.isPlaying) p.pause()
        playing = false
        audio.abandonAudioFocusRequest(focus)
    }

    fun toggle() = if (playing) pause() else start()

    fun seekTo(ms: Int) {
        val p = mp ?: return
        if (!prepared) return
        position = ms.coerceIn(0, duration)
        p.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    fun skip(ms: Int) = seekTo(position + ms)

    fun tick() {
        val p = mp ?: return
        if (prepared && !released) position = p.currentPosition
    }

    fun setDisplay(holder: SurfaceHolder?) {
        if (!released) runCatching { mp?.setDisplay(holder) }
    }

    /**
     * Frees the player. MediaPlayer.release() can wait for a read that waits for the network, so it runs on
     * its own thread, never on the main thread (an ANR on a slow link).
     */
    fun release() {
        if (released) return
        released = true
        audio.abandonAudioFocusRequest(focus)
        val p = mp
        mp = null
        runCatching { source.close() }
        if (p != null) Thread({ runCatching { p.release() } }, "player-release").start()
    }
}

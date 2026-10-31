package io.github.besliky.airplaytv.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import io.github.besliky.airplaytv.BuildConfig
import io.github.besliky.airplaytv.core.NativeBridge
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.lyrics.LyricsTiming
import io.github.besliky.airplaytv.service.ArtworkColors
import io.github.besliky.airplaytv.service.DacpClient.Command
import io.github.besliky.airplaytv.service.NowPlaying
import io.github.besliky.airplaytv.service.NowPlaying.LyricsStatus
import io.github.besliky.airplaytv.service.ReceiverService
import io.github.besliky.airplaytv.service.ReceiverState
import io.github.besliky.airplaytv.service.SleepService
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * What a music sender is playing: cover, title, artist and album, progress with elapsed and
 * remaining time and transport buttons, laid out for the TV. It opens by
 * itself when music starts and closes when the session ends; Back leaves the music playing.
 *
 * - The remote controls the sender: play/pause or OK, next or right, previous or left. Down shows
 *   the lyrics when they are turned on, Up or Back hides them.
 * - Covers slide past each other when the track changes, the cover steps back while paused, and the
 *   backdrop takes a dark tint from the cover.
 * - The player stays still, dims when nobody uses the remote, turns into a small
 *   card wandering over black, and goes black during a long pause (see [Stage]).
 * - Sleep After Music: when AirPlay turned the TV on and nobody touched the remote, the screen
 *   counts down after the music and then puts the TV back to sleep.
 */
class NowPlayingActivity : Activity() {

    /** How much of the screen is lit. */
    private enum class Stage { ACTIVE, DIM, AMBIENT, BLANK, GOODNIGHT }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings

    private lateinit var backdrop: ArtworkBackdrop
    private lateinit var stage: View
    private lateinit var player: View
    private lateinit var playerDetails: View
    private lateinit var cover: View
    private lateinit var coverFront: ImageView
    private lateinit var coverBack: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var albumView: TextView
    private lateinit var elapsedView: TextView
    private lateinit var remainingView: TextView
    private lateinit var formatView: TextView
    private lateinit var progressBar: PillBar
    private lateinit var previousButton: View
    private lateinit var playPauseButton: View
    private lateinit var pauseIcon: View
    private lateinit var playIcon: View
    private lateinit var nextButton: View
    private lateinit var hintView: TextView
    private lateinit var playerStatus: TextView
    private lateinit var lyricsPage: View
    private lateinit var lyricsMetadata: View
    private lateinit var lyricsTransport: View
    private lateinit var lyricsCover: ImageView
    private lateinit var lyricsTitle: TextView
    private lateinit var lyricsArtist: TextView
    private lateinit var lyricsView: LyricsView
    private lateinit var lyricsStatus: TextView
    private lateinit var lyricsElapsed: TextView
    private lateinit var lyricsRemaining: TextView
    private lateinit var lyricsProgress: PillBar
    private lateinit var ambient: View
    private lateinit var ambientCover: ImageView
    private lateinit var ambientTitle: TextView
    private lateinit var ambientArtist: TextView
    private lateinit var goodnightView: TextView

    /** What is on screen; null until the first state arrives. */
    private var shown: NowPlaying? = null
    private var client = ""
    private var lyricsOpen = false
    private var preview = false
    private var coverTransitionRunning = false
    private var queuedCover: Bitmap? = null
    private var hasQueuedCover = false
    private var queuedColors = ArtworkColors.NEUTRAL

    /** The track the cover on screen belongs to, so a cover sent again for it does not slide. */
    private var coverTrack = -1
    private var coverLyricsX = 0f
    private var coverLyricsY = 0f
    private var coverLyricsScale = 2f / 3f
    private val pageMotion by lazy { MotionSpring(0f, omega = 17f) { applyLyricsTransition(it) } }

    /** The player's cover scale: full while playing, [PAUSED_SCALE] while paused. */
    private val playMotion by lazy { MotionSpring(1f, omega = 11f) { applyLyricsTransition(pageMotion.value) } }
    private val buttonMotions = mutableMapOf<View, MotionSpring>()
    private val buttonReleases = mutableMapOf<View, Runnable>()

    private var screenStage = Stage.ACTIVE
    private var lastInteraction = 0L
    private var pausedSince = 0L
    private var holdStageUntil = 0L
    private var goodnightEnd = 0L
    private var lastElapsedSecond = Long.MIN_VALUE
    private var lastRemainingSecond = Long.MIN_VALUE

    private var backdropTop = ArtworkColors.NEUTRAL.backdropTop
    private var backdropBottom = ArtworkColors.NEUTRAL.backdropBottom
    private var accent = ArtworkColors.NEUTRAL.accent
    private var colors = ArtworkColors.NEUTRAL
    private var paletteAnimator: ValueAnimator? = null

    private val stateListener: (ReceiverState.Snapshot) -> Unit = { render(it) }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val stageTick = object : Runnable {
        override fun run() {
            chooseStage()
            handler.postDelayed(this, STAGE_TICK_MS)
        }
    }

    private val ambientTick = object : Runnable {
        override fun run() {
            if (screenStage == Stage.AMBIENT) placeAmbient(animate = true)
            handler.postDelayed(this, AMBIENT_STEP_MS)
        }
    }

    private val goodnightTick = object : Runnable {
        override fun run() {
            val left = goodnightEnd - SystemClock.elapsedRealtime()
            if (left <= 0) {
                goToSleep()
                return
            }
            goodnightView.text = getString(R.string.goodnight_countdown, formatTime(left + 999))
            handler.postDelayed(this, 1000)
        }
    }

    private val hideHint = Runnable { hintView.animate().alpha(0f).setDuration(300).start() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverStandby()
        setContentView(R.layout.activity_now_playing)
        settings = Settings(this)
        bindViews()
        backdrop = ArtworkBackdrop()
        findViewById<View>(R.id.root).background = backdrop
        for (rounded in listOf(cover, lyricsCover, ambientCover)) rounded.clipToOutline = true
        // One cover moves between the two measured layouts; the small view is only its anchor.
        lyricsCover.visibility = View.INVISIBLE
        cover.pivotX = 0f
        cover.pivotY = 0f
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePageGeometry() }
        lyricsCover.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePageGeometry() }
        applyPalette()
        preview = BuildConfig.DEBUG && intent.hasExtra(EXTRA_PREVIEW)
    }

    private fun bindViews() {
        stage = findViewById(R.id.stage)
        player = findViewById(R.id.player)
        playerDetails = findViewById(R.id.playerDetails)
        cover = findViewById(R.id.cover)
        coverFront = findViewById(R.id.coverA)
        coverBack = findViewById(R.id.coverB)
        titleView = findViewById(R.id.title)
        artistView = findViewById(R.id.artist)
        albumView = findViewById(R.id.album)
        elapsedView = findViewById(R.id.elapsed)
        remainingView = findViewById(R.id.remaining)
        formatView = findViewById(R.id.format)
        progressBar = findViewById(R.id.progress)
        previousButton = findViewById(R.id.previous)
        playPauseButton = findViewById(R.id.playPause)
        pauseIcon = findViewById(R.id.pauseIcon)
        playIcon = findViewById(R.id.playIcon)
        nextButton = findViewById(R.id.next)
        hintView = findViewById(R.id.hint)
        playerStatus = findViewById(R.id.playerStatus)
        lyricsPage = findViewById(R.id.lyricsPage)
        lyricsMetadata = findViewById(R.id.lyricsMetadata)
        lyricsTransport = findViewById(R.id.lyricsTransport)
        lyricsCover = findViewById(R.id.lyricsCover)
        lyricsTitle = findViewById(R.id.lyricsTitle)
        lyricsArtist = findViewById(R.id.lyricsArtist)
        lyricsView = findViewById(R.id.lyrics)
        lyricsStatus = findViewById(R.id.lyricsStatus)
        lyricsElapsed = findViewById(R.id.lyricsElapsed)
        lyricsRemaining = findViewById(R.id.lyricsRemaining)
        lyricsProgress = findViewById(R.id.lyricsProgress)
        ambient = findViewById(R.id.ambient)
        ambientCover = findViewById(R.id.ambientCover)
        ambientTitle = findViewById(R.id.ambientTitle)
        ambientArtist = findViewById(R.id.ambientArtist)
        goodnightView = findViewById(R.id.goodnight)
    }

    override fun onStart() {
        super.onStart()
        lastInteraction = SystemClock.elapsedRealtime()
        ReceiverState.observe(stateListener)
        player.alpha = 1f
        lyricsView.setFollowing(lyricsOpen)
        pageMotion.snapTo(if (lyricsOpen) 1f else 0f)
        for (button in listOf(previousButton, playPauseButton, nextButton)) {
            buttonMotions[button]?.snapTo(1f)
            button.scaleX = 1f
            button.scaleY = 1f
        }
        handler.post(tick)
        handler.postDelayed(stageTick, STAGE_TICK_MS)
        handler.postDelayed(ambientTick, AMBIENT_STEP_MS)
        if (preview) intent.getStringExtra(EXTRA_PREVIEW)?.let(::showPreview)
    }

    override fun onStop() {
        ReceiverState.remove(stateListener)
        handler.removeCallbacksAndMessages(null)
        paletteAnimator?.cancel()
        pageMotion.cancel()
        playMotion.cancel()
        buttonMotions.values.forEach { it.cancel() }
        buttonReleases.clear()
        lyricsView.setFollowing(false)
        for (view in listOf(titleView, artistView, albumView, coverFront, coverBack,
            playIcon, pauseIcon, previousButton, playPauseButton, nextButton, player, lyricsPage,
            lyricsTitle, lyricsArtist)) {
            view.animate().cancel()
        }
        shown = null // Re-bind complete state after a cancelled transition when returning.
        coverTransitionRunning = false
        queuedCover = null
        hasQueuedCover = false
        super.onStop()
    }

    // ---------------------------------------------------------------- rendering

    private fun render(s: ReceiverState.Snapshot) {
        val status = when {
            s.status == ReceiverState.Status.NO_NETWORK -> getString(R.string.player_no_network)
            s.clientName != null && !s.nowPlaying.hasTrack -> getString(R.string.player_loading)
            else -> ""
        }
        playerStatus.text = status
        playerStatus.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        if (s.videoActive) {
            finish()
            return
        }
        if (s.clientName == null) {
            musicEnded(s)
            return
        }
        if (screenStage == Stage.GOODNIGHT) setStage(Stage.ACTIVE) // a new session came in time
        client = s.clientName
        showFormat(s.audioCodec)
        val np = s.nowPlaying
        val before = shown
        shown = np
        if (before == null) showAll(np) else showChanges(before, np)
        updateProgress()
    }

    /** How the audio reaches the TV. ALAC is lossless on the way; it says nothing of the song's source file. */
    private fun showFormat(codec: Int) {
        val label = when (codec) {
            NativeBridge.CODEC_ALAC -> getString(R.string.format_alac)
            NativeBridge.CODEC_AAC_LC, NativeBridge.CODEC_AAC_ELD -> getString(R.string.format_aac)
            else -> ""
        }
        if (formatView.tag == label) return
        formatView.tag = label
        if (label.isNotEmpty()) formatView.text = label
        formatView.animate().cancel()
        if (label.isEmpty()) {
            formatView.visibility = View.INVISIBLE
        } else {
            formatView.alpha = 0f
            formatView.visibility = View.VISIBLE
            formatView.animate().alpha(1f).setDuration(TEXT_IN_MS).setInterpolator(CONTENT_EASE).start()
        }
    }

    private fun displayTitle(np: NowPlaying): String =
        if (np.hasTrack) np.title else getString(R.string.now_playing_from, client)

    private fun showAll(np: NowPlaying) {
        showTexts(np)
        showCover(np.artwork, np.colors, animate = false)
        showPlaying(np.playing, animate = false)
        showLyrics(np)
    }

    private fun showChanges(before: NowPlaying, np: NowPlaying) {
        if (np.track != before.track) {
            trackChanged(np)
        } else if (np.title != before.title || np.artist != before.artist || np.album != before.album) {
            showTexts(np)
        }
        if (np.artworkSeq != before.artworkSeq) {
            showCover(np.artwork, np.colors, animate = true)
        }
        if (np.playing != before.playing) {
            showPlaying(np.playing, animate = true)
            if (np.playing && screenStage == Stage.BLANK) setStage(Stage.AMBIENT)
        }
        if (np.volumeSeq != before.volumeSeq && np.volume >= 0f) wake()
        if (np.lyrics !== before.lyrics || np.lyricsStatus != before.lyricsStatus) showLyrics(np)
    }

    private fun showTexts(np: NowPlaying) {
        setText(titleView, displayTitle(np))
        setText(artistView, np.artist)
        setText(albumView, np.album)
        showSmallTexts(np)
    }

    /** The texts of the lyrics header and the ambient card, which change without animation. */
    private fun showSmallTexts(np: NowPlaying) {
        setText(lyricsTitle, displayTitle(np))
        setText(lyricsArtist, np.artist)
        ambientTitle.text = displayTitle(np)
        ambientArtist.text = np.artist
        ambientArtist.visibility = if (np.artist.isBlank()) View.GONE else View.VISIBLE
    }

    private fun setText(view: TextView, text: String) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.tag = text
        view.text = text
        view.visibility = if (text.isBlank()) View.INVISIBLE else View.VISIBLE
    }

    private fun trackChanged(np: NowPlaying) {
        swapText(titleView, displayTitle(np))
        swapText(artistView, np.artist)
        swapText(albumView, np.album)
        swapText(lyricsTitle, displayTitle(np))
        swapText(lyricsArtist, np.artist)
        ambientTitle.text = displayTitle(np)
        ambientArtist.text = np.artist
        ambientArtist.visibility = if (np.artist.isBlank()) View.GONE else View.VISIBLE
        progressBar.setFraction(0f)
        lyricsProgress.setFraction(0f)
        if (screenStage == Stage.DIM) setStage(Stage.ACTIVE)
    }

    /** Replaces metadata without shifting the layout, including rapid track changes. */
    private fun swapText(view: TextView, text: String) {
        if (view.tag == text) return
        view.tag = text
        view.animate().cancel()
        val lift = 6f * resources.displayMetrics.density
        view.animate().setStartDelay(0).alpha(0f).setDuration(TEXT_OUT_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (view.tag != text) return@withEndAction
                view.text = text
                view.visibility = if (text.isBlank()) View.INVISIBLE else View.VISIBLE
                view.translationY = lift
                view.animate().setStartDelay(0).alpha(1f).translationY(0f).setDuration(TEXT_IN_MS)
                    .setInterpolator(CONTENT_EASE).start()
            }
            .start()
    }

    /** Keep an opaque outgoing layer beneath the incoming cover until it fully covers it. */
    private fun showCover(bitmap: Bitmap?, palette: ArtworkColors, animate: Boolean) {
        // Finish the visible dissolve before starting the latest queued artwork, avoiding a flash
        // or an extra full-size snapshot when several covers arrive within one transition.
        if (animate && coverTransitionRunning) {
            queuedCover = bitmap
            hasQueuedCover = true
            queuedColors = palette
            return
        }
        val outgoing = coverFront
        val incoming = coverBack
        coverFront = incoming
        coverBack = outgoing
        incoming.animate().cancel()
        outgoing.animate().cancel()
        // An opaque placeholder also crossfades cleanly when artwork is missing.
        if (bitmap != null) incoming.setImageBitmap(bitmap) else incoming.setImageResource(R.drawable.cover_missing)
        incoming.bringToFront()
        animateColors(palette, animate)
        for (small in listOf(ambientCover)) {
            val next = if (bitmap != null) BitmapDrawable(resources, bitmap) else getDrawable(R.drawable.cover_missing)!!
            val previous = small.drawable.let { if (it is TransitionDrawable) it.getDrawable(1) else it }
            if (animate && previous != null) {
                val transition = TransitionDrawable(arrayOf(previous, next))
                small.setImageDrawable(transition)
                transition.startTransition(COVER_IN_MS.toInt())
            } else {
                small.setImageDrawable(next)
            }
        }
        incoming.translationX = 0f
        outgoing.translationX = 0f
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        outgoing.scaleX = 1f
        outgoing.scaleY = 1f
        if (!animate) {
            coverTrack = shown?.track ?: 0
            coverTransitionRunning = false
            hasQueuedCover = false
            queuedCover = null
            incoming.alpha = 1f
            incoming.translationX = 0f
            incoming.scaleX = 1f
            incoming.scaleY = 1f
            outgoing.alpha = 0f
            outgoing.setImageDrawable(null)
            return
        }
        coverTransitionRunning = true
        // The covers slide past each other inside the cover's rounded frame: the next one comes in from
        // the right, the one before from the left. Senders send the cover again on pause and seek; within
        // a track it only crossfades.
        val track = shown?.track ?: 0
        val newTrack = track != coverTrack
        coverTrack = track
        val shift = if (!newTrack) 0f else cover.width.toFloat() * if ((shown?.direction ?: 1) < 0) -1 else 1
        incoming.alpha = if (newTrack) 1f else 0f
        incoming.translationX = shift
        outgoing.alpha = 1f
        outgoing.animate().translationX(-shift).setDuration(COVER_SLIDE_MS).setInterpolator(SLIDE_EASE).start()
        incoming.animate().alpha(1f).translationX(0f).setDuration(COVER_SLIDE_MS)
            .setInterpolator(SLIDE_EASE).withEndAction {
                outgoing.alpha = 0f
                outgoing.translationX = 0f
                outgoing.setImageDrawable(null)
                coverTransitionRunning = false
                if (hasQueuedCover) {
                    val latest = queuedCover
                    hasQueuedCover = false
                    queuedCover = null
                    showCover(latest, queuedColors, animate = true)
                }
            }.start()
    }

    /** Play and pause cross-fade in place, keeping the artwork anchored. */
    private fun showPlaying(playing: Boolean, animate: Boolean) {
        pausedSince = if (playing) 0L else SystemClock.elapsedRealtime()
        val visible = if (playing) pauseIcon else playIcon
        val hidden = if (playing) playIcon else pauseIcon
        visible.animate().cancel()
        hidden.animate().cancel()
        cover.animate().cancel()
        // As in Apple Music, the cover steps back while paused and comes forward when the music plays.
        val scale = if (playing) 1f else PAUSED_SCALE
        if (animate) playMotion.animateTo(scale) else playMotion.snapTo(scale)
        if (!animate) {
            visible.alpha = 1f
            hidden.alpha = 0f
            visible.scaleX = 1f
            visible.scaleY = 1f
            return
        }
        visible.scaleX = 0.82f
        visible.scaleY = 0.82f
        visible.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        hidden.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f).setDuration(140).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun updateProgress() {
        val np = shown ?: return
        val position = np.positionAt(SystemClock.elapsedRealtime())
        val known = position >= 0
        val fraction = if (known) position.toFloat() / np.durationMs else 0f
        val elapsedSecond = if (known) position / 1000 else -1L
        val remainingSecond = if (known) (np.durationMs - position) / 1000 else -1L
        if (elapsedSecond != lastElapsedSecond) {
            lastElapsedSecond = elapsedSecond
            val elapsed = if (known) formatTime(position) else ""
            elapsedView.text = elapsed
            lyricsElapsed.text = elapsed
        }
        if (remainingSecond != lastRemainingSecond) {
            lastRemainingSecond = remainingSecond
            val remaining = if (known) MINUS + formatTime(np.durationMs - position) else ""
            remainingView.text = remaining
            lyricsRemaining.text = remaining
        }
        val smooth = np.playing && (screenStage == Stage.ACTIVE || screenStage == Stage.DIM)
        if (!lyricsOpen) progressBar.setFraction(fraction, animate = smooth)
        if (lyricsOpen) {
            lyricsProgress.setFraction(fraction, animate = smooth)
            if (known) {
                val synced = np.lyrics?.synced == true
                val lyricPosition = LyricsTiming.position(position, np.durationMs, settings.lyricsAdvanceMs, synced)
                lyricsView.setPosition(lyricPosition + if (synced) LYRICS_LEAD_MS else 0, np.durationMs)
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        } else {
            String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }

    // ---------------------------------------------------------------- colours

    private fun animateColors(next: ArtworkColors, animate: Boolean) {
        colors = next
        val dark = screenStage == Stage.AMBIENT || screenStage == Stage.BLANK || screenStage == Stage.GOODNIGHT
        val top = if (dark) Color.BLACK else next.backdropTop
        val bottom = if (dark) Color.BLACK else next.backdropBottom
        paletteAnimator?.cancel()
        if (!animate) {
            backdropTop = top
            backdropBottom = bottom
            accent = next.accent
            applyPalette()
            return
        }
        val fromTop = backdropTop
        val fromBottom = backdropBottom
        val fromAccent = accent
        val evaluator = ArgbEvaluator()
        paletteAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PALETTE_MS
            interpolator = CONTENT_EASE
            addUpdateListener {
                val p = it.animatedValue as Float
                backdropTop = evaluator.evaluate(p, fromTop, top) as Int
                backdropBottom = evaluator.evaluate(p, fromBottom, bottom) as Int
                accent = evaluator.evaluate(p, fromAccent, next.accent) as Int
                applyPalette()
            }
            start()
        }
    }

    private fun applyPalette() {
        backdrop.setColors(backdropTop, backdropBottom)
        val fill = blend(accent, Color.WHITE, 0.82f)
        val track = blend(backdropBottom, Color.WHITE, 0.18f)
        for (bar in listOf(progressBar, lyricsProgress)) bar.setColors(track, fill)
    }

    private fun blend(from: Int, to: Int, amount: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt(),
    )

    // ---------------------------------------------------------------- lyrics

    private fun showLyrics(np: NowPlaying) {
        lyricsView.setLyrics(np.lyrics)
        val status = when (np.lyricsStatus) {
            LyricsStatus.OFF -> getString(R.string.lyrics_off)
            LyricsStatus.SEARCHING -> getString(R.string.lyrics_searching)
            LyricsStatus.NOT_FOUND -> getString(R.string.lyrics_not_found)
            LyricsStatus.ERROR -> getString(R.string.lyrics_unavailable)
            LyricsStatus.FOUND -> if (np.lyrics?.instrumental == true) getString(R.string.lyrics_instrumental) else ""
        }
        lyricsStatus.text = status
        lyricsStatus.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        val readable = np.lyricsStatus == LyricsStatus.FOUND && np.lyrics?.instrumental != true
        if (readable && !lyricsOpen) {
            showHint(getString(R.string.lyrics_hint), HINT_MS)
        } else if (!readable) {
            hintView.alpha = 0f
        }
    }

    private fun showHint(text: String, durationMs: Long) {
        hintView.text = text
        hintView.animate().cancel()
        hintView.animate().alpha(0.75f).setDuration(250).start()
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, durationMs)
    }

    private fun openLyrics() {
        if (shown?.lyricsStatus == LyricsStatus.OFF) {
            showHint(getString(R.string.lyrics_turn_on), 4000)
            return
        }
        if (lyricsOpen) return
        lyricsOpen = true
        lyricsView.setFollowing(true)
        lyricsPage.visibility = View.VISIBLE
        hintView.alpha = 0f
        updateProgress()
        pageMotion.animateTo(1f)
    }

    private fun closeLyrics() {
        if (!lyricsOpen) return
        lyricsOpen = false
        lyricsView.setFollowing(false)
        updateProgress()
        pageMotion.animateTo(0f)
    }

    /** Geometry comes from layout, not the current transforms, so reversals cannot accumulate drift. */
    private fun updatePageGeometry() {
        if (cover.width == 0 || lyricsCover.width == 0) return
        fun position(view: View, horizontal: Boolean): Float {
            var current = view
            var result = 0f
            while (current !== stage) {
                result += if (horizontal) current.left.toFloat() else current.top.toFloat()
                val parent = current.parent as? View ?: break
                result -= if (horizontal) parent.scrollX else parent.scrollY
                current = parent
            }
            return result
        }
        coverLyricsX = position(lyricsCover, true) - position(cover, true)
        coverLyricsY = position(lyricsCover, false) - position(cover, false)
        coverLyricsScale = lyricsCover.width.toFloat() / cover.width
        applyLyricsTransition(pageMotion.value)
    }

    private fun applyLyricsTransition(value: Float) {
        val p = value.coerceIn(0f, 1f)
        val density = resources.displayMetrics.density
        val pageScale = 1f + (coverLyricsScale - 1f) * p
        // The pause step-back belongs to the player; the small cover beside the lyrics keeps its size.
        val play = 1f - (1f - playMotion.value) * (1f - p)
        val centring = cover.width * pageScale * (1f - play) / 2f
        cover.translationX = coverLyricsX * p + centring
        cover.translationY = coverLyricsY * p + centring
        cover.scaleX = pageScale * play
        cover.scaleY = cover.scaleX
        playerDetails.alpha = (1f - p * 2f).coerceIn(0f, 1f)
        playerDetails.translationY = -8f * density * p
        lyricsPage.visibility = if (p > 0f || lyricsOpen) View.VISIBLE else View.INVISIBLE
        // The text groups trade places in time, avoiding two readable blocks on top of each other.
        val content = ((p - 0.48f) / 0.52f).coerceIn(0f, 1f)
        lyricsMetadata.alpha = content
        // Keep the caption below the moving cover throughout the transition, not just at rest.
        val bottomTravel = cover.height * (1f - coverLyricsScale) - coverLyricsY
        lyricsMetadata.translationY = (1f - p) * bottomTravel
        lyricsView.alpha = content
        lyricsView.translationY = (1f - p) * 16f * density
        lyricsStatus.alpha = content
        lyricsTransport.alpha = p
    }

    // ---------------------------------------------------------------- remote

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (screenStage == Stage.GOODNIGHT) {
            // Someone is here after all: stay awake.
            ReceiverState.update { it.copy(wokeScreen = false) }
            finish()
            return true
        }
        if (ReceiverState.current.wokeScreen) ReceiverState.update { it.copy(wokeScreen = false) }
        val wasDark = screenStage == Stage.AMBIENT || screenStage == Stage.BLANK
        wake()
        if (wasDark && keyCode != KeyEvent.KEYCODE_BACK) return true // the first press only lights the screen up
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.repeatCount == 0 && shown?.lyricsStatus == LyricsStatus.ERROR) {
                    ReceiverService.retryLyrics(this)
                }
                openLyrics()
            }
            KeyEvent.KEYCODE_DPAD_UP -> closeLyrics()
            KeyEvent.KEYCODE_BACK -> if (lyricsOpen) closeLyrics() else return super.onKeyDown(keyCode, event)
            else -> {
                val command = commandFor(keyCode) ?: return super.onKeyDown(keyCode, event)
                if (event.repeatCount == 0) {
                    ReceiverService.sendRemoteCommand(this, command)
                    pulse(
                        when (command) {
                            Command.NEXT -> nextButton
                            Command.PREVIOUS -> previousButton
                            Command.PLAY_PAUSE -> playPauseButton
                        },
                    )
                }
            }
        }
        return true
    }

    private fun commandFor(keyCode: Int): Command? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Command.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> Command.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> Command.PREVIOUS
        else -> null
    }

    /** A short pulse acknowledges a key before the sender has answered. */
    private fun pulse(view: View) {
        val motion = buttonMotions.getOrPut(view) {
            MotionSpring(1f, omega = 26f) { scale -> view.scaleX = scale; view.scaleY = scale }
        }
        buttonReleases.remove(view)?.let { handler.removeCallbacks(it) }
        motion.animateTo(1.08f)
        val release = Runnable { motion.animateTo(1f); buttonReleases.remove(view) }
        buttonReleases[view] = release
        handler.postDelayed(release, 120L)
    }

    // ---------------------------------------------------------------- OLED care

    /** Something the viewer should see happened: back to full brightness. */
    private fun wake() {
        lastInteraction = SystemClock.elapsedRealtime()
        holdStageUntil = 0
        if (screenStage != Stage.ACTIVE && screenStage != Stage.GOODNIGHT) setStage(Stage.ACTIVE)
    }

    private fun chooseStage() {
        val now = SystemClock.elapsedRealtime()
        if (screenStage == Stage.GOODNIGHT || now < holdStageUntil) return
        val idle = now - lastInteraction
        val longPause = pausedSince > 0 && now - pausedSince >= BLANK_AFTER_PAUSE_MS
        setStage(
            when {
                idle >= AMBIENT_AFTER_MS && (longPause || screenStage == Stage.BLANK) -> Stage.BLANK
                idle >= AMBIENT_AFTER_MS -> Stage.AMBIENT
                idle >= DIM_AFTER_MS -> Stage.DIM
                else -> Stage.ACTIVE
            },
        )
    }

    private fun setStage(next: Stage) {
        if (next == screenStage) return
        val fromDark = screenStage != Stage.ACTIVE && screenStage != Stage.DIM
        screenStage = next
        stage.animate().cancel()
        if (next == Stage.BLANK) {
            // black, and the system may let the screen sleep by its own timer
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        when (next) {
            Stage.ACTIVE -> stage.animate().alpha(1f).setDuration(if (fromDark) 900 else 300).start()
            Stage.DIM -> stage.animate().alpha(DIM_ALPHA).setDuration(1500).start()
            Stage.AMBIENT -> stage.animate().alpha(0f).setDuration(1800).start()
            Stage.BLANK -> stage.animate().alpha(0f).setDuration(1200).start()
            Stage.GOODNIGHT -> stage.animate().alpha(0f).setDuration(1500).start()
        }
        if (next == Stage.AMBIENT) showAmbient() else hideAmbient()
        if (next == Stage.GOODNIGHT) {
            goodnightView.visibility = View.VISIBLE
            goodnightView.animate().alpha(0.55f).setDuration(1500).start()
        } else {
            handler.removeCallbacks(goodnightTick)
            goodnightView.animate().alpha(0f).setDuration(300).withEndAction {
                if (screenStage != Stage.GOODNIGHT) goodnightView.visibility = View.GONE
            }.start()
        }
        animateColors(colors, animate = true)
    }

    private fun showAmbient() {
        ambient.visibility = View.VISIBLE
        ambient.animate().cancel()
        ambient.animate().alpha(AMBIENT_ALPHA).setDuration(1800).start()
        placeAmbient(animate = false)
    }

    private fun hideAmbient() {
        ambient.animate().cancel()
        ambient.animate().alpha(0f).setDuration(600).withEndAction {
            if (screenStage != Stage.AMBIENT) ambient.visibility = View.GONE
        }.start()
    }

    /** Moves the ambient card along a slow loop over the whole screen. */
    private fun placeAmbient(animate: Boolean) {
        ambient.post {
            val t = (SystemClock.elapsedRealtime() + if (animate) AMBIENT_STEP_MS else 0L) / 60_000.0
            val margin = 40 * resources.displayMetrics.density
            val root = ambient.parent as View
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            ambient.measure(unspecified, unspecified) // it may not have been laid out since it appeared
            val maxX = (root.width - ambient.measuredWidth - 2 * margin).coerceAtLeast(0f)
            val maxY = (root.height - ambient.measuredHeight - 2 * margin).coerceAtLeast(0f)
            val x = margin + maxX * ((sin(2 * PI * t / AMBIENT_PERIOD_X_MIN) + 1) / 2).toFloat()
            val y = margin + maxY * ((sin(2 * PI * t / AMBIENT_PERIOD_Y_MIN + 1.3) + 1) / 2).toFloat()
            if (animate) {
                ambient.animate().translationX(x).translationY(y).setDuration(AMBIENT_STEP_MS)
                    .setInterpolator(LinearInterpolator()).start()
            } else {
                ambient.translationX = x
                ambient.translationY = y
            }
        }
    }

    // ---------------------------------------------------------------- sleep after music

    private fun musicEnded(s: ReceiverState.Snapshot) {
        if (screenStage == Stage.GOODNIGHT) return
        val minutes = settings.sleepAfterMinutes
        if (s.wokeScreen && minutes > 0 && SleepService.available) startGoodnight(minutes * 60_000L) else finish()
    }

    private fun startGoodnight(durationMs: Long) {
        goodnightEnd = SystemClock.elapsedRealtime() + durationMs
        setStage(Stage.GOODNIGHT)
        handler.removeCallbacks(goodnightTick)
        handler.post(goodnightTick)
    }

    /** Time is up: the TV goes to sleep, unless something else is playing music by now. */
    private fun goToSleep() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (!preview && !audio.isMusicActive) SleepService.sleep()
        finish()
    }

    // ---------------------------------------------------------------- preview (debug builds)

    /** Shows a part of the screen that otherwise needs time or a remote, for the preview tool. */
    private fun showPreview(part: String) {
        val hold = { stage: Stage ->
            holdStageUntil = SystemClock.elapsedRealtime() + PREVIEW_HOLD_MS
            setStage(stage)
        }
        when (part) {
            "lyrics" -> handler.postDelayed({ openLyrics() }, 700)
            "dim" -> hold(Stage.DIM)
            "ambient" -> hold(Stage.AMBIENT)
            "blank" -> hold(Stage.BLANK)
            "goodnight" -> startGoodnight(5 * 60_000L)
        }
    }

    companion object {
        /** Debug builds only: a part of the screen to show at once, see [showPreview]. */
        const val EXTRA_PREVIEW = "preview"

        private const val MINUS = "−"

        private const val TICK_MS = 100L
        private const val STAGE_TICK_MS = 5_000L
        private const val LYRICS_LEAD_MS = 150L
        private const val HINT_MS = 7000L
        private const val PREVIEW_HOLD_MS = 120_000L

        private const val TEXT_OUT_MS = 90L
        private const val TEXT_IN_MS = 280L
        private const val COVER_IN_MS = 480L
        private const val PALETTE_MS = 480L
        private const val COVER_SLIDE_MS = 620L
        private const val PAUSED_SCALE = 0.86f
        private val CONTENT_EASE = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        private val SLIDE_EASE = PathInterpolator(0.32f, 0.72f, 0f, 1f)

        // OLED care: when each stage begins, and how the ambient card wanders
        private const val DIM_AFTER_MS = 90_000L
        private const val AMBIENT_AFTER_MS = 5 * 60_000L
        private const val BLANK_AFTER_PAUSE_MS = 15 * 60_000L
        private const val DIM_ALPHA = 0.45f
        private const val AMBIENT_ALPHA = 0.6f
        private const val AMBIENT_STEP_MS = 20_000L
        private const val AMBIENT_PERIOD_X_MIN = 2.9
        private const val AMBIENT_PERIOD_Y_MIN = 4.1
    }
}

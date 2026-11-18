package com.lanvideo.player

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackActivity : Activity() {
    internal data class Selection(val login: SmbLibrary.Login, val path: String, val title: String)

    companion object {
        internal var pending: Selection? = null
        private const val SKIP_MS = 10_000L
        private const val MAX_SWIPE_MS = 120_000L
        private val SPEEDS = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }

    private class Playback(val player: ExoPlayer, val source: SmbPlaybackSource)

    private class AudioChoice(val group: Tracks.Group, val index: Int) {
        val format: Format get() = group.getTrackFormat(index)
        val supported: Boolean get() = group.isTrackSupported(index)
        val selected: Boolean get() = group.isTrackSelected(index)
    }

    private val cleanup = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var selection: Selection? = null
    private var playback: Playback? = null
    private var ready = false
    private var resumePlaying = true
    private var position = 0L
    private var dragging = false
    private var swipeStart = 0L
    private var swipeTarget: Long? = null
    private val preferences by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var speed = 1f
    private var speedDialog: AlertDialog? = null
    private var audioDialog: AlertDialog? = null
    private lateinit var frame: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var status: TextView
    private lateinit var seekFeedback: TextView
    private lateinit var header: LinearLayout
    private lateinit var speedButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var transport: LinearLayout
    private lateinit var toggle: Icon
    private lateinit var timeline: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var totalTime: TextView
    private lateinit var skipBack: TextView
    private lateinit var skipForward: TextView

    private val hideSeekFeedback = Runnable { seekFeedback.visibility = View.GONE }

    private val hideControls = Runnable {
        if (isPlaying() && !dragging) setControlsVisible(false)
    }
    private val updateTimeline = object : Runnable {
        override fun run() {
            val media = playback?.player ?: return
            if (!ready) return
            if (!dragging) {
                timeline.progress = timelineValue(media.currentPosition)
                timeline.secondaryProgress = timelineValue(media.bufferedPosition)
                elapsed.text = formatTime(media.currentPosition)
            }
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selection = lastNonConfigurationInstance as? Selection ?: pending
        pending = null
        position = state?.getLong("position") ?: 0L
        resumePlaying = state?.getBoolean("playing", true) ?: true
        speed = preferences.getFloat("speed", 1f).takeIf { it in SPEEDS } ?: 1f
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        status = label("", 15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = panel(0xE6192727.toInt(), 16)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        frame.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            marginStart = dp(44)
            marginEnd = dp(44)
        })
        seekFeedback = label("", 21f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = panel(0xEE192727.toInt(), 16)
            visibility = View.GONE
        }
        frame.addView(seekFeedback, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        header = makeHeader()
        frame.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        transport = makeTransport()
        frame.addView(transport, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        frame.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
                header.setPadding(dp(18) + cutout.left, dp(8) + cutout.top, dp(20) + cutout.right, dp(16))
                transport.setPadding(dp(18) + cutout.left, dp(17), dp(22) + cutout.right, dp(14) + cutout.bottom)
            }
            insets
        }
        frame.setOnClickListener {
            if (ready) {
                val show = header.visibility != View.VISIBLE
                setControlsVisible(show)
                if (show) scheduleHide()
            }
        }
        installPlaybackGestures()
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        setContentView(frame)
        hideSystemBars()
        setControlsVisible(true)
        if (selection == null) status.text = "请返回共享目录，重新打开视频。"
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun makeHeader() = row().apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xEE101B1B.toInt(), Color.TRANSPARENT))
        setPadding(dp(18), dp(8), dp(20), dp(16))
        addView(Icon(this@PlaybackActivity, Glyph.BACK, Theme.text).apply {
            background = ripple(0xA62A403C.toInt(), 14)
            contentDescription = "返回共享目录"
            isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label(selection?.title ?: "视频播放", 17f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        audioButton = label("音轨", 14f, Theme.text, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            background = ripple(0xA62A403C.toInt(), 14)
            isEnabled = false
            setOnClickListener { showAudioMenu() }
        }
        addView(audioButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        speedButton = label("", 15f, Theme.accent, bold = true).apply {
            gravity = Gravity.CENTER
            minimumWidth = dp(64)
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            background = ripple(0xA62A403C.toInt(), 14)
            setOnClickListener { showSpeedMenu() }
        }
        updateSpeedButton()
        addView(speedButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
    }

    private fun speedText(value: Float) = "${value.toString().removeSuffix(".0")}×"

    private fun updateSpeedButton() {
        speedButton.text = speedText(speed)
        speedButton.contentDescription = "播放倍速 ${speedText(speed)}"
    }

    private fun showSpeedMenu() {
        main.removeCallbacks(hideControls)
        val options = SPEEDS.map { if (it == 1f) "1× 正常速度" else speedText(it) }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(options, SPEEDS.indexOf(speed)) { choice, index ->
                speed = SPEEDS[index]
                preferences.edit().putFloat("speed", speed).apply()
                playback?.let { opened ->
                    opened.player.setPlaybackSpeed(speed)
                    opened.source.trace.note("播放倍速=${speedText(speed)}")
                }
                updateSpeedButton()
                choice.dismiss()
            }
            .setNegativeButton("关闭", null)
            .create()
        speedDialog = dialog
        dialog.setOnDismissListener {
            speedDialog = null
            scheduleHide()
        }
        dialog.show()
    }

    private fun audioChoices(tracks: Tracks): List<AudioChoice> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
            List(group.length) { index -> AudioChoice(group, index) }
        }

    private fun audioCodec(mime: String?): String = when (mime) {
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/eac3-joc" -> "E-AC-3 JOC"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/true-hd" -> "TrueHD"
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/raw" -> "PCM"
        else -> mime?.substringAfter('/') ?: "未知格式"
    }

    private fun showAudioMenu() {
        val media = playback?.player ?: return
        val choices = audioChoices(media.currentTracks)
        main.removeCallbacks(hideControls)
        val builder = AlertDialog.Builder(this)
            .setTitle("音轨")
            .setNegativeButton("关闭", null)
        if (choices.isEmpty()) {
            builder.setMessage("此视频没有检测到音轨。")
        } else {
            val labels = choices.mapIndexed { index, choice ->
                val format = choice.format
                val name = format.label?.takeIf { it.isNotBlank() } ?: "音轨 ${index + 1}"
                val language = format.language?.takeUnless { it == "und" }?.let {
                    Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault())
                }
                val channels = format.channelCount.takeIf { it > 0 }?.let { "$it 声道" }
                val unsupported = if (choice.supported) null else "当前设备无法解码"
                listOfNotNull(name, language, audioCodec(format.sampleMimeType), channels, unsupported)
                    .joinToString(" · ")
            }
            val adapter = object : ArrayAdapter<String>(this, android.R.layout.select_dialog_singlechoice, labels) {
                override fun areAllItemsEnabled() = choices.all { it.supported }
                override fun isEnabled(position: Int) = choices[position].supported
            }
            builder.setSingleChoiceItems(adapter, choices.indexOfFirst { it.selected }) { dialog, index ->
                if (playback?.player === media) {
                    val choice = choices[index]
                    media.trackSelectionParameters = media.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
                        .build()
                }
                dialog.dismiss()
            }
        }
        val dialog = builder.create()
        audioDialog = dialog
        dialog.setOnDismissListener {
            audioDialog = null
            scheduleHide()
        }
        dialog.show()
    }

    private fun installPlaybackGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                swipeStart = playback?.player?.currentPosition ?: 0L
                return true
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean = frame.performClick()

            override fun onDoubleTap(event: MotionEvent): Boolean {
                skipBy(if (event.x < frame.width / 2f) -SKIP_MS else SKIP_MS)
                return true
            }

            override fun onScroll(
                start: MotionEvent?, event: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                val media = seekablePlayer() ?: return false
                if (start == null || event.pointerCount != 1 || frame.width == 0) return false
                val dx = event.x - start.x
                if (swipeTarget == null && abs(dx) <= abs(event.y - start.y)) return false
                val span = minOf(media.duration, MAX_SWIPE_MS)
                val target = (swipeStart + (dx.toDouble() / frame.width * span).roundToLong())
                    .coerceIn(0L, media.duration)
                swipeTarget = target
                dragging = true
                main.removeCallbacks(hideControls)
                setControlsVisible(true)
                timeline.progress = timelineValue(target)
                elapsed.text = formatTime(target)
                showSeekFeedback("跳转至 ${formatTime(target)}", keepVisible = true)
                return true
            }
        }).apply { setIsLongpressEnabled(false) }

        frame.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> finishSwipe(commit = true)
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finishSwipe(commit = false)
            }
            true
        }
    }

    private fun seekablePlayer(): ExoPlayer? =
        playback?.player?.takeIf { ready && it.isCurrentMediaItemSeekable && it.duration > 0 }

    private fun finishSwipe(commit: Boolean) {
        val target = swipeTarget ?: return
        swipeTarget = null
        dragging = false
        if (commit) {
            seekablePlayer()?.seekTo(target)
            showSeekFeedback("跳转至 ${formatTime(target)}")
        } else {
            hideSeekFeedback.run()
        }
        scheduleHide()
    }

    private fun skipBy(delta: Long) {
        val media = seekablePlayer() ?: return
        val target = (media.currentPosition + delta).coerceIn(0L, media.duration)
        media.seekTo(target)
        val direction = if (delta < 0) "快退" else "快进"
        showSeekFeedback("$direction · ${formatTime(target)}")
        scheduleHide()
    }

    private fun showSeekFeedback(message: String, keepVisible: Boolean = false) {
        main.removeCallbacks(hideSeekFeedback)
        seekFeedback.text = message
        seekFeedback.visibility = View.VISIBLE
        if (!keepVisible) main.postDelayed(hideSeekFeedback, 1_000)
    }

    private fun skipButton(text: String, description: String, delta: Long) =
        label(text, 13f, Theme.text, bold = true).apply {
            gravity = Gravity.CENTER
            background = ripple(0xA62A403C.toInt(), 12)
            contentDescription = description
            setOnClickListener { skipBy(delta) }
        }

    private fun makeTransport() = row().apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, 0xF2101B1B.toInt()))
        setPadding(dp(18), dp(17), dp(22), dp(14))
        toggle = Icon(this@PlaybackActivity, Glyph.PLAY, Theme.ink).apply {
            background = ripple(Theme.accent, 16)
            contentDescription = "播放"
            isFocusable = true
            setOnClickListener {
                if (!ready) return@setOnClickListener
                playback?.player?.let { media ->
                    val ended = media.playbackState == Player.STATE_ENDED
                    if (ended) media.seekTo(0)
                    if (media.playWhenReady && !ended) {
                        media.pause()
                    } else {
                        media.play()
                    }
                    syncPlayButton()
                    scheduleHide()
                }
            }
        }
        addView(toggle, LinearLayout.LayoutParams(dp(50), dp(50)))
        skipBack = skipButton("−10", "快退 10 秒", -SKIP_MS)
        skipForward = skipButton("+10", "快进 10 秒", SKIP_MS)
        for (button in listOf(skipBack, skipForward)) {
            addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(6) })
        }
        elapsed = label("0:00", 13f).apply { gravity = Gravity.END }
        addView(elapsed, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(13) })

        timeline = SeekBar(this@PlaybackActivity).apply {
            contentDescription = "播放进度"
            progressTintList = ColorStateList.valueOf(Theme.accent)
            thumbTintList = ColorStateList.valueOf(Theme.accent)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF546461.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser || !ready) return
                    elapsed.text = formatTime(progress.toLong())
                    if (!dragging) playback?.player?.seekTo(progress.toLong())
                }

                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                    main.removeCallbacks(hideControls)
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    if (ready) playback?.player?.seekTo(bar.progress.toLong())
                    scheduleHide()
                }
            })
        }
        addView(timeline, LinearLayout.LayoutParams(0, dp(48), 1f))
        totalTime = label("0:00", 13f, Theme.muted)
        addView(totalTime)
    }

    override fun onRetainNonConfigurationInstance(): Any? = selection

    override fun onStart() {
        super.onStart()
        open()
    }

    override fun onStop() {
        speedDialog?.dismiss()
        audioDialog?.dismiss()
        closePlayback()
        super.onStop()
    }

    override fun onDestroy() {
        cleanup.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val media = playback?.player
        outState.putLong("position", media?.currentPosition ?: position)
        outState.putBoolean("playing", media?.playWhenReady ?: resumePlaying)
        super.onSaveInstanceState(outState)
    }

    private fun open() {
        val item = selection ?: return
        if (playback != null) return
        status.text = "正在打开视频…"
        status.visibility = View.VISIBLE
        setControlsVisible(true)

        val source = SmbPlaybackSource(item.login, item.path)
        try {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
                .build()
            val renderers = DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            val media = ExoPlayer.Builder(this, renderers)
                .setLoadControl(loadControl)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .build()
            playback = Playback(media, source)
            media.setVideoSurfaceView(video)
            media.addListener(playbackListener(media, source))
            media.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long
                ) {
                    source.trace.note("音频解码器=$decoderName · 初始化=$initializationDurationMs ms")
                }

                override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                    source.trace.failure("音频输出", audioSinkError)
                }

                override fun onAudioPositionAdvancing(
                    eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long
                ) {
                    source.trace.note("音频开始输出")
                }

                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long
                ) {
                    source.trace.note("视频解码器=$decoderName · 初始化=$initializationDurationMs ms")
                }

                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long
                ) {
                    source.trace.note("视频掉帧 · 帧数=$droppedFrames · 统计区间=$elapsedMs ms")
                }
            })
            val itemUri = MediaItem.fromUri(source.mediaUri)
            media.setMediaSource(ProgressiveMediaSource.Factory(source).createMediaSource(itemUri), position)
            media.setPlaybackSpeed(speed)
            source.trace.note("播放倍速=${speedText(speed)}")
            media.playWhenReady = resumePlaying
            media.prepare()
        } catch (error: Exception) {
            source.trace.failure("启动 ExoPlayer", error)
            if (playback == null) cleanup.execute(source::close)
            showError("无法开始播放该视频。")
        }
    }

    private fun playbackListener(media: ExoPlayer, source: SmbPlaybackSource) = object : Player.Listener {
        private var buffering = false
        private var warnedUnsupportedAudio = false

        override fun onTracksChanged(tracks: Tracks) {
            if (playback?.player !== media) return
            val choices = audioChoices(tracks)
            if (choices.isEmpty()) {
                source.trace.note("没有识别到音轨")
                return
            }
            choices.forEachIndexed { index, choice ->
                val format = choice.format
                source.trace.note(
                    "音轨 ${index + 1} · 格式=${format.sampleMimeType ?: "未知"} · " +
                        "声道=${format.channelCount} · 可解码=${choice.supported} · 已选中=${choice.selected}"
                )
            }
            if (!warnedUnsupportedAudio && choices.none { it.supported }) {
                warnedUnsupportedAudio = true
                Toast.makeText(this@PlaybackActivity, "当前设备无法解码此视频的音轨，点「音轨」查看格式。", Toast.LENGTH_LONG).show()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playback?.player !== media) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    buffering = true
                    source.trace.note("播放器开始缓冲 · 进度=${formatTime(media.currentPosition)}")
                    if (ready && media.playWhenReady) {
                        status.text = "正在缓冲…"
                        status.visibility = View.VISIBLE
                    }
                }
                Player.STATE_READY -> {
                    if (buffering) {
                        source.trace.note("播放器缓冲结束 · 进度=${formatTime(media.currentPosition)}")
                        buffering = false
                    }
                    if (!ready) {
                        ready = true
                        audioButton.isEnabled = true
                        setControlsVisible(true)
                        main.removeCallbacks(updateTimeline)
                        main.post(updateTimeline)
                    }
                    status.visibility = View.GONE
                    updateDuration(media)
                }
                Player.STATE_ENDED -> {
                    status.visibility = View.GONE
                    setControlsVisible(true)
                }
            }
            syncPlayButton()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (playback?.player === media && header.visibility == View.VISIBLE) scheduleHide()
        }

        override fun onTimelineChanged(value: Timeline, reason: Int) {
            if (playback?.player === media && ready) updateDuration(media)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = fitVideo()

        override fun onRenderedFirstFrame() {
            source.trace.note("视频开始渲染 · 分辨率=${media.videoSize.width}×${media.videoSize.height}")
        }

        override fun onPlayerError(error: PlaybackException) {
            source.trace.failure("ExoPlayer 播放（${error.errorCodeName}）", error)
            main.post {
                if (playback?.player === media) showError("播放失败。可在共享目录查看 SMB 连接日志。")
            }
        }
    }

    private fun showError(message: String) {
        closePlayback()
        setControlsVisible(true)
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun updateDuration(media: ExoPlayer) {
        timeline.max = timelineValue(media.duration)
        val canSeek = media.isCurrentMediaItemSeekable
        timeline.isEnabled = canSeek
        skipBack.isEnabled = canSeek
        skipForward.isEnabled = canSeek
        totalTime.text = formatTime(media.duration)
    }

    private fun closePlayback() {
        main.removeCallbacks(hideControls)
        main.removeCallbacks(updateTimeline)
        main.removeCallbacks(hideSeekFeedback)
        hideSeekFeedback.run()
        swipeTarget = null
        dragging = false
        val opened = playback ?: return
        val media = opened.player
        position = media.currentPosition
        resumePlaying = media.playWhenReady
        ready = false
        audioButton.isEnabled = false
        playback = null
        media.videoDecoderCounters?.let { counters ->
            counters.ensureUpdated()
            opened.source.trace.note("视频解码统计 · 已渲染=${counters.renderedOutputBufferCount} 帧 · " +
                "掉帧=${counters.droppedBufferCount} 帧")
        }
        try {
            media.release()
        } finally {
            cleanup.execute(opened.source::close)
        }
    }

    private fun fitVideo() {
        val size = playback?.player?.videoSize ?: return
        if (minOf(size.width, size.height, frame.width, frame.height) <= 0) return
        val pixelRatio = size.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val aspect = size.width.toDouble() * pixelRatio / size.height
        val width = minOf(frame.width, (frame.height * aspect).roundToInt()).coerceAtLeast(1)
        val height = minOf(frame.height, (width / aspect).roundToInt()).coerceAtLeast(1)
        if (video.layoutParams.width != width || video.layoutParams.height != height) {
            video.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    private fun timelineValue(milliseconds: Long) =
        milliseconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    private fun isPlaying() = ready && playback?.player?.isPlaying == true

    private fun syncPlayButton() {
        val media = playback?.player
        val playing = media != null && media.playWhenReady && media.playbackState != Player.STATE_ENDED
        toggle.glyph = if (playing) Glyph.PAUSE else Glyph.PLAY
        toggle.contentDescription = if (playing) "暂停" else "播放"
        toggle.invalidate()
    }

    private fun setControlsVisible(visible: Boolean) {
        header.visibility = if (visible) View.VISIBLE else View.GONE
        transport.visibility = if (visible && ready) View.VISIBLE else View.GONE
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideControls)
        if (isPlaying() && speedDialog?.isShowing != true && audioDialog?.isShowing != true) {
            main.postDelayed(hideControls, 3_500)
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) {
            String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        } else {
            String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
    }
}

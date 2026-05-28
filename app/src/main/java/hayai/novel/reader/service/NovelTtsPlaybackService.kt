package hayai.novel.reader.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.notification.Notifications
import hayai.novel.reader.tts.NovelTtsController
import hayai.novel.reader.tts.NovelTtsNotification
import uy.kohesive.injekt.injectLazy
import yokai.domain.ui.settings.ReaderPreferences

/**
 * Foreground service that owns the novel-reader TTS engine + state machine
 * ([NovelTtsController]). Lifecycle:
 *
 *  - **Foreground**: started via [ContextCompat.startForegroundService] so the OS keeps
 *    the process alive when the user backgrounds the reader. Posts the playback
 *    notification with media-playback foreground type.
 *  - **Bound**: clients (the [eu.kanade.tachiyomi.ui.reader.ReaderActivity]) bind via
 *    [LocalBinder] to access [controller] for direct command dispatch — replaces the
 *    old broadcast-based control bus.
 *
 * Phase 1 keeps the existing companion API ([start] / [syncState] / [stop]) and the
 * legacy broadcast actions so the activity's old call sites keep compiling. Phase 5
 * removes those once the activity-side bind path takes over.
 */
class NovelTtsPlaybackService : Service() {

    private val preferences: ReaderPreferences by injectLazy()

    /** Lazily initialised so the controller — and therefore TextToSpeech — only spins up
     * once the OS actually starts the service. */
    val controller: NovelTtsController by lazy {
        NovelTtsController(applicationContext, preferences)
    }

    private val binder = LocalBinder()

    private var isPaused: Boolean = false
    private var progressPercent: Int = 0
    private var novelTitle: String = "TTS playback"
    private var chapterTitle: String = ""
    private var mangaId: Long = -1L
    private var chapterId: Long = -1L

    inner class LocalBinder : Binder() {
        val service: NovelTtsPlaybackService get() = this@NovelTtsPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        Logger.d { "NovelTtsPlaybackService: onCreate" }
        // Subscribe to the controller's state so the foreground notification mirrors what
        // the engine is actually doing — no polling, no broadcast bus.
        controller.setStateListener(
            hayai.novel.reader.tts.NovelTtsController.StateListener {
                state, novelTitle, chapterTitle, mangaId, chapterId ->
                this.isPaused = state is hayai.novel.reader.tts.TtsState.Paused
                this.progressPercent = computeProgressPercent(state)
                this.novelTitle = novelTitle.ifBlank { "TTS playback" }
                this.chapterTitle = chapterTitle
                this.mangaId = mangaId
                this.chapterId = chapterId
                if (state is hayai.novel.reader.tts.TtsState.Idle ||
                    state is hayai.novel.reader.tts.TtsState.Error
                ) {
                    // Drop the foreground status when playback ends so the notification
                    // doesn't sit there saying "Paused" with no underlying engine.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    startForegroundWithNotification()
                }
            },
        )
    }

    private fun computeProgressPercent(state: hayai.novel.reader.tts.TtsState): Int = when (state) {
        is hayai.novel.reader.tts.TtsState.Speaking ->
            if (state.totalParagraphs > 0) ((state.paragraphIndex + 1) * 100 / state.totalParagraphs).coerceIn(0, 100) else 0
        is hayai.novel.reader.tts.TtsState.Paused ->
            if (state.totalParagraphs > 0) ((state.paragraphIndex + 1) * 100 / state.totalParagraphs).coerceIn(0, 100) else 0
        else -> 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                controller.dispatch(hayai.novel.reader.tts.TtsCommand.Stop)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            // Triggered by the notification's Pause/Resume action button. Goes straight
            // to the controller — no broadcast bus, no activity round-trip.
            ACTION_TOGGLE_PAUSE -> controller.toggle()

            ACTION_STOP_PLAYBACK -> {
                controller.dispatch(hayai.novel.reader.tts.TtsCommand.Stop)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            // Legacy SYNC payload from the activity's old polling loop. Kept as a no-op
            // since pre-existing callers may still hit it during a partial upgrade;
            // the state listener wired from `onCreate` is the canonical update path.
            ACTION_SYNC -> Unit
        }

        startForegroundWithNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        Logger.d { "NovelTtsPlaybackService: onDestroy" }
        controller.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundWithNotification() {
        val notification = NovelTtsNotification.build(
            context = this,
            sessionToken = controller.mediaSessionToken(),
            isPaused = isPaused,
            progressPercent = progressPercent,
            novelTitle = novelTitle,
            chapterTitle = chapterTitle,
            mangaId = mangaId,
            chapterId = chapterId,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_TTS_PLAYBACK,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(Notifications.ID_TTS_PLAYBACK, notification)
        }
    }

    private fun sendControlBroadcast(command: String) {
        sendBroadcast(
            Intent(ACTION_CONTROL).apply {
                setPackage(packageName)
                putExtra(EXTRA_COMMAND, command)
            },
        )
    }

    companion object {
        // Phase 1: these stay public so ReaderActivity's old control receiver keeps
        // compiling. They'll be removed in Phase 5 when the activity binds directly.
        const val ACTION_CONTROL =
            "hayai.novel.reader.service.NovelTtsPlaybackService.CONTROL"

        const val EXTRA_COMMAND = "extra_command"
        const val COMMAND_TOGGLE_PAUSE = "toggle_pause"
        const val COMMAND_STOP = "stop"

        const val ACTION_TOGGLE_PAUSE =
            "hayai.novel.reader.service.NovelTtsPlaybackService.TOGGLE_PAUSE"
        const val ACTION_STOP_PLAYBACK =
            "hayai.novel.reader.service.NovelTtsPlaybackService.STOP_PLAYBACK"

        private const val ACTION_SYNC =
            "hayai.novel.reader.service.NovelTtsPlaybackService.SYNC"
        private const val ACTION_STOP_SERVICE =
            "hayai.novel.reader.service.NovelTtsPlaybackService.STOP_SERVICE"

        private const val EXTRA_IS_PAUSED = "extra_is_paused"
        private const val EXTRA_PROGRESS_PERCENT = "extra_progress_percent"
        private const val EXTRA_NOVEL_TITLE = "extra_novel_title"
        private const val EXTRA_CHAPTER_TITLE = "extra_chapter_title"
        private const val EXTRA_MANGA_ID = "extra_manga_id"
        private const val EXTRA_CHAPTER_ID = "extra_chapter_id"

        fun start(context: Context) {
            syncState(
                context = context,
                isPaused = false,
                progressPercent = 0,
                novelTitle = "TTS playback",
                chapterTitle = "",
                mangaId = -1L,
                chapterId = -1L,
            )
        }

        fun syncState(
            context: Context,
            isPaused: Boolean,
            progressPercent: Int,
            novelTitle: String,
            chapterTitle: String,
            mangaId: Long,
            chapterId: Long,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NovelTtsPlaybackService::class.java)
                    .setAction(ACTION_SYNC)
                    .putExtra(EXTRA_IS_PAUSED, isPaused)
                    .putExtra(EXTRA_PROGRESS_PERCENT, progressPercent.coerceIn(0, 100))
                    .putExtra(EXTRA_NOVEL_TITLE, novelTitle)
                    .putExtra(EXTRA_CHAPTER_TITLE, chapterTitle)
                    .putExtra(EXTRA_MANGA_ID, mangaId)
                    .putExtra(EXTRA_CHAPTER_ID, chapterId),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NovelTtsPlaybackService::class.java)
                    .setAction(ACTION_STOP_SERVICE),
            )
        }
    }
}

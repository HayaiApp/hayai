package dev.ahmedmohamed.hayai.novel.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference
import java.util.Locale

class NovelTtsPlaybackService : Service(), TextToSpeech.OnInitListener {
    inner class LocalBinder : Binder() { val service: NovelTtsPlaybackService get() = this@NovelTtsPlaybackService }

    private val binder = LocalBinder()
    private lateinit var engine: TextToSpeech
    private var callbacks = WeakReference<Callbacks>(null)
    private var initialized = false
    private var playWhenReady = false
    private var chunks: List<TtsChunk> = emptyList()
    private var chunkIndex = 0
    private var paused = false
    private var playing = false
    private var allowBackground = false
    private var speed = 1f
    private var pitch = 1f
    private var voiceName = ""
    private var generation = 0
    private var activeUtterance: String? = null
    private var mangaId = -1L
    private var chapterId = -1L
    private var novelTitle = ""
    private var chapterTitle = ""
    private var chunksChapterId = -1L

    val isPlaying: Boolean get() = initialized && playing && !paused
    val hasActivePlayback: Boolean get() = chunks.isNotEmpty() && (playing || paused)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = TextToSpeech(applicationContext, this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (chunks.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_PREVIOUS -> previousParagraph()
            ACTION_NEXT -> nextParagraph()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        initialized = status == TextToSpeech.SUCCESS
        if (!initialized) return reportError(getString(R.string.hayai_novel_reader_tts_unavailable))
        engine.language = Locale.getDefault()
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == activeUtterance) callbacks.get()?.onParagraphChanged(chunks.getOrNull(chunkIndex)?.paragraphIndex ?: 0)
                }

                override fun onDone(utteranceId: String?) { if (utteranceId == activeUtterance) advance() }
                @Deprecated("Deprecated by Android") override fun onError(utteranceId: String?) { if (utteranceId == activeUtterance) reportError(getString(R.string.hayai_novel_reader_tts_passage_error)) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == activeUtterance) reportError(getString(R.string.hayai_novel_reader_tts_error_code, errorCode)) }
            },
        )
        applyConfiguration()
        if (playWhenReady) { playWhenReady = false; play() }
    }

    fun attach(callbacks: Callbacks) { this.callbacks = WeakReference(callbacks); callbacks.onPlaybackChanged(isPlaying) }
    fun detach(callbacks: Callbacks) { if (this.callbacks.get() === callbacks) this.callbacks.clear() }

    fun configure(speed: Float, pitch: Float, voiceName: String, allowBackground: Boolean) {
        this.speed = speed.coerceIn(0.5f, 6f)
        this.pitch = pitch.coerceIn(0.5f, 6f)
        this.voiceName = voiceName
        this.allowBackground = allowBackground
        if (initialized) applyConfiguration()
    }

    fun setChapter(mangaId: Long, chapterId: Long, novelTitle: String, chapterTitle: String) {
        this.mangaId = mangaId
        this.chapterId = chapterId
        this.novelTitle = novelTitle
        this.chapterTitle = chapterTitle
    }

    fun setParagraphs(paragraphs: List<String>, startParagraph: Int) {
        val replacement = paragraphs.flatMapIndexed { index, text -> TtsTextUtils.splitTextForTts(text, TextToSpeech.getMaxSpeechInputLength() - 100).map { TtsChunk(index, it) } }
        if (chunksChapterId == chapterId && replacement == chunks && chunks.isNotEmpty()) return
        stopEngine(true)
        chunks = replacement
        chunksChapterId = chapterId
        chunkIndex = chunks.indexOfFirst { it.paragraphIndex >= startParagraph }.takeIf { it >= 0 } ?: 0
    }

    fun toggle() { if (isPlaying) pause() else play() }

    fun play() {
        if (!initialized) { playWhenReady = true; return }
        if (chunks.isEmpty()) return reportError(getString(R.string.hayai_novel_reader_tts_no_text))
        paused = false
        playing = true
        if (allowBackground) {
            startService(Intent(this, NovelTtsPlaybackService::class.java))
            startForeground(NOTIFICATION_ID, notification())
        }
        speakCurrent()
        callbacks.get()?.onPlaybackChanged(true)
    }

    fun pause() {
        invalidate()
        engine.stop()
        paused = true
        playing = false
        callbacks.get()?.onPlaybackChanged(false)
        if (allowBackground) { removeForeground(); stopSelf() }
    }

    fun nextParagraph() = seekParagraph(1)
    fun previousParagraph() = seekParagraph(-1)

    fun stopPlayback() {
        playWhenReady = false
        paused = false
        playing = false
        stopEngine(true)
        callbacks.get()?.onPlaybackChanged(false)
        callbacks.get()?.onHighlightCleared()
        stopSelf()
    }

    override fun onDestroy() {
        stopPlayback()
        engine.shutdown()
        super.onDestroy()
    }

    private fun seekParagraph(delta: Int) {
        val current = chunks.getOrNull(chunkIndex)?.paragraphIndex ?: return
        val target = (current + delta).coerceIn(0, chunks.last().paragraphIndex)
        chunkIndex = chunks.indexOfFirst { it.paragraphIndex == target }.takeIf { it >= 0 } ?: chunkIndex
        if (isPlaying) speakCurrent() else callbacks.get()?.onParagraphChanged(target)
    }

    private fun advance() {
        if (paused) return
        chunkIndex += 1
        if (chunkIndex < chunks.size) speakCurrent() else {
            playing = false
            stopEngine(true)
            callbacks.get()?.onPlaybackChanged(false)
            callbacks.get()?.onHighlightCleared()
            callbacks.get()?.onChapterCompleted()
        }
    }

    private fun speakCurrent() {
        val chunk = chunks.getOrNull(chunkIndex) ?: return
        generation += 1
        activeUtterance = "hayai-$generation-$chunkIndex"
        engine.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, Bundle(), activeUtterance)
        if (allowBackground) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    private fun stopEngine(clearNotification: Boolean) {
        invalidate()
        if (::engine.isInitialized) engine.stop()
        if (clearNotification) removeForeground()
    }

    private fun invalidate() { generation += 1; activeUtterance = null }

    @Suppress("DEPRECATION")
    private fun removeForeground() = stopForeground(true)

    private fun applyConfiguration() {
        engine.setSpeechRate(speed)
        engine.setPitch(pitch)
        if (voiceName.isNotBlank()) engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
    }

    private fun reportError(message: String) {
        playing = false
        stopEngine(true)
        callbacks.get()?.onPlaybackChanged(false)
        callbacks.get()?.onHighlightCleared()
        callbacks.get()?.onError(message)
    }

    private fun notification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play_arrow_24dp)
            .setContentTitle(novelTitle.ifBlank { getString(R.string.hayai_novel_reader_tts_title_fallback) })
            .setContentText(
                buildString {
                    val state = getString(if (isPlaying) R.string.reading else R.string.pause)
                    val paragraph = chunks.getOrNull(chunkIndex)?.paragraphIndex?.plus(1) ?: 1
                    append(if (chapterTitle.isBlank()) getString(R.string.hayai_novel_reader_tts_status, state, paragraph) else getString(R.string.hayai_novel_reader_tts_status_chapter, state, chapterTitle, paragraph))
                },
            )
            .setSubText(getString(R.string.hayai_novel_reader_tts_subtext, chunkIndex + 1, chunks.size.coerceAtLeast(1)))
            .setContentIntent(contentIntent())
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_skip_previous_24, getString(R.string.previous), serviceAction(ACTION_PREVIOUS, 1))
            .addAction(if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp, getString(if (isPlaying) R.string.pause else R.string.hayai_novel_reader_tts_play), serviceAction(ACTION_TOGGLE, 2))
            .addAction(R.drawable.ic_skip_next_24, getString(R.string.next), serviceAction(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_close_24dp, getString(R.string.stop), serviceAction(ACTION_STOP, 4))
            .build()

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(this, requestCode, Intent(this, NovelTtsPlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun contentIntent(): PendingIntent? {
        if (mangaId < 0 || chapterId < 0) return null
        val database = Injekt.get<DatabaseHelper>()
        val manga = database.getManga(mangaId).executeAsBlocking() ?: return null
        val chapter = database.getChapter(chapterId).executeAsBlocking() ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            ReaderLauncher.newIntent(this, manga, chapter).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.hayai_novel_reader_tts_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.hayai_novel_reader_tts_channel_description)
                },
            )
        }
    }

    private data class TtsChunk(val paragraphIndex: Int, val text: String)

    interface Callbacks {
        fun onParagraphChanged(index: Int)
        fun onHighlightCleared()
        fun onPlaybackChanged(playing: Boolean)
        fun onChapterCompleted()
        fun onError(message: String)
    }

    private companion object {
        const val CHANNEL_ID = "hayai_novel_tts"
        const val NOTIFICATION_ID = 7104
        const val ACTION_TOGGLE = "dev.ahmedmohamed.hayai.novel.tts.TOGGLE"
        const val ACTION_PREVIOUS = "dev.ahmedmohamed.hayai.novel.tts.PREVIOUS"
        const val ACTION_NEXT = "dev.ahmedmohamed.hayai.novel.tts.NEXT"
        const val ACTION_STOP = "dev.ahmedmohamed.hayai.novel.tts.STOP"
    }
}

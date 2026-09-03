package dev.ahmedmohamed.hayai.novel.reader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

internal class NovelTtsController(
    private val context: Context,
    private val callbacks: Callbacks,
) : NovelTtsPlaybackService.Callbacks {
    private var service: NovelTtsPlaybackService? = null
    private val pending = ArrayDeque<NovelTtsPlaybackService.() -> Unit>()
    private var destroyed = false
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? NovelTtsPlaybackService.LocalBinder)?.service?.also { it.attach(this@NovelTtsController) }
                service?.let { connected -> while (pending.isNotEmpty()) pending.removeFirst().invoke(connected) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                if (!destroyed) callbacks.onPlaybackChanged(false)
            }
        }

    init {
        context.bindService(Intent(context, NovelTtsPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    val isPlaying: Boolean get() = service?.isPlaying == true
    val hasActivePlayback: Boolean get() = service?.hasActivePlayback == true
    val currentParagraphIndex: Int get() = service?.currentParagraphIndex ?: 0

    fun configure(speed: Float, pitch: Float, voiceName: String, languageTag: String, allowBackground: Boolean = false) =
        whenReady { configure(speed, pitch, voiceName, languageTag, allowBackground) }
    fun setChapter(mangaId: Long, chapterId: Long, novelTitle: String, chapterTitle: String) =
        whenReady { setChapter(mangaId, chapterId, novelTitle, chapterTitle) }
    fun setParagraphs(paragraphs: List<String>, startParagraph: Int = 0) = whenReady { setParagraphs(paragraphs, startParagraph) }
    fun replaceParagraphs(paragraphs: List<String>, startParagraph: Int = currentParagraphIndex) =
        whenReady { replaceParagraphs(paragraphs, startParagraph) }
    fun toggle() = whenReady { toggle() }
    fun play() = whenReady { play() }
    fun pause() = service?.pause() ?: Unit
    fun nextParagraph() = service?.nextParagraph() ?: Unit
    fun previousParagraph() = service?.previousParagraph() ?: Unit
    fun stop() = service?.stopPlayback() ?: Unit

    fun destroy() {
        destroyed = true
        service?.detach(this)
        runCatching { context.unbindService(connection) }
        service = null
        pending.clear()
    }

    private fun whenReady(action: NovelTtsPlaybackService.() -> Unit) {
        service?.action() ?: pending.addLast(action)
    }

    override fun onParagraphChanged(index: Int) = callbacks.runOnUiThread { callbacks.onParagraphChanged(index) }
    override fun onHighlightCleared() = callbacks.runOnUiThread(callbacks::onHighlightCleared)
    override fun onPlaybackChanged(playing: Boolean) = callbacks.runOnUiThread { callbacks.onPlaybackChanged(playing) }
    override fun onChapterCompleted() = callbacks.runOnUiThread(callbacks::onChapterCompleted)
    override fun onError(message: String) = callbacks.runOnUiThread { callbacks.onError(message) }

    interface Callbacks {
        fun onParagraphChanged(index: Int)
        fun onHighlightCleared()
        fun onPlaybackChanged(playing: Boolean)
        fun onChapterCompleted()
        fun onError(message: String)
        fun runOnUiThread(action: () -> Unit)
    }
}

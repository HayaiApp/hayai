package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

internal class NovelTtsController(
    context: Context,
    private val callbacks: Callbacks,
) : TextToSpeech.OnInitListener {
    private val textToSpeech = TextToSpeech(context.applicationContext, this)
    private var initialized = false
    private var pendingPlay = false
    private var chunks: List<TtsChunk> = emptyList()
    private var chunkIndex = 0
    private var paused = false
    private var speed = 1f
    private var pitch = 1f
    private var preferredVoice = ""
    private var playbackGeneration = 0
    private var activeUtteranceId: String? = null

    val isPlaying: Boolean
        get() = initialized && textToSpeech.isSpeaking && !paused

    override fun onInit(status: Int) {
        initialized = status == TextToSpeech.SUCCESS
        if (!initialized) {
            callbacks.onError("Text-to-speech is unavailable on this device.")
            return
        }
        textToSpeech.language = Locale.getDefault()
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != activeUtteranceId) return
                    callbacks.runOnUiThread { callbacks.onParagraphChanged(chunks.getOrNull(chunkIndex)?.paragraphIndex ?: 0) }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != activeUtteranceId) return
                    callbacks.runOnUiThread { advance() }
                }

                @Deprecated("Deprecated by Android")
                override fun onError(utteranceId: String?) {
                    if (utteranceId != activeUtteranceId) return
                    callbacks.runOnUiThread { failPlayback("Text-to-speech could not read this passage.") }
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) {
                    if (utteranceId != activeUtteranceId) return
                    callbacks.runOnUiThread { failPlayback("Text-to-speech error $errorCode.") }
                }
            },
        )
        applySettings()
        if (pendingPlay) {
            pendingPlay = false
            play()
        }
    }

    fun configure(
        speed: Float,
        pitch: Float,
        voiceName: String,
    ) {
        this.speed = speed.coerceIn(0.1f, 3f)
        this.pitch = pitch.coerceIn(0.1f, 2f)
        preferredVoice = voiceName
        if (initialized) applySettings()
    }

    fun setParagraphs(
        paragraphs: List<String>,
        startParagraph: Int = 0,
    ) {
        stop()
        chunks =
            paragraphs.flatMapIndexed { paragraphIndex, paragraph ->
                TtsTextUtils.splitTextForTts(paragraph, TextToSpeech.getMaxSpeechInputLength() - 100).map {
                    TtsChunk(paragraphIndex, it)
                }
            }
        chunkIndex = chunks.indexOfFirst { it.paragraphIndex >= startParagraph }.takeIf { it >= 0 } ?: 0
    }

    fun toggle() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        if (!initialized) {
            pendingPlay = true
            return
        }
        if (chunks.isEmpty()) {
            callbacks.onError("No readable text was found in this chapter.")
            return
        }
        paused = false
        speakCurrent()
        callbacks.onPlaybackChanged(true)
    }

    fun pause() {
        invalidatePlayback()
        textToSpeech.stop()
        paused = true
        callbacks.onPlaybackChanged(false)
    }

    fun nextParagraph() = seekParagraph(1)

    fun previousParagraph() = seekParagraph(-1)

    fun stop() {
        pendingPlay = false
        paused = false
        invalidatePlayback()
        textToSpeech.stop()
        callbacks.onPlaybackChanged(false)
        callbacks.onHighlightCleared()
    }

    fun destroy() {
        stop()
        textToSpeech.shutdown()
    }

    private fun seekParagraph(delta: Int) {
        if (chunks.isEmpty()) return
        val currentParagraph = chunks.getOrNull(chunkIndex)?.paragraphIndex ?: 0
        val target = (currentParagraph + delta).coerceIn(0, chunks.last().paragraphIndex)
        chunkIndex = chunks.indexOfFirst { it.paragraphIndex == target }.takeIf { it >= 0 } ?: chunkIndex
        if (isPlaying) speakCurrent() else callbacks.onParagraphChanged(target)
    }

    private fun advance() {
        if (paused) return
        chunkIndex += 1
        if (chunkIndex < chunks.size) {
            speakCurrent()
        } else {
            callbacks.onPlaybackChanged(false)
            callbacks.onHighlightCleared()
            callbacks.onChapterCompleted()
        }
    }

    private fun speakCurrent() {
        val chunk = chunks.getOrNull(chunkIndex) ?: return
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        playbackGeneration += 1
        val utteranceId = "hayai-$playbackGeneration-$chunkIndex"
        activeUtteranceId = utteranceId
        textToSpeech.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun invalidatePlayback() {
        playbackGeneration += 1
        activeUtteranceId = null
    }

    private fun failPlayback(message: String) {
        invalidatePlayback()
        textToSpeech.stop()
        paused = false
        callbacks.onPlaybackChanged(false)
        callbacks.onHighlightCleared()
        callbacks.onError(message)
    }

    private fun applySettings() {
        textToSpeech.setSpeechRate(speed)
        textToSpeech.setPitch(pitch)
        if (preferredVoice.isNotBlank()) {
            textToSpeech.voices?.firstOrNull { it.name == preferredVoice }?.let { textToSpeech.voice = it }
        }
    }

    private data class TtsChunk(
        val paragraphIndex: Int,
        val text: String,
    )

    interface Callbacks {
        fun onParagraphChanged(index: Int)

        fun onHighlightCleared()

        fun onPlaybackChanged(playing: Boolean)

        fun onChapterCompleted()

        fun onError(message: String)

        fun runOnUiThread(action: () -> Unit)
    }
}

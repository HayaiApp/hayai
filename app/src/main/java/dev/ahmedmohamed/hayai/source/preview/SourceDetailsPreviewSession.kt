package dev.ahmedmohamed.hayai.source.preview

import eu.kanade.tachiyomi.data.database.models.Manga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import java.io.Closeable

sealed interface SourcePreviewState {
    data object Hidden : SourcePreviewState
    data class Loading(val identity: String, val page: Int) : SourcePreviewState
    data class Ready(val identity: String, val previews: SourcePreviewPage) : SourcePreviewState
    data class Failed(val identity: String, val page: Int, val message: String) : SourcePreviewState
}

class SourceDetailsPreviewSession(
    private val previews: SourceDetailsPreviewProvider,
    private val scope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val mutableState = MutableStateFlow<SourcePreviewState>(SourcePreviewState.Hidden)
    val state: StateFlow<SourcePreviewState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var identity: String? = null

    fun bind(manga: Manga, page: Int = 1, forceRefresh: Boolean = false) {
        require(page > 0)
        if (!previews.owns(manga)) {
            unbind()
            return
        }
        val nextIdentity = "${manga.source}:${manga.url}:$page"
        synchronized(lock) {
            if (!forceRefresh && identity == nextIdentity && loadJob?.isActive == true) return
            if (!forceRefresh && identity == nextIdentity && mutableState.value is SourcePreviewState.Ready) return
            releaseCurrentLocked()
            identity = nextIdentity
            mutableState.value = SourcePreviewState.Loading(nextIdentity, page)
            loadJob = scope.launch(Dispatchers.IO) {
                try {
                    val loaded = previews.load(manga, page, CacheControl.FORCE_NETWORK.takeIf { forceRefresh })
                    synchronized(lock) {
                        if (identity == nextIdentity) {
                            mutableState.value = SourcePreviewState.Ready(nextIdentity, loaded)
                            loadJob = null
                        } else {
                            Unit
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    synchronized(lock) {
                        if (identity == nextIdentity) {
                            mutableState.value = SourcePreviewState.Failed(
                                nextIdentity,
                                page,
                                error.message ?: "Page previews could not be loaded",
                            )
                            loadJob = null
                        }
                    }
                }
            }
        }
    }

    fun unbind() {
        synchronized(lock) {
            identity = null
            releaseCurrentLocked()
            mutableState.value = SourcePreviewState.Hidden
        }
    }

    override fun close() = unbind()

    private fun releaseCurrentLocked() {
        loadJob?.cancel()
        loadJob = null
    }
}

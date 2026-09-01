package dev.ahmedmohamed.hayai.novel.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal sealed interface NovelReaderState {
    data object Loading : NovelReaderState

    data class Ready(
        val chapter: LoadedNovelChapter,
        val progress: Int,
        val controlsVisible: Boolean,
        val editMode: Boolean,
    ) : NovelReaderState

    data class Error(val message: String) : NovelReaderState
}

internal enum class NovelRenderingBackend(val value: String) {
    Native("default"),
    WebView("webview"),
    ;

    companion object {
        fun fromPreference(value: String): NovelRenderingBackend = entries.firstOrNull { it.value == value } ?: Native
    }
}

internal typealias NovelRenderingMode = NovelRenderingBackend

internal enum class NovelLayoutMode(val value: String) {
    Continuous("continuous"),
    Paged("paged"),
    ;

    companion object {
        fun fromPreference(value: String): NovelLayoutMode = entries.firstOrNull { it.value == value } ?: Continuous
    }
}

internal enum class NovelWritingDirection(val value: String) {
    Horizontal("horizontal"),
    VerticalRl("vertical-rl"),
    ;

    companion object {
        fun fromPreference(value: String): NovelWritingDirection = entries.firstOrNull { it.value == value } ?: Horizontal
    }
}

internal sealed interface NovelRenderPlan {
    val backend: NovelRenderingBackend
    val layout: NovelLayoutMode
    val writingDirection: NovelWritingDirection

    data class NativeContinuous(
        override val writingDirection: NovelWritingDirection = NovelWritingDirection.Horizontal,
    ) : NovelRenderPlan {
        override val backend = NovelRenderingBackend.Native
        override val layout = NovelLayoutMode.Continuous
    }

    data class Web(
        override val layout: NovelLayoutMode,
        override val writingDirection: NovelWritingDirection,
    ) : NovelRenderPlan {
        override val backend = NovelRenderingBackend.WebView
    }

    companion object {
        fun resolve(
            backend: NovelRenderingBackend,
            layout: NovelLayoutMode,
            writingDirection: NovelWritingDirection,
        ): NovelRenderPlan =
            if (backend == NovelRenderingBackend.Native && layout == NovelLayoutMode.Continuous && writingDirection == NovelWritingDirection.Horizontal) {
                NativeContinuous()
            } else {
                Web(layout, writingDirection)
            }
    }
}

internal data class NovelPagePresentation(
    val progressPercent: Int,
    val pageNumber: Int? = null,
    val pageCount: Int? = null,
) {
    val hasPageCount: Boolean
        get() = pageNumber != null && pageCount != null && pageCount > 0
}

internal data class NovelProgressUpdate(
    val position: Int,
    val pagesLeft: Int,
    val completed: Boolean,
)

internal object NovelReaderProgress {
    fun update(progress: Int, markReadThreshold: Int): NovelProgressUpdate {
        val position = progress.coerceIn(0, 100)
        val completed = position >= markReadThreshold.coerceIn(1, 100)
        return NovelProgressUpdate(
            position = position,
            pagesLeft = if (completed) 0 else 100 - position,
            completed = completed,
        )
    }
}

internal enum class NovelTapAction {
    Previous,
    Menu,
    Next,
    None,
}

internal enum class NovelTapInversion {
    NONE,
    HORIZONTAL,
    VERTICAL,
    BOTH,
    ;

    val invertHorizontal: Boolean get() = this == HORIZONTAL || this == BOTH
    val invertVertical: Boolean get() = this == VERTICAL || this == BOTH

    companion object {
        fun parse(value: String): NovelTapInversion = entries.firstOrNull { it.name == value } ?: NONE
    }
}

internal object NovelTapZones {
    fun action(
        mode: Int,
        xFraction: Float,
        yFraction: Float,
        inversion: NovelTapInversion,
    ): NovelTapAction {
        var x = xFraction.coerceIn(0f, 1f)
        var y = yFraction.coerceIn(0f, 1f)
        if (inversion.invertHorizontal) x = 1f - x
        if (inversion.invertVertical) y = 1f - y
        return when (mode) {
            5, 6 -> NovelTapAction.Menu
            2 -> when {
                y < 0.33f -> NovelTapAction.Menu
                x < 0.33f -> NovelTapAction.Previous
                else -> NovelTapAction.Next
            }
            3 -> when {
                x < 0.33f || x > 0.66f -> NovelTapAction.Next
                y > 0.66f -> NovelTapAction.Previous
                else -> NovelTapAction.Menu
            }
            4 -> when {
                x < 0.33f -> NovelTapAction.Previous
                x > 0.66f -> NovelTapAction.Next
                else -> NovelTapAction.Menu
            }
            else -> when {
                y < 0.33f || x < 0.33f && y < 0.66f -> NovelTapAction.Previous
                y > 0.66f || x > 0.66f && y < 0.66f -> NovelTapAction.Next
                else -> NovelTapAction.Menu
            }
        }
    }
}

internal enum class NovelBottomAction(val id: String) {
    PreviousChapter("prev_chapter"),
    NextChapter("next_chapter"),
    ScrollToTop("scroll_to_top"),
    Translate("translate"),
    AutoScroll("auto_scroll"),
    Tts("tts"),
    TtsViewport("tts_viewport"),
    TtsPreviousParagraph("tts_prev_paragraph"),
    TtsNextParagraph("tts_next_paragraph"),
    Orientation("orientation"),
    Settings("settings"),
    Edit("edit"),
    Quotes("quotes"),
}

@Serializable
internal data class SerializedNovelBottomAction(val id: String, val enabled: Boolean)

internal data class NovelBottomActionState(val action: NovelBottomAction, val enabled: Boolean)

internal object NovelBottomActions {
    val defaults =
        listOf(
            NovelBottomActionState(NovelBottomAction.PreviousChapter, true),
            NovelBottomActionState(NovelBottomAction.ScrollToTop, true),
            NovelBottomActionState(NovelBottomAction.Translate, false),
            NovelBottomActionState(NovelBottomAction.AutoScroll, false),
            NovelBottomActionState(NovelBottomAction.Tts, true),
            NovelBottomActionState(NovelBottomAction.TtsViewport, false),
            NovelBottomActionState(NovelBottomAction.TtsPreviousParagraph, false),
            NovelBottomActionState(NovelBottomAction.TtsNextParagraph, false),
            NovelBottomActionState(NovelBottomAction.Quotes, true),
            NovelBottomActionState(NovelBottomAction.Orientation, false),
            NovelBottomActionState(NovelBottomAction.Settings, true),
            NovelBottomActionState(NovelBottomAction.Edit, false),
            NovelBottomActionState(NovelBottomAction.NextChapter, true),
        )

    fun deserialize(value: String): List<NovelBottomActionState> {
        val saved = runCatching { Json.decodeFromString<List<SerializedNovelBottomAction>>(value) }.getOrDefault(emptyList())
        val resolved = saved.mapNotNull { item -> NovelBottomAction.entries.firstOrNull { it.id == item.id }?.let { NovelBottomActionState(it, item.enabled) } }
        return (resolved + defaults.filter { default -> resolved.none { it.action == default.action } }).distinctBy { it.action }
    }

    fun serialize(items: List<NovelBottomActionState>): String =
        Json.encodeToString(items.map { SerializedNovelBottomAction(it.action.id, it.enabled) })
}

internal enum class NovelStatusItem(val id: String) {
    Time("time"),
    Chapter("chapter"),
    Progress("progress"),
    Battery("battery"),
}

internal object NovelStatusItems {
    val defaults = listOf(NovelStatusItem.Time, NovelStatusItem.Chapter, NovelStatusItem.Progress, NovelStatusItem.Battery)

    fun deserialize(value: String): List<NovelStatusItem> {
        val ids = runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
        val saved = ids.mapNotNull { id -> NovelStatusItem.entries.firstOrNull { it.id == id } }
        return (saved + defaults.filterNot(saved::contains)).distinct()
    }

    fun serialize(items: List<NovelStatusItem>): String = Json.encodeToString(items.map(NovelStatusItem::id))
}

internal sealed interface NovelReaderAction {
    data class Navigate(val direction: Int) : NovelReaderAction
    data class Seek(val progress: Int) : NovelReaderAction
    data object ToggleChrome : NovelReaderAction
    data object ToggleTts : NovelReaderAction
    data object StartTtsAtViewport : NovelReaderAction
    data object PreviousTtsParagraph : NovelReaderAction
    data object NextTtsParagraph : NovelReaderAction
    data object ShowSettings : NovelReaderAction
    data object ShowQuotes : NovelReaderAction
    data object SaveQuote : NovelReaderAction
    data object ToggleEditMode : NovelReaderAction
    data object ToggleBookmark : NovelReaderAction
    data object ToggleAutoScroll : NovelReaderAction
    data object ToggleOrientation : NovelReaderAction
    data object TranslateSelection : NovelReaderAction
    data object TranslateChapter : NovelReaderAction
    data object TranslateAllChapters : NovelReaderAction
    data object DictionaryLookup : NovelReaderAction
    data object ShowStatistics : NovelReaderAction
    data object ToggleOffline : NovelReaderAction
    data object ShowHighlights : NovelReaderAction
    data object ImportFont : NovelReaderAction
    data object ManageFonts : NovelReaderAction
    data object OpenFullSettings : NovelReaderAction
}

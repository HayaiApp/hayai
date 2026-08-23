package dev.ahmedmohamed.hayai.novel.reader

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import eu.kanade.tachiyomi.R

internal enum class NovelSelectionMenuPlacement {
    IfRoom,
    Overflow,
}

internal data class NovelSelectionMenuEntry(
    val action: NovelSelectionAction,
    @IdRes val itemId: Int,
    @StringRes val titleRes: Int,
    val placement: NovelSelectionMenuPlacement,
)

internal object NovelSelectionActionMenu {
    val entries =
        listOf(
            NovelSelectionMenuEntry(
                NovelSelectionAction.SaveQuote,
                R.id.hayai_novel_selection_save_quote,
                R.string.hayai_novel_reader_save_selected_quote,
                NovelSelectionMenuPlacement.IfRoom,
            ),
            NovelSelectionMenuEntry(
                NovelSelectionAction.Define,
                R.id.hayai_novel_selection_define,
                R.string.hayai_novel_selection_define,
                NovelSelectionMenuPlacement.IfRoom,
            ),
            NovelSelectionMenuEntry(
                NovelSelectionAction.GoogleTranslate,
                R.id.hayai_novel_selection_google_translate,
                R.string.hayai_novel_selection_google_translate,
                NovelSelectionMenuPlacement.IfRoom,
            ),
            NovelSelectionMenuEntry(
                NovelSelectionAction.SearchWeb,
                R.id.hayai_novel_selection_search_web,
                R.string.hayai_novel_selection_search_web,
                NovelSelectionMenuPlacement.Overflow,
            ),
        )

    fun actionFor(itemId: Int): NovelSelectionAction? =
        entries.firstOrNull { it.itemId == itemId }?.action

    fun addMissing(context: Context, menu: Menu): Boolean {
        var changed = false
        entries.forEachIndexed { order, entry ->
            if (menu.findItem(entry.itemId) == null) {
                menu
                    .add(Menu.NONE, entry.itemId, CUSTOM_ITEM_ORDER + order, context.getString(entry.titleRes))
                    .setShowAsAction(
                        when (entry.placement) {
                            NovelSelectionMenuPlacement.IfRoom -> MenuItem.SHOW_AS_ACTION_IF_ROOM
                            NovelSelectionMenuPlacement.Overflow -> MenuItem.SHOW_AS_ACTION_NEVER
                        },
                    )
                changed = true
            }
        }
        return changed
    }

    private const val CUSTOM_ITEM_ORDER = 100
}

internal object NovelSelectionActionModes {
    fun wrap(
        context: Context,
        delegate: ActionMode.Callback? = null,
        onAction: (NovelSelectionAction, ActionMode) -> Unit,
        onSelectionModeChanged: (Boolean) -> Unit,
    ): ActionMode.Callback {
        val state = CallbackState(context, delegate, onAction, onSelectionModeChanged)
        val delegateCallback2 = delegate as? ActionMode.Callback2
        return if (delegateCallback2 != null) {
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
                    state.onCreateActionMode(mode, menu)

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                    state.onPrepareActionMode(mode, menu)

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                    state.onActionItemClicked(mode, item)

                override fun onDestroyActionMode(mode: ActionMode) =
                    state.onDestroyActionMode(mode)

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) =
                    delegateCallback2.onGetContentRect(mode, view, outRect)
            }
        } else {
            object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
                    state.onCreateActionMode(mode, menu)

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
                    state.onPrepareActionMode(mode, menu)

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                    state.onActionItemClicked(mode, item)

                override fun onDestroyActionMode(mode: ActionMode) =
                    state.onDestroyActionMode(mode)
            }
        }
    }

    private class CallbackState(
        private val context: Context,
        private val delegate: ActionMode.Callback?,
        private val onAction: (NovelSelectionAction, ActionMode) -> Unit,
        private val onSelectionModeChanged: (Boolean) -> Unit,
    ) {
        private var selectionModeActive = false

        fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = delegate?.onCreateActionMode(mode, menu) ?: true
            if (!created) return false
            NovelSelectionActionMenu.addMissing(context, menu)
            if (!selectionModeActive) {
                selectionModeActive = true
                onSelectionModeChanged(true)
            }
            return true
        }

        fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val delegateChanged = delegate?.onPrepareActionMode(mode, menu) == true
            return NovelSelectionActionMenu.addMissing(context, menu) || delegateChanged
        }

        fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = NovelSelectionActionMenu.actionFor(item.itemId)
            if (action != null) {
                onAction(action, mode)
                return true
            }
            return delegate?.onActionItemClicked(mode, item) == true
        }

        fun onDestroyActionMode(mode: ActionMode) {
            try {
                delegate?.onDestroyActionMode(mode)
            } finally {
                if (selectionModeActive) {
                    selectionModeActive = false
                    onSelectionModeChanged(false)
                }
            }
        }
    }
}

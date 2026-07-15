package hayai.novel.ui

import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import yokai.util.koin.injectLazy
import android.widget.TextView

/**
 * Adapter for the novel plugins list in the browse bottom sheet.
 * Follows the same pattern as ExtensionAdapter.
 */
class NovelPluginAdapter(val listener: OnButtonClickListener) :
    FlexibleAdapter<IFlexible<*>>(null, listener, true) {

    private var submittedSnapshot: SubmittedSnapshot? = null

    private val preferences: PreferencesHelper by injectLazy()

    var installedSortOrder = preferences.installedExtensionsOrder().get()

    init {
        setDisplayHeadersAtStartUp(true)
    }

    fun updateDataSetIfChanged(items: List<NovelPluginItem>): Boolean {
        val nextSnapshot = SubmittedSnapshot(
            items.map(NovelPluginItem::bindingContentSignature),
            installedSortOrder,
        )
        if (nextSnapshot == submittedSnapshot) return false
        submittedSnapshot = nextSnapshot
        updateDataSet(items)
        return true
    }

    private data class SubmittedSnapshot(
        val itemSignatures: List<Int>,
        val installedSortOrder: Int,
    )

    interface OnButtonClickListener {
        fun onNovelPluginButtonClick(position: Int)
        fun onNovelUpdateAllClicked(position: Int)
        fun onNovelSortClicked(view: TextView, position: Int)
    }
}

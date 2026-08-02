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

    private val preferences: PreferencesHelper by injectLazy()

    var installedSortOrder = preferences.installedExtensionsOrder().get()

    init {
        setDisplayHeadersAtStartUp(true)
    }

    interface OnButtonClickListener {
        fun onNovelPluginButtonClick(position: Int)
        fun onNovelUpdateAllClicked(position: Int)
        fun onNovelSortClicked(view: TextView, position: Int)
    }
}

package eu.kanade.tachiyomi.ui.extension

import android.widget.TextView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.extension.ExtensionAdapter.OnButtonClickListener
import yokai.util.koin.injectLazy
import yokai.domain.base.BasePreferences
import yokai.domain.base.BasePreferences.ExtensionInstaller

/**
 * Adapter that holds the catalogue cards.
 *
 * @param listener instance of [OnButtonClickListener].
 */
class ExtensionAdapter(val listener: OnButtonClickListener) :
    FlexibleAdapter<IFlexible<*>>(null, listener, true) {

    private var submittedSnapshot: SubmittedSnapshot? = null

    val basePreferences: BasePreferences by injectLazy()
    val preferences: PreferencesHelper by injectLazy()

    var installedSortOrder = preferences.installedExtensionsOrder().get()
    var installPrivately = basePreferences.extensionInstaller().get() == ExtensionInstaller.PRIVATE

    init {
        setDisplayHeadersAtStartUp(true)
    }

    /**
     * FlexibleAdapter performs a full visible-list rebind for updateDataSet(). Extension
     * discovery and lifecycle refreshes often rebuild equivalent item instances, so compare
     * their complete binding state before dispatching that expensive update.
     */
    fun updateDataSetIfChanged(items: List<ExtensionItem>): Boolean {
        val nextSnapshot = SubmittedSnapshot(
            items.map(ExtensionItem::bindingContentSignature),
            installedSortOrder,
            installPrivately,
        )
        if (nextSnapshot == submittedSnapshot) return false
        submittedSnapshot = nextSnapshot
        updateDataSet(items)
        return true
    }

    private data class SubmittedSnapshot(
        val itemSignatures: List<Int>,
        val installedSortOrder: Int,
        val installPrivately: Boolean,
    )

    /**
     * Listener for browse item clicks.
     */
    val buttonClickListener: OnButtonClickListener = listener

    interface OnButtonClickListener {
        fun onButtonClick(position: Int)
        fun onCancelClick(position: Int)
        fun onUpdateAllClicked(position: Int)
        fun onExtSortClicked(view: TextView, position: Int)
    }
}

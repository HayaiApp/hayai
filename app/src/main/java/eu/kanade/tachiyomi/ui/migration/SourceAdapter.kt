package eu.kanade.tachiyomi.ui.migration

import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible

/**
 * Adapter that holds the catalogue cards.
 *
 * @param allClickListener instance of [MigrationController].
 */
class SourceAdapter(val allClickListener: OnAllClickListener) :
    FlexibleAdapter<IFlexible<*>>(null, allClickListener, true) {

    private var submittedContentSignatures: List<Int>? = null

    init {
        setDisplayHeadersAtStartUp(true)
    }

    fun updateDataSetIfChanged(items: List<SourceItem>, animate: Boolean = false): Boolean {
        val nextSignatures = items.map(SourceItem::bindingContentSignature)
        if (nextSignatures == submittedContentSignatures) return false
        submittedContentSignatures = nextSignatures
        super.updateDataSet(items, animate)
        return true
    }

    /**
     * Listener which should be called when user clicks select.
     */
    interface OnAllClickListener {
        fun onAllClick(position: Int)
    }
}

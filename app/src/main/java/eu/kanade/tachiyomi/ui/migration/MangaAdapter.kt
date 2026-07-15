package eu.kanade.tachiyomi.ui.migration

import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible

class MangaAdapter(listener: Any, val showOutline: Boolean) :
    FlexibleAdapter<IFlexible<*>>(null, listener) {

    private var submittedContentSignatures: List<Int>? = null

    fun updateDataSetIfChanged(items: List<MangaItem>?, animate: Boolean = false): Boolean {
        val nextSignatures = items.orEmpty().map(MangaItem::bindingContentSignature)
        if (nextSignatures == submittedContentSignatures) return false
        submittedContentSignatures = nextSignatures
        super.updateDataSet(items, animate)
        return true
    }
}

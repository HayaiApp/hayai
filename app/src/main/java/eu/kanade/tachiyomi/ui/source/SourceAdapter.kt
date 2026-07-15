package eu.kanade.tachiyomi.ui.source

import yokai.util.koin.get
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper

/**
 * Adapter that holds the catalogue cards.
 *
 * @param controller instance of [BrowseController].
 */
class SourceAdapter(val controller: BrowseController) :
    FlexibleAdapter<IFlexible<*>>(null, controller, true) {

    private var submittedContentSignatures: List<Int>? = null

    init {
        setDisplayHeadersAtStartUp(true)
    }

    val sourceListener: SourceListener = controller

    val enabledLanguages = get<PreferencesHelper>().enabledLanguages().get()

    val extensionManager = controller.presenter.extensionManager

    fun updateDataSetIfChanged(items: List<IFlexible<*>>, animate: Boolean = false): Boolean {
        val nextSignatures = items.map { item ->
            when (item) {
                is SourceItem -> item.bindingContentSignature()
                is LangItem -> 31 * item.getLayoutRes() + item.code.hashCode()
                else -> 31 * item.getLayoutRes() + item.hashCode()
            }
        }
        if (nextSignatures == submittedContentSignatures) return false
        submittedContentSignatures = nextSignatures
        super.updateDataSet(items, animate)
        return true
    }

    override fun onItemSwiped(position: Int, direction: Int) {
        super.onItemSwiped(position, direction)
        controller.hideCatalogue(position)
    }

    interface SourceListener {
        fun onPinClick(position: Int)
        fun onLatestClick(position: Int)
    }
}

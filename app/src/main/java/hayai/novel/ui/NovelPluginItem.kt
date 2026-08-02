package hayai.novel.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractSectionableItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import hayai.novel.plugin.NovelPluginManager
import hayai.novel.plugin.model.NovelPluginIndex

/**
 * Item representing a novel plugin in the browse bottom sheet.
 *
 * Inflates the same XML as the manga ExtensionItem (extension_card_item) but via the
 * `novel_plugin_card_item` alias so FlexibleAdapter/RecyclerView see a distinct viewType —
 * required because a shared RecycledViewPool is keyed by viewType and would otherwise hand
 * an ExtensionHolder to this adapter and crash on the bindViewHolder cast.
 */
data class NovelPluginItem(
    val plugin: NovelPluginIndex,
    val header: NovelPluginGroupItem? = null,
    val isInstalled: Boolean = false,
    val installedVersion: String? = null,
    val isObsolete: Boolean = false,
    val isInstalling: Boolean = false,
) : AbstractSectionableItem<NovelPluginHolder, NovelPluginGroupItem>(header) {

    val hasUpdate: Boolean
        get() = isInstalled &&
            !isObsolete &&
            installedVersion != null &&
            NovelPluginManager.isVersionNewer(plugin.version, installedVersion)

    override fun getLayoutRes(): Int = R.layout.novel_plugin_card_item

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): NovelPluginHolder {
        return NovelPluginHolder(view, adapter as NovelPluginAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: NovelPluginHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.bind(this)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return plugin.id == (other as NovelPluginItem).plugin.id
    }

    override fun hashCode(): Int = plugin.id.hashCode()
}

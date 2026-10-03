package dev.ahmedmohamed.hayai.source.filter

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.Filter

class SourceTagCompletionItem(
    private val filter: Filter.Text,
    private val completion: SourceTagCompletionFilter,
) : AbstractFlexibleItem<SourceTagCompletionItem.Holder>() {
    override fun getLayoutRes(): Int = R.layout.hayai_source_filter_tag_completion

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): Holder = Holder(view, adapter)

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: Holder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.wrapper.hint = filter.name
        holder.wrapper.helperText = completion.hint
        holder.wrapper.error = null
        holder.inputWatcher?.let(holder.input::removeTextChangedListener)
        val bindingGeneration = ++holder.bindingGeneration
        holder.input.setText("", false)
        holder.input.threshold = MINIMUM_QUERY_LENGTH
        val suggestionAdapter = TagSuggestionAdapter(
            holder.itemView.context,
            completion,
            isCurrentQuery = { query ->
                holder.bindingGeneration == bindingGeneration && holder.input.text?.toString().orEmpty() == query
            },
            onPublished = { hasSuggestions ->
                if (hasSuggestions && holder.input.hasFocus() && holder.itemView.isAttachedToWindow) {
                    holder.input.showDropDown()
                }
            },
        )
        holder.input.setAdapter(suggestionAdapter)

        fun renderSelections() {
            holder.chips.removeAllViews()
            completion.selections().forEach { selection ->
                holder.chips.addView(
                    Chip(holder.itemView.context).apply {
                        text = selection
                        isCloseIconVisible = true
                        isCheckable = false
                        contentDescription = selection
                        setOnCloseIconClickListener {
                            completion.setSelections(completion.selections() - selection)
                            holder.wrapper.error = null
                            renderSelections()
                        }
                    },
                )
            }
        }

        fun submit(raw: String): Boolean {
            val candidate = raw.trim()
            if (candidate.isBlank()) return false
            if (completion.isNamespaceSuggestion(candidate)) {
                holder.wrapper.error = null
                holder.input.setText(candidate)
                holder.input.setSelection(candidate.length)
                return true
            }
            val current = completion.selections()
            if (current.none { it.equals(candidate, ignoreCase = true) } && current.size >= completion.maximumSelections) {
                holder.wrapper.error = holder.itemView.context.getString(R.string.hayai_eh_filter_too_many_tags)
                return false
            }
            return runCatching {
                completion.setSelections(current + candidate)
                holder.wrapper.error = null
                holder.input.setText("", false)
                renderSelections()
                true
            }.getOrElse {
                holder.wrapper.error = holder.itemView.context.getString(R.string.hayai_eh_filter_invalid_tag)
                false
            }
        }

        holder.inputWatcher = holder.input.doAfterTextChanged { editable ->
            holder.wrapper.error = null
            if ((editable?.length ?: 0) < MINIMUM_QUERY_LENGTH) suggestionAdapter.clearSuggestions()
        }
        holder.input.setOnItemClickListener { parent, _, itemPosition, _ ->
            (parent.getItemAtPosition(itemPosition) as? String)?.let(::submit)
        }
        holder.input.setOnEditorActionListener { _, actionId, event ->
            val submitted = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            submitted && submit(holder.input.text?.toString().orEmpty())
        }
        renderSelections()
    }

    override fun equals(other: Any?): Boolean = other is SourceTagCompletionItem && filter == other.filter

    override fun hashCode(): Int = filter.hashCode()

    class Holder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ) : FlexibleViewHolder(view, adapter) {
        val wrapper: TextInputLayout = itemView.findViewById(R.id.hayai_tag_input_wrapper)
        val input: MaterialAutoCompleteTextView = itemView.findViewById(R.id.hayai_tag_input)
        val chips: ChipGroup = itemView.findViewById(R.id.hayai_tag_chips)
        var bindingGeneration = 0
        var inputWatcher: android.text.TextWatcher? = null
    }

    /** ArrayAdapter's default prefix filter would hide the catalog's substring and prefixed matches. */
    private class TagSuggestionAdapter(
        context: Context,
        private val completion: SourceTagCompletionFilter,
        private val isCurrentQuery: (String) -> Boolean,
        private val onPublished: (Boolean) -> Unit,
    ) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line) {
        private var suggestions = emptyList<String>()
        private val catalogFilter = object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString().orEmpty()
                val matches = if (query.length < MINIMUM_QUERY_LENGTH) emptyList() else {
                    runCatching { completion.suggestions(query) }.getOrDefault(emptyList())
                }
                return FilterResults().apply {
                    values = MatchingSuggestions(query, matches)
                    count = matches.size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                val matches = results.values as? MatchingSuggestions ?: return
                if (!isCurrentQuery(matches.query)) return
                suggestions = matches.values
                if (suggestions.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
                onPublished(suggestions.isNotEmpty())
            }
        }

        override fun getCount(): Int = suggestions.size

        override fun getItem(position: Int): String? = suggestions.getOrNull(position)

        override fun getFilter(): android.widget.Filter = catalogFilter

        fun clearSuggestions() {
            suggestions = emptyList()
            notifyDataSetInvalidated()
        }

        private data class MatchingSuggestions(val query: String, val values: List<String>)
    }

    private companion object {
        const val MINIMUM_QUERY_LENGTH = 3
    }
}

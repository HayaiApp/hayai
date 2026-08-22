package dev.ahmedmohamed.hayai.source.filter

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
        holder.input.setText("", false)

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
                            renderSelections()
                        }
                    },
                )
            }
        }

        fun submit(raw: String): Boolean {
            val candidate = raw.trim()
            if (candidate.isBlank()) return false
            return runCatching {
                val current = completion.selections()
                if (candidate !in current && current.size >= completion.maximumSelections) {
                    throw IllegalArgumentException(holder.itemView.context.getString(R.string.hayai_eh_filter_too_many_tags))
                }
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

        holder.inputWatcher?.let(holder.input::removeTextChangedListener)
        holder.inputWatcher = holder.input.doAfterTextChanged { editable ->
            val query = editable?.toString().orEmpty()
            val suggestions = completion.suggestions(query)
            holder.suggestionAdapter.clear()
            holder.suggestionAdapter.addAll(suggestions)
            holder.suggestionAdapter.notifyDataSetChanged()
            if (suggestions.isNotEmpty() && holder.input.hasFocus()) holder.input.showDropDown()
        }
        holder.input.setOnItemClickListener { parent, _, itemPosition, _ ->
            submit(parent.getItemAtPosition(itemPosition).toString())
        }
        holder.input.setOnEditorActionListener { _, actionId, event ->
            val submitted = actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER
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
        val suggestionAdapter = ArrayAdapter<String>(itemView.context, android.R.layout.simple_dropdown_item_1line).also(input::setAdapter)
        var inputWatcher: android.text.TextWatcher? = null
    }
}

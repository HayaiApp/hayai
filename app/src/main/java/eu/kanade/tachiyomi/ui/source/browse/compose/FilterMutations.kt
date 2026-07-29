package eu.kanade.tachiyomi.ui.source.browse.compose

import eu.kanade.tachiyomi.source.model.Filter

/**
 * Pure state-mutation helpers for every [Filter] type rendered in the new sheet.
 *
 * Extracted from the composables so the same logic can be exercised by unit tests on the JVM
 * without standing up a Compose harness. The composables in this package wire user gestures to
 * these functions; they MUST NOT mutate `Filter.X.state` directly anywhere else.
 *
 * Every function mutates the supplied filter in place. The supplied instance belongs only to the
 * sheet's isolated draft; [FilterSheetStateHolder] republishes an immutable UI state afterward.
 */
internal object FilterMutations {

    fun toggleCheckbox(filter: Filter.CheckBox) {
        filter.state = !filter.state
    }

    /**
     * Direct-select TriState: tapping the include chip toggles between [Filter.TriState.STATE_INCLUDE]
     * and [Filter.TriState.STATE_IGNORE]; the exclude chip does the same with `STATE_EXCLUDE`.
     * This is a UX upgrade over the legacy single-tap-cycles-three-states gesture but reaches every
     * end state the old code could reach, so the applied query is identical.
     */
    fun setTriState(filter: Filter.TriState, target: Int) {
        require(
            target == Filter.TriState.STATE_INCLUDE ||
                target == Filter.TriState.STATE_EXCLUDE ||
                target == Filter.TriState.STATE_IGNORE,
        ) { "Invalid TriState target: $target" }
        filter.state = if (filter.state == target) Filter.TriState.STATE_IGNORE else target
    }

    /**
     * Cycles the TriState through Ignore → Include → Exclude → Ignore. Used by the M3 Expressive
     * "single pill that rotates through three states" pattern — every tap rotates one step. Reaches
     * every state the legacy two-chip pattern could reach, so the applied query is identical for
     * every source.
     */
    fun cycleTriState(filter: Filter.TriState) {
        filter.state = when (filter.state) {
            Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE
            Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE
            else -> Filter.TriState.STATE_IGNORE
        }
    }

    /**
     * Direct-set TriState. Used when the picker shows all three states as separate targets (e.g.
     * a 3-segment selector in a popup). Unlike [setTriState], this never toggles; tapping the
     * already-selected target is a no-op rather than clearing to ignore.
     */
    fun setTriStateExact(filter: Filter.TriState, target: Int) {
        require(
            target == Filter.TriState.STATE_INCLUDE ||
                target == Filter.TriState.STATE_EXCLUDE ||
                target == Filter.TriState.STATE_IGNORE,
        ) { "Invalid TriState target: $target" }
        filter.state = target
    }

    fun setText(filter: Filter.Text, text: String) {
        filter.state = text
    }

    fun setSelect(filter: Filter.Select<*>, index: Int) {
        filter.state = index
    }

    /**
     * Sort-row tap, replicating [eu.kanade.tachiyomi.widget.SortTextView]'s rule:
     * - currently unselected → selected, ascending=false (shown as descending arrow)
     * - currently selected → toggle ascending
     *
     * Matches legacy `SortItem.bindViewHolder` so the resulting `Filter.Sort.Selection`
     * payload sent to the source is identical.
     */
    fun toggleSort(filter: Filter.Sort, index: Int) {
        val current = filter.state
        filter.state = when {
            current == null -> Filter.Sort.Selection(index, ascending = false)
            current.index != index -> Filter.Sort.Selection(index, ascending = false)
            else -> Filter.Sort.Selection(index, ascending = !current.ascending)
        }
    }

    /**
     * Adds [tag] to the autocomplete state unless the (prefix-stripped) tag is in
     * [Filter.AutoComplete.skipAutoFillTags]. Returns `true` if added.
     *
     * Matches legacy `AutoCompleteContent.onSubmit` so the rejection semantics around
     * "skip" tags is preserved verbatim.
     */
    fun addAutoCompleteTag(filter: Filter.AutoComplete, tag: String): Boolean {
        val prefix = filter.validPrefixes.find { tag.startsWith(it) }
        val tagNoPrefix = if (prefix != null) tag.removePrefix(prefix).trim() else tag
        if (tagNoPrefix in filter.skipAutoFillTags) return false
        filter.state = filter.state + tag
        return true
    }

    fun removeAutoCompleteTag(filter: Filter.AutoComplete, tag: String) {
        filter.state = filter.state - tag
    }

    /**
     * Direct-set the [target] state for [baseTag], **preserving the entry's index** within
     * `filter.state`. Toggling Include → Exclude (or the reverse) replaces in place rather than
     * removing-and-appending — so the pill rendered for this tag stays where it was and doesn't
     * jump to the end of the list.
     *
     * If the source doesn't declare `-` in [Filter.AutoComplete.validPrefixes], a request for
     * [AutoCompleteTagState.Excluded] degrades to [AutoCompleteTagState.Off] rather than writing
     * an unsupported prefix into the query (which the source would treat as a literal tag name).
     */
    fun setAutoCompleteTagState(
        filter: Filter.AutoComplete,
        baseTag: String,
        target: AutoCompleteTagState,
    ) {
        // A caller may hand us an already-prefixed literal selected from the active summary.
        // Preserve source-defined operators such as `~` verbatim; never manufacture `-~tag`.
        if (filter.validPrefixes.any(baseTag::startsWith)) {
            filter.state = when (target) {
                AutoCompleteTagState.Off -> filter.state - baseTag
                else -> if (baseTag in filter.state) filter.state else filter.state + baseTag
            }
            return
        }
        val include = baseTag
        val exclude = "-$baseTag"
        val supportsExclude = "-" in filter.validPrefixes
        val newEntry: String? = when (target) {
            AutoCompleteTagState.Off -> null
            AutoCompleteTagState.Included -> include
            AutoCompleteTagState.Excluded -> if (supportsExclude) exclude else null
        }
        val current = filter.state
        val existingIndex = current.indexOfFirst { it == include || it == exclude }
        filter.state = when {
            existingIndex >= 0 && newEntry != null ->
                current.toMutableList().apply { set(existingIndex, newEntry) }
            existingIndex >= 0 && newEntry == null ->
                current.toMutableList().apply { removeAt(existingIndex) }
            newEntry != null -> current + newEntry
            else -> current
        }
    }

    /**
     * Cycle one base tag through Off → Include → Exclude → Off. When the source declares `-` in
     * `validPrefixes` (e-hentai / ex-hentai / n-hentai etc.) the full three-state cycle is
     * available; otherwise the cycle collapses to Off → Include → Off because no exclude prefix
     * exists for that source.
     *
     * Returns the new state of the tag after cycling so callers don't need to re-inspect
     * `filter.state`.
     */
    fun cycleAutoCompleteTag(filter: Filter.AutoComplete, baseTag: String): AutoCompleteTagState {
        val include = baseTag
        val exclude = "-$baseTag"
        val supportsExclude = "-" in filter.validPrefixes
        return when {
            include in filter.state -> {
                removeAutoCompleteTag(filter, include)
                if (supportsExclude) {
                    addAutoCompleteTag(filter, exclude)
                    AutoCompleteTagState.Excluded
                } else {
                    AutoCompleteTagState.Off
                }
            }
            exclude in filter.state -> {
                removeAutoCompleteTag(filter, exclude)
                AutoCompleteTagState.Off
            }
            else -> {
                addAutoCompleteTag(filter, include)
                AutoCompleteTagState.Included
            }
        }
    }
}

/**
 * Per-tag tri-state for [Filter.AutoComplete] entries in the tag picker page. Stored implicitly
 * in `filter.state` (as the tag or its `-tag` prefixed form); this enum is just the rendering
 * lens. Returned by [FilterMutations.cycleAutoCompleteTag] so callers can update local UI state
 * in one call without re-reading the filter.
 */
internal enum class AutoCompleteTagState { Off, Included, Excluded }

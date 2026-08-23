package dev.ahmedmohamed.hayai.adult.eh.presentation

data class EhBrowseDisplayMode(
    val browseAsList: Boolean,
    val enhancedList: Boolean,
) {
    val isList: Boolean
        get() = browseAsList || enhancedList

    fun toggled(): EhBrowseDisplayMode =
        if (isList) {
            EhBrowseDisplayMode(browseAsList = false, enhancedList = false)
        } else {
            EhBrowseDisplayMode(browseAsList = true, enhancedList = true)
        }
}

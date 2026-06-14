package eu.kanade.tachiyomi.ui.library.display

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.databinding.LibraryDisplayLayoutBinding
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.util.bindToPreference
import eu.kanade.tachiyomi.util.lang.addBetaTag
import eu.kanade.tachiyomi.util.view.rowsForValue
import eu.kanade.tachiyomi.widget.BaseLibraryDisplayView
import kotlin.math.roundToInt
import yokai.i18n.MR
import yokai.presentation.theme.YokaiTheme
import yokai.util.lang.getString

class LibraryDisplayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    BaseLibraryDisplayView<LibraryDisplayLayoutBinding>(context, attrs) {

    var mainView: View? = null
    override fun inflateBinding() = LibraryDisplayLayoutBinding.bind(this)
    override fun initGeneralPreferences() {
        binding.flattenSearch.isEnabled =
            preferences.libraryDisplayMode().get() == LibraryItem.DISPLAY_MODE_TABBED
        binding.displayModeGroup.bindToPreference(preferences.libraryDisplayMode()) {
            binding.flattenSearch.isEnabled =
                preferences.libraryDisplayMode().get() == LibraryItem.DISPLAY_MODE_TABBED
        }
        binding.flattenSearch.bindToPreference(preferences.librarySearchAcrossTabs())
        binding.displayGroup.bindToPreference(preferences.libraryLayout())
        binding.uniformGrid.bindToPreference(uiPreferences.uniformGrid()) {
            binding.staggeredGrid.isEnabled = !it
        }
        binding.outlineOnCovers.bindToPreference(uiPreferences.outlineOnCovers())
        binding.staggeredGrid.text = context.getString(MR.strings.use_staggered_grid).addBetaTag(context)
        binding.staggeredGrid.isEnabled = !uiPreferences.uniformGrid().get()
        binding.staggeredGrid.bindToPreference(preferences.useStaggeredGrid())

        val gridSizePreference = preferences.gridSize()
        val displayView = this@LibraryDisplayView
        binding.gridSizeCompose.setContent {
            val ctx = LocalContext.current
            val gridSize by gridSizePreference.collectAsState()
            val initialValue = ((gridSize + .5f) * 2f).roundToInt().toFloat()
            var sliderValue by remember(initialValue) { mutableFloatStateOf(initialValue) }
            YokaiTheme {
                val rows = remember(sliderValue) {
                    (displayView.mainView ?: displayView).rowsForValue(sliderValue)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = ctx.getString(MR.strings.grid_size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = ctx.getString(MR.strings._per_row, rows),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                preferences.gridSize().set((sliderValue / 2f) - .5f)
                            },
                            valueRange = 0f..7f,
                            steps = 6,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            sliderValue = 3f
                            preferences.gridSize().set((3f / 2f) - .5f)
                        }) {
                            Text(ctx.getString(MR.strings.reset))
                        }
                    }
                }
            }
        }
    }
}

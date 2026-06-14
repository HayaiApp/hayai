package exh.recs.batch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationSearchBottomSheetDialog(
    initialFlags: Int,
    onDismissRequest: () -> Unit,
    onSearchRequest: (flags: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var flags by remember(initialFlags) { mutableIntStateOf(initialFlags) }

    val includeSources = SearchFlags.hasIncludeSources(flags)
    val includeTrackers = SearchFlags.hasIncludeTrackers(flags)
    val hideLibrary = SearchFlags.hasHideLibraryResults(flags)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(MR.strings.rec_services_to_search),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            CheckboxRow(
                label = stringResource(MR.strings.rec_group_source),
                checked = includeSources,
                onCheckedChange = { checked ->
                    flags = if (checked) {
                        flags or SearchFlags.INCLUDE_SOURCES
                    } else {
                        flags and SearchFlags.INCLUDE_SOURCES.inv()
                    }
                },
            )

            CheckboxRow(
                label = stringResource(MR.strings.rec_group_tracker),
                checked = includeTrackers,
                onCheckedChange = { checked ->
                    flags = if (checked) {
                        flags or SearchFlags.INCLUDE_TRACKERS
                    } else {
                        flags and SearchFlags.INCLUDE_TRACKERS.inv()
                    }
                },
            )

            CheckboxRow(
                label = stringResource(MR.strings.rec_hide_library_entries),
                checked = hideLibrary,
                onCheckedChange = { checked ->
                    flags = if (checked) {
                        flags or SearchFlags.HIDE_LIBRARY_RESULTS
                    } else {
                        flags and SearchFlags.HIDE_LIBRARY_RESULTS.inv()
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = includeSources || includeTrackers,
                onClick = { onSearchRequest(flags) },
            ) {
                Text(text = stringResource(MR.strings.rec_search_short))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

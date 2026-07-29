package eu.kanade.tachiyomi.ui.source.browse.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.domain.source.browse.filter.ActiveConstraint
import yokai.domain.source.browse.filter.ConstraintSemantic
import yokai.i18n.MR

@Composable
internal fun RefinementRibbon(
    constraints: List<ActiveConstraint>,
    updating: Boolean,
    onOpen: (ActiveConstraint) -> Unit,
    onRemove: (ActiveConstraint) -> Unit,
) {
    val listState = rememberLazyListState()
    val showEndCue by remember(listState, constraints) {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index?.let { it < constraints.lastIndex } == true
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    items(constraints, key = { "${it.path}-${it.value}-${it.semantic}" }) { constraint ->
                        InputChip(
                            selected = true,
                            onClick = { onOpen(constraint) },
                            label = {
                                Text(
                                    text = constraint.ribbonText(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = if (constraint.semantic == ConstraintSemantic.QUERY) {
                                { Icon(Icons.Outlined.Search, null, Modifier.width(18.dp)) }
                            } else {
                                null
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemove(constraint) },
                                    modifier = Modifier.width(28.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(MR.strings.remove),
                                        modifier = Modifier.width(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
                if (showEndCue) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(28.dp)
                            .height(48.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.surfaceContainer,
                                    ),
                                ),
                            ),
                    )
                }
            }
            if (updating) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
        }
    }
}

private fun ActiveConstraint.ribbonText(): String = when (semantic) {
    ConstraintSemantic.QUERY -> value
    ConstraintSemantic.INCLUDE -> "+$value"
    ConstraintSemantic.EXCLUDE -> if (value.startsWith("-")) value else "−$value"
    else -> "$label: $value"
}

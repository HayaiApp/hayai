package yokai.presentation.extension.repo.component

import android.content.res.Configuration
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextFieldDefaults.indicatorLine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.util.compose.textHint
import yokai.domain.extension.repo.model.ExtensionRepo
import yokai.presentation.component.Gap
import yokai.presentation.theme.Size
import yokai.util.secondaryItemAlpha

@Composable
fun ExtensionRepoItem(
    modifier: Modifier = Modifier,
    extensionRepo: ExtensionRepo,
    onDeleteClick: (String) -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Label,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column(
                modifier = Modifier.weight(1.0f),
            ) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                    text = extensionRepo.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Gap(Size.extraTiny)
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .secondaryItemAlpha(),
                    text = extensionRepo.baseUrl,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                modifier = Modifier.size(40.dp),
                onClick = { onDeleteClick(extensionRepo.baseUrl) },
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun ExtensionRepoInput(
    inputHint: String,
    modifier: Modifier = Modifier,
    inputText: String = "",
    onInputChange: (String) -> Unit = {},
    onAddClick: (String) -> Unit = {},
    isLoading: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }

    val colors = TextFieldDefaults.colors().copy(
        cursorColor = MaterialTheme.colorScheme.secondary,
        focusedPlaceholderColor = MaterialTheme.colorScheme.textHint,
        unfocusedPlaceholderColor = MaterialTheme.colorScheme.textHint,
        errorPlaceholderColor = MaterialTheme.colorScheme.textHint,
        focusedTextColor = MaterialTheme.colorScheme.onBackground,
        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
        errorTextColor = MaterialTheme.colorScheme.onBackground,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            TextField(
                modifier = Modifier
                    .indicatorLine(
                        enabled = false,
                        colors = colors,
                        interactionSource = interactionSource,
                        isError = true,
                    )
                    .weight(1.0f),
                value = inputText,
                onValueChange = onInputChange,
                enabled = !isLoading,
                placeholder = { Text(text = inputHint, fontSize = 16.sp) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp),
                colors = colors,
            )
            IconButton(
                modifier = Modifier.size(40.dp),
                onClick = { onAddClick(inputText) },
                enabled = inputText.isNotEmpty(),
            ) {
                if (!isLoading) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                } else {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true)
@Composable
fun ExtensionRepoItemPreview() {
    val input = "https://raw.githubusercontent.com/null2264/totally-real-extensions/repo/index.min.json"
    Surface {
        Column {
            ExtensionRepoItem(extensionRepo = ExtensionRepo("", "", "", "", ""))
            ExtensionRepoInput(inputHint = "Input")
            ExtensionRepoInput(inputHint = "", inputText = input)
            ExtensionRepoInput(inputHint = "", inputText = input, isLoading = true)
        }
    }
}

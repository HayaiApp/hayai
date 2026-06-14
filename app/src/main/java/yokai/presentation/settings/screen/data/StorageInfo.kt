package yokai.presentation.settings.screen.data

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.kanade.tachiyomi.R
import yokai.i18n.MR
import yokai.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import yokai.presentation.theme.Size
import yokai.presentation.theme.header
import yokai.util.secondaryItemAlpha
import java.io.File

@Composable
fun StorageInfo(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val storages = remember(context) { DiskUtil.getExternalStorages(context) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Size.small),
    ) {
        storages.forEach {
            StorageInfo(it)
        }
    }
}

@Composable
private fun StorageInfo(
    file: File,
) {
    val context = LocalContext.current

    val available = remember(file) { DiskUtil.getAvailableStorageSpace(file) }
    val availableText = remember(context, available) { Formatter.formatFileSize(context, available) }
    val total = remember(file) { DiskUtil.getTotalStorageSpace(file) }
    val totalText = remember(context, total) { Formatter.formatFileSize(context, total) }

    Column(
        verticalArrangement = Arrangement.spacedBy(Size.tiny),
    ) {
        Text(
            text = file.absolutePath,
            style = MaterialTheme.typography.header,
        )

        Text(
            text = stringResource(MR.strings.available_disk_space_info, availableText, totalText),
            modifier = Modifier.secondaryItemAlpha(),
            style = MaterialTheme.typography.bodySmall,
        )

        LinearProgressIndicator(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .fillMaxWidth(),
            progress = { (1 - (available / total.toFloat())) },
        )
    }
}

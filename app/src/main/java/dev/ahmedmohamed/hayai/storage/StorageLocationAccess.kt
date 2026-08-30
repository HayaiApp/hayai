package dev.ahmedmohamed.hayai.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import java.io.File

/** Android storage boundary shared by downloads and automatic backups. */
object StorageLocationAccess {
    fun defaultDirectory(
        context: Context,
        child: String,
    ): Uri {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return Uri.fromFile(File(root, child))
    }

    fun persistTreePermission(
        context: Context,
        uri: Uri,
        resultFlags: Int,
    ): Result<Unit> =
        runCatching {
            val accessFlags = resultFlags and READ_WRITE_FLAGS
            require(accessFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                context.getString(R.string.hayai_storage_write_permission_missing)
            }
            context.contentResolver.takePersistableUriPermission(uri, accessFlags)
        }

    fun openDirectory(
        context: Context,
        rawUri: String,
    ): Result<UniFile> =
        runCatching {
            val uri = Uri.parse(rawUri)
            when (uri.scheme) {
                "content" -> requirePersistedWritePermission(context, uri)
                "file" -> prepareFileDirectory(context, uri)
                else -> error(context.getString(R.string.hayai_storage_location_unsupported))
            }
            val directory = UniFile.fromUri(context, uri)
                ?: error(context.getString(R.string.hayai_storage_location_unavailable))
            require(directory.exists() && directory.isDirectory && directory.canWrite()) {
                context.getString(R.string.hayai_storage_location_unavailable)
            }
            directory
        }

    fun displayName(
        context: Context,
        rawUri: String,
    ): String {
        val uri = Uri.parse(rawUri)
        val file = UniFile.fromUri(context, uri)
        return file?.filePath ?: file?.name ?: uri.lastPathSegment ?: rawUri
    }

    fun needsAllFilesAccess(
        context: Context,
        rawUri: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val uri = Uri.parse(rawUri)
        if (uri.scheme != "file") return false
        val path = uri.path?.let(::File) ?: return false
        return !isInside(path, context.filesDir) &&
            !isInside(path, context.getExternalFilesDir(null)) &&
            !Environment.isExternalStorageManager()
    }

    private fun requirePersistedWritePermission(
        context: Context,
        uri: Uri,
    ) {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        require(permission?.isWritePermission == true) {
            context.getString(R.string.hayai_storage_permission_lost)
        }
    }

    private fun prepareFileDirectory(
        context: Context,
        uri: Uri,
    ) {
        val directory = uri.path?.let(::File)
            ?: error(context.getString(R.string.hayai_storage_location_unavailable))
        if (needsAllFilesAccess(context, uri.toString())) {
            error(context.getString(R.string.hayai_storage_all_files_required))
        }
        require(directory.exists() || directory.mkdirs()) {
            context.getString(R.string.hayai_storage_location_unavailable)
        }
    }

    internal fun isInside(
        child: File,
        parent: File?,
    ): Boolean {
        if (parent == null) return false
        val childPath = child.canonicalFile.path
        val parentPath = parent.canonicalFile.path
        return childPath == parentPath || childPath.startsWith(parentPath.trimEnd(File.separatorChar) + File.separator)
    }

    private const val READ_WRITE_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}

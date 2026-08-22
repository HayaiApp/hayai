package dev.ahmedmohamed.hayai.novel.archive

import android.os.ParcelFileDescriptor
import android.content.res.Resources
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import eu.kanade.tachiyomi.R
import java.io.Closeable
import java.io.File
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor, resources: Resources) : Closeable {
    private val size = pfd.statSize.also { require(it > 0L) { resources.getString(R.string.hayai_failure_archive_empty) } }
    private val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = ArchiveInputStream(address, size).use {
        block(generateSequence { it.getNextEntry() })
    }

    fun getInputStream(entryName: String): InputStream? {
        val archive = ArchiveInputStream(address, size)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    override fun close() {
        Os.munmap(address, size)
    }

    companion object {
        fun open(file: File, resources: Resources): ArchiveReader {
            require(file.isFile) { resources.getString(R.string.hayai_failure_archive_missing, file.path) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { ArchiveReader(it, resources) }
        }
    }
}

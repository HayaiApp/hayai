package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.extension.repo.ExtensionRepoRepository

class ExtensionReposBackupRestorer(
    private val extensionRepoRepository: ExtensionRepoRepository = Injekt.get(),
) {
    suspend fun restoreExtensionRepos(backupExtensionRepos: List<BackupExtensionRepo>) {
        backupExtensionRepos.forEach {
            extensionRepoRepository.upsertRepository(it.getExtensionRepo())
        }
    }
}

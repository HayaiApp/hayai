package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.extension.repo.interactor.GetExtensionRepo

class ExtensionReposBackupCreator(
    private val getExtensionRepo: GetExtensionRepo = Injekt.get(),
) {
    suspend operator fun invoke(): List<BackupExtensionRepo> {
        return getExtensionRepo.getAll()
            .map { BackupExtensionRepo.copyFrom(it) }
    }
}

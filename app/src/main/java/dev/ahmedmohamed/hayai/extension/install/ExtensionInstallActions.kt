package dev.ahmedmohamed.hayai.extension.install

import eu.kanade.tachiyomi.extension.model.InstallStep

object ExtensionInstallActions {
    fun canCancel(step: InstallStep?, hasSession: Boolean): Boolean =
        when (step) {
            InstallStep.Pending, InstallStep.Downloading, InstallStep.Loading -> true
            InstallStep.Installing -> hasSession
            else -> false
        }
}

# Hayai emulator verification

`tools/verify-hayai-emulator.ps1` verifies migration and the coupled novel and adult workflows against a disposable debug installation. It refuses the production application ID. It also refuses an AVD whose name does not explicitly contain both `Hayai` and `Test`.

## Prerequisites

- A dedicated API 35 AVD such as `Hayai_API_35_Test`.
- A previously built universal dev-debug APK. The harness never builds the app.
- Android SDK `adb` and `emulator` on `PATH`.
- SQLite 3 on `PATH`.
- A dedicated E-Hentai test account. Do not use a personal account.
- Controlled E-Hentai and ExHentai gallery URLs that the test account may access.

Copy `tools/fixtures/hayai-emulator/eh-secrets.example.json` outside the repository. Fill it with the dedicated account cookie values and controlled gallery URLs. The harness does not print these values. It writes the temporary preference XML under the operating-system temporary directory and removes it in `finally`.

Run the non-mutating prerequisite check first.

```powershell
.\tools\verify-hayai-emulator.ps1 `
  -AvdName Hayai_API_35_Test `
  -ApkPath .\app\build\outputs\apk\dev\debug\app-dev-universal-debug.apk `
  -EhSecretsPath C:\secure\hayai-eh-test.json `
  -DryRun
```

Then run the workflow with the same arguments and without `-DryRun`. Evidence is written under `artifacts/emulator-verification/<UTC timestamp>`.

## Verified behavior

The harness creates a sanitized v36 database from source SQL. It installs only `dev.ahmedmohamed.hayai.debug`, places the legacy database before first launch, and checks migration state plus table counts. It opens the controlled local novel directly, checks the selection actions for highlights, translation, and dictionary lookup, stores an offline chapter, restarts the process, and confirms offline recovery.

It stages dedicated test credentials, asks the app to verify them with ExHentai, uploads remote settings, previews and runs favorites sync, starts the gallery updater, opens the controlled gallery details, and checks page previews. It captures screenshots, UI hierarchy files, database snapshots, count reports, and Logcat. Any captured Hayai fatal exception fails the run.

Cleanup uninstalls only the debug application and removes only the fixed controlled local-novel fixture directory. It never clears, installs over, or uninstalls production Hayai. Use `-KeepTestApp` only when inspecting a failed dedicated AVD manually.

The harness expects stable user-facing labels from the production features. A label mismatch fails at the affected workflow instead of silently skipping it.

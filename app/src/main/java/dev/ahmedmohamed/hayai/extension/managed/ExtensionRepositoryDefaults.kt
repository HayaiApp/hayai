package dev.ahmedmohamed.hayai.extension.managed

/** Catalogs shipped for first-run extension discovery. Users can remove them normally. */
object ExtensionRepositoryDefaults {
    const val KEIYOUSHI_MANGA = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
    const val CURSED_MANGA = "https://raw.githubusercontent.com/mojuru/cursed-manga-repo/repo/index.pb"
    const val LNREADER_NOVELS =
        "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"

    val manga: Set<String> = setOf(KEIYOUSHI_MANGA, CURSED_MANGA)
}

package dev.ahmedmohamed.hayai.novel.dictionary

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

enum class NovelDictionaryProvider(val packageName: String?, val uri: (String) -> Uri) {
    SYSTEM(null, { Uri.EMPTY }),
    AARD2("itkach.aard2", { Uri.parse("aard2://lookup/${Uri.encode(it)}") }),
    COLOR_DICT("com.socialnmobile.colordict", { Uri.parse("colordict://lookup/${Uri.encode(it)}") }),
    LIVIO("livio.pack.lang.en_US", { Uri.parse("dictionary://lookup/${Uri.encode(it)}") }),
    WEB_GOOGLE(null, { Uri.parse("https://www.google.com/search?q=${Uri.encode("define $it")}") }),
    WEB_WIKTIONARY(null, { Uri.parse("https://en.wiktionary.org/wiki/${Uri.encode(it.replace(' ', '_'))}") }),
}

data class NovelDictionarySettings(
    val provider: NovelDictionaryProvider = NovelDictionaryProvider.SYSTEM,
    val webFallback: NovelDictionaryProvider = NovelDictionaryProvider.WEB_WIKTIONARY,
)

class NovelDictionarySettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("hayai_novel_dictionary", Context.MODE_PRIVATE)

    fun get() = NovelDictionarySettings(
        provider = runCatching {
            NovelDictionaryProvider.valueOf(preferences.getString("provider", null).orEmpty())
        }.getOrDefault(NovelDictionaryProvider.SYSTEM),
        webFallback = runCatching {
            NovelDictionaryProvider.valueOf(preferences.getString("fallback", null).orEmpty())
        }.getOrDefault(NovelDictionaryProvider.WEB_WIKTIONARY),
    )

    fun set(value: NovelDictionarySettings) {
        require(value.webFallback.name.startsWith("WEB_"))
        check(
            preferences.edit()
                .putString("provider", value.provider.name)
                .putString("fallback", value.webFallback.name)
                .commit(),
        )
    }
}

class NovelDictionaryLauncher(private val context: Context) {
    fun availableProviders(): List<NovelDictionaryProvider> = NovelDictionaryProvider.entries.filter { provider ->
        provider.packageName == null || context.packageManager.getLaunchIntentForPackage(provider.packageName) != null
    }

    fun open(selection: String, settings: NovelDictionarySettings = NovelDictionarySettings()) {
        val word = selection.trim().replace(Regex("\\s+"), " ")
        require(word.isNotEmpty() && word.length <= MAX_QUERY_LENGTH)
        if (settings.provider == NovelDictionaryProvider.SYSTEM) {
            val intent = Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(Intent.createChooser(intent, "Look up in dictionary").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        } else if (tryOpen(settings.provider, word)) {
            return
        }
        val fallback = settings.webFallback.takeIf { it.name.startsWith("WEB_") }
            ?: NovelDictionaryProvider.WEB_WIKTIONARY
        check(tryOpen(fallback, word)) { "No dictionary application or browser is available" }
    }

    fun applicationSettingsIntent(provider: NovelDictionaryProvider): Intent? = provider.packageName?.let {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$it"))
    }

    private fun tryOpen(provider: NovelDictionaryProvider, word: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, provider.uri(word)).apply {
            provider.packageName?.let(::setPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            if (intent.resolveActivity(context.packageManager) == null) {
                false
            } else {
                context.startActivity(intent)
                true
            }
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private companion object { const val MAX_QUERY_LENGTH = 512 }
}

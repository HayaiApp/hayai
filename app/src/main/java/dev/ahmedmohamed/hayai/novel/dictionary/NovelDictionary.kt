package dev.ahmedmohamed.hayai.novel.dictionary

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private fun encodeDictionaryQuery(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

enum class NovelDictionaryProvider(val packageName: String?, private val urlFactory: (String) -> String) {
    SYSTEM(null, { "" }),
    AARD2("itkach.aard2", { "aard2://lookup/${encodeDictionaryQuery(it)}" }),
    COLOR_DICT("com.socialnmobile.colordict", { "colordict://lookup/${encodeDictionaryQuery(it)}" }),
    LIVIO("livio.pack.lang.en_US", { "dictionary://lookup/${encodeDictionaryQuery(it)}" }),
    WEB_GOOGLE(null, { "https://www.google.com/search?q=${encodeDictionaryQuery("define $it")}" }),
    WEB_WIKTIONARY(null, { "https://en.wiktionary.org/wiki/${encodeDictionaryQuery(it.replace(' ', '_'))}" }),
    ;

    fun url(query: String): String = urlFactory(query)

    fun uri(query: String): Uri = if (this == SYSTEM) Uri.EMPTY else Uri.parse(url(query))

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

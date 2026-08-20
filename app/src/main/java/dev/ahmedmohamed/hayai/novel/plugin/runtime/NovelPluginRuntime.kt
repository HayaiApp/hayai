package dev.ahmedmohamed.hayai.novel.plugin.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withTimeout

internal class NovelPluginRuntime(
    private val pluginId: String,
    private val siteUrl: String,
    private val dispatcher: CoroutineDispatcher,
) {
    private val library = NovelPluginLibrary(pluginId, siteUrl)

    suspend fun open(code: String): NovelPluginInstance {
        require(code.length in 1..MAX_CODE_CHARS) { "Plugin code is empty or too large" }
        val runtime = QuickJs.create(dispatcher)
        try {
            withTimeout(EVALUATION_TIMEOUT_MS) { library.setup(runtime) }
            withTimeout(EVALUATION_TIMEOUT_MS) { runtime.evaluate<Any?>(sanitize(code), "$pluginId.js", asModule = false) }
            withTimeout(EVALUATION_TIMEOUT_MS) {
                runtime.evaluate<Any?>(
                    """
                    globalThis.__hayaiPluginClass =
                        (typeof exports !== 'undefined' && exports.default) ||
                        (typeof module !== 'undefined' && module.exports && (module.exports.default || module.exports));
                    if (!globalThis.__hayaiPluginClass) throw new Error('Plugin has no default export');
                    globalThis.plugin = typeof globalThis.__hayaiPluginClass === 'function'
                        ? new globalThis.__hayaiPluginClass()
                        : globalThis.__hayaiPluginClass;
                    if (!globalThis.plugin) throw new Error('Plugin could not be instantiated');
                    """.trimIndent(),
                    "hayai-plugin-loader.js",
                    asModule = false,
                )
            }
            return NovelPluginInstance(runtime, library)
        } catch (error: Throwable) {
            runtime.close()
            library.cleanup()
            throw error
        }
    }

    private fun sanitize(code: String): String =
        code
            .removePrefix("\uFEFF")
            .replace("\u0000", "")
            .replace(Regex("[\u0001-\u0008\u000B\u000C\u000E-\u001F]"), "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    companion object {
        private const val MAX_CODE_CHARS = 8 * 1024 * 1024
        private const val EVALUATION_TIMEOUT_MS = 30_000L
    }
}

internal class NovelPluginInstance(
    private val runtime: QuickJs,
    private val library: NovelPluginLibrary,
) : AutoCloseable {
    suspend fun evaluate(script: String): Any? =
        withTimeout(30_000L) { runtime.evaluate<Any?>(script, "hayai-plugin-call.js", asModule = false) }

    suspend fun execute(script: String): Any? = evaluate(script)

    override fun close() {
        runtime.close()
        library.cleanup()
    }
}

package dev.ahmedmohamed.hayai.novel.plugin.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One native QuickJS runtime for both J2K extension helpers and Hayai novel plugins.
 * Keeping one implementation avoids two incompatible JNI libraries with the same filename.
 */
object HayaiJavaScriptEvaluator {
    suspend fun <T> evaluate(script: String): T =
        withContext(Dispatchers.IO) {
            QuickJs.create(Dispatchers.IO).use { runtime ->
                @Suppress("UNCHECKED_CAST")
                runtime.evaluate<Any?>(script, "tachiyomi-extension.js", asModule = false) as T
            }
        }
}

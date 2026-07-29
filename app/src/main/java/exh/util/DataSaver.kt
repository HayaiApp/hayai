package exh.util

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.ExhPreferences
import okhttp3.Response
import java.net.URLEncoder

/**
 * Data saver interface for bandwidth optimization.
 * Supports wsrv.nl CDN proxy and custom Hayai image proxy servers.
 */
interface DataSaver {

    fun compress(imageUrl: String): String

    companion object {
        val NoOp = object : DataSaver {
            override fun compress(imageUrl: String): String {
                return imageUrl
            }
        }

        suspend fun HttpSource.getImage(page: Page, dataSaver: DataSaver, existingSize: Long = 0L): Response {
            val imageUrl = page.imageUrl ?: return getImage(page, existingSize)
            page.imageUrl = dataSaver.compress(imageUrl)
            return try {
                getImage(page, existingSize)
            } finally {
                page.imageUrl = imageUrl
            }
        }

        fun fromPreferences(exhPreferences: ExhPreferences): DataSaver {
            val quality = exhPreferences.dataSaverQuality.get()
            val format = exhPreferences.dataSaverFormat.get()
            val maxWidth = exhPreferences.dataSaverMaxWidth.get().toIntOrNull() ?: 0
            val maxHeight = exhPreferences.dataSaverMaxHeight.get().toIntOrNull() ?: 0
            val fitMode = exhPreferences.dataSaverFitMode.get()
            val noUpscale = exhPreferences.dataSaverNoUpscale.get()
            val brightness = exhPreferences.dataSaverBrightness.get()
            val contrast = exhPreferences.dataSaverContrast.get()
            val saturation = exhPreferences.dataSaverSaturation.get()
            val sharpen = exhPreferences.dataSaverSharpen.get()
            val blur = exhPreferences.dataSaverBlur.get()
            val filter = exhPreferences.dataSaverFilter.get()

            return when (exhPreferences.dataSaverMode.get()) {
                1 -> WsrvNlDataSaver(
                    quality = quality,
                    format = format,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    fitMode = fitMode,
                    noUpscale = noUpscale,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    sharpen = sharpen,
                    blur = blur,
                    filter = filter,
                )
                2 -> HayaiProxyDataSaver(
                    serverUrl = exhPreferences.dataSaverServerUrl.get(),
                    apiKey = exhPreferences.dataSaverApiKey.get().nullIfBlank(),
                    quality = quality,
                    format = format,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    fitMode = fitMode,
                    noUpscale = noUpscale,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    sharpen = sharpen,
                    blur = blur,
                    filter = filter,
                )
                3 -> ProxyDataSaver(
                    serverUrl = exhPreferences.dataSaverServerUrl.get(),
                    apiKey = exhPreferences.dataSaverApiKey.get().nullIfBlank(),
                    quality = quality,
                    format = format,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    fitMode = fitMode,
                    noUpscale = noUpscale,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    sharpen = sharpen,
                    blur = blur,
                    filter = filter,
                )
                else -> NoOp
            }
        }
    }
}

/**
 * Builds wsrv.nl query parameters from common image processing options.
 */
private fun buildWsrvParams(
    quality: Int,
    format: String,
    maxWidth: Int,
    maxHeight: Int,
    fitMode: String,
    noUpscale: Boolean,
    brightness: Int,
    contrast: Int,
    saturation: Int,
    sharpen: Int,
    blur: Int,
    filter: String,
): String = buildString {
    if (maxWidth > 0) append("&w=$maxWidth")
    if (maxHeight > 0) append("&h=$maxHeight")
    append("&q=$quality")
    if (format != "original") append("&output=$format")
    if (fitMode != "inside") append("&fit=$fitMode")
    if (noUpscale) append("&we")
    if (brightness != 0) append("&bri=$brightness")
    if (contrast != 0) append("&con=$contrast")
    if (saturation != 0) append("&sat=$saturation")
    if (sharpen > 0) append("&sharp=$sharpen")
    if (blur > 0) append("&blur=$blur")
    if (filter != "none") append("&filter=$filter")
}

/**
 * wsrv.nl — free image CDN proxy that supports on-the-fly image optimization.
 * See https://wsrv.nl/ for documentation.
 */
class WsrvNlDataSaver(
    private val quality: Int = 80,
    private val format: String = "webp",
    private val maxWidth: Int = 0,
    private val maxHeight: Int = 0,
    private val fitMode: String = "inside",
    private val noUpscale: Boolean = true,
    private val brightness: Int = 0,
    private val contrast: Int = 0,
    private val saturation: Int = 0,
    private val sharpen: Int = 0,
    private val blur: Int = 0,
    private val filter: String = "none",
) : DataSaver {
    override fun compress(imageUrl: String): String {
        val encoded = URLEncoder.encode(imageUrl, "UTF-8")
        val params = buildWsrvParams(
            quality, format, maxWidth, maxHeight, fitMode, noUpscale,
            brightness, contrast, saturation, sharpen, blur, filter,
        )
        return "https://wsrv.nl/?url=$encoded$params"
    }
}

/**
 * Custom Hayai image proxy server for self-hosted image optimization.
 */
class HayaiProxyDataSaver(
    private val serverUrl: String,
    private val apiKey: String? = null,
    private val quality: Int = 80,
    private val format: String = "webp",
    private val maxWidth: Int = 0,
    private val maxHeight: Int = 0,
    private val fitMode: String = "inside",
    private val noUpscale: Boolean = true,
    private val brightness: Int = 0,
    private val contrast: Int = 0,
    private val saturation: Int = 0,
    private val sharpen: Int = 0,
    private val blur: Int = 0,
    private val filter: String = "none",
) : DataSaver {
    override fun compress(imageUrl: String): String {
        val encoded = URLEncoder.encode(imageUrl, "UTF-8")
        val params = buildString {
            append("url=$encoded&q=$quality&f=$format")
            if (maxWidth > 0) append("&w=$maxWidth")
            if (maxHeight > 0) append("&h=$maxHeight")
            if (fitMode != "inside") append("&fit=$fitMode")
            if (noUpscale) append("&we=1")
            if (brightness != 0) append("&bri=$brightness")
            if (contrast != 0) append("&con=$contrast")
            if (saturation != 0) append("&sat=$saturation")
            if (sharpen > 0) append("&sharp=$sharpen")
            if (blur > 0) append("&blur=$blur")
            if (filter != "none") append("&filter=$filter")
            apiKey?.let { append("&key=$it") }
        }
        return "$serverUrl/image?$params"
    }
}

/**
 * Proxy via wsrv.nl: routes images through a custom server, then applies
 * wsrv.nl processing on the proxied URL. This hides the original image
 * origin from wsrv.nl.
 */
class ProxyDataSaver(
    private val serverUrl: String,
    private val apiKey: String? = null,
    private val quality: Int = 80,
    private val format: String = "webp",
    private val maxWidth: Int = 0,
    private val maxHeight: Int = 0,
    private val fitMode: String = "inside",
    private val noUpscale: Boolean = true,
    private val brightness: Int = 0,
    private val contrast: Int = 0,
    private val saturation: Int = 0,
    private val sharpen: Int = 0,
    private val blur: Int = 0,
    private val filter: String = "none",
) : DataSaver {
    override fun compress(imageUrl: String): String {
        // Build the proxy URL that fetches the original via our custom server
        val proxyParams = buildString {
            append("url=").append(URLEncoder.encode(imageUrl, "UTF-8"))
            append("&f=original")
            apiKey?.let { append("&key=$it") }
        }
        val proxyUrl = "$serverUrl/image?$proxyParams"

        // Now pass the proxy URL through wsrv.nl for image processing
        val encodedProxy = URLEncoder.encode(proxyUrl, "UTF-8")
        val wsrvParams = buildWsrvParams(
            quality, format, maxWidth, maxHeight, fitMode, noUpscale,
            brightness, contrast, saturation, sharpen, blur, filter,
        )
        return "https://wsrv.nl/?url=$encodedProxy$wsrvParams"
    }
}

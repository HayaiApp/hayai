package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Lets [okhttp3.brotli.BrotliInterceptor] handle both gzip and Brotli responses.
 *
 * OkHttp's bridge interceptor adds `Accept-Encoding: gzip` before network interceptors run.
 * Removing that automatically-added value lets the Brotli interceptor advertise and decode
 * every encoding it supports without double-decoding gzip responses.
 */
class IgnoreGzipInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().let { request ->
            if (request.header("Accept-Encoding") == "gzip") {
                request.newBuilder().removeHeader("Accept-Encoding").build()
            } else {
                request
            }
        }
        return chain.proceed(request)
    }
}

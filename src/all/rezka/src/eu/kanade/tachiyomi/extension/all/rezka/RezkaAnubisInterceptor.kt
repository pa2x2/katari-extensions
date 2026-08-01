package eu.kanade.tachiyomi.extension.all.rezka

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.interceptor.WebViewInterceptor
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch

internal class RezkaAnubisInterceptor(
    context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        if (response.request.url.host != REZKA_HOST || response.code != 200) return false
        if (response.body.contentType()?.let { it.type == "text" && it.subtype == "html" } != true) return false

        val body = response.peekBody(CHALLENGE_PEEK_BYTES).string()
        return ANUBIS_CHALLENGE_MARKERS.all(body::contains)
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response = synchronized(challengeLock) {
        response.close()

        if (!hasAuthCookie(request)) {
            cookieManager.remove(request.url, ANUBIS_COOKIE_NAMES, 0)
            resolveWithWebView(request)
        }

        chain.proceed(request)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request) {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var bypassed = false
        var pageError: String? = null

        executor.execute {
            webView = createWebView(request).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (hasAuthCookie(request)) {
                            bypassed = true
                            latch.countDown()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            pageError = error.description?.toString()
                            latch.countDown()
                        }
                    }
                }
                loadUrl(request.url.toString(), parseHeaders(request.headers))
            }
        }

        latch.awaitFor30Seconds()

        executor.execute {
            webView?.run {
                stopLoading()
                destroy()
            }
        }

        if (!bypassed) {
            throw IOException(
                pageError?.let { "Rezka anti-bot verification failed: $it" }
                    ?: "Rezka anti-bot verification timed out",
            )
        }
    }

    private fun hasAuthCookie(request: Request): Boolean {
        return cookieManager.get(request.url).any { it.name == ANUBIS_AUTH_COOKIE }
    }

    private companion object {
        const val REZKA_HOST = "rezka.ag"
        const val ANUBIS_AUTH_COOKIE = "techaro.lol-anubis-auth"
        const val CHALLENGE_PEEK_BYTES = 64L * 1024L

        val ANUBIS_COOKIE_NAMES = listOf(
            ANUBIS_AUTH_COOKIE,
            "techaro.lol-anubis-cookie-verification",
        )
        val ANUBIS_CHALLENGE_MARKERS = listOf(
            "id=\"anubis_version\"",
            "id=\"anubis_challenge\"",
        )
        val challengeLock = Any()
    }
}

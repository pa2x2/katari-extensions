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
import okhttp3.Cookie
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

        // Another request may have passed the check while this one waited for the lock.
        if (hasAuthCookie(request)) {
            val retryResponse = chain.proceed(request)
            if (!shouldIntercept(retryResponse)) return retryResponse
            retryResponse.close()
        }

        clearAnubisCookies(request)
        resolveWithWebView(request)

        val retryResponse = chain.proceed(request)
        if (shouldIntercept(retryResponse)) {
            retryResponse.close()
            throw challengeRequired("the check was still shown after it passed")
        }
        retryResponse
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
            throw challengeRequired(pageError ?: "timed out")
        }
    }

    // Anubis suffixes cookie names with a per-deployment hash, e.g. techaro.lol-anubis-auth-d82da6a5.
    private fun hasAuthCookie(request: Request): Boolean {
        return cookieManager.get(request.url).any { it.name.startsWith(ANUBIS_AUTH_COOKIE) }
    }

    private fun clearAnubisCookies(request: Request) {
        val expiredCookies = cookieManager.get(request.url)
            .filter { it.name.startsWith(ANUBIS_COOKIE_PREFIX) }
            .map { cookie ->
                Cookie.Builder()
                    .name(cookie.name)
                    .value("")
                    .hostOnlyDomain(request.url.host)
                    .path("/")
                    .expiresAt(0)
                    .build()
            }
        if (expiredCookies.isNotEmpty()) {
            cookieManager.saveFromResponse(request.url, expiredCookies)
        }
    }

    private fun challengeRequired(reason: String): IOException {
        return IOException(
            "Rezka anti-bot check could not be passed automatically ($reason). " +
                "Open the title in WebView, wait until the site loads, then try again.",
        )
    }

    private companion object {
        const val REZKA_HOST = "rezka.ag"
        const val ANUBIS_COOKIE_PREFIX = "techaro.lol-anubis-"
        const val ANUBIS_AUTH_COOKIE = "${ANUBIS_COOKIE_PREFIX}auth"
        const val CHALLENGE_PEEK_BYTES = 64L * 1024L

        val ANUBIS_CHALLENGE_MARKERS = listOf(
            "id=\"anubis_version\"",
            "id=\"anubis_challenge\"",
        )
        val challengeLock = Any()
    }
}

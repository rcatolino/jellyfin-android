package org.jellyfin.mobile.webapp

import android.app.Activity
import android.net.Uri
import android.net.http.SslError
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.security.KeyChainException
import android.webkit.ClientCertRequest
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.initLocale
import org.jellyfin.mobile.utils.inject
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.Reader
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class JellyfinWebViewClient(
    private val coroutineScope: CoroutineScope,
    private val server: ServerEntity,
    private val assetsPathHandler: AssetsPathHandler,
    private val apiClientController: ApiClientController,
    private val parent: Activity?,
    private val preferences: AppPreferences,
    private val externalScope: CoroutineScope = GlobalScope,
) : WebViewClientCompat() {

    abstract fun onConnectedToWebapp()

    abstract fun onErrorReceived()

    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val path = url.path?.lowercase(Locale.ROOT) ?: return null
        return when {
            path.matches(Constants.MAIN_BUNDLE_PATH_REGEX) && "deferred" !in url.query.orEmpty() -> {
                onConnectedToWebapp()
                assetsPathHandler.inject("native/injectionScript.js")
            }
            // Load injected scripts from application assets
            path.contains("/native/") -> assetsPathHandler.inject("native/${url.lastPathSegment}")
            // Load the chrome.cast.js library instead
            path.endsWith(Constants.CAST_SDK_PATH) -> assetsPathHandler.inject("native/chrome.cast.js")
            path.endsWith(Constants.SESSION_CAPABILITIES_PATH) -> {
                coroutineScope.launch {
                    val credentials = suspendCoroutine { continuation ->
                        webView.evaluateJavascript("JSON.parse(window.localStorage.getItem('jellyfin_credentials'))") { result ->
                            try {
                                continuation.resume(JSONObject(result))
                            } catch (e: JSONException) {
                                val message = "Failed to extract credentials"
                                Timber.e(e, message)
                                continuation.resumeWithException(Exception(message, e))
                            }
                        }
                    }
                    val storedServer = credentials.getJSONArray("Servers").getJSONObject(0)
                    val user = storedServer.getString("UserId")
                    val token = storedServer.getString("AccessToken")
                    apiClientController.setupUser(server.id, user, token)
                    webView.initLocale(user)
                }
                null
            }
            else -> null
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        val errorMessage = errorResponse.data?.run { bufferedReader().use(Reader::readText) }
        Timber.e("Received WebView HTTP %d error: %s", errorResponse.statusCode, errorMessage)

        if (request.url == Uri.parse(view.url)) onErrorReceived()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceErrorCompat,
    ) {
        val description = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) error.description else null
        val errorCode = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) error.errorCode else ERROR_UNKNOWN
        Timber.e("Received WebView error %d at %s: %s", errorCode, request.url.toString(), description)

        // Abort on some specific error codes or when the request url matches the server url
        when (errorCode) {
            ERROR_HOST_LOOKUP,
            ERROR_CONNECT,
            ERROR_TIMEOUT,
            ERROR_REDIRECT_LOOP,
            ERROR_UNSUPPORTED_SCHEME,
            ERROR_FAILED_SSL_HANDSHAKE,
            -> onErrorReceived()
            else -> if (request.url == Uri.parse(view.url)) onErrorReceived()
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        Timber.e("Received SSL error: %s", error.toString())
        handler.cancel()
        onErrorReceived()
    }

    private fun answerCertRequest(alias: String, view: WebView, request: ClientCertRequest) : Boolean {
        try {
            val chain = KeyChain.getCertificateChain(view.context, alias)
            val privateKey = KeyChain.getPrivateKey(view.context, alias)
            request.proceed(privateKey, chain)
            return true
        } catch (e: KeyChainException) {
            Timber.d("Error getting certificate chain or private key : %s", e)
        } catch (e: InterruptedException) {
            Timber.d("Interrupted while getting certificate chain or private key : %s", e)
        }

        return false
    }

    override fun onReceivedClientCertRequest (view: WebView, request: ClientCertRequest) {
        Timber.d("SSL Client Cert required")
        externalScope.launch {
            var alias = preferences.certificateAlias
            if (alias != null) {
                Timber.d("Using stored alias %s", alias)
                if (!answerCertRequest(alias, view, request)) {
                    preferences.certificateAlias = null
                    alias = null
                }
            }

            if (alias == null) {
                if (parent != null) {
                    KeyChain.choosePrivateKeyAlias(parent, KeyChainAliasCallback {
                        if (it != null) {
                            Timber.d("Using choosen alias %s", it)
                            if (answerCertRequest(it, view, request)) {
                                Timber.d("Storing choosen alias %s for future use", it)
                                preferences.certificateAlias = it
                            } else {
                                request.ignore()
                            }
                        }
                    }, request.getKeyTypes(), request.getPrincipals(), request.getHost(), request.getPort(), null)
                } else {
                    Timber.d("SSL Client cert ignored because parent activity is null")
                    request.ignore()
                }
            }
        }
    }
}

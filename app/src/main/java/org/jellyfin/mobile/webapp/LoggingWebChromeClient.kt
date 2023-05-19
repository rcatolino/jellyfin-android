package org.jellyfin.mobile.webapp

import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import timber.log.Timber

class LoggingWebChromeClient : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest): Unit {
        for (resource in request.getResources()) {
            Timber.tag("WebView").log(Log.DEBUG, "New permission request from %s for %s", request.getOrigin(), resource);
        }

        request.grant(request.getResources().filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toTypedArray());
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val logLevel = when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
            ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
            ConsoleMessage.MessageLevel.TIP -> Log.VERBOSE
            else -> Log.INFO
        }

        Timber.tag("WebView").log(
            logLevel,
            "%s, %s (%d)",
            consoleMessage.message(),
            consoleMessage.sourceId(),
            consoleMessage.lineNumber(),
        )

        return true
    }
}

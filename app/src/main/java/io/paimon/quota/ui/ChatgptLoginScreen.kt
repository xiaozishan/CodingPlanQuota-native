package io.paimon.quota.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.paimon.quota.data.provider.AuthSession
import io.paimon.quota.data.provider.CallbackResult
import io.paimon.quota.data.provider.ChatgptPlusProvider

internal fun chatgptCallbackValidationError(result: CallbackResult, expectedState: String): String? = when {
    !result.isCallback -> result.error.ifBlank { "不是 OpenAI 登录回调" }
    result.error.isNotBlank() -> {
        if (result.error == "access_denied") "已取消 OpenAI 登录"
        else "OpenAI 登录失败：${result.error}"
    }
    result.state != expectedState -> "登录状态校验失败，请重试"
    result.code.isBlank() -> "OpenAI 授权回跳里没有 code"
    else -> null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ChatgptLoginDialog(
    session: AuthSession,
    onCancel: () -> Unit,
    onCode: (String) -> Unit,
    onError: (String) -> Unit,
    busy: Boolean = false,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
) {
    val currentSession by rememberUpdatedState(session)
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnError by rememberUpdatedState(onError)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember(session) { mutableStateOf(true) }
    var callbackHandled by remember(session) { mutableStateOf(false) }
    var localError by remember(session) { mutableStateOf<String?>(null) }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onCancel()
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterStart) {
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                    Text(
                        "OpenAI 登录",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(72.dp))
                }

                if ((loading && !callbackHandled) || busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                Text(
                    if (busy) "正在换取 OpenAI 登录凭据…" else "授权完成后会自动返回应用",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )

                (errorMessage ?: localError)?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                message,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                            )
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    onRetry()
                                    localError = null
                                    callbackHandled = false
                                    loading = true
                                    webView?.loadUrl(currentSession.url)
                                },
                            ) { Text("重试") }
                        }
                    }
                }

                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            fun reportError(message: String) {
                                loading = false
                                localError = message
                                currentOnError(message)
                            }

                            fun handleCallbackUrl(view: WebView, url: String): Boolean {
                                val result = ChatgptPlusProvider.parseCallback(url)
                                if (!result.isCallback) return false
                                view.stopLoading()
                                if (callbackHandled) return true
                                callbackHandled = true
                                val validationError = chatgptCallbackValidationError(
                                    result,
                                    currentSession.state,
                                )
                                if (validationError != null) {
                                    reportError(validationError)
                                } else {
                                    localError = null
                                    loading = true
                                    currentOnCode(result.code)
                                }
                                return true
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean = handleCallbackUrl(view, request.url.toString())

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                                    handleCallbackUrl(view, url)

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (!handleCallbackUrl(view, url) && !callbackHandled) loading = true
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (!callbackHandled) loading = false
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (!request.isForMainFrame || callbackHandled) return
                                    if (handleCallbackUrl(view, request.url.toString())) return
                                    reportError("网页加载失败（${error.errorCode}）：${error.description}")
                                }
                            }

                            loadUrl(currentSession.url)
                            webView = this
                        }
                    },
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }
}

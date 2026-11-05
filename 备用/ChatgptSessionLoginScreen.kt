// 备用源码：不参与构建。0.5.5 的 WebView Session Cookie 抓取实现。
package io.paimon.quota.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import org.json.JSONObject

private const val CHATGPT_HOME = "https://chatgpt.com/"
private const val SESSION_BRIDGE = "QuotaChatgptSession"

private class ChatgptSessionBridge(
    val onCaptured: (String) -> Unit,
    val onAnonymous: () -> Unit,
    val onFailure: (String) -> Unit,
) {
    @JavascriptInterface
    fun onSession(raw: String) = onCaptured(raw)

    @JavascriptInterface
    fun onAnonymousSession() = onAnonymous()

    @JavascriptInterface
    fun onError(message: String) = onFailure(message)
}

private fun validChatgptSession(raw: String): Boolean {
    val body = runCatching { JSONObject(raw) }.getOrNull() ?: return false
    return body.optJSONObject("user") != null || body.optString("accessToken").isNotBlank()
}

private fun isChatgptPage(view: WebView): Boolean =
    Uri.parse(view.url ?: CHATGPT_HOME).host.equals("chatgpt.com", ignoreCase = true)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ChatgptLoginDialog(
    onCancel: () -> Unit,
    onSession: (String) -> Unit,
) {
    val currentOnSession by rememberUpdatedState(onSession)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("完成网页登录后会自动读取 Session JSON") }

    fun captureSession(view: WebView) {
        if (capturing || completed || !isChatgptPage(view)) return
        capturing = true
        loading = true
        localError = null
        status = "正在读取登录会话…"

        view.evaluateJavascript(
            """
            (function() {
              if (location.origin !== 'https://chatgpt.com') return;
              fetch('/api/auth/session', {
                method: 'GET',
                credentials: 'include',
                headers: { 'Accept': 'application/json' }
              }).then(function(response) {
                return response.text().then(function(raw) {
                  if (!response.ok) throw new Error('HTTP ' + response.status);
                  var body = JSON.parse(raw);
                  if (body && (body.user || body.accessToken)) {
                    $SESSION_BRIDGE.onSession(raw);
                  } else {
                    $SESSION_BRIDGE.onAnonymousSession();
                  }
                });
              }).catch(function(error) {
                $SESSION_BRIDGE.onError(String(error && error.message ? error.message : error));
              });
            })();
            """.trimIndent(),
            null,
        )
    }

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
                    Box(Modifier.width(88.dp), contentAlignment = Alignment.CenterStart) {
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                    Text(
                        "ChatGPT 网页登录",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Box(Modifier.width(88.dp), contentAlignment = Alignment.CenterEnd) {
                        TextButton(
                            enabled = !capturing && !completed,
                            onClick = { webView?.let(::captureSession) },
                        ) { Text("读取会话") }
                    }
                }

                if (loading || capturing) LinearProgressIndicator(Modifier.fillMaxWidth())

                Text(
                    status,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )

                localError?.let { message ->
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
                                enabled = !capturing && !completed,
                                onClick = { webView?.let(::captureSession) },
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
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            fun captured(raw: String) {
                                post {
                                    capturing = false
                                    loading = false
                                    if (completed || !isChatgptPage(this)) return@post
                                    if (validChatgptSession(raw)) {
                                        completed = true
                                        localError = null
                                        status = "登录成功，已获取 Session JSON"
                                        CookieManager.getInstance().flush()
                                        currentOnSession(raw)
                                    } else {
                                        status = "请在网页中完成登录"
                                    }
                                }
                            }

                            fun anonymous() {
                                post {
                                    capturing = false
                                    loading = false
                                    if (!completed) status = "未检测到登录，请在网页中完成登录"
                                }
                            }

                            fun failed(message: String) {
                                post {
                                    capturing = false
                                    loading = false
                                    if (completed) return@post
                                    status = "自动读取失败，可点右上角重试"
                                    localError = "Session 读取失败：$message"
                                }
                            }

                            addJavascriptInterface(
                                ChatgptSessionBridge(::captured, ::anonymous, ::failed),
                                SESSION_BRIDGE,
                            )

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (!capturing && !completed) loading = true
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (completed) return
                                    loading = false
                                    if (Uri.parse(url).host.equals("chatgpt.com", ignoreCase = true)) {
                                        captureSession(view)
                                    } else if (!capturing) {
                                        status = "完成网页登录后会自动读取 Session JSON"
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (!request.isForMainFrame || completed) return
                                    loading = false
                                    localError = "网页加载失败（${error.errorCode}）：${error.description}"
                                }
                            }

                            loadUrl(CHATGPT_HOME)
                            webView = this
                        }
                    },
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.removeJavascriptInterface(SESSION_BRIDGE)
            webView?.destroy()
        }
    }
}

package io.paimon.quota.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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

/**
 * 阿里云百炼控制台登录：在应用内打开真实登录页，登录完成后自动抓取会话 Cookie。
 *
 * Cookie 只回传给调用方并写入加密 Prefs，不上报任何服务器。
 */
private val QWEN_LOGIN_HOSTS = listOf(".aliyun.com", ".alibabacloud.com", ".taobao.com", ".tmall.com")

private val QWEN_LOGIN_COOKIE_MARKERS = listOf(
    "login_aliyunid_cn",
    "login_aliyunid_ticket",
    "cookie2",
    "login_aliyunid_pk",
)

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val ALTERNATE_LOGIN_URL = "https://account.aliyun.com/login/login.htm"

internal fun qwenCookieLooksLoggedIn(cookie: String?): Boolean {
    if (cookie.isNullOrBlank()) return false
    return QWEN_LOGIN_COOKIE_MARKERS.any { cookie.contains(it) }
}

private fun qwenHostMatches(url: String?): Boolean {
    val host = runCatching { java.net.URI(url ?: "").host.orEmpty() }.getOrNull().orEmpty()
    if (host.isBlank()) return false
    return QWEN_LOGIN_HOSTS.any { host.endsWith(it) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun QwenLoginDialog(
    startUrl: String,
    onCancel: () -> Unit,
    onCookie: (cookie: String, url: String) -> Unit,
) {
    val currentOnCookie by rememberUpdatedState(onCookie)
    var loading by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(startUrl) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onCancel()
    }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = Runnable { timedOut = true }
        handler.postDelayed(runnable, 90_000)
        onDispose { handler.removeCallbacks(runnable) }
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
                        TextButton(onClick = onCancel, shape = UiShapes.Control) { Text("取消") }
                    }
                    Text(
                        "阿里云登录",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(72.dp))
                }

                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

                Text(
                    pageError?.let { "加载失败：$it" }
                        ?: if (timedOut) "还没检测到登录，继续操作即可；也可以返回手动粘贴 Cookie"
                        else "登录完成后会自动保存登录状态并返回",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    color = if (pageError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 12.sp,
                )

                Text(
                    currentUrl,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )

                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.userAgentString = DESKTOP_UA
                            settings.loadsImagesAutomatically = true
                            settings.mixedContentMode =
                                android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                    currentUrl = url.orEmpty()
                                    if (done) return
                                    if (!qwenHostMatches(url)) return
                                    val cookie = CookieManager.getInstance().getCookie(url)
                                    if (qwenCookieLooksLoggedIn(cookie) && !cookie.isNullOrBlank()) {
                                        done = true
                                        currentOnCookie(cookie, url.orEmpty())
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        pageError = error?.description?.toString() ?: "网络错误"
                                    }
                                }
                            }
                            loadUrl(startUrl)
                            webView = this
                        }
                    },
                    update = { view -> webView = view },
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        shape = UiShapes.Control,
                        onClick = {
                            done = false
                            timedOut = false
                            pageError = null
                            loading = true
                            webView?.loadUrl(startUrl)
                        },
                    ) { Text("重新加载") }
                    TextButton(
                        shape = UiShapes.Control,
                        onClick = {
                            done = false
                            timedOut = false
                            pageError = null
                            loading = true
                            webView?.loadUrl(ALTERNATE_LOGIN_URL)
                        },
                    ) { Text("换登录页") }
                    TextButton(shape = UiShapes.Control, onClick = onCancel) {
                        Text("手动粘贴 Cookie")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "只保存在本机",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

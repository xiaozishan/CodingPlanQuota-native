package io.paimon.quota.data.provider

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.paimon.quota.data.QuotaResult
import io.paimon.quota.data.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 网页版 Gemini（Google AI Pro）额度读取。
 *
 * 登录弹窗与后台读取共用 [webView]，因此 Google 登录后的内存会话与 Cookie 都会保留。
 * 只解析 gemini.google.com 页面；失败时绝不回退到 Cloud Code 冒充网页订阅额度。
 */
@SuppressLint("SetJavaScriptEnabled")
internal object GeminiWebQuota {
    private const val USAGE_URL = "https://gemini.google.com/usage"
    private val CURRENT_USAGE_HEADING = Regex("当前用量|current\\s+usage", RegexOption.IGNORE_CASE)
    private val WEEKLY_LIMIT_HEADING = Regex("每周限额|weekly\\s+limit", RegexOption.IGNORE_CASE)
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var webView: WebView? = null

    /** Application 启动时只保存 applicationContext，不提前创建 WebView。 */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** 登录弹窗调用；后续额度查询复用返回的同一实例。 */
    fun obtainWebView(context: Context): WebView {
        initialize(context)
        return getOrCreateWebView()
    }

    private fun getOrCreateWebView(): WebView {
        webView?.let { return it }
        val context = appContext ?: throw IllegalStateException("网页会话尚未初始化")
        return WebView(context).also { view ->
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            webView = view
        }
    }

    suspend fun fetch(): QuotaResult = mutex.withLock {
        try {
            withContext(Dispatchers.Main) {
                val view = getOrCreateWebView()
                CookieManager.getInstance().flush()
                withTimeout(35_000L) { loadGemini(view) }
                if (view.url?.contains("accounts.google.com", ignoreCase = true) == true) {
                    return@withContext QuotaResult(
                        false,
                        error = "Gemini 网页端未登录",
                    )
                }

                // /usage 是独立页面；onPageFinished 后轮询等待动态面板渲染完成。
                var domRaw = ""
                repeat(7) { attempt ->
                    delay(if (attempt == 0) 1_800L else 1_000L)
                    domRaw = evaluate(view, EXTRACT_DOM)
                    val parsed = parseWebResult(domRaw)
                    if (parsed.ok) return@withContext parsed
                }

                QuotaResult(
                    false,
                    error = "未登录或页面未加载",
                    raw = domRaw.take(20_000),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QuotaResult(false, error = "页面加载失败，请稍后重试")
        }
    }

    private suspend fun loadGemini(view: WebView) = suspendCancellableCoroutine<Unit> { continuation ->
        view.webViewClient = object : WebViewClient() {
            private var resumed = false

            fun finish(url: String?) {
                if (resumed || !continuation.isActive) return
                val isGemini = url?.contains("gemini.google.com", ignoreCase = true) == true
                val isLogin = url?.contains("accounts.google.com", ignoreCase = true) == true
                if (!isGemini && !isLogin) return
                resumed = true
                continuation.resume(Unit)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = Unit

            override fun onPageFinished(view: WebView, url: String) = finish(url)

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame || resumed || !continuation.isActive) return
                resumed = true
                continuation.resumeWithException(
                    IllegalStateException("网页加载失败（${error.errorCode}）：${error.description}")
                )
            }
        }
        continuation.invokeOnCancellation { view.post { view.stopLoading() } }
        view.loadUrl(USAGE_URL)
    }

    private suspend fun evaluate(view: WebView, script: String): String = withTimeout(10_000L) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result.orEmpty())
            }
        }
    }

    /** evaluateJavascript 会把 JSON.stringify 的字符串再包一层引号，先解码。 */
    private fun decodeJavascriptString(raw: String): String? {
        if (raw.isBlank() || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
    }

    private fun parseWebResult(raw: String): QuotaResult {
        val decoded = decodeJavascriptString(raw) ?: return QuotaResult(false)
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return QuotaResult(false)
        val pageText = json.optString("pageText")
        val segmented = parseWindowTiers(pageText)
        if (segmented != null) {
            return QuotaResult(
                ok = true,
                tiers = segmented,
                raw = decoded.take(20_000),
            )
        }
        // 标准分段已出现但数值尚未渲染时，继续轮询；不得让候选文本抢用其他窗口的百分比。
        if (CURRENT_USAGE_HEADING.containsMatchIn(pageText) || WEEKLY_LIMIT_HEADING.containsMatchIn(pageText)) {
            return QuotaResult(false)
        }
        val candidates = mutableListOf<String>()
        json.optJSONArray("candidates")?.let { array ->
            for (i in 0 until array.length()) {
                val text = array.optString(i).trim().replace(Regex("\\s+"), " ")
                if (text.isNotBlank()) candidates.add(text)
            }
        }

        val parsed = candidates.mapNotNull { candidate -> parseCandidate(candidate) }
        val fiveHour = selectWindow(parsed, "5 小时窗口")
        val weekly = selectWindow(parsed, "周额度")
        if (fiveHour == null && weekly == null) return QuotaResult(false)

        // 网页版的两个口径始终分开；页面只给一个时，另一个明确标为未展示。
        val tiers = listOf(
            fiveHour ?: Tier("5 小时窗口", 0.0, valueText = "未展示"),
            weekly ?: Tier("周额度", 0.0, valueText = "未展示"),
        )
        return QuotaResult(
            ok = true,
            tiers = tiers,
            raw = decoded.take(20_000),
        )
    }

    /** 5 小时窗口只读「当前用量」段，周额度只读「每周限额」段。 */
    private fun parseWindowTiers(pageText: String): List<Tier>? {
        if (pageText.isBlank()) return null
        val currentStart = CURRENT_USAGE_HEADING.find(pageText)?.range?.first
        val weeklyStart = WEEKLY_LIMIT_HEADING.find(pageText)?.range?.first
        if (currentStart == null && weeklyStart == null) return null

        fun section(start: Int, otherStart: Int?): String {
            val end = if (otherStart != null && otherStart > start) otherStart else pageText.length
            return pageText.substring(start, end)
        }
        val current = currentStart?.let { start ->
            parseWindowSection(section(start, weeklyStart), "5 小时窗口")
        }
        val weekly = weeklyStart?.let { start ->
            parseWindowSection(section(start, currentStart), "周额度")
        }
        if (current == null && weekly == null) return null
        return listOf(
            current ?: Tier("5 小时窗口", 0.0, valueText = "未展示"),
            weekly ?: Tier("周额度", 0.0, valueText = "未展示"),
        )
    }

    private fun parseWindowSection(section: String, label: String): Tier? {
        // find() 从区段起点向后找，因此不会拿到另一个窗口的全局第一个百分比。
        val used = Regex(
            "(?:已使用|已用|used)\\s*(\\d+(?:\\.\\d+)?)\\s*%",
            RegexOption.IGNORE_CASE,
        ).find(section)
        val reset = Regex(
            "(?:重置时间|重置|reset(?:\\s+time)?)\\s*[:：]?\\s*([^\\r\\n]+)",
            RegexOption.IGNORE_CASE,
        ).find(section)?.groupValues?.get(1)?.trim()
            ?.substringBefore(" 已使用")?.substringBefore(" 每周限额")?.take(80)
        if (used != null) {
            return Tier(
                label,
                used.groupValues[1].toDouble().coerceIn(0.0, 100.0),
                resetAt = reset?.ifBlank { null },
            )
        }
        val usedAndRemaining = Regex(
            "(?:已使用|已用|used)\\s*\\d+(?:\\.\\d+)?\\s*(?:次|requests?)?\\s*[/／,，;；]\\s*" +
                "(?:剩余|remaining|left)\\s*\\d+(?:\\.\\d+)?",
            RegexOption.IGNORE_CASE,
        ).find(section)
        if (usedAndRemaining != null) {
            return Tier(label, 0.0, resetAt = reset?.ifBlank { null }, valueText = usedAndRemaining.value)
        }
        val count = countQuotaText(section)
        if (count != null) return Tier(label, 0.0, resetAt = reset?.ifBlank { null }, valueText = count)
        return null
    }

    private fun selectWindow(parsed: List<Tier>, label: String): Tier? {
        val options = parsed.filter { it.label == label }
        // 同一窗口出现嵌套 DOM 的重复文本时，优先使用能算出比例的项。
        return options.firstOrNull { it.valueText == null } ?: options.firstOrNull()
    }

    private fun parseCandidate(text: String): Tier? {
        val label = when {
            Regex("5[\\s-]*(?:小时|hours?)|five[\\s-]*hours?", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) -> "5 小时窗口"
            Regex("周|每周|week|7[\\s-]*(?:天|days?)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) -> "周额度"
            else -> return null
        }
        val usedOverRemaining = Regex(
            "(?:已用|used)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:次|requests?)?\\s*[/／,，;；]\\s*" +
                "(?:剩余|remaining|left)\\s*(\\d+(?:\\.\\d+)?)",
            RegexOption.IGNORE_CASE,
        ).find(text)
        if (usedOverRemaining != null) {
            // 用户要求「已用 x / 剩余 y」按原文展示，不换算成百分比。
            return Tier(label, 0.0, valueText = usedOverRemaining.value)
        }

        val usedOfTotal = Regex(
            "(?:已用|used)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:/|／|of|共)\\s*(\\d+(?:\\.\\d+)?)",
            RegexOption.IGNORE_CASE,
        ).find(text)
        if (usedOfTotal != null) {
            val used = usedOfTotal.groupValues[1].toDouble()
            val total = usedOfTotal.groupValues[2].toDouble()
            if (total > 0.0) return Tier(label, (used / total * 100.0).coerceIn(0.0, 100.0))
        }

        val remainingPercent = Regex(
            "(?:remaining|left|剩余|还能用|可用)[^0-9%]{0,30}(\\d+(?:\\.\\d+)?)\\s*%",
            RegexOption.IGNORE_CASE,
        ).find(text)
        if (remainingPercent != null) {
            val remaining = remainingPercent.groupValues[1].toDouble()
            return Tier(label, (100.0 - remaining).coerceIn(0.0, 100.0))
        }

        val usedPercent = Regex(
            "(?:used|utilization|已用|使用)[^0-9%]{0,30}(\\d+(?:\\.\\d+)?)\\s*%",
            RegexOption.IGNORE_CASE,
        ).find(text)
        if (usedPercent != null) {
            return Tier(label, usedPercent.groupValues[1].toDouble().coerceIn(0.0, 100.0))
        }

        val jsonRemainingFraction = Regex(
            "[\"']?remaining[_]?(?:fraction|ratio)[\"']?\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)",
            RegexOption.IGNORE_CASE,
        ).find(text)
        if (jsonRemainingFraction != null) {
            val remaining = jsonRemainingFraction.groupValues[1].toDouble()
            if (remaining in 0.0..1.0) return Tier(label, (1.0 - remaining) * 100.0)
        }
        val jsonRemainingPercent = Regex(
            "[\"']?remaining[_]?(?:percent|percentage)[\"']?\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)",
            RegexOption.IGNORE_CASE,
        ).find(text)
        if (jsonRemainingPercent != null) {
            val remaining = jsonRemainingPercent.groupValues[1].toDouble()
            return Tier(label, (100.0 - remaining).coerceIn(0.0, 100.0))
        }
        val jsonUsedPercent = Regex(
            "[\"']?(?:used|usage|utilization)[_]?(?:percent|percentage)[\"']?\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)",
            RegexOption.IGNORE_CASE,
        ).find(text)
        if (jsonUsedPercent != null) {
            return Tier(label, jsonUsedPercent.groupValues[1].toDouble().coerceIn(0.0, 100.0))
        }
        // 不把窗口文字里的第一个裸百分比当作已用量：它可能属于另一个窗口。
        // 「当前用量 / 每周限额」的标准页面已由 parseWindowTiers 分段解析。
        // 只有剩余次数时没有总量，保留次数文本，不伪造 usedPct。
        return countQuotaText(text)?.let { Tier(label, 0.0, valueText = it) }
    }

    private fun countQuotaText(text: String): String? = Regex(
        "(?:还能用|剩余|remaining|left)[^0-9]{0,24}\\d+(?:\\.\\d+)?\\s*(?:次|条|requests?|prompts?|messages?)|" +
            "\\d+(?:\\.\\d+)?\\s*(?:次|requests?|prompts?|messages?)\\s*(?:剩余|可用|remaining|left)",
        RegexOption.IGNORE_CASE,
    ).find(text)?.value

    private const val EXTRACT_DOM = """
(function() {
  var out = [];
  function add(s) {
    if (!s) return;
    var t = String(s).replace(/\s+/g, ' ').trim();
    if (t.length < 4 || t.length > 1000 || out.indexOf(t) >= 0) return;
    if (!/\d/.test(t)) return;
    if (!/(quota|usage|limit|remaining|left|used|reset|hour|week|小时|周|使用上限|使用限制|额度|剩余|已用|还能用|重置)/i.test(t)) return;
    out.push(t);
  }
  var roots = document.querySelectorAll('[role="dialog"], [role="menu"], aside, section, div, span, p, button');
  for (var i = 0; i < roots.length; i++) {
    var el = roots[i];
    if (el.offsetWidth === 0 && el.offsetHeight === 0) continue;
    var children = el.children ? el.children.length : 0;
    if (children < 12) add(el.innerText || el.textContent);
  }
  var lines = (document.body.innerText || '').split('\n');
  for (var j = 0; j < lines.length; j++) add(lines[j]);
  var page = document.body.innerText || '';
  return JSON.stringify({url: location.href, pageText: page.slice(0, 20000), candidates: out.slice(0, 80)});
})()
"""

}

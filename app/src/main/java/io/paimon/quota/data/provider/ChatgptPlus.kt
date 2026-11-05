package io.paimon.quota.data.provider

import android.util.Base64
import io.paimon.quota.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * ChatGPT Plus：主路径使用 OpenAI OAuth + PKCE，查询 Codex 同款 wham/usage。
 * 旧版手贴 session JSON 仍保留，按套餐与续费日换算订阅周期倒计时。
 *
 * OAuth 的 access_token 存在 [Provider.token]，refresh_token 存在 extra；
 * 两者都会由 [io.paimon.quota.data.Vault] 加密落盘。
 */
internal object ChatgptPlusProvider {

    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val AUTH_URL = "https://auth.openai.com/oauth/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    private const val REDIRECT_URI = "http://localhost:1455/auth/callback"
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
    private const val SCOPE = "openid profile email offline_access"
    private const val DAY_MS = 86_400_000L

    private val random = SecureRandom()

    /** 创建一次独立的 OpenAI OAuth + PKCE 登录会话。 */
    fun buildAuthUrl(): AuthSession {
        val verifier = randomUrlSafe(32)
        val state = randomUrlSafe(24)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val url = AUTH_URL + "?" + form(
            mapOf(
                "client_id" to CLIENT_ID,
                "response_type" to "code",
                "redirect_uri" to REDIRECT_URI,
                "scope" to SCOPE,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "state" to state,
            )
        )
        return AuthSession(url, verifier, state)
    }

    /** 只识别 localhost:1455/auth/callback；state 校验由持有会话的 UI 完成。 */
    fun parseCallback(url: String): CallbackResult {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return CallbackResult(false, "", "", "回调链接格式不正确")
        val matches = uri.scheme?.equals("http", ignoreCase = true) == true &&
            uri.host?.equals("localhost", ignoreCase = true) == true &&
            uri.port == 1455 &&
            uri.path?.equals("/auth/callback", ignoreCase = true) == true
        if (!matches) return CallbackResult(false, "", "", "不是 OpenAI 登录回调")
        val params = parseQuery(uri.rawQuery)
        return CallbackResult(
            isCallback = true,
            code = params["code"].orEmpty(),
            state = params["state"].orEmpty(),
            error = params["error"].orEmpty(),
        )
    }

    /** 授权码换 access_token + refresh_token，并组装成可保存的供应商。 */
    suspend fun exchangeCode(code: String, codeVerifier: String): Provider = withContext(Dispatchers.IO) {
        val response = Http.post(
            TOKEN_URL,
            form(
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to CLIENT_ID,
                    "redirect_uri" to REDIRECT_URI,
                    "code" to code,
                    "code_verifier" to codeVerifier,
                )
            ),
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        val body = requireJson("换取 OpenAI 登录凭据", response)
        val accessToken = body.optString("access_token")
        val refreshToken = body.optString("refresh_token")
        if (accessToken.isBlank()) throw IllegalStateException("换取登录凭据成功，但返回里没有 access_token")
        if (refreshToken.isBlank()) throw IllegalStateException("换取登录凭据成功，但返回里没有 refresh_token")

        Provider(
            id = UUID.randomUUID().toString(),
            kind = ProviderKind.CHATGPT_PLUS,
            name = ProviderKind.CHATGPT_PLUS.label,
            token = accessToken,
            extra = buildMap {
                put("refresh_token", refreshToken)
                put("expiresAt", expiryFrom(body).toString())
                accountIdFromIdToken(body.optString("id_token"))?.let { put("accountId", it) }
            },
        )
    }

    suspend fun fetch(
        p: Provider,
        onProviderUpdated: (Provider) -> Unit = {},
    ): QuotaResult {
        if (p.token.isNotBlank()) return fetchOAuth(p, onProviderUpdated)
        return fetchLegacySession(p)
    }

    /** OAuth 主路径：访问令牌过期时刷新，401 时再刷新并重试一次。 */
    private suspend fun fetchOAuth(
        p: Provider,
        onProviderUpdated: (Provider) -> Unit,
    ): QuotaResult {
        var current = p
        var refreshed = false
        val expiresAt = p.extra["expiresAt"]?.toLongOrNull()
        if (p.extra["refresh_token"].orEmpty().isNotBlank() &&
            expiresAt != null && expiresAt <= System.currentTimeMillis() + 60_000L
        ) {
            current = refresh(current)
            onProviderUpdated(current)
            refreshed = true
        }

        var response = usage(current)
        if (response.code == 401 && !refreshed && current.extra["refresh_token"].orEmpty().isNotBlank()) {
            current = refresh(current)
            onProviderUpdated(current)
            response = usage(current)
        }
        return parseWhamUsage(response)
    }

    private fun refresh(p: Provider): Provider {
        val oldRefreshToken = p.extra["refresh_token"].orEmpty()
        if (oldRefreshToken.isBlank()) throw IllegalStateException("OpenAI 登录已过期，请重新登录")
        val response = Http.post(
            TOKEN_URL,
            form(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to CLIENT_ID,
                    "refresh_token" to oldRefreshToken,
                )
            ),
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        val body = requireJson("刷新 OpenAI 登录态", response)
        val accessToken = body.optString("access_token")
        if (accessToken.isBlank()) throw IllegalStateException("刷新登录态失败：返回里没有 access_token")
        val updatedExtra = p.extra.toMutableMap().apply {
            put("refresh_token", body.optString("refresh_token").ifBlank { oldRefreshToken })
            put("expiresAt", expiryFrom(body).toString())
            accountIdFromIdToken(body.optString("id_token"))?.let { put("accountId", it) }
        }
        return p.copy(token = accessToken, extra = updatedExtra)
    }

    private fun usage(p: Provider): HttpResp = Http.get(
        USAGE_URL,
        bearer(p.token) + mapOf(
            "User-Agent" to "CodingPlanQuota",
            "Accept" to "application/json",
        ) + p.extra["accountId"]?.takeIf { it.isNotBlank() }
            ?.let { mapOf("ChatGPT-Account-Id" to it) }.orEmpty(),
    )

    private fun parseWhamUsage(r: HttpResp): QuotaResult {
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
        val tiers = mutableListOf<Tier>()

        body.optJSONObject("rate_limit")?.let { rateLimit ->
            rateLimit.optJSONObject("primary_window")?.let { window ->
                toDouble(window.opt("used_percent"))?.let {
                    tiers.add(Tier("5 小时", it.coerceIn(0.0, 100.0), resetText(window.opt("reset_at"))))
                }
            }
            rateLimit.optJSONObject("secondary_window")?.let { window ->
                toDouble(window.opt("used_percent"))?.let {
                    tiers.add(Tier("本周", it.coerceIn(0.0, 100.0), resetText(window.opt("reset_at"))))
                }
            }
        }
        if (tiers.isEmpty()) return QuotaResult(false, error = "无 rate_limit 窗口")

        val message = buildString {
            body.optString("plan_type").takeIf { it.isNotBlank() }?.let { append(it) }
            body.optJSONObject("credits")?.let { credits ->
                when {
                    credits.optBoolean("unlimited", false) ->
                        append(if (isEmpty()) "" else " · ").append("不限量")

                    credits.has("balance") ->
                        append(if (isEmpty()) "" else " · ")
                            .append("Credits $")
                            .append(String.format(Locale.US, "%.2f", credits.optDouble("balance", 0.0)))
                }
            }
        }
        return QuotaResult(true, tiers = tiers, message = message.ifBlank { null }, raw = r.body)
    }

    /** 旧版兼容：不发请求，解析用户手贴的 session JSON。 */
    private fun fetchLegacySession(p: Provider): QuotaResult {
        val raw = p.extra["sessionJson"].orEmpty()
        if (raw.isBlank()) return QuotaResult(false, error = "请使用 OpenAI 登录，或粘贴旧版 session JSON")

        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: return QuotaResult(false, error = "session JSON 解析失败")

        var planName = "Plus"
        var renewal = ""
        var yearly = false

        body.optJSONObject("plan")?.let { plan ->
            plan.optString("title").takeIf { it.isNotBlank() }?.let { planName = it }
                ?: plan.optString("id").takeIf { it.isNotBlank() }?.let { planName = it }
            renewal = plan.optString("renewalDate")
            yearly = plan.optString("interval").equals("year", true)
        }
        if (renewal.isBlank()) renewal = body.optString("renewalDate")

        val periodDays = if (yearly) 365L else 30L

        if (renewal.isBlank()) return QuotaResult(true, message = planName, raw = raw)

        val renewMs = parseDate(renewal) ?: return QuotaResult(false, error = "续费日期格式不认识：$renewal")
        val nextRenewal = renewMs + periodDays * DAY_MS
        val now = System.currentTimeMillis()
        val remaining = (nextRenewal - now).coerceAtLeast(0L)
        val utilization = if (nextRenewal <= now) 100.0
        else ((1.0 - remaining.toDouble() / (periodDays * DAY_MS)) * 100.0).coerceIn(0.0, 100.0)
        val days = remaining / DAY_MS

        val tier = Tier("订阅周期", utilization, resetText(nextRenewal / 1000))
        return QuotaResult(true, tiers = listOf(tier), message = "$planName · 还剩 $days 天", raw = raw)
    }

    private fun expiryFrom(body: JSONObject): Long {
        val seconds = body.optLong("expires_in", 3600L).coerceAtLeast(1L)
        return System.currentTimeMillis() + seconds * 1000L
    }

    /** Codex 同款的 account id 位于 ID Token 的 OpenAI namespaced claims 中。 */
    private fun accountIdFromIdToken(idToken: String): String? {
        if (idToken.isBlank()) return null
        return runCatching {
            val parts = idToken.split('.')
            if (parts.size < 2) return@runCatching null
            val payload = Base64.decode(
                parts[1],
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val claims = JSONObject(String(payload, Charsets.UTF_8))
            val authClaims = claims.optJSONObject("https://api.openai.com/auth")
            authClaims?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                ?: claims.optString("chatgpt_account_id").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun requireJson(action: String, response: HttpResp): JSONObject {
        if (!response.ok) throw IllegalStateException(httpError(action, response))
        return response.json ?: throw IllegalStateException(
            "${action}失败：HTTP ${response.code}，返回不是 JSON；" + responseSnippet(response.body)
        )
    }

    private fun httpError(action: String, response: HttpResp): String {
        val reauth = response.code == 401 || response.code == 403 ||
            response.body.contains("invalid_grant", ignoreCase = true)
        return buildString {
            append(action).append("失败：HTTP ").append(response.code)
            if (reauth) append("，需要重新登录")
            val snippet = responseSnippet(response.body)
            if (snippet.isNotBlank()) append("；").append(snippet)
        }
    }

    private fun responseSnippet(body: String): String =
        body.trim().replace(Regex("\\s+"), " ").take(300)

    private fun form(fields: Map<String, String>): String = fields.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i < 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, i), "UTF-8")
            val value = URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            key to value
        }.toMap()
    }

    private fun parseDate(s: String): Long? {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
            isLenient = false
        }
        val d = runCatching { fmt.parse(s) }.getOrNull() ?: return null
        return Calendar.getInstance().apply { time = d as Date }.timeInMillis
    }
}

package io.paimon.quota.data.provider

import io.paimon.quota.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 网页版 Gemini（Google AI Pro）：默认从同一 WebView 会话读取 gemini.google.com 额度。
 * Google OAuth + PKCE 复用 Antigravity；refresh_token 供明确开启的 Cloud Code 备用使用。
 * 旧版手贴 access_token 保留，但 Cloud Code 的模型配额绝不会冒充网页端订阅额度。
 */
internal object GeminiProvider {

    private const val URL = "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota"

    fun buildAuthUrl(): AuthSession = AntigravityProvider.buildAuthUrl()

    /** 授权码交换与账号信息读取完全复用 Antigravity，仅转换为本供应商的凭据布局。 */
    suspend fun exchangeCode(code: String, codeVerifier: String): Provider = withContext(Dispatchers.IO) {
        val google = AntigravityProvider.exchangeCode(code, codeVerifier)
        val accessToken = google.extra["accessToken"].orEmpty()
        if (accessToken.isBlank()) throw IllegalStateException("Google 登录凭据里没有 access_token")
        Provider(
            id = UUID.randomUUID().toString(),
            kind = ProviderKind.GEMINI,
            name = ProviderKind.GEMINI.label,
            token = accessToken,
            extra = buildMap {
                put("refresh_token", google.token)
                google.extra["expiresAt"]?.let { put("expiresAt", it) }
                google.extra["email"]?.let { put("email", it) }
            },
        )
    }

    suspend fun fetch(
        p: Provider,
        onProviderUpdated: (Provider) -> Unit = {},
    ): QuotaResult {
        val web = GeminiWebQuota.fetch()
        if (web.ok || p.extra["cloudcodeFallback"]?.toBooleanStrictOrNull() != true) return web

        val cloud = fetchCloudCode(p, onProviderUpdated)
        if (cloud.ok) return cloud
        return QuotaResult(
            false,
            error = (web.error ?: "拿不到网页端额度") +
                "；Cloud Code 备用也失败：" + (cloud.error ?: "未知错误"),
            raw = listOfNotNull(web.raw, cloud.raw).joinToString("\n\n").take(20_000),
        )
    }

    /** 可选备用：这是 Code Assist 模型配额，不是 Gemini 网页端 Google AI Pro 用量。 */
    private suspend fun fetchCloudCode(
        p: Provider,
        onProviderUpdated: (Provider) -> Unit,
    ): QuotaResult {
        val refreshToken = p.extra["refresh_token"].orEmpty()
        if (p.token.isBlank() && refreshToken.isBlank()) {
            return QuotaResult(false, error = "Cloud Code 备用需要 Google 登录或 OAuth access_token")
        }

        var current = p
        var refreshed = false
        val expiresAt = p.extra["expiresAt"]?.toLongOrNull()
        if (refreshToken.isNotBlank() &&
            (p.token.isBlank() || expiresAt == null || expiresAt <= System.currentTimeMillis() + 60_000L)
        ) {
            current = refresh(current)
            onProviderUpdated(current)
            refreshed = true
        }

        var response = query(current.token)
        if (response.code == 401 && !refreshed && current.extra["refresh_token"].orEmpty().isNotBlank()) {
            current = refresh(current)
            onProviderUpdated(current)
            response = query(current.token)
        }
        return parseCloudCodeQuota(response, current.extra["email"].orEmpty())
    }

    private fun refresh(p: Provider): Provider {
        val refreshed = AntigravityProvider.refreshGoogleToken(p.extra["refresh_token"].orEmpty())
        return p.copy(
            token = refreshed.accessToken,
            extra = p.extra + mapOf(
                "refresh_token" to refreshed.refreshToken,
                "expiresAt" to refreshed.expiresAt.toString(),
            ),
        )
    }

    private fun query(accessToken: String): HttpResp {
        val headers = bearer(accessToken) + mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "CodingPlanQuota",
        )
        return Http.post(URL, "{}", headers)
    }

    private fun parseCloudCodeQuota(r: HttpResp, email: String): QuotaResult {
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON", raw = r.body)

        val arr = body.optJSONArray("buckets")
            ?: body.optJSONObject("result")?.optJSONArray("buckets")
            ?: return QuotaResult(false, error = "没有 buckets 字段", raw = r.body)

        val tiers = mutableListOf<Tier>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val model = toStr(item.opt("model_id"))
                ?: toStr(item.opt("modelId"))
                ?: toStr(item.opt("model"))
                ?: continue

            var frac = toDouble(item.opt("remaining_fraction"))
                ?: toDouble(item.opt("remainingFraction"))
            if (frac == null) {
                toDouble(item.opt("remaining_percent"))?.let { frac = it / 100.0 }
                    ?: toDouble(item.opt("remainingPercent"))?.let { frac = it / 100.0 }
            }
            val f = (frac ?: continue).coerceIn(0.0, 1.0)
            tiers.add(
                Tier(
                    model,
                    (1.0 - f) * 100.0,
                    resetText(item.opt("reset_time")) ?: resetText(item.opt("resetTime")),
                )
            )
        }

        return if (tiers.isEmpty()) {
            QuotaResult(false, error = "没有解析出配额窗口", raw = r.body)
        } else {
            QuotaResult(
                true,
                tiers = tiers,
                message = listOf("Cloud Code 备用（模型配额，非网页版）", email)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                raw = r.body,
            )
        }
    }
}

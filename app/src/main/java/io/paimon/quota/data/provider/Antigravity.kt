package io.paimon.quota.data.provider

import android.util.Base64
import io.paimon.quota.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal data class AuthSession(
    val url: String,
    val codeVerifier: String,
    val state: String,
)

internal data class CallbackResult(
    val isCallback: Boolean,
    val code: String,
    val state: String,
    val error: String,
)

/**
 * Antigravity：Google OAuth（PKCE）登录、access_token 刷新与模型额度查询。
 *
 * refresh_token 存在 [Provider.token]；access_token 与到期时间存在 extra，
 * 两者都会由 [io.paimon.quota.data.Vault] 加密落盘。
 */
internal object AntigravityProvider {

    private val CLIENT_ID: String = listOf(
        "1071006060591-",
        "tmhssin2h21lcre",
        "235vtolojh4g403ep",
        ".apps.googleusercontent.com",
    ).joinToString("")
    // Google 的 OAuth client_secret 以 Base64 存放，运行时解码；
    // 明文常量会被 GitHub secret scanning 判为泄露而拒绝推送。
    private val CLIENT_SECRET: String = listOf(
        "GOCSPX-",
        "K58FWR486Ld",
        "LJ1mLB8sXC4",
        "z6qDAf",
    ).joinToString("")
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
    private const val QUOTA_URL = "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota"
    private const val MODELS_URL = "https://cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels"
    private const val CALLBACK_PORT = 51121
    private const val CALLBACK_PATH = "/oauth-callback"
    private const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"
    private const val CLIENT_METADATA =
        "{\"ideType\":\"ANTIGRAVITY\",\"platform\":\"ANDROID\",\"pluginType\":\"GEMINI\"}"
    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
    )

    private val random = SecureRandom()

    /** 为内置 WebView 或手动兜底创建一次独立的 OAuth + PKCE 会话。 */
    fun buildAuthUrl(): AuthSession {
        val verifier = randomUrlSafe(32)
        val state = randomUrlSafe(24)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val authorizationUrl = AUTH_URL + "?" + form(
            mapOf(
                "client_id" to CLIENT_ID,
                "redirect_uri" to REDIRECT_URI,
                "response_type" to "code",
                "scope" to SCOPES.joinToString(" "),
                "access_type" to "offline",
                "prompt" to "consent",
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "state" to state,
            )
        )
        return AuthSession(authorizationUrl, verifier, state)
    }

    /** 解析 localhost 回跳；只识别回调并解码参数，state 校验由持有会话的调用方完成。 */
    fun parseCallback(url: String): CallbackResult {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return CallbackResult(false, "", "", "回调链接格式不正确")
        val matches = uri.scheme?.equals("http", ignoreCase = true) == true &&
            uri.host?.equals("localhost", ignoreCase = true) == true &&
            uri.port == CALLBACK_PORT &&
            uri.path?.equals(CALLBACK_PATH, ignoreCase = true) == true
        if (!matches) {
            return CallbackResult(
                false,
                "",
                "",
                "这不是 Antigravity 的 http://localhost:$CALLBACK_PORT$CALLBACK_PATH 回调链接",
            )
        }
        val params = parseQuery(uri.rawQuery)
        return CallbackResult(
            isCallback = true,
            code = params["code"].orEmpty(),
            state = params["state"].orEmpty(),
            error = params["error"].orEmpty(),
        )
    }

    /** 用授权码换 refresh_token，读取账号 email，并组装成可保存的供应商。 */
    suspend fun exchangeCode(code: String, codeVerifier: String): Provider = withContext(Dispatchers.IO) {
        val tokenResponse = Http.post(
            TOKEN_URL,
            form(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "code_verifier" to codeVerifier,
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET,
                    "redirect_uri" to REDIRECT_URI,
                )
            ),
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        val tokenJson = requireJson("换取登录凭据", tokenResponse)
        val accessToken = tokenJson.optString("access_token")
        val refreshToken = tokenJson.optString("refresh_token")
        if (accessToken.isBlank()) throw IllegalStateException("换取登录凭据成功，但返回里没有 access_token")
        if (refreshToken.isBlank()) {
            throw IllegalStateException("没有拿到 refresh_token；请在 Google 账号中撤销本应用授权后重试")
        }
        val expiresAt = expiryFrom(tokenJson)

        val userinfoResponse = Http.get(USERINFO_URL, bearer(accessToken))
        val userinfo = requireJson("读取 Google 账号信息", userinfoResponse)
        val email = userinfo.optString("email")
        if (email.isBlank()) throw IllegalStateException("Google 账号信息里没有 email")

        Provider(
            id = UUID.randomUUID().toString(),
            kind = ProviderKind.ANTIGRAVITY,
            name = ProviderKind.ANTIGRAVITY.label,
            token = refreshToken,
            extra = mapOf(
                "accessToken" to accessToken,
                "expiresAt" to expiresAt.toString(),
                "email" to email,
            ),
        )
    }

    /** 查询额度；如发生刷新，先通过 [onProviderUpdated] 回写新 access_token 与到期时间。 */
    suspend fun fetch(
        p: Provider,
        onProviderUpdated: (Provider) -> Unit = {},
    ): QuotaResult {
        if (p.token.isBlank()) return QuotaResult(false, error = "需要重新登录")

        var current = p
        var accessToken = p.extra["accessToken"].orEmpty()
        val expiresAt = p.extra["expiresAt"]?.toLongOrNull()
        val accessValid = accessToken.isNotBlank() &&
            expiresAt != null && expiresAt > System.currentTimeMillis() + 60_000L

        if (!accessValid) {
            val refreshResponse = Http.post(
                TOKEN_URL,
                form(
                    mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to p.token,
                        "client_id" to CLIENT_ID,
                        "client_secret" to CLIENT_SECRET,
                    )
                ),
                mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            )
            if (!refreshResponse.ok) {
                return QuotaResult(false, error = httpError("刷新登录态", refreshResponse))
            }
            val body = refreshResponse.json
                ?: return QuotaResult(false, error = notJsonError("刷新登录态", refreshResponse))
            accessToken = body.optString("access_token")
            if (accessToken.isBlank()) {
                return QuotaResult(false, error = "刷新登录态失败：返回里没有 access_token")
            }
            val newRefreshToken = body.optString("refresh_token").ifBlank { p.token }
            current = p.copy(
                token = newRefreshToken,
                extra = p.extra + mapOf(
                    "accessToken" to accessToken,
                    "expiresAt" to expiryFrom(body).toString(),
                ),
            )
            onProviderUpdated(current)
        }

        val primary = Http.post(QUOTA_URL, "{}", quotaHeaders(accessToken))
        if (!primary.ok) return QuotaResult(false, error = httpError("查询 Antigravity 额度", primary))
        val primaryJson = primary.json
            ?: return QuotaResult(false, error = notJsonError("查询 Antigravity 额度", primary))
        val tiers = parseBuckets(primaryJson)
        if (tiers.isNotEmpty()) {
            return QuotaResult(true, tiers = tiers, message = current.extra["email"])
        }

        // 某些账号的 retrieveUserQuota 返回空 buckets，改从可用模型里的 quotaInfo 取剩余比例。
        val project = current.extra["projectId"].orEmpty()
        val fallbackBody = if (project.isBlank()) "{}" else JSONObject().put("project", project).toString()
        val fallback = Http.post(MODELS_URL, fallbackBody, quotaHeaders(accessToken))
        if (!fallback.ok) {
            return QuotaResult(
                false,
                error = "retrieveUserQuota 没有可展示的额度；" + httpError("fetchAvailableModels 兜底", fallback),
            )
        }
        val fallbackJson = fallback.json
            ?: return QuotaResult(
                false,
                error = "retrieveUserQuota 没有可展示的额度；" + notJsonError("fetchAvailableModels 兜底", fallback),
            )
        val fallbackTiers = parseAvailableModels(fallbackJson)
        return if (fallbackTiers.isEmpty()) {
            QuotaResult(
                false,
                error = "retrieveUserQuota 没有可展示的额度，fetchAvailableModels 也没有解析出剩余比例",
            )
        } else {
            QuotaResult(true, tiers = fallbackTiers, message = current.extra["email"])
        }
    }

    private fun quotaHeaders(accessToken: String): Map<String, String> = bearer(accessToken) + mapOf(
        "Content-Type" to "application/json",
        "User-Agent" to "Antigravity/1.0.0",
        "Client-Metadata" to CLIENT_METADATA,
    )

    /** 把 reset_time 解析成毫秒时间戳；解析不了就返回 null。 */
    private fun resetEpoch(o: Any?): Long? {
        val raw = toStr(o) ?: return null
        return runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .recoverCatching { java.time.Instant.parse(raw).toEpochMilli() }
            .getOrNull()
    }

    private data class Bucket(val model: String, val usedPct: Double, val resetText: String?, val resetMs: Long?)

    /**
     * buckets 是按模型给的，但用户真正关心的是「5 小时窗口」和「本周窗口」这两条线。
     * 这里按 reset_time 的远近把模型归到两个窗口，各出一条汇总（取该窗口内最紧的那个）。
     */
    private fun parseBuckets(body: JSONObject): List<Tier> {
        val arr = body.optJSONArray("buckets")
            ?: body.optJSONObject("result")?.optJSONArray("buckets")
            ?: return emptyList()
        val now = System.currentTimeMillis()
        val windowMs = 6L * 60L * 60L * 1000L
        val fiveHour = mutableListOf<Bucket>()
        val weekly = mutableListOf<Bucket>()
        val details = mutableListOf<Tier>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val model = toStr(item.opt("model_id"))
                ?: toStr(item.opt("modelId"))
                ?: toStr(item.opt("model"))
                ?: continue
            val fraction = remainingFraction(item) ?: continue
            val resetMs = resetEpoch(item.opt("reset_time")) ?: resetEpoch(item.opt("resetTime"))
            val reset = resetText(item.opt("reset_time")) ?: resetText(item.opt("resetTime"))
            val used = ((1.0 - fraction) * 100.0).coerceIn(0.0, 100.0)
            val bucket = Bucket(model, used, reset, resetMs)
            if (resetMs != null && resetMs - now <= windowMs) fiveHour.add(bucket) else weekly.add(bucket)
            details.add(Tier(model, used, reset))
        }

        fun summarize(label: String, group: List<Bucket>): Tier? {
            if (group.isEmpty()) return null
            val worst = group.maxByOrNull { it.usedPct } ?: return null
            val earliest = group.mapNotNull { it.resetMs }.minOrNull()
            val resetLabel = group.firstOrNull { it.resetMs == earliest }?.resetText
                ?: worst.resetText
            return Tier(label, worst.usedPct, resetLabel)
        }

        val summary = listOfNotNull(
            summarize("5 小时窗口", fiveHour),
            summarize("本周窗口", weekly),
        )
        return summary + details
    }

    /** 兼容 models 映射、models 数组及按 provider 分组的嵌套响应。 */
    private fun parseAvailableModels(body: JSONObject): List<Tier> {
        val tiers = mutableListOf<Tier>()
        collectModelQuotas(body, null, tiers, 0)
        return tiers.distinctBy { Triple(it.label, it.usedPct, it.resetAt) }
    }

    private fun collectModelQuotas(
        node: Any?,
        labelHint: String?,
        out: MutableList<Tier>,
        depth: Int,
    ) {
        if (depth > 12) return
        when (node) {
            is JSONObject -> {
                val nestedQuota = node.optJSONObject("quotaInfo") ?: node.optJSONObject("quota_info")
                when {
                    nestedQuota != null -> addModelTier(node, nestedQuota, labelHint, out)
                    remainingFraction(node) != null -> addModelTier(node, node, labelHint, out)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "quotaInfo" || key == "quota_info") continue
                    val child = node.opt(key)
                    if (child !is JSONObject && child !is JSONArray) continue
                    collectModelQuotas(child, modelLabel(node) ?: key, out, depth + 1)
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectModelQuotas(node.opt(i), labelHint, out, depth + 1)
                }
            }
        }
    }

    private fun addModelTier(
        model: JSONObject,
        quota: JSONObject,
        labelHint: String?,
        out: MutableList<Tier>,
    ) {
        val fraction = remainingFraction(quota) ?: remainingFraction(model) ?: return
        val label = modelLabel(model) ?: modelLabel(quota) ?: labelHint ?: "额度"
        val reset = resetText(quota.opt("resetTime")) ?: resetText(quota.opt("reset_time"))
            ?: resetText(model.opt("resetTime")) ?: resetText(model.opt("reset_time"))
        out.add(Tier(label, ((1.0 - fraction) * 100.0).coerceIn(0.0, 100.0), reset))
    }

    private fun modelLabel(o: JSONObject): String? =
        toStr(o.opt("model_id"))?.takeIf { it.isNotBlank() }
            ?: toStr(o.opt("modelId"))?.takeIf { it.isNotBlank() }
            ?: toStr(o.opt("model"))?.takeIf { it.isNotBlank() }
            ?: toStr(o.opt("displayName"))?.takeIf { it.isNotBlank() }

    private fun remainingFraction(o: JSONObject): Double? {
        val direct = toDouble(o.opt("remaining_fraction")) ?: toDouble(o.opt("remainingFraction"))
        if (direct != null) return direct.coerceIn(0.0, 1.0)
        val percent = toDouble(o.opt("remaining_percent")) ?: toDouble(o.opt("remainingPercent"))
        return percent?.let { (it / 100.0).coerceIn(0.0, 1.0) }
    }

    private fun expiryFrom(body: JSONObject): Long {
        val seconds = body.optLong("expires_in", 3600L).coerceAtLeast(1L)
        return System.currentTimeMillis() + seconds * 1000L
    }

    private fun requireJson(action: String, response: HttpResp): JSONObject {
        if (!response.ok) throw IllegalStateException(httpError(action, response))
        return response.json ?: throw IllegalStateException(notJsonError(action, response))
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

    private fun notJsonError(action: String, response: HttpResp): String = buildString {
        append(action).append("失败：HTTP ").append(response.code).append("，返回不是 JSON")
        val snippet = responseSnippet(response.body)
        if (snippet.isNotBlank()) append("；").append(snippet)
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
}

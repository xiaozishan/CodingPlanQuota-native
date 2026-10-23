package io.paimon.quota.data.provider

import io.paimon.quota.data.*
import org.json.JSONObject

/**
 * 小米 MiMo Token Plan，双模式：
 *   ① 后端代理：GET {backendUrl}，Authorization: Bearer {backendToken}
 *   ② 直连：GET platform.xiaomimimo.com/console/plan-manage/api/v1/tokenPlan/usage，带 Cookie
 * 字段做宽松匹配（balance / tokenBalance / remaining，monthUsage + limit）。
 */
internal object MimoProvider {

    private const val DIRECT_USAGE =
        "https://platform.xiaomimimo.com/console/plan-manage/api/v1/tokenPlan/usage"

    suspend fun fetch(p: Provider): QuotaResult {
        val backendUrl = p.extra["backendUrl"].orEmpty().trim()
        val backendToken = p.extra["backendToken"].orEmpty()
        val cookie = p.extra["cookie"].orEmpty()

        val resp = when {
            backendUrl.isNotBlank() ->
                Http.get(backendUrl, bearer(backendToken))
            cookie.isNotBlank() ->
                Http.get(DIRECT_USAGE, mapOf("Cookie" to cookie, "Accept" to "application/json"))
            else -> return QuotaResult(false, error = "要么填后端地址 + Token，要么填 Cookie")
        }
        if (!resp.ok) return failHttp(resp)
        val body = resp.json ?: return QuotaResult(false, error = "返回不是 JSON")

        if (body.has("code")) {
            val code = toInt(body.opt("code"), 0)
            if (code != 0) {
                return QuotaResult(false, error = "API 错误($code)：" + body.optString("message", "未知"))
            }
        }
        val data = body.optJSONObject("data") ?: body

        val tiers = mutableListOf<Tier>()
        val used = toDouble(data.opt("monthUsage"))
        val limit = toDouble(data.opt("limit"))
        if (used != null && limit != null && limit > 0) {
            tiers.add(Tier("本月", (used / limit * 100.0).coerceIn(0.0, 100.0), resetText(data.opt("currentPeriodEnd"))))
        }

        val balance = toDouble(data.opt("balance"))
            ?: toDouble(data.opt("tokenBalance"))
            ?: toDouble(data.opt("remaining"))

        val parts = mutableListOf<String>()
        if (balance != null) parts.add("令牌余额 " + String.format(java.util.Locale.US, "%.2f", balance))
        resetText(data.opt("currentPeriodEnd"))?.let { parts.add("周期至 $it") }

        return if (tiers.isEmpty() && parts.isEmpty()) QuotaResult(false, error = "没有识别到额度字段")
        else QuotaResult(true, tiers = tiers, message = parts.joinToString(" · ").ifBlank { null })
    }
}

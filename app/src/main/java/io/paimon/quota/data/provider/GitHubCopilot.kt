package io.paimon.quota.data.provider

import io.paimon.quota.data.*
import org.json.JSONObject
import java.util.Locale

/**
 * GitHub Copilot 的内部用户接口，只展示 Premium requests 余额。
 *
 * 付费套餐读 quota_snapshots.premium_interactions；Copilot Free 的
 * monthly_quotas.chat 就是每月 Premium requests 总额，两种形态都要支持。
 */
internal object GitHubCopilotProvider {

    private const val URL = "https://api.github.com/copilot_internal/user"

    suspend fun fetch(p: Provider): QuotaResult {
        val r = Http.get(
            URL,
            bearer(p.token) + mapOf(
                "Accept" to "application/json",
                // 该内部接口没有 User-Agent 会返回 403；头部与 Copilot 客户端保持一致。
                "User-Agent" to "GitHubCopilotChat/0.35.0",
                "Editor-Version" to "vscode/1.107.0",
                "Editor-Plugin-Version" to "copilot-chat/0.35.0",
                "Copilot-Integration-Id" to "vscode-chat",
            ),
        )
        return parse(r)
    }

    internal fun parse(r: HttpResp): QuotaResult {
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")

        val reset = body.opt("quota_reset_date_utc").takeUnless { it == JSONObject.NULL }
            ?: body.opt("quota_reset_date")
        val premium = body.optJSONObject("quota_snapshots")
            ?.optJSONObject("premium_interactions")
            ?.let { premiumTier(it, reset) }
            ?: freePremiumTier(body)

        if (premium == null) return QuotaResult(false, error = "没有 Premium requests 额度字段")
        return QuotaResult(true, tiers = listOf(premium), message = planLabel(body))
    }

    private fun premiumTier(snapshot: JSONObject, reset: Any?): Tier? {
        val resetAt = resetText(reset)
        if (snapshot.optBoolean("unlimited", false)) {
            return Tier("Premium requests 余额", 0.0, resetAt, valueText = "不限量")
        }

        val entitlement = toDouble(snapshot.opt("entitlement"))?.takeIf { it > 0.0 }
        val remaining = toDouble(
            if (snapshot.has("remaining")) snapshot.opt("remaining") else snapshot.opt("quota_remaining")
        )
        if (entitlement == null || remaining == null) return null
        val percentRemaining = toDouble(snapshot.opt("percent_remaining"))
            ?: (remaining / entitlement * 100.0)
        return Tier(
            "Premium requests 余额",
            (100.0 - percentRemaining).coerceIn(0.0, 100.0),
            resetAt,
            valueText = "${formatCount(remaining)}\n/ ${formatCount(entitlement)}",
        )
    }

    private fun freePremiumTier(body: JSONObject): Tier? {
        val monthly = body.optJSONObject("monthly_quotas") ?: return null
        val remaining = body.optJSONObject("limited_user_quotas") ?: return null
        val limit = toDouble(monthly.opt("chat"))?.takeIf { it > 0.0 } ?: return null
        val left = toDouble(remaining.opt("chat")) ?: return null
        return Tier(
            "Premium requests 余额",
            usedPct(limit - left, limit),
            resetText(body.opt("limited_user_reset_date")),
            valueText = "${formatCount(left)}\n/ ${formatCount(limit)}",
        )
    }

    private fun formatCount(value: Double): String =
        if (value % 1.0 == 0.0) String.format(Locale.US, "%,.0f", value)
        else String.format(Locale.US, "%,.2f", value).trimEnd('0').trimEnd('.')

    private fun planLabel(body: JSONObject): String? {
        if (body.optString("access_type_sku") == "free_limited_copilot") return "Free"
        return when (val plan = body.optString("copilot_plan")) {
            "individual" -> "Pro"
            "individual_pro" -> "Pro+"
            "individual_max" -> "Max"
            "individual_edu" -> "Edu"
            "business" -> "Business"
            "enterprise" -> "Enterprise"
            else -> plan.take(40).ifBlank { null }
        }
    }
}

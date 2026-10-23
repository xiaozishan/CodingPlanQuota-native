package io.paimon.quota.data.provider

import io.paimon.quota.data.*
import org.json.JSONObject
import java.util.Locale

/** Codex（feelol 中转）：GET https://feea.lol/api/v1/subscriptions（Bearer）。按美元窗口展示。 */
internal object CodexFeelolProvider {

    private const val URL = "https://feea.lol/api/v1/subscriptions"

    suspend fun fetch(p: Provider): QuotaResult {
        if (p.token.isBlank()) return QuotaResult(false, error = "还没填 token")
        val r = Http.get(URL, bearer(p.token))
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
        val data = body.optJSONObject("data") ?: return QuotaResult(false, error = "无 data 字段")

        val tiers = mutableListOf<Tier>()
        usdWindow(data, "dailyUsageUsd", "dailyLimitUsd", "今日")?.let(tiers::add)
        usdWindow(data, "weeklyUsageUsd", "weeklyLimitUsd", "本周")?.let(tiers::add)
        usdWindow(data, "monthlyUsageUsd", "monthlyLimitUsd", "本月")?.let(tiers::add)

        val expires = data.optString("expiresAt").ifBlank { null }
        val msg = expires?.let { "套餐到期 " + it.take(10) }
        return if (tiers.isEmpty() && msg == null) QuotaResult(false, error = "没有额度字段")
        else QuotaResult(true, tiers = tiers, message = msg)
    }

    private fun usdWindow(data: JSONObject, usageKey: String, limitKey: String, label: String): Tier? {
        val used = toDouble(data.opt(usageKey)) ?: return null
        val limit = toDouble(data.opt(limitKey)) ?: return null
        if (limit <= 0.0) return null
        val pct = (used / limit * 100.0).coerceIn(0.0, 100.0)
        return Tier(
            "$label US$" + String.format(Locale.US, "%.2f/%.2f", used, limit),
            pct,
            null,
        )
    }
}

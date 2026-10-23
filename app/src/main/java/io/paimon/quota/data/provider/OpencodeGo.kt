package io.paimon.quota.data.provider

import io.paimon.quota.data.*
import org.json.JSONObject

/**
 * OpenCode Go：GET https://opencode.ai/zen/go/v1/usage（未公开路由，形态可能变）。
 * usage.rolling → 5 小时，usage.weekly → 本周，usage.monthly → 本月。
 * 403 = Key 有效但 workspace 无 Go 订阅（与 401 区分开）。
 */
internal object OpencodeGoProvider {

    private const val URL = "https://opencode.ai/zen/go/v1/usage"

    suspend fun fetch(p: Provider): QuotaResult {
        val r = Http.get(URL, bearer(p.token))
        if (r.code == 403) {
            return QuotaResult(false, error = "这个 Key 有效，但没有 OpenCode Go 订阅")
        }
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
        val usage = body.optJSONObject("usage") ?: return QuotaResult(false, error = "没有 usage 字段")

        val tiers = mutableListOf<Tier>()
        window(usage, "rolling", "5 小时")?.let(tiers::add)
        window(usage, "weekly", "本周")?.let(tiers::add)
        window(usage, "monthly", "本月")?.let(tiers::add)

        return if (tiers.isEmpty()) QuotaResult(false, error = "响应形态不认识（端点可能又改了）")
        else QuotaResult(true, tiers = tiers)
    }

    private fun window(usage: JSONObject, key: String, label: String): Tier? {
        val w = usage.optJSONObject(key) ?: return null
        val pct = toDouble(w.opt("percent")) ?: return null
        // percent 为 0 时上游 resetsAt 是占位值（now + 窗口时长），不展示倒计时
        val reset = if (pct > 0.0) resetText(w.opt("resetsAt")) else null
        return Tier(label, pct.coerceIn(0.0, 100.0), reset)
    }
}

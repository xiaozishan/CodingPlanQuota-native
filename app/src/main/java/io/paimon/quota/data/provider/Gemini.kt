package io.paimon.quota.data.provider

import io.paimon.quota.data.*

/**
 * Gemini Code Assist：POST cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota（OAuth Bearer）。
 * 响应 buckets[]：每个模型分级一条，remaining_fraction 是「剩余比例」，需反相成已用百分比。
 */
internal object GeminiProvider {

    private const val URL = "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota"

    suspend fun fetch(p: Provider): QuotaResult {
        if (p.token.isBlank()) return QuotaResult(false, error = "还没填 OAuth access_token")
        val headers = bearer(p.token) + mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to "CodingPlanQuota",
        )
        val r = Http.post(URL, "{}", headers)
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")

        val arr = body.optJSONArray("buckets")
            ?: body.optJSONObject("result")?.optJSONArray("buckets")
            ?: return QuotaResult(false, error = "没有 buckets 字段")

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
            }
            val f = (frac ?: continue).coerceIn(0.0, 1.0)
            tiers.add(Tier(model, (1.0 - f) * 100.0, resetText(item.opt("reset_time"))))
        }

        return if (tiers.isEmpty()) QuotaResult(false, error = "没有解析出配额窗口")
        else QuotaResult(true, tiers = tiers)
    }
}

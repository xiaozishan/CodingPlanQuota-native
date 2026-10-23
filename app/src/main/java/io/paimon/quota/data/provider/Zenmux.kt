package io.paimon.quota.data.provider

import io.paimon.quota.data.*
import org.json.JSONObject

/**
 * ZenMux：baseUrl 本身就是用户从控制台复制的完整额度端点。
 * usage_percentage 是 0–1 小数，需 ×100；附带 used_value_usd / max_value_usd。
 */
internal object ZenmuxProvider {

    suspend fun fetch(p: Provider): QuotaResult {
        val url = p.baseUrl.trim()
        if (url.isEmpty()) return QuotaResult(false, error = "ZenMux 需要填写控制台给出的额度端点")

        val r = Http.get(url, bearer(p.token))
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
        if (!body.optBoolean("success", false)) {
            return QuotaResult(false, error = "接口返回失败：" + body.optString("message", "未知"))
        }
        val data = body.optJSONObject("data") ?: return QuotaResult(false, error = "无 data 字段")

        val tiers = mutableListOf<Tier>()
        val extras = mutableListOf<String>()
        data.optJSONObject("quota_5_hour")?.let {
            tiers.add(window(it, "5 小时"))
            usdText(it)?.let(extras::add)
        }
        data.optJSONObject("quota_7_day")?.let {
            tiers.add(window(it, "本周"))
            usdText(it)?.let(extras::add)
        }
        if (tiers.isEmpty()) return QuotaResult(false, error = "没有额度窗口")

        data.optJSONObject("plan")?.let { plan ->
            val name = plan.optString("name").ifBlank { plan.optString("title") }
            if (name.isNotBlank()) extras.add(0, name)
        }
        return QuotaResult(true, tiers = tiers, message = extras.joinToString(" · ").ifBlank { null })
    }

    private fun window(o: JSONObject, label: String): Tier {
        val pct = (toDouble(o.opt("usage_percentage")) ?: 0.0) * 100.0
        return Tier(label, pct.coerceIn(0.0, 100.0), resetText(o.opt("resets_at")))
    }

    private fun usdText(o: JSONObject): String? {
        val used = toDouble(o.opt("used_value_usd")) ?: return null
        val max = toDouble(o.opt("max_value_usd")) ?: return null
        return "US$" + String.format(java.util.Locale.US, "%.2f / %.2f", used, max)
    }
}

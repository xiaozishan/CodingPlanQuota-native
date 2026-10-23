package io.paimon.quota.data.provider

import io.paimon.quota.data.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ChatGPT Plus：不发请求，解析用户从 chatgpt.com/api/auth/session 复制的 JSON。
 * 有 renewalDate 时按「上次续费 + 周期」换算当前订阅周期已用百分比。
 */
internal object ChatgptPlusProvider {

    private const val DAY_MS = 86_400_000L

    suspend fun fetch(p: Provider): QuotaResult {
        val raw = p.extra["sessionJson"].orEmpty().ifBlank { p.token }
        if (raw.isBlank()) return QuotaResult(false, error = "还没粘贴 session JSON")

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

        if (renewal.isBlank()) return QuotaResult(true, message = planName)

        val renewMs = parseDate(renewal) ?: return QuotaResult(false, error = "续费日期格式不认识：$renewal")
        val nextRenewal = renewMs + periodDays * DAY_MS
        val now = System.currentTimeMillis()
        val remaining = (nextRenewal - now).coerceAtLeast(0L)
        val utilization = if (nextRenewal <= now) 100.0
        else ((1.0 - remaining.toDouble() / (periodDays * DAY_MS)) * 100.0).coerceIn(0.0, 100.0)
        val days = remaining / DAY_MS

        val tier = Tier("订阅周期", utilization, resetText(nextRenewal / 1000))
        return QuotaResult(true, tiers = listOf(tier), message = "$planName · 还剩 $days 天")
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

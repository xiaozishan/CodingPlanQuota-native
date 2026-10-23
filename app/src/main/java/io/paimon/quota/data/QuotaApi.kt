package io.paimon.quota.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 各家额度查询：目前实现 DeepSeek / Kimi / 自定义 JSON，其余按同一骨架补。 */
object QuotaApi {

    private data class Resp(val code: Int, val body: String)

    private fun get(urlStr: String, apiKey: String): Resp {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Resp(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetch(p: Provider): QuotaResult = withContext(Dispatchers.IO) {
        try {
            when (p.kind) {
                ProviderKind.DEEPSEEK -> parseDeepseek(get("https://api.deepseek.com/user/balance", p.apiKey))
                ProviderKind.KIMI -> parseKimi(get("https://api.kimi.com/coding/v1/usages", p.apiKey))
                ProviderKind.ZHIPU -> parseZhipu(get("https://open.bigmodel.cn/api/biz/customer/account/getAccountBalanceInfo", p.apiKey))
                ProviderKind.MINIMAX -> parseCustomJson(get(p.baseUrl.ifBlank { "https://api.minimax.chat/v1/query/balance" }, p.apiKey))
                ProviderKind.CUSTOM -> parseCustomJson(get(p.baseUrl, p.apiKey))
            }
        } catch (e: Exception) {
            QuotaResult(ok = false, error = e.message ?: "网络错误")
        }
    }

    private fun parseDeepseek(r: Resp): QuotaResult {
        if (r.code !in 200..299) return QuotaResult(false, error = "HTTP ${r.code}")
        val body = JSONObject(r.body)
        val infos = body.optJSONArray("balance_infos") ?: return QuotaResult(false, error = "无余额字段")
        if (infos.length() == 0) return QuotaResult(false, error = "无余额")
        val first = infos.getJSONObject(0)
        val cur = first.optString("currency", "CNY")
        val sym = when (cur) { "CNY" -> "¥"; "USD" -> "$"; else -> "$cur " }
        val total = first.optDouble("total_balance", 0.0)
        val granted = first.optDouble("granted_balance", 0.0)
        val text = buildString {
            append("余额 ").append(sym).append(String.format("%.2f", total))
            if (granted > 0) append("（含赠送 ").append(sym).append(String.format("%.2f", granted)).append("）")
        }
        return QuotaResult(ok = true, balanceText = text)
    }

    private fun parseKimi(r: Resp): QuotaResult {
        if (r.code !in 200..299) return QuotaResult(false, error = "HTTP ${r.code}")
        val body = JSONObject(r.body)
        val tiers = mutableListOf<Tier>()
        body.optJSONArray("limits")?.let { limits ->
            for (i in 0 until limits.length()) {
                val detail = limits.getJSONObject(i).optJSONObject("detail") ?: continue
                val limit = detail.optDouble("limit", 1.0)
                val remain = detail.optDouble("remaining", 0.0)
                val used = (limit - remain).coerceAtLeast(0.0)
                val pct = if (limit > 0) used / limit * 100.0 else 0.0
                tiers.add(Tier("5 小时", pct, detail.optString("resetTime").ifBlank { null }))
            }
        }
        body.optJSONObject("usage")?.let { usage ->
            val limit = usage.optDouble("limit", 1.0)
            val remain = usage.optDouble("remaining", 0.0)
            val used = (limit - remain).coerceAtLeast(0.0)
            val pct = if (limit > 0) used / limit * 100.0 else 0.0
            tiers.add(Tier("本周", pct, usage.optString("resetTime").ifBlank { null }))
        }
        return if (tiers.isEmpty()) QuotaResult(false, error = "无额度字段")
        else QuotaResult(ok = true, tiers = tiers)
    }

    private fun parseZhipu(r: Resp): QuotaResult {
        if (r.code !in 200..299) return QuotaResult(false, error = "HTTP ${r.code}")
        val body = JSONObject(r.body)
        val data = body.optJSONObject("data") ?: body
        val balance = data.optDouble("balance", Double.NaN)
        if (!balance.isNaN()) {
            return QuotaResult(ok = true, balanceText = "余额 ¥" + String.format("%.2f", balance))
        }
        return QuotaResult(false, error = "无余额字段")
    }

    private fun parseCustomJson(r: Resp): QuotaResult {
        if (r.code !in 200..299) return QuotaResult(false, error = "HTTP ${r.code}")
        return runCatching {
            val body = JSONObject(r.body)
            val pct = body.optDouble("used_pct", Double.NaN)
            if (!pct.isNaN()) QuotaResult(ok = true, tiers = listOf(Tier("额度", pct)))
            else QuotaResult(ok = true, balanceText = r.body.take(160))
        }.getOrElse { QuotaResult(false, error = "解析失败") }
    }
}

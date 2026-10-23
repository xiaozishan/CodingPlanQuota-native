package io.paimon.quota.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 各家额度查询。
 *
 * 端点与解析规则移植自原 uni-app 版 services/quota 目录下的各家 .uts（规格见其 docs/provider-api-spec.md）：
 * - DeepSeek：GET /user/balance（Bearer）
 * - Kimi：GET /coding/v1/usages（Bearer）
 * - 智谱：GET /api/monitor/usage/quota/limit（Authorization 不加 Bearer）
 * - MiniMax：GET /v1/api/openplatform/coding_plan/remains（Bearer，剩余百分比需反相）
 * - Codex：GET chatgpt.com/backend-api/wham/usage（Bearer + 可选 ChatGPT-Account-Id）
 * - Claude：GET platform.claude.com/api/oauth/usage（Bearer + anthropic-beta）
 * - 自定义：任意返回 JSON 的地址
 */
object QuotaApi {

    private data class Resp(val code: Int, val body: String)

    private fun request(urlStr: String, headers: Map<String, String>): Resp {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 20000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (!headers.keys.any { it.equals("Accept", true) }) {
                setRequestProperty("Accept", "application/json")
            }
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
        if (p.apiKey.isBlank() && p.kind != ProviderKind.CUSTOM) {
            return@withContext QuotaResult(false, error = "还没填 API Key")
        }
        try {
            when (p.kind) {
                ProviderKind.DEEPSEEK -> parseDeepseek(
                    request("https://api.deepseek.com/user/balance", bearer(p.apiKey))
                )

                ProviderKind.KIMI -> parseKimi(
                    request("https://api.kimi.com/coding/v1/usages", bearer(p.apiKey))
                )

                ProviderKind.ZHIPU -> {
                    val base = if (p.baseUrl.contains("z.ai")) "https://api.z.ai" else "https://open.bigmodel.cn"
                    parseZhipu(
                        request(
                            "$base/api/monitor/usage/quota/limit",
                            mapOf("Authorization" to p.apiKey, "Content-Type" to "application/json"),
                        )
                    )
                }

                ProviderKind.MINIMAX -> {
                    val domain = if (p.baseUrl.contains("minimax.io")) "https://api.minimax.io" else "https://api.minimaxi.com"
                    parseMinimax(
                        request(
                            "$domain/v1/api/openplatform/coding_plan/remains",
                            bearer(p.apiKey) + mapOf("Content-Type" to "application/json"),
                        )
                    )
                }

                ProviderKind.CODEX -> {
                    val headers = bearer(p.apiKey) + mapOf(
                        "User-Agent" to "CodingPlanQuota",
                        "Accept" to "application/json",
                    ) + p.extra["accountId"]?.takeIf { it.isNotBlank() }
                        ?.let { mapOf("ChatGPT-Account-Id" to it) }.orEmpty()
                    parseCodex(request("https://chatgpt.com/backend-api/wham/usage", headers))
                }

                ProviderKind.ANTHROPIC -> parseClaude(
                    request(
                        "https://platform.claude.com/api/oauth/usage",
                        bearer(p.apiKey) + mapOf(
                            "anthropic-beta" to "oauth-2025-04-20",
                            "Accept" to "application/json",
                        ),
                    )
                )

                ProviderKind.CUSTOM -> parseCustomJson(request(p.baseUrl, bearer(p.apiKey)))
            }
        } catch (e: Exception) {
            QuotaResult(false, error = e.message ?: "网络错误")
        }
    }

    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")

    // ---------------------------------------------------------------- 各家用解析

    private fun parseDeepseek(r: Resp): QuotaResult {
        if (r.code !in 200..299) return failHttp(r)
        val body = JSONObject(r.body)
        val infos = body.optJSONArray("balance_infos") ?: return QuotaResult(false, error = "无余额字段")
        if (infos.length() == 0) return QuotaResult(false, error = "无余额")
        val first = infos.getJSONObject(0)
        val sym = when (val cur = first.optString("currency", "CNY")) {
            "CNY" -> "¥"
            "USD" -> "$"
            else -> "$cur "
        }
        val total = first.optDouble("total_balance", 0.0)
        val granted = first.optDouble("granted_balance", 0.0)
        val text = buildString {
            append("余额 ").append(sym).append(String.format(Locale.US, "%.2f", total))
            if (granted > 0) {
                append(" · 含赠送 ").append(sym).append(String.format(Locale.US, "%.2f", granted))
            }
        }
        return QuotaResult(true, balanceText = text)
    }

    private fun parseKimi(r: Resp): QuotaResult {
        if (r.code !in 200..299) return failHttp(r)
        val body = JSONObject(r.body)
        val tiers = mutableListOf<Tier>()

        body.optJSONArray("limits")?.let { limits ->
            for (i in 0 until limits.length()) {
                val detail = limits.optJSONObject(i)?.optJSONObject("detail") ?: continue
                val limit = detail.optDouble("limit", 1.0)
                val remain = detail.optDouble("remaining", 0.0)
                tiers.add(Tier("5 小时", pct(limit, remain), reset(detail.opt("resetTime"))))
            }
        }
        body.optJSONObject("usage")?.let { usage ->
            val limit = usage.optDouble("limit", 1.0)
            val remain = usage.optDouble("remaining", 0.0)
            tiers.add(Tier("本周", pct(limit, remain), reset(usage.opt("resetTime"))))
        }
        return if (tiers.isEmpty()) QuotaResult(false, error = "无额度字段")
        else QuotaResult(true, tiers = tiers)
    }

    private fun parseZhipu(r: Resp): QuotaResult {
        if (r.code !in 200..299) return failHttp(r)
        val body = JSONObject(r.body)
        if (body.has("success") && !body.optBoolean("success", true)) {
            return QuotaResult(false, error = "接口返回失败：" + body.optString("msg", "未知"))
        }
        val data = body.optJSONObject("data") ?: return QuotaResult(false, error = "无 data")
        val limits = data.optJSONArray("limits") ?: return QuotaResult(false, error = "无 limits")

        var fiveHour: Tier? = null
        var weekly: Tier? = null
        val rest = mutableListOf<Tier>()

        for (i in 0 until limits.length()) {
            val item = limits.optJSONObject(i) ?: continue
            val type = item.optString("type").uppercase()
            if (type != "TOKENS_LIMIT" && type != "CREDIT_LIMIT") continue

            val percentage = item.optDouble("percentage", 0.0)
            val unit = if (item.has("unit")) item.optInt("unit", -1) else -1
            val t = Tier("额度", percentage, reset(item.opt("nextResetTime")))

            when {
                unit == 3 && fiveHour == null -> fiveHour = t.copy(label = "5 小时")
                unit == 6 && weekly == null -> weekly = t.copy(label = "本周")
                else -> rest.add(t)
            }
        }
        for (t in rest) {
            if (fiveHour == null) fiveHour = t.copy(label = "5 小时")
            else if (weekly == null) weekly = t.copy(label = "本周")
        }

        val tiers = listOfNotNull(fiveHour, weekly)
        return if (tiers.isEmpty()) QuotaResult(false, error = "无额度窗口")
        else QuotaResult(true, tiers = tiers)
    }

    private fun parseMinimax(r: Resp): QuotaResult {
        if (r.code !in 200..299) return failHttp(r)
        val body = JSONObject(r.body)
        body.optJSONObject("base_resp")?.let { br ->
            val code = br.optInt("status_code", 0)
            if (code != 0) {
                return QuotaResult(false, error = "MiniMax 返回 $code：" + br.optString("status_msg"))
            }
        }
        val arr = body.optJSONArray("model_remains") ?: return QuotaResult(false, error = "无 model_remains")
        var general: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("model_name") == "general") {
                general = o
                break
            }
        }
        val g = general ?: return QuotaResult(false, error = "没有 general 套餐")

        val tiers = mutableListOf<Tier>()
        g.opt("current_interval_remaining_percent")?.let { v ->
            val remain = toDouble(v) ?: return@let
            tiers.add(Tier("5 小时", (100.0 - remain).coerceIn(0.0, 100.0), reset(g.opt("end_time"))))
        }
        if (g.optInt("current_weekly_status", -1) == 1) {
            g.opt("current_weekly_remaining_percent")?.let { v ->
                val remain = toDouble(v) ?: return@let
                tiers.add(Tier("本周", (100.0 - remain).coerceIn(0.0, 100.0), reset(g.opt("weekly_end_time"))))
            }
        }
        return if (tiers.isEmpty()) QuotaResult(false, error = "无额度窗口")
        else QuotaResult(true, tiers = tiers)
    }

    private fun parseCodex(r: Resp): QuotaResult {
        if (r.code !in 200..299) return failHttp(r)
        val body = JSONObject(r.body)
        val tiers = mutableListOf<Tier>()

        body.optJSONObject("rate_limit")?.let { rl ->
            rl.optJSONObject("primary_window")?.let { w ->
                toDouble(w.opt("used_percent"))?.let { tiers.add(Tier("5 小时", it, reset(w.opt("reset_at")))) }
            }
            rl.optJSONObject("secondary_window")?.let { w ->
                toDouble(w.opt("used_percent"))?.let { tiers.add(Tier("本周", it, reset(w.opt("reset_at")))) }
            }
        }
        if (tiers.isEmpty()) return QuotaResult(false, error = "无 rate_limit 窗口")

        val msg = buildString {
            val plan = body.optString("plan_type")
            if (plan.isNotBlank()) append(plan)
            body.optJSONObject("credits")?.let { c ->
                when {
                    c.optBoolean("unlimited", false) -> append(if (isEmpty()) "" else " · ").append("不限量")
                    c.has("balance") -> append(if (isEmpty()) "" else " · ")
                        .append("Credits $").append(String.format(Locale.US, "%.2f", c.optDouble("balance", 0.0)))
                }
            }
        }
        return QuotaResult(true, tiers = tiers, message = msg.ifBlank { null })
    }

    private fun parseClaude(r: Resp): QuotaResult {
        if (r.code !in 200..299) return failHttp(r)
        val body = JSONObject(r.body)
        val keys = listOf(
            "five_hour" to "5 小时",
            "seven_day" to "本周",
            "seven_day_opus" to "Opus 周",
            "seven_day_sonnet" to "Sonnet 周",
            "seven_day_oauth_apps" to "OAuth 应用周",
        )
        val tiers = mutableListOf<Tier>()
        keys.forEach { (key, label) ->
            val w = body.optJSONObject(key) ?: return@forEach
            val util = toDouble(w.opt("utilization")) ?: return@forEach
            tiers.add(Tier(label, util * 100.0, reset(w.opt("resets_at"))))
        }
        if (tiers.isEmpty()) return QuotaResult(false, error = "无额度窗口")

        val msg = body.optJSONObject("extra_usage")?.let { e ->
            if (e.optBoolean("is_enabled", false)) {
                "超额用量 $" + String.format(Locale.US, "%.2f", e.optDouble("used_credits", 0.0)) +
                    " / $" + String.format(Locale.US, "%.2f", e.optDouble("monthly_limit", 0.0))
            } else null
        }
        return QuotaResult(true, tiers = tiers, message = msg)
    }

    private fun parseCustomJson(r: Resp): QuotaResult {
        if (r.code !in 200..299) return failHttp(r)
        return runCatching {
            val body = JSONObject(r.body)
            val pct = toDouble(body.opt("used_pct"))
            if (pct != null) {
                QuotaResult(true, tiers = listOf(Tier("额度", pct)))
            } else {
                QuotaResult(true, balanceText = r.body.take(200))
            }
        }.getOrElse { QuotaResult(false, error = "解析失败") }
    }

    // ---------------------------------------------------------------- 小工具

    private fun failHttp(r: Resp): QuotaResult {
        val hint = when (r.code) {
            401, 403 -> "鉴权失败（Key 或 token 无效/过期）"
            404 -> "接口不存在（可能是自定义地址写错）"
            429 -> "被限流，稍后再试"
            else -> "HTTP ${r.code}"
        }
        return QuotaResult(false, error = hint)
    }

    private fun pct(limit: Double, remain: Double): Double =
        if (limit <= 0.0) 0.0 else ((limit - remain).coerceAtLeast(0.0) / limit * 100.0)

    private fun toDouble(v: Any?): Double? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    /** 接受 unix 秒 / 毫秒 / ISO 字符串，统一成 "MM-dd HH:mm" 本地时间 */
    private fun reset(v: Any?): String? {
        if (v == null || v == JSONObject.NULL) return null
        return runCatching {
            val raw = when (v) {
                is Number -> v.toLong()
                is String -> {
                    val n = v.toLongOrNull()
                    if (n != null) n else return@runCatching formatIso(v)
                }
                else -> return@runCatching null
            }
            val ms = if (raw < 10_000_000_000L) raw * 1000 else raw
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
        }.getOrNull()
    }

    private fun formatIso(s: String): String? = runCatching {
        val cleaned = s.replace("Z", "+0000").replace(":", "")
        val hasZone = cleaned.contains("+") || cleaned.indexOf('-', 10) >= 0
        val parser = if (hasZone) {
            SimpleDateFormat("yyyy-MM-dd'T'HHmmssZ", Locale.US)
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        }
        val parsed = parser.parse(cleaned) ?: return@runCatching null
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(parsed)
    }.getOrNull()

    @Suppress("unused")
    private fun arrayOfObjects(a: JSONArray): List<JSONObject> =
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

package io.paimon.quota.data

import io.paimon.quota.data.provider.ChatgptPlusProvider
import io.paimon.quota.data.provider.CodexFeelolProvider
import io.paimon.quota.data.provider.GeminiProvider
import io.paimon.quota.data.provider.MimoProvider
import io.paimon.quota.data.provider.OpencodeGoProvider
import io.paimon.quota.data.provider.VolcengineProvider
import io.paimon.quota.data.provider.ZenmuxProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * 额度查询总入口：按 [ProviderKind] 路由到各家的解析器。
 *
 * 端点与解析规则移植自原 uni-app 版 services/quota 目录（规格见其 docs/provider-api-spec.md）。
 */
object QuotaApi {

    suspend fun fetch(p: Provider): QuotaResult = withContext(Dispatchers.IO) {
        if (p.kind != ProviderKind.CUSTOM &&
            p.kind != ProviderKind.CHATGPT_PLUS &&
            p.kind != ProviderKind.MIMO &&
            p.token.isBlank()
        ) {
            return@withContext QuotaResult(false, error = "还没填凭据")
        }
        try {
            when (p.kind) {
                ProviderKind.DEEPSEEK -> parseDeepseek(
                    Http.get("https://api.deepseek.com/user/balance", bearer(p.token))
                )

                ProviderKind.KIMI -> parseKimi(
                    Http.get("https://api.kimi.com/coding/v1/usages", bearer(p.token))
                )

                ProviderKind.ZHIPU -> parseZhipu(zhipuRequest(p, team = false))
                ProviderKind.ZHIPU_TEAM -> parseZhipu(zhipuRequest(p, team = true))

                ProviderKind.MINIMAX -> parseMinimax(
                    Http.get(
                        "https://${minimaxDomain(p.baseUrl)}/v1/api/openplatform/coding_plan/remains",
                        bearer(p.token) + mapOf("Content-Type" to "application/json"),
                    )
                )

                ProviderKind.ZENMUX -> ZenmuxProvider.fetch(p)
                ProviderKind.OPENCODE_GO -> OpencodeGoProvider.fetch(p)
                ProviderKind.VOLCENGINE -> VolcengineProvider.fetch(p)
                ProviderKind.CODEX_FEELOL -> CodexFeelolProvider.fetch(p)
                ProviderKind.CHATGPT_PLUS -> ChatgptPlusProvider.fetch(p)
                ProviderKind.MIMO -> MimoProvider.fetch(p)
                ProviderKind.GEMINI -> GeminiProvider.fetch(p)

                ProviderKind.CODEX -> parseCodex(
                    Http.get(
                        "https://chatgpt.com/backend-api/wham/usage",
                        bearer(p.token) + mapOf(
                            "User-Agent" to "CodingPlanQuota",
                            "Accept" to "application/json",
                        ) + p.extra["accountId"]?.takeIf { it.isNotBlank() }
                            ?.let { mapOf("ChatGPT-Account-Id" to it) }.orEmpty(),
                    )
                )

                ProviderKind.ANTHROPIC -> parseClaude(
                    Http.get(
                        "https://platform.claude.com/api/oauth/usage",
                        bearer(p.token) + mapOf(
                            "anthropic-beta" to "oauth-2025-04-20",
                            "Accept" to "application/json",
                        ),
                    )
                )

                ProviderKind.CUSTOM -> parseCustomJson(
                    Http.get(p.baseUrl.trim(), bearer(p.token))
                )
            }
        } catch (e: Exception) {
            QuotaResult(false, error = e.message ?: "网络错误")
        }
    }

    // ---------------------------------------------------------------- 智谱

    private fun zhipuRequest(p: Provider, team: Boolean): HttpResp {
        val headers = mutableMapOf(
            "Authorization" to p.token, // 智谱不加 Bearer 前缀
            "Content-Type" to "application/json",
            "Accept-Language" to "en-US,en",
        )
        val url = if (team) {
            headers["bigmodel-organization"] = p.extra["organizationId"].orEmpty()
            headers["bigmodel-project"] = p.extra["projectId"].orEmpty()
            "https://open.bigmodel.cn/api/monitor/usage/quota/limit?type=2"
        } else {
            val base = if (p.baseUrl.contains("z.ai")) "https://api.z.ai" else "https://open.bigmodel.cn"
            "$base/api/monitor/usage/quota/limit"
        }
        return Http.get(url, headers)
    }

    private fun parseZhipu(r: HttpResp): QuotaResult {
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
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
            val type = item.optString("type").uppercase(Locale.US)
            if (type != "TOKENS_LIMIT" && type != "CREDIT_LIMIT") continue

            val percentage = (toDouble(item.opt("percentage")) ?: 0.0).coerceIn(0.0, 100.0)
            val unit = toInt(item.opt("unit"), -1)
            val t = Tier("额度", percentage, resetText(item.opt("nextResetTime")))

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
        val level = data.optString("level").ifBlank { null }
        return if (tiers.isEmpty()) QuotaResult(false, error = "没有额度窗口")
        else QuotaResult(true, tiers = tiers, message = level)
    }

    // ---------------------------------------------------------------- 其余内置解析

    private fun parseDeepseek(r: HttpResp): QuotaResult {
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
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
            if (granted > 0) append(" · 含赠送 ").append(sym).append(String.format(Locale.US, "%.2f", granted))
        }
        return QuotaResult(true, balanceText = text)
    }

    private fun parseKimi(r: HttpResp): QuotaResult {
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
        val tiers = mutableListOf<Tier>()

        body.optJSONArray("limits")?.let { limits ->
            for (i in 0 until limits.length()) {
                val detail = limits.optJSONObject(i)?.optJSONObject("detail") ?: continue
                val limit = toDouble(detail.opt("limit")) ?: 1.0
                val remain = toDouble(detail.opt("remaining")) ?: 0.0
                tiers.add(
                    Tier(
                        "5 小时",
                        usedPct(limit - remain, limit),
                        resetText(detail.opt("resetTime")),
                    )
                )
            }
        }
        body.optJSONObject("usage")?.let { usage ->
            val limit = toDouble(usage.opt("limit")) ?: 1.0
            val remain = toDouble(usage.opt("remaining")) ?: 0.0
            tiers.add(Tier("本周", usedPct(limit - remain, limit), resetText(usage.opt("resetTime"))))
        }
        return if (tiers.isEmpty()) QuotaResult(false, error = "无额度字段")
        else QuotaResult(true, tiers = tiers)
    }

    private fun minimaxDomain(baseUrl: String): String =
        if (baseUrl.lowercase(Locale.US).contains("minimax.io")) "api.minimax.io" else "api.minimaxi.com"

    private fun parseMinimax(r: HttpResp): QuotaResult {
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
        body.optJSONObject("base_resp")?.let { br ->
            val code = toInt(br.opt("status_code"), 0)
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
        toDouble(g.opt("current_interval_remaining_percent"))?.let { remain ->
            tiers.add(Tier("5 小时", (100.0 - remain).coerceIn(0.0, 100.0), resetText(g.opt("end_time"))))
        }
        if (toInt(g.opt("current_weekly_status"), -1) == 1) {
            toDouble(g.opt("current_weekly_remaining_percent"))?.let { remain ->
                tiers.add(Tier("本周", (100.0 - remain).coerceIn(0.0, 100.0), resetText(g.opt("weekly_end_time"))))
            }
        }
        return if (tiers.isEmpty()) QuotaResult(false, error = "无额度窗口")
        else QuotaResult(true, tiers = tiers)
    }

    private fun parseCodex(r: HttpResp): QuotaResult {
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
        val tiers = mutableListOf<Tier>()

        body.optJSONObject("rate_limit")?.let { rl ->
            rl.optJSONObject("primary_window")?.let { w ->
                toDouble(w.opt("used_percent"))?.let {
                    tiers.add(Tier("5 小时", it.coerceIn(0.0, 100.0), resetText(w.opt("reset_at"))))
                }
            }
            rl.optJSONObject("secondary_window")?.let { w ->
                toDouble(w.opt("used_percent"))?.let {
                    tiers.add(Tier("本周", it.coerceIn(0.0, 100.0), resetText(w.opt("reset_at"))))
                }
            }
        }
        if (tiers.isEmpty()) return QuotaResult(false, error = "无 rate_limit 窗口")

        val msg = buildString {
            body.optString("plan_type").takeIf { it.isNotBlank() }?.let { append(it) }
            body.optJSONObject("credits")?.let { c ->
                when {
                    c.optBoolean("unlimited", false) ->
                        append(if (isEmpty()) "" else " · ").append("不限量")

                    c.has("balance") ->
                        append(if (isEmpty()) "" else " · ")
                            .append("Credits $")
                            .append(String.format(Locale.US, "%.2f", c.optDouble("balance", 0.0)))
                }
            }
        }
        return QuotaResult(true, tiers = tiers, message = msg.ifBlank { null })
    }

    private fun parseClaude(r: HttpResp): QuotaResult {
        if (!r.ok) return failHttp(r)
        val body = r.json ?: return QuotaResult(false, error = "返回不是 JSON")
        val pairs = listOf(
            "five_hour" to "5 小时",
            "seven_day" to "本周",
            "seven_day_opus" to "Opus 周",
            "seven_day_sonnet" to "Sonnet 周",
            "seven_day_oauth_apps" to "OAuth 应用周",
        )
        val tiers = mutableListOf<Tier>()
        pairs.forEach { (key, label) ->
            val w = body.optJSONObject(key) ?: return@forEach
            val util = toDouble(w.opt("utilization")) ?: return@forEach
            tiers.add(Tier(label, (util * 100.0).coerceIn(0.0, 100.0), resetText(w.opt("resets_at"))))
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

    private fun parseCustomJson(r: HttpResp): QuotaResult {
        if (!r.ok) return failHttp(r)
        return runCatching {
            val pct = r.json?.let { toDouble(it.opt("used_pct")) }
            if (pct != null) {
                QuotaResult(true, tiers = listOf(Tier("额度", pct.coerceIn(0.0, 100.0))))
            } else {
                QuotaResult(true, balanceText = r.body.take(200))
            }
        }.getOrElse { QuotaResult(false, error = "解析失败") }
    }
}

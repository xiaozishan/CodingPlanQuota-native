package io.paimon.quota.data.provider

import io.paimon.quota.data.Http
import io.paimon.quota.data.HttpResp
import io.paimon.quota.data.Provider
import io.paimon.quota.data.QuotaResult
import io.paimon.quota.data.Tier
import io.paimon.quota.data.resetText
import io.paimon.quota.data.toDouble
import io.paimon.quota.data.toStr
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import kotlin.math.roundToLong

/** 阿里云百炼 Coding Plan 的两个官方控制台区域。 */
internal enum class QwenCodingPlanRegion(
    val gateway: String,
    val regionId: String,
    val commodityCode: String,
    val dashboardUrl: String,
) {
    INTERNATIONAL(
        gateway = "https://modelstudio.console.alibabacloud.com",
        regionId = "ap-southeast-1",
        commodityCode = "sfm_codingplan_public_intl",
        dashboardUrl = "https://modelstudio.console.alibabacloud.com/ap-southeast-1/?tab=globalset#/efm/coding_plan",
    ),
    CHINA(
        gateway = "https://bailian.console.aliyun.com",
        regionId = "cn-beijing",
        commodityCode = "sfm_codingplan_public_cn",
        dashboardUrl = "https://bailian.console.aliyun.com/cn-beijing/?tab=model#/efm/coding_plan",
    ),
}

/** 实际请求的控制台 Origin 与所属分区；自定义地址复用对应分区的商品代码。 */
internal data class QwenConsoleEndpoint(
    val gateway: String,
    val regionId: String,
    val commodityCode: String,
    val dashboardUrl: String,
)

internal typealias QwenPoster = (String, String, Map<String, String>) -> HttpResp

/**
 * 千问 / 阿里云百炼 Coding Plan 的控制台会话额度查询。
 *
 * 默认携带用户从对应区域控制台复制的完整 Cookie，调用只读 RPC：
 * POST <区域控制台>/data/api.json?action=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2
 * 套餐专属 API Key 仍保留为可选备用鉴权方式。
 *
 * 参考（2026-11-12 交叉核对）：
 * - 阿里云官方 CLI：modelstudioai/cli `packages/commands/src/commands/usage/coding-plan.ts`
 * - Qwen Code 官方文档的 Coding Plan Key / 区域说明
 * - CodexBar：AlibabaCodingPlanUsageFetcher.swift（API Key 请求与响应解析）
 */
internal object QwenCodingPlanProvider {
    val DASHBOARD_URL = QwenCodingPlanRegion.CHINA.dashboardUrl
    val INTL_DASHBOARD_URL = QwenCodingPlanRegion.INTERNATIONAL.dashboardUrl

    private const val ACTION =
        "zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2"
    private const val API_KEY_LIMIT = 4_096
    private const val COOKIE_LIMIT = 32_768
    private const val RESPONSE_SNIPPET_LIMIT = 500

    /** 单次请求只使用一种鉴权，避免把备用 Key 与控制台会话混发。 */
    private data class ConsoleAuth(
        val cookie: String = "",
        val apiKey: String = "",
    ) {
        val usesSession: Boolean get() = cookie.isNotBlank()
    }

    /** 控制台首页、分区页面或 /data/api.json 地址归一为控制台 Origin。 */
    internal fun resolveConsole(raw: String): QwenConsoleEndpoint? {
        val text = raw.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (scheme != "https" || host.isBlank() || uri.userInfo != null) return null
        val gateway = "https://$host" + if (uri.port >= 0) ":${uri.port}" else ""
        val suffix = (uri.rawPath.orEmpty() + "?" + uri.rawQuery.orEmpty() + "#" +
            uri.rawFragment.orEmpty()).lowercase(Locale.US)
        val intlHost = URI(QwenCodingPlanRegion.INTERNATIONAL.gateway).host
        val chinaHost = URI(QwenCodingPlanRegion.CHINA.gateway).host
        val region = when {
            host == intlHost -> QwenCodingPlanRegion.INTERNATIONAL
            host == chinaHost -> QwenCodingPlanRegion.CHINA
            host.endsWith(".alibabacloud.com") || host == "alibabacloud.com" ->
                QwenCodingPlanRegion.INTERNATIONAL
            host.endsWith(".aliyun.com") || host == "aliyun.com" ->
                QwenCodingPlanRegion.CHINA
            "ap-southeast-1" in suffix -> QwenCodingPlanRegion.INTERNATIONAL
            "cn-beijing" in suffix -> QwenCodingPlanRegion.CHINA
            else -> QwenCodingPlanRegion.CHINA
        }
        val hasConsolePath = !uri.path.isNullOrBlank() && uri.path != "/"
        return QwenConsoleEndpoint(
            gateway = gateway,
            regionId = region.regionId,
            commodityCode = region.commodityCode,
            dashboardUrl = if (hasConsolePath) text else region.dashboardUrl,
        )
    }

    suspend fun fetch(
        p: Provider,
        poster: QwenPoster = { url, body, headers -> Http.post(url, body, headers) },
    ): QuotaResult {
        val apiKey = p.token.removePrefix("\uFEFF").trim()
        val cookie = p.extra["cookie"].orEmpty().removePrefix("\uFEFF").trim()
        if (cookie.isBlank() && apiKey.isBlank()) {
            return QuotaResult(false, error = "还没填千问控制台 Cookie（也可填套餐 API Key 备用）")
        }
        if (cookie.length > COOKIE_LIMIT || cookie.any { it == '\r' || it == '\n' || it < ' ' }) {
            return QuotaResult(false, error = "千问控制台 Cookie 格式无效")
        }
        if (cookie.isBlank() &&
            (apiKey.length > API_KEY_LIMIT || apiKey.any { it == '\r' || it == '\n' || it < ' ' })
        ) {
            return QuotaResult(false, error = "Coding Plan API Key 格式无效")
        }
        val auth = ConsoleAuth(cookie = cookie, apiKey = apiKey)

        val customConsole = p.baseUrl.removePrefix("\uFEFF").trim()
        if (customConsole.isNotEmpty()) {
            val endpoint = resolveConsole(customConsole) ?: return QuotaResult(
                false,
                error = "千问控制台地址无效：请输入不带账号信息的完整 HTTPS 地址",
            )
            // 显式指定分区后只查该入口，避免错误分区被第二次请求掩盖。
            return fetchOnce(endpoint, poster, auth)
        }

        // 未填控制台地址时保留原行为：先国际站再试国内站。
        val international = fetchOnce(QwenCodingPlanRegion.INTERNATIONAL, poster, auth)
        if (international.ok) return international
        val china = fetchOnce(QwenCodingPlanRegion.CHINA, poster, auth)
        if (china.ok) return china

        // 第二次结果通常更贴近国内账号；保留国际站错误可帮助诊断区域限制。
        val firstError = international.error.orEmpty()
        val secondError = china.error.orEmpty()
        val combined = when {
            firstError.isBlank() -> secondError
            secondError.isBlank() -> firstError
            firstError == secondError -> secondError
            else -> "国际站：$firstError；国内站：$secondError"
        }
        return QuotaResult(false, error = combined)
    }

    internal fun requestUrl(endpoint: QwenConsoleEndpoint): String =
        "${endpoint.gateway}/data/api.json?action=$ACTION" +
            "&product=broadscope-bailian&api=queryCodingPlanInstanceInfoV2" +
            "&currentRegionId=${endpoint.regionId}"

    internal fun requestUrl(region: QwenCodingPlanRegion): String =
        "${region.gateway}/data/api.json?action=$ACTION" +
            "&product=broadscope-bailian&api=queryCodingPlanInstanceInfoV2" +
            "&currentRegionId=${region.regionId}"

    internal fun requestBody(endpoint: QwenConsoleEndpoint): String = JSONObject()
        .put(
            "queryCodingPlanInstanceInfoRequest",
            JSONObject()
                .put("commodityCode", endpoint.commodityCode)
                .put("onlyLatestOne", true),
        )
        .toString()

    internal fun requestBody(region: QwenCodingPlanRegion): String = JSONObject()
        .put(
            "queryCodingPlanInstanceInfoRequest",
            JSONObject()
                .put("commodityCode", region.commodityCode)
                .put("onlyLatestOne", true),
        )
        .toString()

    internal fun apiHeaders(endpoint: QwenConsoleEndpoint, apiKey: String): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer $apiKey",
            "x-api-key" to apiKey,
            "X-DashScope-API-Key" to apiKey,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Origin" to endpoint.gateway,
            "Referer" to endpoint.dashboardUrl,
            "User-Agent" to "CodingPlanQuota/0.6.7 (Android)",
        )

    internal fun apiHeaders(region: QwenCodingPlanRegion, apiKey: String): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer $apiKey",
            "x-api-key" to apiKey,
            "X-DashScope-API-Key" to apiKey,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Origin" to region.gateway,
            "Referer" to region.dashboardUrl,
            "User-Agent" to "CodingPlanQuota/0.6.7 (Android)",
        )

    internal fun sessionHeaders(
        endpoint: QwenConsoleEndpoint,
        cookie: String,
    ): Map<String, String> = mapOf(
        "Cookie" to cookie,
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Origin" to endpoint.gateway,
        "Referer" to endpoint.dashboardUrl,
        "User-Agent" to "CodingPlanQuota/0.6.7 (Android)",
    )

    internal fun sessionHeaders(
        region: QwenCodingPlanRegion,
        cookie: String,
    ): Map<String, String> = mapOf(
        "Cookie" to cookie,
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Origin" to region.gateway,
        "Referer" to region.dashboardUrl,
        "User-Agent" to "CodingPlanQuota/0.6.7 (Android)",
    )

    private fun authHeaders(
        endpoint: QwenConsoleEndpoint,
        auth: ConsoleAuth,
    ): Map<String, String> = if (auth.usesSession) {
        sessionHeaders(endpoint, auth.cookie)
    } else {
        apiHeaders(endpoint, auth.apiKey)
    }

    private fun authHeaders(
        region: QwenCodingPlanRegion,
        auth: ConsoleAuth,
    ): Map<String, String> = if (auth.usesSession) {
        sessionHeaders(region, auth.cookie)
    } else {
        apiHeaders(region, auth.apiKey)
    }

    private fun fetchOnce(
        endpoint: QwenConsoleEndpoint,
        poster: QwenPoster,
        auth: ConsoleAuth,
    ): QuotaResult = try {
        parse(poster(requestUrl(endpoint), requestBody(endpoint), authHeaders(endpoint, auth)))
    } catch (e: Exception) {
        QuotaResult(false, error = "千问控制台网络错误：${e.message ?: e.javaClass.simpleName}")
    }

    private fun fetchOnce(
        region: QwenCodingPlanRegion,
        poster: QwenPoster,
        auth: ConsoleAuth,
    ): QuotaResult = try {
        parse(poster(requestUrl(region), requestBody(region), authHeaders(region, auth)))
    } catch (e: Exception) {
        QuotaResult(false, error = "千问控制台网络错误：${e.message ?: e.javaClass.simpleName}")
    }

    internal fun parse(response: HttpResp): QuotaResult {
        when (response.code) {
            in 200..299 -> Unit
            401, 403 -> return failure(
                response,
                "控制台会话无效 / 已过期，或该区域没有套餐权限",
            )
            429 -> return failure(response, "千问额度接口被限流，请稍后再试")
            else -> return failure(response, "千问额度接口请求失败")
        }

        val root = response.json ?: return failure(response, "千问额度返回不是 JSON")
        responseError(root, response)?.let { return it }
        val usage = findUsage(root) ?: return failure(
            response,
            "未找到有效 Coding Plan 套餐额度；请确认控制台登录分区与套餐状态",
        )

        val tiers = mutableListOf<Tier>()
        addWindow(tiers, usage.quota, "5 小时", "per5Hour", "perFiveHour")
        addWindow(tiers, usage.quota, "本周", "perWeek")
        addWindow(tiers, usage.quota, "本月", "perBillMonth", "perMonth")
        if (tiers.isEmpty()) {
            return failure(response, "千问返回中没有可计算的 5 小时 / 本周 / 本月额度")
        }

        val plan = usage.plan?.takeIf { it.isNotBlank() }
        val message = buildList {
            add(if (plan == null) "Coding Plan" else "Coding Plan $plan")
            usage.remainingDays?.takeIf { it >= 0.0 }
                ?.let { add("剩余 ${it.roundToLong()} 天") }
        }.joinToString(" · ")
        return QuotaResult(true, tiers = tiers, message = message)
    }

    private fun responseError(root: JSONObject, response: HttpResp): QuotaResult? {
        val code = firstString(root, "code", "Code")?.trim().orEmpty()
        val message = firstString(root, "message", "Message").orEmpty()
        if (code.contains("login", ignoreCase = true) ||
            message.contains("notlogined", ignoreCase = true) ||
            message.contains("needlogin", ignoreCase = true)
        ) {
            return failure(response, "控制台会话无效或已过期（$code）")
        }

        val failed = listOf("success", "Success", "successResponse").any { name ->
            root.opt(name).let { it is Boolean && !it }
        }
        val badCode = code.isNotEmpty() && code !in setOf("0", "200", "Success", "success", "SUCCESS")
        if (failed || badCode) {
            return failure(response, "千问额度接口返回失败" + if (code.isBlank()) "" else "：$code")
        }
        return null
    }

    /** 所有上游失败都带状态码和限量响应片段，便于在卡片上直接判断登录 / 分区问题。 */
    private fun failure(response: HttpResp, reason: String): QuotaResult {
        val snippet = response.body
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(RESPONSE_SNIPPET_LIMIT)
            .ifBlank { "<空响应>" }
        return QuotaResult(
            false,
            error = "$reason（HTTP ${response.code}）；响应：$snippet",
        )
    }

    private data class UsagePayload(
        val quota: JSONObject,
        val plan: String?,
        val remainingDays: Double? = null,
    )

    /** 防御式展开 data / DataV2 以及字符串内嵌 JSON，只采用有效套餐的真实计数。 */
    private fun findUsage(input: Any?, depth: Int = 0): UsagePayload? {
        if (depth > 16) return null
        return when (val value = expandJson(input)) {
            is JSONObject -> {
                asObject(value.opt("codingPlanQuotaInfo"))?.let { quota ->
                    return UsagePayload(quota, planName(value), remainingDays(value))
                }
                if (hasWindowCounters(value)) {
                    return UsagePayload(value, planName(value), remainingDays(value))
                }

                val instances = asArray(value.opt("codingPlanInstanceInfos"))
                if (instances != null) {
                    var unspecified: UsagePayload? = null
                    for (i in 0 until instances.length()) {
                        val instance = asObject(instances.opt(i)) ?: continue
                        val quota = asObject(instance.opt("codingPlanQuotaInfo"))
                            ?: instance.takeIf(::hasWindowCounters)
                            ?: continue
                        val candidate = UsagePayload(
                            quota,
                            planName(instance),
                            remainingDays(instance),
                        )
                        when (instance.optString("status").uppercase(Locale.US)) {
                            "VALID", "ACTIVE" -> return candidate
                            "" -> if (unspecified == null) unspecified = candidate
                        }
                    }
                    // 有实例列表时，绝不拿已失效实例的计数冒充当前额度。
                    return unspecified
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    findUsage(value.opt(key), depth + 1)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    findUsage(value.opt(i), depth + 1)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun addWindow(
        target: MutableList<Tier>,
        quota: JSONObject,
        label: String,
        vararg prefixes: String,
    ) {
        for (prefix in prefixes) {
            val used = number(quota, "${prefix}UsedQuota")
            val total = number(quota, "${prefix}TotalQuota")
            if (used == null || total == null || used < 0.0 || total <= 0.0) continue
            val reset = firstValue(
                quota,
                "${prefix}QuotaNextRefreshTime",
                "${prefix}NextRefreshTime",
            )
            target.add(
                Tier(
                    label = label,
                    usedPct = (used / total * 100.0).coerceIn(0.0, 100.0),
                    resetAt = resetText(reset),
                    valueText = "${formatCount(used.roundToLong())}\n/ ${formatCount(total.roundToLong())}",
                ),
            )
            return
        }
    }

    private fun hasWindowCounters(value: JSONObject): Boolean =
        listOf("per5Hour", "perFiveHour", "perWeek", "perBillMonth", "perMonth").any { prefix ->
            value.has("${prefix}UsedQuota") || value.has("${prefix}TotalQuota")
        }

    private fun planName(value: JSONObject): String? = listOf(
        "instanceType", "planName", "instanceName", "packageName", "specCode",
    ).firstNotNullOfOrNull { name ->
        value.optString(name).trim().takeIf { it.isNotBlank() }
    }

    private fun remainingDays(value: JSONObject): Double? =
        toDouble(value.opt("remainingDays"))?.takeIf { it.isFinite() }

    private fun number(value: JSONObject, name: String): Double? =
        toDouble(value.opt(name))?.takeIf { it.isFinite() }

    private fun firstValue(value: JSONObject, vararg names: String): Any? {
        for (name in names) {
            val item = value.opt(name)
            if (item != null && item != JSONObject.NULL) return item
        }
        return null
    }

    private fun firstString(value: JSONObject, vararg names: String): String? {
        for (name in names) {
            val text = toStr(value.opt(name))
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun expandJson(value: Any?): Any? = when (value) {
        is String -> {
            val text = value.trim()
            when {
                text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
                text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()
                else -> null
            }
        }
        else -> value
    }

    private fun asObject(value: Any?): JSONObject? = when (val expanded = expandJson(value)) {
        is JSONObject -> expanded
        else -> null
    }

    private fun asArray(value: Any?): JSONArray? = when (val expanded = expandJson(value)) {
        is JSONArray -> expanded
        else -> null
    }

    private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)
}

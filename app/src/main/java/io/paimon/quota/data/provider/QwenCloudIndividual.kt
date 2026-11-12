package io.paimon.quota.data.provider

import io.paimon.quota.data.Http
import io.paimon.quota.data.HttpResp
import io.paimon.quota.data.Provider
import io.paimon.quota.data.QuotaResult
import io.paimon.quota.data.Tier
import io.paimon.quota.data.resetText
import io.paimon.quota.data.toDouble
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Qwen Cloud 国际站 Individual Token Plan 的网页控制台额度接口。
 *
 * 推理 API Key 不能查询套餐额度；这里复用控制台自身的 Cookie + sec_token：
 * 1. GET home.qwencloud.com/tool/user/info.json 取 data.secToken；
 * 2. POST cs-data.qwencloud.com/data/api.json，调用 personal/api/v2/usage；
 * 3. subscription / quota-config 仅补充套餐名和次数，失败不影响真实用量展示。
 *
 * 非公开 API 可能变动。参考（2026-11-11）：
 * https://github.com/steipete/CodexBar/blob/main/docs/qwen-cloud.md
 * https://github.com/alvinlui-hk/dsh-coding-plan-usage
 */
internal object QwenCloudIndividualProvider {
    const val DASHBOARD_URL =
        "https://home.qwencloud.com/billing/subscription/token-plan-individual"

    private const val USER_INFO_URL = "https://home.qwencloud.com/tool/user/info.json"
    private const val GATEWAY_URL = "https://cs-data.qwencloud.com/data/api.json"
    private const val COMMODITY_CODE = "sfm_tokenplansolo_public_intl"
    private const val CONSOLE_PRODUCT = "sfm_bailian"
    private const val CONSOLE_ACTION = "IntlBroadScopeAspnGateway"
    private const val USAGE_API = "zeldaHttp.apikeyMgr./tokenplan/personal/api/v2/usage"
    private const val SUBSCRIPTION_API =
        "zeldaHttp.apikeyMgr./tokenplan/personal/api/v2/subscription"
    private const val QUOTA_CONFIG_API =
        "zeldaHttp.apikeyMgr./tokenplan/personal/api/v2/quota-config"
    private const val COOKIE_LIMIT = 32_768

    suspend fun fetch(p: Provider): QuotaResult {
        val rawCookie = p.extra["cookie"].orEmpty()
        if (rawCookie.isBlank()) {
            return QuotaResult(false, error = "还没填 Qwen Cloud 网页 Cookie（推理 API Key 不能查额度）")
        }
        val cookie = try {
            normalizeCookie(rawCookie)
        } catch (e: IllegalArgumentException) {
            return QuotaResult(false, error = e.message ?: "Cookie 格式无效")
        }

        val userInfo = Http.get(USER_INFO_URL, consoleHeaders(cookie))
        httpFailure(userInfo)?.let { return it }
        val userJson = userInfo.json
            ?: return QuotaResult(false, error = "千问登录已失效，请重新复制 Qwen Cloud Cookie")
        loginFailure(userJson)?.let { return it }
        val userData = asObject(userJson.opt("data")) ?: userJson
        val secToken = userData.optString("secToken").ifBlank {
            userData.optString("sec_token")
        }.ifBlank {
            cookieValue(cookie, "sec_token").orEmpty()
        }
        if (secToken.isBlank()) {
            return QuotaResult(
                false,
                error = "千问会话未返回 sec_token；请重新登录 Individual 套餐页并复制完整 Cookie",
            )
        }

        val usage = gateway(USAGE_API, cookie, secToken)
        httpFailure(usage)?.let { return it }
        val usageJson = usage.json
            ?: return QuotaResult(false, error = "千问额度接口返回的不是 JSON，可能登录已失效")
        loginFailure(usageJson)?.let { return it }

        // 元数据只用于补充显示；usage 已成功时，不因可选接口失败而隐藏真实额度。
        val subscription = runCatching {
            gateway(SUBSCRIPTION_API, cookie, secToken).takeIf { it.ok && it.json != null }
        }.getOrNull()
        val quotaConfig = runCatching {
            gateway(QUOTA_CONFIG_API, cookie, secToken).takeIf { it.ok && it.json != null }
        }.getOrNull()
        return parse(usage, subscription, quotaConfig)
    }

    /** 允许直接粘贴浏览器 Network 里的完整 Cookie 头；不会接受推理 API Key。 */
    internal fun normalizeCookie(raw: String): String {
        var value = raw.removePrefix("\uFEFF").trim()
        value = value.replace(Regex("^cookie\\s*:\\s*", RegexOption.IGNORE_CASE), "").trim()
        if (value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') ||
                (value.first() == '\'' && value.last() == '\''))
        ) {
            value = value.substring(1, value.length - 1).trim()
        }
        value = value.replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        require(value.isNotEmpty()) { "Cookie 不能为空" }
        require(value.length <= COOKIE_LIMIT) { "Cookie 过长，请只复制 qwencloud.com 请求的 Cookie 头" }
        require(!value.startsWith("sk-", ignoreCase = true)) {
            "这里需要 Qwen Cloud 网页 Cookie，不是推理 API Key"
        }
        require(value.contains('=')) { "Cookie 格式无效，应包含 name=value；不要填推理 API Key" }
        require(value.none { it < ' ' && it != '\t' }) { "Cookie 含有非法控制字符" }
        return value
    }

    private fun consoleHeaders(cookie: String): Map<String, String> = buildMap {
        put("Cookie", cookie)
        put("Accept", "application/json, text/plain, */*")
        put("Origin", "https://home.qwencloud.com")
        put("Referer", DASHBOARD_URL)
        put("X-Requested-With", "XMLHttpRequest")
        val csrf = cookieValue(cookie, "login_aliyunid_csrf")
            ?: cookieValue(cookie, "csrf")
        if (!csrf.isNullOrBlank()) {
            put("x-xsrf-token", csrf)
            put("x-csrf-token", csrf)
        }
    }

    private fun gateway(api: String, cookie: String, secToken: String): HttpResp {
        val cornerstone = JSONObject()
            .put("feTraceId", UUID.randomUUID().toString().lowercase(Locale.US))
            .put("feURL", DASHBOARD_URL)
            .put("protocol", "V2")
            .put("console", "ONE_CONSOLE")
            .put("productCode", "p_efm")
            .put("domain", "home.qwencloud.com")
            .put("consoleSite", "QWENCLOUD")
            .put("userNickName", "")
            .put("userPrincipalName", "")
            .put("xsp_lang", "en")
        cookieValue(cookie, "cna")?.let { cornerstone.put("X-Anonymous-Id", it) }
        val params = JSONObject()
            .put("Api", api)
            .put("V", "1.0")
            .put(
                "Data",
                JSONObject()
                    .put("commodityCode", COMMODITY_CODE)
                    .put("cornerstoneParam", cornerstone),
            )
        val body = linkedMapOf(
            "product" to CONSOLE_PRODUCT,
            "action" to CONSOLE_ACTION,
            "sec_token" to secToken,
            "region" to "ap-southeast-1",
            "language" to "en-US",
            "params" to params.toString(),
        ).entries.joinToString("&") { (key, value) -> "${formEncode(key)}=${formEncode(value)}" }
        val url = "$GATEWAY_URL?product=$CONSOLE_PRODUCT&action=$CONSOLE_ACTION" +
            "&api=${formEncode(api)}&_v=undefined"
        return Http.post(
            url,
            body,
            consoleHeaders(cookie) + ("Content-Type" to "application/x-www-form-urlencoded"),
        )
    }

    private fun formEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun cookieValue(cookie: String, name: String): String? =
        cookie.split(';').asSequence()
            .map { it.trim() }
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null else pair.substring(0, index).trim() to
                    pair.substring(index + 1).trim()
            }
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.second
            ?.takeIf { it.isNotBlank() }

    private fun httpFailure(r: HttpResp): QuotaResult? = when (r.code) {
        in 200..299 -> null
        401, 403, in 300..399 -> QuotaResult(
            false,
            error = "千问登录已失效（HTTP ${r.code}），请重新复制 Qwen Cloud Cookie",
        )
        else -> QuotaResult(false, error = "千问额度接口 HTTP ${r.code}")
    }

    private fun loginFailure(body: JSONObject): QuotaResult? {
        val code = body.optString("code").ifBlank { body.optString("Code") }
        val success = when {
            body.has("success") -> body.optBoolean("success", true)
            body.has("Success") -> body.optBoolean("Success", true)
            body.has("successResponse") -> body.optBoolean("successResponse", true)
            else -> true
        }
        if (code.equals("ConsoleNeedLogin", ignoreCase = true) ||
            code == "401" || code == "403"
        ) {
            return QuotaResult(false, error = "千问登录已失效，请重新复制 Qwen Cloud Cookie")
        }
        if (!success) {
            val suffix = code.ifBlank { "（未提供错误码）" }
            return QuotaResult(
                false,
                error = "千问额度接口返回失败" + if (suffix.startsWith("（")) suffix else "：$suffix",
            )
        }
        return null
    }

    internal fun parse(
        usageResponse: HttpResp,
        subscriptionResponse: HttpResp? = null,
        quotaResponse: HttpResp? = null,
    ): QuotaResult {
        httpFailure(usageResponse)?.let { return it }
        val usageRoot = usageResponse.json
            ?: return QuotaResult(false, error = "千问额度返回不是 JSON")
        loginFailure(usageRoot)?.let { return it }
        val usage = unwrapZelda(usageRoot)
            ?: return QuotaResult(false, error = "千问额度返回缺少 data")
        val subscription = subscriptionResponse?.json?.let(::unwrapZelda)
        val quotaRoot = quotaResponse?.json?.let(::unwrapZelda)
        val specCode = subscription?.optString("specCode")?.trim().orEmpty()
        val quota = quotaFor(quotaRoot, specCode)

        val tiers = mutableListOf<Tier>()
        addWindow(
            tiers,
            label = "5 小时",
            fractionValue = usage.opt("per5HourPercentage"),
            resetValue = usage.opt("per5HourResetTime"),
            total = quota?.number("five_hour", "fiveHour"),
        )
        addWindow(
            tiers,
            label = "本周",
            fractionValue = usage.opt("per1WeekPercentage"),
            resetValue = usage.opt("per1WeekResetTime"),
            total = quota?.number("weekly", "week"),
        )
        addWindow(
            tiers,
            label = "本月",
            fractionValue = usage.present("per1MonthPercentage") ?: usage.present("perMonthPercentage"),
            resetValue = usage.present("per1MonthResetTime") ?: usage.present("perMonthResetTime"),
            total = quota?.number("monthly", "month"),
        )
        if (tiers.isEmpty()) {
            return QuotaResult(false, error = "千问返回中没有 5 小时 / 本周 / 本月额度字段")
        }

        val message = buildList {
            add(if (specCode.isBlank()) "Token Plan Individual" else "Token Plan $specCode")
            subscription?.opt("remainingDays")?.let(::toDouble)
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { add("剩余 ${it.roundToLong()} 天") }
        }.joinToString(" · ")
        return QuotaResult(true, tiers = tiers, message = message)
    }

    private fun addWindow(
        target: MutableList<Tier>,
        label: String,
        fractionValue: Any?,
        resetValue: Any?,
        total: Double?,
    ) {
        val fraction = usageFraction(fractionValue) ?: return
        val valueText = total?.takeIf { it.isFinite() && it >= 0.0 }?.let { limit ->
            val used = (limit * fraction).roundToLong()
            "${formatCount(used)}\n/ ${formatCount(limit.roundToLong())}"
        }
        target.add(
            Tier(
                label = label,
                usedPct = (fraction * 100.0).coerceIn(0.0, 100.0),
                resetAt = resetText(resetValue),
                valueText = valueText,
            ),
        )
    }

    private fun usageFraction(value: Any?): Double? {
        val number = toDouble(value)?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return when {
            number <= 1.0 -> number
            number <= 100.0 -> number / 100.0
            else -> 1.0
        }
    }

    private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

    private fun JSONObject.number(vararg names: String): Double? = names.asSequence()
        .mapNotNull { name -> if (has(name)) toDouble(opt(name)) else null }
        .firstOrNull { it.isFinite() }

    private fun JSONObject.present(name: String): Any? = opt(name).takeUnless { it == JSONObject.NULL }

    private fun quotaFor(root: JSONObject?, specCode: String): JSONObject? {
        if (root == null) return null
        if (specCode.isNotBlank()) {
            listOf(specCode, specCode.lowercase(Locale.US), specCode.uppercase(Locale.US))
                .distinct()
                .forEach { key -> asObject(root.opt(key))?.let { return it } }
        }
        root.keys().forEach { key ->
            val row = asObject(root.opt(key)) ?: return@forEach
            if (listOf("five_hour", "fiveHour", "weekly", "week", "monthly", "month")
                    .any(row::has)
            ) return row
        }
        return null
    }

    /** 展开控制台网关的多层 data / DataV2；部分层级会是 JSON 字符串。 */
    private fun unwrapZelda(payload: JSONObject): JSONObject? {
        val outer = asObject(payload.opt("data")) ?: payload
        val dataV2 = asObject(outer.opt("DataV2")) ?: asObject(outer.opt("dataV2"))
        val inner = asObject(dataV2?.opt("data")) ?: asObject(outer.opt("data"))
            ?: if (dataV2 == null && !outer.has("data")) outer else null
        if (inner?.has("data") == true) asObject(inner.opt("data"))?.let { return it }
        return inner
    }

    private fun asObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
}

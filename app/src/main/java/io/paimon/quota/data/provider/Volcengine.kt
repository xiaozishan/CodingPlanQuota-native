package io.paimon.quota.data.provider

import io.paimon.quota.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 火山方舟 Agent Plan / Coding Plan 额度查询。
 *
 * 移植自 uni-app 版 services/quota/volcengine.uts（算法又源自 cc-switch 的 coding_plan.rs 火山变体）。
 * 和其余供应商不同：火山用量接口是**控制面 OpenAPI**，必须用账号级 AK/SK 做「火山签名 V4」，
 * 推理用的 Bearer Key 会被网关以 400 InvalidAuthorization 拒绝。
 *
 * 端点（固定，不走用户 baseUrl；baseUrl 只用来猜 Region）：
 *   POST https://open.volcengineapi.com/?Action=<Action>&Region=<region>&Version=2024-01-01
 *   签名头：X-Date / X-Content-Sha256 / Content-Type / Authorization
 *   Body：空（空串的 SHA-256 是常量 EMPTY_SHA256），因此 Content-Type 仍写 application/json
 *
 * 探测顺序（共用同一份 AK/SK）：
 *   1) Action=GetAFPUsage        —— Agent Plan，返回绝对额度 Quota/Used（5h / 周 / 月 三窗口）
 *   2) Action=GetCodingPlanUsage —— Coding Plan，返回百分比窗口（session/weekly/monthly）
 *   鉴权类错误直接终止（两个 plan 共用凭据，换 action 也没用）。
 */
internal object VolcengineProvider {

    private const val OPENAPI_HOST = "open.volcengineapi.com"
    private const val API_VERSION = "2024-01-01"
    private const val DEFAULT_REGION = "cn-beijing"
    private const val SERVICE = "ark"
    private const val CONTENT_TYPE = "application/json; charset=utf-8"

    /** ⚠️ 火山要求固定顺序，**不按字母序**（标准 AWS4 是按字母序排） */
    private const val SIGNED_HEADERS = "host;x-date;x-content-sha256;content-type"

    /** 空 body 的 SHA-256，恒定值，无需运行时计算 */
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private const val HINT =
        "请核对 AccessKey ID / Secret 是否正确，且账号具有方舟用量查询（OpenAPI）权限"

    // ------------------------------------------------------------------ 入口

    suspend fun fetch(p: Provider): QuotaResult = withContext(Dispatchers.IO) {
        val ak = p.extra["accessKeyId"].orEmpty().trim()
        val sk = p.extra["secretAccessKey"].orEmpty().trim()
        if (ak.isEmpty() || sk.isEmpty()) {
            return@withContext QuotaResult(false, error = "火山方舟需要 AccessKey ID 和 Secret")
        }

        // region 优先取 extra 里显式配置的值；没填就从推理 baseUrl 猜（ark.cn-beijing.volces.com → cn-beijing）
        val explicitRegion = p.extra["region"].orEmpty().trim()
        val region = if (explicitRegion.isNotEmpty()) explicitRegion else regionFromBaseUrl(p.baseUrl)

        try {
            val softErrors = mutableListOf<String>()
            val emptyResponses = mutableListOf<String>()

            // ---- 1) Agent Plan：GetAFPUsage
            when (val r1 = openApiCall(region, ak, sk, "GetAFPUsage")) {
                is Outcome.Auth -> return@withContext QuotaResult(false, error = r1.detail + " " + HINT)
                is Outcome.Transient -> return@withContext QuotaResult(false, error = r1.detail)
                is Outcome.Soft -> softErrors.add("GetAFPUsage: " + r1.detail)
                is Outcome.Body -> {
                    val result = r1.json.optJSONObject("Result") ?: r1.json
                    val tiers = parseAfpTiers(result)
                    if (tiers.isNotEmpty()) {
                        val planType = toStr(result.opt("PlanType"))?.trim().orEmpty()
                        val plan = if (planType.isNotEmpty()) "Agent Plan $planType" else "Agent Plan"
                        return@withContext QuotaResult(true, tiers = tiers, message = plan)
                    }
                    emptyResponses.add(summarize("GetAFPUsage", r1.json))
                }
            }

            // ---- 2) Coding Plan：GetCodingPlanUsage
            when (val r2 = openApiCall(region, ak, sk, "GetCodingPlanUsage")) {
                is Outcome.Auth -> return@withContext QuotaResult(false, error = r2.detail + " " + HINT)
                is Outcome.Transient -> return@withContext QuotaResult(false, error = r2.detail)
                is Outcome.Soft -> softErrors.add("GetCodingPlanUsage: " + r2.detail)
                is Outcome.Body -> {
                    val result = r2.json.optJSONObject("Result") ?: r2.json
                    val tiers = parseCodingPlanTiers(result)
                    if (tiers.isNotEmpty()) {
                        return@withContext QuotaResult(true, tiers = tiers, message = "Coding Plan")
                    }
                    emptyResponses.add(summarize("GetCodingPlanUsage", r2.json))
                }
            }

            when {
                softErrors.isNotEmpty() ->
                    QuotaResult(false, error = softErrors.joinToString("; "))
                emptyResponses.isNotEmpty() ->
                    QuotaResult(false, error = "未找到有效订阅（签名已通过）。原始响应：" +
                        emptyResponses.joinToString(" || "))
                else ->
                    QuotaResult(false, error = "该凭据未找到有效的 Agent Plan 或 Coding Plan 订阅")
            }
        } catch (e: Exception) {
            QuotaResult(false, error = e.message ?: "网络错误")
        }
    }

    // ------------------------------------------------------------ Region 解析

    /**
     * 从推理 base_url 提取 Region：取 host 里第一个以 cn- / ap- 开头的标签
     * （ark.cn-beijing.volces.com → cn-beijing），识别失败回落 cn-beijing。
     */
    private fun regionFromBaseUrl(baseUrl: String): String {
        var host = baseUrl.trim()
        if (host.isEmpty()) return DEFAULT_REGION
        val proto = host.split("://")
        if (proto.size == 2) host = proto[1]
        host = host.split("/")[0]
        for (part in host.split(".")) {
            if (part.startsWith("cn-") || part.startsWith("ap-")) return part
        }
        return DEFAULT_REGION
    }

    // ------------------------------------------------------------------ 签名

    /**
     * 火山签名 V4。与标准 AWS4 的**三点差异**（写代码时踩过，别“顺手改回标准”）：
     *  1. canonical headers 固定顺序 host;x-date;x-content-sha256;content-type（不按字母序）
     *  2. 算法串是 "HMAC-SHA256"（没有 AWS4 前缀），scope 结尾是 "request"（不是 aws4_request）
     *  3. 派生密钥第一层是 HMAC(SK, date)，SecretKey **不加 "AWS4" 前缀**
     *
     * 步骤：
     *  ① 取 UTC 当前时间 → xDate = yyyyMMdd'T'HHmmss'Z'，shortDate = yyyyMMdd（必须 UTC，不能用本地时区）
     *  ② canonicalRequest = "POST\n/\n<query>\n<canonicalHeaders>\n<signedHeaders>\n<sha256(body)>"
     *     canonicalHeaders 每行 "name:value\n"，末尾带换行；body 为空 → EMPTY_SHA256
     *  ③ credentialScope = "<shortDate>/<region>/ark/request"
     *  ④ stringToSign = "HMAC-SHA256\n<xDate>\n<scope>\n<hex(sha256(canonicalRequest))>"
     *  ⑤ kDate=HMAC(SK, shortDate) → kRegion=HMAC(kDate, region) → kService=HMAC(kRegion, "ark")
     *     → kSigning=HMAC(kService, "request") → signature=hex(HMAC(kSigning, stringToSign))（小写）
     *  ⑥ Authorization: HMAC-SHA256 Credential=<AK>/<scope>, SignedHeaders=<...>, Signature=<sig>
     */
    private fun sign(ak: String, sk: String, region: String, query: String): VolcAuth {
        val now = System.currentTimeMillis()
        val xDate = utcFormat(now, "yyyyMMdd'T'HHmmss'Z'")
        val shortDate = utcFormat(now, "yyyyMMdd")

        val canonicalHeaders = "host:$OPENAPI_HOST\n" +
            "x-date:$xDate\n" +
            "x-content-sha256:$EMPTY_SHA256\n" +
            "content-type:$CONTENT_TYPE\n"
        val canonicalRequest = "POST\n/\n" + query + "\n" + canonicalHeaders + "\n" +
            SIGNED_HEADERS + "\n" + EMPTY_SHA256

        val credentialScope = "$shortDate/$region/$SERVICE/request"
        val stringToSign = "HMAC-SHA256\n" + xDate + "\n" + credentialScope + "\n" +
            sha256Hex(canonicalRequest)

        val kDate = hmacSha256(sk.toByteArray(Charsets.UTF_8), shortDate.toByteArray(Charsets.UTF_8))
        val kRegion = hmacSha256(kDate, region.toByteArray(Charsets.UTF_8))
        val kService = hmacSha256(kRegion, SERVICE.toByteArray(Charsets.UTF_8))
        val kSigning = hmacSha256(kService, "request".toByteArray(Charsets.UTF_8))
        val signature = hex(hmacSha256(kSigning, stringToSign.toByteArray(Charsets.UTF_8)))

        val authorization = "HMAC-SHA256 Credential=$ak/$credentialScope, " +
            "SignedHeaders=$SIGNED_HEADERS, Signature=$signature"

        return VolcAuth(xDate = xDate, sha = EMPTY_SHA256, authorization = authorization)
    }

    /** UTC 时间格式化：等价于 JS 的 toISOString 切片，避开本地时区差异 */
    private fun utcFormat(millis: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return hex(md.digest(s.toByteArray(Charsets.UTF_8)))
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /** RFC3986：unreserved（A-Z a-z 0-9 - _ . ~）原样，其余按 UTF-8 字节 %XX 大写 */
    private fun uriEncode(input: String): String {
        val out = StringBuilder()
        for (b in input.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val unreserved = (v in 65..90) || (v in 97..122) || (v in 48..57) ||
                v == 45 || v == 95 || v == 46 || v == 126
            if (unreserved) {
                out.append(v.toChar())
            } else {
                out.append('%')
                out.append(Character.toUpperCase(Character.forDigit(v shr 4, 16)))
                out.append(Character.toUpperCase(Character.forDigit(v and 0x0F, 16)))
            }
        }
        return out.toString()
    }

    /** 查询串按 key 字母序：Action, Region, Version */
    private fun canonicalQuery(action: String, region: String): String =
        "Action=" + uriEncode(action) +
            "&Region=" + uriEncode(region) +
            "&Version=" + uriEncode(API_VERSION)

    private data class VolcAuth(val xDate: String, val sha: String, val authorization: String)

    // ------------------------------------------------------------------ 调用

    private sealed class Outcome {
        data class Body(val json: JSONObject) : Outcome()
        data class Auth(val detail: String) : Outcome()
        data class Soft(val detail: String) : Outcome()
        data class Transient(val detail: String) : Outcome()
    }

    private fun openApiCall(region: String, ak: String, sk: String, action: String): Outcome {
        return try {
            val query = canonicalQuery(action, region)
            val url = "https://$OPENAPI_HOST/?$query"
            val auth = sign(ak, sk, region, query)
            val headers = mapOf(
                "X-Date" to auth.xDate,
                "X-Content-Sha256" to auth.sha,
                "Content-Type" to CONTENT_TYPE,
                "Authorization" to auth.authorization,
            )
            // 空 body：签名的 x-content-sha256 就是空串哈希，二者必须一致
            val r: HttpResp = Http.post(url, "", headers)
            classify(r)
        } catch (e: Exception) {
            Outcome.Transient(e.message ?: "网络错误")
        }
    }

    private fun classify(r: HttpResp): Outcome {
        if (r.code == 401 || r.code == 403) {
            return Outcome.Auth("鉴权失败（HTTP ${r.code}）")
        }
        if (!r.ok) {
            val e = responseError(r.json)
            if (e != null) {
                if (isAuthErrorCode(e.first)) {
                    return Outcome.Auth("鉴权失败（HTTP ${r.code} ${e.first}）：${e.second}")
                }
                return Outcome.Soft("API error (HTTP ${r.code}, ${e.first}): ${e.second}")
            }
            return Outcome.Soft("API error (HTTP ${r.code})")
        }
        val body = r.json ?: return Outcome.Soft("响应不是 JSON")
        val e = responseError(body)
        if (e != null) {
            if (isAuthErrorCode(e.first)) {
                return Outcome.Auth("鉴权失败（${e.first}）：${e.second}")
            }
            return Outcome.Soft("API error (${e.first}): ${e.second}")
        }
        return Outcome.Body(body)
    }

    private fun isAuthErrorCode(code: String): Boolean {
        val c = code.lowercase(Locale.US)
        return c.contains("auth") || c.contains("signature") || c.contains("accessdenied") ||
            c.contains("denied") || c.contains("unauthorized") || c.contains("forbidden") ||
            c.contains("credential") || c.contains("token")
    }

    /** OpenAPI 信封错误：ResponseMetadata.Error 优先，其次顶层 Error；都没有返回 null */
    private fun responseError(body: JSONObject?): Pair<String, String>? {
        if (body == null) return null
        var err = body.optJSONObject("ResponseMetadata")?.optJSONObject("Error")
        if (err == null) err = body.optJSONObject("Error")
        if (err == null) return null
        val code = toStr(err.opt("Code")).orEmpty()
        val msg = toStr(err.opt("Message")).orEmpty()
        if (code.isEmpty() && msg.isEmpty()) return null
        return code to msg
    }

    // ------------------------------------------------------------------ 解析

    /** GetAFPUsage：5h / 周 / 月三窗口，绝对量 Quota/Used → 已用百分比；Quota<=0 视为无该窗口 */
    private fun parseAfpTiers(result: JSONObject): List<Tier> {
        val tiers = mutableListOf<Tier>()
        val keys = listOf(
            "AFPFiveHour" to "5 小时",
            "AFPWeekly" to "本周",
            "AFPMonthly" to "本月",
        )
        for ((key, label) in keys) {
            val win = result.optJSONObject(key) ?: continue
            val quota = toDouble(win.opt("Quota")) ?: continue
            if (quota <= 0.0) continue
            val used = toDouble(win.opt("Used")) ?: 0.0
            // 已用百分比（不反相）
            tiers.add(Tier(label, used / quota * 100.0, resetAt(win.opt("ResetTime"))))
        }
        return tiers
    }

    /** GetCodingPlanUsage 的窗口标签归一（实测字段是 Level，其余为防御式回落） */
    private fun codingWindow(label: String): String? {
        return when (label.lowercase(Locale.US)) {
            "session", "5h", "fivehour", "five_hour", "rolling_5h" -> "5 小时"
            "weekly", "week", "7d" -> "本周"
            "monthly", "month" -> "本月"
            else -> null
        }
    }

    /** GetCodingPlanUsage：QuotaUsage / Usages / Details 数组，宽松字段匹配，只给百分比 */
    private fun parseCodingPlanTiers(result: JSONObject): List<Tier> {
        val tiers = mutableListOf<Tier>()
        val arr: JSONArray = result.optJSONArray("QuotaUsage")
            ?: result.optJSONArray("Usages")
            ?: result.optJSONArray("Details")
            ?: return tiers

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val label = toStr(item.opt("Level"))
                ?: toStr(item.opt("Type"))
                ?: toStr(item.opt("Period"))
                ?: toStr(item.opt("Label"))
                ?: toStr(item.opt("Window"))
                ?: continue
            val tierLabel = codingWindow(label) ?: continue

            val utilization = toDouble(item.opt("Percent"))
                ?: toDouble(item.opt("UsedPercent"))
                ?: toDouble(item.opt("UsagePercent"))
                ?: 0.0
            val reset = resetAt(item.opt("ResetTime"))
                ?: resetAt(item.opt("ResetTimestamp"))

            tiers.add(Tier(tierLabel, utilization, reset))
        }
        return tiers
    }

    /**
     * 重置时间：数字 <= 0（火山无活跃窗口常回 -1）视为没有重置时间；
     * 其余交给共享 resetText（秒/毫秒/ISO → "MM-dd HH:mm"）。
     */
    private fun resetAt(v: Any?): String? {
        val n = v as? Number
        if (n != null && n.toDouble() <= 0.0) return null
        return resetText(v)
    }

    /** 空响应时把原始 JSON 截断进报错，方便对照上游变形 */
    private fun summarize(action: String, body: JSONObject): String {
        val raw = body.toString()
        return if (raw.length > 700) "$action=${raw.substring(0, 700)}" else "$action=$raw"
    }
}

package io.paimon.quota.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class HttpResp(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
    val json: JSONObject? get() = runCatching { JSONObject(body) }.getOrNull()
}

/** 极简 HTTP 封装：够各家额度接口用，不引第三方依赖。 */
internal object Http {

    fun get(url: String, headers: Map<String, String>): HttpResp = send("GET", url, null, headers)

    fun post(url: String, body: String, headers: Map<String, String>): HttpResp =
        send("POST", url, body, headers)

    private fun send(
        method: String,
        urlStr: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpResp {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 25000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (headers.keys.none { it.equals("Accept", true) }) {
                setRequestProperty("Accept", "application/json")
            }
            if (body != null) {
                doOutput = true
                if (headers.keys.none { it.equals("Content-Type", true) }) {
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        }
        return try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            HttpResp(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}

internal fun failHttp(r: HttpResp): QuotaResult {
    val hint = when (r.code) {
        401 -> "鉴权失败（Key/token 无效或已过期）"
        403 -> "无权限（Key 有效但没有对应订阅）"
        404 -> "接口不存在（地址可能写错）"
        429 -> "被限流，稍后再试"
        in 500..599 -> "服务端错误 HTTP ${r.code}"
        else -> "HTTP ${r.code}"
    }
    return QuotaResult(false, error = hint)
}

internal fun bearer(key: String): Map<String, String> = mapOf("Authorization" to "Bearer $key")

internal fun toDouble(v: Any?): Double? = when (v) {
    null, JSONObject.NULL -> null
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

internal fun toStr(v: Any?): String? = when (v) {
    null, JSONObject.NULL -> null
    is String -> v
    is Number -> v.toString()
    else -> null
}

internal fun toInt(v: Any?, def: Int = 0): Int = when (v) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull() ?: def
    else -> def
}

/**
 * 重置时间统一格式化：接受 unix 秒 / 毫秒 / ISO 8601 字符串，输出 "MM-dd HH:mm"。
 * 无法解析时返回 null（调用方不要展示）。
 */
internal fun resetText(v: Any?): String? {
    val raw = toStr(v) ?: return null
    if (raw.isBlank()) return null

    // 纯数字：秒 or 毫秒
    raw.toLongOrNull()?.let { n ->
        val ms = if (n < 10_000_000_000L) n * 1000 else n
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

    // ISO 8601：yyyy-MM-ddTHH:mm:ss[.SSS][Z|+08:00]
    val iso = raw.trim()
        .replace("Z", "+00:00")
        .replace(Regex("\\.\\d+"), "")
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )
    for (p in patterns) {
        runCatching {
            val fmt = SimpleDateFormat(p, Locale.US).apply { isLenient = true }
            val d = fmt.parse(iso) ?: return@runCatching
            return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(d)
        }
    }
    return null
}

/** 已用百分比，限幅到 0..100 */
internal fun usedPct(used: Double, limit: Double): Double =
    if (limit <= 0.0) 0.0 else ((used / limit) * 100.0).coerceIn(0.0, 100.0)

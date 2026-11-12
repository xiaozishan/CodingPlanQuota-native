package io.paimon.quota.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val QUOTA_RESULTS_CACHE = "quota_results_v1"

/**
 * 本地存储：主题模式、供应商配置、上次查询结果缓存。
 *
 * 凭据落盘一律经 [Vault] 加密（AES-256-GCM，密钥在 Android Keystore）：
 * - 主凭据 [Provider.token]
 * - extra 里 [SENSITIVE_EXTRA_KEYS] 列出的会话、OAuth 与访问凭据字段
 * 内存中保持明文，供查询时使用。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("cpa", Context.MODE_PRIVATE)

    var themeMode: String
        get() = sp.getString("theme", "auto") ?: "auto"
        set(value) {
            sp.edit().putString("theme", value).apply()
        }

    var mergeAccounts: Boolean
        get() = if (sp.contains("merge_accounts")) {
            sp.getBoolean("merge_accounts", true)
        } else {
            // 0.5.16 的 DeepSeek 专用开关升级为通用的多账号开关。
            sp.getBoolean("merge_deepseek", true)
        }
        set(value) {
            sp.edit()
                .putBoolean("merge_accounts", value)
                .remove("merge_deepseek")
                .apply()
        }

    var autoRefreshEnabled: Boolean
        get() = sp.getBoolean("auto_refresh_enabled", true)
        set(value) {
            sp.edit().putBoolean("auto_refresh_enabled", value).apply()
        }

    var autoRefreshIntervalSeconds: Int
        get() = sp.getInt("auto_refresh_interval_seconds", 60).coerceIn(5, 86_400)
        set(value) {
            sp.edit()
                .putInt("auto_refresh_interval_seconds", value.coerceIn(5, 86_400))
                .apply()
        }

    fun loadProviders(): List<Provider> {
        val raw = sp.getString("providers", null) ?: return emptyList()

        var legacyPlaintext = false
        var removedQwenCookie = false
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { ProviderKind.valueOf(o.optString("kind")) }
                    .getOrDefault(ProviderKind.CUSTOM)

                val storedKey = o.optString("apiKey")
                if (storedKey.isNotBlank() && !Vault.isEncrypted(storedKey)) legacyPlaintext = true

                val extraObj = o.optJSONObject("extra")
                val extra = buildMap {
                    if (extraObj != null) {
                        extraObj.keys().forEach { k ->
                            // 0.5.27 的千问 Cookie 方案已废止；升级后立即移除旧会话。
                            if (kind == ProviderKind.QWEN_CLOUD_INDIVIDUAL && k == "cookie") {
                                removedQwenCookie = true
                                return@forEach
                            }
                            val v = extraObj.optString(k)
                            put(
                                k,
                                if (k in SENSITIVE_EXTRA_KEYS) {
                                    if (v.isNotBlank() && !Vault.isEncrypted(v)) {
                                        legacyPlaintext = true
                                    }
                                    Vault.decrypt(v)
                                } else v
                            )
                        }
                    }
                }

                Provider(
                    id = o.optString("id"),
                    kind = kind,
                    name = o.optString("name", kind.label),
                    baseUrl = o.optString("baseUrl"),
                    token = Vault.decrypt(storedKey),
                    extra = extra,
                )
            }
        }.getOrDefault(emptyList())

        // 旧明文凭据立刻加密回写；旧千问 Cookie 也在本次迁移中彻底移除。
        if (legacyPlaintext || removedQwenCookie) saveProviders(list)

        return list
    }

    fun saveProviders(list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { p ->
            val extraObj = JSONObject()
            p.extra.forEach { (k, v) ->
                extraObj.put(k, if (k in SENSITIVE_EXTRA_KEYS) Vault.encrypt(v) else v)
            }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("kind", p.kind.name)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("apiKey", Vault.encrypt(p.token))
                    .put("extra", extraObj)
            )
        }
        sp.edit().putString("providers", arr.toString()).apply()
    }

    fun putCache(id: String, payload: String) {
        sp.edit().putString("cache:$id", payload).apply()
    }

    fun getCache(id: String): String? = sp.getString("cache:$id", null)

    /** 同步读取上次成功结果；只访问 SharedPreferences，不触碰网络。 */
    fun loadQuotaResults(): Map<String, QuotaResult> =
        QuotaResultCache.decode(getCache(QUOTA_RESULTS_CACHE))

    /** 仅保存成功结果；apply 异步落盘，不阻塞逐卡刷新。 */
    fun saveQuotaResults(results: Map<String, QuotaResult>) {
        putCache(QUOTA_RESULTS_CACHE, QuotaResultCache.encode(results))
    }
}

/** 额度结果的轻量 JSON 编解码；原始响应可能很大或含敏感信息，不写入启动缓存。 */
internal object QuotaResultCache {
    fun decode(raw: String?): Map<String, QuotaResult> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap<String, QuotaResult> {
                root.keys().forEach { id ->
                    if (id.isBlank()) return@forEach
                    val item = root.optJSONObject(id) ?: return@forEach
                    if (!item.optBoolean("ok", false)) return@forEach
                    val tiers = item.optJSONArray("tiers")?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            val tier = array.optJSONObject(index) ?: return@mapNotNull null
                            val label = tier.optString("label")
                            if (label.isBlank()) return@mapNotNull null
                            Tier(
                                label = label,
                                usedPct = tier.optDouble("usedPct", 0.0)
                                    .takeIf { it.isFinite() } ?: 0.0,
                                resetAt = tier.optionalString("resetAt"),
                                valueText = tier.optionalString("valueText"),
                            )
                        }
                    }.orEmpty()
                    put(
                        id,
                        QuotaResult(
                            ok = true,
                            tiers = tiers,
                            balanceText = item.optionalString("balanceText"),
                            balanceAmount = item.optionalDouble("balanceAmount"),
                            message = item.optionalString("message"),
                            at = item.optLong("at", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun encode(results: Map<String, QuotaResult>): String {
        val root = JSONObject()
        results.forEach { (id, result) ->
            if (id.isBlank() || !result.ok) return@forEach
            val tiers = JSONArray()
            result.tiers.forEach { tier ->
                tiers.put(
                    JSONObject()
                        .put("label", tier.label)
                        .put("usedPct", tier.usedPct.takeIf { it.isFinite() } ?: 0.0)
                        .put("resetAt", tier.resetAt)
                        .put("valueText", tier.valueText)
                )
            }
            val item = JSONObject()
                .put("ok", true)
                .put("tiers", tiers)
                .put("balanceText", result.balanceText)
                .put("message", result.message)
                .put("at", result.at)
            result.balanceAmount?.takeIf { it.isFinite() }?.let {
                item.put("balanceAmount", it)
            }
            root.put(id, item)
        }
        return root.toString()
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) optString(name) else null

    private fun JSONObject.optionalDouble(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf { it.isFinite() } else null
}

package io.paimon.quota.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    fun loadProviders(): List<Provider> {
        val raw = sp.getString("providers", null) ?: return emptyList()

        var legacyPlaintext = false
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

        // 旧版本的明文凭据：解出后立刻加密回写，本地不再留明文
        if (legacyPlaintext) saveProviders(list)

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
}

package io.paimon.quota.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 本地存储：主题模式、供应商配置、上次查询结果缓存 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("cpa", Context.MODE_PRIVATE)

    var themeMode: String
        get() = sp.getString("theme", "auto") ?: "auto"
        set(value) {
            sp.edit().putString("theme", value).apply()
        }

    fun loadProviders(): List<Provider> {
        val raw = sp.getString("providers", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { ProviderKind.valueOf(o.optString("kind")) }
                    .getOrDefault(ProviderKind.CUSTOM)
                Provider(
                    id = o.optString("id"),
                    kind = kind,
                    name = o.optString("name", kind.label),
                    baseUrl = o.optString("baseUrl"),
                    apiKey = o.optString("apiKey"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveProviders(list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("kind", p.kind.name)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("apiKey", p.apiKey)
            )
        }
        sp.edit().putString("providers", arr.toString()).apply()
    }

    fun putCache(id: String, payload: String) {
        sp.edit().putString("cache:$id", payload).apply()
    }

    fun getCache(id: String): String? = sp.getString("cache:$id", null)
}

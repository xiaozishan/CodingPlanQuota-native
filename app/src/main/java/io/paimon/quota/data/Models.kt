package io.paimon.quota.data

/** 供应商类型（内置端点 / 解析器） */
enum class ProviderKind(val label: String, val needsBaseUrl: Boolean) {
    DEEPSEEK("DeepSeek", false),
    KIMI("Kimi", false),
    ZHIPU("智谱 GLM", false),
    MINIMAX("MiniMax", false),
    CUSTOM("自定义 JSON", true),
}

/** 一个已保存的供应商配置 */
data class Provider(
    val id: String,
    val kind: ProviderKind,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val extra: Map<String, String> = emptyMap(),
)

/** 单个额度窗口（5 小时 / 周 / 月） */
data class Tier(
    val label: String,
    val usedPct: Double,
    val resetAt: String? = null,
)

/** 一次查询结果 */
data class QuotaResult(
    val ok: Boolean,
    val tiers: List<Tier> = emptyList(),
    val balanceText: String? = null,
    val error: String? = null,
    val at: Long = System.currentTimeMillis(),
)

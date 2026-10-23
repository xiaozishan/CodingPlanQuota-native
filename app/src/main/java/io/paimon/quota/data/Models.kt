package io.paimon.quota.data

/** 供应商类型（内置端点 / 解析器） */
enum class ProviderKind(val label: String, val needsBaseUrl: Boolean = false) {
    DEEPSEEK("DeepSeek"),
    KIMI("Kimi"),
    ZHIPU("智谱 GLM"),
    MINIMAX("MiniMax"),
    CODEX("Codex（ChatGPT）"),
    ANTHROPIC("Claude"),
    CUSTOM("自定义 JSON", needsBaseUrl = true),
}

/** 一个已保存的供应商配置 */
data class Provider(
    val id: String,
    val kind: ProviderKind,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    /** 额外字段，例如 Codex 的 accountId */
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
    val message: String? = null,
    val error: String? = null,
    val at: Long = System.currentTimeMillis(),
)

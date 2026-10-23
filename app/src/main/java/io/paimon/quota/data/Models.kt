package io.paimon.quota.data

/**
 * 供应商类型（对应原 uni-app 版的 kind）。
 *
 * [needsBaseUrl] = 表单要显示「接口地址」输入框（ZenMux 的额度端点由用户从控制台复制）。
 */
enum class ProviderKind(
    val label: String,
    val needsBaseUrl: Boolean = false,
) {
    DEEPSEEK("DeepSeek"),
    KIMI("Kimi"),
    ZHIPU("智谱 GLM"),
    ZHIPU_TEAM("智谱团队版"),
    MINIMAX("MiniMax"),
    ZENMUX("ZenMux", needsBaseUrl = true),
    OPENCODE_GO("OpenCode Go"),
    VOLCENGINE("火山方舟"),
    CODEX("Codex（ChatGPT）"),
    CODEX_FEELOL("Codex（feelol）"),
    CHATGPT_PLUS("ChatGPT Plus"),
    MIMO("MiMo Token Plan"),
    ANTHROPIC("Claude"),
    GEMINI("Gemini Code Assist"),
    CUSTOM("自定义 JSON", needsBaseUrl = true),
}

/** 一个已保存的供应商配置 */
data class Provider(
    val id: String,
    val kind: ProviderKind,
    val name: String,
    val baseUrl: String = "",
    /** 主凭据：API Key / access_token / 团队版 Key 等 */
    val token: String = "",
    /** 额外字段：accountId、organizationId、sessionJson、backendToken… */
    val extra: Map<String, String> = emptyMap(),
)

/** 单个额度窗口 */
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

/** extra 里需要加密落盘的字段（会话/凭据类） */
val SENSITIVE_EXTRA_KEYS = setOf(
    "sessionJson",
    "backendToken",
    "cookie",
    "secretAccessKey",
)

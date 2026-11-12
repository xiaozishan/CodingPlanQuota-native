package io.paimon.quota.data

import androidx.compose.runtime.Immutable

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
    GEMINI("Gemini（网页版）"),
    ANTIGRAVITY("Antigravity"),
    GITHUB_COPILOT("GitHub Copilot"),
    CUSTOM("自定义 JSON", needsBaseUrl = true),
    // 枚举名保留用于读取旧配置；0.5.28 起实际走阿里云百炼 Coding Plan API Key。
    QWEN_CLOUD_INDIVIDUAL("千问 Coding Plan", needsBaseUrl = true),
}

/** 一个已保存的供应商配置 */
@Immutable
data class Provider(
    val id: String,
    val kind: ProviderKind,
    val name: String,
    val baseUrl: String = "",
    /** 主凭据：API Key / access_token / 团队版 Key 等 */
    val token: String = "",
    /** 额外字段：accountId、organizationId、sessionJson、accessToken… */
    val extra: Map<String, String> = emptyMap(),
)

/** 单个额度窗口 */
@Immutable
data class Tier(
    val label: String,
    val usedPct: Double,
    val resetAt: String? = null,
    /** 次数额度或「未展示」等不能直接换算成百分比时的显示文本。 */
    val valueText: String? = null,
)

/** 一次查询结果 */
@Immutable
data class QuotaResult(
    val ok: Boolean,
    val tiers: List<Tier> = emptyList(),
    val balanceText: String? = null,
    /** DeepSeek 等数值型余额；保留原始数值，避免从展示文本反解析。 */
    val balanceAmount: Double? = null,
    val message: String? = null,
    val error: String? = null,
    val at: Long = System.currentTimeMillis(),
    val raw: String? = null,
)

/** extra 里需要加密落盘的字段（会话/凭据类） */
val SENSITIVE_EXTRA_KEYS = setOf(
    "sessionJson",
    "backendToken",
    "cookie",
    "secretAccessKey",
    "accessToken",
    "refresh_token",
)

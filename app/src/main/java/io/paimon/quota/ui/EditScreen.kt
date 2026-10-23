package io.paimon.quota.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.paimon.quota.data.Provider
import io.paimon.quota.data.ProviderKind
import java.util.UUID

/** 每种供应商除主凭据外还要填的字段 */
private data class ExtraField(
    val key: String,
    val label: String,
    val secret: Boolean = false,
    val multiline: Boolean = false,
)

private fun fieldsFor(kind: ProviderKind): List<ExtraField> = when (kind) {
    ProviderKind.ZHIPU_TEAM -> listOf(
        ExtraField("organizationId", "组织 ID"),
        ExtraField("projectId", "项目 ID"),
    )

    ProviderKind.VOLCENGINE -> listOf(
        ExtraField("accessKeyId", "AccessKey ID"),
        ExtraField("secretAccessKey", "Secret AccessKey", secret = true),
        ExtraField("region", "region（可空，默认 cn-beijing）"),
    )

    ProviderKind.CODEX -> listOf(ExtraField("accountId", "ChatGPT Account ID（可选）"))

    ProviderKind.CHATGPT_PLUS -> listOf(
        ExtraField("sessionJson", "Session JSON（chatgpt.com/api/auth/session）", secret = true, multiline = true),
    )

    ProviderKind.MIMO -> listOf(
        ExtraField("backendUrl", "后端地址（模式①）"),
        ExtraField("backendToken", "后端 Token（模式①）", secret = true),
        ExtraField("cookie", "Cookie（模式②直连）", secret = true),
    )

    else -> emptyList()
}

/** 主凭据字段是否显示 */
private fun showsMainKey(kind: ProviderKind): Boolean = when (kind) {
    ProviderKind.CHATGPT_PLUS, ProviderKind.VOLCENGINE, ProviderKind.MIMO -> false
    else -> true
}

private fun tokenLabel(kind: ProviderKind): String = when (kind) {
    ProviderKind.CODEX -> "ChatGPT access_token"
    ProviderKind.ANTHROPIC -> "Claude OAuth access_token"
    ProviderKind.GEMINI -> "Google OAuth access_token"
    ProviderKind.CODEX_FEELOL -> "feelol token"
    else -> "API Key"
}

private fun hintText(kind: ProviderKind): String = when (kind) {
    ProviderKind.CODEX ->
        "access_token 从 chatgpt.com/api/auth/session 取；内部接口，过期需重新获取。"
    ProviderKind.ANTHROPIC ->
        "OAuth access_token，走 platform.claude.com 的 usage 接口；实验性支持。"
    ProviderKind.GEMINI ->
        "Google Cloud OAuth access_token，走 cloudcode-pa 的 retrieveUserQuota；实验性支持。"
    ProviderKind.ZHIPU -> "国内站走 open.bigmodel.cn，国际站走 api.z.ai（按你填的地址判断）。"
    ProviderKind.ZHIPU_TEAM -> "团队版端点带 ?type=2，需要组织 ID 与项目 ID 两个头部。"
    ProviderKind.MINIMAX -> "留空走国内 api.minimaxi.com；填含 minimax.io 的地址则走国际站。"
    ProviderKind.ZENMUX -> "填 ZenMux 控制台复制的完整额度端点（返回 usage_percentage 的那个）。"
    ProviderKind.OPENCODE_GO -> "固定走 opencode.ai/zen/go/v1/usage；403 表示没有 Go 订阅。"
    ProviderKind.VOLCENGINE -> "用 AK/SK 签名请求；region 留空按 cn-beijing 处理。"
    ProviderKind.CODEX_FEELOL -> "第三方中转 feea.lol，实验性支持。"
    ProviderKind.CHATGPT_PLUS -> "不发网络请求，只按 session JSON 里的套餐与续费日换算倒计时。"
    ProviderKind.MIMO -> "两种模式二选一：后端地址 + Token，或直连 Cookie。"
    ProviderKind.CUSTOM -> "地址返回 JSON 时优先读 used_pct，否则显示原始返回片段。"
    else -> "凭据只保存在本机，用 Android Keystore 加密（AES-256-GCM）。"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    existing: Provider?,
    onBack: () -> Unit,
    onSave: (Provider) -> Unit,
    onDelete: ((Provider) -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: ProviderKind.DEEPSEEK) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var keyText by remember { mutableStateOf(existing?.token ?: "") }
    val extras = remember {
        mutableStateMapOf<String, String>().apply {
            existing?.extra?.forEach { (k, v) -> put(k, v) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "添加供应商" else "编辑供应商") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = {
                        onSave(
                            Provider(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                kind = kind,
                                name = name.ifBlank { kind.label },
                                baseUrl = baseUrl,
                                token = keyText,
                                extra = extras.filterValues { it.isNotBlank() },
                            )
                        )
                    }) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("类型", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRows(
                options = ProviderKind.entries,
                selected = kind,
                label = { it.label },
                onSelect = { kind = it },
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称（可留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (showsMainKey(kind)) {
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(tokenLabel(kind)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (kind.needsBaseUrl) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(if (kind == ProviderKind.ZENMUX) "额度端点 URL" else "接口地址（返回 JSON）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            fieldsFor(kind).forEach { f ->
                val v = extras[f.key].orEmpty()
                OutlinedTextField(
                    value = v,
                    onValueChange = { extras[f.key] = it },
                    label = { Text(f.label) },
                    singleLine = !f.multiline,
                    minLines = if (f.multiline) 4 else 1,
                    visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                hintText(kind),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            if (existing != null && onDelete != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onDelete(existing) }, modifier = Modifier.fillMaxWidth()) {
                    Text("删除这个供应商")
                }
            }
        }
    }
}

@Composable
private fun <T> ChipRows(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    options.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { o ->
                FilterChip(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    label = { Text(label(o), maxLines = 1, fontSize = 12.sp) },
                )
            }
        }
    }
}

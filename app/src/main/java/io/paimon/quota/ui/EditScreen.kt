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
    var keyText by remember { mutableStateOf(existing?.apiKey ?: "") }
    var accountId by remember { mutableStateOf(existing?.extra?.get("accountId") ?: "") }

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
                        val extra = if (kind == ProviderKind.CODEX && accountId.isNotBlank())
                            mapOf("accountId" to accountId.trim())
                        else emptyMap()
                        onSave(
                            Provider(
                                id = existing?.id ?: UUID.randomUUID().toString(),
                                kind = kind,
                                name = name.ifBlank { kind.label },
                                baseUrl = baseUrl,
                                apiKey = keyText.trim(),
                                extra = extra,
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
            FlowRowSimple(
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

            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                label = { Text(tokenLabel(kind)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (kind == ProviderKind.CODEX) {
                OutlinedTextField(
                    value = accountId,
                    onValueChange = { accountId = it },
                    label = { Text("ChatGPT Account ID（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (kind.needsBaseUrl) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("接口地址（返回 JSON）") },
                    singleLine = true,
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

private fun tokenLabel(kind: ProviderKind): String = when (kind) {
    ProviderKind.DEEPSEEK, ProviderKind.KIMI, ProviderKind.ZHIPU, ProviderKind.MINIMAX -> "API Key"
    ProviderKind.CODEX -> "ChatGPT access_token"
    ProviderKind.ANTHROPIC -> "Claude OAuth access_token"
    ProviderKind.CUSTOM -> "API Key（可留空）"
}

private fun hintText(kind: ProviderKind): String = when (kind) {
    ProviderKind.CODEX ->
        "access_token 从 chatgpt.com/api/auth/session 取；属于内部接口，token 过期后需重新获取。"
    ProviderKind.ANTHROPIC ->
        "OAuth access_token，调用 platform.claude.com 的 usage 接口；属于实验性支持。"
    ProviderKind.ZHIPU -> "国内站走 open.bigmodel.cn，国际站走 api.z.ai（按你填的地址判断）。"
    ProviderKind.MINIMAX -> "留空走国内 api.minimaxi.com；填含 minimax.io 的地址则走国际站。"
    ProviderKind.CUSTOM -> "地址需返回 JSON，优先读 used_pct 字段，否则显示原始返回片段。"
    else -> "Key 只保存在本机，用 Android Keystore 加密（AES-256-GCM）。"
}

@Composable
private fun <T> FlowRowSimple(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.take(4).forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o), maxLines = 1) },
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.drop(4).forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o), maxLines = 1) },
            )
        }
    }
}

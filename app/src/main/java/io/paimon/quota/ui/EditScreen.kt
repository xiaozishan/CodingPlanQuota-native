package io.paimon.quota.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }

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
                        val p = Provider(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            kind = kind,
                            name = name.ifBlank { kind.label },
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                        )
                        onSave(p)
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
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (kind.needsBaseUrl) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("接口地址（返回 JSON）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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
private fun <T> FlowRowSimple(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.take(3).forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o), maxLines = 1) },
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.drop(3).forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o), maxLines = 1) },
            )
        }
    }
}

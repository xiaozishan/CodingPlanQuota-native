package io.paimon.quota.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.paimon.quota.data.Provider
import io.paimon.quota.data.ProviderKind
import io.paimon.quota.data.QuotaResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    providers: List<Provider>,
    results: Map<String, QuotaResult>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Provider) -> Unit,
    onSettings: () -> Unit,
    showSettingsAction: Boolean = true,
    mergeAccounts: Boolean,
) {
    var rawJson by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("额度盘", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    TextButton(onClick = onRefresh, enabled = !loading) {
                        Text(if (loading) "刷新中" else "刷新")
                    }
                    if (showSettingsAction) {
                        TextButton(onClick = onSettings) { Text("设置") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text("＋", fontSize = 24.sp) }
        },
    ) { padding ->
        if (providers.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有供应商，点右下角 ＋ 添加",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val columns = if (maxWidth >= 600.dp) 2 else 1
            val pagePadding = if (maxWidth >= 600.dp) 24.dp else 16.dp
            val cardSpacing = if (maxWidth >= 600.dp) 16.dp else 12.dp
            Box(
                modifier = Modifier
                    .widthIn(max = 1_000.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = pagePadding,
                        top = 8.dp,
                        end = pagePadding,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(cardSpacing),
                    horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                ) {
                    val cardGroups = if (mergeAccounts) {
                        providers.groupBy { it.kind }.values.toList()
                    } else {
                        providers.map { listOf(it) }
                    }
                    items(cardGroups, key = { group -> group.joinToString(":") { it.id } }) { group ->
                        val p = group.first()
                        if (group.size > 1) {
                            MergedProviderCard(
                                accounts = group,
                                results = results,
                                onEdit = onEdit,
                                onShowRaw = { rawJson = it },
                            )
                        } else {
                            ProviderCard(
                                p = p,
                                r = results[p.id],
                                onClick = { onEdit(p) },
                                onShowRaw = { rawJson = it },
                            )
                        }
                    }
                }
            }
        }
    }

    rawJson?.let { raw ->
        RawJsonDialog(raw = raw, onDismiss = { rawJson = null })
    }
}

@Composable
private fun MergedProviderCard(
    accounts: List<Provider>,
    results: Map<String, QuotaResult>,
    onEdit: (Provider) -> Unit,
    onShowRaw: (String) -> Unit,
) {
    val kind = accounts.first().kind
    val showBalances = kind == ProviderKind.DEEPSEEK || accounts.all {
        results[it.id]?.balanceAmount != null
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    kind.label,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${accounts.size} 个账号" + if (showBalances) " · 余额" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (showBalances) {
                BalanceGaugeGroup(accounts = accounts, results = results, onEdit = onEdit)
            } else {
                AccountTierGaugeGrid(accounts = accounts, results = results, onEdit = onEdit)
                accounts.forEachIndexed { index, provider ->
                    val r = results[provider.id]
                    if (r == null || !r.ok || r.tiers.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            accountLabel(provider, index),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        ProviderResultContent(
                            p = provider,
                            r = r,
                            onShowRaw = onShowRaw,
                        )
                    } else {
                        if (!r.message.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${accountLabel(provider, index)} · ${r.message}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val raw = r.raw?.takeIf { it.isNotEmpty() }
                        if (provider.kind == ProviderKind.ANTIGRAVITY && raw != null) {
                            TextButton(
                                onClick = { onShowRaw(raw) },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                            ) {
                                Text("${accountLabel(provider, index)} · 查看原始 JSON", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    p: Provider,
    r: QuotaResult?,
    onClick: () -> Unit,
    onShowRaw: (String) -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    p.name.ifBlank { p.kind.label },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    p.kind.label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            ProviderResultContent(p = p, r = r, onShowRaw = onShowRaw)
        }
    }
}

@Composable
private fun ProviderResultContent(
    p: Provider,
    r: QuotaResult?,
    onShowRaw: (String) -> Unit,
) {
    when {
        r == null -> Text(
            "尚未查询",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        !r.ok -> {
            val failure = if (p.kind == ProviderKind.GEMINI) {
                when {
                    r.error?.contains("未登录") == true -> "Gemini 网页端未登录"
                    r.error?.contains("页面加载失败") == true -> "页面加载失败，请稍后重试"
                    else -> "未登录或页面未加载"
                }
            } else {
                r.error ?: "未知错误"
            }
            Text(
                "查询失败：$failure",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        r.balanceAmount != null -> BalanceGauge(
            balance = r.balanceAmount,
            balanceText = r.balanceText,
        )

        r.balanceText != null -> Text(
            r.balanceText,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        else -> TierGaugeGrid(r.tiers)
    }

    if (p.kind != ProviderKind.GEMINI && r != null && r.ok && !r.message.isNullOrBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(
            r.message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val raw = r?.raw?.takeIf { it.isNotEmpty() }
    if (p.kind == ProviderKind.ANTIGRAVITY && raw != null) {
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = { onShowRaw(raw) },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text("查看原始 JSON", fontSize = 12.sp)
        }
    }
}

@Composable
private fun RawJsonDialog(raw: String, onDismiss: () -> Unit) {
    val knownEndpoints = listOf("retrieveUserQuota", "fetchAvailableModels", "loadCodeAssist")
    val lines = raw.lineSequence().toList()
    val endpoints = knownEndpoints.filter { name -> lines.any { it == "$name:" } }
    val endpointText = endpoints.joinToString(" / ").ifBlank { "Antigravity" }

    BackHandler(onBack = onDismiss)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false),
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            Text(
                "原始 JSON · $endpointText",
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            SelectionContainer(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    raw,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        },
    )
}



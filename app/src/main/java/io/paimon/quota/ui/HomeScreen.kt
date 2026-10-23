package io.paimon.quota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.paimon.quota.data.Provider
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
) {
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
                    TextButton(onClick = onSettings) { Text("设置") }
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

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(providers, key = { it.id }) { p ->
                ProviderCard(p, results[p.id], onClick = { onEdit(p) })
            }
        }
    }
}

@Composable
private fun ProviderCard(p: Provider, r: QuotaResult?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
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

            Spacer(Modifier.height(12.dp))

            when {
                r == null -> Text("尚未查询", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                !r.ok -> Text("查询失败：" + (r.error ?: "未知错误"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.balanceText != null -> Text(
                    r.balanceText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    r.tiers.forEach { t ->
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(t.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${"%.1f".format(t.usedPct)}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier.fillMaxWidth().height(6.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                            ) {
                                Box(
                                    Modifier.fillMaxWidth((t.usedPct / 100.0).toFloat().coerceIn(0f, 1f))
                                        .height(6.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                                )
                            }
                            if (!t.resetAt.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("重置：${t.resetAt}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

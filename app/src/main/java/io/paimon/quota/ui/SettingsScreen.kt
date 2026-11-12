package io.paimon.quota.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    mergeAccounts: Boolean,
    onMergeAccountsChange: (Boolean) -> Unit,
    autoRefreshEnabled: Boolean,
    onAutoRefreshEnabledChange: (Boolean) -> Unit,
    autoRefreshIntervalSeconds: Int,
    onAutoRefreshIntervalChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val pagePadding = if (maxWidth >= 600.dp) 24.dp else 16.dp
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = pagePadding, end = pagePadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "设置",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                TextButton(onClick = onBack, shape = UiShapes.Control) { Text("返回") }
            }
            Text("外观", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemeMode.entries.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    RadioButton(selected = mode == m, onClick = { onModeChange(m) })
                    Text(
                        m.label,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                    "界面以黑白单色为主，用量警示与错误状态保留状态色。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Text("自动刷新", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("前台自动刷新", color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "回到前台立即刷新，并按设定间隔刷新；退到后台自动停止",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoRefreshEnabled, onCheckedChange = onAutoRefreshEnabledChange)
            }
            if (autoRefreshEnabled) {
                Text(
                    "刷新间隔",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                autoRefreshIntervals.forEach { (seconds, label) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = autoRefreshIntervalSeconds == seconds,
                            onClick = { onAutoRefreshIntervalChange(seconds) },
                        )
                        Text(
                            label,
                            modifier = Modifier.padding(start = 8.dp),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Text("首页", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("多账号卡片合并", color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "同一供应商的账号在同一张卡片中显示；关闭后恢复独立卡片",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = mergeAccounts, onCheckedChange = onMergeAccountsChange)
            }
            // 与首页一致：只提供滚动余量，内容可以穿到 Dock 后方，不留白色底带。
            Spacer(Modifier.fillMaxWidth().height(84.dp))
            }
        }
    }
}

private val autoRefreshIntervals = listOf(
    30 to "30 秒",
    60 to "60 秒（默认）",
    120 to "2 分钟",
    300 to "5 分钟",
    600 to "10 分钟",
)

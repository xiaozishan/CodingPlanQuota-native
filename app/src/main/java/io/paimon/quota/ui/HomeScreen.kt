package io.paimon.quota.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.paimon.quota.data.Provider
import io.paimon.quota.data.ProviderKind
import io.paimon.quota.data.QuotaResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    providers: List<Provider>,
    results: Map<String, QuotaResult>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onEdit: (Provider) -> Unit,
    onDelete: (List<Provider>) -> Unit,
    mergeAccounts: Boolean,
) {
    var rawJson by remember { mutableStateOf<String?>(null) }
    val cardGroups = remember(providers, mergeAccounts) {
        if (mergeAccounts) {
            providers.groupBy { it.kind }.values.toList()
        } else {
            providers.map { listOf(it) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
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
                        // 首屏标题避开状态栏；该间距随列表滚动，内容可从状态栏下穿过。
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                        end = pagePadding,
                        bottom = 0.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(cardSpacing),
                    horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                ) {
                    item(
                        key = "home-header",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "header",
                    ) {
                        HomeHeader(loading = loading, onRefresh = onRefresh)
                    }
                    if (cardGroups.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 120.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "还没有供应商，点导航栏 ＋ 添加",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(cardGroups, key = { group -> group.joinToString(":") { it.id } }) { group ->
                        val p = group.first()
                        SwipeDeleteCard(
                            deleteLabel = if (group.size > 1) {
                                "删除${p.kind.label}的 ${group.size} 个账号"
                            } else {
                                "删除${p.name.ifBlank { p.kind.label }}"
                            },
                            onDelete = { onDelete(group) },
                            modifier = Modifier.animateItemPlacement(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ),
                        ) {
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
                    item(
                        key = "home-bottom-spacer",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "transparent-spacer",
                    ) {
                        // 只提供滚动余量，不绘制背景；60dp Dock + 24dp 底部外边距。
                        Box(Modifier.fillMaxWidth().height(84.dp))
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
private fun HomeHeader(
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "额度盘",
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        TextButton(onClick = onRefresh, enabled = !loading, shape = UiShapes.Control) {
            Text(if (loading) "刷新中" else "刷新")
        }
    }
}

@Composable
private fun SwipeDeleteCard(
    deleteLabel: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val revealWidthPx = with(LocalDensity.current) { 88.dp.toPx() }
    val swipeOffset = remember { Animatable(0f) }
    var deleting by remember { mutableStateOf(false) }
    val reveal = (-swipeOffset.value / revealWidthPx).coerceIn(0f, 1f)

    LaunchedEffect(deleting) {
        if (deleting) {
            delay(310)
            onDelete()
        }
    }

    AnimatedVisibility(
        visible = !deleting,
        modifier = modifier,
        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(180)),
    ) {
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            // 前景卡片移开后才露出红底；透明度同时跟位移联动。
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = reveal }
                    .background(MaterialTheme.colorScheme.error, UiShapes.Card),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Surface(
                    onClick = { if (!deleting) deleting = true },
                    enabled = !deleting,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(56.dp)
                        .graphicsLayer {
                            alpha = reveal
                            scaleX = 0.85f + 0.15f * reveal
                            scaleY = 0.85f + 0.15f * reveal
                        }
                        .semantics { contentDescription = deleteLabel },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    contentColor = Color.White,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("删除", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Box(
                Modifier
                    .graphicsLayer { translationX = swipeOffset.value }
                    .pointerInput(revealWidthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                scope.launch { swipeOffset.stop() }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    swipeOffset.snapTo(
                                        (swipeOffset.value + dragAmount)
                                            .coerceIn(-revealWidthPx, 0f),
                                    )
                                }
                            },
                            onDragEnd = {
                                scope.launch {
                                    val target = if (swipeOffset.value <= -revealWidthPx * 0.45f) {
                                        -revealWidthPx
                                    } else 0f
                                    swipeOffset.animateTo(
                                        target,
                                        animationSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    swipeOffset.animateTo(
                                        0f,
                                        animationSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                            },
                        )
                    },
            ) {
                content()
            }
        }
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
        shape = UiShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(UiDimens.BorderWidth, MaterialTheme.colorScheme.outline),
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
                                shape = UiShapes.Control,
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
        shape = UiShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(UiDimens.BorderWidth, MaterialTheme.colorScheme.outline),
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
            shape = UiShapes.Control,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text("查看原始 JSON", fontSize = 12.sp)
        }
    }
}

@Composable
private fun RawJsonDialog(raw: String, onDismiss: () -> Unit) {
    val endpointText = remember(raw) {
        val knownEndpoints = listOf("retrieveUserQuota", "fetchAvailableModels", "loadCodeAssist")
        val lines = raw.lineSequence().toList()
        knownEndpoints.filter { name -> lines.any { it == "$name:" } }
            .joinToString(" / ")
            .ifBlank { "Antigravity" }
    }

    BackHandler(onBack = onDismiss)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = UiShapes.Card,
        properties = DialogProperties(dismissOnBackPress = false),
        confirmButton = {
            TextButton(onClick = onDismiss, shape = UiShapes.Control) { Text("关闭") }
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

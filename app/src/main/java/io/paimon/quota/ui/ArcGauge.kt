package io.paimon.quota.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.paimon.quota.data.Provider
import io.paimon.quota.data.QuotaResult
import io.paimon.quota.data.Tier
import java.util.Locale
import kotlin.math.roundToInt

private const val GAUGE_START_ANGLE = 135f
private const val GAUGE_SWEEP_ANGLE = 270f

private enum class GaugeKind {
    USAGE,
    BALANCE,
}

private data class AccountTier(
    val provider: Provider,
    val accountIndex: Int,
    val tier: Tier,
)

@Composable
fun TierGaugeGrid(tiers: List<Tier>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiers.chunked(2).forEach { rowTiers ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowTiers.forEach { tier ->
                    TierGauge(tier = tier, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun AccountTierGaugeGrid(
    accounts: List<Provider>,
    results: Map<String, QuotaResult>,
    onEdit: (Provider) -> Unit,
) {
    // 同一指标下不同账号并排，例如第一行是号①/号②的 5 小时，第二行是本周。
    val entries = remember(accounts, results) {
        val maxTierCount = accounts.maxOfOrNull { results[it.id]?.tiers?.size ?: 0 } ?: 0
        buildList {
            for (tierIndex in 0 until maxTierCount) {
                accounts.forEachIndexed { accountIndex, provider ->
                    results[provider.id]?.tiers?.getOrNull(tierIndex)?.let { tier ->
                        add(AccountTier(provider, accountIndex, tier))
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.chunked(2).forEach { rowEntries ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowEntries.forEach { entry ->
                    val label = "${accountLabel(entry.provider, entry.accountIndex)} · ${entry.tier.label}"
                    TierGauge(
                        tier = entry.tier.copy(label = label),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onEdit(entry.provider) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TierGauge(tier: Tier, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ArcGauge(
            usedPct = tier.usedPct,
            kind = GaugeKind.USAGE,
            modifier = Modifier.size(112.dp),
        ) {
            Text(
                text = tier.valueText ?: "${tier.usedPct.coerceIn(0.0, 100.0).roundToInt()}%",
                modifier = Modifier.padding(horizontal = 22.dp),
                fontSize = if (tier.valueText == null) 18.sp else 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            tier.label.replace("窗口", "").trim(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!tier.resetAt.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "重置 ${tier.resetAt}",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun BalanceGauge(balance: Double, balanceText: String?) {
    val amount = balance.takeIf { it.isFinite() } ?: 0.0
    val percent = (amount / 100.0).coerceIn(0.0, 1.0) * 100.0
    val giftText = balanceText?.substringAfter(" · ", "")?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArcGauge(
            usedPct = percent,
            kind = GaugeKind.BALANCE,
            modifier = Modifier.size(120.dp),
        ) {
            Text(
                "¥${String.format(Locale.US, "%.2f", amount)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (giftText != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                giftText,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun BalanceGaugeGroup(
    accounts: List<Provider>,
    results: Map<String, QuotaResult>,
    onEdit: (Provider) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val balanceFontSize = if (maxWidth < 360.dp) 15.sp else 17.sp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            accounts.chunked(2).forEachIndexed { rowIndex, rowAccounts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowAccounts.forEachIndexed { columnIndex, provider ->
                        val index = rowIndex * 2 + columnIndex
                        val result = results[provider.id]
                        val amount = result?.takeIf { it.ok }?.balanceAmount?.takeIf { it.isFinite() }
                        val percent = amount?.let { (it / 100.0).coerceIn(0.0, 1.0) * 100.0 } ?: 0.0
                        Column(
                            modifier = Modifier.weight(1f).clickable { onEdit(provider) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ArcGauge(
                                usedPct = percent,
                                kind = GaugeKind.BALANCE,
                                modifier = Modifier.size(112.dp),
                            ) {
                                Text(
                                    amount?.let { "¥${String.format(Locale.US, "%.2f", it)}" }
                                        ?: if (result?.ok == false) "失败" else "--",
                                    fontSize = balanceFontSize,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                accountLabel(provider, index),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun accountLabel(provider: Provider, index: Int): String {
    val customName = provider.name.trim()
        .removePrefix(provider.kind.label)
        .trim(' ', '·', '-', '_')
    if (customName.isNotBlank()) return customName
    val markers = listOf("①", "②", "③", "④", "⑤", "⑥")
    return "号" + markers.getOrElse(index) { (index + 1).toString() }
}

@Composable
private fun ArcGauge(
    usedPct: Double,
    kind: GaugeKind,
    modifier: Modifier = Modifier,
    centerContent: @Composable BoxScope.() -> Unit,
) {
    val percent = usedPct.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = gaugeColorAt(kind, percent)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 10.dp.toPx()
            val inset = strokeWidth / 2f + 2.dp.toPx()
            val diameter = (minOf(size.width, size.height) - inset * 2).coerceAtLeast(0f)
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            drawArc(
                color = trackColor,
                startAngle = GAUGE_START_ANGLE,
                sweepAngle = GAUGE_SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            // 小角度只显示轨迹，避免短弧在起点形成方块状色斑；中心仍显示真实数值。
            if (percent >= 3.0 && diameter > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = GAUGE_START_ANGLE,
                    sweepAngle = GAUGE_SWEEP_ANGLE * (percent / 100.0).toFloat(),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
        centerContent()
    }
}

private fun gaugeColorAt(kind: GaugeKind, percent: Double): Color {
    val value = percent.coerceIn(0.0, 100.0)
    val severity = if (kind == GaugeKind.USAGE) value else 100.0 - value
    val hue = (120.0 * (1.0 - severity / 100.0)).toFloat()
    return Color(AndroidColor.HSVToColor(floatArrayOf(hue, 0.78f, 0.86f)))
}


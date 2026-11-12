package io.paimon.quota.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class QuotaDestination {
    HOME,
    SETTINGS,
}

private val DockBarColor = Color.White.copy(alpha = 0.72f)
private val DockTextColor = Color(0xFF111111)
private val DockMutedTextColor = Color(0xFF737373)

@Composable
fun QuotaBottomDock(
    selected: QuotaDestination,
    onSelect: (QuotaDestination) -> Unit,
    onAdd: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = SquircleShape(30.dp),
            color = DockBarColor,
            contentColor = DockTextColor,
            shadowElevation = 0.dp,
            border = BorderStroke(0.6.dp, Color(0x0F000000)),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockTab(
                    selected = selected == QuotaDestination.HOME,
                    onClick = { onSelect(QuotaDestination.HOME) },
                    icon = Icons.Filled.Home,
                    label = "额度",
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    DockAddButton(onAdd)
                }
                DockTab(
                    selected = selected == QuotaDestination.SETTINGS,
                    onClick = { onSelect(QuotaDestination.SETTINGS) },
                    icon = Icons.Filled.Settings,
                    label = "设置",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun QuotaSideDock(
    selected: QuotaDestination,
    onSelect: (QuotaDestination) -> Unit,
    onAdd: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxHeight()
            .width(96.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 10.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = SquircleShape(24.dp),
            color = DockBarColor,
            contentColor = DockTextColor,
            shadowElevation = 0.dp,
            border = BorderStroke(0.6.dp, Color(0x0F000000)),
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                DockTab(
                    selected = selected == QuotaDestination.HOME,
                    onClick = { onSelect(QuotaDestination.HOME) },
                    icon = Icons.Filled.Home,
                    label = "额度",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                DockAddButton(onAdd)
                Spacer(Modifier.height(10.dp))
                DockTab(
                    selected = selected == QuotaDestination.SETTINGS,
                    onClick = { onSelect(QuotaDestination.SETTINGS) },
                    icon = Icons.Filled.Settings,
                    label = "设置",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DockTab(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) DockTextColor else DockMutedTextColor,
        label = "dockContentColor",
    )
    val haloColor by animateColorAsState(
        targetValue = if (selected) DockTextColor.copy(alpha = 0.09f) else Color.Transparent,
        label = "dockHaloColor",
    )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = SquircleShape(24.dp),
        color = Color.Transparent,
        contentColor = contentColor,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(listOf(haloColor, Color.Transparent)),
                    SquircleShape(20.dp),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
            )
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DockAddButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = SquircleShape(10.dp),
        color = DockTextColor,
        contentColor = DockBarColor,
        shadowElevation = 4.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "添加",
                modifier = Modifier.size(24.dp),
            )
        }
    }
}


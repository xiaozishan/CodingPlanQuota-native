package io.paimon.quota.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class QuotaDestination {
    HOME,
    SETTINGS,
}

/** 轻量玻璃材质：半透明底色、顶部高光和细描边，深浅色主题都保留层次。 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    Surface(
        modifier = modifier,
        shape = shape,
        color = scheme.surface.copy(alpha = if (dark) 0.82f else 0.76f),
        contentColor = scheme.onSurface,
        shadowElevation = if (dark) 8.dp else 18.dp,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            Color.White.copy(alpha = if (dark) 0.14f else 0.72f),
        ),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.09f else 0.52f),
                        scheme.surface.copy(alpha = 0.08f),
                        Color.White.copy(alpha = if (dark) 0.025f else 0.24f),
                    ),
                ),
            ),
        ) {
            content()
        }
    }
}

@Composable
fun QuotaBottomDock(
    selected: QuotaDestination,
    onSelect: (QuotaDestination) -> Unit,
    onAdd: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        GlassPanel(Modifier.fillMaxWidth().height(72.dp)) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockItem(
                    selected = selected == QuotaDestination.HOME,
                    onClick = { onSelect(QuotaDestination.HOME) },
                    icon = Icons.Filled.Home,
                    label = "额度",
                    modifier = Modifier.weight(1f),
                )
                DockItem(
                    selected = false,
                    onClick = onAdd,
                    icon = Icons.Filled.Add,
                    label = "添加",
                    emphasized = true,
                    modifier = Modifier.weight(1f),
                )
                DockItem(
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
            .width(104.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 12.dp, top = 16.dp, end = 8.dp, bottom = 16.dp),
    ) {
        GlassPanel(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                DockItem(
                    selected = selected == QuotaDestination.HOME,
                    onClick = { onSelect(QuotaDestination.HOME) },
                    icon = Icons.Filled.Home,
                    label = "额度",
                )
                Spacer(Modifier.height(14.dp))
                DockItem(
                    selected = false,
                    onClick = onAdd,
                    icon = Icons.Filled.Add,
                    label = "添加",
                    emphasized = true,
                )
                Spacer(Modifier.height(14.dp))
                DockItem(
                    selected = selected == QuotaDestination.SETTINGS,
                    onClick = { onSelect(QuotaDestination.SETTINGS) },
                    icon = Icons.Filled.Settings,
                    label = "设置",
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val selection by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "dockSelection",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            emphasized -> MaterialTheme.colorScheme.onPrimary
            selected -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "dockContentColor",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "dockLabelColor",
    )
    val itemShape = RoundedCornerShape(22.dp)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = itemShape,
            color = Color.Transparent,
            contentColor = contentColor,
        ) {
            Column(
                Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    Modifier.size(48.dp, 34.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = if (emphasized) 1f else selection
                                scaleX = 0.82f + 0.18f * if (emphasized) 1f else selection
                                scaleY = 0.82f + 0.18f * if (emphasized) 1f else selection
                            }
                            .clip(itemShape)
                            .background(
                                if (emphasized) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
                            )
                            .border(
                                0.7.dp,
                                if (emphasized) Color.White.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f * selection),
                                itemShape,
                            ),
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = contentColor,
                        modifier = Modifier.size(if (emphasized) 25.dp else 22.dp),
                    )
                }
                Text(
                    label,
                    color = labelColor,
                    fontSize = 11.sp,
                    fontWeight = if (selected || emphasized) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

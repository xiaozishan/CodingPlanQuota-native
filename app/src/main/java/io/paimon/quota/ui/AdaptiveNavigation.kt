package io.paimon.quota.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class QuotaDestination {
    HOME,
    SETTINGS,
}

@Composable
fun QuotaNavigationRail(
    selected: QuotaDestination,
    onSelect: (QuotaDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(88.dp),
        containerColor = MaterialTheme.colorScheme.background,
        header = { Spacer(Modifier.height(8.dp)) },
    ) {
        val colors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NavigationRailItem(
            selected = selected == QuotaDestination.HOME,
            onClick = { onSelect(QuotaDestination.HOME) },
            icon = { Icon(Icons.Filled.Home, contentDescription = "额度") },
            label = { Text("额度") },
            colors = colors,
        )
        NavigationRailItem(
            selected = selected == QuotaDestination.SETTINGS,
            onClick = { onSelect(QuotaDestination.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
            label = { Text("设置") },
            colors = colors,
        )
    }
    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

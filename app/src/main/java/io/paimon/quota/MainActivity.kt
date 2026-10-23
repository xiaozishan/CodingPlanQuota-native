package io.paimon.quota

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import io.paimon.quota.data.Prefs
import io.paimon.quota.data.Provider
import io.paimon.quota.data.QuotaApi
import io.paimon.quota.data.QuotaResult
import io.paimon.quota.ui.EditScreen
import io.paimon.quota.ui.HomeScreen
import io.paimon.quota.ui.QuotaTheme
import io.paimon.quota.ui.SettingsScreen
import io.paimon.quota.ui.ThemeMode
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val provider: Provider?) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent { AppRoot(prefs) }
    }
}

@Composable
private fun AppRoot(prefs: Prefs) {
    var mode by rememberSaveable { mutableStateOf(ThemeMode.from(prefs.themeMode)) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var providers by remember { mutableStateOf(prefs.loadProviders()) }
    var results by remember { mutableStateOf<Map<String, QuotaResult>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshAll() {
        if (loading || providers.isEmpty()) return
        loading = true
        scope.launch {
            val out = mutableMapOf<String, QuotaResult>()
            providers.forEach { p -> out[p.id] = QuotaApi.fetch(p) }
            results = out
            loading = false
        }
    }

    LaunchedEffect(providers) {
        if (providers.isNotEmpty()) {
            loading = true
            val out = mutableMapOf<String, QuotaResult>()
            providers.forEach { p -> out[p.id] = QuotaApi.fetch(p) }
            results = out
            loading = false
        }
    }

    QuotaTheme(mode) {
        Surface(color = MaterialTheme.colorScheme.background) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(
                    providers = providers,
                    results = results,
                    loading = loading,
                    onRefresh = { refreshAll() },
                    onAdd = { screen = Screen.Edit(null) },
                    onEdit = { screen = Screen.Edit(it) },
                    onSettings = { screen = Screen.Settings },
                )

                is Screen.Edit -> EditScreen(
                    existing = s.provider,
                    onBack = { screen = Screen.Home },
                    onSave = { p ->
                        providers = if (s.provider == null) providers + p
                        else providers.map { if (it.id == p.id) p else it }
                        prefs.saveProviders(providers)
                        screen = Screen.Home
                    },
                    onDelete = if (s.provider == null) null else { p ->
                        providers = providers.filterNot { it.id == p.id }
                        prefs.saveProviders(providers)
                        screen = Screen.Home
                    },
                )

                Screen.Settings -> SettingsScreen(
                    mode = mode,
                    onModeChange = { m ->
                        mode = m
                        prefs.themeMode = m.key
                    },
                    onBack = { screen = Screen.Home },
                )
            }
        }
    }
}

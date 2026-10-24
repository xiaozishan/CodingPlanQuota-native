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
    var queryGeneration by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 侧滑返回 / 系统返回键：非首页一律回首页（Android 13+ 预测式返回动画依赖 manifest 的 enableOnBackInvokedCallback）
    androidx.activity.compose.BackHandler(enabled = screen != Screen.Home) {
        screen = Screen.Home
    }

    // OAuth 刷新发生在 IO 线程；回到主线程只合并登录态，保留用户同时编辑的名称等字段。
    fun persistProviderAuth(updated: Provider) {
        scope.launch {
            providers = providers.map { old ->
                if (old.id != updated.id) old
                else old.copy(
                    token = updated.token,
                    extra = old.extra + updated.extra.filterKeys {
                        it == "accessToken" || it == "expiresAt"
                    },
                )
            }
            prefs.saveProviders(providers)
        }
    }

    suspend fun fetchQuota(p: Provider): QuotaResult =
        QuotaApi.fetch(p) { updated -> persistProviderAuth(updated) }

    fun refreshAll() {
        if (loading || providers.isEmpty()) return
        loading = true
        scope.launch {
            val out = mutableMapOf<String, QuotaResult>()
            providers.forEach { p -> out[p.id] = fetchQuota(p) }
            results = out
            loading = false
        }
    }

    // 仅用户保存/删除后开启新一轮查询；OAuth 回写登录态本身不重复刷新。
    LaunchedEffect(queryGeneration) {
        if (providers.isNotEmpty()) {
            loading = true
            val out = mutableMapOf<String, QuotaResult>()
            providers.forEach { p -> out[p.id] = fetchQuota(p) }
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
                        queryGeneration++
                        screen = Screen.Home
                    },
                    onDelete = if (s.provider == null) null else { p ->
                        providers = providers.filterNot { it.id == p.id }
                        prefs.saveProviders(providers)
                        queryGeneration++
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

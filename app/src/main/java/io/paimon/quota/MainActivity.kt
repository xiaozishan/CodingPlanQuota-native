package io.paimon.quota

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.paimon.quota.data.Prefs
import io.paimon.quota.data.Provider
import io.paimon.quota.data.ProviderKind
import io.paimon.quota.data.QuotaApi
import io.paimon.quota.data.QuotaResult
import io.paimon.quota.data.provider.GeminiWebQuota
import io.paimon.quota.ui.AddProviderSheet
import io.paimon.quota.ui.EditScreen
import io.paimon.quota.ui.HomeScreen
import io.paimon.quota.ui.QuotaBottomDock
import io.paimon.quota.ui.QuotaDestination
import io.paimon.quota.ui.QuotaSideDock
import io.paimon.quota.ui.QuotaTheme
import io.paimon.quota.ui.SettingsScreen
import io.paimon.quota.ui.ThemeMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REFRESH_LOG_TAG = "QuotaRefresh"

private sealed interface Screen {
    data object Home : Screen
    data class Edit(
        val provider: Provider?,
        val initialKind: ProviderKind = ProviderKind.DEEPSEEK,
    ) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            // 两个系统栏都不加 scrim；图标仍按深浅色主题自动切换。
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        GeminiWebQuota.initialize(applicationContext)
        val prefs = Prefs(this)
        setContent { AppRoot(prefs) }
    }
}

@Composable
private fun AppRoot(prefs: Prefs) {
    var mode by rememberSaveable { mutableStateOf(ThemeMode.from(prefs.themeMode)) }
    var mergeAccounts by rememberSaveable { mutableStateOf(prefs.mergeAccounts) }
    var autoRefreshEnabled by rememberSaveable { mutableStateOf(prefs.autoRefreshEnabled) }
    var autoRefreshIntervalSeconds by rememberSaveable {
        mutableStateOf(prefs.autoRefreshIntervalSeconds)
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var providers by remember { mutableStateOf(prefs.loadProviders()) }
    // 首次组合同步读取本地成功结果；网络刷新只在界面渲染后进行。
    var results by remember { mutableStateOf(prefs.loadQuotaResults()) }
    var loading by remember { mutableStateOf(false) }
    var queryGeneration by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // OAuth 刷新发生在 IO 线程；回到主线程只合并登录态，保留用户同时编辑的名称等字段。
    fun persistProviderAuth(updated: Provider) {
        scope.launch {
            providers = providers.map { old ->
                if (old.id != updated.id) old
                else old.copy(
                    token = updated.token,
                    extra = old.extra + updated.extra.filterKeys {
                        it == "accessToken" || it == "expiresAt" || it == "refresh_token" || it == "accountId"
                    },
                )
            }
            prefs.saveProviders(providers)
        }
    }

    suspend fun fetchQuota(p: Provider): QuotaResult =
        QuotaApi.fetch(p) { updated -> persistProviderAuth(updated) }

    fun refreshAll() {
        if (loading) return
        val requestedProviders = providers
        if (requestedProviders.isEmpty()) return
        loading = true
        scope.launch {
            val refreshStartedAt = SystemClock.elapsedRealtime()
            Log.d(REFRESH_LOG_TAG, "begin count=${requestedProviders.size}")
            try {
                coroutineScope {
                    requestedProviders.map { p ->
                        async {
                            val startedAt = SystemClock.elapsedRealtime()
                            Log.d(
                                REFRESH_LOG_TAG,
                                "start id=${p.id} afterMs=${startedAt - refreshStartedAt}",
                            )
                            val result = fetchQuota(p)
                            // 每家完成立即合并，卡片无需等待最慢的一家。
                            results = results + (p.id to result)
                            if (result.ok) prefs.saveQuotaResults(results)
                            Log.d(
                                REFRESH_LOG_TAG,
                                "done id=${p.id} ok=${result.ok} totalMs=${SystemClock.elapsedRealtime() - startedAt}",
                            )
                            p.id to result
                        }
                    }.awaitAll()
                }
            } finally {
                loading = false
                Log.d(
                    REFRESH_LOG_TAG,
                    "end totalMs=${SystemClock.elapsedRealtime() - refreshStartedAt}",
                )
            }
        }
    }

    // 每次进入前台立即查询，并仅在前台按设定间隔轮询。
    // 离开前台会自动取消协程，不在后台耗电。
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val isForeground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(isForeground, autoRefreshEnabled, autoRefreshIntervalSeconds) {
        if (isForeground && autoRefreshEnabled) {
            refreshAll()
            while (true) {
                delay(autoRefreshIntervalSeconds * 1_000L)
                refreshAll()
            }
        }
    }

    // 自动刷新开启时，首次查询由前台效果负责；关闭时仍保留启动查询。
    // 用户保存/删除后开启新一轮查询；OAuth 回写登录态本身不重复刷新。
    LaunchedEffect(queryGeneration) {
        if (providers.isNotEmpty() && (queryGeneration > 0 || !autoRefreshEnabled)) {
            refreshAll()
        }
    }

    QuotaTheme(mode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val sideDock = maxWidth >= 600.dp
                val selectedDestination = if (screen == Screen.Settings) {
                    QuotaDestination.SETTINGS
                } else {
                    QuotaDestination.HOME
                }
                val onDestinationSelect: (QuotaDestination) -> Unit = { destination ->
                    screen = when (destination) {
                        QuotaDestination.HOME -> Screen.Home
                        QuotaDestination.SETTINGS -> Screen.Settings
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    // 顶层不再重复消费状态栏 inset；各页面自行保留可交互内容的安全间距。
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (!sideDock) {
                            QuotaBottomDock(
                                selected = selectedDestination,
                                onSelect = onDestinationSelect,
                                onAdd = { showAddSheet = true },
                            )
                        }
                    },
                ) { _ ->
                    // 不消费 dock 高度，列表可以直接绘制到胶囊和系统导航栏后面。
                    Row(Modifier.fillMaxSize()) {
                        if (sideDock) {
                            QuotaSideDock(
                                selected = selectedDestination,
                                onSelect = onDestinationSelect,
                                onAdd = { showAddSheet = true },
                            )
                        }

                        Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (val s = screen) {
                Screen.Home -> HomeScreen(
                    providers = providers,
                    results = results,
                    loading = loading,
                    onRefresh = { refreshAll() },
                    onEdit = { screen = Screen.Edit(it) },
                    onDelete = { removed ->
                        val removedIds = removed.mapTo(mutableSetOf()) { it.id }
                        providers = providers.filterNot { it.id in removedIds }
                        results = results.filterKeys { it !in removedIds }
                        prefs.saveProviders(providers)
                        prefs.saveQuotaResults(results)
                        queryGeneration++
                    },
                    mergeAccounts = mergeAccounts,
                )

                is Screen.Edit -> EditScreen(
                    existing = s.provider,
                    initialKind = s.initialKind,
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
                    mergeAccounts = mergeAccounts,
                    onMergeAccountsChange = { enabled ->
                        mergeAccounts = enabled
                        prefs.mergeAccounts = enabled
                    },
                    autoRefreshEnabled = autoRefreshEnabled,
                    onAutoRefreshEnabledChange = { enabled ->
                        autoRefreshEnabled = enabled
                        prefs.autoRefreshEnabled = enabled
                    },
                    autoRefreshIntervalSeconds = autoRefreshIntervalSeconds,
                    onAutoRefreshIntervalChange = { seconds ->
                        autoRefreshIntervalSeconds = seconds
                        prefs.autoRefreshIntervalSeconds = seconds
                    },
                    onBack = { screen = Screen.Home },
                )
                        }
                        }
                    }
                }

                if (showAddSheet) {
                    AddProviderSheet(
                        onDismiss = { showAddSheet = false },
                        onSelect = { kind ->
                            showAddSheet = false
                            screen = Screen.Edit(null, kind)
                        },
                    )
                }
            }
        }
    }
}

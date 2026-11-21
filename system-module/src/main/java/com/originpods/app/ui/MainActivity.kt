package com.originpods.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.originpods.app.BuildConfig
import com.originpods.app.hook.OriginPodsXposedEntry
import com.originpods.app.runtime.LogEntry
import com.originpods.app.runtime.LogLevel
import com.originpods.app.runtime.OriginDeviceSessionManager
import com.originpods.app.runtime.OriginLogStore
import com.originpods.integration.BatteryFeatureState
import com.originpods.integration.EarbudAdapterRegistry
import com.originpods.integration.FirmwareFeatureState
import com.originpods.integration.NoiseMode
import com.originpods.integration.NoiseModeFeatureState
import com.originpods.integration.OppoModelCatalog
import com.originpods.integration.OppoModelSpec
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.FileDownloads
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog

data class RemoteTelemetryUi(
    val mac: String,
    val name: String,
    val left: Int = -1,
    val right: Int = -1,
    val box: Int = -1,
    val chargingState: Int = 0,
    val noiseMode: NoiseMode? = null,
    val firmware: String? = null,
)

class MainActivity : ComponentActivity() {
    private val remoteStates = mutableStateMapOf<String, RemoteTelemetryUi>()
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val mac = intent.getStringExtra("mac") ?: return
            val name = intent.getStringExtra("name") ?: mac
            val prev = remoteStates[mac]
            val mode = intent.getStringExtra("noise_mode")
                ?.let { runCatching { NoiseMode.valueOf(it) }.getOrNull() }
                ?: prev?.noiseMode
            remoteStates[mac] = RemoteTelemetryUi(
                mac = mac,
                name = name,
                left = intent.getIntExtra("left", prev?.left ?: -1),
                right = intent.getIntExtra("right", prev?.right ?: -1),
                box = intent.getIntExtra("box", prev?.box ?: -1),
                chargingState = intent.getIntExtra("charging_state", prev?.chargingState ?: 0),
                noiseMode = mode,
                firmware = intent.getStringExtra("firmware") ?: prev?.firmware,
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scanConnectedHeadsets()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OriginLogStore.init(this)
        enableEdgeToEdge()
        if (!receiverRegistered) {
            registerReceiver(
                stateReceiver,
                IntentFilter(OriginPodsXposedEntry.ACTION_STATE_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }
        ensureBluetoothPermissionAndScan()

        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                OriginPodsApp(
                    remoteStates = remoteStates,
                    onRefreshRequested = ::ensureBluetoothPermissionAndScan,
                )
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun ensureBluetoothPermissionAndScan() {
        sendBroadcast(
            Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                putExtra("request_state", true)
            },
        )
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        scanConnectedHeadsets()
    }

    private fun scanConnectedHeadsets() {
        val manager = getSystemService(BluetoothManager::class.java) ?: return
        val adapter: BluetoothAdapter = manager.adapter ?: return
        adapter.getProfileProxy(
            this,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runCatching {
                        proxy.connectedDevices.forEach { device ->
                            OriginDeviceSessionManager.onDeviceConnected(device)
                        }
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            },
            BluetoothProfile.A2DP,
        )
    }
}

private enum class TopDestination(val label: String) {
    DASHBOARD("主页"),
    SETTINGS("设置"),
}

private enum class SecondaryPage {
    SUPPORTED_DEVICES,
    LOGS,
    ABOUT,
}

@Composable
private fun OriginPodsApp(
    remoteStates: Map<String, RemoteTelemetryUi>,
    onRefreshRequested: () -> Unit,
) {
    var secondaryPage by remember { mutableStateOf<SecondaryPage?>(null) }
    when (secondaryPage) {
        SecondaryPage.SUPPORTED_DEVICES -> {
            SupportedDevicesScreen(onBack = { secondaryPage = null })
            return
        }
        SecondaryPage.LOGS -> {
            LogViewerScreen(onBack = { secondaryPage = null })
            return
        }
        SecondaryPage.ABOUT -> {
            AboutScreen(onBack = { secondaryPage = null })
            return
        }
        null -> Unit
    }

    val pagerState = rememberPagerState(pageCount = { TopDestination.entries.size })
    val scope = rememberCoroutineScope()
    val destinations = TopDestination.entries

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEachIndexed { index, dest ->
                    val icon = when (dest) {
                        TopDestination.DASHBOARD -> MiuixIcons.Home
                        TopDestination.SETTINGS -> MiuixIcons.Settings
                    }
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = icon,
                        label = dest.label,
                    )
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (destinations[page]) {
                TopDestination.DASHBOARD -> DashboardTab(
                    remoteStates = remoteStates,
                    onRefresh = onRefreshRequested,
                    bottomPadding = innerPadding.calculateBottomPadding(),
                )
                TopDestination.SETTINGS -> SettingsTab(
                    onRefresh = onRefreshRequested,
                    onOpenSupportedDevices = { secondaryPage = SecondaryPage.SUPPORTED_DEVICES },
                    onOpenLogs = { secondaryPage = SecondaryPage.LOGS },
                    onOpenAbout = { secondaryPage = SecondaryPage.ABOUT },
                    bottomPadding = innerPadding.calculateBottomPadding(),
                )
            }
        }
    }
}

@Composable
private fun DashboardTab(
    remoteStates: Map<String, RemoteTelemetryUi>,
    onRefresh: () -> Unit,
    bottomPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val activeDevices by OriginDeviceSessionManager.activeDevices.collectAsState()
    val modelCount = remember { OppoModelCatalog.allModels().size }

    val mergedSessions = remember(activeDevices, remoteStates.toMap()) {
        val map = linkedMapOf<String, SessionCardModel>()
        activeDevices.forEach { (mac, s) ->
            val bat = s.snapshot.get<BatteryFeatureState>()?.battery
            map[mac] = SessionCardModel(
                mac = mac,
                name = s.identity.deviceName ?: mac,
                adapterName = s.adapter.displayName,
                adapterId = s.adapter.id,
                handshakeReady = s.handshakeAccepted,
                left = bat?.left?.percent ?: -1,
                right = bat?.right?.percent ?: -1,
                box = bat?.case?.percent ?: -1,
                noiseMode = s.snapshot.get<NoiseModeFeatureState>()?.mode,
                firmware = s.snapshot.get<FirmwareFeatureState>()?.version,
            )
        }
        remoteStates.forEach { (mac, r) ->
            val prev = map[mac]
            map[mac] = SessionCardModel(
                mac = mac,
                name = r.name,
                adapterName = prev?.adapterName ?: "蓝牙后台进程",
                adapterId = prev?.adapterId ?: "remote-session",
                handshakeReady = true,
                left = if (r.left >= 0) r.left else (prev?.left ?: -1),
                right = if (r.right >= 0) r.right else (prev?.right ?: -1),
                box = if (r.box >= 0) r.box else (prev?.box ?: -1),
                noiseMode = r.noiseMode ?: prev?.noiseMode,
                firmware = r.firmware ?: prev?.firmware,
            )
        }
        map.values.toList()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = "OriginPods",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { pagePadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pagePadding.calculateTopPadding())
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = bottomPadding + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "runtime-status") {
                RuntimeStatusCard(
                    sessions = mergedSessions,
                    modelCount = modelCount,
                    onRefresh = onRefresh,
                )
            }
            item(key = "runtime-stats") {
                RuntimeStatsRow(
                    connectedCount = mergedSessions.size,
                    handshakeCount = mergedSessions.count { it.handshakeReady },
                )
            }
            item(key = "originos-header") {
                SmallTitle(text = "OriginOS 集成")
            }
            item(key = "originos-details") {
                OriginIntegrationCard(onRefresh = onRefresh)
            }
            item(key = "session-header") {
                SmallTitle(text = "设备会话 (${mergedSessions.size})")
            }
            if (mergedSessions.isEmpty()) {
                item(key = "empty-sessions") {
                    EmptySessionsCard()
                }
            } else {
                items(items = mergedSessions, key = { it.mac }) { session ->
                    ReadonlySessionCard(session = session)
                }
            }
        }
    }
}

private data class SessionCardModel(
    val mac: String,
    val name: String,
    val adapterName: String,
    val adapterId: String,
    val handshakeReady: Boolean,
    val left: Int,
    val right: Int,
    val box: Int,
    val noiseMode: NoiseMode?,
    val firmware: String?,
)

@Composable
private fun RuntimeStatusCard(
    sessions: List<SessionCardModel>,
    modelCount: Int,
    onRefresh: () -> Unit,
) {
    val ready = sessions.any { it.handshakeReady }
    val title = if (ready) "运行正常" else "等待设备连接"
    val summary = if (ready) {
        "已接管连接中心与系统蓝牙设置，控制由系统原生界面承担"
    } else {
        "已内置 $modelCount+ 款 OPPO / 一加 / realme 耳机型号，连接后自动同步"
    }
    val dark = isSystemInDarkTheme()
    val container = when {
        ready && dark -> Color(0xFF1A3825)
        ready -> Color(0xFFDFFAE4)
        dark -> Color(0xFF2C2C2E)
        else -> Color(0xFFF2F3F5)
    }
    val accent = if (ready) Color(0xFF36D167) else MiuixTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = container,
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
        onClick = onRefresh,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(50.dp, 38.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    imageVector = if (ready) MiuixIcons.Basic.Check else MiuixIcons.Info,
                    contentDescription = null,
                    modifier = Modifier.size(165.dp),
                    tint = accent.copy(alpha = 0.25f),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    text = "${sessions.size} 个活动会话 · 点击同步状态",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun RuntimeStatsRow(connectedCount: Int, handshakeCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(
            label = "活动会话",
            value = connectedCount,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatCard(
            label = "协议已确认",
            value = handshakeCount,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value.toString(),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun OriginIntegrationCard(onRefresh: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRefresh),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "系统接管通道",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                IntegrationMetric("蓝牙服务", "RFCOMM", Modifier.weight(1f))
                IntegrationMetric("连接中心", "卡片/响铃", Modifier.weight(1f))
                IntegrationMetric("系统设置", "入口识别", Modifier.weight(1f))
                IntegrationMetric("VivoTws", "原生详情", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IntegrationMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptySessionsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "暂无活动设备会话",
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "受支持耳机连接后在此展示实时遥测状态",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ReadonlySessionCard(session: SessionCardModel) {
    val dark = isSystemInDarkTheme()
    val chipBg = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2)
    val pillColor = if (session.handshakeReady) Color(0xFF36D167) else MiuixTheme.colorScheme.secondary

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.name,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "适配器  ${session.adapterName}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = buildString {
                            append("蓝牙  ${session.mac}")
                            if (!session.firmware.isNullOrEmpty()) append(" · 固件 ${session.firmware}")
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(pillColor.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (session.handshakeReady) "已就绪" else "握手中",
                        style = MiuixTheme.textStyles.footnote2,
                        fontWeight = FontWeight.SemiBold,
                        color = pillColor,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TelemetryTile("左耳", session.left.takeIf { it >= 0 }?.let { "$it%" } ?: "--", chipBg, Modifier.weight(1f))
                TelemetryTile("右耳", session.right.takeIf { it >= 0 }?.let { "$it%" } ?: "--", chipBg, Modifier.weight(1f))
                TelemetryTile("充电盒", session.box.takeIf { it >= 0 }?.let { "$it%" } ?: "--", chipBg, Modifier.weight(1f))
                TelemetryTile("降噪", session.noiseMode?.toDisplayLabel() ?: "--", chipBg, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TelemetryTile(label: String, value: String, bg: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        cornerRadius = 12.dp,
        colors = CardDefaults.defaultColors(color = bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private fun NoiseMode.toDisplayLabel(): String = when (this) {
    NoiseMode.ANC -> "降噪"
    NoiseMode.ANC_ADAPTIVE -> "自适应"
    NoiseMode.ANC_DEEP -> "深度"
    NoiseMode.ANC_MEDIUM -> "中度"
    NoiseMode.ANC_LIGHT -> "轻度"
    NoiseMode.OFF -> "关闭"
    NoiseMode.TRANSPARENCY -> "通透"
    NoiseMode.WIND -> "抗风噪"
}

private data class GitHubReleaseInfo(
    val tag: String,
    val url: String,
    val notes: String,
)

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentTag: String) : UpdateState
    data class Available(val release: GitHubReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Composable
private fun SettingsTab(
    onRefresh: () -> Unit,
    onOpenSupportedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    bottomPadding: Dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val modelCount = remember { OppoModelCatalog.allModels().size }
    var yieldEnabled by remember { mutableStateOf(true) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val launcherAlias = remember(context) {
        ComponentName(context, "com.originpods.app.ui.LauncherAlias")
    }
    var hideIcon by remember {
        mutableStateOf(
            context.packageManager.getComponentEnabledSetting(launcherAlias) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        )
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun checkGitHubUpdate() {
        if (updateState is UpdateState.Checking) return
        updateState = UpdateState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchLatestGitHubRelease() }
            updateState = result
            showUpdateDialog = true
        }
    }

    val updateSummary = when (val s = updateState) {
        UpdateState.Idle -> "当前版本 v${BuildConfig.VERSION_NAME} · 点击从 GitHub 检查"
        UpdateState.Checking -> "正在连接 GitHub Releases..."
        is UpdateState.UpToDate -> "已是最新版本 (${s.currentTag})"
        is UpdateState.Available -> "发现新版本 ${s.release.tag}"
        is UpdateState.Failed -> "检查失败: ${s.message}"
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = "设置",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { pagePadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pagePadding.calculateTopPadding())
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = bottomPadding + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SmallTitle(text = "系统集成与退避") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SwitchPreference(
                            checked = hideIcon,
                            onCheckedChange = { hide ->
                                hideIcon = hide
                                context.packageManager.setComponentEnabledSetting(
                                    launcherAlias,
                                    if (hide) {
                                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                    } else {
                                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    },
                                    PackageManager.DONT_KILL_APP,
                                )
                            },
                            title = "隐藏桌面图标",
                            summary = "隐藏后仍可从 LSPosed 管理器 → 模块中打开 OriginPods",
                        )
                        SwitchPreference(
                            checked = yieldEnabled,
                            onCheckedChange = { yieldEnabled = it },
                            title = "官方 App 运行时退避",
                            summary = "检测到欢律 (HeyMelody) 启动时自动让出 RFCOMM 通道",
                        )
                        ArrowPreference(
                            title = "支持的设备",
                            summary = "查看与搜索已适配的 $modelCount 款 OPPO / 一加 / realme 耳机",
                            onClick = onOpenSupportedDevices,
                        )
                        ArrowPreference(
                            title = "运行日志",
                            summary = "查看跨进程 Hook 日志、报错堆栈与导出诊断文件",
                            onClick = onOpenLogs,
                        )
                        ArrowPreference(
                            title = "LSPosed 推荐作用域",
                            summary = "蓝牙 · 连接中心 · 设置 · VivoTws",
                            onClick = onRefresh,
                        )
                    }
                }
            }

            item { SmallTitle(text = "关于与更新") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ArrowPreference(
                            title = "关于 OriginPods",
                            summary = "版本 v${BuildConfig.VERSION_NAME} · 作用域清单、开源协议与致谢",
                            onClick = onOpenAbout,
                        )
                        ArrowPreference(
                            title = "检查更新 (GitHub)",
                            summary = updateSummary,
                            onClick = ::checkGitHubUpdate,
                        )
                        ArrowPreference(
                            title = "GitHub 开源仓库",
                            summary = "github.com/Syuuieo/OriginPods",
                            onClick = { openUrl("https://github.com/Syuuieo/OriginPods") },
                        )
                    }
                }
            }
        }
    }

    val currentDialogState = updateState
    WindowDialog(
        show = showUpdateDialog && currentDialogState !is UpdateState.Idle && currentDialogState !is UpdateState.Checking,
        title = when (currentDialogState) {
            is UpdateState.Available -> "发现新版本 ${currentDialogState.release.tag}"
            is UpdateState.UpToDate -> "已是最新版本"
            is UpdateState.Failed -> "检查更新失败"
            else -> ""
        },
        summary = when (currentDialogState) {
            is UpdateState.Available -> currentDialogState.release.notes.ifBlank {
                "当前版本 v${BuildConfig.VERSION_NAME}，可前往 GitHub Release 下载最新版 APK。"
            }
            is UpdateState.UpToDate -> "当前已安装最新正式版 v${BuildConfig.VERSION_NAME}。"
            is UpdateState.Failed -> currentDialogState.message
            else -> ""
        },
        onDismissRequest = { showUpdateDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "关闭",
                onClick = { showUpdateDialog = false },
                modifier = Modifier.weight(1f),
            )
            if (currentDialogState is UpdateState.Available) {
                TextButton(
                    text = "前往下载",
                    onClick = {
                        showUpdateDialog = false
                        openUrl(currentDialogState.release.url)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private fun fetchLatestGitHubRelease(): UpdateState {
    return runCatching {
        val url = URL("https://api.github.com/repos/Syuuieo/OriginPods/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OriginPods/${BuildConfig.VERSION_NAME}")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            return UpdateState.Failed("HTTP $code")
        }
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifBlank { return UpdateState.Failed("未找到版本标签") }
        val htmlUrl = json.optString("html_url", "https://github.com/Syuuieo/OriginPods/releases")
        val notes = json.optString("body", "").take(300)
        val remoteVer = tag.removePrefix("v").trim()
        val localVer = BuildConfig.VERSION_NAME.removePrefix("v").trim()
        if (compareSemver(remoteVer, localVer) > 0) {
            UpdateState.Available(GitHubReleaseInfo(tag = tag, url = htmlUrl, notes = notes))
        } else {
            UpdateState.UpToDate(tag)
        }
    }.getOrElse { err ->
        UpdateState.Failed(err.message ?: "网络请求异常")
    }
}

private fun compareSemver(remote: String, local: String): Int {
    val rParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
    val lParts = local.split(".").map { it.toIntOrNull() ?: 0 }
    val max = maxOf(rParts.size, lParts.size)
    for (i in 0 until max) {
        val r = rParts.getOrElse(i) { 0 }
        val l = lParts.getOrElse(i) { 0 }
        if (r != l) return r.compareTo(l)
    }
    return 0
}

@Composable
private fun SupportedDevicesScreen(
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val scrollBehavior = MiuixScrollBehavior()
    var query by remember { mutableStateOf("") }
    val allModels = remember {
        OppoModelCatalog.allModels()
            .distinctBy { "${it.id}_${it.name}" }
            .sortedWith(compareBy<OppoModelSpec> { it.brand.lowercase() }.thenBy { it.name.lowercase() })
    }
    val filteredModels = remember(query, allModels) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            allModels
        } else {
            allModels.filter { spec ->
                spec.name.lowercase().contains(needle) ||
                    spec.brand.lowercase().contains(needle) ||
                    spec.id.lowercase().contains(needle) ||
                    spec.aliases.any { it.contains(needle.replace(" ", "")) }
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "支持的设备 (${filteredModels.size})",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { pagePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pagePadding.calculateTopPadding()),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                label = "搜索型号、品牌或 Product ID",
                useLabelAsPlaceholder = true,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp).size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = MiuixIcons.Close,
                                contentDescription = "清除搜索",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    null
                },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = pagePadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filteredModels.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(18.dp),
                        ) {
                            Text(
                                text = "未找到匹配的耳机型号",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "可尝试搜索 OPPO、OnePlus、realme 或耳机型号关键字",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    items(filteredModels, key = { "${it.id}_${it.name}" }) { spec ->
                        SupportedDeviceItemCard(spec)
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportedDeviceItemCard(spec: OppoModelSpec) {
    val brandLabel = when (spec.brand.lowercase()) {
        "oppo" -> "OPPO"
        "oneplus" -> "OnePlus"
        "realme" -> "realme"
        else -> spec.brand.ifBlank { "OPPO" }
    }
    val ancSummary = if (spec.supportedNoiseModes.isNotEmpty()) {
        "${spec.supportedNoiseModes.size} 档降噪控制"
    } else {
        "基础电量与状态"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = spec.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = brandLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "ID: ${spec.id.ifBlank { "-" }} · $ancSummary · ${if (spec.supportSpp) "SPP 协议" else "通用"}",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun AboutScreen(
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val modelCount = remember { OppoModelCatalog.allModels().size }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "关于",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { pagePadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pagePadding.calculateTopPadding())
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = pagePadding.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "OriginPods",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "面向 vivo OriginOS 6 / 7 的第三方蓝牙耳机原生集成模块",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "在不接管 Android A2DP/HFP 音频链路的前提下，将受支持的 OPPO / 一加 / realme 耳机原生接入 OriginOS 连接中心卡片、系统蓝牙设置与 VivoTws 详情页。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            item { SmallTitle(text = "项目信息") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ArrowPreference(
                            title = "GitHub 开源仓库",
                            summary = "github.com/Syuuieo/OriginPods",
                            onClick = { openUrl("https://github.com/Syuuieo/OriginPods") },
                        )
                        ArrowPreference(
                            title = "开源协议",
                            summary = "GNU General Public License v3.0 (GPL-3.0)",
                            onClick = { openUrl("https://github.com/Syuuieo/OriginPods/blob/main/LICENSE") },
                        )
                        ArrowPreference(
                            title = "已收录设备目录",
                            summary = "共 $modelCount 款 OPPO / OnePlus / realme 型号",
                            onClick = null,
                        )
                    }
                }
            }

            item { SmallTitle(text = "系统作用域清单") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    val scopes = listOf(
                        "com.android.bluetooth" to "维护单会话 RFCOMM 通道并向系统进程广播电量与降噪状态",
                        "com.vivo.connbase.connectcenter" to "注入连接中心降噪图标/标签与查找响铃转发（兼容 OriginOS 6 / 7）",
                        "com.android.settings" to "系统蓝牙设备列表识别为原生 TWS 入口",
                        "com.android.vivo.tws.vivotws" to "原生耳机详情页电量、降噪模式与功能开关桥接",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        scopes.forEach { (pkg, desc) ->
                            Column {
                                Text(
                                    text = pkg,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = desc,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }

            item { SmallTitle(text = "致谢与参考") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ArrowPreference(
                            title = "HyperEars",
                            summary = "参考模块整体架构、协议/适配器分层、蓝牙单会话桥接与 Miuix 界面风格",
                            onClick = { openUrl("https://github.com/silverpoetry/HyperEars") },
                        )
                        ArrowPreference(
                            title = "OppoPods",
                            summary = "参考 OPPO / 一加 / realme 设备白名单目录与 0x8103 协议字段解析",
                            onClick = { openUrl("https://github.com/Leaf-lsgtky/OppoPods") },
                        )
                        ArrowPreference(
                            title = "Miuix",
                            summary = "Compose Multiplatform UI 组件库",
                            onClick = { openUrl("https://github.com/compose-miuix-ui/miuix") },
                        )
                        ArrowPreference(
                            title = "libxposed",
                            summary = "现代 Xposed API 101+ 模块开发接口",
                            onClick = { openUrl("https://github.com/libxposed/api") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogViewerScreen(
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val entries by OriginLogStore.entries.collectAsState()
    val captureSource by OriginLogStore.lastCaptureSource.collectAsState()

    var query by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    val filtered = remember(entries, query, errorsOnly) {
        val needle = query.trim().lowercase()
        entries.asReversed().filter { entry ->
            val levelOk = !errorsOnly || entry.level == LogLevel.WARN || entry.level == LogLevel.ERROR
            val queryOk = needle.isEmpty() ||
                entry.tag.lowercase().contains(needle) ||
                entry.message.lowercase().contains(needle) ||
                entry.source.lowercase().contains(needle)
            levelOk && queryOk
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = OriginLogStore.buildExportText(context, filtered.asReversed())
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        }.getOrDefault(false)
        Toast.makeText(
            context,
            if (ok) "已导出 ${filtered.size} 条日志" else "日志导出失败",
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun refreshLogs() {
        if (capturing) return
        capturing = true
        scope.launch {
            OriginLogStore.captureSystemLogs(context)
            capturing = false
        }
    }

    LaunchedEffect(Unit) {
        refreshLogs()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "运行日志 (${filtered.size})",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = ::refreshLogs) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "刷新系统日志",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = {
                            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            exportLauncher.launch("OriginPods-logs-$ts.txt")
                        },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.FileDownloads,
                            contentDescription = "导出日志",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = {
                            OriginLogStore.clear(context)
                            Toast.makeText(context, "已清空本地日志", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "清空日志",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { pagePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = pagePadding.calculateTopPadding()),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                label = "筛选标签、报错或关键词",
                useLabelAsPlaceholder = true,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp).size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = MiuixIcons.Close,
                                contentDescription = "清除",
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    null
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = if (errorsOnly) "仅 W/E" else "全部级别",
                    onClick = { errorsOnly = !errorsOnly },
                    colors = if (errorsOnly) {
                        ButtonDefaults.textButtonColorsPrimary()
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                )
                TextButton(
                    text = "复制日志",
                    onClick = {
                        val text = OriginLogStore.buildExportText(context, filtered.asReversed())
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "已复制 ${filtered.size} 条日志", Toast.LENGTH_SHORT).show()
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (capturing) "正在抓取..." else captureSource,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = pagePadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(18.dp),
                        ) {
                            Text(
                                text = if (capturing) "正在读取系统日志..." else "暂无匹配的日志记录",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "可点击右上角刷新按钮抓取 Root 系统 Logcat，或取消「仅 W/E」筛选。",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { entry ->
                        LogEntryCard(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(entry: LogEntry) {
    val (levelBg, levelFg) = when (entry.level) {
        LogLevel.ERROR -> Color(0xFFFF3B30).copy(alpha = 0.14f) to Color(0xFFD92D20)
        LogLevel.WARN -> Color(0xFFFF9F0A).copy(alpha = 0.16f) to Color(0xFFB54708)
        LogLevel.INFO -> MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) to MiuixTheme.colorScheme.primary
        LogLevel.DEBUG -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f) to MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(levelBg)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = entry.level.shortLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = levelFg,
                )
            }
            Text(
                text = entry.tag,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.timestamp} · ${entry.source}",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.message,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

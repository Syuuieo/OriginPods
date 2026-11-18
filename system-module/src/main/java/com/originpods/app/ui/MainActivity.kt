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
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.originpods.app.BuildConfig
import com.originpods.app.hook.OriginPodsXposedEntry
import com.originpods.app.runtime.ActiveDeviceSessionState
import com.originpods.app.runtime.OriginDeviceSessionManager
import com.originpods.integration.BatteryFeatureState
import com.originpods.integration.EarbudAdapterRegistry
import com.originpods.integration.FirmwareFeatureState
import com.originpods.integration.NoiseMode
import com.originpods.integration.NoiseModeFeatureState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.PressFeedbackType

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
    ABOUT("关于"),
}

@Composable
private fun OriginPodsApp(
    remoteStates: Map<String, RemoteTelemetryUi>,
    onRefreshRequested: () -> Unit,
) {
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
                        TopDestination.ABOUT -> MiuixIcons.Info
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
                    bottomPadding = innerPadding.calculateBottomPadding(),
                )
                TopDestination.ABOUT -> AboutTab(
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
    onRefresh: () -> Unit,
) {
    val ready = sessions.any { it.handshakeReady }
    val title = if (ready) "运行正常" else "等待设备连接"
    val summary = if (ready) {
        "已接管连接中心与系统蓝牙设置，控制由系统原生界面承担"
    } else {
        "连接 OPPO / OnePlus 耳机后自动同步至 OriginOS"
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

@Composable
private fun SettingsTab(
    onRefresh: () -> Unit,
    bottomPadding: Dp,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val adapters = remember { EarbudAdapterRegistry.catalog() }
    var disabledIds by remember { mutableStateOf(OriginDeviceSessionManager.disabledAdapterIds) }
    var yieldEnabled by remember { mutableStateOf(true) }
    val launcherAlias = remember(context) {
        ComponentName(context, "com.originpods.app.ui.LauncherAlias")
    }
    var hideIcon by remember {
        mutableStateOf(
            context.packageManager.getComponentEnabledSetting(launcherAlias) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        )
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
                            title = "LSPosed 推荐作用域",
                            summary = "蓝牙 · 连接中心 · 设置 · VivoTws",
                            onClick = onRefresh,
                        )
                    }
                }
            }
            item { SmallTitle(text = "已注册协议适配器") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        adapters.forEach { adapter ->
                            val enabled = adapter.id !in disabledIds
                            SwitchPreference(
                                checked = enabled,
                                onCheckedChange = { nextEnabled ->
                                    val next = if (nextEnabled) {
                                        disabledIds - adapter.id
                                    } else {
                                        disabledIds + adapter.id
                                    }
                                    disabledIds = next
                                    OriginDeviceSessionManager.disabledAdapterIds = next
                                },
                                title = adapter.displayName,
                                summary = "ID: ${adapter.id} · ${adapter.resolution.name}",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutTab(bottomPadding: Dp) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = "关于",
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
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "OriginPods",
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "版本 v${BuildConfig.VERSION_NAME} · OriginOS 蓝牙耳机原生集成模块",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "将第三方 OPPO / OnePlus TWS 耳机原生接入 OriginOS 连接中心卡片、播放声音查找、系统蓝牙设置与 VivoTws 详情页，无需在模块控制台内手动切换。",
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
            item { SmallTitle(text = "系统作用域清单") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            "com.android.bluetooth — RFCOMM 协议与连接中心数据桥接",
                            "com.vivo.connbase.connectcenter — 降噪图标与播放声音转发",
                            "com.android.settings — 蓝牙设备列表原生入口识别",
                            "com.android.vivo.tws.vivotws — 原生耳机详情页与设置项桥接",
                        ).forEach { line ->
                            Text(
                                text = line,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
}

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
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionColors
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.Dialog
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.layout.Scaffold
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.navigation.NavigationBar
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiTextStyle
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.LargeTopAppBar
import com.itos.coui.topappbar.TopAppBar
import com.itos.coui.topappbar.TopAppBarButton
import com.itos.coui.topappbar.TopAppBarButtonGroup
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
            // BluetoothProcessHook (com.android.bluetooth) owns the single RFCOMM session;
            // close any fallback local socket in the UI process so they never compete.
            OriginDeviceSessionManager.onDeviceDisconnected(mac)
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
        sendBroadcast(
            Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                putExtra("request_state", true)
            },
        )
        window.decorView.postDelayed({ ensureBluetoothPermissionAndScan() }, 1200L)

        setContent {
            CouiTheme {
                OriginPodsApp(
                    remoteStates = remoteStates,
                    onRefreshRequested = {
                        ensureBluetoothPermissionAndScan()
                        sendBroadcast(
                            Intent(OriginPodsXposedEntry.ACTION_CONTROL).apply {
                                putExtra("request_state", true)
                            },
                        )
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun ensureBluetoothPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            scanConnectedHeadsets()
        } else {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun scanConnectedHeadsets() {
        val manager = getSystemService(BluetoothManager::class.java) ?: return
        val adapter = manager.adapter ?: return
        adapter.getProfileProxy(
            this,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runCatching {
                        proxy.connectedDevices.forEach { device ->
                            val addr = device.address
                            if (addr != null && !remoteStates.containsKey(addr)) {
                                OriginDeviceSessionManager.onDeviceConnected(device)
                            }
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

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val scrollBehavior = CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val dark = CouiTheme.isDark
    val pageColors = CouiPageDefaults.themeColors(dark)

    val navItems = listOf(
        CouiNavigationItem(
            title = "主页",
            icon = CouiIcons.Outlined.Headset,
            selectedIcon = CouiIcons.Filled.Headset,
        ),
        CouiNavigationItem(
            title = "设置",
            icon = CouiIcons.Outlined.Setting,
            selectedIcon = CouiIcons.Filled.Setting,
        ),
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = pageColors.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    BasicText(
                        text = if (selectedTab == 0) "OriginPods" else "设置",
                        style = LocalCouiTextStyle.current,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (Build.VERSION.SDK_INT >= 34) {
                NavigationBar(
                    items = navItems,
                    selectedIndex = selectedTab,
                    onSelection = { selectedTab = it },
                )
            } else {
                FallbackBottomBar(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }
        },
    ) { padding ->
        if (selectedTab == 0) {
            DashboardContent(
                remoteStates = remoteStates,
                onRefresh = onRefreshRequested,
                onOpenSupportedDevices = { secondaryPage = SecondaryPage.SUPPORTED_DEVICES },
                contentPadding = padding,
            )
        } else {
            SettingsContent(
                onRefresh = onRefreshRequested,
                onOpenSupportedDevices = { secondaryPage = SecondaryPage.SUPPORTED_DEVICES },
                onOpenLogs = { secondaryPage = SecondaryPage.LOGS },
                onOpenAbout = { secondaryPage = SecondaryPage.ABOUT },
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun FallbackBottomBar(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .clip(CircleShape)
            .background(colors.card)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("主页", "设置").forEachIndexed { idx, label ->
            val selected = selectedTab == idx
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (selected) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onSelect(idx) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    kind = CouiTextKind.ItemTitle,
                    color = if (selected) colors.accent else colors.summary,
                )
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
private fun DashboardContent(
    remoteStates: Map<String, RemoteTelemetryUi>,
    onRefresh: () -> Unit,
    onOpenSupportedDevices: () -> Unit,
    contentPadding: PaddingValues,
) {
    val activeDevices by OriginDeviceSessionManager.activeDevices.collectAsState()
    val modelCount = remember { OppoModelCatalog.allModels().size }
    val adapterCount = remember { EarbudAdapterRegistry.catalog().size }
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)

    val sessions = remember(activeDevices, remoteStates.toMap()) {
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(contentPadding),
        contentPadding = contentPadding,
    ) {
        item {
            Group(
                title = "系统集成概览",
                footer = "在连接中心卡片、系统蓝牙设置与 VivoTws 详情页内直接操作降噪与查找，无需在模块内手动切换。",
                colors = colors,
            ) {
                Item(
                    icon = { CouiIconGlyph(CouiIcons.Filled.Bluetooth, colors.accent) },
                    summary = {
                        Text(
                            text = if (sessions.isNotEmpty()) {
                                "已接管 ${sessions.size} 台设备 · OriginOS 6 / 7 原生桥接运行中"
                            } else {
                                "等待受支持耳机连接 · 点击刷新同步状态"
                            },
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    },
                    onClick = onRefresh,
                ) {
                    Text(
                        text = if (sessions.isNotEmpty()) "模块运行中" else "模块已就绪",
                        kind = CouiTextKind.ItemTitle,
                        color = colors.title,
                    )
                }
                Divider(color = colors.divider)
                Item(
                    icon = { CouiIconGlyph(CouiIcons.Filled.Headset, colors.accent) },
                    summary = {
                        Text(
                            text = "$adapterCount 组品牌适配器 · $modelCount 款 OPPO / 一加 / realme 型号",
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    },
                    trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                    onClick = onOpenSupportedDevices,
                ) {
                    Text(
                        text = "多品牌支持目录",
                        kind = CouiTextKind.ItemTitle,
                        color = colors.title,
                    )
                }
            }
        }

        if (sessions.isEmpty()) {
            item {
                Group(
                    title = "当前设备",
                    colors = colors,
                ) {
                    Item(
                        icon = { CouiIconGlyph(CouiIcons.Outlined.Headset, colors.summary) },
                        summary = {
                            Text(
                                text = "支持 OPPO / 一加 / realme、华为、荣耀、Sony、三星、Bose、漫步者、水月雨、QCY、弱水时砂、Technics 等设备。",
                                kind = CouiTextKind.ItemSummary,
                                color = colors.summary,
                            )
                        },
                        onClick = onRefresh,
                    ) {
                        Text(
                            text = "暂未检测到已连接的受支持耳机",
                            kind = CouiTextKind.ItemTitle,
                            color = colors.title,
                        )
                    }
                }
            }
        } else {
            items(sessions, key = { it.mac }) { session ->
                Group(
                    title = "已连接设备",
                    colors = colors,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.name,
                                    kind = CouiTextKind.ItemTitle,
                                    color = colors.title,
                                )
                                Text(
                                    text = "${session.adapterName} · ${session.mac}",
                                    kind = CouiTextKind.ItemSummary,
                                    color = colors.summary,
                                )
                            }
                            StatusPill(
                                text = if (session.handshakeReady) "已握手" else "连接中",
                                accent = if (session.handshakeReady) Color(0xFF1A9E52) else colors.accent,
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BatteryTile("左耳", session.left, colors, Modifier.weight(1f))
                            BatteryTile("右耳", session.right, colors, Modifier.weight(1f))
                            BatteryTile("充电盒", session.box, colors, Modifier.weight(1f))
                        }

                        val modeLabel = when (session.noiseMode) {
                            NoiseMode.ANC -> "降噪"
                            NoiseMode.ANC_ADAPTIVE -> "自适应降噪"
                            NoiseMode.ANC_DEEP -> "深度降噪"
                            NoiseMode.ANC_MEDIUM -> "中度降噪"
                            NoiseMode.ANC_LIGHT -> "轻度降噪"
                            NoiseMode.TRANSPARENCY -> "通透模式"
                            NoiseMode.OFF -> "关闭"
                            NoiseMode.WIND -> "抗风噪"
                            null -> "未同步"
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = buildString {
                                append("降噪状态：$modeLabel")
                                session.firmware?.takeIf { it.isNotBlank() }?.let { append(" · 固件 $it") }
                            },
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun BatteryTile(
    label: String,
    percent: Int,
    colors: com.itos.coui.layout.CouiPageColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.background)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (percent >= 0) "$percent%" else "--",
            kind = CouiTextKind.ItemTitle,
            color = if (percent in 0..20) Color(0xFFD92D20) else colors.title,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            kind = CouiTextKind.ItemSummary,
            color = colors.summary,
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    accent: Color,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            ),
        )
    }
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
private fun SettingsContent(
    onRefresh: () -> Unit,
    onOpenSupportedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)

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

    val quickExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val allEntries = OriginLogStore.entries.value
        val text = OriginLogStore.buildExportText(context, allEntries)
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        }.getOrDefault(false)
        Toast.makeText(
            context,
            if (ok) "已导出 ${allEntries.size} 条日志" else "日志导出失败",
            Toast.LENGTH_SHORT,
        ).show()
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(contentPadding),
        contentPadding = contentPadding,
    ) {
        item {
            Group(
                title = "系统集成与退避",
                colors = colors,
            ) {
                CouiSwitchItem(
                    title = "隐藏桌面图标",
                    summary = "隐藏后仍可从 LSPosed 管理器 → 模块中打开 OriginPods",
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
                    colors = colors,
                    dark = dark,
                )
                Divider(color = colors.divider)
                CouiSwitchItem(
                    title = "官方 App 运行时退避",
                    summary = "检测到欢律、智慧生活、Sony Sound Connect、Galaxy Wearable 等启动时自动让出通道",
                    checked = yieldEnabled,
                    onCheckedChange = { yieldEnabled = it },
                    colors = colors,
                    dark = dark,
                )
                Divider(color = colors.divider)
                Item(
                    icon = { CouiIconGlyph(CouiIcons.Filled.Headset, colors.accent) },
                    summary = {
                        Text(
                            text = "OPPO / 一加 / realme ($modelCount 款) 及华为、荣耀、Sony、三星、Bose 等",
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    },
                    trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                    onClick = onOpenSupportedDevices,
                ) {
                    Text(text = "支持的设备", kind = CouiTextKind.ItemTitle, color = colors.title)
                }
                Divider(color = colors.divider)
                Item(
                    icon = { CouiIconGlyph(CouiIcons.Filled.Lab, colors.accent) },
                    summary = {
                        Text(
                            text = "查看跨进程 Hook 日志、报错堆栈与导出诊断文件",
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    },
                    trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                    onClick = onOpenLogs,
                ) {
                    Text(text = "运行日志", kind = CouiTextKind.ItemTitle, color = colors.title)
                }
                Divider(color = colors.divider)
                Item(
                    icon = { CouiIconGlyph(CouiIcons.Filled.CloudData, colors.accent) },
                    summary = {
                        Text(
                            text = "自动抓取系统日志并保存为 .txt 文件",
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    },
                    trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                    onClick = {
                        scope.launch {
                            OriginLogStore.captureSystemLogs(context)
                            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            quickExportLauncher.launch("OriginPods-logs-$ts.txt")
                        }
                    },
                ) {
                    Text(text = "一键导出日志", kind = CouiTextKind.ItemTitle, color = colors.title)
                }
                Divider(color = colors.divider)
                Item(
                    icon = { CouiIconGlyph(CouiIcons.Filled.ShieldCheck, colors.accent) },
                    summary = {
                        Text(
                            text = "蓝牙 · 连接中心 · 设置 · VivoTws",
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    },
                    onClick = onRefresh,
                ) {
                    Text(text = "LSPosed 推荐作用域", kind = CouiTextKind.ItemTitle, color = colors.title)
                }
            }
        }

        item {
            Group(
                title = "关于与更新",
                colors = colors,
            ) {
                Item(
                    icon = { CouiIconGlyph(CouiIcons.Filled.BookOpen, colors.accent) },
                    summary = {
                        Text(
                            text = "版本 v${BuildConfig.VERSION_NAME} · 作用域清单、开源协议与致谢",
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    },
                    trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                    onClick = onOpenAbout,
                ) {
                    Text(text = "关于 OriginPods", kind = CouiTextKind.ItemTitle, color = colors.title)
                }
                Divider(color = colors.divider)
                Item(
                    icon = { CouiIconGlyph(CouiIcons.Filled.AppUpdate, colors.accent) },
                    summary = {
                        Text(
                            text = updateSummary,
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    },
                    trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                    onClick = ::checkGitHubUpdate,
                ) {
                    Text(text = "检查更新 (GitHub)", kind = CouiTextKind.ItemTitle, color = colors.title)
                }
                Divider(color = colors.divider)
                Item(
                    icon = { CouiIconGlyph(CouiIcons.Filled.CloudLink, colors.accent) },
                    summary = {
                        Text(
                            text = "github.com/Syuuieo/OriginPods",
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    },
                    trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                    onClick = { openUrl("https://github.com/Syuuieo/OriginPods") },
                ) {
                    Text(text = "GitHub 开源仓库", kind = CouiTextKind.ItemTitle, color = colors.title)
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    val currentDialogState = updateState
    val dialogTitle = when (currentDialogState) {
        is UpdateState.Available -> "发现新版本 ${currentDialogState.release.tag}"
        is UpdateState.UpToDate -> "已是最新版本"
        is UpdateState.Failed -> "检查更新失败"
        else -> null
    }
    val dialogMessage = when (currentDialogState) {
        is UpdateState.Available -> buildString {
            append("当前版本: v${BuildConfig.VERSION_NAME}\n")
            append("最新版本: ${currentDialogState.release.tag}")
            if (currentDialogState.release.notes.isNotBlank()) {
                append("\n\n${currentDialogState.release.notes}")
            }
        }
        is UpdateState.UpToDate -> "当前版本 v${BuildConfig.VERSION_NAME} 已是 GitHub Releases 最新版本 (${currentDialogState.currentTag})。"
        is UpdateState.Failed -> "无法获取 GitHub Releases：${currentDialogState.message}"
        else -> null
    }

    val primaryActionColors = remember(colors.accent) {
        CouiDialogActionColors(
            surface = colors.accent,
            content = Color.White,
            disabledSurface = colors.accent.copy(alpha = 0.3f),
            disabledContent = Color.White.copy(alpha = 0.5f),
        )
    }

    Dialog(
        visible = showUpdateDialog && dialogTitle != null,
        onDismissRequest = { showUpdateDialog = false },
        dark = dark,
        title = dialogTitle,
        message = dialogMessage,
        confirmAction = if (currentDialogState is UpdateState.Available) {
            CouiDialogAction(
                text = "前往下载",
                style = CouiDialogActionStyle.Primary,
                colors = primaryActionColors,
                onClick = {
                    showUpdateDialog = false
                    openUrl(currentDialogState.release.url)
                },
            )
        } else {
            CouiDialogAction(
                text = "确定",
                style = CouiDialogActionStyle.Primary,
                colors = primaryActionColors,
                onClick = {
                    showUpdateDialog = false
                },
            )
        },
        dismissAction = if (currentDialogState is UpdateState.Available) {
            CouiDialogAction("稍后", onClick = { showUpdateDialog = false })
        } else {
            null
        },
    )
}

@Composable
private fun CouiSwitchItem(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: com.itos.coui.layout.CouiPageColors,
    dark: Boolean,
) {
    val source = remember { MutableInteractionSource() }
    Item(
        modifier = Modifier
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .couiButtonFeedback(dark, source, enabled = true, list = true),
        summary = {
            Text(text = summary, kind = CouiTextKind.ItemSummary, color = colors.summary)
        },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                interactionSource = source,
                onDragCheckedChange = onCheckedChange,
                dark = dark,
            )
        },
    ) {
        Text(text = title, kind = CouiTextKind.ItemTitle, color = colors.title)
    }
}

@Composable
private fun CouiIconGlyph(
    icon: ImageVector,
    tint: Color,
    sizeDp: Int = 22,
) {
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(sizeDp.dp),
        colorFilter = ColorFilter.tint(tint),
    )
}

@Composable
private fun CouiNavBackButton(
    onBack: () -> Unit,
    tint: Color,
) {
    if (Build.VERSION.SDK_INT >= 34) {
        TopAppBarButton(onClick = onBack) {
            CouiIconGlyph(CouiIcons.Filled.Back, tint, 20)
        }
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            CouiIconGlyph(CouiIcons.Filled.Back, tint, 20)
        }
    }
}

@Composable
private fun CouiActionButton(
    text: String,
    onClick: () -> Unit,
    tint: Color,
) {
    if (Build.VERSION.SDK_INT >= 34) {
        TopAppBarButton(onClick = onClick, text = true) {
            BasicText(text = text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = tint))
        }
    } else {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            BasicText(text = text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = tint))
        }
    }
}

@Composable
private fun SupportedDevicesScreen(
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    var query by rememberSaveable { mutableStateOf("") }
    var selectedBrand by rememberSaveable { mutableStateOf("all") }

    val thirdPartyModels = remember { com.originpods.integration.MultiBrandModelCatalog.models }
    val allModels = remember {
        OppoModelCatalog.allModels()
            .distinctBy { "${it.id}_${it.name}" }
            .sortedWith(compareBy<OppoModelSpec> { it.brand.lowercase() }.thenBy { it.name.lowercase() })
    }

    val brandTabs = remember(allModels, thirdPartyModels) {
        val oppoCount = allModels.count { it.brand.equals("oppo", ignoreCase = true) }
        val onePlusCount = allModels.count { it.brand.equals("oneplus", ignoreCase = true) }
        val realmeCount = allModels.count { it.brand.equals("realme", ignoreCase = true) }
        val xiaomiCount = thirdPartyModels.count { it.brandKey == "xiaomi" }
        val huaweiCount = thirdPartyModels.count { it.brandKey == "huawei" }
        val sonyCount = thirdPartyModels.count { it.brandKey == "sony" }
        val otherCount = thirdPartyModels.count { it.brandKey == "others" }
        listOf(
            "all" to "全部 (${allModels.size + thirdPartyModels.size})",
            "oppo" to "OPPO ($oppoCount)",
            "oneplus" to "一加 ($onePlusCount)",
            "realme" to "realme ($realmeCount)",
            "xiaomi" to "小米/Redmi ($xiaomiCount)",
            "huawei" to "华为/荣耀 ($huaweiCount)",
            "sony" to "Sony ($sonyCount)",
            "others" to "其他品牌 ($otherCount)",
        )
    }

    val needle = query.trim().lowercase()
    val filteredThirdPartyModels = remember(needle, selectedBrand, thirdPartyModels) {
        val byBrand = when (selectedBrand) {
            "all" -> thirdPartyModels
            "xiaomi", "huawei", "sony", "others" -> thirdPartyModels.filter { it.brandKey == selectedBrand }
            else -> emptyList()
        }
        if (needle.isEmpty()) {
            byBrand
        } else {
            byBrand.filter {
                it.name.lowercase().contains(needle) ||
                    it.brand.lowercase().contains(needle) ||
                    it.adapterId.lowercase().contains(needle)
            }
        }
    }

    val filteredModels = remember(needle, selectedBrand, allModels) {
        val byBrand = when (selectedBrand) {
            "all" -> allModels
            "oppo" -> allModels.filter { it.brand.equals("oppo", ignoreCase = true) }
            "oneplus" -> allModels.filter { it.brand.equals("oneplus", ignoreCase = true) }
            "realme" -> allModels.filter { it.brand.equals("realme", ignoreCase = true) }
            else -> emptyList()
        }
        if (needle.isEmpty()) {
            byBrand
        } else {
            byBrand.filter { spec ->
                spec.name.lowercase().contains(needle) ||
                    spec.brand.lowercase().contains(needle) ||
                    spec.id.lowercase().contains(needle) ||
                    spec.aliases.any { it.contains(needle.replace(" ", "")) }
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    BasicText(
                        text = "支持的设备 (${filteredModels.size + filteredThirdPartyModels.size})",
                        style = LocalCouiTextStyle.current,
                    )
                },
                navigationIcon = { CouiNavBackButton(onBack = onBack, tint = colors.title) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .consumeWindowInsets(padding),
        ) {
            CompactSearchInput(
                query = query,
                onQueryChange = { query = it },
                placeholder = "搜索型号、品牌或 Product ID",
                colors = colors,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                brandTabs.forEach { (key, label) ->
                    val selected = selectedBrand == key
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) colors.accent else colors.card)
                            .clickable { selectedBrand = key }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        BasicText(
                            text = label,
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) Color.White else colors.title,
                            ),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                if (filteredModels.isNotEmpty()) {
                    item {
                        val sectionTitle = when (selectedBrand) {
                            "oppo" -> "OPPO 型号目录 (${filteredModels.size})"
                            "oneplus" -> "一加 (OnePlus) 型号目录 (${filteredModels.size})"
                            "realme" -> "realme 真我型号目录 (${filteredModels.size})"
                            else -> "OPPO / 一加 / realme 型号目录 (${filteredModels.size})"
                        }
                        Group(
                            title = sectionTitle,
                            colors = colors,
                        ) {}
                    }
                    items(filteredModels, key = { "${it.id}_${it.name}" }) { spec ->
                        SupportedDeviceItemCard(spec, colors)
                    }
                }

                if (filteredThirdPartyModels.isNotEmpty()) {
                    item {
                        val thirdPartyTitle = when (selectedBrand) {
                            "xiaomi" -> "小米 / Redmi 型号目录 (${filteredThirdPartyModels.size})"
                            "huawei" -> "华为 / 荣耀型号目录 (${filteredThirdPartyModels.size})"
                            "sony" -> "Sony 型号目录 (${filteredThirdPartyModels.size})"
                            "others" -> "其他品牌型号目录 (${filteredThirdPartyModels.size})"
                            else -> "跨品牌适配型号 (${filteredThirdPartyModels.size})"
                        }
                        Group(
                            title = thirdPartyTitle,
                            colors = colors,
                        ) {}
                    }
                    items(filteredThirdPartyModels, key = { "${it.adapterId}_${it.name}" }) { spec ->
                        Group(colors = colors) {
                            Item(
                                summary = {
                                    Text(
                                        text = "协议: ${spec.adapterId} · ${if (spec.ancSupported) "电量与降噪控制" else "基础电量协议"}",
                                        kind = CouiTextKind.ItemSummary,
                                        color = colors.summary,
                                    )
                                },
                                trailing = { StatusPill(spec.brand, colors.accent) },
                            ) {
                                Text(
                                    text = spec.name,
                                    kind = CouiTextKind.ItemTitle,
                                    color = colors.title,
                                )
                            }
                        }
                    }
                }

                if (filteredThirdPartyModels.isEmpty() && filteredModels.isEmpty()) {
                    item {
                        Group(title = "无匹配结果", colors = colors) {
                            Item(
                                summary = {
                                    Text(
                                        text = "可切换品牌分类或更换搜索关键字（如 OPPO、Redmi、FreeBuds、Sony）",
                                        kind = CouiTextKind.ItemSummary,
                                        color = colors.summary,
                                    )
                                },
                            ) {
                                Text(
                                    text = "未找到匹配的耳机型号",
                                    kind = CouiTextKind.ItemTitle,
                                    color = colors.title,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    colors: com.itos.coui.layout.CouiPageColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(CircleShape)
            .background(colors.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CouiIconGlyph(CouiIcons.Outlined.Search, colors.summary, 18)
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                BasicText(
                    text = placeholder,
                    style = TextStyle(fontSize = 14.sp, color = colors.summary),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = colors.title),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onQueryChange("") }
                    .padding(4.dp),
            ) {
                CouiIconGlyph(CouiIcons.Filled.Close, colors.summary, 16)
            }
        }
    }
}

@Composable
private fun SupportedDeviceItemCard(
    spec: OppoModelSpec,
    colors: com.itos.coui.layout.CouiPageColors,
) {
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
    Group(colors = colors) {
        Item(
            summary = {
                Text(
                    text = "ID: ${spec.id.ifBlank { "-" }} · $ancSummary · ${if (spec.supportSpp) "SPP 协议" else "通用"}",
                    kind = CouiTextKind.ItemSummary,
                    color = colors.summary,
                )
            },
            trailing = { StatusPill(brandLabel, colors.accent) },
        ) {
            Text(
                text = spec.name,
                kind = CouiTextKind.ItemTitle,
                color = colors.title,
            )
        }
    }
}

@Composable
private fun AboutScreen(
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val modelCount = remember { OppoModelCatalog.allModels().size }
    val adapterCount = remember { EarbudAdapterRegistry.catalog().size }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { BasicText("关于", style = LocalCouiTextStyle.current) },
                navigationIcon = { CouiNavBackButton(onBack = onBack, tint = colors.title) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding),
            contentPadding = contentPaddingWithBottom(padding),
        ) {
            item {
                Group(colors = colors) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                text = "OriginPods",
                                style = TextStyle(
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.title,
                                ),
                            )
                            StatusPill(
                                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                accent = colors.accent,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        BasicText(
                            text = "面向 vivo OriginOS 6 / 7 的第三方蓝牙耳机原生集成模块",
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.accent,
                            ),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "基于 COUI-Kit 重构前端界面，在不接管 Android A2DP/HFP 音频链路的前提下，为 OPPO / 一加 / realme、华为、荣耀、Sony、三星、Bose、漫步者、水月雨、QCY、弱水时砂、Technics 等耳机提供原生系统集成。",
                            kind = CouiTextKind.ItemSummary,
                            color = colors.summary,
                        )
                    }
                }
            }

            item {
                Group(title = "项目信息", colors = colors) {
                    Item(
                        summary = { Text(text = "github.com/Syuuieo/OriginPods", kind = CouiTextKind.ItemSummary, color = colors.summary) },
                        trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                        onClick = { openUrl("https://github.com/Syuuieo/OriginPods") },
                    ) {
                        Text(text = "GitHub 开源仓库", kind = CouiTextKind.ItemTitle, color = colors.title)
                    }
                    Divider(color = colors.divider)
                    Item(
                        summary = { Text(text = "GNU General Public License v3.0 (GPL-3.0)", kind = CouiTextKind.ItemSummary, color = colors.summary) },
                        trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                        onClick = { openUrl("https://github.com/Syuuieo/OriginPods/blob/main/LICENSE") },
                    ) {
                        Text(text = "开源协议", kind = CouiTextKind.ItemTitle, color = colors.title)
                    }
                    Divider(color = colors.divider)
                    Item(
                        summary = {
                            Text(
                                text = "$adapterCount 组品牌适配器 · $modelCount 款 OPPO / OnePlus / realme 型号",
                                kind = CouiTextKind.ItemSummary,
                                color = colors.summary,
                            )
                        },
                    ) {
                        Text(text = "已收录设备目录", kind = CouiTextKind.ItemTitle, color = colors.title)
                    }
                }
            }

            item {
                Group(title = "系统作用域清单", colors = colors) {
                    val scopes = listOf(
                        "com.android.bluetooth" to "维护单会话 RFCOMM 通道并向系统进程广播电量与降噪状态",
                        "com.vivo.connbase.connectcenter" to "注入连接中心降噪图标/标签与查找响铃转发（兼容 OriginOS 6 / 7）",
                        "com.android.settings" to "系统蓝牙设备列表识别为原生 TWS 入口",
                        "com.android.vivo.tws.vivotws" to "原生耳机详情页电量、降噪模式与功能开关桥接",
                    )
                    scopes.forEachIndexed { idx, (pkg, desc) ->
                        if (idx > 0) Divider(color = colors.divider)
                        Item(
                            summary = { Text(text = desc, kind = CouiTextKind.ItemSummary, color = colors.summary) },
                        ) {
                            Text(text = pkg, kind = CouiTextKind.ItemTitle, color = colors.title)
                        }
                    }
                }
            }

            item {
                Group(title = "致谢与参考", colors = colors) {
                    val credits = listOf(
                        Triple("HyperEars", "参考整体模块架构、协议/适配器分层、上游多品牌协议实现与单会话桥接", "https://github.com/silverpoetry/HyperEars"),
                        Triple("OppoPods", "参考 OPPO / 一加 / realme 设备白名单目录与 0x8103 协议字段解析", "https://github.com/Leaf-lsgtky/OppoPods"),
                        Triple("COUI-Kit", "提供 ColorOS 风格 Compose 组件库与凝光视效", "https://github.com/ItosEO/COUI-Kit"),
                        Triple("libxposed", "提供现代 Xposed API 101+ 模块开发接口", "https://github.com/libxposed/api"),
                    )
                    credits.forEachIndexed { idx, (name, desc, url) ->
                        if (idx > 0) Divider(color = colors.divider)
                        Item(
                            summary = { Text(text = desc, kind = CouiTextKind.ItemSummary, color = colors.summary) },
                            trailing = { CouiIconGlyph(CouiIcons.Filled.Next, colors.summary, 18) },
                            onClick = { openUrl(url) },
                        ) {
                            Text(text = name, kind = CouiTextKind.ItemTitle, color = colors.title)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LogViewerScreen(
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)

    val entries by OriginLogStore.entries.collectAsState()
    val captureSource by OriginLogStore.lastCaptureSource.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
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
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { BasicText("运行日志 (${filtered.size})", style = LocalCouiTextStyle.current) },
                navigationIcon = { CouiNavBackButton(onBack = onBack, tint = colors.title) },
                actions = {
                    if (Build.VERSION.SDK_INT >= 34) {
                        TopAppBarButtonGroup {
                            CouiActionButton("刷新", ::refreshLogs, colors.title)
                            CouiActionButton(
                                "导出",
                                {
                                    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                    exportLauncher.launch("OriginPods-logs-$ts.txt")
                                },
                                colors.accent,
                            )
                        }
                    } else {
                        CouiActionButton("刷新", ::refreshLogs, colors.title)
                        CouiActionButton(
                            "导出",
                            {
                                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                exportLauncher.launch("OriginPods-logs-$ts.txt")
                            },
                            colors.accent,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .consumeWindowInsets(padding),
        ) {
            CompactSearchInput(
                query = query,
                onQueryChange = { query = it },
                placeholder = "筛选标签、报错或关键词",
                colors = colors,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionChip(
                    text = if (errorsOnly) "仅 W/E" else "全部级别",
                    active = errorsOnly,
                    colors = colors,
                    onClick = { errorsOnly = !errorsOnly },
                )
                ActionChip(
                    text = "复制日志",
                    active = false,
                    colors = colors,
                    onClick = {
                        val text = OriginLogStore.buildExportText(context, filtered.asReversed())
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "已复制 ${filtered.size} 条日志", Toast.LENGTH_SHORT).show()
                    },
                )
                ActionChip(
                    text = "导出 .txt",
                    active = true,
                    colors = colors,
                    onClick = {
                        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        exportLauncher.launch("OriginPods-logs-$ts.txt")
                    },
                )
                ActionChip(
                    text = "清空",
                    active = false,
                    colors = colors,
                    onClick = {
                        OriginLogStore.clear(context)
                        Toast.makeText(context, "已清空本地日志", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            BasicText(
                text = if (capturing) "正在抓取系统日志..." else "来源：$captureSource",
                style = TextStyle(fontSize = 11.sp, color = colors.summary),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Group(colors = colors) {
                            Item(
                                summary = {
                                    Text(
                                        text = "可点击右上角「刷新」抓取 Root 系统 Logcat，或取消「仅 W/E」筛选。",
                                        kind = CouiTextKind.ItemSummary,
                                        color = colors.summary,
                                    )
                                },
                            ) {
                                Text(
                                    text = if (capturing) "正在读取系统日志..." else "暂无匹配的日志记录",
                                    kind = CouiTextKind.ItemTitle,
                                    color = colors.title,
                                )
                            }
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { entry ->
                        LogEntryCard(entry, colors)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    active: Boolean,
    colors: com.itos.coui.layout.CouiPageColors,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) colors.accent else colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) Color.White else colors.title,
            ),
        )
    }
}

@Composable
private fun LogEntryCard(
    entry: LogEntry,
    colors: com.itos.coui.layout.CouiPageColors,
) {
    val levelColor = when (entry.level) {
        LogLevel.ERROR -> Color(0xFFD92D20)
        LogLevel.WARN -> Color(0xFFB54708)
        LogLevel.INFO -> colors.accent
        LogLevel.DEBUG -> colors.summary
    }
    Group(colors = colors) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(entry.level.shortLabel, levelColor)
                BasicText(
                    text = entry.tag,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.title,
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = "${entry.timestamp} · ${entry.source}",
                    style = TextStyle(fontSize = 10.sp, color = colors.summary),
                )
            }
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = entry.message,
                style = TextStyle(
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.summary,
                ),
            )
        }
    }
}

private fun contentPaddingWithBottom(padding: PaddingValues): PaddingValues =
    PaddingValues(
        top = padding.calculateTopPadding(),
        bottom = padding.calculateBottomPadding() + 16.dp,
    )

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

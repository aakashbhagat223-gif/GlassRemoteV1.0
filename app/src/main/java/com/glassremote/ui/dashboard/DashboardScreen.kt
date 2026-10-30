package com.glassremote.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.glassremote.data.adb.AdbManager
import com.glassremote.data.adb.AdbState
import com.glassremote.data.adb.NativeActions
import com.glassremote.data.adb.NowPlaying
import com.glassremote.data.adb.NowPlayingRepository
import com.glassremote.data.ir.IrBlaster
import com.glassremote.data.macro.MacroIds
import com.glassremote.data.macro.MacroRunner
import com.glassremote.data.macro.MacroStore
import com.glassremote.data.network.ImeBridge
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import com.glassremote.service.NowPlayingService
import com.glassremote.ui.theme.LocalHaptics
import com.glassremote.ui.theme.getThemeColors
import com.glassremote.ui.theme.glass
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class NetInfo(
    val wifi: Boolean = false,
    val phoneIp: String? = null,
    val pingMs: Int? = null,
    val ssid: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: TvRepository,
    store: PairedDeviceStore,
    ir: IrBlaster,
    private val macros: MacroStore,
    private val runner: MacroRunner,
    nowPlayingRepo: NowPlayingRepository,
    private val adb: AdbManager,
    private val actions: NativeActions,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    val status: StateFlow<RemoteStatus> = repo.status
    val irSupported: Boolean = ir.isSupported
    val nowPlaying: StateFlow<NowPlaying?> = nowPlayingRepo.state
    val adbState: StateFlow<AdbState> = adb.state

    val device: StateFlow<TvDevice?> = store.lastDevice
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentApp: StateFlow<String> = ImeBridge.state
        .map { it.currentApp }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val net: StateFlow<NetInfo> = device
        .flatMapLatest { d ->
            flow {
                while (true) {
                    emit(measure(d))
                    delay(5_000)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetInfo())

    private val _backlight = MutableStateFlow<Int?>(null)
    val backlight: StateFlow<Int?> = _backlight.asStateFlow()

    private val deltas = Channel<Int>(Channel.UNLIMITED)
    private val presses = Channel<Int>(Channel.UNLIMITED)
    private var baseBacklight: Int? = null
    private var nativeBacklight = true
    private var sessionOpen = false

    @Volatile
    private var opening = false

    init {
        viewModelScope.launch {
            for (first in deltas) {
                var sum = first
                while (true) {
                    val n = deltas.tryReceive().getOrNull() ?: break
                    sum += n
                }
                runCatching {
                    val current = baseBacklight ?: actions.readBacklight() ?: 50
                    val next = (current + sum).coerceIn(0, 100)
                    actions.writeBacklight(next)
                    baseBacklight = next
                    _backlight.value = next
                }
            }
        }
        viewModelScope.launch {
            var closeJob: Job? = null
            for (direction in presses) {
                closeJob?.cancel()
                if (!sessionOpen) {
                    opening = true
                    runCatching { runner.run(macros.get(MacroIds.BACKLIGHT_OPEN), label = "Backlight") }
                    sessionOpen = true
                    opening = false
                }
                val code = if (direction > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                runCatching { repo.sendKey(code) }
                delay(90)
                closeJob = launch {
                    delay(3_000)
                    runCatching { runner.run(macros.get(MacroIds.BACKLIGHT_CLOSE), label = "Backlight") }
                    sessionOpen = false
                }
            }
        }
    }

    fun refreshBacklight() {
        viewModelScope.launch {
            nativeBacklight = actions.nativeBacklightEnabled()
            if (adb.state.value.connected && nativeBacklight) {
                runCatching { actions.readBacklight() }.getOrNull()?.let {
                    baseBacklight = it
                    _backlight.value = it
                }
            }
        }
    }

    fun stepBacklight(direction: Int, magnitude: Float) {
        if (adb.state.value.connected && nativeBacklight) {
            val delta = direction * (1 + (magnitude * 5f).toInt())
            _backlight.value = ((_backlight.value ?: 50) + delta).coerceIn(0, 100)
            deltas.trySend(delta)
        } else {
            if (opening) return
            presses.trySend(direction)
        }
    }

    fun releaseBacklight() {
        while (presses.tryReceive().isSuccess) Unit
    }

    private fun measure(d: TvDevice?): NetInfo {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        val wifi = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ip = cm.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        var ping: Int? = null
        if (d != null) {
            val t0 = System.nanoTime()
            ping = try {
                Socket().use { it.connect(InetSocketAddress(d.host, 6466), 1_500) }
                ((System.nanoTime() - t0) / 1_000_000).toInt()
            } catch (_: Exception) {
                null
            }
        }
        return NetInfo(wifi, ip, ping, if (wifi) readSsid() else null)
    }

    @Suppress("DEPRECATION")
    private fun readSsid(): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val raw = wm.connectionInfo?.ssid ?: return null
        val name = raw.trim('"')
        return if (name.isBlank() || name == "<unknown ssid>") null else name
    }
}

private fun appLabel(pkg: String): String = when {
    pkg.isBlank() -> ""
    pkg.contains("tvmusic") -> "YouTube Music"
    pkg.contains("youtube.tv") -> "YouTube"
    pkg == "com.stremio.one" -> "Stremio"
    pkg.startsWith("com.netflix") -> "Netflix"
    pkg.contains("amazonvideo") -> "Prime Video"
    pkg.contains("spotify") -> "Spotify"
    pkg.contains("launcher") -> "Home screen"
    pkg.contains("settings") -> "Settings"
    else -> pkg.substringAfterLast('.')
}

@Composable
fun DashboardScreen(vm: DashboardViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val net by vm.net.collectAsStateWithLifecycle()
    val currentApp by vm.currentApp.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val adbState by vm.adbState.collectAsStateWithLifecycle()
    val backlight by vm.backlight.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val colors = getThemeColors()

    LaunchedEffect(adbState.connected) { vm.refreshBacklight() }

    var hasLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasLocation = it
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Dashboard", color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        NowPlayingCard(appLabel(currentApp), nowPlaying, adbState.connected)

        TvInfoCard(
            name = device?.name ?: "",
            host = device?.host ?: "",
            ping = net.pingMs,
            phase = status.phase,
            adb = adbState,
            phoneIp = net.phoneIp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile(
                "Wi-Fi",
                when {
                    net.ssid != null -> net.ssid!!
                    !hasLocation -> "Tap to show name"
                    net.wifi -> "Connected"
                    else -> "Off"
                },
                Modifier.weight(1f),
                onClick = if (!hasLocation) ({ askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) else null,
            )
            StatTile(
                "IR blaster",
                if (vm.irSupported) "Available" else "Not supported",
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile(
                "Volume",
                status.volume?.let { v -> if (status.muted == true) "Muted" else "$v / ${status.volumeMax ?: "?"}" } ?: "—",
                Modifier.weight(1f),
            )
            StatTile(
                "Power",
                when (status.poweredOn) { true -> "On"; false -> "Standby"; null -> "—" },
                Modifier.weight(1f),
            )
        }

        SpringBacklightCard(
            backlight = backlight,
            onStep = { dir, mag -> vm.stepBacklight(dir, mag) },
            onRelease = { vm.releaseBacklight() },
        )
    }
}

@Composable
private fun TvInfoCard(
    name: String,
    host: String,
    ping: Int?,
    phase: LinkPhase,
    adb: AdbState,
    phoneIp: String?,
) {
    val colors = getThemeColors()
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Xiaomi TV · " + name.ifBlank { "not paired" },
            color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Line("TV IP", host.ifBlank { "—" })
        Line("Latency", ping?.let { "$it ms" } ?: "—")
        Line(
            "Remote link",
            when (phase) {
                LinkPhase.Ready -> "Live"
                LinkPhase.Connecting -> "Connecting…"
                LinkPhase.Disconnected -> "Down"
                LinkPhase.Idle -> "Idle"
            },
        )
        Line("Pairing", if (phase == LinkPhase.Ready) "Valid · certificate accepted" else "Not verified")
        Line("ADB", if (adb.connected) "Connected" else "Off")
        Line("This phone", phoneIp ?: "—")
    }
}

@Composable
private fun Line(label: String, value: String) {
    val colors = getThemeColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp)
        Text(
            value,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp),
        )
    }
}

@Composable
private fun SpringBacklightCard(
    backlight: Int?,
    onStep: (Int, Float) -> Unit,
    onRelease: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val colors = getThemeColors()
    val deflection = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    val release by rememberUpdatedState(onRelease)
    val thumb = 44.dp
    val thumbPx = with(LocalDensity.current) { thumb.toPx() }

    LaunchedEffect(dragging) {
        var atLimit = false
        while (dragging) {
            val d = deflection.value
            val mag = abs(d)
            if (mag > 0.08f) {
                if (mag >= 0.97f) {
                    if (!atLimit) {
                        haptics?.heavyClick()
                        atLimit = true
                    } else {
                        haptics?.rumble()
                    }
                } else {
                    atLimit = false
                    haptics?.tick()
                }
                step(if (d > 0f) 1 else -1, mag)
                delay((620f - 510f * mag).toLong())
            } else {
                atLimit = false
                delay(40)
            }
        }
    }

    fun snapBack() {
        dragging = false
        release()
        scope.launch {
            deflection.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            )
        }
    }

    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (backlight != null) "TV backlight · $backlight" else "TV backlight",
            color = colors.textSecondary, fontSize = 12.sp,
        )
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("−", color = colors.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.15f))
                    .onSizeChanged { trackWidthPx = it.width.toFloat() }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragging = true
                                scope.launch { deflection.stop() }
                            },
                            onDragEnd = { snapBack() },
                            onDragCancel = { snapBack() },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val half = ((trackWidthPx - thumbPx) / 2f).coerceAtLeast(1f)
                                scope.launch {
                                    deflection.snapTo((deflection.value + dragAmount / half).coerceIn(-1f, 1f))
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(2.dp).height(22.dp).background(colors.accent.copy(alpha = 0.5f)))
                Box(
                    Modifier
                        .offset { IntOffset((deflection.value * ((trackWidthPx - thumbPx) / 2f)).roundToInt(), 0) }
                        .size(thumb)
                        .background(colors.textPrimary, CircleShape)
                )
            }
            Text("+", color = colors.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        }
        Text(
            "Drag right for brighter, left for dimmer; further is faster. It springs back on release. " +
                "With ADB it sets the TV's backlight value directly.",
            color = colors.textSecondary, fontSize = 11.sp,
        )
    }
}

@Composable
private fun NowPlayingCard(app: String, np: NowPlaying?, adbConnected: Boolean) {
    val artwork by NowPlayingService.artwork.collectAsStateWithLifecycle()
    val colors = getThemeColors()
    val header = when {
        np == null -> if (app.isBlank()) "NOW PLAYING" else "ON THE TV"
        np.state == 3 -> "NOW PLAYING"
        else -> "PAUSED"
    }
    val title = when {
        np != null -> np.title.ifBlank { appLabel(np.pkg) }
        app.isNotBlank() -> app
        else -> "Nothing reported"
    }
    val sub = when {
        np != null -> appLabel(np.pkg)
        adbConnected -> "Nothing is playing"
        else -> "Connect ADB in the Link tab for titles"
    }
    Box(Modifier.fillMaxWidth().height(170.dp).glass(RoundedCornerShape(28.dp))) {
        Box(
            Modifier.align(Alignment.CenterStart).padding(16.dp).size(110.dp)
                .clip(RoundedCornerShape(20.dp))
        ) {
            if (artwork != null) {
                // Soft glass-like haze / frosted blur background layer behind the thumbnail
                Box(
                    Modifier.fillMaxSize()
                        .blur(16.dp, BlurredEdgeTreatment.Unbounded)
                        .background(colors.accent.copy(alpha = 0.35f))
                ) {
                    Image(
                        bitmap = artwork!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Crisp foreground thumbnail
                Image(
                    bitmap = artwork!!.asImageBitmap(),
                    contentDescription = "Now playing artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    Modifier.fillMaxSize()
                        .blur(18.dp, BlurredEdgeTreatment.Unbounded)
                        .background(
                            Brush.linearGradient(listOf(colors.accent, colors.textSecondary)),
                            RoundedCornerShape(24.dp),
                        )
                )
            }
        }
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 148.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(header, color = colors.textSecondary, fontSize = 11.sp)
            Text(
                title,
                color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
            )
            Text(sub, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier,
    dot: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = getThemeColors()
    Column(
        modifier
            .height(92.dp)
            .glass(RoundedCornerShape(24.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(value, color = colors.textPrimary, fontSize.let { 17.sp }, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

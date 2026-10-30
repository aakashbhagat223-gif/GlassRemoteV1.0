package com.glassremote.ui.link

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.glassremote.data.adb.AdbManager
import com.glassremote.data.adb.NativeActions
import com.glassremote.data.adb.TvInput
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.domain.model.TvDevice
import com.glassremote.service.NowPlayingService
import com.glassremote.ui.theme.glass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinkViewModel @Inject constructor(
    val adb: AdbManager,
    private val actions: NativeActions,
    store: PairedDeviceStore,
) : ViewModel() {

    val device: StateFlow<TvDevice?> = store.lastDevice
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _inputs = MutableStateFlow<List<TvInput>>(emptyList())
    val inputs: StateFlow<List<TvInput>> = _inputs.asStateFlow()

    private val _hdmi2 = MutableStateFlow("")
    val hdmi2: StateFlow<String> = _hdmi2.asStateFlow()

    private val _nativeBacklight = MutableStateFlow(true)
    val nativeBacklight: StateFlow<Boolean> = _nativeBacklight.asStateFlow()

    private val _keysViaAdb = MutableStateFlow(false)
    val keysViaAdb: StateFlow<Boolean> = _keysViaAdb.asStateFlow()

    init {
        viewModelScope.launch {
            _hdmi2.value = actions.savedHdmi2()
            _nativeBacklight.value = actions.nativeBacklightEnabled()
            _keysViaAdb.value = actions.keysViaAdb()
        }
    }

    fun setNativeBacklight(on: Boolean) {
        viewModelScope.launch {
            actions.setNativeBacklight(on)
            _nativeBacklight.value = on
        }
    }

    fun setKeysViaAdb(on: Boolean) {
        viewModelScope.launch {
            actions.setKeysViaAdb(on)
            _keysViaAdb.value = on
        }
    }

    fun connectAdb(host: String, port: Int) {
        viewModelScope.launch {
            adb.connect(host, port)
            _output.value = adb.state.value.message
        }
    }

    private fun run(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            _output.value = "$label…"
            _output.value = runCatching { block() }
                .fold({ it.ifBlank { "Done" } }, { "Error: ${it.message}" })
        }
    }

    fun testAdb() = run("Testing") { adb.shell("getprop ro.product.model") }

    fun launchStremio() = run("Launching Stremio") { actions.launchPackage("com.stremio.one") }

    fun scanInputs() {
        viewModelScope.launch {
            _output.value = "Scanning inputs…"
            runCatching { actions.hdmiInputs() }
                .onSuccess {
                    _inputs.value = it
                    _output.value = if (it.isEmpty()) "No HDMI inputs found" else "Found ${it.size} input(s)"
                }
                .onFailure { _output.value = "Error: ${it.message}" }
        }
    }

    fun switchInput(id: String) = run("Switching") { actions.switchInput(id) }

    fun useAsHdmi2(id: String) {
        viewModelScope.launch {
            actions.saveHdmi2(id)
            _hdmi2.value = id
            _output.value = "Saved as HDMI 2"
        }
    }

    fun probeBacklight() = run("Searching settings") {
        actions.backlightSettings().joinToString("\n").ifBlank { "No matching settings found" }
    }
}

@Composable
fun LinkScreen(vm: LinkViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val adbState by vm.adb.state.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val hdmi2 by vm.hdmi2.collectAsStateWithLifecycle()
    val nativeBacklight by vm.nativeBacklight.collectAsStateWithLifecycle()
    val keysViaAdb by vm.keysViaAdb.collectAsStateWithLifecycle()

    var host by remember(device) { mutableStateOf(device?.host ?: "") }
    var port by remember { mutableStateOf("5555") }
    var lockOn by remember { mutableStateOf(NowPlayingService.isEnabled(ctx)) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            NowPlayingService.setEnabled(ctx, true)
            lockOn = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Link", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Section("ADB (primary control channel)") {
            Text(adbState.message, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, singleLine = true,
                    label = { Text("TV IP") }, modifier = Modifier.weight(2f), colors = fieldColors(),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it }, singleLine = true,
                    label = { Text("Port") }, modifier = Modifier.weight(1f), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row {
                LinkButton("Connect") { vm.connectAdb(host.trim(), port.toIntOrNull() ?: 5555) }
                LinkButton("Test") { vm.testAdb() }
                LinkButton("Disconnect") { vm.adb.disconnect() }
            }
            InfoToggle(
                "Turn on USB debugging in the TV's Developer options first. Accept the prompt on the TV " +
                    "and tick Always allow. Once connected it reconnects by itself (also if the TV's IP " +
                    "changes). Launching apps, switching to HDMI 2, the backlight slider and typing letters " +
                    "use ADB first. If ADB is off, your macros and app links take over."
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = keysViaAdb, onCheckedChange = { vm.setKeysViaAdb(it) })
                Text("Send every key press over ADB", color = Color.White, fontSize = 13.sp)
            }
            InfoToggle(
                "Off (default): navigation keys use the fast Wi-Fi remote link, and ADB takes over " +
                    "automatically if that link drops. On: every key goes over ADB, which needs no remote " +
                    "link but adds about 0.3 s per press and doesn't repeat well when held."
            )
        }

        Section("Native actions") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = nativeBacklight, onCheckedChange = { vm.setNativeBacklight(it) })
                Text("Backlight slider sets the TV value directly", color = Color.White, fontSize = 13.sp)
            }
            Row {
                LinkButton("Launch Stremio") { vm.launchStremio() }
                LinkButton("Scan HDMI inputs") { vm.scanInputs() }
                LinkButton("Find backlight") { vm.probeBacklight() }
            }
            Text(
                if (hdmi2.isBlank()) "HDMI 2 input: not set (the default is used)" else "HDMI 2 input: $hdmi2",
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
            )
            inputs.forEach { input ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(input.id, color = Color.White, fontSize = 12.sp)
                    Row {
                        LinkButton("Switch") { vm.switchInput(input.id) }
                        LinkButton("Use as HDMI 2") { vm.useAsHdmi2(input.id) }
                    }
                }
            }
            if (output.isNotBlank()) {
                Text(output, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }

        Section("Lock screen") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(
                    checked = lockOn,
                    onCheckedChange = { on ->
                        if (!on) {
                            NowPlayingService.setEnabled(ctx, false)
                            lockOn = false
                        } else if (Build.VERSION.SDK_INT >= 33 && !NowPlayingService.notificationsAllowed(ctx)) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            NowPlayingService.setEnabled(ctx, true)
                            lockOn = true
                        }
                    },
                )
                Text("Show Now Playing on the lock screen", color = Color.White, fontSize = 13.sp)
            }
            InfoToggle(
                "Shows the TV's current title as a media card on your phone's lock screen, with Previous, " +
                    "Play/Pause and Next sent to the TV over ADB. It needs ADB connected for titles and " +
                    "the notification permission. The artwork is generated, because the TV doesn't share " +
                    "real artwork. If the card doesn't appear, check that your phone's lock screen " +
                    "settings allow media controls and notifications."
            )
        }
    }
}

@Composable
private fun InfoToggle(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clickable { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Info, contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (open) "Hide info" else "Info", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
        AnimatedVisibility(visible = open) {
            Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
    ) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedBorderColor = Color.White.copy(alpha = 0.6f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
    focusedLabelColor = Color.White.copy(alpha = 0.7f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
)

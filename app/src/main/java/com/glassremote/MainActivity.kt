package com.glassremote

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassremote.data.macro.MacroIds
import com.glassremote.service.NowPlayingService
import com.glassremote.ui.dashboard.DashboardScreen
import com.glassremote.ui.link.LinkScreen
import com.glassremote.ui.macros.MacrosScreen
import com.glassremote.ui.macros.MacrosViewModel
import com.glassremote.ui.pairing.PairingScreen
import com.glassremote.ui.remote.RemoteScreen
import com.glassremote.ui.remote.RemoteViewModel
import com.glassremote.ui.settings.SettingsScreen
import com.glassremote.ui.settings.SettingsViewModel
import com.glassremote.ui.theme.GlassBackground
import com.glassremote.ui.theme.Haptics
import com.glassremote.ui.theme.LocalGlassStyle
import com.glassremote.ui.theme.LocalHaptics
import com.glassremote.ui.theme.ThemePreferences
import com.glassremote.ui.theme.glass
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var haptics: Haptics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        ThemePreferences.init(this)
        NowPlayingService.startIfEnabled(this)
        setContent {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val style by settingsVm.style.collectAsStateWithLifecycle()
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalGlassStyle provides style,
                LocalHaptics provides haptics,
                LocalDensity provides Density(base.density, base.fontScale * style.fontScale),
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    ProvideTextStyle(TextStyle(fontWeight = FontWeight(style.fontWeight))) {
                        AppRoot(settingsVm)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot(settingsVm: SettingsViewModel) {
    val remoteVm: RemoteViewModel = hiltViewModel()
    val macrosVm: MacrosViewModel = hiltViewModel()
    val saved by remoteVm.saved.collectAsStateWithLifecycle()
    val recording by macrosVm.recording.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(recording) { if (recording != null) tab = 0 }

    when (saved) {
        null -> GlassBackground {}
        false -> PairingScreen()
        true -> GlassBackground {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
                    when (tab) {
                        0 -> RemoteScreen(remoteVm)
                        1 -> DashboardScreen()
                        2 -> LinkScreen()
                        3 -> MacrosScreen(macrosVm)
                        else -> SettingsScreen(settingsVm)
                    }
                }
                recording?.let { id -> RecordingBar(MacroIds.title(id)) { macrosVm.stopRecording() } }
                BottomNav(tab) { tab = it }
            }
        }
    }
}

@Composable
private fun RecordingBar(title: String, onStop: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(48.dp)
            .glass(RoundedCornerShape(24.dp), strong = true)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(Color(0xFFF87171), CircleShape))
            Text("Recording: $title", color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
        TextButton(
            onClick = onStop,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) { Text("Stop", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun BottomNav(tab: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        "Remote" to Icons.Rounded.SettingsRemote,
        "Dashboard" to Icons.Rounded.Dashboard,
        "Link" to Icons.Rounded.Link,
        "Macros" to Icons.Rounded.Tune,
        "Settings" to Icons.Rounded.Settings,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .height(64.dp)
            .glass(RoundedCornerShape(32.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, (label, icon) ->
            val tint = if (i == tab) Color.White else Color.White.copy(alpha = 0.5f)
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
                Text(label, color = tint, fontSize = 10.sp)
            }
        }
    }
}

package com.glassremote.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.data.repository.SettingsRepository
import com.glassremote.domain.model.BackgroundStyle
import com.glassremote.domain.model.GlassStyle
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import com.glassremote.ui.theme.getThemeColors
import com.glassremote.ui.theme.glass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    store: PairedDeviceStore,
    private val repo: TvRepository,
) : ViewModel() {

    val style: StateFlow<GlassStyle> = settings.style
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlassStyle())

    val device: StateFlow<TvDevice?> = store.lastDevice
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setFontScale(v: Float) { viewModelScope.launch { settings.setFontScale(v) } }
    fun setWeight(v: Int) { viewModelScope.launch { settings.setWeight(v) } }
    fun setBackground(b: BackgroundStyle) { viewModelScope.launch { settings.setBackground(b) } }
    fun setImage(uri: String) { viewModelScope.launch { settings.setImage(uri) } }
    fun setRadius(v: Float) { viewModelScope.launch { settings.setRadius(v) } }
    fun resetPairing() { viewModelScope.launch { repo.resetPairing() } }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val style by vm.style.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val colors = getThemeColors()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.setImage(uri.toString())
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
        Text("Settings", color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // Theme and Aesthetic Selection Card
        ThemeSettingsCard()

        // Live preview: reflects text size, weight and corner radius immediately.
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("PREVIEW", color = colors.textSecondary, fontSize = 11.sp)
            Text("Living Room TV", color = colors.textPrimary, fontSize = 20.sp)
            Text("Connected · Vol 12", color = colors.textSecondary, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(88.dp, 46.dp).glass(RoundedCornerShape(23.dp)), contentAlignment = Alignment.Center) {
                    Text("Back", color = colors.textPrimary, fontSize = 14.sp)
                }
                Box(Modifier.size(64.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                    Text("OK", color = colors.textPrimary, fontSize = 14.sp)
                }
            }
        }

        Section("Text size") {
            LabeledSlider(style.fontScale, 0.85f..1.3f, { "${(it * 100).roundToInt()}%" }) { vm.setFontScale(it) }
        }

        Section("Text weight") {
            ChoiceRow(
                listOf("Light" to 300, "Regular" to 400, "Medium" to 500, "Bold" to 700),
                style.fontWeight,
            ) { vm.setWeight(it) }
        }

        Section("Background") {
            ChoiceRow(
                listOf(
                    "Glass" to BackgroundStyle.Glass,
                    "Solid" to BackgroundStyle.Solid,
                    "OLED" to BackgroundStyle.Oled,
                    "Image" to BackgroundStyle.Image,
                ),
                style.background,
            ) { vm.setBackground(it) }
            if (style.background == BackgroundStyle.Image) {
                Button(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent.copy(alpha = 0.2f), contentColor = colors.textPrimary,
                    ),
                ) { Text("Choose image") }
            }
        }

        Section("Corner radius") {
            LabeledSlider(style.radius, 0.2f..1.4f, { "${(it * 100).roundToInt()}%" }) { vm.setRadius(it) }
        }

        Section("Connection") {
            Text(device?.name ?: "No TV saved", color = colors.textPrimary, fontSize = 16.sp)
            Text(device?.host ?: "—", color = colors.textSecondary, fontSize = 13.sp)
            Button(
                onClick = { vm.resetPairing() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0x33FF6B6B), contentColor = Color(0xFFFF8A8A),
                ),
            ) { Text("Reset pairing & certificate") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = getThemeColors()
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = colors.textSecondary, fontSize = 12.sp)
        content()
    }
}

@Composable
private fun LabeledSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    onDone: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    val colors = getThemeColors()
    Text(label(v), color = colors.textPrimary, fontSize = 15.sp)
    Slider(
        value = v,
        onValueChange = { v = it },
        onValueChangeFinished = { onDone(v) },
        valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = colors.textPrimary,
            activeTrackColor = colors.accent,
            inactiveTrackColor = colors.border,
        ),
    )
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    val colors = getThemeColors()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label, fontSize = 12.sp, maxLines = 1) },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.accent.copy(alpha = 0.3f),
                    selectedLabelColor = colors.textPrimary,
                    labelColor = colors.textSecondary,
                ),
            )
        }
    }
}

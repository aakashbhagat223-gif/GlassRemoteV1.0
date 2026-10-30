package com.glassremote.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glassremote.MainActivity
import com.glassremote.R
import com.glassremote.data.adb.AdbManager
import com.glassremote.data.adb.NativeActions
import com.glassremote.data.adb.NowPlaying
import com.glassremote.data.adb.NowPlayingRepository
import com.glassremote.data.network.ImeBridge
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.repository.TvRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject

@AndroidEntryPoint
class NowPlayingService : Service() {

    @Inject lateinit var nowPlayingRepo: NowPlayingRepository
    @Inject lateinit var adb: AdbManager
    @Inject lateinit var actions: NativeActions
    @Inject lateinit var repo: TvRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private var started = false
    private var lastKey = ""
    private var artCache: Pair<String, Bitmap>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "TV now playing", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        session = MediaSession(this, "TvNowPlaying").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                override fun onPause() { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                override fun onSkipToNext() { media(KeyEvent.KEYCODE_MEDIA_NEXT) }
                override fun onSkipToPrevious() { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ACTION_TOGGLE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ACTION_NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        if (!started) {
            started = true
            val first = buildNotification("Now playing", "Starting…", false, artFor("TV"))
            ServiceCompat.startForeground(
                this, NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            observe()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private fun observe() {
        scope.launch {
            combine(
                nowPlayingRepo.state,
                adb.state,
                ImeBridge.state.map { it.currentApp }.distinctUntilChanged(),
            ) { np, a, app -> Triple(np, a.connected, app) }
                .collect { (np, adbOn, app) -> processMediaState(np, adbOn, app) }
        }
    }

    private fun processMediaState(np: NowPlaying?, adbOn: Boolean, app: String) {
        val appName = appLabel(np?.pkg ?: app)
        val title = when {
            np != null -> np.title.ifBlank { appName.ifBlank { "TV" } }
            appName.isNotBlank() -> appName
            else -> "Nothing playing"
        }
        val subtitle = when {
            np != null -> appName
            adbOn -> "Nothing is playing on the TV"
            else -> "Connect ADB in the app for titles"
        }
        val playing = np?.state == 3
        val key = "$title|$subtitle|$playing|${np?.artUrl}"
        if (key == lastKey) return
        lastKey = key

        scope.launch {
            val art = if (!np?.artUrl.isNullOrBlank()) {
                downloadArtwork(np!!.artUrl) ?: fetchCatalogArtwork(title) ?: artFor(appName.ifBlank { title })
            } else {
                fetchCatalogArtwork(title) ?: artFor(appName.ifBlank { title })
            }

            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                    .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                    .build()
            )
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f,
                    )
                    .build()
            )
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(title, subtitle, playing, art))
        }
    }

    private fun buildNotification(title: String, subtitle: String, playing: Boolean, art: Bitmap): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setLargeIcon(art)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
            .addAction(
                action(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                    ACTION_TOGGLE,
                )
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun action(iconRes: Int, label: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, NowPlayingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, iconRes), label, pi).build()
    }

    private fun media(code: Int) {
        scope.launch {
            runCatching {
                if (adb.state.value.connected) actions.keyEvent(code) else repo.sendKey(code, KeyDirection.Short)
            }
        }
    }

    private suspend fun fetchCatalogArtwork(mediaTitle: String): Bitmap? = withContext(Dispatchers.IO) {
        val cleanTitle = mediaTitle.split(" - ", " | ", " (", " [").firstOrNull()?.trim() ?: return@withContext null
        if (cleanTitle.isBlank() || cleanTitle == "TV" || cleanTitle == "Nothing playing") return@withContext null
        
        try {
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val apiEndpoint = "https://omdbapi.com"
            val responseText = URL(apiEndpoint).readText()
            val json = JSONObject(responseText)
            if (json.optString("Response") == "True") {
                val posterUrl = json.optString("Poster")
                if (!posterUrl.isNullOrBlank() && posterUrl != "N/A") {
                    return@withContext downloadArtwork(posterUrl)
                }
            }
        } catch (e: Exception) {
            // Fallback gracefully if rate-limits or network interruptions occur
        }
        null
    }

    private suspend fun downloadArtwork(urlString: String): Bitmap? = withContext(Dispatchers.IO) {
        artCache?.let { if (it.first == urlString) return@withContext it.second }
        try {
val url = URL(urlString)
val connection = url.openConnection().apply {
connectTimeout = 3000
readTimeout = 3000
}
val bitmap = BitmapFactory.decodeStream(connection.getInputStream())
if (bitmap != null) {
artCache = urlString to bitmap
}
bitmap
} catch (e: Exception) {
null
}
}
private fun artFor(label: String): Bitmap {
artCache?.let { if (it.first == label) return it.second }
val size = 512
val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
val canvas = Canvas(bmp)
val paint = Paint(Paint.ANTI_ALIAS_FLAG)
paint.shader = LinearGradient(
0f, 0f, size.toFloat(), size.toFloat(),
0xFF5B6CFF.toInt(), 0xFFFF5FA2.toInt(), Shader.TileMode.CLAMP,
)
canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
paint.shader = null
paint.color = Color.WHITE
paint.textSize = 240f
paint.textAlign = Paint.Align.CENTER
paint.typeface = Typeface.DEFAULT_BOLD
val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "TV"
canvas.drawText(letter, size / 2f, size / 2f + 85f, paint)
artCache = label to bmp
return bmp
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
companion object {
const val CHANNEL = "tv_now_playing"
const val NOTIF_ID = 4107
const val ACTION_PREV = "com.glassremote.action.PREV"
const val ACTION_TOGGLE = "com.glassremote.action.TOGGLE"
const val ACTION_NEXT = "com.glassremote.action.NEXT"
private const val PREFS = "lockscreen"
fun isEnabled(ctx: Context): Boolean =
ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
fun notificationsAllowed(ctx: Context): Boolean =
Build.VERSION.SDK_INT < 33 ||
ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
PackageManager.PERMISSION_GRANTED
fun setEnabled(ctx: Context, on: Boolean) {
ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
if (on) start(ctx) else stop(ctx)
}
fun start(ctx: Context) {
ContextCompat.startForegroundService(ctx, Intent(ctx, NowPlayingService::class.java))
}
fun stop(ctx: Context) {
ctx.stopService(Intent(ctx, NowPlayingService::class.java))
}
fun startIfEnabled(ctx: Context) {
if (isEnabled(ctx) && notificationsAllowed(ctx)) start(ctx)
}
}
}

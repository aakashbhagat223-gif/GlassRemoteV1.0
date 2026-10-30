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
import android.util.LruCache
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Shows what the TV is playing as a media card on the phone's lock screen (and the notification shade).
 * Fetches real thumbnails/posters from IMDb, YouTube, and iTunes based on the active app and title.
 */
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
    private var lastTrackKey = ""
    private var artJob: Job? = null

    private var fallbackCache: Pair<String, Bitmap>? = null
    private val networkArtCache = LruCache<String, Bitmap>(20)

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
        artJob?.cancel()
        scope.cancel()
        session.release()
        _artwork.value = null
        super.onDestroy()
    }

    private fun observe() {
        scope.launch {
            combine(
                nowPlayingRepo.state,
                adb.state,
                ImeBridge.state.map { it.currentApp }.distinctUntilChanged(),
            ) { np, a, app -> Triple(np, a.connected, app) }
                .collect { (np, adbOn, app) -> render(np, adbOn, app) }
        }
    }

    private fun render(np: NowPlaying?, adbOn: Boolean, app: String) {
        val rawPkg = np?.pkg ?: app
        val appName = appLabel(rawPkg)
        val rawTitle = np?.title?.trim().orEmpty()

        val title = when {
            np != null -> rawTitle.ifBlank { appName.ifBlank { "TV" } }
            appName.isNotBlank() -> appName
            else -> "Nothing playing"
        }
        val subtitle = when {
            np != null -> appName
            adbOn -> "Nothing is playing on the TV"
            else -> "Connect ADB in the app for titles"
        }
        val playing = np?.state == 3
        val key = "$title|$subtitle|$playing"
        if (key == lastKey) return
        lastKey = key

        val trackKey = "$rawPkg|$rawTitle"
        val cachedArt = if (rawTitle.isNotBlank()) networkArtCache.get(trackKey) else null
        val initialArt = cachedArt ?: artFor(appName.ifBlank { title })

        _artwork.value = cachedArt
        publishMediaState(title, subtitle, playing, initialArt)

        // Only trigger a network lookup when we have a real media title that isn't cached yet
        if (rawTitle.isNotBlank() && rawTitle != appName && cachedArt == null) {
            if (trackKey != lastTrackKey) {
                lastTrackKey = trackKey
                artJob?.cancel()
                artJob = scope.launch {
                    val resolved = resolveArtwork(rawPkg, rawTitle)
                    if (resolved != null) {
                        networkArtCache.put(trackKey, resolved)
                        if (lastTrackKey == trackKey) {
                            _artwork.value = resolved
                            publishMediaState(title, subtitle, np?.state == 3, resolved)
                        }
                    }
                }
            }
        } else if (rawTitle.isBlank()) {
            lastTrackKey = ""
            artJob?.cancel()
            _artwork.value = null
        }
    }

    private fun publishMediaState(title: String, subtitle: String, playing: Boolean, art: Bitmap) {
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

    /**
     * Routes artwork lookup based on the active TV app, with cross-source fallbacks.
     */
    private suspend fun resolveArtwork(pkg: String, rawTitle: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val imageUrl = when {
                pkg.contains("tvmusic") || pkg.contains("spotify") ->
                    findItunesArtworkUrl(rawTitle)
                        ?: findYouTubeThumbnailUrl(rawTitle)

                pkg.contains("youtube") ->
                    findYouTubeThumbnailUrl(rawTitle)
                        ?: findImdbPosterUrl(rawTitle)

                else ->
                    // Stremio, Netflix, Prime Video, Plex, Kodi, etc. -> IMDb Catalog first
                    findImdbPosterUrl(rawTitle)
                        ?: findYouTubeThumbnailUrl(rawTitle)
            }
            imageUrl?.let { downloadBitmap(it) }
        }.getOrNull()
    }

    /**
     * Queries the public IMDb suggestion catalog (no API key required) for movies and series.
     */
    private fun findImdbPosterUrl(rawTitle: String): String? {
        val cleaned = cleanTitleForImdb(rawTitle)
        val slug = cleaned.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(50)
        if (slug.isBlank()) return null

        val firstChar = slug.first()
        val url = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$slug.json"
        val body = httpGetText(url) ?: return null

        val d = JSONObject(body).optJSONArray("d") ?: return null
        for (i in 0 until d.length()) {
            val item = d.optJSONObject(i) ?: continue
            val id = item.optString("id")
            // Only match titles ("tt..."), skipping person/actor entries ("nm...")
            if (!id.startsWith("tt")) continue
            val imgUrl = item.optJSONObject("i")?.optString("imageUrl").orEmpty()
            if (imgUrl.isNotBlank()) {
                // Request a resized 600px image from Amazon's CDN instead of the raw multi-MB poster
                return imgUrl.replace(Regex("_V1_.*\\.jpg$"), "_V1_QL80_UX600_.jpg")
            }
        }
        return null
    }

    /**
     * Strips season/episode tags, resolutions, and app suffixes so IMDb matches the parent title.
     */
    private fun cleanTitleForImdb(title: String): String {
        return title
            .replace(Regex("(?i)\\bS\\d{1,2}\\s*E\\d{1,3}\\b.*"), "")
            .replace(Regex("(?i)\\bSeason\\s*\\d+.*"), "")
            .replace(Regex("(?i)\\bEpisode\\s*\\d+.*"), "")
            .replace(Regex("(?i)\\bEp\\.?\\s*\\d+.*"), "")
            .replace(Regex("\\[.*?\\]|\\(.*?\\)"), " ")
            .replace(Regex("(?i)\\b(1080p|2160p|720p|4k|hdr|webrip|bluray)\\b.*"), "")
            .substringBefore(" - ")
            .substringBefore(" | ")
            .trim()
            .ifBlank { title }
    }

    /**
     * Extracts the top videoId from YouTube search and returns its high-res thumbnail URL.
     */
    private fun findYouTubeThumbnailUrl(query: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = httpGetText("https://www.youtube.com/results?search_query=$encoded") ?: return null
        val match = Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\"").find(html) ?: return null
        val videoId = match.groupValues[1]
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    /**
     * Queries Apple's public iTunes Search API for music tracks (Spotify / YouTube Music).
     */
    private fun findItunesArtworkUrl(query: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = httpGetText("https://itunes.apple.com/search?term=$encoded&media=music&limit=1") ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val art100 = results.optJSONObject(0)?.optString("artworkUrl100").orEmpty()
        return if (art100.isNotBlank()) art100.replace("100x100bb", "600x600bb") else null
    }

    private fun httpGetText(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val raw = conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            scaleBitmap(raw, 600)
        } finally {
            conn.disconnect()
        }
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
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

    /** Generated fallback artwork while real artwork is loading or when idle. */
    private fun artFor(label: String): Bitmap {
        fallbackCache?.let { if (it.first == label) return it.second }
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
        fallbackCache = label to bmp
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
        private const val CHANNEL = "tv_now_playing"
        private const val NOTIF_ID = 4107
        private const val ACTION_PREV = "com.glassremote.action.PREV"
        private const val ACTION_TOGGLE = "com.glassremote.action.TOGGLE"
        private const val ACTION_NEXT = "com.glassremote.action.NEXT"
        private const val PREFS = "lockscreen"

        private val _artwork = MutableStateFlow<Bitmap?>(null)
        /** Observe this in the Dashboard screen to render the fetched thumbnail in-app. */
        val artwork: StateFlow<Bitmap?> = _artwork.asStateFlow()

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

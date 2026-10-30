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
 * Fetches real thumbnails/posters from Stremio Cinemeta, IMDb, YouTube, and iTunes.
 * Features Mute, Play/Pause, and Power controls directly on the lock screen.
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
    private var activeTrackKey = ""
    private var artJob: Job? = null

    private var fallbackCache: Pair<String, Bitmap>? = null
    private val networkArtCache = LruCache<String, Bitmap>(25)

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
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ACTION_MUTE -> media(KeyEvent.KEYCODE_VOLUME_MUTE)
            ACTION_POWER -> media(KeyEvent.KEYCODE_POWER)
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

        val (mainTitle, extraSubtitle) = splitTitleAndSubtitle(rawPkg, rawTitle)

        val title = when {
            np != null -> mainTitle.ifBlank { appName.ifBlank { "TV" } }
            appName.isNotBlank() -> appName
            else -> "Nothing playing"
        }
        val subtitle = when {
            np != null -> if (extraSubtitle.isNotBlank() && appName.isNotBlank()) {
                "$extraSubtitle · $appName"
            } else {
                appName.ifBlank { extraSubtitle }
            }
            adbOn -> "Nothing is playing on the TV"
            else -> "Connect ADB in the app for titles"
        }
        val playing = np?.state == 3
        val key = "$rawPkg|$rawTitle|$subtitle|$playing"
        if (key == lastKey) return
        lastKey = key

        val trackKey = "$rawPkg|$rawTitle"
        val cachedArt = if (rawTitle.isNotBlank()) networkArtCache.get(trackKey) else null
        val initialArt = cachedArt ?: artFor(appName.ifBlank { title })

        _artwork.value = cachedArt
        publishMediaState(title, subtitle, playing, initialArt, isResolvedArt = cachedArt != null)

        if (rawTitle.isNotBlank() && rawTitle != appName && cachedArt == null) {
            if (trackKey != activeTrackKey || artJob?.isActive != true) {
                activeTrackKey = trackKey
                artJob?.cancel()
                artJob = scope.launch {
                    val resolved = resolveArtwork(rawPkg, rawTitle, mainTitle, extraSubtitle)
                    if (resolved != null) {
                        networkArtCache.put(trackKey, resolved)
                        if (activeTrackKey == trackKey) {
                            _artwork.value = resolved
                            publishMediaState(title, subtitle, np?.state == 3, resolved, isResolvedArt = true)
                        }
                    }
                }
            }
        } else if (rawTitle.isBlank()) {
            activeTrackKey = ""
            artJob?.cancel()
            _artwork.value = null
        }
    }

    private fun splitTitleAndSubtitle(pkg: String, rawTitle: String): Pair<String, String> {
        if (!rawTitle.contains(",")) return rawTitle to ""
        return if (pkg.contains("youtube") || pkg.contains("tvmusic") || pkg.contains("spotify")) {
            val main = rawTitle.substringBeforeLast(",").trim()
            val sub = rawTitle.substringAfterLast(",").trim()
            if (main.isNotBlank()) main to sub else rawTitle to ""
        } else {
            val show = rawTitle.substringBefore(",").trim()
            val episode = rawTitle.substringAfter(",").trim()
            if (episode.isNotBlank()) episode to show else rawTitle to ""
        }
    }

    private fun publishMediaState(
        title: String,
        subtitle: String,
        playing: Boolean,
        art: Bitmap,
        isResolvedArt: Boolean,
    ) {
        val mediaId = "$title|$subtitle|${if (isResolvedArt) "real" else "fallback"}"
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
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

    private suspend fun resolveArtwork(pkg: String, rawTitle: String, mainTitle: String, extraSubtitle: String): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val urls = when {
                    pkg.contains("tvmusic") || pkg.contains("spotify") -> listOfNotNull(
                        findItunesArtworkUrl(rawTitle),
                        findItunesArtworkUrl(mainTitle),
                        findYouTubeThumbnailUrl(rawTitle),
                    )
                    pkg.contains("youtube") -> listOfNotNull(
                        findYouTubeThumbnailUrl(rawTitle),
                        findYouTubeThumbnailUrl(mainTitle),
                        findImdbPosterUrl(mainTitle),
                    )
                    else -> listOfNotNull(
                        findCinemetaPosterUrl(extraSubtitle),
                        findImdbPosterUrl(extraSubtitle),
                        findCinemetaPosterUrl(mainTitle),
                        findImdbPosterUrl(mainTitle),
                        findYouTubeThumbnailUrl(rawTitle),
                    )
                }
                for (url in urls.distinct()) {
                    val bmp = downloadBitmap(url)
                    if (bmp != null) return@withContext bmp
                }
                null
            }.getOrNull()
        }

    private fun findImdbPosterUrl(titleQuery: String): String? {
        val cleaned = cleanTitleForImdb(titleQuery)
        val slug = cleaned.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(20)
            .trimEnd('_')
        if (slug.isBlank()) return null

        val firstChar = slug.first()
        val url = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$slug.json"
        val body = httpGetText(url) ?: return null

        val d = JSONObject(body).optJSONArray("d") ?: return null
        for (i in 0 until d.length()) {
            val item = d.optJSONObject(i) ?: continue
            if (!item.optString("id").startsWith("tt")) continue
            val imgUrl = item.optJSONObject("i")?.optString("imageUrl").orEmpty()
            if (imgUrl.isNotBlank()) {
                return imgUrl.replace(Regex("""\._V1_.*\.jpg$"""), "._V1_QL80_UX600_.jpg")
            }
        }
        return null
    }

    private fun findCinemetaPosterUrl(titleQuery: String): String? {
        val cleaned = cleanTitleForImdb(titleQuery)
        if (cleaned.isBlank()) return null
        val encoded = URLEncoder.encode(cleaned, "UTF-8").replace("+", "%20")

        for (type in listOf("series", "movie")) {
            val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
            val body = httpGetText(url) ?: continue
            val metas = JSONObject(body).optJSONArray("metas") ?: continue
            for (i in 0 until metas.length()) {
                val poster = metas.optJSONObject(i)?.optString("poster").orEmpty()
                if (poster.isNotBlank()) return poster
            }
        }
        return null
    }

    private fun cleanTitleForImdb(title: String): String {
        return title
            .substringBefore(",")
            .replace(Regex("(?i)\\bS\\d{1,2}\\s*E\\d{1,3}\\b.*"), "")
            .replace(Regex("(?i)\\b\\d{1,2}x\\d{1,3}\\b.*"), "")
            .replace(Regex("(?i)\\bSeason\\s*\\d+.*"), "")
            .replace(Regex("(?i)\\bEpisode\\s*\\d+.*"), "")
            .replace(Regex("(?i)\\bEp\\.?\\s*\\d+.*"), "")
            .replace(Regex("\\[.*?\\]|\\(.*?\\)"), " ")
            .replace(Regex("(?i)\\b(1080p|2160p|720p|4k|hdr|webrip|bluray)\\b.*"), "")
            .substringBefore(" - ")
            .substringBefore(" | ")
            .trim()
            .ifBlank { title.trim() }
    }

    private fun findYouTubeThumbnailUrl(query: String): String? {
        val videoId = findYouTubeIdViaInnertube(query) ?: findYouTubeIdViaHtml(query) ?: return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun findYouTubeIdViaInnertube(query: String): String? {
        val conn = (URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", DESKTOP_UA)
        }
        return try {
            val payload = JSONObject().apply {
                put("query", query)
                put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject()
                            .put("clientName", "WEB")
                            .put("clientVersion", "2.20240726.00.00")
                            .put("hl", "en")
                            .put("gl", "US")
                    )
                )
            }.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(body)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun findYouTubeIdViaHtml(query: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = httpGetText("https://www.youtube.com/results?search_query=$encoded&hl=en") ?: return null
        val direct = Regex(""""videoId":"([a-zA-Z0-9_-]{11})"""").find(html)?.groupValues?.get(1)
        if (direct != null) return direct
        return Regex("""\\x22videoId\\x22:\\x22([a-zA-Z0-9_-]{11})\\x22""").find(html)?.groupValues?.get(1)
    }

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
            instanceFollowRedirects = true
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", DESKTOP_UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", DESKTOP_UA)
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val raw = conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            scaleBitmap(raw, 600)
        } catch (_: Exception) {
            null
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
            .addAction(action(android.R.drawable.ic_menu_manage, "Mute", ACTION_MUTE))
            .addAction(
                action(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                    ACTION_TOGGLE,
                )
            )
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Power", ACTION_POWER))
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
        private const val PREFS = "lockscreen"
        private const val ACTION_TOGGLE = "com.glassremote.action.TOGGLE"
        private const val ACTION_MUTE = "com.glassremote.action.MUTE"
        private const val ACTION_POWER = "com.glassremote.action.POWER"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val _artwork = MutableStateFlow<Bitmap?>(null)
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

package com.novra.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ComponentName
import android.app.PictureInPictureParams
import android.util.Rational
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.media.MediaMetadataRetriever
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.audiofx.Equalizer
import android.util.Size
import android.util.LruCache
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val mediaArtworkCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

private data class NovraPalette(
    val background: Color, val surface: Color, val surfaceVariant: Color,
    val muted: Color, val accent: Color, val text: Color
)
private val DarkPalette = NovraPalette(
    Color(0xFF0B1211), Color(0xE615201D), Color(0xF01C2B26),
    Color(0xFF9AAEA6), Color(0xFF78E0C0), Color(0xFFF2FBF6)
)
private val LightPalette = NovraPalette(
    Color(0xFFF4F8F6), Color(0xEFFFFFFF), Color(0xFFE7F0EC),
    Color(0xFF687B72), Color(0xFF087E68), Color(0xFF15241D)
)
private val LocalNovraPalette = staticCompositionLocalOf { DarkPalette }
private val AppBg: Color @Composable get() = LocalNovraPalette.current.background
private val CardBg: Color @Composable get() = LocalNovraPalette.current.surface
private val CardAlt: Color @Composable get() = LocalNovraPalette.current.surfaceVariant
private val Muted: Color @Composable get() = LocalNovraPalette.current.muted
private val Accent: Color @Composable get() = LocalNovraPalette.current.accent
private val TextPrimary: Color @Composable get() = LocalNovraPalette.current.text

data class UserPlaylist(val name: String, val mediaKeys: List<String>)

private fun mediaKey(media: LocalMedia): String = "${if (media.isVideo) "v" else "a"}:${media.id}"

private data class OnlineTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val previewUrl: String,
    val artworkUrl: String,
    val storeUrl: String
)

private fun normalizeSearchText(value: String): String = value.lowercase()
    .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
    .replace('ى', 'ي').replace('ة', 'ه')
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

private fun editDistanceAtMostOne(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) { i++; j++ }
        else {
            edits++
            if (edits > 1) return false
            if (a.length > b.length) i++
            else if (b.length > a.length) j++
            else { i++; j++ }
        }
    }
    if (i < a.length || j < b.length) edits++
    return edits <= 1
}

private fun mediaSearchScore(media: LocalMedia, query: String): Int? {
    val q = normalizeSearchText(query)
    if (q.isBlank()) return 0
    val title = normalizeSearchText(media.title)
    val artist = normalizeSearchText(media.artist)
    val queryTokens = q.split(" ").filter(String::isNotBlank)
    val titleTokens = title.split(" ").filter(String::isNotBlank)
    val artistTokens = artist.split(" ").filter(String::isNotBlank)

    // Prefer the artist identity over incidental words in unrelated song titles.
    var score = when {
        artist == q -> 1800
        artist.startsWith("$q ") -> 1550
        artist.contains(q) -> 1300
        title == q -> 1150
        title.startsWith("$q ") -> 950
        title.contains(q) -> 750
        else -> 0
    }
    for (token in queryTokens) {
        val artistMatch = artistTokens.maxOfOrNull { word ->
            when {
                word == token -> 360
                word.startsWith(token) -> 140
                else -> 0
            }
        } ?: 0
        val titleMatch = titleTokens.maxOfOrNull { word ->
            when {
                word == token -> 260
                word.startsWith(token) -> 100
                else -> 0
            }
        } ?: 0
        val best = maxOf(artistMatch, titleMatch)
        if (best == 0) return null
        score += best
    }
    return score
}


class MainActivity : ComponentActivity() {
    var pipController: MediaController? = null
    var pipVideoActive: Boolean = false

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pipVideoActive && pipController?.isPlaying == true) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NovraApp() }
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasAudioPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

private fun hasVideoPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
    val fullAccess = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_MEDIA_VIDEO
    ) == PackageManager.PERMISSION_GRANTED
    val selectedAccess = Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED
    return fullAccess || selectedAccess
}

private fun hasMediaPermissions(context: android.content.Context): Boolean =
    hasAudioPermission(context) || hasVideoPermission(context)

private fun audioOutputDescription(device: AudioDeviceInfo, language: String): String {
    val typeLabel = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> if (language == "ar") "سماعة الهاتف" else "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> if (language == "ar") "سماعة المكالمات" else "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> if (language == "ar") "سماعة سلكية بميكروفون" else "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> if (language == "ar") "سماعة سلكية" else "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> if (language == "ar") "سماعة USB" else "USB audio"
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        AudioDeviceInfo.TYPE_DOCK -> if (language == "ar") "قاعدة صوت" else "Audio dock"
        else -> if (language == "ar") "مخرج صوت" else "Audio output"
    }
    val deviceName = device.productName?.toString()?.trim().orEmpty()
    return if (deviceName.isNotBlank() && !deviceName.equals(typeLabel, ignoreCase = true)) {
        "$deviceName · $typeLabel"
    } else typeLabel
}

private fun readAudioOutputs(context: android.content.Context, language: String): List<String> {
    val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
    return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.isSink }
        .map { audioOutputDescription(it, language) }
        .distinct()
}

@Composable
private fun NovraApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selected by remember { mutableStateOf("Home") }
    var navigationStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var showExitDialog by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("novra_preferences", android.content.Context.MODE_PRIVATE) }
    var pinEnabled by remember { mutableStateOf(AppPinVault.isEnabled(context)) }
    var pinLocked by remember { mutableStateOf(AppPinVault.isEnabled(context)) }
    var pinFlowMode by remember { mutableStateOf("none") }
    var language by remember { mutableStateOf(prefs.getString("language", "en") ?: "en") }
    var wallpaperUri by remember { mutableStateOf(prefs.getString("wallpaper_uri", null)?.let(Uri::parse)) }
    var compactCards by remember { mutableStateOf(prefs.getBoolean("compact_cards", false)) }
    var cardStyle by remember { mutableStateOf(prefs.getString("card_style", "rounded") ?: "rounded") }
    var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "dark") ?: "dark") }
    var pureBlack by remember { mutableStateOf(prefs.getBoolean("pure_black", false)) }
    var wallpaperDim by remember { mutableFloatStateOf(prefs.getFloat("wallpaper_dim", 0.22f)) }
    var accentName by remember { mutableStateOf(prefs.getString("accent_name", "mint") ?: "mint") }
    val isLightTheme = themeMode == "light"
    val chosenAccent = when (accentName) {
        "ocean" -> if (isLightTheme) Color(0xFF0369A1) else Color(0xFF7DD3FC)
        "violet" -> if (isLightTheme) Color(0xFF7052D8) else Color(0xFFC18BFF)
        "mint" -> if (isLightTheme) Color(0xFF047857) else Color(0xFF6EE7B7)
        "rose" -> if (isLightTheme) Color(0xFFBE123C) else Color(0xFFFDA4AF)
        "amber" -> if (isLightTheme) Color(0xFFB45309) else Color(0xFFFCD34D)
        "cyan" -> if (isLightTheme) Color(0xFF0E7490) else Color(0xFF67E8F9)
        "indigo" -> if (isLightTheme) Color(0xFF4338CA) else Color(0xFFA5B4FC)
        "orange" -> if (isLightTheme) Color(0xFFC2410C) else Color(0xFFFDBA74)
        else -> if (isLightTheme) Color(0xFF087E68) else Color(0xFF78E0C0)
    }
    val basePalette = if (isLightTheme) LightPalette else DarkPalette
    val palette = if (!isLightTheme && pureBlack) {
        basePalette.copy(
            background = Color.Black,
            surface = Color(0xE610111B),
            surfaceVariant = Color(0xF01A1B2A),
            accent = chosenAccent
        )
    } else basePalette.copy(accent = chosenAccent)
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            wallpaperUri = uri
            prefs.edit().putString("wallpaper_uri", uri.toString()).apply()
        }
    }
    var selectedMedia by remember { mutableStateOf<LocalMedia?>(null) }
    var playlists by remember {
        mutableStateOf(runCatching {
            val stored = JSONArray(prefs.getString("playlists_json", "[]"))
            buildList {
                for (index in 0 until stored.length()) {
                    val entry = stored.getJSONObject(index)
                    val keys = entry.getJSONArray("items")
                    add(UserPlaylist(entry.getString("name"), buildList {
                        for (itemIndex in 0 until keys.length()) add(keys.getString(itemIndex))
                    }))
                }
            }
        }.getOrDefault(emptyList()))
    }
    fun savePlaylists(updated: List<UserPlaylist>) {
        playlists = updated
        val json = JSONArray()
        updated.forEach { playlist ->
            val entry = JSONObject().put("name", playlist.name)
            val items = JSONArray()
            playlist.mediaKeys.forEach(items::put)
            entry.put("items", items)
            json.put(entry)
        }
        prefs.edit().putString("playlists_json", json.toString()).apply()
    }
    var audioOutputs by remember { mutableStateOf(readAudioOutputs(context, language)) }
    DisposableEffect(context, language) {
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        fun refreshOutputs() {
            audioOutputs = readAudioOutputs(context, language)
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refreshOutputs()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                refreshOutputs()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refreshOutputs()
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    var listenedSeconds by remember { mutableLongStateOf(prefs.getLong("listened_seconds", 0L)) }
    var favorites by remember {
        mutableStateOf(prefs.getStringSet("favorite_media", emptySet())?.toSet() ?: emptySet())
    }
    fun toggleFavorite(item: LocalMedia) {
        val key = mediaKey(item)
        favorites = if (key in favorites) favorites - key else favorites + key
        prefs.edit().putStringSet("favorite_media", favorites).apply()
    }
    val playCounts = remember {
        mutableStateMapOf<String, Int>().apply {
            prefs.all.forEach { (key, value) ->
                if (key.startsWith("play_count_") && value is Int) put(key, value)
            }
        }
    }
    val trackListenSeconds = remember {
        mutableStateMapOf<String, Long>().apply {
            prefs.all.forEach { (key, value) ->
                if (value is Long && (key.startsWith("listened_seconds_audio_") || key.startsWith("listened_seconds_video_"))) {
                    val kind = if (key.startsWith("listened_seconds_video_")) "v" else "a"
                    put("$kind:${key.substringAfterLast('_')}", value)
                }
            }
        }
    }
    fun recordPlay(item: LocalMedia) {
        val key = "play_count_${if (item.isVideo) "video" else "audio"}_${item.id}"
        val count = (playCounts[key] ?: prefs.getInt(key, 0)) + 1
        prefs.edit().putInt(key, count).apply()
        playCounts[key] = count
    }
    var playQueue by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var showNowPlaying by remember { mutableStateOf(false) }
    fun navigateTo(destination: String) {
        if (destination != selected) {
            navigationStack = navigationStack + selected
            selected = destination
        }
        showNowPlaying = false
    }
    fun navigateBack() {
        if (showNowPlaying) showNowPlaying = false
        else if (navigationStack.isNotEmpty()) {
            selected = navigationStack.last()
            navigationStack = navigationStack.dropLast(1)
        } else if (selected != "Home") selected = "Home"
        else showExitDialog = true
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playbackRequestId by remember { mutableIntStateOf(0) }
    var handledPlaybackRequestId by remember { mutableIntStateOf(0) }
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    LaunchedEffect(sleepTimerMinutes, controller) {
        if (sleepTimerMinutes > 0) {
            delay(sleepTimerMinutes * 60_000L)
            controller?.pause()
            sleepTimerMinutes = 0
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(controller, playQueue) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val activeIndex = controller?.currentMediaItemIndex ?: -1
                playQueue.getOrNull(activeIndex)?.let { item ->
                    selectedMedia = item
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    ) recordPlay(item)
                }
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(isPlaying, selectedMedia) {
        val activeMedia = selectedMedia ?: return@LaunchedEffect
        val activeKey = mediaKey(activeMedia)
        val storageKey = "listened_seconds_${if (activeMedia.isVideo) "video" else "audio"}_${activeMedia.id}"
        while (isPlaying && selectedMedia?.let(::mediaKey) == activeKey) {
            delay(1000)
            listenedSeconds += 1
            val trackTotal = (trackListenSeconds[activeKey] ?: prefs.getLong(storageKey, 0L)) + 1L
            trackListenSeconds[activeKey] = trackTotal
            if (trackTotal % 5L == 0L || listenedSeconds % 15L == 0L) {
                prefs.edit()
                    .putLong("listened_seconds", listenedSeconds)
                    .putLong(storageKey, trackTotal)
                    .apply()
            }
        }
    }
    fun updatePlaybackQueue(updated: List<LocalMedia>) {
        val player = controller
        val currentIndex = player?.currentMediaItemIndex ?: 0
        val current = playQueue.getOrNull(currentIndex) ?: selectedMedia
        val currentPosition = player?.currentPosition ?: 0L
        val wasPlaying = player?.isPlaying == true
        val currentKey = current?.let(::mediaKey)
        val preservedIndex = currentKey?.let { key ->
            updated.indexOfFirst { mediaKey(it) == key }
        } ?: -1
        playQueue = updated
        if (updated.isEmpty()) {
            player?.pause()
            player?.clearMediaItems()
            selectedMedia = null
            showNowPlaying = false
            return
        }

        // If the current item was removed, continue with the item now occupying
        // its position (or the previous final item when it was at the end).
        val targetIndex = if (preservedIndex >= 0) {
            preservedIndex
        } else {
            currentIndex.coerceIn(0, updated.lastIndex)
        }
        val nextCurrent = updated[targetIndex]
        val keepPosition = preservedIndex >= 0
        selectedMedia = nextCurrent

        val mediaItems = updated.map { entry ->
            MediaItem.Builder().setUri(entry.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setDisplayTitle(entry.title).setArtist(entry.artist).setDurationMs(entry.durationMs.coerceAtLeast(0L)).apply {
                    if (!entry.isVideo) {
                        if (entry.artworkUri != null) setArtworkUri(entry.artworkUri)
                        else if (entry.albumId >= 0L) setArtworkUri(Uri.parse("content://media/external/audio/albumart/${entry.albumId}"))
                    }
                }.build())
                .build()
        }
        player?.setMediaItems(mediaItems, targetIndex, if (keepPosition) currentPosition else 0L)
        player?.prepare()
        if (wasPlaying || !keepPosition) player?.play() else player?.pause()
    }
    fun moveQueueItem(from: Int, to: Int) {
        if (from !in playQueue.indices || to !in playQueue.indices || from == to) return
        val updated = playQueue.toMutableList()
        val item = updated.removeAt(from)
        updated.add(to, item)
        updatePlaybackQueue(updated)
    }
    fun removeQueueItem(index: Int) {
        if (index !in playQueue.indices) return
        updatePlaybackQueue(playQueue.toMutableList().also { it.removeAt(index) })
    }
    fun clearUpcomingQueue() {
        val currentIndex = controller?.currentMediaItemIndex ?: 0
        val current = playQueue.getOrNull(currentIndex) ?: selectedMedia
        if (current != null) updatePlaybackQueue(listOf(current))
        else updatePlaybackQueue(emptyList())
    }
    fun stopPlayback() {
        controller?.stop()
        controller?.clearMediaItems()
        playQueue = emptyList()
        selectedMedia = null
        showNowPlaying = false
    }
    fun startMedia(item: LocalMedia, queue: List<LocalMedia>) {
        selectedMedia = item
        playQueue = queue
        playbackRequestId += 1
        showNowPlaying = true
        recordPlay(item)
    }
    fun enqueueMedia(item: LocalMedia) {
        val currentQueue = playQueue.ifEmpty { listOfNotNull(selectedMedia) }
        if (currentQueue.isEmpty()) {
            startMedia(item, listOf(item))
            return
        }
        if (currentQueue.any { mediaKey(it) == mediaKey(item) }) return
        updatePlaybackQueue(currentQueue + item)
    }
    fun playNextMedia(item: LocalMedia) {
        val currentQueue = playQueue.ifEmpty { listOfNotNull(selectedMedia) }
        if (currentQueue.isEmpty()) {
            startMedia(item, listOf(item))
            return
        }
        val activeIndex = (controller?.currentMediaItemIndex ?: 0).coerceIn(0, currentQueue.lastIndex)
        val activeKey = currentQueue[activeIndex].let(::mediaKey)
        if (mediaKey(item) == activeKey) return
        val updated = currentQueue.toMutableList()
        val existingIndex = updated.indexOfFirst { mediaKey(it) == mediaKey(item) }
        if (existingIndex >= 0) updated.removeAt(existingIndex)
        val newActiveIndex = updated.indexOfFirst { mediaKey(it) == activeKey }.coerceAtLeast(0)
        updated.add((newActiveIndex + 1).coerceAtMost(updated.size), item)
        updatePlaybackQueue(updated)
    }
    val controllerFuture = remember(context) {
        MediaController.Builder(context, SessionToken(context, ComponentName(context, NovraPlaybackService::class.java))).buildAsync()
    }
    DisposableEffect(controllerFuture) {
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess { controller = it }
        }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(controllerFuture) }
    }
    SideEffect {
        (context as? MainActivity)?.apply {
            pipController = controller
            pipVideoActive = selectedMedia?.isVideo == true
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = isLightTheme
                isAppearanceLightNavigationBars = isLightTheme
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setPictureInPictureParams(PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(pipVideoActive && controller?.isPlaying == true)
                    .build())
            }
        }
    }
    LaunchedEffect(selectedMedia, controller, playbackRequestId) {
        val media = selectedMedia
        val player = controller
        if (media != null && player != null) {
            val isNewPlayRequest = playbackRequestId != handledPlaybackRequestId
            if (!isNewPlayRequest && player.currentMediaItem?.localConfiguration?.uri == media.uri) {
                return@LaunchedEffect
            }
            val queue = playQueue.ifEmpty { listOf(media) }
            val startIndex = queue.indexOfFirst { it.id == media.id && it.isVideo == media.isVideo }.coerceAtLeast(0)
            val items = queue.map { entry ->
                MediaItem.Builder()
                    .setUri(entry.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setDisplayTitle(entry.title).setArtist(entry.artist).setDurationMs(entry.durationMs.coerceAtLeast(0L)).apply { if (!entry.isVideo && entry.albumId >= 0L) setArtworkUri(Uri.parse("content://media/external/audio/albumart/${entry.albumId}")) }.build())
                    .build()
            }
            player.setMediaItems(items, startIndex, 0L)
            player.prepare()
            player.play()
            handledPlaybackRequestId = playbackRequestId
        }
    }
    var videos by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var hasPermission by remember {
        mutableStateOf(hasMediaPermissions(context))
    }
    var hasAudioAccess by remember { mutableStateOf(hasAudioPermission(context)) }
    var hasVideoAccess by remember { mutableStateOf(hasVideoPermission(context)) }
    var loading by remember { mutableStateOf(false) }
    var mediaRefresh by remember { mutableIntStateOf(0) }
    val deleteMediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) mediaRefresh += 1
    }
    fun requestMediaDelete(items: List<LocalMedia>) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri })
            deleteMediaLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            items.forEach { item -> runCatching { context.contentResolver.delete(item.uri, null, null) } }
            mediaRefresh += 1
        }
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { mediaRefresh += 1 }
        }
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasAudioAccess = hasAudioPermission(context)
                    hasVideoAccess = hasVideoPermission(context)
                    hasPermission = hasAudioAccess || hasVideoAccess
                    mediaRefresh += 1
                }
                Lifecycle.Event.ON_STOP -> if (AppPinVault.isEnabled(context)) pinLocked = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasAudioAccess = hasAudioPermission(context)
        hasVideoAccess = hasVideoPermission(context)
        hasPermission = hasAudioAccess || hasVideoAccess
    }

    LaunchedEffect(hasPermission, hasAudioAccess, hasVideoAccess, mediaRefresh) {
        if (hasPermission) {
            delay(300)
            loading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val videoItems = if (hasVideoAccess) {
                        runCatching { MediaLibrary.videos(context) }.getOrDefault(emptyList())
                    } else emptyList()
                    val audioItems = if (hasAudioAccess) {
                        runCatching { MediaLibrary.music(context) }.getOrDefault(emptyList())
                    } else emptyList()
                    videoItems to audioItems
                }
                videos = result.first
                tracks = result.second
            } finally {
                loading = false
            }
        } else {
            videos = emptyList()
            tracks = emptyList()
        }
    }

    BackHandler(enabled = true) { if (showExitDialog) showExitDialog = false else navigateBack() }
    CompositionLocalProvider(LocalNovraPalette provides palette, LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = if (themeMode == "light") lightColorScheme(
            background = AppBg, surface = CardBg, surfaceVariant = CardAlt,
            primary = Accent, onPrimary = Color.White, onBackground = TextPrimary,
            onSurface = TextPrimary, onSurfaceVariant = Muted, outline = Muted,
            secondary = Accent, tertiary = Accent
        ) else darkColorScheme(
            background = AppBg, surface = CardBg, surfaceVariant = CardAlt,
            primary = Accent, onPrimary = Color(0xFF101310), onBackground = TextPrimary,
            onSurface = TextPrimary, onSurfaceVariant = Muted, outline = Muted,
            secondary = Accent, tertiary = Accent
        )) {
            Box(Modifier.fillMaxSize()) {
            if (!isLightTheme && wallpaperUri == null) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 110.dp, y = (-90).dp).size(270.dp).blur(90.dp).background(Color(0xFF8B35E8).copy(alpha = 0.24f), CircleShape))
                Box(Modifier.align(Alignment.BottomStart).offset(x = (-110).dp, y = 90.dp).size(230.dp).blur(85.dp).background(Color(0xFF287ECA).copy(alpha = 0.17f), CircleShape))
            }
            if (!pinLocked && pinFlowMode == "none" && showExitDialog) AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text(if (language == "ar") "الخروج من Viora؟" else "Exit Viora?") },
                text = { Text(if (language == "ar") "هل أنت متأكد أنك تريد إغلاق التطبيق؟" else "Are you sure you want to close the app?") },
                confirmButton = { TextButton(onClick = { showExitDialog = false; (context as? MainActivity)?.finish() }) { Text(if (language == "ar") "خروج" else "Exit") } },
                dismissButton = { TextButton(onClick = { showExitDialog = false }) { Text(if (language == "ar") "إلغاء" else "Cancel") } }
            )
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val drawerScope = rememberCoroutineScope()
            val topDestinations = listOf("Home", "Videos", "Music", "Explore", "Playlists", "Favorites")
            val mainDestinations = listOf(
                Triple("Home", Icons.Rounded.Home, "Home"),
                Triple("Videos", Icons.Rounded.OndemandVideo, "Videos"),
                Triple("Music", Icons.Rounded.MusicNote, "Music"),
                Triple("Playlists", Icons.Rounded.QueueMusic, "Playlists"),
                Triple("Favorites", Icons.Rounded.Favorite, "Favorites")
            )
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = false,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = CardBg,
                        drawerContentColor = TextPrimary,
                        drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)
                    ) {
                        Column(
                            Modifier.fillMaxHeight().verticalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 18.dp)
                        ) {
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF7356C9), Color(0xFF253E59))))
                                    .padding(18.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                                            .background(Color.White.copy(alpha = 0.13f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(25.dp))
                                    }
                                    Spacer(Modifier.width(11.dp))
                                    Column {
                                        Text("Viora", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 2.5.sp)
                                        Text("MEDIA EXPERIENCE", color = Color(0xFFD0E2D6), fontSize = 9.sp, letterSpacing = 1.3.sp)
                                    }
                                }
                                Spacer(Modifier.height(17.dp))
                                Text("Your entertainment, organized.", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(22.dp))
                            Text("LIBRARY", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                            mainDestinations.forEach { item ->
                                NavigationDrawerItem(
                                    label = { Text(item.third, fontSize = 14.sp, fontWeight = if (selected == item.first) FontWeight.SemiBold else FontWeight.Normal) },
                                    selected = selected == item.first,
                                    onClick = {
                                        selected = item.first
                                        showNowPlaying = false
                                        drawerScope.launch { drawerState.close() }
                                    },
                                    icon = { Icon(item.second, contentDescription = item.third) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = NavigationDrawerItemDefaults.colors(
                                        selectedContainerColor = CardAlt,
                                        selectedIconColor = Accent,
                                        selectedTextColor = Accent,
                                        unselectedIconColor = Muted,
                                        unselectedTextColor = TextPrimary
                                    )
                                )
                            }
                            Spacer(Modifier.height(18.dp))
                            HorizontalDivider(color = CardAlt, modifier = Modifier.padding(horizontal = 10.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("MORE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                            listOf(
                                Triple("Settings", Icons.Rounded.Settings, "Settings")
                            ).forEach { item ->
                                NavigationDrawerItem(
                                    label = { Text(item.third, fontSize = 14.sp) },
                                    selected = selected == item.first,
                                    onClick = {
                                        selected = item.first
                                        showNowPlaying = false
                                        drawerScope.launch { drawerState.close() }
                                    },
                                    icon = { Icon(item.second, contentDescription = item.third) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = NavigationDrawerItemDefaults.colors(
                                        selectedContainerColor = CardAlt,
                                        selectedIconColor = Accent,
                                        selectedTextColor = Accent,
                                        unselectedIconColor = Muted,
                                        unselectedTextColor = TextPrimary
                                    )
                                )
                            }
                            Spacer(Modifier.height(22.dp))
                            Text("Viora · Play your world", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            ) {
                Box(Modifier.fillMaxSize().background(AppBg).statusBarsPadding().navigationBarsPadding()) {
                    if (wallpaperUri != null) {
                        AsyncImage(
                            model = wallpaperUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (wallpaperDim > 0f) Box(Modifier.fillMaxSize().background((if (isLightTheme) Color.White else Color.Black).copy(alpha = wallpaperDim)))
                    }
                    Scaffold(
                        containerColor = if (wallpaperUri != null) Color.Transparent else AppBg,
                        topBar = {
                            if (!showNowPlaying) {
                            Column(
                                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(CardBg.copy(alpha = 0.94f), AppBg.copy(alpha = 0.90f))))
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (showNowPlaying) {
                                        IconButton(onClick = { showNowPlaying = false }) {
                                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                                        }
                                    } else if (selected == "Home") {
                                        Box(
                                            Modifier.size(34.dp).clip(RoundedCornerShape(12.dp))
                                                .background(Brush.linearGradient(listOf(Color(0xFF238A78), Color(0xFF39BFA0), Color(0xFF8DE8C9))))
                                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("V", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Viora", tint = Color.White.copy(alpha = 0.95f), modifier = Modifier.size(15.dp).align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 3.dp))
                                        }
                                    }
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(
                                            if (showNowPlaying) {
                                                if (selectedMedia?.isVideo == true) (if (language == "ar") "مشغل الفيديو" else "Video player")
                                                else (if (language == "ar") "يعمل الآن" else "Now Playing")
                                            } else if (selected == "Home") "Viora" else if (language == "ar") when (selected) {
                                                "Videos" -> "الفيديوهات"
                                                "Music" -> "الأغاني"
                                                "Playlists" -> "قوائم التشغيل"
                                                "Explore" -> "استكشف"
                                                "Favorites" -> "المفضلة"
                                                "Settings" -> "الإعدادات"
                                                else -> selected
                                            } else selected,
                                            color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (showNowPlaying) {
                                                if (selectedMedia?.isVideo == true) (if (language == "ar") "شاهد الفيديو وتحكم في التشغيل" else "Watch and control your video")
                                                else (if (language == "ar") "الوسائط الحالية" else "Your current media")
                                            } else if (language == "ar") "مساحتك للوسائط" else "Your personal media space",
                                            color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!showNowPlaying && selected != "Home" && selected != "Settings") {
                                        IconButton(onClick = { navigateTo("Settings") }) {
                                            Icon(Icons.Rounded.Tune, contentDescription = "Settings", tint = if (selected == "Settings") Accent else Muted)
                                        }
                                    }
                                }

                            }
                            }
                        },
                        bottomBar = {
                            if (!showNowPlaying) {
                            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, AppBg.copy(alpha = 0.96f)))).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 5.dp)) {
                                if (selectedMedia != null && !showNowPlaying) {
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(CardAlt.copy(alpha = 0.98f), CardBg.copy(alpha = 0.96f)))).padding(start = 10.dp, end = 5.dp, top = 7.dp, bottom = 7.dp).clickable { showNowPlaying = true }, verticalAlignment = Alignment.CenterVertically) {
                                        MediaArtwork(selectedMedia!!, Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)))
                                        Spacer(Modifier.width(11.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(selectedMedia?.title.orEmpty(), color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            Text(if (language == "ar") "يعمل الآن" else "NOW PLAYING", color = Accent, fontSize = 9.sp, letterSpacing = 1.sp)
                                        }
                                        IconButton(onClick = { controller?.let { if (it.currentPosition > 3_000L) it.seekTo(0L) else it.seekToPreviousMediaItem() } }) { Icon(Icons.Rounded.SkipPrevious, if (language == "ar") "السابق" else "Previous", tint = Muted) }
                                        IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) { Icon(if (controller?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = TextPrimary) }
                                        IconButton(onClick = { controller?.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, if (language == "ar") "التالي" else "Next", tint = Muted) }
                                        IconButton(onClick = ::stopPlayback) { Icon(Icons.Rounded.Close, null, tint = Muted) }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                if (!showNowPlaying) {
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp)).background(CardBg.copy(alpha = 0.96f)).padding(horizontal = 3.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                                        val dockItems = listOf(
                                            Triple("Home", Icons.Rounded.Home, if (language == "ar") "الرئيسية" else "Home"),
                                            Triple("Videos", Icons.Rounded.OndemandVideo, if (language == "ar") "فيديو" else "Videos"),
                                            Triple("Music", Icons.Rounded.MusicNote, if (language == "ar") "موسيقى" else "Music"),
                                            Triple("Playlists", Icons.Rounded.QueueMusic, if (language == "ar") "قوائم" else "Playlists"),
                                            Triple("Favorites", Icons.Rounded.Favorite, if (language == "ar") "المفضلة" else "Saved")
                                        )
                                        dockItems.forEach { item ->
                                            val active = selected == item.first
                                            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (active) Accent.copy(alpha = 0.16f) else Color.Transparent).clickable { selected = item.first; navigationStack = emptyList(); showNowPlaying = false }.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(item.second, contentDescription = item.third, tint = if (active) Accent else Muted, modifier = Modifier.size(21.dp))
                                                Spacer(Modifier.height(3.dp))
                                                Text(item.third, color = if (active) TextPrimary else Muted, fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                            }
                        }
                    ) { padding ->
                        AnimatedContent(
                            targetState = showNowPlaying to selected,
                            transitionSpec = {
                                (fadeIn(tween(220)) + slideInHorizontally { it / 18 }) togetherWith
                                    (fadeOut(tween(150)) + slideOutHorizontally { -it / 18 })
                            },
                            label = "viora-screen-transition"
                        ) { (playerVisible, destination) ->
                        if (playerVisible && selectedMedia != null) {
                            NowPlayingScreen(
                                Modifier.padding(padding), selectedMedia!!, controller, playQueue,
                                playCount = playCounts["play_count_audio_${selectedMedia!!.id}"] ?: 0,
                                trackListenSeconds = trackListenSeconds[mediaKey(selectedMedia!!)] ?: 0L,
                                sleepTimerMinutes, onSleepTimerChange = { sleepTimerMinutes = it },
                                onMoveQueueItem = ::moveQueueItem, onRemoveQueueItem = ::removeQueueItem,
                                onClearQueue = ::clearUpcomingQueue,
                                onBack = { showNowPlaying = false }, language = language
                            )
                        } else when (destination) {
                            "Home" -> HomeScreen(
                                Modifier.padding(padding), videos.size, tracks.size,
                                tracks.map { track ->
                                    val key = "play_count_audio_${track.id}"
                                    track to (playCounts[key] ?: 0)
                                }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5),
                                navigate = ::navigateTo,
                                onPlay = { startMedia(it, tracks) }, language = language
                            )
                            "Videos" -> MediaScreen(
                                Modifier.padding(padding), "Videos", videos, loading, hasVideoAccess,
                                onGrant = { permissionLauncher.launch(mediaPermissions()) },
                                compactCards = compactCards, cardStyle = cardStyle,
                                playCounts = playCounts, favorites = favorites,
                                onToggleFavorite = ::toggleFavorite,
                                onPlay = { item -> startMedia(item, (videos + item).distinctBy { mediaKey(it) }) },
                                onAddToQueue = ::enqueueMedia,
                                onPlayNext = ::playNextMedia,
                                language = language,
                                playlists = playlists,
                                onAddToPlaylist = { playlistName, item ->
                                    savePlaylists(playlists.map { playlist ->
                                        if (playlist.name == playlistName && mediaKey(item) !in playlist.mediaKeys)
                                            playlist.copy(mediaKeys = playlist.mediaKeys + mediaKey(item))
                                        else playlist
                                    })
                                },
                                onDelete = ::requestMediaDelete
                            )
                            "Music" -> MediaScreen(
                                Modifier.padding(padding), "Music", tracks, loading, hasAudioAccess,
                                onGrant = { permissionLauncher.launch(mediaPermissions()) },
                                compactCards = compactCards, cardStyle = cardStyle,
                                playCounts = playCounts, favorites = favorites,
                                onToggleFavorite = ::toggleFavorite,
                                onPlay = { item -> startMedia(item, (tracks + item).distinctBy { mediaKey(it) }) },
                                onAddToQueue = ::enqueueMedia,
                                onPlayNext = ::playNextMedia,
                                language = language,
                                onNavigate = ::navigateTo,
                                playlists = playlists,
                                onAddToPlaylist = { playlistName, item ->
                                    savePlaylists(playlists.map { playlist ->
                                        if (playlist.name == playlistName && mediaKey(item) !in playlist.mediaKeys)
                                            playlist.copy(mediaKeys = playlist.mediaKeys + mediaKey(item))
                                        else playlist
                                    })
                                },
                                onDelete = ::requestMediaDelete
                            )
                            "Explore" -> OnlineSearchScreen(
                                language = language,
                                onPlayPreview = { item -> startMedia(item, listOf(item)) }
                            )
                            "Favorites" -> MediaScreen(
                                Modifier.padding(padding), "Favorites", (videos + tracks).distinctBy { mediaKey(it) }, loading, hasPermission,
                                onGrant = { permissionLauncher.launch(mediaPermissions()) },
                                compactCards = compactCards, cardStyle = cardStyle,
                                playCounts = playCounts, favorites = favorites,
                                onToggleFavorite = ::toggleFavorite,
                                onPlay = { item -> startMedia(item, (videos + tracks + item).distinctBy { mediaKey(it) }) },
                                onAddToQueue = ::enqueueMedia,
                                onPlayNext = ::playNextMedia,
                                language = language,
                                playlists = playlists,
                                onAddToPlaylist = { playlistName, item ->
                                    savePlaylists(playlists.map { playlist ->
                                        if (playlist.name == playlistName && mediaKey(item) !in playlist.mediaKeys)
                                            playlist.copy(mediaKeys = playlist.mediaKeys + mediaKey(item))
                                        else playlist
                                    })
                                },
                                onDelete = ::requestMediaDelete
                            )
                            "Playlists" -> PlaylistsScreen(
                                Modifier.padding(padding), playlists, (videos + tracks).distinctBy { mediaKey(it) },
                                onCreate = { name -> savePlaylists(playlists + UserPlaylist(name, emptyList())) },
                                onAdd = { playlistName, item ->
                                    savePlaylists(playlists.map { playlist ->
                                        if (playlist.name == playlistName && mediaKey(item) !in playlist.mediaKeys)
                                            playlist.copy(mediaKeys = playlist.mediaKeys + mediaKey(item))
                                        else playlist
                                    })
                                },
                                onRemove = { playlistName, item ->
                                    savePlaylists(playlists.map { playlist ->
                                        if (playlist.name == playlistName)
                                            playlist.copy(mediaKeys = playlist.mediaKeys.filterNot { it == mediaKey(item) })
                                        else playlist
                                    })
                                },
                                onDelete = { playlistName -> savePlaylists(playlists.filterNot { it.name == playlistName }) },
                                onPlay = { items -> if (items.isNotEmpty()) startMedia(items.first(), items) },
                                onAddToQueue = ::enqueueMedia,
                                onPlayNext = ::playNextMedia,
                                language = language
                            )
                            else -> SettingsScreen(
                                Modifier.padding(padding), wallpaperUri, compactCards, cardStyle, listenedSeconds, themeMode, accentName, pureBlack,
                                pinEnabled = pinEnabled,
                                onEnablePin = { pinFlowMode = "setup" },
                                onChangePin = { pinFlowMode = "verify_change" },
                                onDisablePin = { pinFlowMode = "verify_disable" },
                                onThemeChange = { themeMode = it; prefs.edit().putString("theme_mode", it).apply() },
                                onPureBlackChange = { pureBlack = it; prefs.edit().putBoolean("pure_black", it).apply() },
                                onAccentChange = { accentName = it; prefs.edit().putString("accent_name", it).apply() },
                                onChooseWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                                onClearWallpaper = { wallpaperUri = null; prefs.edit().remove("wallpaper_uri").apply() },
                                onCompactChange = { compactCards = it; prefs.edit().putBoolean("compact_cards", it).apply() },
                                onCardStyleChange = { cardStyle = it; prefs.edit().putString("card_style", it).apply() },
                                 language = language,
                                 onLanguageChange = { language = it; prefs.edit().putString("language", it).apply() },
                                 wallpaperDim = wallpaperDim,
                                 onWallpaperDimChange = { wallpaperDim = it; prefs.edit().putFloat("wallpaper_dim", it).apply() },
                                 audioOutputs = audioOutputs
                            )
                        }
                        }
                    }
                }
            }
            if (pinLocked || pinFlowMode != "none") {
                val isSetup = pinFlowMode == "setup"
                val isVerification = pinFlowMode == "verify_change" || pinFlowMode == "verify_disable"
                AppPinGate(
                    title = when {
                        pinLocked -> if (language == "ar") "افتح Viora" else "Unlock Viora"
                        isSetup -> if (language == "ar") "إنشاء رمز القفل" else "Create app PIN"
                        pinFlowMode == "verify_change" -> if (language == "ar") "تأكيد هويتك" else "Verify your PIN"
                        else -> if (language == "ar") "تعطيل القفل" else "Disable app lock"
                    },
                    subtitle = when {
                        pinLocked -> if (language == "ar") "أدخل الرمز للمتابعة. يستمر تشغيل الوسائط بالخلفية." else "Enter your PIN to continue. Media can keep playing in the background."
                        isSetup -> if (language == "ar") "اختر رقمًا من 4 إلى 8 أرقام." else "Choose a PIN between 4 and 8 digits."
                        else -> if (language == "ar") "أدخل رمزك الحالي أولًا." else "Enter your current PIN first."
                    },
                    confirmLabel = if (isSetup) "Confirm PIN" else "",
                    errorLabel = if (language == "ar") "الرمز غير صحيح أو حدث خطأ. حاول مرة أخرى." else "Incorrect PIN or a security error. Try again.",
                    pinLabel = if (language == "ar") if (isSetup) "رمز جديد" else "الرقم السري" else if (isSetup) "New PIN" else "PIN",
                    confirmationFieldLabel = if (language == "ar") "تأكيد الرمز" else "Confirm PIN",
                    submitLabel = if (language == "ar") when { isSetup -> "حفظ الرمز"; pinLocked -> "فتح Viora"; else -> "تأكيد" } else if (isSetup) "Save PIN" else if (pinLocked) "Unlock" else "Confirm",
                    cancelLabel = if (language == "ar") "إلغاء" else "Cancel",
                    lockoutMessage = { seconds -> if (language == "ar") "محاولات كثيرة. حاول بعد ${seconds} ثانية." else "Too many attempts. Try again in ${seconds}s." },
                    onSubmit = { entered ->
                        when {
                            pinLocked -> AppPinVault.verify(context, entered).also { if (it) pinLocked = false }
                            isSetup -> runCatching {
                                AppPinVault.setPin(context, entered)
                                pinEnabled = true
                                pinFlowMode = "none"
                            }.isSuccess
                            isVerification && AppPinVault.verify(context, entered) -> {
                                if (pinFlowMode == "verify_change") pinFlowMode = "setup"
                                else {
                                    AppPinVault.disable(context)
                                    pinEnabled = false
                                    pinFlowMode = "none"
                                }
                                true
                            }
                            else -> false
                        }
                    },
                    onCancel = if (pinLocked) null else ({ pinFlowMode = "none" })
                )
            }
            }
        }
    }
}


@Composable
private fun OnlineSearchScreen(
    language: String,
    onPlayPreview: (LocalMedia) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<OnlineTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(submittedQuery) {
        val term = submittedQuery.trim()
        if (term.isBlank()) {
            tracks = emptyList()
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val found = withContext(Dispatchers.IO) {
                val connection = (URL("https://itunes.apple.com/search?term=${Uri.encode(term)}&media=music&entity=song&limit=30").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Viora-Android")
                }
                try {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val results = json.optJSONArray("results") ?: JSONArray()
                    buildList {
                        for (index in 0 until results.length()) {
                            val item = results.optJSONObject(index) ?: continue
                            val preview = item.optString("previewUrl")
                            if (preview.isBlank()) continue
                            val artwork = item.optString("artworkUrl100")
                            add(
                                OnlineTrack(
                                    id = item.optLong("trackId", index.toLong()),
                                    title = item.optString("trackName").ifBlank { "Unknown track" },
                                    artist = item.optString("artistName").ifBlank { "Unknown artist" },
                                    album = item.optString("collectionName"),
                                    previewUrl = preview,
                                    artworkUrl = artwork.replace("100x100bb", "600x600bb"),
                                    storeUrl = item.optString("trackViewUrl")
                                )
                            )
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            tracks = found
            if (found.isEmpty()) error = if (language == "ar") "لم نعثر على معاينات متاحة. جرّب اسمًا آخر." else "No playable previews found. Try another search."
        } catch (_: Exception) {
            error = if (language == "ar") "تعذر الاتصال بخدمة البحث. تحقق من الإنترنت وحاول مجددًا." else "Search is unavailable. Check your connection and try again."
            tracks = emptyList()
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (language == "ar") "اكتشف الموسيقى" else "Discover music", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(if (language == "ar") "ابحث عن الأغاني واستمع إلى معاينة فورية" else "Find tracks and play instant previews", color = Muted, fontSize = 11.sp)
            }
            Icon(Icons.Rounded.Explore, null, tint = Accent, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(13.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(15.dp),
            placeholder = { Text(if (language == "ar") "اسم الأغنية أو الفنان..." else "Song or artist name...", color = Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Accent) },
            trailingIcon = {
                if (query.isNotBlank()) IconButton(onClick = { query = ""; submittedQuery = ""; tracks = emptyList(); error = null }) {
                    Icon(Icons.Rounded.Close, if (language == "ar") "مسح" else "Clear", tint = Muted)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = CardAlt,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent
            )
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { submittedQuery = query.trim() },
            enabled = query.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(13.dp)
        ) {
            Icon(Icons.Rounded.Search, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (language == "ar") "ابحث عن الأغاني" else "Search songs")
        }
        Spacer(Modifier.height(10.dp))
        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(10.dp))
                    Text(if (language == "ar") "نبحث عن الموسيقى..." else "Finding music...", color = Muted, fontSize = 12.sp)
                }
            }
        } else if (error != null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(error.orEmpty(), color = Muted, fontSize = 13.sp)
            }
        } else if (tracks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = Accent, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(if (language == "ar") "موسيقى جديدة في انتظارك" else "Your next favorite track awaits", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(if (language == "ar") "اكتب اسم أغنية أو فنان للبدء." else "Search for a song or artist to begin.", color = Muted, fontSize = 11.sp)
                }
            }
        } else {
            Text(if (language == "ar") "${tracks.size} نتيجة · معاينات موسيقية" else "${tracks.size} results · music previews", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 5.dp))
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(tracks, key = { it.id }) { track ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(CardBg).padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = track.title,
                            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (track.album.isNotBlank()) Text(track.album, color = Muted.copy(alpha = 0.75f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (language == "ar") "معاينة 30 ثانية" else "30-second preview", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = {
                            val item = LocalMedia(
                                id = -((track.id and 0x7fffffffL) + 1L),
                                uri = Uri.parse(track.previewUrl),
                                title = track.title,
                                artist = track.artist,
                                durationMs = 30000L,
                                sizeBytes = 0L,
                                isVideo = false,
                                artworkUri = Uri.parse(track.artworkUrl)
                            )
                            onPlayPreview(item)
                        }) {
                            Icon(Icons.Rounded.PlayCircleFilled, if (language == "ar") "تشغيل المعاينة" else "Play preview", tint = Accent, modifier = Modifier.size(31.dp))
                        }
                        if (track.storeUrl.isNotBlank()) {
                            IconButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.storeUrl))) }
                            }) {
                                Icon(Icons.Rounded.OpenInNew, if (language == "ar") "فتح المصدر" else "Open source", tint = Muted)
                            }
                        }
                    }
                }
            }
        }
        Text(
            if (language == "ar") "المعاينات مقدمة عبر Apple Music؛ التشغيل الكامل متاح من المصدر." else "Previews are provided by Apple Music. Full tracks are available from the source.",
            color = Muted.copy(alpha = 0.72f), fontSize = 9.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun VideoNowPlayingScreen(
    modifier: Modifier,
    media: LocalMedia,
    controller: MediaController?,
    queue: List<LocalMedia>,
    position: Float,
    duration: Float,
    onBack: () -> Unit,
    language: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var seekFeedback by remember(media.id) { mutableStateOf("") }
    var showControls by remember(media.id) { mutableStateOf(true) }
    var settingsExpanded by remember(media.id) { mutableStateOf(false) }
    var videoSpeed by remember(controller) { mutableFloatStateOf(controller?.playbackParameters?.speed ?: 1f) }
    var scrubbing by remember(media.id) { mutableStateOf(false) }
    var seekPosition by remember(media.id) { mutableFloatStateOf(position) }
    var isPlaying by remember(controller) { mutableStateOf(controller?.isPlaying == true) }
    LaunchedEffect(position, scrubbing) { if (!scrubbing) seekPosition = position }
    LaunchedEffect(controller) {
        while (true) {
            isPlaying = controller?.isPlaying == true
            delay(250)
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback.isNotEmpty()) { delay(750); seekFeedback = "" }
    }
    LaunchedEffect(showControls, settingsExpanded, isPlaying, seekFeedback) {
        if (showControls && isPlaying && !settingsExpanded && seekFeedback.isEmpty()) {
            delay(4_000)
            showControls = false
        }
    }
    var subtitleUri by remember(media.id) { mutableStateOf<Uri?>(null) }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            subtitleUri = uri
            val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType("application/x-subrip").setLanguage("und").setLabel("Viora subtitle").build()
            controller?.let { player ->
                val wasPlaying = player.isPlaying
                val activeQueue = queue.ifEmpty { listOf(media) }
                val activeIndex = player.currentMediaItemIndex.coerceIn(0, activeQueue.lastIndex)
                val rebuiltItems = activeQueue.mapIndexed { index, entry ->
                    val builder = MediaItem.Builder()
                        .setUri(entry.uri)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setDisplayTitle(entry.title).setArtist(entry.artist).setDurationMs(entry.durationMs.coerceAtLeast(0L)).apply { if (!entry.isVideo && entry.albumId >= 0L) setArtworkUri(Uri.parse("content://media/external/audio/albumart/${entry.albumId}")) }.build())
                    if (index == activeIndex) builder.setSubtitleConfigurations(listOf(subtitle))
                    builder.build()
                }
                player.setMediaItems(rebuiltItems, activeIndex, position.toLong())
                player.prepare()
                if (wasPlaying) player.play()
            }
        }
    }
    var loopStart by remember(media.id) { mutableLongStateOf(-1L) }
    var loopEnd by remember(media.id) { mutableLongStateOf(-1L) }
    LaunchedEffect(controller, loopStart, loopEnd) {
        while (loopStart >= 0L && loopEnd > loopStart) {
            val player = controller
            if (player != null && player.isPlaying && player.currentPosition >= loopEnd) player.seekTo(loopStart)
            delay(180)
        }
    }
    Column(modifier.fillMaxSize().background(AppBg).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                } },
                update = { view -> view.player = controller }
            )
            Box(Modifier.fillMaxSize().pointerInput(controller, duration) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        val player = controller
                        if (player != null) {
                            val forward = offset.x >= size.width / 2f
                            val target = (player.currentPosition + if (forward) 10_000L else -10_000L)
                                .coerceIn(0L, duration.toLong().coerceAtLeast(0L))
                            player.seekTo(target)
                            seekFeedback = if (forward) "+10s" else "−10s"
                            showControls = true
                        }
                    }
                )
            })
            if (showControls) {
                Box(Modifier.fillMaxWidth().height(82.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent))))
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, if (language == "ar") "رجوع" else "Back", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(media.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (language == "ar") "مشغل الفيديو" else "VIDEO PLAYER", color = Color.White.copy(alpha = 0.65f), fontSize = 9.sp, letterSpacing = 1.2.sp)
                    }
                    Box {
                        IconButton(onClick = { settingsExpanded = true }) { Icon(Icons.Rounded.Settings, if (language == "ar") "إعدادات الفيديو" else "Video settings", tint = Color.White) }
                        DropdownMenu(expanded = settingsExpanded, onDismissRequest = { settingsExpanded = false }) {
                            DropdownMenuItem(text = { Text(if (language == "ar") "سرعة التشغيل" else "Playback speed", fontWeight = FontWeight.Bold) }, enabled = false, onClick = {})
                            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                DropdownMenuItem(
                                    text = { Text(if (speed == 1f) "Normal  ·  1×" else "${speed}×", color = if (videoSpeed == speed) Accent else TextPrimary) },
                                    onClick = { videoSpeed = speed; controller?.setPlaybackSpeed(speed); settingsExpanded = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (language == "ar") "تحميل ملف ترجمة SRT" else "Load SRT subtitles") },
                                leadingIcon = { Icon(Icons.Rounded.Subtitles, null) },
                                onClick = { settingsExpanded = false; subtitlePicker.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) }
                            )
                            DropdownMenuItem(
                                text = { Text(if (language == "ar") "تحديد بداية المقطع A" else "Set loop point A") },
                                leadingIcon = { Icon(Icons.Rounded.Repeat, null) },
                                onClick = { loopStart = controller?.currentPosition ?: 0L; loopEnd = -1L; settingsExpanded = false; seekFeedback = if (language == "ar") "تم تحديد بداية المقطع A" else "Loop point A set"; showControls = true }
                            )
                            DropdownMenuItem(
                                text = { Text(if (language == "ar") "تحديد نهاية المقطع B" else "Set loop point B") },
                                enabled = loopStart >= 0L,
                                onClick = {
                                    val endPoint = controller?.currentPosition ?: 0L
                                    if (endPoint > loopStart) {
                                        loopEnd = endPoint
                                        seekFeedback = if (language == "ar") "تم تفعيل التكرار A–B" else "A–B repeat enabled"
                                    } else {
                                        seekFeedback = if (language == "ar") "نقطة B يجب أن تأتي بعد A" else "Point B must be after A"
                                    }
                                    settingsExpanded = false
                                    showControls = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (language == "ar") "إلغاء تكرار المقطع" else "Clear A-B loop") },
                                onClick = { loopStart = -1L; loopEnd = -1L; settingsExpanded = false; seekFeedback = if (language == "ar") "تم إلغاء التكرار" else "A–B repeat cleared"; showControls = true }
                            )
                        }
                    }
                }
                Row(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(30.dp))
                        .background(Color.Black.copy(alpha = 0.34f)).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    IconButton(onClick = { controller?.seekToPreviousMediaItem() }) { Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                    IconButton(onClick = { controller?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) } }) { Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(29.dp)) }
                    Box(
                        Modifier.size(58.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Accent, Color(0xFF6650C8))))
                            .clickable { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                        contentAlignment = Alignment.Center
                    ) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                    IconButton(onClick = { controller?.let { it.seekTo((it.currentPosition + 10_000L).coerceAtMost(duration.toLong().coerceAtLeast(0L))) } }) { Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(29.dp)) }
                    IconButton(onClick = { controller?.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                }
                Box(Modifier.fillMaxWidth().height(94.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
                    if (seekFeedback.isNotEmpty()) {
                        Text(seekFeedback, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    Slider(
                        value = seekPosition.coerceIn(0f, duration.coerceAtLeast(1f)),
                        onValueChange = { scrubbing = true; seekPosition = it },
                        onValueChangeFinished = { controller?.seekTo(seekPosition.toLong()); scrubbing = false },
                        valueRange = 0f..duration.coerceAtLeast(1f),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.35f))
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(seekPosition.toLong()), color = Color.White, fontSize = 10.sp)
                        Text(formatDuration(duration.toLong()), color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
            Text(media.title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(if (language == "ar") "فيديو من مكتبتك" else "From your video library", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(17.dp)).background(CardBg).padding(12.dp)) {
                    Text(if (language == "ar") "السرعة" else "SPEED", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(if (videoSpeed == 1f) "Normal · 1×" else "${videoSpeed}×", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(17.dp)).background(CardBg).padding(12.dp)) {
                    Text(if (language == "ar") "الترجمة" else "SUBTITLES", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(if (subtitleUri == null) (if (language == "ar") "غير مفعلة" else "Off") else "SRT", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(17.dp)).background(CardBg).padding(12.dp)) {
                    Text(if (language == "ar") "تكرار" else "A-B LOOP", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(if (loopStart >= 0L && loopEnd > loopStart) "A–B" else "Off", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (loopStart >= 0L) {
                Text(
                    if (loopEnd > loopStart) "A ${formatDuration(loopStart)}  ·  B ${formatDuration(loopEnd)}" else "A ${formatDuration(loopStart)}  ·  Set B from video settings",
                    color = Muted, fontSize = 10.sp
                )
            }
        }
    }
}
@Composable
private fun HomeScreen(
    modifier: Modifier, videoCount: Int, musicCount: Int,
    topTracks: List<Pair<LocalMedia, Int>>,
    navigate: (String) -> Unit, onPlay: (LocalMedia) -> Unit, language: String = "en"
) {
    val featured = topTracks.firstOrNull()?.first
    val isArabic = language == "ar"
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (isArabic) "أهلاً بك في Viora" else "WELCOME TO VIORA", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
                }
                IconButton(onClick = { navigate("Settings") }) { Icon(Icons.Rounded.Settings, if (isArabic) "الإعدادات" else "Settings", tint = Muted) }
                IconButton(onClick = { navigate("Explore") }) { Icon(Icons.Rounded.Search, if (isArabic) "بحث" else "Search", tint = TextPrimary) }
            }
            Spacer(Modifier.height(13.dp))
            Box(
                Modifier.fillMaxWidth().height(232.dp).clip(RoundedCornerShape(27.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF103C35), Color(0xFF14505A), Color(0xFF102D36))))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(27.dp))
            ) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 40.dp, y = (-60).dp).size(190.dp).blur(55.dp).background(Color(0xFF65E2BC).copy(alpha = 0.34f), CircleShape))
                Box(Modifier.align(Alignment.BottomStart).offset(x = (-50).dp, y = 55.dp).size(170.dp).blur(55.dp).background(Color(0xFF4DCDBB).copy(alpha = 0.22f), CircleShape))
                if (featured != null) {
                    MediaArtwork(featured, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(148.dp).clip(RoundedCornerShape(23.dp)).border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(23.dp)))
                } else {
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(140.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Color(0xFF238A78), Color(0xFF45C7A5)))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(78.dp))
                    }
                }
                Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.64f).padding(start = 18.dp, top = 14.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF56E6D1)))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isArabic) "تجربة Viora" else "THE VIORA EXPERIENCE", color = Color(0xFFBEEBFF), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (isArabic) "كل ترفيهك\nفي مكان واحد" else "Your world.\nYour soundtrack.", color = Color.White, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(7.dp))
                    Text(if (isArabic) "موسيقى وفيديوهات بتجربة مختلفة." else "Music and video, beautifully together.", color = Color.White.copy(alpha = 0.78f), fontSize = 9.sp, maxLines = 2)
                    Spacer(Modifier.height(13.dp))
                    Row(
                        Modifier.clip(CircleShape).background(Color.White).clickable { if (featured != null) onPlay(featured) else navigate("Music") }.padding(horizontal = 13.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF10372F), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isArabic) "ابدأ الاستماع" else "Start listening", color = Color(0xFF10372F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (isArabic) "مكتبتك" else "YOUR LIBRARY", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
                    Text(if (isArabic) "كل شيء جاهز للتشغيل" else "Ready when you are", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Text(if (isArabic) "عرض الكل" else "VIEW ALL", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { navigate("Music") })
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF54218A), Color(0xFF28204F)))).clickable { navigate("Music") }.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color(0xFFE6C9FF), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowOutward, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(musicCount.toString(), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (isArabic) "أغنية" else "SONGS", color = Color.White.copy(alpha = 0.72f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF15516C), Color(0xFF172B51)))).clickable { navigate("Videos") }.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.OndemandVideo, null, tint = Color(0xFFB7F1FF), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowOutward, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(videoCount.toString(), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (isArabic) "فيديو" else "VIDEOS", color = Color.White.copy(alpha = 0.72f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
        if (topTracks.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (isArabic) "عد إلى موسيقاك" else "BACK TO YOUR MUSIC", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(if (isArabic) "الأكثر استماعًا" else "Most played", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text(if (isArabic) "المكتبة" else "LIBRARY", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { navigate("Music") })
            }
            Spacer(Modifier.height(11.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                topTracks.take(8).forEach { entry ->
                    Column(Modifier.width(132.dp).clip(RoundedCornerShape(18.dp)).background(CardBg).clickable { onPlay(entry.first) }.padding(9.dp)) {
                        Box {
                            MediaArtwork(entry.first, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(13.dp)))
                            Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(31.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = AppBg, modifier = Modifier.size(21.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(entry.first.title, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.first.artist, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text(if (isArabic) "اكتشف Viora" else "DISCOVER VIORA", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf(
                    Triple("Playlists", Icons.Rounded.QueueMusic, if (isArabic) "قوائمك" else "Playlists"),
                    Triple("Favorites", Icons.Rounded.Favorite, if (isArabic) "المفضلة" else "Favorites"),
                    Triple("Explore", Icons.Rounded.Explore, if (isArabic) "استكشف" else "Explore")
                ).forEach { item ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(17.dp)).background(CardBg).clickable { navigate(item.first) }.padding(vertical = 13.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(item.second, null, tint = Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(7.dp))
                        Text(item.third, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun MediaScreen(
    modifier: Modifier, type: String, media: List<LocalMedia>, loading: Boolean,
    hasPermission: Boolean, onGrant: () -> Unit, compactCards: Boolean, cardStyle: String,
    playCounts: Map<String, Int>, favorites: Set<String>,
    onToggleFavorite: (LocalMedia) -> Unit, onPlay: (LocalMedia) -> Unit,
    onAddToQueue: (LocalMedia) -> Unit = {},
    onPlayNext: (LocalMedia) -> Unit = {},
    language: String = "en",
    onNavigate: (String) -> Unit = {},
    playlists: List<UserPlaylist> = emptyList(),
    onAddToPlaylist: (String, LocalMedia) -> Unit = {},
    onDelete: (List<LocalMedia>) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val importPrefs = remember(context, type) { context.getSharedPreferences("viora_imports", android.content.Context.MODE_PRIVATE) }
    fun importedItem(uri: Uri): LocalMedia {
        val isVideoFile = type == "Videos"
        val retriever = MediaMetadataRetriever()
        val metadata = runCatching {
            retriever.setDataSource(context, uri)
            Triple(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            )
        }.getOrDefault(Triple("", "", 0L))
        runCatching { retriever.release() }
        val displayNameAndSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    (if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "") to
                        (if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L)
                } else "" to 0L
            } ?: ("" to 0L)
        }.getOrDefault("" to 0L)
        val fallbackName = displayNameAndSize.first.substringBeforeLast('.').ifBlank { if (language == "ar") "ملف مستورد" else "Imported media" }
        val stableId = -((uri.toString().hashCode().toLong() and 0x7fffffffL) + 1L)
        return LocalMedia(
            id = stableId, uri = uri,
            title = metadata.first.ifBlank { fallbackName },
            artist = metadata.second,
            durationMs = metadata.third,
            sizeBytes = displayNameAndSize.second,
            isVideo = isVideoFile,
            dateAddedSeconds = System.currentTimeMillis() / 1000L
        )
    }
    var importedMedia by remember(type) {
        mutableStateOf(
            importPrefs.getStringSet("uris_$type", emptySet()).orEmpty().mapNotNull { value ->
                runCatching { importedItem(Uri.parse(value)) }.getOrNull()
            }
        )
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            importedMedia = (importedMedia + uris.map(::importedItem)).distinctBy { it.uri.toString() }
            importPrefs.edit().putStringSet("uris_$type", importedMedia.map { it.uri.toString() }.toSet()).apply()
        }
    }
    val allMedia = remember(media, importedMedia) { (media + importedMedia).distinctBy { mediaKey(it) } }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember(type) { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("Default") }
    var selectedKeys by remember(type) { mutableStateOf<Set<String>>(emptySet()) }
    var playlistSelection by remember { mutableStateOf<List<LocalMedia>?>(null) }
    fun toggleSelection(item: LocalMedia) {
        val key = mediaKey(item)
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }
    fun shareItems(items: List<LocalMedia>) {
        if (items.isEmpty()) return
        val sendIntent = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = if (items.first().isVideo) "video/*" else "audio/*"
                putExtra(Intent.EXTRA_STREAM, items.first().uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching { context.startActivity(Intent.createChooser(sendIntent, if (language == "ar") "مشاركة الوسائط" else "Share media")) }
    }
    var mediaFilter by remember(type) { mutableStateOf(if (type == "Favorites") "Favorites" else "All") }
    var smartFolder by remember(type) { mutableStateOf("All") }
    val nowSeconds = System.currentTimeMillis() / 1000L
    val ranked = allMedia.mapNotNull { item ->
        mediaSearchScore(item, query)?.let { item to it }
    }.filter { mediaFilter != "Favorites" || mediaKey(it.first) in favorites }
        .filter { pair ->
            val item = pair.first
            when (smartFolder) {
                "Recent" -> item.dateAddedSeconds > 0L && nowSeconds - item.dateAddedSeconds <= 30L * 24L * 60L * 60L
                "Short" -> item.isVideo && item.durationMs in 1L..180_000L
                "Large" -> item.sizeBytes >= 500L * 1024L * 1024L
                else -> true
            }
        }
    val sortedMedia = when (sortBy) {
        "Name" -> ranked.sortedBy { it.first.title.lowercase() }.map { it.first }
        "Oldest" -> ranked.sortedBy { it.first.dateAddedSeconds }.map { it.first }
        "Newest" -> ranked.sortedByDescending { it.first.dateAddedSeconds }.map { it.first }
        "Duration" -> ranked.sortedByDescending { it.first.durationMs }.map { it.first }
        "Most played" -> ranked.sortedByDescending {
            playCounts["play_count_${if (it.first.isVideo) "video" else "audio"}_${it.first.id}"] ?: 0
        }.map { it.first }
        else -> ranked.sortedWith(
            compareByDescending<Pair<LocalMedia, Int>> { it.second }
                .thenByDescending { playCounts["play_count_${if (it.first.isVideo) "video" else "audio"}_${it.first.id}"] ?: 0 }
        ).map { it.first }
    }
    val selectedItems = sortedMedia.filter { mediaKey(it) in selectedKeys }
    Column(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (language == "ar") when (type) { "Videos" -> "الفيديوهات"; "Music" -> "الأغاني"; else -> "المفضلة" } else if (type == "Music") "Songs" else type, color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (type == "Music") {
                IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) query = "" }) {
                    Icon(if (searchExpanded) Icons.Rounded.Close else Icons.Rounded.Search, if (language == "ar") "بحث" else "Search", tint = TextPrimary)
                }
            }
            if (type == "Music" || type == "Videos") {
                IconButton(onClick = { importLauncher.launch(arrayOf(if (type == "Music") "audio/*" else "video/*")) }) {
                    Icon(Icons.Rounded.Add, if (language == "ar") "استيراد ملفات" else "Import files", tint = Accent)
                }
            }
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) { Icon(Icons.Rounded.Sort, "Sort", tint = TextPrimary) }
                DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                    listOf("Default", "Name", "Oldest", "Newest", "Duration", "Most played").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(if (language == "ar") when (option) {
                                "Default" -> "الترتيب الافتراضي"
                                "Name" -> "أبجديًا"
                                "Oldest" -> "من الأقدم"
                                "Newest" -> "من الأحدث"
                                "Duration" -> "حسب المدة"
                                else -> "الأكثر استماعًا"
                            } else when (option) {
                                "Name" -> "Alphabetical"
                                "Oldest" -> "Oldest first"
                                "Newest" -> "Newest first"
                                "Most played" -> "Most played"
                                else -> option
                            }) },
                            onClick = { sortBy = option; sortMenuExpanded = false },
                            trailingIcon = { if (sortBy == option) Icon(Icons.Rounded.Check, null, tint = Accent) }
                        )
                    }
                }
            }
        }
        if (type != "Music") Text(
            when (type) {
                "Videos" -> if (language == "ar") "مجموعتك من الفيديوهات" else "Your video collection"
                else -> if (language == "ar") "كل الوسائط المحفوظة" else "Everything you saved"
            },
            color = Muted, fontSize = 12.sp
        )
        if (type == "Music") {
            Spacer(Modifier.height(15.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Videos", if (language == "ar") "فيديوهات" else "Videos", Icons.Rounded.OndemandVideo),
                    Triple("Music", if (language == "ar") "أغاني" else "Songs", Icons.Rounded.MusicNote),
                    Triple("Playlists", if (language == "ar") "قوائم التشغيل" else "Playlists", Icons.Rounded.QueueMusic),
                    Triple("Favorites", if (language == "ar") "المفضلة" else "Favorites", Icons.Rounded.Favorite)
                ).forEach { tab ->
                    val active = tab.first == "Music"
                    Surface(
                        color = if (active) Accent else CardBg,
                        shape = CircleShape,
                        modifier = Modifier.clickable { if (!active) onNavigate(tab.first) }
                    ) {
                        Row(Modifier.padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(tab.third, null, tint = if (active) AppBg else Muted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tab.second, color = if (active) AppBg else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedVisibility(
            visible = type != "Music" || searchExpanded,
            enter = fadeIn() + slideInVertically { -it / 4 },
            exit = fadeOut() + slideOutVertically { -it / 4 }
        ) {
            OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            placeholder = { Text(if (language == "ar") "ابحث في ${when (type) { "Music" -> "الأغاني"; "Videos" -> "الفيديوهات"; else -> "الوسائط" }}" else "Search $type", color = Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = CardAlt,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                cursorColor = Accent
            )
            )
        }
        Spacer(Modifier.height(12.dp))
        if (type != "Music") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All" to if (language == "ar") "الكل" else "All", "Favorites" to if (language == "ar") "المفضلة" else "Favorites").forEach { (key, label) ->
                Surface(
                    color = if (mediaFilter == key) Accent else CardBg,
                    shape = CircleShape,
                    modifier = Modifier.clickable { mediaFilter = key }
                ) {
                    Text(
                        label,
                        color = if (mediaFilter == key) AppBg else TextPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (type != "Music") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            val folders = buildList {
                add("All" to if (language == "ar") "الكل" else "All")
                add("Recent" to if (language == "ar") "أُضيف حديثًا" else "Recent")
                if (type == "Videos" || type == "Favorites") add("Short" to if (language == "ar") "فيديوهات قصيرة" else "Short videos")
                add("Large" to if (language == "ar") "ملفات كبيرة" else "Large files")
            }
            folders.forEach { (key, label) ->
                FilterChip(selected = smartFolder == key, onClick = { smartFolder = key }, label = { Text(label, fontSize = 10.sp) })
            }
            Text("Sort: $sortBy", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(if (type == "Music") 4.dp else 14.dp))
        when {
            !hasPermission -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Accent, modifier = Modifier.size(45.dp))
                Spacer(Modifier.height(14.dp))
                Text(if (language == "ar") "اسمح بالوصول إلى الوسائط" else "Allow media access", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (language == "ar") "يحتاج Viora إلى إذن لعرض وسائط جهازك." else "Viora needs permission to show your $type.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onGrant) { Text(if (language == "ar") "السماح بالوصول" else "Grant access") }
            }
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            sortedMedia.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (type == "Videos") Icons.Rounded.OndemandVideo else Icons.Rounded.MusicNote, null, tint = Accent, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(12.dp))
                Text(if (language == "ar") { if (query.isBlank()) "لا توجد وسائط بعد" else "لا توجد نتائج مطابقة" } else if (query.isBlank()) "Nothing here yet" else "No results found", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (language == "ar") { if (query.isBlank()) "ستظهر وسائط جهازك هنا." else "جرّب كلمات بحث أخرى." } else if (query.isBlank()) "Media on your device will appear here." else "Try another search.", color = Muted, fontSize = 11.sp)
            }
            else -> {
                if (type == "Videos") {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(if (compactCards) 10.dp else 16.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        item {
                            Text(
                                if (language == "ar") "${sortedMedia.size} فيديو" else "${sortedMedia.size} videos",
                                color = Muted, fontSize = 11.sp
                            )
                        }
                        items(sortedMedia, key = { mediaKey(it) }) { item ->
                            val countKey = "play_count_video_${item.id}"
                            Column(
                                Modifier.fillMaxWidth()
                                    .clip(if (cardStyle == "square") RoundedCornerShape(8.dp) else RoundedCornerShape(22.dp))
                                    .background(Brush.linearGradient(listOf(CardBg, CardAlt.copy(alpha = 0.62f))))
                                    .border(1.dp, Color.White.copy(alpha = 0.055f), if (cardStyle == "square") RoundedCornerShape(8.dp) else RoundedCornerShape(22.dp))
                                    .combinedClickable(
                                        onClick = { if (selectedKeys.isNotEmpty()) toggleSelection(item) else onPlay(item) },
                                        onLongClick = { toggleSelection(item) }
                                    )
                                    .background(if (mediaKey(item) in selectedKeys) Accent.copy(alpha = 0.10f) else Color.Transparent)
                                    .padding(if (compactCards) 8.dp else 10.dp)
                            ) {
                                Box(
                                    Modifier.fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(if (cardStyle == "square") RoundedCornerShape(5.dp) else RoundedCornerShape(13.dp))
                                        .background(CardAlt)
                                ) {
                                    MediaArtwork(item, Modifier.fillMaxSize())
                                    Box(
                                        Modifier.align(Alignment.Center).size(48.dp)
                                            .clip(CircleShape).background(Color.Black.copy(alpha = 0.62f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(31.dp))
                                    }
                                    Surface(
                                        Modifier.align(Alignment.BottomEnd).padding(8.dp),
                                        color = Color.Black.copy(alpha = 0.78f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(formatDuration(item.durationMs), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            if (language == "ar") "${playCounts[countKey] ?: 0} مشاهدة · من جهازك" else "${playCounts[countKey] ?: 0} plays · On your device",
                                            color = Muted, fontSize = 10.sp, maxLines = 1
                                        )
                                    }
                                    IconButton(onClick = { onPlayNext(item) }) {
                                        Icon(Icons.Rounded.SkipNext, if (language == "ar") "تشغيل بعد المقطع الحالي" else "Play next", tint = Accent)
                                    }
                                    IconButton(onClick = { onAddToQueue(item) }) {
                                        Icon(Icons.Rounded.QueueMusic, if (language == "ar") "إضافة إلى آخر الطابور" else "Add to queue", tint = Muted)
                                    }
                                    IconButton(onClick = { onToggleFavorite(item) }) {
                                        Icon(
                                            if (mediaKey(item) in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                            if (mediaKey(item) in favorites) "Remove from favorites" else "Add to favorites",
                                            tint = if (mediaKey(item) in favorites) Accent else Muted
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(if (compactCards) 5.dp else 8.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        item {
                            Text(
                                if (language == "ar") "${sortedMedia.size} أغنية" else "${sortedMedia.size} songs",
                                color = Muted, fontSize = 11.sp
                            )
                        }
                        items(sortedMedia, key = { mediaKey(it) }) { item ->
                            var menuExpanded by remember(item.id) { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(15.dp))
                                    .combinedClickable(
                                        onClick = { if (selectedKeys.isNotEmpty()) toggleSelection(item) else onPlay(item) },
                                        onLongClick = { toggleSelection(item) }
                                    )
                                    .background(if (mediaKey(item) in selectedKeys) Accent.copy(alpha = 0.10f) else Color.Transparent)
                                    .padding(vertical = 7.dp, horizontal = 1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MediaArtwork(
                                    item,
                                    Modifier.size(74.dp).clip(RoundedCornerShape(13.dp))
                                )
                                Spacer(Modifier.width(13.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        item.artist.ifBlank { if (language == "ar") "فنان غير معروف" else "Unknown artist" },
                                        color = Muted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        if (language == "ar") "${formatDuration(item.durationMs)} · ${playCounts["play_count_audio_${item.id}"] ?: 0} تشغيل"
                                        else "${formatDuration(item.durationMs)} · ${playCounts["play_count_audio_${item.id}"] ?: 0} plays",
                                        color = Muted.copy(alpha = 0.78f),
                                        fontSize = 10.sp
                                    )
                                }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Rounded.MoreVert, if (language == "ar") "المزيد" else "More", tint = Muted)
                                    }
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text(if (language == "ar") "تشغيل بعد الحالي" else "Play next") },
                                            leadingIcon = { Icon(Icons.Rounded.SkipNext, null) },
                                            onClick = { menuExpanded = false; onPlayNext(item) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (language == "ar") "إضافة إلى قائمة التشغيل" else "Add to queue") },
                                            leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) },
                                            onClick = { menuExpanded = false; onAddToQueue(item) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (mediaKey(item) in favorites) { if (language == "ar") "إزالة من المفضلة" else "Remove from favorites" } else { if (language == "ar") "إضافة للمفضلة" else "Add to favorites" }) },
                                            leadingIcon = { Icon(if (mediaKey(item) in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) },
                                            onClick = { menuExpanded = false; onToggleFavorite(item) }
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = CardAlt.copy(alpha = 0.55f), thickness = 0.6.dp)
                        }
                    }
                }
            }
        }
        if (selectedItems.isNotEmpty()) {
            Surface(color = CardBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (language == "ar") "تم تحديد ${selectedItems.size}" else "${selectedItems.size} selected", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedKeys = emptySet() }) { Text(if (language == "ar") "إلغاء" else "Clear") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedItems.forEach(onToggleFavorite); selectedKeys = emptySet() }) { Icon(Icons.Rounded.FavoriteBorder, null); Text(if (language == "ar") "المفضلة" else "Favorite") }
                        TextButton(onClick = { selectedItems.forEach(onPlayNext); selectedKeys = emptySet() }) { Icon(Icons.Rounded.SkipNext, null); Text(if (language == "ar") "التالي" else "Play next") }
                        TextButton(onClick = { selectedItems.forEach(onAddToQueue); selectedKeys = emptySet() }) { Icon(Icons.Rounded.QueueMusic, null); Text(if (language == "ar") "الطابور" else "Queue") }
                        TextButton(onClick = { playlistSelection = selectedItems }) { Icon(Icons.Rounded.PlaylistAdd, null); Text(if (language == "ar") "قائمة" else "Playlist") }
                        TextButton(onClick = { shareItems(selectedItems) }) { Icon(Icons.Rounded.Share, null); Text(if (language == "ar") "مشاركة" else "Share") }
                        TextButton(onClick = { onDelete(selectedItems); selectedKeys = emptySet() }) { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error); Text(if (language == "ar") "حذف" else "Delete") }
                    }
                }
            }
        }
    }
    playlistSelection?.let { itemsToAdd ->
        AlertDialog(
            onDismissRequest = { playlistSelection = null },
            title = { Text(if (language == "ar") "إضافة إلى قائمة تشغيل" else "Add to playlist") },
            text = {
                if (playlists.isEmpty()) Text(if (language == "ar") "أنشئ قائمة تشغيل أولًا من قسم القوائم." else "Create a playlist first in the Playlists section.")
                else Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    playlists.forEach { playlist ->
                        TextButton(onClick = {
                            itemsToAdd.forEach { onAddToPlaylist(playlist.name, it) }
                            playlistSelection = null
                            selectedKeys = emptySet()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.QueueMusic, null)
                            Spacer(Modifier.width(9.dp))
                            Text(playlist.name, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { playlistSelection = null }) { Text(if (language == "ar") "إغلاق" else "Close") } }
        )
    }

}

internal fun formatDuration(duration: Long): String {
    val total = duration / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}


private fun decodeAlbumArtwork(data: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
    if (maxDimension <= 0) return null
    var sample = 1
    while (maxDimension / sample > 1200) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        data, 0, data.size,
        BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    ) ?: return null
    val side = maxOf(decoded.width, decoded.height)
    if (side <= 1200) return decoded
    val scale = 1200f / side
    return Bitmap.createScaledBitmap(
        decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1), true
    ).also { if (it !== decoded) decoded.recycle() }
}

private fun loadAlbumArtwork(context: android.content.Context, media: LocalMedia): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, media.uri)
        val embedded = retriever.embeddedPicture
        if (embedded != null) {
            decodeAlbumArtwork(embedded)
        } else if (media.albumId >= 0L) {
            val albumUri = Uri.parse("content://media/external/audio/albumart/${media.albumId}")
            runCatching {
                context.contentResolver.openInputStream(albumUri)?.use { input ->
                    decodeAlbumArtwork(input.readBytes())
                }
            }.getOrNull()
        } else null
    } catch (_: Throwable) {
        null
    } finally {
        retriever.release()
    }
}

@Composable
private fun MediaArtwork(media: LocalMedia, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, media.uri, media.albumId, media.artworkUri) {
        if (media.artworkUri != null) {
            value = null
        } else value = withContext(Dispatchers.IO) {
            val cacheKey = media.uri.toString()
            mediaArtworkCache.get(cacheKey) ?: runCatching {
                if (!media.isVideo) {
                    loadAlbumArtwork(context, media)
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, media.uri)
                        val generatedThumbnail: Bitmap? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                runCatching { context.contentResolver.loadThumbnail(media.uri, Size(512, 512), null) }.getOrNull()
                            } else null
                        generatedThumbnail ?: retriever.getFrameAtTime(
                            0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )?.let { frame ->
                            val maxSide = maxOf(frame.width, frame.height)
                            if (maxSide > 768) {
                                val scale = 768f / maxSide
                                Bitmap.createScaledBitmap(
                                    frame, (frame.width * scale).toInt().coerceAtLeast(1),
                                    (frame.height * scale).toInt().coerceAtLeast(1), true
                                ).also { if (it !== frame) frame.recycle() }
                            } else frame
                        }
                    } finally {
                        retriever.release()
                    }
                }
            }.getOrNull()?.also { bitmap -> mediaArtworkCache.put(cacheKey, bitmap) }
        }
    }
    Box(modifier.background(CardAlt), contentAlignment = Alignment.Center) {
        if (media.artworkUri != null) {
            AsyncImage(model = media.artworkUri, contentDescription = media.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = media.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                if (media.isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.Album,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(42.dp)
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingScreen(
    modifier: Modifier, media: LocalMedia, controller: MediaController?, queue: List<LocalMedia>,
    playCount: Int, trackListenSeconds: Long,
    sleepTimerMinutes: Int, onSleepTimerChange: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit, onRemoveQueueItem: (Int) -> Unit,
    onClearQueue: () -> Unit, onBack: () -> Unit, language: String = "en"
) {
    var shuffle by remember(controller) { mutableStateOf(controller?.shuffleModeEnabled == true) }
    var repeatMode by remember(controller) { mutableIntStateOf(controller?.repeatMode ?: Player.REPEAT_MODE_OFF) }
    var currentQueueIndex by remember(controller) { mutableIntStateOf(controller?.currentMediaItemIndex ?: 0) }
    var playbackPosition by remember(controller) { mutableFloatStateOf(0f) }
    var isSeeking by remember(controller) { mutableStateOf(false) }
    var playbackDuration by remember(controller) { mutableFloatStateOf(media.durationMs.coerceAtLeast(1L).toFloat()) }
    var playbackSpeed by remember(controller, media.id) { mutableFloatStateOf(controller?.playbackParameters?.speed ?: 1f) }
    var isPlaying by remember(controller) { mutableStateOf(controller?.isPlaying == true) }
    var activePanel by remember { mutableStateOf("none") }
    var showRingtoneCutter by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val lyricPrefs = remember(context) { context.getSharedPreferences("novra_lyrics", android.content.Context.MODE_PRIVATE) }
    var lyricsUri by remember(media.id) { mutableStateOf(lyricPrefs.getString("audio_${media.id}", null)?.let(Uri::parse)) }
    var lyricLines by remember(media.id) { mutableStateOf<List<LyricLine>>(emptyList()) }
    val lyricsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            lyricsUri = uri
            lyricPrefs.edit().putString("audio_${media.id}", uri.toString()).apply()
        }
    }
    val artworkBitmap by produceState<Bitmap?>(null, media.uri) {
        value = withContext(Dispatchers.IO) {
            val key = media.uri.toString()
            mediaArtworkCache.get(key) ?: loadAlbumArtwork(context, media)?.also { mediaArtworkCache.put(key, it) }
        }
    }
    val artworkTint = remember(artworkBitmap) { dominantArtworkColor(artworkBitmap) }
    var artworkDragOffset by remember(media.id) { mutableFloatStateOf(0f) }
    val artworkSwipeScope = rememberCoroutineScope()
    val artworkSwipeThreshold = 72f * context.resources.displayMetrics.density
    LaunchedEffect(lyricsUri, media.id) {
        lyricLines = if (lyricsUri == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(lyricsUri!!)?.bufferedReader()?.use { parseLrc(it.readText()) } ?: emptyList() }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(controller, media.id) {
        while (true) {
            val duration = controller?.duration?.takeIf { it > 0L } ?: media.durationMs
            playbackDuration = duration.coerceAtLeast(1L).toFloat()
            if (!isSeeking) playbackPosition = (controller?.currentPosition ?: 0L).coerceIn(0L, duration.coerceAtLeast(1L)).toFloat()
            currentQueueIndex = controller?.currentMediaItemIndex ?: 0
            isPlaying = controller?.isPlaying == true
            shuffle = controller?.shuffleModeEnabled == true
            repeatMode = controller?.repeatMode ?: Player.REPEAT_MODE_OFF
            playbackSpeed = controller?.playbackParameters?.speed ?: 1f
            delay(350)
        }
    }
    if (media.isVideo) {
        VideoNowPlayingScreen(modifier, media, controller, queue, playbackPosition, playbackDuration, onBack, language)
        return
    }
    Box(modifier.fillMaxSize()) {
        if (artworkBitmap != null) {
            androidx.compose.foundation.Image(bitmap = artworkBitmap!!.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.16f, scaleY = 1.16f).blur(58.dp), contentScale = ContentScale.Crop)
        } else Box(Modifier.fillMaxSize().background(artworkTint.copy(alpha = 0.35f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.50f), Color.Black.copy(alpha = 0.83f), Color.Black.copy(alpha = 0.96f)))))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(0.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(24.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (language == "ar") "يعمل الآن" else "NOW PLAYING", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(if (language == "ar") "من مكتبتك" else "FROM YOUR LIBRARY", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp)
                }
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(1.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxWidth(0.82f).aspectRatio(1f)
                        .graphicsLayer {
                            translationX = artworkDragOffset
                            rotationZ = (artworkDragOffset / 85f).coerceIn(-8f, 8f)
                            scaleX = 1f - (kotlin.math.abs(artworkDragOffset) / 1800f).coerceIn(0f, 0.08f)
                            scaleY = scaleX
                        }
                        .pointerInput(media.id, controller, currentQueueIndex, queue.size) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    artworkDragOffset = (artworkDragOffset + dragAmount).coerceIn(-size.width * 0.42f, size.width * 0.42f)
                                },
                                onDragEnd = {
                                    val distance = artworkDragOffset
                                    val artworkWidth = size.width.toFloat()
                                    val wantsNext = distance < -artworkSwipeThreshold && controller?.hasNextMediaItem() == true
                                    val wantsPrevious = distance > artworkSwipeThreshold && controller?.hasPreviousMediaItem() == true
                                    artworkSwipeScope.launch {
                                        val destination = when {
                                            wantsNext -> -artworkWidth * 1.1f
                                            wantsPrevious -> artworkWidth * 1.1f
                                            else -> 0f
                                        }
                                        val animation = androidx.compose.animation.core.Animatable(artworkDragOffset)
                                        animation.animateTo(destination, animationSpec = androidx.compose.animation.core.tween(190)) {
                                            artworkDragOffset = value
                                        }
                                        if (wantsNext) controller?.seekToNextMediaItem()
                                        else if (wantsPrevious) controller?.seekToPreviousMediaItem()
                                        artworkDragOffset = 0f
                                    }
                                },
                                onDragCancel = {
                                    artworkSwipeScope.launch {
                                        val animation = androidx.compose.animation.core.Animatable(artworkDragOffset)
                                        animation.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(170)) {
                                            artworkDragOffset = value
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    MediaArtwork(media, Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp)))
                }
            }
            Spacer(Modifier.height(15.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(media.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(media.artist.ifBlank { if (language == "ar") "فنان غير معروف" else "Unknown artist" }, color = Color.White.copy(alpha = 0.68f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (language == "ar") "استمعتها $playCount مرة · سمعت منها ${formatDuration(trackListenSeconds * 1000L)}"
                        else "Played $playCount times · Listened ${formatDuration(trackListenSeconds * 1000L)}",
                        color = Color.White.copy(alpha = 0.58f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Slider(value = playbackPosition.coerceIn(0f, playbackDuration), onValueChange = { isSeeking = true; playbackPosition = it },
                onValueChangeFinished = { controller?.seekTo(playbackPosition.toLong()); isSeeking = false }, valueRange = 0f..playbackDuration,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(playbackPosition.toLong()), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                Text("-${formatDuration((playbackDuration-playbackPosition).toLong().coerceAtLeast(0L))}", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.height(18.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle }) {
                        Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffle) Accent else Color.White.copy(alpha = 0.58f), modifier = Modifier.size(23.dp))
                    }
                    IconButton(onClick = { controller?.let { if (it.currentPosition > 3000L) it.seekTo(0L) else it.seekToPreviousMediaItem() } }) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Box(Modifier.size(72.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(artworkTint, artworkTint.copy(alpha = 0.72f), Color.White.copy(alpha = 0.2f))))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        .clickable { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, contentAlignment = Alignment.Center) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(39.dp))
                    }
                    IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                        Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = {
                        repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                        controller?.repeatMode = repeatMode
                    }) {
                        Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            "Repeat", tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.58f) else Accent, modifier = Modifier.size(23.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { activePanel = "tools" }) {
                        Icon(Icons.Rounded.Tune, if (language == "ar") "إعدادات الصوت" else "Audio settings", tint = Color.White.copy(alpha = 0.72f))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { activePanel = "lyrics" }) {
                        Icon(Icons.Rounded.Subtitles, if (language == "ar") "كلمات الأغنية" else "Lyrics", tint = Color.White.copy(alpha = 0.72f))
                    }
                }
            }
            Spacer(Modifier.height(25.dp))
        }
    }
    if (activePanel == "lyrics") ModalBottomSheet(
        onDismissRequest = { activePanel = "none" },
        containerColor = CardBg,
        contentColor = TextPrimary
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 28.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (language == "ar") "كلمات الأغنية" else "LYRICS",
                        color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(media.title, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { lyricsPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }) {
                    Icon(Icons.Rounded.Subtitles, null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (language == "ar") "استيراد LRC" else "Import LRC")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (lyricLines.isEmpty()) {
                Text(
                    if (language == "ar") "لم تتم إضافة كلمات لهذه الأغنية بعد." else "No synced lyrics added for this song yet.",
                    color = Muted, modifier = Modifier.padding(vertical = 28.dp)
                )
            } else {
                val activeLine = lyricLines.indexOfLast { it.timeMs <= playbackPosition.toLong() }.coerceAtLeast(0)
                lyricLines.forEachIndexed { index, line ->
                    Text(
                        line.text,
                        color = if (index == activeLine) Accent else TextPrimary.copy(alpha = 0.68f),
                        fontSize = if (index == activeLine) 17.sp else 14.sp,
                        fontWeight = if (index == activeLine) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { controller?.seekTo(line.timeMs) }
                            .padding(vertical = 9.dp)
                    )
                }
            }
        }
    }
    if (activePanel == "tools") ModalBottomSheet(onDismissRequest = { activePanel = "none" }, containerColor = CardBg, contentColor = TextPrimary) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(if (language == "ar") "إعدادات الصوت" else "AUDIO SETTINGS", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(if (language == "ar") "سرعة التشغيل" else "Playback speed", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    FilterChip(selected = playbackSpeed == speed, onClick = { playbackSpeed = speed; controller?.setPlaybackSpeed(speed) }, label = { Text("${speed}×", fontSize = 10.sp) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (language == "ar") "مؤقت إيقاف التشغيل" else "Sleep timer", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(if (sleepTimerMinutes > 0) "${sleepTimerMinutes} min" else if (language == "ar") "غير مفعّل" else "Off", color = Muted, fontSize = 11.sp)
                }
                if (sleepTimerMinutes > 0) TextButton(onClick = { onSleepTimerChange(0) }) { Text(if (language == "ar") "إلغاء" else "Cancel") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(15,30,45,60).forEach { m -> FilterChip(selected = sleepTimerMinutes == m, onClick = { onSleepTimerChange(m) }, label = { Text("${m}m") }) }
            }
            if (controller != null) { Spacer(Modifier.height(16.dp)); EqualizerPanel(controller = controller, language = language) }
            OutlinedButton(onClick = { showRingtoneCutter = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.ContentCut, null)
                Spacer(Modifier.width(8.dp))
                Text(if (language == "ar") "قص جزء من الأغنية كنغمة" else "Trim song as ringtone")
            }
        }
    }
    if (activePanel == "queue") ModalBottomSheet(onDismissRequest = { activePanel = "none" }, containerColor = CardBg, contentColor = TextPrimary) {
        val firstUpcoming = currentQueueIndex.coerceIn(0, queue.size)
        val upcomingCount = (queue.size - firstUpcoming).coerceAtLeast(0)
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (language == "ar") "قائمة التشغيل" else "UP NEXT", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(if (language == "ar") "${upcomingCount} مقاطع في الانتظار" else "${upcomingCount} tracks in queue", color = Muted, fontSize = 11.sp)
                }
                if (upcomingCount > 1) TextButton(onClick = onClearQueue) { Text(if (language == "ar") "مسح التالي" else "Clear upcoming") }
            }
            Spacer(Modifier.height(10.dp))
            if (upcomingCount == 0) Text(if (language == "ar") "لا توجد أغانٍ تالية" else "Nothing queued next", color = Muted, modifier = Modifier.padding(vertical = 24.dp))
            else for (index in firstUpcoming until queue.size) {
                val item = queue[index]
                val current = index == currentQueueIndex
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (current) CardAlt else Color.Transparent)
                    .clickable { controller?.seekTo(index, 0L) }.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaArtwork(item, Modifier.size(43.dp).clip(RoundedCornerShape(9.dp))); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = if (current) Accent else TextPrimary, fontSize = 12.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (current) { if (language == "ar") "يعمل الآن" else "Now playing" } else "${item.artist.ifBlank { "Unknown artist" }} · ${formatDuration(item.durationMs)}", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(enabled = index > firstUpcoming + 1, onClick = { onMoveQueueItem(index,index-1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, null, tint = if (index > firstUpcoming + 1) TextPrimary else Muted) }
                    IconButton(enabled = index > firstUpcoming && index < queue.lastIndex, onClick = { onMoveQueueItem(index,index+1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = if (index > firstUpcoming && index < queue.lastIndex) TextPrimary else Muted) }
                    IconButton(enabled = !current, onClick = { onRemoveQueueItem(index) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Remove", tint = if (current) Muted.copy(alpha = 0.35f) else Muted) }
                }
            }
        }
    }
    if (showRingtoneCutter) RingtoneCutterDialog(context, media, controller, playbackDuration.toLong(), language) { showRingtoneCutter = false }

}

private fun dominantArtworkColor(bitmap: Bitmap?): Color {
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return Color(0xFF2E9C83)
    var r = 0L; var g = 0L; var b = 0L; var count = 0L
    val sx = (bitmap.width / 28).coerceAtLeast(1); val sy = (bitmap.height / 28).coerceAtLeast(1)
    for (y in 0 until bitmap.height step sy) for (x in 0 until bitmap.width step sx) {
        val p = bitmap.getPixel(x,y)
        if (android.graphics.Color.alpha(p) < 180) continue
        val rr = android.graphics.Color.red(p); val gg = android.graphics.Color.green(p); val bb = android.graphics.Color.blue(p)
        val hsv = FloatArray(3); android.graphics.Color.RGBToHSV(rr,gg,bb,hsv)
        if (hsv[1] < 0.18f || hsv[2] < 0.16f || hsv[2] > 0.96f) continue
        r += rr; g += gg; b += bb; count++
    }
    if (count == 0L) return Color(0xFF2E9C83)
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((r/count).toInt(),(g/count).toInt(),(b/count).toInt(),hsv)
    hsv[1] = (hsv[1]*1.16f).coerceIn(0.25f,0.78f); hsv[2] = (hsv[2]*0.78f).coerceIn(0.28f,0.72f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
private fun SettingsScreen(
    modifier: Modifier, wallpaperUri: Uri?, compactCards: Boolean, cardStyle: String, listenedSeconds: Long, themeMode: String, accentName: String, pureBlack: Boolean,
    onThemeChange: (String) -> Unit, onPureBlackChange: (Boolean) -> Unit, onAccentChange: (String) -> Unit, onChooseWallpaper: () -> Unit, onClearWallpaper: () -> Unit,
    onCompactChange: (Boolean) -> Unit, onCardStyleChange: (String) -> Unit,
    pinEnabled: Boolean, onEnablePin: () -> Unit, onChangePin: () -> Unit, onDisablePin: () -> Unit,
    language: String, onLanguageChange: (String) -> Unit,
    wallpaperDim: Float, onWallpaperDimChange: (Float) -> Unit,
    audioOutputs: List<String>
) {
    var settingsCategory by remember { mutableStateOf("All") }
    var showAccentPicker by remember { mutableStateOf(false) }
    val settingsCategories = listOf("Appearance", "Audio", "Privacy", "Language", "Stats", "All")
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(if (language == "ar") "كل إعداد في مكانه. اختر القسم الذي تريد تخصيصه." else "Everything has its place. Choose what you want to customize.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(13.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            settingsCategories.forEach { category ->
                val label = if (language == "ar") when (category) {
                    "Appearance" -> "المظهر"
                    "Audio" -> "الصوت"
                    "Privacy" -> "الخصوصية"
                    "Language" -> "اللغة"
                    "Stats" -> "الإحصائيات"
                    else -> "الكل"
                } else category
                FilterChip(
                    selected = settingsCategory == category,
                    onClick = { settingsCategory = category },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (settingsCategory == "All" || settingsCategory == "Privacy") {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(CardBg, CardAlt.copy(alpha = 0.48f)))).border(1.dp, Color.White.copy(alpha = 0.055f), RoundedCornerShape(22.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (language == "ar") "قفل التطبيق" else "App lock", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (pinEnabled) (if (language == "ar") "القفل مفعّل — سيُطلب الرمز عند العودة للتطبيق." else "Enabled — PIN is required when you return to the app.")
                        else (if (language == "ar") "احمِ التطبيق برمز من 4 إلى 8 أرقام." else "Protect the app with a 4–8 digit PIN."),
                        color = Muted, fontSize = 11.sp
                    )
                }
                if (pinEnabled) Switch(checked = true, onCheckedChange = { onDisablePin() })
                else Switch(checked = false, onCheckedChange = { onEnablePin() })
            }
            if (pinEnabled) {
                TextButton(onClick = onChangePin) { Text(if (language == "ar") "تغيير الرمز" else "Change PIN") }
            }
        }
        }
        if (settingsCategory == "All" || settingsCategory == "Language") {
        Spacer(Modifier.height(24.dp))
        Text(if (language == "ar") "اللغة" else "LANGUAGE", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Text(if (language == "ar") "لغة التطبيق" else "App language", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = language == "en", onClick = { onLanguageChange("en") }, label = { Text("English") })
                FilterChip(selected = language == "ar", onClick = { onLanguageChange("ar") }, label = { Text("العربية") })
            }
        }
        }
        if (settingsCategory == "All" || settingsCategory == "Audio") {
        Spacer(Modifier.height(18.dp))
        Text(if (language == "ar") "الصوت والأجهزة" else "AUDIO & DEVICES", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Headphones, null, tint = Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (language == "ar") "مخارج الصوت المتاحة" else "Available audio outputs", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (language == "ar") "تتحدث القائمة تلقائيًا عند توصيل أو فصل سماعة." else "Updates automatically when a headset connects or disconnects.",
                        color = Muted, fontSize = 10.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (audioOutputs.isEmpty()) {
                Text(if (language == "ar") "لا توجد مخارج صوت متاحة حاليًا." else "No audio outputs are currently available.", color = Muted, fontSize = 11.sp)
            } else {
                audioOutputs.forEach { output ->
                    val isBluetooth = output.contains("Bluetooth", ignoreCase = true) || output.contains("بلوتوث")
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isBluetooth) Icons.Rounded.Bluetooth else Icons.Rounded.VolumeUp,
                            null, tint = if (isBluetooth) Accent else Muted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(output, color = TextPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
        }
        if (settingsCategory == "All" || settingsCategory == "Appearance") {
        Spacer(Modifier.height(18.dp))
        Text(if (language == "ar") "المظهر" else "APPEARANCE", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Text(if (language == "ar") "نمط الألوان" else "Color theme", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (language == "ar") "اختر المظهر المناسب لك." else "Choose the look that feels right.", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = themeMode == "dark", onClick = { onThemeChange("dark") }, label = { Text(if (language == "ar") "داكن" else "Dark") }, leadingIcon = { Icon(Icons.Rounded.DarkMode, null) })
                FilterChip(selected = themeMode == "light", onClick = { onThemeChange("light") }, label = { Text(if (language == "ar") "فاتح" else "Light") }, leadingIcon = { Icon(Icons.Rounded.LightMode, null) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (language == "ar") "أسود AMOLED الحقيقي" else "True AMOLED black", color = TextPrimary, fontSize = 13.sp)
                    Text(if (language == "ar") "خلفية سوداء خالصة مع أسطح داكنة — متاح في الوضع الداكن." else "Pure black background with deep surfaces. Available in Dark mode.", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = pureBlack, onCheckedChange = onPureBlackChange, enabled = themeMode == "dark")
            }
            HorizontalDivider(color = CardAlt)
            Text(if (language == "ar") "اللون الأساسي" else "Accent color", color = TextPrimary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            val accentChoices = listOf(
                "mint" to ("Mint" to Color(0xFF6EE7B7)),
                "ocean" to ("Ocean" to Color(0xFF7DD3FC)),
                "violet" to ("Violet" to Color(0xFFC18BFF)),
                "rose" to ("Rose" to Color(0xFFFDA4AF)),
                "amber" to ("Amber" to Color(0xFFFCD34D)),
                "cyan" to ("Cyan" to Color(0xFF67E8F9)),
                "indigo" to ("Indigo" to Color(0xFFA5B4FC)),
                "orange" to ("Orange" to Color(0xFFFDBA74))
            )
            Surface(
                onClick = { showAccentPicker = true },
                shape = RoundedCornerShape(14.dp),
                color = CardAlt,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(25.dp).clip(CircleShape).background(accentChoices.firstOrNull { it.first == accentName }?.second?.second ?: Accent))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (language == "ar") "اللون الحالي" else "Current accent", color = Muted, fontSize = 10.sp)
                        Text(accentChoices.firstOrNull { it.first == accentName }?.second?.first ?: "Mint", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Icon(Icons.Rounded.Palette, null, tint = Accent)
                    Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Text(if (language == "ar") "خلفية التطبيق" else "App background", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(if (language == "ar") "اختر صورة من المعرض واضبط وضوحها." else "Choose a picture from your gallery and adjust its visibility.", color = Muted, fontSize = 11.sp)
            if (wallpaperUri != null) {
                Spacer(Modifier.height(12.dp))
                AsyncImage(model = wallpaperUri, contentDescription = "Selected background", modifier = Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = onChooseWallpaper) { Text(if (language == "ar") "اختيار من المعرض" else "Choose from gallery") }
                if (wallpaperUri != null) OutlinedButton(onClick = onClearWallpaper) { Text(if (language == "ar") "إزالة" else "Remove") }
            }
            if (wallpaperUri != null) {
                Spacer(Modifier.height(10.dp))
                Text(if (language == "ar") "تعتيم الخلفية" else "Background dim", color = TextPrimary, fontSize = 12.sp)
                Slider(value = wallpaperDim, onValueChange = onWallpaperDimChange, valueRange = 0f..0.75f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                Text("${(wallpaperDim * 100).toInt()}%", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Text(if (language == "ar") "بطاقات الوسائط" else "Media cards", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (language == "ar") "بطاقات مضغوطة" else "Compact cards", color = TextPrimary, fontSize = 13.sp)
                    Text(if (language == "ar") "اعرض عددًا أكبر من الأغاني والفيديوهات." else "Show more songs and videos on screen.", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = compactCards, onCheckedChange = onCompactChange)
            }
            HorizontalDivider(color = CardAlt)
            Text(if (language == "ar") "شكل زوايا البطاقات" else "Card corners", color = TextPrimary, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = cardStyle == "rounded", onClick = { onCardStyleChange("rounded") }, label = { Text(if (language == "ar") "دائري" else "Rounded") })
                FilterChip(selected = cardStyle == "square", onClick = { onCardStyleChange("square") }, label = { Text(if (language == "ar") "مربع" else "Square") })
            }
        }
        }
        if (settingsCategory == "All" || settingsCategory == "Stats") {
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Text(if (language == "ar") "إجمالي وقت التشغيل" else "Total playback time", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(formatDuration(listenedSeconds * 1000L), color = Accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(if (language == "ar") "إجمالي الوقت الذي شغّل فيه Viora الوسائط على هذا الجهاز." else "Total time Viora has spent playing media on this device.", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                if (language == "ar") "يستمر تشغيل الموسيقى في الخلفية، ويمكنك استخدام صورة داخل صورة للفيديو."
                else "Video picture-in-picture is available while music continues in the background.",
                color = Muted, fontSize = 11.sp
            )
        }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            if (language == "ar") "تم تصميم وتطوير التطبيق من قبل فريق Novra" else "Designed and developed by the Novra team",
            color = Muted.copy(alpha = 0.72f), fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
    }
    if (showAccentPicker) {
        AlertDialog(
            onDismissRequest = { showAccentPicker = false },
            title = { Text(if (language == "ar") "اختر اللون الأساسي" else "Choose accent color") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    accentChoices.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (key, choice) ->
                                val (label, color) = choice
                                Surface(
                                    onClick = { onAccentChange(key); showAccentPicker = false },
                                    shape = RoundedCornerShape(13.dp),
                                    color = if (accentName == key) color.copy(alpha = 0.20f) else CardAlt,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(22.dp).clip(CircleShape).background(color))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (language == "ar") when(key) { "mint" -> "نعناعي"; "ocean" -> "محيطي"; "violet" -> "بنفسجي"; "rose" -> "وردي"; "amber" -> "عنبر"; "cyan" -> "سماوي"; "indigo" -> "نيلي"; else -> "برتقالي" } else label, color = TextPrimary, fontSize = 11.sp, fontWeight = if (accentName == key) FontWeight.Bold else FontWeight.Medium)
                                        if (accentName == key) Spacer(Modifier.weight(1f))
                                        if (accentName == key) Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAccentPicker = false }) { Text(if (language == "ar") "إغلاق" else "Close") } }
        )
    }
}

@Composable
private fun PlaylistsScreen(
    modifier: Modifier,
    playlists: List<UserPlaylist>,
    media: List<LocalMedia>,
    onCreate: (String) -> Unit,
    onAdd: (String, LocalMedia) -> Unit,
    onRemove: (String, LocalMedia) -> Unit,
    onDelete: (String) -> Unit,
    onPlay: (List<LocalMedia>) -> Unit,
    onAddToQueue: (LocalMedia) -> Unit,
    onPlayNext: (LocalMedia) -> Unit,
    language: String = "en"
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var addToPlaylist by remember { mutableStateOf<String?>(null) }
    val resolved = playlists.associateWith { playlist ->
        playlist.mediaKeys.mapNotNull { key -> media.firstOrNull { mediaKey(it) == key } }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (language == "ar") "قوائم التشغيل" else "Playlists", color = TextPrimary, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Text(if (language == "ar") "${playlists.size} قائمة تشغيل" else "${playlists.size} personal playlists", color = Muted, fontSize = 12.sp)
            }
            Button(onClick = { newName = ""; showCreate = true }) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(5.dp))
                Text(if (language == "ar") "إنشاء" else "Create")
            }
        }
        Spacer(Modifier.height(20.dp))
        if (playlists.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.QueueMusic, null, tint = Accent, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (language == "ar") "ابدأ بإنشاء أول قائمة" else "Your playlists start here", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(if (language == "ar") "أنشئ قائمة ثم أضف إليها الأغاني أو الفيديوهات من جهازك." else "Create a playlist, then add songs or videos from your device.", color = Muted, fontSize = 12.sp)
            }
        }
        playlists.forEach { playlist ->
            val items = resolved[playlist].orEmpty()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.QueueMusic, null, tint = Accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (language == "ar") "${items.size} عناصر" else "${items.size} items", color = Muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { addToPlaylist = playlist.name }) { Icon(Icons.Rounded.Add, "Add media", tint = Accent) }
                    IconButton(onClick = { onDelete(playlist.name) }) { Icon(Icons.Rounded.DeleteOutline, "Delete playlist", tint = Muted) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPlay(items) }, enabled = items.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Text(if (language == "ar") "تشغيل" else "Play")
                    }
                    OutlinedButton(onClick = { addToPlaylist = playlist.name }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Add, null)
                        Text(if (language == "ar") "إضافة وسائط" else "Add media")
                    }
                }
                if (items.isNotEmpty()) {
                    Spacer(Modifier.height(9.dp))
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                                .background(CardAlt.copy(alpha = 0.42f)).padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MediaArtwork(item, Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = TextPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (language == "ar") if (item.isVideo) "فيديو" else "صوت" else if (item.isVideo) "Video" else "Audio", color = Muted, fontSize = 9.sp)
                            }
                            IconButton(onClick = { onPlayNext(item) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Rounded.SkipNext, if (language == "ar") "تشغيل بعد المقطع الحالي" else "Play next", tint = Accent, modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { onAddToQueue(item) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Rounded.QueueMusic, if (language == "ar") "إضافة إلى آخر الطابور" else "Add to queue", tint = Muted, modifier = Modifier.size(17.dp))
                            }
                            IconButton(onClick = { onRemove(playlist.name, item) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Rounded.Close, if (language == "ar") "إزالة من القائمة" else "Remove from playlist", tint = Muted, modifier = Modifier.size(17.dp))
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(if (language == "ar") "إنشاء قائمة تشغيل" else "Create playlist") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text(if (language == "ar") "اسم القائمة" else "Playlist name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty() && playlists.none { it.name.equals(name, ignoreCase = true) }) onCreate(name)
                    showCreate = false
                }) { Text(if (language == "ar") "إنشاء" else "Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(if (language == "ar") "إلغاء" else "Cancel") } }
        )
    }
    addToPlaylist?.let { playlistName ->
        AlertDialog(
            onDismissRequest = { addToPlaylist = null },
            title = { Text(if (language == "ar") "إضافة إلى $playlistName" else "Add to $playlistName") },
            text = {
                if (media.isEmpty()) Text(if (language == "ar") "لا توجد وسائط على الجهاز. امنح التطبيق إذن الوصول إلى الملفات ثم حدّث المكتبة." else "No device media found yet. Grant media access and scan your library first.")
                else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    val existingKeys = playlists.firstOrNull { it.name == playlistName }?.mediaKeys.orEmpty().toSet()
                    media.forEach { item ->
                        val alreadyAdded = mediaKey(item) in existingKeys
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !alreadyAdded) { onAdd(playlistName, item) }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MediaArtwork(item, Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = if (alreadyAdded) Muted else TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (language == "ar") if (item.isVideo) "فيديو" else "صوت" else if (item.isVideo) "Video" else "Audio", color = Muted, fontSize = 10.sp)
                            }
                            Icon(if (alreadyAdded) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, null, tint = if (alreadyAdded) Muted else Accent)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addToPlaylist = null }) { Text(if (language == "ar") "تم" else "Done") } }
        )
    }
}


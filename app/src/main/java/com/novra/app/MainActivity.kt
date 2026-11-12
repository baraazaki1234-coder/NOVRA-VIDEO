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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.util.Size
import android.util.LruCache
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private val mediaArtworkCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

private data class NovraPalette(
    val background: Color, val surface: Color, val surfaceVariant: Color,
    val muted: Color, val accent: Color, val text: Color
)
private val DarkPalette = NovraPalette(
    Color(0xFF101310), Color(0xFF1D241F), Color(0xFF2A332D),
    Color(0xFFA7B0A8), Color(0xFFC7E8D1), Color(0xFFFFFFFF)
)
private val LightPalette = NovraPalette(
    Color(0xFFF3F6F3), Color(0xFFFFFFFF), Color(0xFFE5ECE7),
    Color(0xFF66736A), Color(0xFF286B47), Color(0xFF17221A)
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
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
private fun NovraApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selected by remember { mutableStateOf("Home") }
    var navigationStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var showExitDialog by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("novra_preferences", android.content.Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf(prefs.getString("language", "en") ?: "en") }
    var wallpaperUri by remember { mutableStateOf(prefs.getString("wallpaper_uri", null)?.let(Uri::parse)) }
    var compactCards by remember { mutableStateOf(prefs.getBoolean("compact_cards", false)) }
    var cardStyle by remember { mutableStateOf(prefs.getString("card_style", "rounded") ?: "rounded") }
    var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "dark") ?: "dark") }
    var wallpaperDim by remember { mutableFloatStateOf(prefs.getFloat("wallpaper_dim", 0.22f)) }
    var accentName by remember { mutableStateOf(prefs.getString("accent_name", "mint") ?: "mint") }
    val isLightTheme = themeMode == "light"
    val chosenAccent = when (accentName) {
        "ocean" -> if (isLightTheme) Color(0xFF0369A1) else Color(0xFF7DD3FC)
        "violet" -> if (isLightTheme) Color(0xFF6D28D9) else Color(0xFFC4B5FD)
        "rose" -> if (isLightTheme) Color(0xFFBE123C) else Color(0xFFFDA4AF)
        "amber" -> if (isLightTheme) Color(0xFFB45309) else Color(0xFFFCD34D)
        "cyan" -> if (isLightTheme) Color(0xFF0E7490) else Color(0xFF67E8F9)
        "indigo" -> if (isLightTheme) Color(0xFF4338CA) else Color(0xFFA5B4FC)
        "orange" -> if (isLightTheme) Color(0xFFC2410C) else Color(0xFFFDBA74)
        else -> if (isLightTheme) Color(0xFF286B47) else Color(0xFFC7E8D1)
    }
    val palette = (if (isLightTheme) LightPalette else DarkPalette).copy(accent = chosenAccent)
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
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    LaunchedEffect(sleepTimerMinutes, controller) {
        if (sleepTimerMinutes > 0) {
            delay(sleepTimerMinutes * 60_000L)
            controller?.pause()
            sleepTimerMinutes = 0
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(isPlaying, selectedMedia) {
        while (isPlaying && selectedMedia != null) {
            delay(1000)
            listenedSeconds += 1
            if (listenedSeconds % 15L == 0L) prefs.edit().putLong("listened_seconds", listenedSeconds).apply()
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
                .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setArtist(entry.artist).build())
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
    fun startMedia(item: LocalMedia, queue: List<LocalMedia>) {
        selectedMedia = item
        playQueue = queue
        showNowPlaying = true
        val key = "play_count_${if (item.isVideo) "video" else "audio"}_${item.id}"
        val count = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, count).apply()
        playCounts[key] = count
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
    LaunchedEffect(selectedMedia, controller) {
        val media = selectedMedia
        val player = controller
        if (media != null && player != null) {
            val queue = playQueue.ifEmpty { listOf(media) }
            val startIndex = queue.indexOfFirst { it.id == media.id && it.isVideo == media.isVideo }.coerceAtLeast(0)
            val items = queue.map { entry ->
                MediaItem.Builder()
                    .setUri(entry.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setArtist(entry.artist).build())
                    .build()
            }
            player.setMediaItems(items, startIndex, 0L)
            player.prepare()
            player.play()
        }
    }
    var videos by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var hasPermission by remember {
        mutableStateOf(mediaPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    var loading by remember { mutableStateOf(false) }
    var mediaRefresh by remember { mutableIntStateOf(0) }
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
            if (event == Lifecycle.Event.ON_RESUME) mediaRefresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = mediaPermissions().all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(hasPermission, mediaRefresh) {
        if (hasPermission) {
            delay(300)
            loading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { MediaLibrary.videos(context) to MediaLibrary.music(context) }
                }
                result.onSuccess { (v, a) -> videos = v; tracks = a }
            } finally {
                loading = false
            }
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
            if (showExitDialog) AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text(if (language == "ar") "الخروج من NOVRA؟" else "Exit NOVRA?") },
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
                                    .background(Brush.linearGradient(listOf(Color(0xFF385A49), Color(0xFF17271F))))
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
                                        Text("NOVRA", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 2.5.sp)
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
                                Triple("Premium", Icons.Rounded.WorkspacePremium, "NOVRA Premium"),
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
                            Text("NOVRA · Play your world", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp))
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
                            Column(
                                Modifier.fillMaxWidth().background(CardBg.copy(alpha = 0.97f))
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (showNowPlaying) {
                                        IconButton(onClick = { showNowPlaying = false }) {
                                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                                        }
                                    } else {
                                        Box(
                                            Modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
                                                .background(CardAlt),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Rounded.GraphicEq, contentDescription = "NOVRA", tint = Accent, modifier = Modifier.size(23.dp))
                                        }
                                    }
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(
                                            if (showNowPlaying) {
                                                if (selectedMedia?.isVideo == true) (if (language == "ar") "مشغل الفيديو" else "Video player")
                                                else (if (language == "ar") "يعمل الآن" else "Now Playing")
                                            } else if (selected == "Home") "NOVRA" else if (language == "ar") when (selected) {
                                                "Videos" -> "الفيديوهات"
                                                "Music" -> "الأغاني"
                                                "Playlists" -> "قوائم التشغيل"
                                                "Explore" -> "استكشف"
                                                "Favorites" -> "المفضلة"
                                                "Settings" -> "الإعدادات"
                                                "Premium" -> "NOVRA Premium"
                                                else -> selected
                                            } else selected,
                                            color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (showNowPlaying) {
                                                if (selectedMedia?.isVideo == true) (if (language == "ar") "شاهد الفيديو وتحكم في التشغيل" else "Watch and control your video")
                                                else (if (language == "ar") "الوسائط الحالية" else "Your current media")
                                            } else if (language == "ar") "مساحتك للوسائط" else "Your personal media space",
                                            color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!showNowPlaying) {
                                        IconButton(onClick = { navigateTo("Settings") }) {
                                            Icon(Icons.Rounded.Tune, contentDescription = "Settings", tint = if (selected == "Settings") Accent else Muted)
                                        }
                                    }
                                }
                                if (!showNowPlaying && selected in topDestinations) {
                                    val tabIndex = topDestinations.indexOf(selected).coerceAtLeast(0)
                                    ScrollableTabRow(
                                        selectedTabIndex = tabIndex,
                                        containerColor = CardBg,
                                        contentColor = Accent,
                                        edgePadding = 12.dp,
                                        divider = { HorizontalDivider(color = CardAlt, thickness = 1.dp) }
                                    ) {
                                        topDestinations.forEachIndexed { index, destination ->
                                            Tab(
                                                selected = tabIndex == index,
                                                onClick = {
                                                    selected = destination
                                                    showNowPlaying = false
                                                },
                                                text = {
                                                    Text(
                                                        if (language == "ar") when (destination) {
                                                            "Home" -> "الرئيسية"
                                                            "Videos" -> "الفيديوهات"
                                                            "Music" -> "الأغاني"
                                                            "Playlists" -> "قوائمي"
                                                            "Explore" -> "استكشف"
                                                            else -> "المفضلة"
                                                        } else destination,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (tabIndex == index) FontWeight.SemiBold else FontWeight.Medium
                                                    )
                                                },
                                                selectedContentColor = Accent,
                                                unselectedContentColor = Muted
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        bottomBar = {
                            if (selectedMedia != null && !showNowPlaying) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                                        .clip(RoundedCornerShape(17.dp)).background(CardAlt)
                                        .clickable { showNowPlaying = true }
                                        .padding(start = 11.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (selectedMedia?.isVideo == true) Icons.Rounded.PlayCircle else Icons.Rounded.GraphicEq,
                                        null, tint = Accent, modifier = Modifier.size(25.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(selectedMedia?.title.orEmpty(), color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                        Text(if (language == "ar") "يعمل الآن · اضغط للفتح" else "NOW PLAYING · TAP TO OPEN", color = Muted, fontSize = 9.sp)
                                    }
                                    IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                                        Icon(if (controller?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = TextPrimary)
                                    }
                                    IconButton(onClick = { controller?.stop(); selectedMedia = null }) {
                                        Icon(Icons.Rounded.Close, null, tint = Muted)
                                    }
                                }
                            }
                        }
                    ) { padding ->
                        if (showNowPlaying && selectedMedia != null) {
                            NowPlayingScreen(
                                Modifier.padding(padding), selectedMedia!!, controller, playQueue,
                                sleepTimerMinutes, onSleepTimerChange = { sleepTimerMinutes = it },
                                onMoveQueueItem = ::moveQueueItem, onRemoveQueueItem = ::removeQueueItem,
                                onBack = { showNowPlaying = false }, language = language
                            )
                        } else when (selected) {
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
                                Modifier.padding(padding), "Videos", videos, loading, hasPermission,
                                onGrant = { permissionLauncher.launch(mediaPermissions()) },
                                compactCards = compactCards, cardStyle = cardStyle,
                                playCounts = playCounts, favorites = favorites,
                                onToggleFavorite = ::toggleFavorite,
                                onPlay = { item -> startMedia(item, videos) },
                                language = language
                            )
                            "Music" -> MediaScreen(
                                Modifier.padding(padding), "Music", tracks, loading, hasPermission,
                                onGrant = { permissionLauncher.launch(mediaPermissions()) },
                                compactCards = compactCards, cardStyle = cardStyle,
                                playCounts = playCounts, favorites = favorites,
                                onToggleFavorite = ::toggleFavorite,
                                onPlay = { item -> startMedia(item, tracks) },
                                language = language
                            )
                            "Explore" -> OnlineSearchScreen(language = language)
                            "Favorites" -> MediaScreen(
                                Modifier.padding(padding), "Favorites", (videos + tracks).distinctBy { mediaKey(it) }, loading, hasPermission,
                                onGrant = { permissionLauncher.launch(mediaPermissions()) },
                                compactCards = compactCards, cardStyle = cardStyle,
                                playCounts = playCounts, favorites = favorites,
                                onToggleFavorite = ::toggleFavorite,
                                onPlay = { item -> startMedia(item, (videos + tracks).distinctBy { mediaKey(it) }) },
                                language = language
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
                                onDelete = { playlistName -> savePlaylists(playlists.filterNot { it.name == playlistName }) },
                                onPlay = { items -> if (items.isNotEmpty()) startMedia(items.first(), items) }, language = language
                            )
                            "Premium" -> PremiumScreen(
                                Modifier.padding(padding),
                                transactionReference = prefs.getString("premium_transaction_reference", "") ?: "",
                                pendingReview = prefs.getBoolean("premium_pending_review", false),
                                onReferenceChange = { prefs.edit().putString("premium_transaction_reference", it).apply() },
                                onSubmit = { reference ->
                                    prefs.edit().putString("premium_transaction_reference", reference)
                                        .putBoolean("premium_pending_review", true).apply()
                                }
                            )
                            else -> SettingsScreen(
                                Modifier.padding(padding), wallpaperUri, compactCards, cardStyle, listenedSeconds, themeMode, accentName,
                                onOpenPremium = { navigateTo("Premium") },
                                onThemeChange = { themeMode = it; prefs.edit().putString("theme_mode", it).apply() },
                                onAccentChange = { accentName = it; prefs.edit().putString("accent_name", it).apply() },
                                onChooseWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                                onClearWallpaper = { wallpaperUri = null; prefs.edit().remove("wallpaper_uri").apply() },
                                onCompactChange = { compactCards = it; prefs.edit().putBoolean("compact_cards", it).apply() },
                                onCardStyleChange = { cardStyle = it; prefs.edit().putString("card_style", it).apply() },
                                 language = language,
                                 onLanguageChange = { language = it; prefs.edit().putString("language", it).apply() },
                                 wallpaperDim = wallpaperDim,
                                 onWallpaperDimChange = { wallpaperDim = it; prefs.edit().putFloat("wallpaper_dim", it).apply() }
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun OnlineSearchScreen(language: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("web") }
    var submittedUrl by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    canGoBack = view.canGoBack()
                    canGoForward = view.canGoForward()
                }
            }
            webChromeClient = WebChromeClient()
            setBackgroundColor(android.graphics.Color.rgb(16, 19, 16))
        }
    }
    DisposableEffect(webView) {
        onDispose { webView.stopLoading(); webView.destroy() }
    }
    fun submitSearch() {
        val clean = query.trim()
        if (clean.isBlank()) return
        val encoded = Uri.encode(clean)
        submittedUrl = when (provider) {
            "youtube" -> "https://m.youtube.com/results?search_query=$encoded"
            "spotify" -> "https://open.spotify.com/search/$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
    }
    LaunchedEffect(submittedUrl) {
        submittedUrl?.let { url -> if (webView.url != url) webView.loadUrl(url) }
    }
    BackHandler(enabled = canGoBack) { webView.goBack() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (language == "ar") "استكشف" else "Explore", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text(if (language == "ar") "ابحث واكتشف من داخل NOVRA" else "Discover without leaving NOVRA", color = Muted, fontSize = 12.sp)
            }
            if (submittedUrl != null) {
                IconButton(onClick = { webView.reload() }) { Icon(Icons.Rounded.Refresh, if (language == "ar") "تحديث" else "Refresh", tint = Muted) }
            }
        }
        Spacer(Modifier.height(13.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(17.dp),
            placeholder = { Text(if (language == "ar") "فنان، أغنية، ألبوم أو فيديو..." else "Artist, song, album or video...", color = Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Accent) },
            trailingIcon = {
                if (query.isNotBlank()) IconButton(onClick = { query = ""; submittedUrl = null }) {
                    Icon(Icons.Rounded.Close, if (language == "ar") "مسح" else "Clear", tint = Muted)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = CardAlt,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent
            )
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("web" to (if (language == "ar") "الويب" else "Web"), "youtube" to "YouTube", "spotify" to "Spotify").forEach { (key, label) ->
                FilterChip(
                    selected = provider == key,
                    onClick = { provider = key },
                    label = { Text(label, fontSize = 11.sp) },
                    leadingIcon = if (provider == key) ({ Icon(Icons.Rounded.Check, null, modifier = Modifier.size(15.dp)) }) else null
                )
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = ::submitSearch, enabled = query.isNotBlank(), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp)) {
                Icon(Icons.Rounded.Search, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (language == "ar") "بحث" else "Search")
            }
        }
        Spacer(Modifier.height(10.dp))
        if (submittedUrl == null) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(CardBg), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(CardAlt), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Explore, null, tint = Accent, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(if (language == "ar") "اكتشف حاجات جديدة" else "Find something new", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (language == "ar") "اكتب اسم الفنان أو الأغنية، واختر المكان اللي تحب تبحث فيه. النتائج هتظهر هنا داخل التطبيق." else "Search for an artist or a track. Results will appear here inside the app.",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (canGoBack) webView.goBack() }, enabled = canGoBack) { Icon(Icons.Rounded.ArrowBack, null); Text(if (language == "ar") "رجوع" else "Back") }
                TextButton(onClick = { if (canGoForward) webView.goForward() }, enabled = canGoForward) { Icon(Icons.Rounded.ArrowForward, null); Text(if (language == "ar") "التالي" else "Forward") }
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            )
        }
    }
}


@Composable
private fun EqualizerPanel(controller: MediaController?, language: String) {
    var sessionId by remember { mutableIntStateOf(NovraPlaybackService.currentAudioSessionId) }
    LaunchedEffect(controller) {
        while (sessionId == 0) {
            delay(300)
            sessionId = NovraPlaybackService.currentAudioSessionId
        }
    }
    val equalizer = remember(sessionId) {
        if (sessionId > 0) runCatching { Equalizer(0, sessionId).apply { enabled = true } }.getOrNull() else null
    }
    DisposableEffect(equalizer) {
        onDispose { runCatching { equalizer?.release() } }
    }
    var expanded by remember { mutableStateOf(false) }
    var levels by remember(sessionId) { mutableStateOf(List(5) { 0f }) }
    val range = remember(equalizer) {
        equalizer?.bandLevelRange?.let { it[0].toFloat() / 100f..it[1].toFloat() / 100f } ?: (-12f..12f)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (language == "ar") "معادل الصوت" else "AUDIO EQUALIZER", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (equalizer == null) {
                        if (language == "ar") "غير متاح في جلسة الصوت الحالية" else "Unavailable for this audio session"
                    } else {
                        if (language == "ar") "تحكم في ترددات الصوت مباشرة" else "Tune playback frequencies"
                    },
                    color = Muted, fontSize = 10.sp
                )
            }
            TextButton(onClick = { expanded = !expanded }, enabled = equalizer != null) {
                Text(if (expanded) (if (language == "ar") "إخفاء" else "Hide") else (if (language == "ar") "فتح" else "Open"))
            }
        }
        if (expanded && equalizer != null) {
            val bandCount = equalizer.numberOfBands.toInt().coerceAtMost(5)
            LaunchedEffect(equalizer, bandCount) {
                levels = List(bandCount) { index ->
                    runCatching { equalizer.getBandLevel(index.toShort()).toFloat() / 100f }.getOrDefault(0f)
                }
            }
            repeat(bandCount) { index ->
                val frequency = runCatching { equalizer.getCenterFreq(index.toShort()) / 1000 }.getOrDefault(0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (frequency >= 1000) "${frequency / 1000}k" else "${frequency}Hz", color = Muted, fontSize = 10.sp, modifier = Modifier.width(42.dp))
                    Slider(
                        value = levels.getOrElse(index) { 0f }.coerceIn(range.start, range.endInclusive),
                        onValueChange = { value ->
                            runCatching { equalizer.setBandLevel(index.toShort(), (value * 100).toInt().toShort()) }
                            levels = levels.toMutableList().also { it[index] = value }
                        },
                        valueRange = range,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
                    )
                    Text("${levels.getOrElse(index) { 0f }.toInt()} dB", color = Muted, fontSize = 9.sp, modifier = Modifier.width(42.dp))
                }
            }
            TextButton(onClick = {
                runCatching { equalizer.usePreset(0.toShort()) }
                levels = List(bandCount) { 0f }
            }) { Text(if (language == "ar") "إعادة ضبط" else "Reset") }
        }
    }
}

@Composable
private fun VideoNowPlayingScreen(
    modifier: Modifier,
    media: LocalMedia,
    controller: MediaController?,
    position: Float,
    duration: Float,
    onBack: () -> Unit,
    language: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var subtitleUri by remember(media.id) { mutableStateOf<Uri?>(null) }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            subtitleUri = uri
            val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType("application/x-subrip")
                .setLanguage("und")
                .setLabel("NOVRA subtitle")
                .build()
            val item = MediaItem.Builder().setUri(media.uri)
                .setSubtitleConfigurations(listOf(subtitle)).build()
            controller?.let { player ->
                val wasPlaying = player.isPlaying
                player.setMediaItem(item, position.toLong())
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
            if (player != null && player.isPlaying && player.currentPosition >= loopEnd) {
                player.seekTo(loopStart)
            }
            delay(180)
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Spacer(Modifier.height(6.dp))
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(15.dp)),
            factory = { ctx -> PlayerView(ctx).apply {
                useController = true
                controllerAutoShow = true
                controllerShowTimeoutMs = 3500
                keepScreenOn = true
            } },
            update = { view -> view.player = controller }
        )
        Spacer(Modifier.height(16.dp))
        Text(media.title, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (language == "ar") "فيديو من مكتبتك" else "Video from your library", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Slider(
            value = position.coerceIn(0f, duration.coerceAtLeast(1f)),
            onValueChange = { controller?.seekTo(it.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1f),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = CardAlt)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(position.toLong()), color = Muted, fontSize = 11.sp)
            Text(formatDuration(duration.toLong()), color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        var videoSpeed by remember(controller) { mutableFloatStateOf(1f) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { speed ->
                FilterChip(
                    selected = videoSpeed == speed,
                    onClick = { videoSpeed = speed; controller?.setPlaybackSpeed(speed) },
                    label = { Text("${speed}×", fontSize = 10.sp) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(CardBg).padding(12.dp)) {
            Text(if (language == "ar") "الترجمة الخارجية" else "EXTERNAL SUBTITLES", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (subtitleUri == null) {
                        if (language == "ar") "لم يتم اختيار ملف ترجمة" else "No subtitle file selected"
                    } else {
                        if (language == "ar") "تم تحميل ملف الترجمة" else "Subtitle file loaded"
                    },
                    color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { subtitlePicker.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) }) {
                    Icon(Icons.Rounded.Subtitles, null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (language == "ar") "اختيار SRT" else "Load SRT")
                }
            }
            HorizontalDivider(color = CardAlt)
            Spacer(Modifier.height(8.dp))
            Text(if (language == "ar") "تكرار مقطع A-B" else "A-B REPEAT", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = {
                    loopStart = controller?.currentPosition ?: 0L
                    loopEnd = -1L
                }, modifier = Modifier.weight(1f)) { Text("Set A") }
                OutlinedButton(
                    enabled = loopStart >= 0L,
                    onClick = {
                        val end = controller?.currentPosition ?: 0L
                        if (end > loopStart) loopEnd = end
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Set B") }
                TextButton(onClick = { loopStart = -1L; loopEnd = -1L }) { Text(if (language == "ar") "إيقاف" else "Off") }
            }
            if (loopStart >= 0L) {
                Text(
                    if (loopEnd > loopStart) "A ${formatDuration(loopStart)}  •  B ${formatDuration(loopEnd)}"
                    else "A ${formatDuration(loopStart)}  •  Set point B",
                    color = Muted, fontSize = 10.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) } }) {
                Icon(Icons.Rounded.Replay10, null, tint = TextPrimary, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                Icon(if (controller?.isPlaying == true) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircle, null, tint = Accent, modifier = Modifier.size(54.dp))
            }
            IconButton(onClick = { controller?.let { it.seekTo(it.currentPosition + 10_000L) } }) {
                Icon(Icons.Rounded.Forward10, null, tint = TextPrimary, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier, videoCount: Int, musicCount: Int,
    topTracks: List<Pair<LocalMedia, Int>>,
    navigate: (String) -> Unit, onPlay: (LocalMedia) -> Unit, language: String = "en"
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NOVRA", color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text(if (language == "ar") "شغّل عالمك." else "Play your world.", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = { navigate("Explore") }) { Icon(Icons.Rounded.Search, "Explore online", tint = TextPrimary) }
            IconButton(onClick = { navigate("Playlists") }) { Icon(Icons.Rounded.MoreHoriz, "More", tint = TextPrimary) }
        }
        Spacer(Modifier.height(25.dp))
        Text(if (language == "ar") "ترفيهك كله" else "YOUR ENTERTAINMENT", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(7.dp))
        Text(if (language == "ar") "إيه حابب تسمع أو\nتشاهد النهارده؟" else "What feels like\nplaying today?", color = TextPrimary, fontSize = 32.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(21.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTile(if (language == "ar") "مشغل الفيديو" else "VIDEO PLAYER", if (language == "ar") "شاهد فيديوهاتك" else "Watch your videos", Icons.Rounded.PlayCircle, Brush.linearGradient(listOf(Color(0xFF536E61), Color(0xFF293C35))), Modifier.weight(1f)) { navigate("Videos") }
            HomeTile(if (language == "ar") "مشغل الموسيقى" else "MUSIC PLAYER", if (language == "ar") "استمع لأغانيك" else "Listen to your music", Icons.Rounded.GraphicEq, Brush.linearGradient(listOf(Color(0xFF79634D), Color(0xFF3C3028))), Modifier.weight(1f)) { navigate("Music") }
        }
        Spacer(Modifier.height(27.dp))
        Text(if (language == "ar") "مكتبتك" else "YOUR MEDIA", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CountCard(if (language == "ar") "فيديوهات" else "VIDEOS", videoCount.toString(), Icons.Rounded.Movie, Modifier.weight(1f)) { navigate("Videos") }
            CountCard(if (language == "ar") "أغاني" else "SONGS", musicCount.toString(), Icons.Rounded.MusicNote, Modifier.weight(1f)) { navigate("Music") }
        }
        if (topTracks.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(if (language == "ar") "الأكثر تشغيلًا" else "MOST PLAYED", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(10.dp))
            topTracks.forEachIndexed { index, entry ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg)
                        .clickable { onPlay(entry.first) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${index + 1}", color = Muted, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                    MediaArtwork(entry.first, Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.first.title, color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (language == "ar") "تم التشغيل ${entry.second} مرة" else "${entry.second} plays", color = Muted, fontSize = 10.sp)
                    }
                    Icon(Icons.Rounded.PlayArrow, null, tint = Accent)
                }
                Spacer(Modifier.height(7.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Quick access", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("SEE ALL", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        listOf("Favorites", "Playlists").forEach { label ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(CardBg).clickable {
                navigate(if (label == "Favorites") "Favorites" else "Playlists")
            }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(when (label) {
                    "Favorites" -> Icons.Rounded.FavoriteBorder
                    "Playlists" -> Icons.Rounded.QueueMusic
                    else -> Icons.Rounded.History
                }, null, tint = Accent)
                Spacer(Modifier.width(13.dp))
                Text(label, color = TextPrimary, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeTile(tag: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, brush: Brush, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(brush).clickable(onClick = onClick).padding(15.dp)) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(26.dp))
        Text(tag, color = TextPrimary.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CountCard(label: String, count: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(CardBg).clickable(onClick = onClick).padding(15.dp)) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.height(12.dp))
        Text(count, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun MediaScreen(
    modifier: Modifier, type: String, media: List<LocalMedia>, loading: Boolean,
    hasPermission: Boolean, onGrant: () -> Unit, compactCards: Boolean, cardStyle: String,
    playCounts: Map<String, Int>, favorites: Set<String>,
    onToggleFavorite: (LocalMedia) -> Unit, onPlay: (LocalMedia) -> Unit,
    language: String = "en"
) {
    var query by remember { mutableStateOf("") }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("Default") }
    var mediaFilter by remember(type) { mutableStateOf(if (type == "Favorites") "Favorites" else "All") }
    val ranked = media.mapNotNull { item ->
        mediaSearchScore(item, query)?.let { item to it }
    }.filter { mediaFilter != "Favorites" || mediaKey(it.first) in favorites }
    val sortedMedia = when (sortBy) {
        "Name" -> ranked.sortedBy { it.first.title.lowercase() }.map { it.first }
        "Duration" -> ranked.sortedByDescending { it.first.durationMs }.map { it.first }
        "Most played" -> ranked.sortedByDescending {
            playCounts["play_count_${if (it.first.isVideo) "video" else "audio"}_${it.first.id}"] ?: 0
        }.map { it.first }
        else -> ranked.sortedWith(
            compareByDescending<Pair<LocalMedia, Int>> { it.second }
                .thenByDescending { playCounts["play_count_${if (it.first.isVideo) "video" else "audio"}_${it.first.id}"] ?: 0 }
        ).map { it.first }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (language == "ar") when (type) { "Videos" -> "الفيديوهات"; "Music" -> "الأغاني"; else -> "المفضلة" } else type, color = TextPrimary, fontSize = 29.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) { Icon(Icons.Rounded.Sort, "Sort", tint = TextPrimary) }
                DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                    listOf("Default", "Name", "Duration", "Most played").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { sortBy = option; sortMenuExpanded = false },
                            trailingIcon = { if (sortBy == option) Icon(Icons.Rounded.Check, null, tint = Accent) }
                        )
                    }
                }
            }
        }
        Text(
            when (type) {
                "Videos" -> if (language == "ar") "مجموعتك من الفيديوهات" else "Your video collection"
                "Music" -> if (language == "ar") "مجموعتك الموسيقية" else "Your music collection"
                else -> if (language == "ar") "كل الوسائط المحفوظة" else "Everything you saved"
            },
            color = Muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(17.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (language == "ar") "ابحث في ${if (type == "Music") "الأغاني" else "الفيديوهات"}" else "Search $type", color = Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = CardAlt,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                cursorColor = Accent
            )
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Text("Sort: $sortBy", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Spacer(Modifier.height(14.dp))
        when {
            !hasPermission -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Accent, modifier = Modifier.size(45.dp))
                Spacer(Modifier.height(14.dp))
                Text("Allow media access", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("NOVRA needs permission to show your $type.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onGrant) { Text("Grant access") }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            sortedMedia.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (type == "Videos") Icons.Rounded.OndemandVideo else Icons.Rounded.MusicNote, null, tint = Accent, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(12.dp))
                Text(if (language == "ar") { if (query.isBlank()) "لا توجد وسائط بعد" else "لا توجد نتائج مطابقة" } else if (query.isBlank()) "Nothing here yet" else "No results found", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (language == "ar") { if (query.isBlank()) "ستظهر وسائط جهازك هنا." else "جرّب كلمات بحث أخرى." } else if (query.isBlank()) "Media on your device will appear here." else "Try another search.", color = Muted, fontSize = 11.sp)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item { Text("${sortedMedia.size} items", color = Muted, fontSize = 11.sp) }
                items(sortedMedia, key = { mediaKey(it) }) { item ->
                    Row(Modifier.fillMaxWidth().clip(if (cardStyle == "square") RoundedCornerShape(7.dp) else RoundedCornerShape(16.dp)).background(CardBg).clickable { onPlay(item) }.padding(if (compactCards) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        MediaArtwork(item, Modifier.size(if (compactCards) 42.dp else 54.dp).clip(if (cardStyle == "square") RoundedCornerShape(5.dp) else RoundedCornerShape(13.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            val countKey = "play_count_${if (item.isVideo) "video" else "audio"}_${item.id}"
                            Text(
                                if (!item.isVideo && item.artist.isNotBlank()) item.artist
                                else "${formatDuration(item.durationMs)}  •  ${playCounts[countKey] ?: 0} plays",
                                color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
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
    }
}

private fun formatDuration(duration: Long): String {
    val total = duration / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}


@Composable
private fun MediaArtwork(media: LocalMedia, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, media.uri) {
        value = withContext(Dispatchers.IO) {
            val cacheKey = media.uri.toString()
            mediaArtworkCache.get(cacheKey) ?: runCatching {
                if (media.isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(media.uri, Size(256, 256), null)
                } else if (!media.isVideo) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, media.uri)
                        retriever.embeddedPicture?.let { imageBytes ->
                            BitmapFactory.decodeByteArray(
                                imageBytes, 0, imageBytes.size,
                                BitmapFactory.Options().apply { inSampleSize = 4 }
                            )
                        }
                    } finally {
                        retriever.release()
                    }
                } else null
            }.getOrNull()?.also { bitmap ->
                mediaArtworkCache.put(cacheKey, bitmap)
            }
        }
    }
    Box(modifier.background(CardAlt), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
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

@Composable
private fun NowPlayingScreen(
    modifier: Modifier, media: LocalMedia, controller: MediaController?, queue: List<LocalMedia>,
    sleepTimerMinutes: Int, onSleepTimerChange: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit, onRemoveQueueItem: (Int) -> Unit,
    onBack: () -> Unit, language: String = "en"
) {
    var shuffle by remember(controller) { mutableStateOf(controller?.shuffleModeEnabled == true) }
    var repeatMode by remember(controller) { mutableIntStateOf(controller?.repeatMode ?: Player.REPEAT_MODE_OFF) }
    var currentQueueIndex by remember(controller) { mutableIntStateOf(controller?.currentMediaItemIndex ?: 0) }
    var playbackPosition by remember(controller) { mutableFloatStateOf(0f) }
    var playbackDuration by remember(controller) { mutableFloatStateOf(media.durationMs.coerceAtLeast(1L).toFloat()) }
    var playbackSpeed by remember(controller) { mutableFloatStateOf(1f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val lyricPrefs = remember(context) { context.getSharedPreferences("novra_lyrics", android.content.Context.MODE_PRIVATE) }
    var lyricsUri by remember(media.id, media.isVideo) {
        mutableStateOf(lyricPrefs.getString("${if (media.isVideo) "video" else "audio"}_${media.id}", null)?.let(Uri::parse))
    }
    var lyricLines by remember(media.id, media.isVideo) { mutableStateOf<List<LyricLine>>(emptyList()) }
    val lyricsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            lyricsUri = uri
            lyricPrefs.edit().putString("${if (media.isVideo) "video" else "audio"}_${media.id}", uri.toString()).apply()
        }
    }
    LaunchedEffect(lyricsUri, media.id) {
        lyricLines = if (lyricsUri == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(lyricsUri!!)?.bufferedReader()?.use { parseLrc(it.readText()) }
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(controller, media.id) {
        while (true) {
            val duration = controller?.duration?.takeIf { it > 0L } ?: media.durationMs
            playbackDuration = duration.coerceAtLeast(1L).toFloat()
            playbackPosition = (controller?.currentPosition ?: 0L).coerceIn(0L, duration.coerceAtLeast(1L)).toFloat()
            currentQueueIndex = controller?.currentMediaItemIndex ?: 0
            delay(500)
        }
    }
    if (media.isVideo) {
        VideoNowPlayingScreen(
            modifier = modifier,
            media = media,
            controller = controller,
            position = playbackPosition,
            duration = playbackDuration,
            onBack = onBack,
            language = language
        )
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Spacer(Modifier.height(10.dp))
        if (media.isVideo) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)),
                factory = { ctx -> PlayerView(ctx).apply { useController = true } },
                update = { view -> view.player = controller }
            )
        } else {
            MediaArtwork(media, Modifier.fillMaxWidth().padding(horizontal = 18.dp).aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
        }
        Spacer(Modifier.height(22.dp))
        Text(media.title, color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (media.artist.isNotBlank()) Text(media.artist, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!media.isVideo) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (language == "ar") "كلمات الأغنية" else "SYNCED LYRICS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(
                        if (lyricLines.isEmpty()) {
                            if (language == "ar") "استورد ملف كلمات بصيغة LRC" else "Import an .LRC lyrics file"
                        } else {
                            if (language == "ar") "${lyricLines.size} سطر متزامن" else "${lyricLines.size} synced lines"
                        },
                        color = Muted, fontSize = 10.sp
                    )
                }
                OutlinedButton(onClick = { lyricsPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }) {
                    Icon(Icons.Rounded.Subtitles, null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (language == "ar") "استيراد" else "Import LRC")
                }
            }
            if (lyricLines.isNotEmpty()) {
                val activeLyric = lyricLines.indexOfLast { it.timeMs <= playbackPosition.toLong() }.coerceAtLeast(0)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    lyricLines.slice(maxOf(0, activeLyric - 1)..minOf(lyricLines.lastIndex, activeLyric + 2)).forEachIndexed { visibleIndex, line ->
                        val actualIndex = maxOf(0, activeLyric - 1) + visibleIndex
                        Text(
                            line.text,
                            color = if (actualIndex == activeLyric) Accent else Muted,
                            fontSize = if (actualIndex == activeLyric) 16.sp else 13.sp,
                            fontWeight = if (actualIndex == activeLyric) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { controller?.seekTo(line.timeMs) }.padding(vertical = 6.dp)
                        )
                    }
                }
            }
        }
        Text(if (language == "ar") { if (media.isVideo) "فيديو" else "صوت" } else if (media.isVideo) "Video" else "Audio", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Slider(
            value = playbackPosition.coerceIn(0f, playbackDuration),
            onValueChange = { playbackPosition = it },
            onValueChangeFinished = { controller?.seekTo(playbackPosition.toLong()) },
            valueRange = 0f..playbackDuration,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = CardAlt
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(playbackPosition.toLong()), color = Muted, fontSize = 10.sp)
            Text(formatDuration(playbackDuration.toLong()), color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(16.dp))
        if (!media.isVideo) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle }) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffle) Accent else Muted)
                }
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) { Icon(Icons.Rounded.SkipPrevious, null, tint = TextPrimary, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                    Icon(if (controller?.isPlaying == true) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircle, null, tint = Accent, modifier = Modifier.size(58.dp))
                }
                IconButton(onClick = { controller?.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, null, tint = TextPrimary, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = {
                    repeatMode = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                    controller?.repeatMode = repeatMode
                }) {
                    Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (repeatMode == Player.REPEAT_MODE_OFF) Muted else Accent)
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(12.dp)) {
            Text("PLAYBACK SPEED", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    FilterChip(
                        selected = playbackSpeed == speed,
                        onClick = { playbackSpeed = speed; controller?.setPlaybackSpeed(speed) },
                        label = { Text(if (speed == 1f) "1×" else "${speed}×", fontSize = 10.sp) }
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SLEEP TIMER", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                if (sleepTimerMinutes > 0) Text("Timer set: ${sleepTimerMinutes} min", color = Accent, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    FilterChip(
                        selected = sleepTimerMinutes == minutes,
                        onClick = { onSleepTimerChange(minutes) },
                        label = { Text("${minutes}m", fontSize = 10.sp) }
                    )
                }
                if (sleepTimerMinutes > 0) {
                    FilterChip(selected = false, onClick = { onSleepTimerChange(0) }, label = { Text("Off", fontSize = 10.sp) })
                }
            }
        }
        if (controller != null) {
            EqualizerPanel(controller = controller, language = language)
        }
        if (queue.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (language == "ar") "التالي في قائمة التشغيل" else "UP NEXT", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text(if (language == "ar") "${queue.size} عناصر" else "${queue.size} items", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            queue.forEachIndexed { index, item ->
                val isCurrent = index == currentQueueIndex
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                        .background(if (isCurrent) CardAlt else Color.Transparent)
                        .clickable { controller?.seekTo(index, 0L) }
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MediaArtwork(item, Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = if (isCurrent) Accent else TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (isCurrent) "Now playing" else formatDuration(item.durationMs), color = Muted, fontSize = 10.sp)
                    }
                    if (isCurrent) Icon(Icons.Rounded.VolumeUp, null, tint = Accent, modifier = Modifier.size(17.dp))
                    IconButton(
                        enabled = index > 0,
                        onClick = { onMoveQueueItem(index, index - 1) },
                        modifier = Modifier.size(32.dp)
                    ) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = if (index > 0) TextPrimary else Muted) }
                    IconButton(
                        enabled = index < queue.lastIndex,
                        onClick = { onMoveQueueItem(index, index + 1) },
                        modifier = Modifier.size(32.dp)
                    ) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down", tint = if (index < queue.lastIndex) TextPrimary else Muted) }
                    IconButton(
                        enabled = queue.size > 1,
                        onClick = { onRemoveQueueItem(index) },
                        modifier = Modifier.size(32.dp)
                    ) { Icon(Icons.Rounded.Close, "Remove from queue", tint = Muted) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier, wallpaperUri: Uri?, compactCards: Boolean, cardStyle: String, listenedSeconds: Long, themeMode: String, accentName: String,
    onThemeChange: (String) -> Unit, onAccentChange: (String) -> Unit, onChooseWallpaper: () -> Unit, onClearWallpaper: () -> Unit,
    onCompactChange: (Boolean) -> Unit, onCardStyleChange: (String) -> Unit,
    onOpenPremium: () -> Unit,
    language: String, onLanguageChange: (String) -> Unit,
    wallpaperDim: Float, onWallpaperDimChange: (Float) -> Unit
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text(if (language == "ar") "الإعدادات" else "Settings", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(if (language == "ar") "خصص NOVRA بالشكل اللي يناسبك." else "Make NOVRA feel like yours.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF354A3D), Color(0xFF1D2922))))
                .clickable(onClick = onOpenPremium).padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WorkspacePremium, null, tint = Color(0xFFE8D49A), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("NOVRA Premium", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(if (language == "ar") "افتح تجربة NOVRA المطوّرة" else "Unlock the enhanced NOVRA experience", color = Color(0xFFD4DED6), fontSize = 11.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            Text("50 EGP / 1 month", color = Color(0xFFE8D49A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
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
            HorizontalDivider(color = CardAlt)
            Text(if (language == "ar") "اللون الأساسي" else "Accent color", color = TextPrimary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("mint" to "Mint", "ocean" to "Ocean", "violet" to "Violet", "rose" to "Rose", "amber" to "Amber", "cyan" to "Cyan", "indigo" to "Indigo", "orange" to "Orange").forEach { (key, label) ->
                    FilterChip(selected = accentName == key, onClick = { onAccentChange(key) }, label = { Text(label, fontSize = 10.sp) })
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
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Text(if (language == "ar") "إجمالي وقت التشغيل" else "Total playback time", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(formatDuration(listenedSeconds * 1000L), color = Accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(if (language == "ar") "إجمالي الوقت الذي شغّل فيه NOVRA الوسائط على هذا الجهاز." else "Total time NOVRA has spent playing media on this device.", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Text("Video picture-in-picture is enabled when you leave NOVRA while a video is playing. Music playback continues through the media session.", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PlaylistsScreen(
    modifier: Modifier,
    playlists: List<UserPlaylist>,
    media: List<LocalMedia>,
    onCreate: (String) -> Unit,
    onAdd: (String, LocalMedia) -> Unit,
    onDelete: (String) -> Unit,
    onPlay: (List<LocalMedia>) -> Unit, language: String = "en"
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
                Text("Create")
            }
        }
        Spacer(Modifier.height(20.dp))
        if (playlists.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.QueueMusic, null, tint = Accent, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your playlists start here", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("Create a playlist, then add songs or videos from your device.", color = Muted, fontSize = 12.sp)
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
                        Text("${items.size} items", color = Muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { addToPlaylist = playlist.name }) { Icon(Icons.Rounded.Add, "Add media", tint = Accent) }
                    IconButton(onClick = { onDelete(playlist.name) }) { Icon(Icons.Rounded.DeleteOutline, "Delete playlist", tint = Muted) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPlay(items) }, enabled = items.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Text("Play")
                    }
                    OutlinedButton(onClick = { addToPlaylist = playlist.name }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Add, null)
                        Text("Add media")
                    }
                }
                items.take(3).forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MediaArtwork(item, Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)))
                        Spacer(Modifier.width(9.dp))
                        Text(item.title, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create playlist") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("Playlist name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty() && playlists.none { it.name.equals(name, ignoreCase = true) }) onCreate(name)
                    showCreate = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
        )
    }
    addToPlaylist?.let { playlistName ->
        AlertDialog(
            onDismissRequest = { addToPlaylist = null },
            title = { Text("Add to $playlistName") },
            text = {
                if (media.isEmpty()) Text("No device media found yet. Grant media access and scan your library first.")
                else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    media.forEach { item ->
                        Row(Modifier.fillMaxWidth().clickable { onAdd(playlistName, item) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            MediaArtwork(item, Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (item.isVideo) "Video" else "Audio", color = Muted, fontSize = 10.sp)
                            }
                            Icon(Icons.Rounded.AddCircleOutline, null, tint = Accent)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addToPlaylist = null }) { Text("Done") } }
        )
    }
}


@Composable
private fun PremiumScreen(
    modifier: Modifier,
    transactionReference: String,
    pendingReview: Boolean,
    onReferenceChange: (String) -> Unit,
    onSubmit: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val walletNumber = "01131023695"
    var receiptUri by remember { mutableStateOf<Uri?>(null) }
    val receiptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            receiptUri = uri
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WorkspacePremium, null, tint = Color(0xFFE8C979), modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("NOVRA Premium", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("More control. More ways to enjoy.", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF394E40), Color(0xFF18251D))))
                .padding(20.dp)
        ) {
            Text("ONE-MONTH MEMBERSHIP", color = Color(0xFFE8D49A), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("50", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Black)
                Text(" EGP / month", color = Color(0xFFE1E8E2), fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text("Valid for 30 days after payment approval.", color = Color(0xFFD4DED6), fontSize = 11.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text("PREMIUM BENEFITS", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(10.dp))
        val benefits = listOf(
            "Exclusive themes and accent color combinations",
            "More home-screen and media-card customization",
            "Advanced playback and listening statistics",
            "Expanded playlist and queue organization",
            "Premium visual styles and backgrounds"
        )
        benefits.forEach { benefit ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(benefit, color = TextPrimary, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("PAY WITH VODAFONE CASH", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(9.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(16.dp)) {
            Text("Transfer 50 EGP to this Vodafone Cash number:", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(walletNumber, color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NOVRA Vodafone Cash number", walletNumber))
                }) { Text("Copy") }
            }
            Text("After transferring, attach the receipt image and enter its transaction reference. Your request will be reviewed manually.", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = transactionReference,
            onValueChange = onReferenceChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Transfer transaction reference") },
            placeholder = { Text("Enter the reference shown in your receipt") }
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { receiptPicker.launch(arrayOf("image/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.AttachFile, null)
            Spacer(Modifier.width(8.dp))
            Text(if (receiptUri == null) "Attach transfer receipt image" else "Receipt attached — change image")
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val reference = transactionReference.trim()
                val receipt = receiptUri
                if (reference.isNotEmpty() && receipt != null) {
                    onSubmit(reference)
                    val message = "NOVRA Premium — 50 EGP / 1 month\nVodafone Cash transfer to: $walletNumber\nTransaction reference: $reference\nPlease verify this payment and activate the one-month membership."
                    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "NOVRA Premium payment verification")
                        putExtra(android.content.Intent.EXTRA_TEXT, message)
                        putExtra(android.content.Intent.EXTRA_STREAM, receipt)
                        clipData = android.content.ClipData.newUri(context.contentResolver, "NOVRA transfer receipt", receipt)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(sendIntent, "Send payment receipt"))
                }
            },
            enabled = transactionReference.trim().isNotEmpty() && receiptUri != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Send, null)
            Spacer(Modifier.width(8.dp))
            Text("Send receipt for manual review")
        }
        if (pendingReview) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardAlt).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.HourglassTop, null, tint = Accent)
                Spacer(Modifier.width(8.dp))
                Text("A review request was saved on this device. Premium is not active until payment is verified.", color = TextPrimary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Sending this request does not verify the transfer or activate Premium automatically. Never share your wallet PIN or OTP.",
            color = Muted, fontSize = 10.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

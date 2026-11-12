package com.novra.app

import android.Manifest
import android.content.pm.PackageManager
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
import android.media.MediaMetadataRetriever
import android.util.Size
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

private val AppBg = Color(0xFF101310)
private val CardBg = Color(0xFF1D241F)
private val CardAlt = Color(0xFF2A332D)
private val Muted = Color(0xFFA7B0A8)
private val Accent = Color(0xFFC7E8D1)

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
    val prefs = remember { context.getSharedPreferences("novra_preferences", android.content.Context.MODE_PRIVATE) }
    var wallpaperUri by remember { mutableStateOf(prefs.getString("wallpaper_uri", null)?.let(Uri::parse)) }
    var compactCards by remember { mutableStateOf(prefs.getBoolean("compact_cards", false)) }
    var cardStyle by remember { mutableStateOf(prefs.getString("card_style", "rounded") ?: "rounded") }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            wallpaperUri = uri
            prefs.edit().putString("wallpaper_uri", uri.toString()).apply()
        }
    }
    var selectedMedia by remember { mutableStateOf<LocalMedia?>(null) }
    var listenedSeconds by remember { mutableLongStateOf(prefs.getLong("listened_seconds", 0L)) }
    val playCounts = remember {
        mutableStateMapOf<String, Int>().apply {
            prefs.all.forEach { (key, value) ->
                if (key.startsWith("play_count_") && value is Int) put(key, value)
            }
        }
    }
    var playQueue by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
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
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).build())
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
            loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching { MediaLibrary.videos(context) to MediaLibrary.music(context) }
            }
            result.onSuccess { (v, a) -> videos = v; tracks = a }
            loading = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = AppBg, surface = CardBg, primary = Accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Box(Modifier.fillMaxSize()) {
            if (wallpaperUri != null) {
                AsyncImage(
                    model = wallpaperUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Scaffold(
            containerColor = if (wallpaperUri != null) Color.Transparent else AppBg,
            bottomBar = {
                Column {
                    if (selectedMedia != null && !showNowPlaying) {
                        Row(Modifier.fillMaxWidth().background(CardAlt).clickable { showNowPlaying = true }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (selectedMedia?.isVideo == true) Icons.Rounded.PlayCircle else Icons.Rounded.GraphicEq, null, tint = Accent)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(selectedMedia?.title.orEmpty(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                Text("NOW PLAYING", color = Muted, fontSize = 9.sp)
                            }
                            IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                                Icon(if (controller?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White)
                            }
                            IconButton(onClick = { controller?.stop(); selectedMedia = null }) { Icon(Icons.Rounded.Close, null, tint = Muted) }
                        }
                    }
                    NavigationBar(containerColor = CardBg, tonalElevation = 0.dp) {
                    listOf(
                        Triple("Home", Icons.Rounded.Home, "Home"),
                        Triple("Videos", Icons.Rounded.OndemandVideo, "Videos"),
                        Triple("Music", Icons.Rounded.MusicNote, "Music"),
                        Triple("Playlists", Icons.Rounded.QueueMusic, "Playlists"),
                        Triple("Settings", Icons.Rounded.Settings, "Settings")
                    ).forEach { item ->
                        NavigationBarItem(
                            selected = selected == item.first,
                            onClick = { selected = item.first },
                            icon = { Icon(item.second, contentDescription = item.third) },
                            label = { Text(item.third, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent, selectedTextColor = Accent,
                                indicatorColor = Color(0xFF35463A),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                    }
                }
            }
        ) { padding ->
            if (showNowPlaying && selectedMedia != null) {
                NowPlayingScreen(Modifier.padding(padding), selectedMedia!!, controller, playQueue, onBack = { showNowPlaying = false })
            } else when (selected) {
                "Home" -> HomeScreen(
                    Modifier.padding(padding), videos.size, tracks.size,
                    tracks.map { track ->
                        val key = "play_count_audio_${track.id}"
                        track to (playCounts[key] ?: 0)
                    }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5),
                    navigate = { selected = it },
                    onPlay = { startMedia(it, tracks) }
                )
                "Videos" -> MediaScreen(
                    Modifier.padding(padding), "Videos", videos, loading, hasPermission,
                    onGrant = { permissionLauncher.launch(mediaPermissions()) },
                    compactCards = compactCards, cardStyle = cardStyle,
                    playCounts = playCounts, onPlay = { item -> startMedia(item, videos) }
                )
                "Music" -> MediaScreen(
                    Modifier.padding(padding), "Music", tracks, loading, hasPermission,
                    onGrant = { permissionLauncher.launch(mediaPermissions()) },
                    compactCards = compactCards, cardStyle = cardStyle,
                    playCounts = playCounts, onPlay = { item -> startMedia(item, tracks) }
                )
                "Playlists" -> PlaylistsScreen(Modifier.padding(padding))
                else -> SettingsScreen(
                    Modifier.padding(padding), wallpaperUri, compactCards, cardStyle, listenedSeconds,
                    onChooseWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                    onClearWallpaper = { wallpaperUri = null; prefs.edit().remove("wallpaper_uri").apply() },
                    onCompactChange = { compactCards = it; prefs.edit().putBoolean("compact_cards", it).apply() },
                    onCardStyleChange = { cardStyle = it; prefs.edit().putString("card_style", it).apply() }
                )
            }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier, videoCount: Int, musicCount: Int,
    topTracks: List<Pair<LocalMedia, Int>>,
    navigate: (String) -> Unit, onPlay: (LocalMedia) -> Unit
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NOVRA", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text("Play your world.", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = { navigate("Videos") }) { Icon(Icons.Rounded.Search, "Search", tint = Color.White) }
            IconButton(onClick = { navigate("Playlists") }) { Icon(Icons.Rounded.MoreHoriz, "More", tint = Color.White) }
        }
        Spacer(Modifier.height(25.dp))
        Text("YOUR ENTERTAINMENT", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(7.dp))
        Text("What feels like\nplaying today?", color = Color.White, fontSize = 33.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(21.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTile("VIDEO PLAYER", "Watch your videos", Icons.Rounded.PlayCircle, Brush.linearGradient(listOf(Color(0xFF536E61), Color(0xFF293C35))), Modifier.weight(1f)) { navigate("Videos") }
            HomeTile("MUSIC PLAYER", "Listen to your music", Icons.Rounded.GraphicEq, Brush.linearGradient(listOf(Color(0xFF79634D), Color(0xFF3C3028))), Modifier.weight(1f)) { navigate("Music") }
        }
        Spacer(Modifier.height(27.dp))
        Text("YOUR MEDIA", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CountCard("VIDEOS", videoCount.toString(), Icons.Rounded.Movie, Modifier.weight(1f)) { navigate("Videos") }
            CountCard("SONGS", musicCount.toString(), Icons.Rounded.MusicNote, Modifier.weight(1f)) { navigate("Music") }
        }
        if (topTracks.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("MOST PLAYED", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
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
                        Text(entry.first.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${entry.second} plays", color = Muted, fontSize = 10.sp)
                    }
                    Icon(Icons.Rounded.PlayArrow, null, tint = Accent)
                }
                Spacer(Modifier.height(7.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Quick access", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("SEE ALL", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        listOf("Recently played", "Favorites", "Playlists").forEach { label ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(CardBg).clickable { navigate("Playlists") }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(when (label) {
                    "Favorites" -> Icons.Rounded.FavoriteBorder
                    "Playlists" -> Icons.Rounded.QueueMusic
                    else -> Icons.Rounded.History
                }, null, tint = Accent)
                Spacer(Modifier.width(13.dp))
                Text(label, color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
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
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(26.dp))
        Text(tag, color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CountCard(label: String, count: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(CardBg).clickable(onClick = onClick).padding(15.dp)) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.height(12.dp))
        Text(count, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun MediaScreen(
    modifier: Modifier, type: String, media: List<LocalMedia>, loading: Boolean,
    hasPermission: Boolean, onGrant: () -> Unit, compactCards: Boolean, cardStyle: String, playCounts: Map<String, Int>, onPlay: (LocalMedia) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sortNewest by remember { mutableStateOf(true) }
    val filtered = media.filter { it.title.contains(query, ignoreCase = true) }
    Column(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(type, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { sortNewest = !sortNewest }) { Icon(Icons.Rounded.Sort, "Sort", tint = Color.White) }
        }
        Text(if (type == "Videos") "Your video collection" else "Your music collection", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(17.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search $type", color = Muted) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = CardAlt,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                cursorColor = Accent
            )
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Folders", "Favorites").forEachIndexed { index, label ->
                Surface(color = if (index == 0) Accent else CardBg, shape = CircleShape) {
                    Text(label, color = if (index == 0) AppBg else Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        when {
            !hasPermission -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Accent, modifier = Modifier.size(45.dp))
                Spacer(Modifier.height(14.dp))
                Text("Allow media access", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("NOVRA needs permission to show your $type.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onGrant) { Text("Grant access") }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            filtered.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(if (type == "Videos") Icons.Rounded.OndemandVideo else Icons.Rounded.MusicNote, null, tint = Accent, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(12.dp))
                Text(if (query.isBlank()) "Nothing here yet" else "No results found", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (query.isBlank()) "Media on your device will appear here." else "Try another search.", color = Muted, fontSize = 11.sp)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item { Text("${filtered.size} items", color = Muted, fontSize = 11.sp) }
                items(filtered) { item ->
                    Row(Modifier.fillMaxWidth().clip(if (cardStyle == "square") RoundedCornerShape(7.dp) else RoundedCornerShape(16.dp)).background(CardBg).clickable { onPlay(item) }.padding(if (compactCards) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        MediaArtwork(item, Modifier.size(if (compactCards) 42.dp else 54.dp).clip(if (cardStyle == "square") RoundedCornerShape(5.dp) else RoundedCornerShape(13.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            val countKey = "play_count_${if (item.isVideo) "video" else "audio"}_${item.id}"
                            Text("${formatDuration(item.durationMs)}  •  ${playCounts[countKey] ?: 0} plays", color = Muted, fontSize = 10.sp)
                        }
                        IconButton(onClick = { }) { Icon(Icons.Rounded.MoreVert, "More", tint = Muted) }
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
            runCatching {
                if (media.isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(media.uri, Size(512, 512), null)
                } else if (!media.isVideo) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, media.uri)
                        retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    } finally {
                        retriever.release()
                    }
                } else null
            }.getOrNull()
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
private fun NowPlayingScreen(modifier: Modifier, media: LocalMedia, controller: MediaController?, queue: List<LocalMedia>, onBack: () -> Unit) {
    var shuffle by remember(controller) { mutableStateOf(controller?.shuffleModeEnabled == true) }
    var repeatMode by remember(controller) { mutableIntStateOf(controller?.repeatMode ?: Player.REPEAT_MODE_OFF) }
    var currentQueueIndex by remember(controller) { mutableIntStateOf(controller?.currentMediaItemIndex ?: 0) }
    var playbackPosition by remember(controller) { mutableFloatStateOf(0f) }
    var playbackDuration by remember(controller) { mutableFloatStateOf(media.durationMs.coerceAtLeast(1L).toFloat()) }
    var playbackSpeed by remember(controller) { mutableFloatStateOf(1f) }
    var sleepTimerMinutes by remember(controller) { mutableIntStateOf(0) }
    LaunchedEffect(sleepTimerMinutes, controller) {
        if (sleepTimerMinutes > 0) {
            delay(sleepTimerMinutes * 60_000L)
            controller?.pause()
            sleepTimerMinutes = 0
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
    Column(modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Back", tint = Color.White) }
            Text("Now Playing", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        if (media.isVideo) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)),
                factory = { ctx -> PlayerView(ctx).apply { useController = true } },
                update = { view -> view.player = controller }
            )
        } else {
            MediaArtwork(media, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)))
        }
        Spacer(Modifier.height(22.dp))
        Text(media.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (media.isVideo) "Video" else "Audio", color = Muted, fontSize = 12.sp)
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
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) { Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                    Icon(if (controller?.isPlaying == true) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircle, null, tint = Accent, modifier = Modifier.size(58.dp))
                }
                IconButton(onClick = { controller?.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
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
                if (sleepTimerMinutes > 0) Text("Stops in ${sleepTimerMinutes} min", color = Accent, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    FilterChip(
                        selected = sleepTimerMinutes == minutes,
                        onClick = { sleepTimerMinutes = minutes },
                        label = { Text("${minutes}m", fontSize = 10.sp) }
                    )
                }
                if (sleepTimerMinutes > 0) {
                    FilterChip(selected = false, onClick = { sleepTimerMinutes = 0 }, label = { Text("Off", fontSize = 10.sp) })
                }
            }
        }
        if (queue.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("UP NEXT", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.weight(1f))
                Text("${queue.size} items", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            queue.take(8).forEachIndexed { index, item ->
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
                        Text(item.title, color = if (isCurrent) Accent else Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (isCurrent) "Now playing" else formatDuration(item.durationMs), color = Muted, fontSize = 10.sp)
                    }
                    if (isCurrent) Icon(Icons.Rounded.VolumeUp, null, tint = Accent, modifier = Modifier.size(17.dp))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier, wallpaperUri: Uri?, compactCards: Boolean, cardStyle: String, listenedSeconds: Long,
    onChooseWallpaper: () -> Unit, onClearWallpaper: () -> Unit,
    onCompactChange: (Boolean) -> Unit, onCardStyleChange: (String) -> Unit
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Settings", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Make NOVRA feel like yours.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Text("APPEARANCE", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Text("App background", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Choose a picture from your gallery as your personal background.", color = Muted, fontSize = 11.sp)
            if (wallpaperUri != null) {
                Spacer(Modifier.height(12.dp))
                AsyncImage(model = wallpaperUri, contentDescription = "Selected background", modifier = Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = onChooseWallpaper) { Text("Choose from gallery") }
                if (wallpaperUri != null) OutlinedButton(onClick = onClearWallpaper) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Text("Media cards", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compact cards", color = Color.White, fontSize = 13.sp)
                    Text("Show more songs and videos on screen.", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = compactCards, onCheckedChange = onCompactChange)
            }
            HorizontalDivider(color = CardAlt)
            Text("Card corners", color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = cardStyle == "rounded", onClick = { onCardStyleChange("rounded") }, label = { Text("Rounded") })
                FilterChip(selected = cardStyle == "square", onClick = { onCardStyleChange("square") }, label = { Text("Square") })
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(15.dp)) {
            Text("Total playback time", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(formatDuration(listenedSeconds * 1000L), color = Accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Total time NOVRA has spent playing media on this device.", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Text("Video picture-in-picture is enabled when you leave NOVRA while a video is playing. Music playback continues through the media session.", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PlaylistsScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(22.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Playlists", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("Your mixes, favorites and listening queue.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(22.dp))
        listOf(
            "Favorites" to Icons.Rounded.Favorite,
            "Recently played" to Icons.Rounded.History,
            "Create a playlist" to Icons.Rounded.Add,
            "Now playing queue" to Icons.Rounded.QueueMusic
        ).forEach { (label, icon) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(CardBg).clickable { }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Accent)
                Spacer(Modifier.width(14.dp))
                Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
            Spacer(Modifier.height(9.dp))
        }
    }
}

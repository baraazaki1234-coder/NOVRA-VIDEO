package com.novra.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val AppBg = Color(0xFF101310)
private val CardBg = Color(0xFF1D241F)
private val CardAlt = Color(0xFF2A332D)
private val Muted = Color(0xFFA7B0A8)
private val Accent = Color(0xFFC7E8D1)

class MainActivity : ComponentActivity() {
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
    var selectedMedia by remember { mutableStateOf<LocalMedia?>(null) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    val controllerFuture = remember(context) {
        MediaController.Builder(context, SessionToken(context, ComponentName(context, NovraPlaybackService::class.java))).buildAsync()
    }
    DisposableEffect(controllerFuture) {
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess { controller = it }
        }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(controllerFuture) }
    }
    LaunchedEffect(selectedMedia, controller) {
        val media = selectedMedia
        val player = controller
        if (media != null && player != null) {
            player.setMediaItem(MediaItem.fromUri(media.uri))
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
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = mediaPermissions().all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(hasPermission) {
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
        Scaffold(
            containerColor = AppBg,
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
                        Triple("Playlists", Icons.Rounded.QueueMusic, "Playlists")
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
                NowPlayingScreen(Modifier.padding(padding), selectedMedia!!, controller, onBack = { showNowPlaying = false })
            } else when (selected) {
                "Home" -> HomeScreen(Modifier.padding(padding), videos.size, tracks.size) { selected = it }
                "Videos" -> MediaScreen(
                    Modifier.padding(padding), "Videos", videos, loading, hasPermission,
                    onGrant = { permissionLauncher.launch(mediaPermissions()) },
                    onPlay = { selectedMedia = it; showNowPlaying = true }
                )
                "Music" -> MediaScreen(
                    Modifier.padding(padding), "Music", tracks, loading, hasPermission,
                    onGrant = { permissionLauncher.launch(mediaPermissions()) },
                    onPlay = { selectedMedia = it; showNowPlaying = true }
                )
                else -> PlaylistsScreen(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, videoCount: Int, musicCount: Int, navigate: (String) -> Unit) {
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
    hasPermission: Boolean, onGrant: () -> Unit, onPlay: (LocalMedia) -> Unit
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
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).clickable { onPlay(item) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(13.dp)).background(CardAlt), contentAlignment = Alignment.Center) {
                            Icon(if (type == "Videos") Icons.Rounded.PlayArrow else Icons.Rounded.MusicNote, null, tint = Accent, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(formatDuration(item.durationMs), color = Muted, fontSize = 10.sp)
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
private fun NowPlayingScreen(modifier: Modifier, media: LocalMedia, controller: MediaController?, onBack: () -> Unit) {
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
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(CardBg), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Album, null, tint = Accent, modifier = Modifier.size(112.dp))
                    Spacer(Modifier.height(20.dp))
                    Text("NOVRA MUSIC", color = Muted, fontSize = 10.sp, letterSpacing = 3.sp)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(media.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (media.isVideo) "Video" else "Audio", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        if (!media.isVideo) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) { Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                    Icon(if (controller?.isPlaying == true) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircle, null, tint = Accent, modifier = Modifier.size(58.dp))
                }
                IconButton(onClick = { controller?.seekToNextMediaItem() }) { Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            }
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

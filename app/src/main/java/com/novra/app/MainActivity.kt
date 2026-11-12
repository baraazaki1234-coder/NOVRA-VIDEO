package com.novra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF101310)
private val SurfaceOne = Color(0xFF1B211D)
private val SurfaceTwo = Color(0xFF252D27)
private val TextMuted = Color(0xFFA5AEA6)
private val Mint = Color(0xFFC7E8D1)
private val Warm = Color(0xFFE6C7A5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NovraApp() }
    }
}

@Composable
private fun NovraApp() {
    var tab by remember { mutableStateOf("Home") }
    var dark by remember { mutableStateOf(true) }
    val bg = if (dark) Background else Color(0xFFF3F4EF)
    val panel = if (dark) SurfaceOne else Color.White
    val primaryText = if (dark) Color.White else Color(0xFF202720)
    MaterialTheme(colorScheme = if (dark) darkColorScheme(
        background = bg, surface = panel, primary = Mint, onBackground = primaryText, onSurface = primaryText
    ) else lightColorScheme(
        background = bg, surface = panel, primary = Color(0xFF426B50), onBackground = primaryText, onSurface = primaryText
    )) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = panel, tonalElevation = 0.dp) {
                    listOf(
                        Triple("Home", Icons.Rounded.Home, "Home"),
                        Triple("Videos", Icons.Rounded.OndemandVideo, "Videos"),
                        Triple("Music", Icons.Rounded.MusicNote, "Music"),
                        Triple("Library", Icons.Rounded.LibraryMusic, "Library")
                    ).forEach { item ->
                        NavigationBarItem(
                            selected = tab == item.first,
                            onClick = { tab = item.first },
                            icon = { Icon(item.second, contentDescription = item.third) },
                            label = { Text(item.third, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Mint, selectedTextColor = Mint,
                                indicatorColor = Color(0xFF35463A),
                                unselectedIconColor = TextMuted, unselectedTextColor = TextMuted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (tab) {
                "Home" -> HomeScreen(Modifier.padding(padding), panel, primaryText) { tab = it }
                "Videos" -> CollectionScreen("Your videos", "A personal cinema, ready when you are.", Icons.Rounded.Movie, panel, primaryText)
                "Music" -> CollectionScreen("Your music", "Albums, artists and playlists in one place.", Icons.Rounded.GraphicEq, panel, primaryText)
                else -> LibraryScreen(panel, primaryText) { dark = !dark }
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, panel: Color, textColor: Color, navigate: (String) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NOVRA", color = textColor, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text("Everything you love to play.", color = TextMuted, fontSize = 11.sp)
            }
            IconButton(onClick = { }) { Icon(Icons.Rounded.Search, "Search", tint = textColor) }
            IconButton(onClick = { navigate("Library") }) { Icon(Icons.Rounded.Tune, "Personalize", tint = textColor) }
        }
        Spacer(Modifier.height(26.dp))
        Text("MADE FOR YOUR MOMENT", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(7.dp))
        Text("Your world.\nYour soundtrack.", color = textColor, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("For you", "Videos", "Music", "Albums", "Playlists", "Recently added").forEachIndexed { index, label ->
                Surface(
                    color = if (index == 0) Mint else panel,
                    shape = CircleShape,
                    modifier = Modifier.clickable { if (label == "Videos") navigate("Videos") else if (label == "Music") navigate("Music") }
                ) {
                    Text(label, color = if (index == 0) Color(0xFF172019) else textColor, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorialCard("WATCH", "A little escape", "Your videos,\nbeautifully arranged", Icons.Rounded.PlayCircle, Brush.linearGradient(listOf(Color(0xFF536E61), Color(0xFF293C35))), Modifier.weight(1f)) { navigate("Videos") }
            EditorialCard("LISTEN", "Find your flow", "Every track has\na place", Icons.Rounded.GraphicEq, Brush.linearGradient(listOf(Color(0xFF79634D), Color(0xFF3C3028))), Modifier.weight(1f)) { navigate("Music") }
        }
        Spacer(Modifier.height(27.dp))
        SectionHeading("Continue listening", "YOUR RECENT", textColor)
        Spacer(Modifier.height(12.dp))
        WideFeature(panel, textColor, "Your listening history", "Recently played tracks will appear here", Icons.Rounded.History)
        Spacer(Modifier.height(25.dp))
        SectionHeading("Made for your library", "EXPLORE", textColor)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniTile("Albums", Icons.Rounded.Album, panel, textColor, Modifier.weight(1f)) { navigate("Music") }
            MiniTile("Playlists", Icons.Rounded.QueueMusic, panel, textColor, Modifier.weight(1f)) { navigate("Library") }
            MiniTile("Favorites", Icons.Rounded.FavoriteBorder, panel, textColor, Modifier.weight(1f)) { navigate("Library") }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun EditorialCard(tag: String, title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, brush: Brush, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(23.dp)).background(brush).clickable(onClick = onClick).padding(15.dp)) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(tag, color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun SectionHeading(title: String, overline: String, color: Color) {
    Column {
        Text(overline, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(3.dp))
        Text(title, color = color, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WideFeature(panel: Color, textColor: Color, title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(panel).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(15.dp)).background(SurfaceTwo), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Mint, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = TextMuted, fontSize = 10.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = TextMuted)
    }
}

@Composable
private fun MiniTile(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, panel: Color, textColor: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(17.dp)).background(panel).clickable(onClick = onClick).padding(13.dp)) {
        Icon(icon, null, tint = Mint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(13.dp))
        Text(title, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CollectionScreen(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, panel: Color, textColor: Color) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Spacer(Modifier.height(20.dp))
        Text(title, color = textColor, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(panel).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = TextMuted)
            Spacer(Modifier.width(10.dp))
            Text("Search your collection", color = TextMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF35463A), Color(0xFF252E29)))), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = Mint, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(13.dp))
                Text("Your collection starts here", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("NOVRA will organize media from your device", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("BROWSE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        listOf("Recently added", "Folders", "Favorites", "Playlists").forEach { label ->
            Row(Modifier.fillMaxWidth().clickable { }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Mint)
                Spacer(Modifier.width(13.dp))
                Text(label, color = textColor, modifier = Modifier.weight(1f), fontSize = 14.sp)
                Icon(Icons.Rounded.ChevronRight, null, tint = TextMuted)
            }
            HorizontalDivider(color = panel)
        }
    }
}

@Composable
private fun LibraryScreen(panel: Color, textColor: Color, toggleTheme: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Your library", color = textColor, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Everything, just the way you like it.", color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(25.dp))
        listOf(
            "Favorites" to Icons.Rounded.FavoriteBorder,
            "Playlists & queues" to Icons.Rounded.QueueMusic,
            "Recently played" to Icons.Rounded.History,
            "Downloads" to Icons.Rounded.Download,
            "Folders" to Icons.Rounded.FolderOpen,
            "Equalizer" to Icons.Rounded.Equalizer,
            "Sleep timer" to Icons.Rounded.Bedtime,
            "Playback settings" to Icons.Rounded.Tune
        ).forEach { (label, icon) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(panel).clickable { }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Mint)
                Spacer(Modifier.width(14.dp))
                Text(label, color = textColor, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, tint = TextMuted)
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = toggleTheme, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Contrast, null)
            Spacer(Modifier.width(9.dp))
            Text("Switch appearance")
        }
    }
}
